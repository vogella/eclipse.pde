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
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.pde.core.plugin.IPluginBase;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.internal.ui.PDEPluginImages;
import org.eclipse.pde.internal.ui.editor.plugin.DependenciesPage;
import org.eclipse.pde.internal.ui.editor.plugin.ManifestEditor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.pde.internal.ui.views.dependencygraph.DependencyGraphModel.GraphNode;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Point;
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
 * Interactions:
 * <ul>
 * <li>Click a node to see its import details in the detail panel</li>
 * <li>Double-click a node to open its MANIFEST.MF in the editor</li>
 * <li>Hover a node to highlight its direct dependency chain</li>
 * <li>Right-click a node for context menu actions</li>
 * <li>Drag to pan the canvas; mouse wheel to zoom</li>
 * <li>Type in the filter field to highlight matching plug-ins</li>
 * </ul>
 */
public class PluginDependencyGraphView extends ViewPart {

	/** The unique view identifier as registered in plugin.xml */
	public static final String VIEW_ID = "org.eclipse.pde.ui.PluginDependencyGraphView"; //$NON-NLS-1$

	private DependencyGraphModel model;
	private DependencyGraphRenderer renderer;

	private ScrolledComposite scrolledComposite;
	private Canvas canvas;
	private Text searchText;
	private Text detailText;

	private boolean showReexportOnly;
	private Action toggleReexportAction;
	private Action zoomInAction;
	private Action zoomOutAction;
	private Action refreshAction;
	private Action clearFilterAction;

	// Pan state
	private boolean panActive;
	private boolean panDragged;
	private int panStartMouseX, panStartMouseY;
	private int panStartScrollX, panStartScrollY;

	// Last right-click position (canvas coordinates) for the context menu
	private int lastRightClickX, lastRightClickY;

	// Track last known viewport size so we only re-zoom on actual resizes
	private int lastViewportWidth, lastViewportHeight;

	@Override
	public void createPartControl(Composite parent) {
		model = new DependencyGraphModel();
		renderer = new DependencyGraphRenderer(model);

		Composite main = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		layout.verticalSpacing = 0;
		main.setLayout(layout);

		createSearchBar(main);
		createGraphArea(main);
		createDetailPanel(main);
		createActions();
		createContextMenu();
		contributeToToolbar();

		refreshGraph();
	}

	// ---- Search bar ------------------------------------------------------------

	private void createSearchBar(Composite parent) {
		Composite bar = new Composite(parent, SWT.NONE);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout gl = new GridLayout(2, false);
		gl.marginHeight = 3;
		gl.marginWidth = 6;
		bar.setLayout(gl);

		Label lbl = new Label(bar, SWT.NONE);
		lbl.setText(Messages.PluginDependencyGraphView_filterLabel);

		searchText = new Text(bar, SWT.SEARCH | SWT.ICON_CANCEL | SWT.ICON_SEARCH);
		searchText.setMessage(Messages.PluginDependencyGraphView_searchPlaceholder);
		searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		searchText.addModifyListener(e -> {
			renderer.setFilterText(searchText.getText());
			if (canvas != null && !canvas.isDisposed()) {
				canvas.redraw();
			}
		});
	}

	// ---- Graph canvas ----------------------------------------------------------

