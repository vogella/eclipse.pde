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
package org.eclipse.pde.ui.tests.target;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.equinox.frameworkadmin.BundleInfo;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.target.IUBundleContainer;
import org.eclipse.pde.internal.core.target.P2TargetUtils;
import org.eclipse.pde.internal.core.target.SharedBundlePool;
import org.junit.Test;

/**
 * Tests the bundle pool that is shared between workspaces.
 */
public class SharedBundlePoolTests extends AbstractTargetTest {

	@Test
	public void testPoolIsOutsideWorkspace() {
		assertTrue(P2TargetUtils.isSharedBundlePool());
		assertFalse(PDECore.getDefault().getStateLocation().isPrefixOf(P2TargetUtils.BUNDLE_POOL));
	}

	@Test
	public void testGarbageCollectionKeepsArtifactsOfOtherWorkspaces() throws Exception {
		ITargetDefinition target = getTargetService().newTarget();
		try {
			target.setTargetLocations(new ITargetLocation[] { createContainer("feature.b.feature.group") });
			Map<String, File> all = resolveFiles(target);
			assertEquals(6, all.size());
			for (File file : all.values()) {
				assertTrue(file + " outside of the shared pool",
						file.toPath().startsWith(P2TargetUtils.BUNDLE_POOL.toPath()));
			}

			target.setTargetLocations(new ITargetLocation[] { createContainer("feature.a.feature.group") });
			assertEquals(3, resolveFiles(target).size());

			Path usageDir = P2TargetUtils.BUNDLE_POOL.toPath().resolve(".pde-workspaces");
			Path otherWorkspace = Files.createTempDirectory("otherWorkspace");
			Path otherUsage = usageDir.resolve("other.txt");
			Files.write(otherUsage, List.of("workspace=" + otherWorkspace, "osgi.bundle,bundle.b1,1.0.0"),
					StandardCharsets.UTF_8);
			Path goneUsage = usageDir.resolve("gone.txt");
			Files.write(goneUsage, List.of("workspace=" + otherWorkspace.resolve("gone"), "osgi.bundle,bundle.b2,1.0.0"),
					StandardCharsets.UTF_8);
			long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
			for (File file : all.values()) {
				file.setLastModified(old);
			}

			// ignore the profiles that other tests left behind
			SharedBundlePool.garbageCollect(P2TargetUtils.getProfileId(target)::equals);

			assertTrue(all.get("bundle.a1").exists());
			assertTrue("used by another workspace", all.get("bundle.b1").exists());
			assertFalse("only used by a deleted workspace", all.get("bundle.b2").exists());
			assertFalse("unused", all.get("bundle.b3").exists());
			assertTrue(Files.exists(otherUsage));
			assertFalse(Files.exists(goneUsage));
			try (Stream<Path> files = Files.list(usageDir)) {
				assertEquals(2, files.count());
			}
			Files.delete(otherUsage);
			Files.delete(otherWorkspace);
		} finally {
			P2TargetUtils.deleteProfile(target.getHandle());
		}
	}

	private IUBundleContainer createContainer(String unitId) throws Exception {
		URI uri = IUBundleContainerTests.getURI("/tests/sites/site.a.b");
		IMetadataRepository repository = P2TargetUtils.getRepoManager().loadRepository(uri, null);
		IInstallableUnit unit = repository.query(QueryUtil.createIUQuery(unitId), null).iterator().next();
		return (IUBundleContainer) getTargetService().newIULocation(new IInstallableUnit[] { unit },
				new URI[] { uri }, IUBundleContainer.INCLUDE_REQUIRED);
	}

	private Map<String, File> resolveFiles(ITargetDefinition target) throws Exception {
		target.resolve(null);
		Map<String, File> files = new HashMap<>();
		for (BundleInfo info : getAllBundleInfos(target)) {
			files.put(info.getSymbolicName(), new File(info.getLocation()));
		}
		return files;
	}
}
