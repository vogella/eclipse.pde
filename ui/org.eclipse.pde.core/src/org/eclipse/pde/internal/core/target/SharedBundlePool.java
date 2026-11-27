/*******************************************************************************
 * Copyright (c) 2026 vogella GmbH and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Lars Vogel - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.core.target;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.UserScope;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.ArtifactKeyQuery;
import org.eclipse.equinox.p2.repository.artifact.IFileArtifactRepository;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.util.CoreUtility;

/**
 * Bundle pool for p2 based target platforms that is shared by all workspaces
 * of the user, by default in {@code ~/.eclipse/p2-pool}.
 * <p>
 * Each workspace records the artifacts its target profiles use in the pool, so
 * that garbage collection in one workspace keeps the artifacts of the others.
 * A file lock in the pool keeps garbage collection from running while any
 * workspace provisions into the pool.
 * </p>
 */
public final class SharedBundlePool {

	/** User scope preference enabling the shared pool, {@code true} by default. */
	public static final String PREF_SHARED = "bundlePool.shared"; //$NON-NLS-1$

	/** User scope preference with the directory of the shared pool. */
	public static final String PREF_LOCATION = "bundlePool.location"; //$NON-NLS-1$

	/** System property overriding {@link #PREF_SHARED}. */
	private static final String PROP_SHARED = "pde.sharedBundlePool"; //$NON-NLS-1$

	/** System property overriding {@link #PREF_LOCATION}. */
	private static final String PROP_LOCATION = "pde.sharedBundlePool.location"; //$NON-NLS-1$

	private static final String USAGE_DIR = ".pde-workspaces"; //$NON-NLS-1$

	private static final String USAGE_SUFFIX = ".txt"; //$NON-NLS-1$

	private static final String WORKSPACE_PREFIX = "workspace="; //$NON-NLS-1$

	private static final String LOCK_FILE = ".pde-workspaces.lock"; //$NON-NLS-1$

	/**
	 * Artifacts younger than this are kept, in case a workspace provisioned
	 * them but failed to record its usage.
	 */
	private static final long GRACE_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

	/** Orders provisioning and garbage collection within this JVM, where file locks must not overlap. */
	private static final ReadWriteLock LOCK = new ReentrantReadWriteLock();

	private static final Object SHARED_LOCK_MONITOR = new Object();

	private static FileChannel sharedLockChannel;

	private static int sharedLockHolders;

	private SharedBundlePool() {
	}

	public static IEclipsePreferences getPreferences() {
		return UserScope.INSTANCE.getNode(PDECore.PLUGIN_ID);
	}

	public static IPath getDefaultLocation() {
		return UserScope.INSTANCE.getLocation().append("p2-pool"); //$NON-NLS-1$
	}

	public static boolean isEnabled() {
		String property = System.getProperty(PROP_SHARED);
		if (property != null) {
			return Boolean.parseBoolean(property);
		}
		return Platform.getPreferencesService().getBoolean(PDECore.PLUGIN_ID, PREF_SHARED, true,
				new IScopeContext[] { UserScope.INSTANCE });
	}

	public static IPath getLocation() {
		String location = System.getProperty(PROP_LOCATION);
		if (location == null || location.isBlank()) {
			location = Platform.getPreferencesService().getString(PDECore.PLUGIN_ID, PREF_LOCATION, null,
					new IScopeContext[] { UserScope.INSTANCE });
		}
		if (location == null || location.isBlank()) {
			return getDefaultLocation();
		}
		return IPath.fromOSString(location.strip());
	}

	/**
	 * Runs an operation that provisions into the bundle pool and then records
	 * the usage of this workspace, while no garbage collection of the shared
	 * pool can run.
	 */
	static void provision(ICoreRunnable operation, IProgressMonitor monitor) throws CoreException {
		if (!P2TargetUtils.isSharedBundlePool()) {
			operation.run(monitor);
			return;
		}
		LOCK.readLock().lock();
		try {
			acquireSharedLock();
			try {
				operation.run(monitor);
				recordUsage();
			} finally {
				releaseSharedLock();
			}
		} finally {
			LOCK.readLock().unlock();
		}
	}

	private static void acquireSharedLock() throws CoreException {
		synchronized (SHARED_LOCK_MONITOR) {
			if (sharedLockHolders == 0) {
				FileChannel channel = openLockChannel();
				try {
					channel.lock(0, Long.MAX_VALUE, true);
				} catch (IOException e) {
					closeQuietly(channel);
					throw new CoreException(Status.error(e.getMessage(), e));
				}
				sharedLockChannel = channel;
			}
			sharedLockHolders++;
		}
	}

	private static void releaseSharedLock() {
		synchronized (SHARED_LOCK_MONITOR) {
			if (--sharedLockHolders == 0) {
				closeQuietly(sharedLockChannel);
				sharedLockChannel = null;
			}
		}
	}

	private static FileChannel openLockChannel() throws CoreException {
		try {
			Path pool = P2TargetUtils.BUNDLE_POOL.toPath();
			Files.createDirectories(pool);
			return FileChannel.open(pool.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE);
		} catch (IOException e) {
			throw new CoreException(Status.error(e.getMessage(), e));
		}
	}

	private static void closeQuietly(FileChannel channel) {
		try {
			channel.close();
		} catch (IOException e) {
			PDECore.log(e);
		}
	}