	private void createGraphArea(Composite parent) {
		scrolledComposite = new ScrolledComposite(parent, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
		scrolledComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		scrolledComposite.setExpandHorizontal(true);
		scrolledComposite.setExpandVertical(true);
		scrolledComposite.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

		canvas = new Canvas(scrolledComposite, SWT.DOUBLE_BUFFERED);
		canvas.setBackground(canvas.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
		scrolledComposite.setContent(canvas);

		canvas.addPaintListener(this::paintGraph);

		canvas.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseDown(MouseEvent e) {
				if (e.button == 1) {
					panActive = true;
					panDragged = false;
					panStartMouseX = e.x;
					panStartMouseY = e.y;
					Point origin = scrolledComposite.getOrigin();
					panStartScrollX = origin.x;
					panStartScrollY = origin.y;
				} else if (e.button == 3) {
					lastRightClickX = e.x;
					lastRightClickY = e.y;
				}
			}

			@Override
			public void mouseUp(MouseEvent e) {
				if (e.button == 1) {
					panActive = false;
					if (!panDragged) {
						handleClick(e.x, e.y);
					}
					// Restore hand cursor if still over a node
					String hit = renderer.hitTest(e.x, e.y);
					canvas.setCursor(hit != null
							? canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
							: null);
				}
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				if (e.button == 1) {
					String hitId = renderer.hitTest(e.x, e.y);
					if (hitId != null) {
						openManifest(hitId);
					}
				}
			}
		});

		canvas.addMouseMoveListener(e -> {
			if (panActive) {
				int dx = e.x - panStartMouseX;
				int dy = e.y - panStartMouseY;
				if (!panDragged && (Math.abs(dx) > 3 || Math.abs(dy) > 3)) {
					panDragged = true;
				}
				if (panDragged) {
					scrolledComposite.setOrigin(
							Math.max(0, panStartScrollX - dx),
							Math.max(0, panStartScrollY - dy));
					canvas.setCursor(canvas.getDisplay().getSystemCursor(SWT.CURSOR_SIZEALL));
					return;
				}
			}
			String hit = renderer.hitTest(e.x, e.y);
			renderer.setHoveredNode(hit);
			canvas.setCursor(hit != null
					? canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
					: null);
			canvas.redraw();
		});

		// Mouse wheel zooms in/out; use scrollbars (or drag) for panning
		canvas.addListener(SWT.MouseVerticalWheel, event -> {
			float delta = event.count > 0 ? 0.1f : -0.1f;
			renderer.setZoom(renderer.getZoom() + delta);
			updateCanvasSize();
			canvas.redraw();
			event.doit = false;
		});

		// Re-fit zoom when view is actually resized (not on scroll)
		scrolledComposite.addListener(SWT.Resize, event -> {
			Point size = scrolledComposite.getSize();
			if (size.x != lastViewportWidth || size.y != lastViewportHeight) {
				lastViewportWidth = size.x;
				lastViewportHeight = size.y;
				zoomToFit();
				updateCanvasSize();
				canvas.redraw();
			}
		});
	}

	// ---- Detail panel ----------------------------------------------------------

