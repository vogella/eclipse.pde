/*******************************************************************************
 *  Copyright (c) 2005, 2025 IBM Corporation and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *     Johannes Ahlers <Johannes.Ahlers@gmx.de> - bug 477677
 *     Christoph Läubrich - Use bnd analyzer to compute required packages
 *******************************************************************************/
package org.eclipse.pde.internal.ui.search.dependencies;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.osgi.container.namespaces.EquinoxModuleDataNamespace;
import org.eclipse.osgi.service.resolver.BundleDescription;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.ClasspathUtilCore;
import org.eclipse.pde.internal.core.bnd.PdeProjectAnalyzer;
import org.eclipse.pde.internal.core.ibundle.IBundle;
import org.eclipse.pde.internal.core.ibundle.IBundleModel;
import org.eclipse.pde.internal.core.ibundle.IBundlePluginModelBase;
import org.eclipse.pde.internal.core.ibundle.IManifestHeader;
import org.eclipse.pde.internal.core.plugin.PluginImport;
import org.eclipse.pde.internal.core.search.PluginJavaSearchUtil;
import org.eclipse.pde.internal.core.text.bundle.ExportPackageHeader;
import org.eclipse.pde.internal.core.text.bundle.ImportPackageHeader;
import org.eclipse.pde.internal.core.text.bundle.ImportPackageObject;
import org.eclipse.pde.internal.ui.PDEUIMessages;
import org.eclipse.pde.internal.ui.util.TextUtil;
import org.osgi.framework.Constants;

import aQute.bnd.osgi.Descriptors.PackageRef;
import aQute.bnd.osgi.Packages;

public class GatherUnusedDependenciesOperation implements IRunnableWithProgress {

	static class Requestor extends SearchRequestor {
		boolean fFound = false;

		@Override
		public void acceptSearchMatch(SearchMatch match) throws CoreException {
			fFound = true;
		}

		public boolean foundMatches() {
			return fFound;
		}
	}

	private final IPluginModelBase fModel;
	private List<Object> fList;

	public GatherUnusedDependenciesOperation(IPluginModelBase model) {
		fModel = model;
	}

	@Override
	public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
		if (!ClasspathUtilCore.hasBundleStructure(fModel)) {
			return;
		}
		Set<String> computedPackages;
		try (PdeProjectAnalyzer analyzer = new PdeProjectAnalyzer(fModel.getUnderlyingResource().getProject(), true)) {
			analyzer.setImportPackage("*"); //$NON-NLS-1$
			analyzer.calcManifest();
			Packages imports = analyzer.getImports();
			if (imports == null) {
				computedPackages = Set.of();
			} else {
				computedPackages = imports.keySet().stream().map(PackageRef::getFQN).collect(Collectors.toSet());
			}
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			throw new InvocationTargetException(e);
		}
		ImportPackageObject[] packages = null;
		IBundle bundle = ((IBundlePluginModelBase) fModel).getBundleModel().getBundle();
		IManifestHeader header = bundle.getManifestHeader(Constants.IMPORT_PACKAGE);
		if (header instanceof ImportPackageHeader) {
			packages = ((ImportPackageHeader) header).getPackages();
		} else if (header != null && header.getValue() != null) {
			header = new ImportPackageHeader(Constants.IMPORT_PACKAGE, header.getValue(), bundle,
					TextUtil.getDefaultLineDelimiter());
			packages = ((ImportPackageHeader) header).getPackages();
		}
		Collection<String> exportedPackages = getExportedPackages(fModel);
		IPluginImport[] imports = fModel.getPluginBase().getImports();

		int totalWork = imports.length * 3 + (packages != null ? packages.length : 0) + 1;
		SubMonitor subMonitor = SubMonitor.convert(monitor, totalWork);

		HashMap<String, IPluginImport> usedPlugins = new HashMap<>();
		fList = new ArrayList<>();
		for (IPluginImport pluginImport : imports) {
			if (subMonitor.isCanceled()) {
				return;
			}
			if (isUnused(pluginImport, computedPackages, subMonitor.split(3))) {
				fList.add(pluginImport);
			} else {
				usedPlugins.put(pluginImport.getId(), pluginImport);
			}
			updateMonitor(subMonitor, fList.size());
		}

