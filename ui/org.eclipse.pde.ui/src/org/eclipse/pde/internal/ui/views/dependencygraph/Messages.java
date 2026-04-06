/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.pde.internal.ui.views.dependencygraph;

import org.eclipse.osgi.util.NLS;

public class Messages extends NLS {
	private static final String BUNDLE_NAME = "org.eclipse.pde.internal.ui.views.dependencygraph.messages"; //$NON-NLS-1$

	public static String PluginDependencyGraphView_detailLabel;
	public static String PluginDependencyGraphView_showReexportOnly;
	public static String PluginDependencyGraphView_showReexportOnlyTooltip;
	public static String PluginDependencyGraphView_zoomIn;
	public static String PluginDependencyGraphView_zoomInTooltip;
	public static String PluginDependencyGraphView_zoomOut;
	public static String PluginDependencyGraphView_zoomOutTooltip;
	public static String PluginDependencyGraphView_refresh;
	public static String PluginDependencyGraphView_refreshTooltip;
	public static String PluginDependencyGraphView_selectPlugin;
	public static String PluginDependencyGraphView_pluginId;
	public static String PluginDependencyGraphView_version;
	public static String PluginDependencyGraphView_layer;
	public static String PluginDependencyGraphView_importsHeader;
	public static String PluginDependencyGraphView_noImports;
	public static String PluginDependencyGraphView_reexported;
	public static String PluginDependencyGraphView_optional;
	public static String PluginDependencyGraphView_filterLabel;
	public static String PluginDependencyGraphView_searchPlaceholder;
	public static String PluginDependencyGraphView_openManifest;
	public static String PluginDependencyGraphView_showDepsOf;
	public static String PluginDependencyGraphView_showDependentsOf;
	public static String PluginDependencyGraphView_clearFilter;
	public static String PluginDependencyGraphView_clearFilterTooltip;

	static {
		NLS.initializeMessages(BUNDLE_NAME, Messages.class);
	}

	private Messages() {
	}
}