	private void createDetailPanel(Composite parent) {
		Composite detailComposite = new Composite(parent, SWT.NONE);
		GridData gd = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
		gd.heightHint = 200;
		detailComposite.setLayoutData(gd);
		detailComposite.setLayout(new GridLayout(1, false));

		Label separator = new Label(detailComposite, SWT.SEPARATOR | SWT.HORIZONTAL);
		separator.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		Label label = new Label(detailComposite, SWT.NONE);
		label.setText(Messages.PluginDependencyGraphView_detailLabel);
		label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

		detailText = new Text(detailComposite, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
		detailText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
	}

	// ---- Actions ---------------------------------------------------------------

	private void createActions() {
		toggleReexportAction = new Action(Messages.PluginDependencyGraphView_showReexportOnly,
				IAction.AS_CHECK_BOX) {
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

		clearFilterAction = new Action(Messages.PluginDependencyGraphView_clearFilter) {
			@Override
			public void run() {
				clearFilter();
			}
		};
		clearFilterAction.setToolTipText(Messages.PluginDependencyGraphView_clearFilterTooltip);
	}

	private void createContextMenu() {
		MenuManager menuMgr = new MenuManager();
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(mgr -> {
			String hitId = renderer.hitTest(lastRightClickX, lastRightClickY);
			if (hitId != null) {
				mgr.add(new Action(Messages.PluginDependencyGraphView_openManifest) {
					@Override
					public void run() {
						openManifest(hitId);
					}
				});
				mgr.add(new Separator());
				mgr.add(new Action(Messages.PluginDependencyGraphView_showDepsOf) {
					@Override
					public void run() {
						model.setFocus(hitId, true);
						clearSearchText();
						refreshGraph();
					}
				});
				mgr.add(new Action(Messages.PluginDependencyGraphView_showDependentsOf) {
					@Override
					public void run() {
						model.setFocus(hitId, false);
						clearSearchText();
						refreshGraph();
					}
				});
				if (model.hasFocus()) {
					mgr.add(new Separator());
					mgr.add(new Action(Messages.PluginDependencyGraphView_clearFilter) {
						@Override
						public void run() {
							clearFilter();
						}
					});
				}
			} else if (model.hasFocus()
					|| (searchText != null && !searchText.getText().isEmpty())) {
				mgr.add(new Action(Messages.PluginDependencyGraphView_clearFilter) {
					@Override
					public void run() {
						clearFilter();
					}
				});
			}
		});
		canvas.setMenu(menuMgr.createContextMenu(canvas));
	}

	private void contributeToToolbar() {
		IToolBarManager mgr = getViewSite().getActionBars().getToolBarManager();
		mgr.add(toggleReexportAction);
		mgr.add(new Separator());
		mgr.add(zoomInAction);
		mgr.add(zoomOutAction);
		mgr.add(new Separator());
		mgr.add(clearFilterAction);
		mgr.add(refreshAction);
	}

	// ---- Graph lifecycle -------------------------------------------------------

	private void refreshGraph() {
		model.compute(showReexportOnly);
		renderer.setSelectedNode(null);
		zoomToFit();
		updateCanvasSize();
		if (detailText != null && !detailText.isDisposed()) {
			detailText.setText(Messages.PluginDependencyGraphView_selectPlugin);
		}
		if (canvas != null && !canvas.isDisposed()) {
			canvas.redraw();
		}
	}

	private void zoomToFit() {
		if (scrolledComposite == null || scrolledComposite.isDisposed()) {
			return;
		}
		Point viewportSize = scrolledComposite.getSize();
		if (viewportSize.x > 0 && viewportSize.y > 0) {
			renderer.setZoom(renderer.computeZoomToFit(viewportSize.x, viewportSize.y));
		}
	}

	private void updateCanvasSize() {
		Point size = renderer.computeSize();
		Point parentSize = scrolledComposite.getSize();
		canvas.setSize(Math.max(size.x, parentSize.x), Math.max(size.y, parentSize.y));
		scrolledComposite.setMinSize(size.x, size.y);
	}

	private void paintGraph(PaintEvent e) {
		renderer.render(e.gc, canvas.getClientArea());
	}

	// ---- Interaction handlers --------------------------------------------------

	private void handleClick(int x, int y) {
		String hitId = renderer.hitTest(x, y);
		renderer.setSelectedNode(hitId);
		canvas.redraw();

		if (hitId == null) {
			detailText.setText(Messages.PluginDependencyGraphView_selectPlugin);
			return;
		}

		GraphNode node = model.getNodes().get(hitId);
		if (node == null) return;

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
				sb.append("  ").append(imp.getId()); //$NON-NLS-1$
				if (imp.getVersion() != null && !imp.getVersion().isEmpty()) {
					sb.append(" [").append(imp.getVersion()).append(']'); //$NON-NLS-1$
				}
				if (imp.isReexported()) sb.append(Messages.PluginDependencyGraphView_reexported);
				if (imp.isOptional()) sb.append(Messages.PluginDependencyGraphView_optional);
				sb.append('\n');
			}
		}
		detailText.setText(sb.toString());
	}

	private void openManifest(String pluginId) {
		GraphNode node = model.getNodes().get(pluginId);
		if (node == null) return;
		IEditorPart editor = ManifestEditor.openPluginEditor(node.model());
		if (editor instanceof ManifestEditor me) {
			me.setActivePage(DependenciesPage.PAGE_ID);
		}
	}

	private void clearFilter() {
		model.clearFocus();
		clearSearchText();
		refreshGraph();
	}

	private void clearSearchText() {
		if (searchText != null && !searchText.isDisposed() && !searchText.getText().isEmpty()) {
			searchText.setText(""); //$NON-NLS-1$
			// ModifyListener will call renderer.setFilterText("") and redraw
		}
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
