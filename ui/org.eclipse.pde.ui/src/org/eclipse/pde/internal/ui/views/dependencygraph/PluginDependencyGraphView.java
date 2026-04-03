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

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.pde.core.plugin.IPluginBase;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.internal.ui.PDEPluginImages;
import org.eclipse.pde.internal.ui.views.dependencygraph.DependencyGraphModel.GraphNode;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/**
 * A view that displays the dependency relationships between workspace plug-ins
 * as a layered graph drawn on an SWT {@link Canvas}.
 * <p>
 * Plug-ins with no workspace dependencies are on the bottom layer. Each higher
 * layer contains plug-ins that depend on those below. Arrows indicate
 * dependency direction (from dependent to dependency).
 * </p>
 * <p>
 * Features:
 * <ul>
 * <li>Scrollable canvas with mouse-wheel zoom</li>
 * <li>Click a node to see import details in the detail panel</li>
 * <li>Toolbar toggle to show only re-exported dependencies</li>
 * </ul>
 */
public class PluginDependencyGraphView extends ViewPart {

	/** The unique view identifier as registered in plugin.xml */
	public static final String VIEW_ID = "org.eclipse.pde.ui.PluginDependencyGraphView"; //$NON-NLS-1$

	private DependencyGraphModel model;
	private DependencyGraphRenderer renderer;

	private ScrolledComposite scrolledComposite;
	private Canvas canvas;
	private Text detailText;

	private boolean showReexportOnly;
	private Action toggleReexportAction;
	private Action zoomInAction;
	private Action zoomOutAction;
	private Action refreshAction;

	@Override
	public void createPartControl(Composite parent) {
		model = new DependencyGraphModel();
		renderer = new DependencyGraphRenderer(model);

		Composite main = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		main.setLayout(layout);

		createGraphArea(main);
		createDetailPanel(main);
		createActions();
		contributeToToolbar();

		refreshGraph();
	}