		List<ImportPackageObject> usedPackages = new ArrayList<>();
		if (packages != null && !subMonitor.isCanceled()) {
			for (ImportPackageObject importPackage : packages) {
				if (subMonitor.isCanceled()) {
					return;
				}
				if (isUnused(importPackage, exportedPackages, computedPackages, subMonitor.split(1))) {
					fList.add(importPackage);
					updateMonitor(subMonitor, fList.size());
				} else {
					usedPackages.add(importPackage);
				}
			}
		}
		if (subMonitor.isCanceled()) {
			return;
		}
		minimizeDependencies(usedPlugins, usedPackages, subMonitor);
		removeBuddies();
		removeReexported();
	}

	private static Collection<String> getExportedPackages(IPluginModelBase model) {
		if (model instanceof IBundlePluginModelBase plugin) {
			IBundleModel bundleModel = plugin.getBundleModel();
			if (bundleModel != null) {
				IBundle bundle = bundleModel.getBundle();
				IManifestHeader header = bundle.getManifestHeader(Constants.EXPORT_PACKAGE);
				if (header instanceof ExportPackageHeader pkg) {
					return pkg.getPackageNames();
				} else if (header != null && header.getValue() != null) {
					ExportPackageHeader pkg = new ExportPackageHeader(Constants.EXPORT_PACKAGE, header.getValue(),
							bundle, TextUtil.getDefaultLineDelimiter());
					return pkg.getPackageNames();
				}
			}
		}
		BundleDescription bundleDescription = model.getBundleDescription();
		if (bundleDescription != null) {
			return Arrays.stream(bundleDescription.getExportPackages()).map(ExportPackageDescription::getName).toList();
		}
		return List.of();
	}

	protected void removeBuddies() {
		if (fModel instanceof IBundlePluginModelBase plugin) {
			IBundleModel bundleModel = plugin.getBundleModel();
			if (bundleModel != null) {
				IManifestHeader header = bundleModel.getBundle()
						.getManifestHeader(EquinoxModuleDataNamespace.REGISTERED_BUDDY_HEADER);
				if (header != null) {
					String values = header.getValue();
					String[] registerBud = values.split("\\s*,\\s*"); //$NON-NLS-1$
					List<Object> found = new ArrayList<>();
					for (String string : registerBud) {
						for (Object obj : fList) {
							if (obj instanceof PluginImport) {
								String id = ((PluginImport) obj).getId();
								if (string.equals(id)) {
									found.add(obj);
								}
							}
						}
					}
					fList.removeAll(found);
				}
			}
		}
	}

	private void removeReexported() {
		List<Object> found = new ArrayList<>();
		for (Object obj : fList) {
			if (obj instanceof PluginImport plugin) {
				if (plugin.isReexported()) {
					found.add(plugin);
				}
			}
		}
		fList.removeAll(found);
	}

	private void updateMonitor(IProgressMonitor monitor, int size) {
		monitor.setTaskName(PDEUIMessages.UnusedDependencies_analyze + size + " " //$NON-NLS-1$
				+ PDEUIMessages.UnusedDependencies_unused + " " //$NON-NLS-1$
				+ (size == 1 ? PDEUIMessages.DependencyExtent_singular : PDEUIMessages.DependencyExtent_plural) + " " //$NON-NLS-1$
				+ PDEUIMessages.DependencyExtent_found);
	}

	private boolean isUnused(IPluginImport plugin, Set<String> computedPackages, IProgressMonitor monitor) {
		IPluginModelBase[] models = PluginJavaSearchUtil.getPluginImports(plugin);
		for (IPluginModelBase model : models) {
			Collection<String> exportedPackages = getExportedPackages(model);
			for (String exportedPackage : exportedPackages) {
				if (computedPackages.contains(exportedPackage)) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean isUnused(ImportPackageObject pkg, Collection<String> exportedPackages, Set<String> computedPackages,
			IProgressMonitor monitor) {
		if (exportedPackages != null && exportedPackages.contains(pkg.getValue())) {
			return false;
		}
		String name = pkg.getName();
		return !computedPackages.contains(name);
	}

	public List<Object> getList() {
		return fList;
	}

	public static void removeDependencies(IPluginModelBase model, Object[] elements) {
		ImportPackageHeader pkgHeader = null;
		for (Object element : elements) {
			if (element instanceof IPluginImport) {
				try {
					model.getPluginBase().remove((IPluginImport) element);
				} catch (CoreException e) {
				}
			} else if (element instanceof ImportPackageObject) {
				if (pkgHeader == null) {
					pkgHeader = (ImportPackageHeader) ((ImportPackageObject) element).getHeader();
				}
				pkgHeader.removePackage((ImportPackageObject) element);
			}
		}
	}

	private void minimizeDependencies(Map<String, IPluginImport> usedPlugins, List<ImportPackageObject> usedPackages,
			IProgressMonitor monitor) {
		ListIterator<ImportPackageObject> li = usedPackages.listIterator();
		while (li.hasNext()) {
			ImportPackageObject ipo = li.next();
			String bundle = ipo.getAttribute(Constants.BUNDLE_SYMBOLICNAME_ATTRIBUTE);
			if (usedPlugins.containsKey(bundle)) {
				fList.add(ipo);
			}
		}

		Iterator<String> it = usedPlugins.keySet().iterator();
		ArrayDeque<String> plugins = new ArrayDeque<>();
		while (it.hasNext()) {
			plugins.push(it.next().toString());
		}
		SubMonitor subMonitor = SubMonitor.convert(monitor);
		while (!(plugins.isEmpty())) {
			String pluginId = plugins.pop();
			IPluginModelBase base = PluginRegistry.findModel(pluginId);
			if (base == null) {
				continue;
			}
			IPluginImport[] imports = base.getPluginBase().getImports();
			SubMonitor iterationMonitor = subMonitor.setWorkRemaining(Math.max(plugins.size() + 1, 5)).split(1)
					.setWorkRemaining(imports.length);
			for (IPluginImport imp : imports) {
				if (imp.isReexported()) {
					String reExportedId = imp.getId();
					if (reExportedId != null && reExportedId.equals(pluginId)) {
						continue;
					}
					Object pluginImport = usedPlugins.remove(imp.getId());
					if (pluginImport != null) {
						fList.add(pluginImport);
						updateMonitor(iterationMonitor, fList.size());
					}
					plugins.push(reExportedId);
				}
				iterationMonitor.worked(1);
			}
		}
	}
}