	private static void recordUsage() {
		try {
			writeUsage(usageFile(), collectUsedArtifacts(id -> true));
		} catch (CoreException | IOException e) {
			PDECore.log(e);
		}
	}

	/**
	 * Removes the artifacts from the shared pool that no target profile of any
	 * known workspace uses. Workspaces whose metadata no longer exists are
	 * forgotten. Skipped while another workspace provisions into the pool.
	 */
	static void garbageCollect() throws CoreException {
		garbageCollect(id -> true);
	}

	/**
	 * Variant of {@link #garbageCollect()} that only counts the target profiles
	 * of this workspace whose id the filter accepts, for tests.
	 */
	public static void garbageCollect(Predicate<String> profileIds) throws CoreException {
		LOCK.writeLock().lock();
		try (FileChannel channel = openLockChannel()) {
			FileLock lock = channel.tryLock();
			if (lock == null) {
				PDECore.log(Status.info(
						"Bundle pool not cleaned, another workspace is resolving a target platform into it")); //$NON-NLS-1$
				return;
			}
			removeUnused(collectUsedArtifacts(profileIds));
		} catch (IOException e) {
			throw new CoreException(Status.error(e.getMessage(), e));
		} finally {
			LOCK.writeLock().unlock();
		}
	}

	private static void removeUnused(Set<String> inUse) throws CoreException {
		try {
			writeUsage(usageFile(), inUse);
			for (Path file : listUsageFiles()) {
				readUsage(file, inUse);
			}
		} catch (IOException e) {
			// without the complete usage of all workspaces nothing can be removed safely
			PDECore.log(e);
			return;
		}
		IFileArtifactRepository pool = P2TargetUtils.getBundlePool();
		long keepNewerThan = System.currentTimeMillis() - GRACE_PERIOD_MILLIS;
		pool.executeBatch(monitor -> {
			for (IArtifactKey key : pool.query(ArtifactKeyQuery.ALL_KEYS, null)) {
				if (inUse.contains(key.toExternalForm())) {
					continue;
				}
				File file = pool.getArtifactFile(key);
				if (file != null && file.lastModified() > keepNewerThan) {
					continue;
				}
				pool.removeDescriptor(key, new NullProgressMonitor());
			}
		}, new NullProgressMonitor());
		deleteLegacyWorkspacePool();
	}

	private static Set<String> collectUsedArtifacts(Predicate<String> profileIds) throws CoreException {
		Set<String> keys = new TreeSet<>();
		String cache = P2TargetUtils.BUNDLE_POOL.toOSString();
		for (IProfile profile : P2TargetUtils.getProfileRegistry().getProfiles()) {
			if (!profile.getProfileId().startsWith(P2TargetUtils.PROFILE_ID_PREFIX)
					|| !profileIds.test(profile.getProfileId())
					|| !cache.equals(profile.getProperty(IProfile.PROP_CACHE))) {
				continue;
			}
			for (IInstallableUnit unit : profile.query(QueryUtil.createIUAnyQuery(), null)) {
				for (IArtifactKey key : unit.getArtifacts()) {
					keys.add(key.toExternalForm());
				}
			}
		}
		return keys;
	}

	/** Deletes the workspace pool once no profile of this workspace uses it anymore. */
	private static void deleteLegacyWorkspacePool() throws CoreException {
		File legacy = P2TargetUtils.WORKSPACE_BUNDLE_POOL.toFile();
		if (!legacy.isDirectory()) {
			return;
		}
		String legacyPath = P2TargetUtils.WORKSPACE_BUNDLE_POOL.toOSString();
		for (IProfile profile : P2TargetUtils.getProfileRegistry().getProfiles()) {
			if (legacyPath.equals(profile.getProperty(IProfile.PROP_CACHE))) {
				return;
			}
		}
		CoreUtility.deleteContent(legacy);
	}

	private static Path usageDirectory() {
		return P2TargetUtils.BUNDLE_POOL.toPath().resolve(USAGE_DIR);
	}

	private static String workspaceKey() {
		return P2TargetUtils.getAgentDirectory().toAbsolutePath().normalize().toString();
	}

	private static Path usageFile() {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
					.digest(workspaceKey().getBytes(StandardCharsets.UTF_8));
			return usageDirectory().resolve(HexFormat.of().formatHex(hash, 0, 16) + USAGE_SUFFIX);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<Path> listUsageFiles() throws IOException {
		Path directory = usageDirectory();
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(f -> f.getFileName().toString().endsWith(USAGE_SUFFIX)).toList();
		}
	}

	private static void writeUsage(Path file, Set<String> keys) throws IOException {
		List<String> lines = new ArrayList<>(keys.size() + 1);
		lines.add(WORKSPACE_PREFIX + workspaceKey());
		lines.addAll(keys);
		Files.createDirectories(file.getParent());
		Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp"); //$NON-NLS-1$
		try {
			Files.write(temp, lines, StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void readUsage(Path file, Set<String> keys) throws IOException {
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			return; // forgotten by a concurrent garbage collection
		}
		if (lines.isEmpty() || !lines.get(0).startsWith(WORKSPACE_PREFIX)) {
			return;
		}
		Path agentDirectory = Path.of(lines.get(0).substring(WORKSPACE_PREFIX.length()));
		if (!Files.isDirectory(agentDirectory)) {
			Files.deleteIfExists(file);
			return;
		}
		keys.addAll(lines.subList(1, lines.size()));
	}
}