	private void createGraphArea(Composite parent) {
		scrolledComposite = new ScrolledComposite(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
		scrolledComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		scrolledComposite.setExpandHorizontal(true);
		scrolledComposite.setExpandVertical(true);

		canvas = new Canvas(scrolledComposite, SWT.DOUBLE_BUFFERED);
		scrolledComposite.setContent(canvas);

		canvas.addPaintListener(this::paintGraph);
		canvas.addMouseListener(new MouseListener() {
			@Override
			public void mouseDown(MouseEvent e) {
				handleClick(e.x, e.y);
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				// reserved
			}

			@Override
			public void mouseUp(MouseEvent e) {
				// no-op
			}
		});
		canvas.addMouseMoveListener(new MouseMoveListener() {
			@Override
			public void mouseMove(MouseEvent e) {
				String hit = renderer.hitTest(e.x, e.y);
				renderer.setHoveredNode(hit);
				canvas.setCursor(hit != null
						? canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
						: null);
				canvas.redraw();
			}
		});

		// Mouse wheel zoom
		canvas.addListener(SWT.MouseVerticalWheel, event -> {
			if ((event.stateMask & SWT.MOD1) != 0) {
				float delta = event.count > 0 ? 0.1f : -0.1f;
				renderer.setZoom(renderer.getZoom() + delta);
				updateCanvasSize();
				canvas.redraw();
				event.doit = false;
			}
		});
	}

	private void createDetailPanel(Composite parent) {
		Composite detailComposite = new Composite(parent, SWT.NONE);
		GridData gd = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
		gd.heightHint = 120;
		detailComposite.setLayoutData(gd);
		detailComposite.setLayout(new FillLayout());

		Label separator = new Label(detailComposite, SWT.SEPARATOR | SWT.HORIZONTAL);
		separator.setVisible(true);

		detailComposite.setLayout(new GridLayout(1, false));

		Label label = new Label(detailComposite, SWT.NONE);
		label.setText(Messages.PluginDependencyGraphView_detailLabel);
		label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		detailText = new Text(detailComposite, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
		detailText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
	}

	private void createActions() {
		toggleReexportAction = new Action(Messages.PluginDependencyGraphView_showReexportOnly, IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				showReexportOnly = isChecked();
				refreshGraph();
			}
		};
		toggleReexportAction.setToolTipText(Messages.PluginDependencyGraphView_showReexportOnlyTooltip);
		toggleReexportAction.setImageDescriptor(PDEPluginImages.DESC_REQ_PLUGINS_OBJ);

		zoomInAction = new Action(Messages.PluginDependencyGraphView_zoomIn) {
			@Override
			public void run() {
				renderer.setZoom(renderer.getZoom() + 0.1f);
				updateCanvasSize();
				canvas.redraw();
			}
		};
		zoomInAction.setToolTipText(Messages.PluginDependencyGraphView_zoomInTooltip);

		zoomOutAction = new Action(Messages.PluginDependencyGraphView_zoomOut) {
			@Override
			public void run() {
				renderer.setZoom(renderer.getZoom() - 0.1f);
				updateCanvasSize();
				canvas.redraw();
			}
		};
		zoomOutAction.setToolTipText(Messages.PluginDependencyGraphView_zoomOutTooltip);

		refreshAction = new Action(Messages.PluginDependencyGraphView_refresh) {
			@Override
			public void run() {
				refreshGraph();
			}
		};
		refreshAction.setToolTipText(Messages.PluginDependencyGraphView_refreshTooltip);
	}

	private void contributeToToolbar() {
		IToolBarManager mgr = getViewSite().getActionBars().getToolBarManager();
		mgr.add(toggleReexportAction);
		mgr.add(new Separator());
		mgr.add(zoomInAction);
		mgr.add(zoomOutAction);
		mgr.add(new Separator());
		mgr.add(refreshAction);
	}

	private void refreshGraph() {
		model.compute(showReexportOnly);
		renderer.setSelectedNode(null);
		updateCanvasSize();
		if (detailText != null && !detailText.isDisposed()) {
			detailText.setText(Messages.PluginDependencyGraphView_selectPlugin);
		}
		if (canvas != null && !canvas.isDisposed()) {
			canvas.redraw();
		}
	}

	private void updateCanvasSize() {
		Point size = renderer.computeSize();
		// Ensure at least the scroll area is filled
		Point parentSize = scrolledComposite.getSize();
		int w = Math.max(size.x, parentSize.x);
		int h = Math.max(size.y, parentSize.y);
		canvas.setSize(w, h);
		scrolledComposite.setMinSize(w, h);
	}

	private void paintGraph(PaintEvent e) {
		renderer.render(e.gc, canvas.getClientArea());
	}

	private void handleClick(int x, int y) {
		String hitId = renderer.hitTest(x, y);
		renderer.setSelectedNode(hitId);
		canvas.redraw();

		if (hitId == null) {
			detailText.setText(Messages.PluginDependencyGraphView_selectPlugin);
			return;
		}

		GraphNode node = model.getNodes().get(hitId);
		if (node == null) {
			return;
		}

		StringBuilder sb = new StringBuilder();
		IPluginModelBase pluginModel = node.model();
		IPluginBase base = pluginModel.getPluginBase();
		sb.append(Messages.PluginDependencyGraphView_pluginId).append(base.getId()).append('\n');
		sb.append(Messages.PluginDependencyGraphView_version).append(base.getVersion()).append('\n');
		sb.append(Messages.PluginDependencyGraphView_layer).append(model.getLayer(hitId)).append('\n');
		sb.append('\n');
		sb.append(Messages.PluginDependencyGraphView_importsHeader).append('\n');

		IPluginImport[] imports = base.getImports();
		if (imports == null || imports.length == 0) {
			sb.append(Messages.PluginDependencyGraphView_noImports);
		} else {
			for (IPluginImport imp : imports) {
				sb.append("  "); //$NON-NLS-1$
				sb.append(imp.getId());
				if (imp.getVersion() != null && !imp.getVersion().isEmpty()) {
					sb.append(" [").append(imp.getVersion()).append(']'); //$NON-NLS-1$
				}
				if (imp.isReexported()) {
					sb.append(Messages.PluginDependencyGraphView_reexported);
				}
				if (imp.isOptional()) {
					sb.append(Messages.PluginDependencyGraphView_optional);
				}
				sb.append('\n');
			}
		}

		detailText.setText(sb.toString());
	}

	@Override
	public void setFocus() {
		if (canvas != null && !canvas.isDisposed()) {
			canvas.setFocus();
		}
	}

	@Override
	public void dispose() {
		if (renderer != null) {
			renderer.dispose();
		}
		super.dispose();
	}
}
