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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

import org.eclipse.pde.internal.ui.views.dependencygraph.DependencyGraphModel.GraphEdge;
import org.eclipse.pde.internal.ui.views.dependencygraph.DependencyGraphModel.GraphNode;

/**
 * Renders the layered dependency graph onto a GC.
 * <p>
 * Layer 0 (plug-ins with no workspace dependencies) is always at the visual
 * bottom; higher layers stack upward. Each layer's nodes are horizontally
 * centred. Colors adapt to the active SWT theme.
 * </p>
 * <p>
 * When a node is hovered its direct <em>dependencies</em> (nodes it depends on,
 * below it) are highlighted in <strong>teal</strong> and its direct
 * <em>dependents</em> (nodes that depend on it, above it) are highlighted in
 * <strong>amber</strong>. The connecting arrows are coloured to match. All
 * unrelated nodes and edges are dimmed. A live text filter further dims nodes
 * that do not match.
 * </p>
 */
public class DependencyGraphRenderer {

	static final int NODE_WIDTH = 180;
	static final int NODE_HEIGHT = 36;
	static final int H_GAP = 30;
	static final int V_GAP = 60;
	static final int LAYER_LABEL_WIDTH = 40;
	static final int PADDING = 30;
	static final int ARROW_SIZE = 8;

	private final DependencyGraphModel model;
	private final Map<String, Rectangle> nodeBounds = new HashMap<>();
	private float zoom = 1.0f;
	private Font nodeFont;
	private Font layerFont;
	private Font badgeFont;
	private String hoveredNode;
	private String selectedNode;
	private String filterText = ""; //$NON-NLS-1$

	public DependencyGraphRenderer(DependencyGraphModel model) {
		this.model = model;
	}

	public void setZoom(float zoom) {
		this.zoom = Math.max(0.1f, Math.min(3.0f, zoom));
	}

	public float getZoom() {
		return zoom;
	}

	/**
	 * Computes the zoom level that makes the entire graph fit into the given
	 * viewport, capped at 1.0 so the graph is never enlarged beyond its
	 * natural size.
	 */
	public float computeZoomToFit(int viewportWidth, int viewportHeight) {
		Point natural = computeSizeAtZoom1();
		if (natural.x <= 0 || natural.y <= 0) {
			return 1.0f;
		}
		float zx = (float) viewportWidth / natural.x;
		float zy = (float) viewportHeight / natural.y;
		float fit = Math.min(zx, zy);
		return Math.max(0.1f, Math.min(1.0f, fit));
	}

	/** Size at zoom == 1 (no scaling). */
	private Point computeSizeAtZoom1() {
		int maxNodesInLayer = 0;
		int layerCount = model.getLayerCount();
		for (int i = 0; i < layerCount; i++) {
			maxNodesInLayer = Math.max(maxNodesInLayer, model.getNodesOnLayer(i).size());
		}
		int width = LAYER_LABEL_WIDTH + PADDING + maxNodesInLayer * (NODE_WIDTH + H_GAP) + PADDING;
		int height = 2 * PADDING + layerCount * NODE_HEIGHT + Math.max(0, layerCount - 1) * V_GAP;
		return new Point(width, height);
	}

	public void setHoveredNode(String nodeId) {
		this.hoveredNode = nodeId;
	}

	public void setSelectedNode(String nodeId) {
		this.selectedNode = nodeId;
	}

	public String getSelectedNode() {
		return selectedNode;
	}

	public void setFilterText(String text) {
		this.filterText = text == null ? "" : text; //$NON-NLS-1$
	}

	// ---- Size / layout ---------------------------------------------------------

	public Point computeSize() {
		int maxNodesInLayer = 0;
		int layerCount = model.getLayerCount();
		for (int i = 0; i < layerCount; i++) {
			maxNodesInLayer = Math.max(maxNodesInLayer, model.getNodesOnLayer(i).size());
		}
		int width = LAYER_LABEL_WIDTH + PADDING + maxNodesInLayer * (NODE_WIDTH + H_GAP) + PADDING;
		int height = 2 * PADDING + layerCount * NODE_HEIGHT + Math.max(0, layerCount - 1) * V_GAP;
		return new Point(Math.round(width * zoom), Math.round(height * zoom));
	}

	private void layoutNodes(int unscaledW, int unscaledH) {
		nodeBounds.clear();
		int layerCount = model.getLayerCount();
		int usableW = unscaledW - LAYER_LABEL_WIDTH - 2 * PADDING;

		for (int layer = 0; layer < layerCount; layer++) {
			List<String> nodesOnLayer = model.getNodesOnLayer(layer);
			int y = unscaledH - PADDING - NODE_HEIGHT - layer * (NODE_HEIGHT + V_GAP);

			int layerW = nodesOnLayer.size() * NODE_WIDTH
					+ Math.max(0, nodesOnLayer.size() - 1) * H_GAP;
			int startX = LAYER_LABEL_WIDTH + PADDING + Math.max(0, (usableW - layerW) / 2);

			int x = startX;
			for (String nodeId : nodesOnLayer) {
				nodeBounds.put(nodeId, new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT));
				x += NODE_WIDTH + H_GAP;
			}
		}
	}

	public String hitTest(int zoomedX, int zoomedY) {
		int x = Math.round(zoomedX / zoom);
		int y = Math.round(zoomedY / zoom);
		for (Map.Entry<String, Rectangle> entry : nodeBounds.entrySet()) {
			if (entry.getValue().contains(x, y)) return entry.getKey();
		}
		return null;
	}

	// ---- Render entry point ----------------------------------------------------

	public void render(GC gc, Rectangle clientArea) {
		// Fill background immediately to avoid white flash
		gc.setBackground(gc.getDevice().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
		gc.fillRectangle(clientArea);

		int unscaledW = Math.round(clientArea.width / zoom);
		int unscaledH = Math.round(clientArea.height / zoom);
		layoutNodes(unscaledW, unscaledH);
		createFonts(gc);

		gc.setAdvanced(true);
		gc.setAntialias(SWT.ON);
		gc.setTextAntialias(SWT.ON);

		org.eclipse.swt.graphics.Transform t = new org.eclipse.swt.graphics.Transform(gc.getDevice());
		t.scale(zoom, zoom);
		gc.setTransform(t);

		HoverContext ctx = computeHoverContext();
		drawLayerBackgrounds(gc, unscaledH);
		drawEdges(gc, ctx);
		drawNodes(gc, ctx);
		drawLayerLabels(gc, unscaledH);

		gc.setTransform(null);
		t.dispose();
		disposeFonts();
	}

	// ---- Hover / filter context ------------------------------------------------

	/**
	 * Classifies every node into one of four roles relative to the hovered node
	 * and active filter text.
	 *
	 * <ul>
	 * <li>{@code dependencies} — nodes the hovered node directly depends on
	 * (below)</li>
	 * <li>{@code dependents} — nodes that directly depend on the hovered node
	 * (above)</li>
	 * <li>{@code dimmed} — all other nodes (visually faded)</li>
	 * </ul>
	 *
	 * When only a text filter is active (no hover), {@code dependencies} and
	 * {@code dependents} are empty and matching nodes are simply not dimmed.
	 */
	private HoverContext computeHoverContext() {
		boolean hasHover = hoveredNode != null;
		boolean hasFilter = !filterText.isEmpty();
		if (!hasHover && !hasFilter) return HoverContext.NONE;

		Set<String> dependencies = new HashSet<>();
		Set<String> dependents = new HashSet<>();
		Set<String> filterMatches = new HashSet<>();

		if (hasHover) {
			for (GraphEdge edge : model.getEdges()) {
				if (edge.fromId().equals(hoveredNode)) dependencies.add(edge.toId());
				if (edge.toId().equals(hoveredNode)) dependents.add(edge.fromId());
			}
		}

		if (hasFilter) {
			String lower = filterText.toLowerCase();
			for (String id : model.getNodes().keySet()) {
				if (id.toLowerCase().contains(lower)) filterMatches.add(id);
			}
		}

		// Everything not in the highlighted set becomes dimmed
		Set<String> highlighted = new HashSet<>(filterMatches);
		if (hasHover) {
			highlighted.add(hoveredNode);
			highlighted.addAll(dependencies);
			highlighted.addAll(dependents);
		}
		Set<String> dimmed = new HashSet<>();
		for (String id : model.getNodes().keySet()) {
			if (!highlighted.contains(id)) dimmed.add(id);
		}

		return new HoverContext(
				hasHover ? dependencies : Set.of(),
				hasHover ? dependents : Set.of(),
				dimmed);
	}

	/** Immutable snapshot of hover roles for a single render pass. */
	private record HoverContext(Set<String> dependencies, Set<String> dependents, Set<String> dimmed) {
		static final HoverContext NONE = new HoverContext(Set.of(), Set.of(), Set.of());
		boolean isDimmed(String id) { return dimmed.contains(id); }
		boolean isDependency(String id) { return dependencies.contains(id); }
		boolean isDependent(String id) { return dependents.contains(id); }
	}

	// ---- Fonts -----------------------------------------------------------------

	private void createFonts(GC gc) {
		Display d = Display.getCurrent();
		FontData[] fd = d.getSystemFont().getFontData();
		for (FontData f : fd) f.setHeight(9);
		nodeFont = new Font(d, fd);

		FontData[] lfd = d.getSystemFont().getFontData();
		for (FontData f : lfd) { f.setHeight(8); f.setStyle(SWT.ITALIC); }
		layerFont = new Font(d, lfd);

		FontData[] bfd = d.getSystemFont().getFontData();
		for (FontData f : bfd) f.setHeight(7);
		badgeFont = new Font(d, bfd);
	}

	private void disposeFonts() {
		if (nodeFont != null) { nodeFont.dispose(); nodeFont = null; }
		if (layerFont != null) { layerFont.dispose(); layerFont = null; }
		if (badgeFont != null) { badgeFont.dispose(); badgeFont = null; }
	}

	// ---- Drawing ---------------------------------------------------------------

	private void drawLayerBackgrounds(GC gc, int unscaledH) {
		Color base = gc.getDevice().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
		boolean dark = isDark(base);
		int adj = dark ? 15 : -8;
		Color band = adjustColor(base, adj, adj, adj);
		try {
			int layerCount = model.getLayerCount();
			for (int layer = 0; layer < layerCount; layer++) {
				if (layer % 2 == 0) {
					int y = unscaledH - PADDING - NODE_HEIGHT - layer * (NODE_HEIGHT + V_GAP);
					gc.setBackground(band);
					gc.fillRectangle(0, y - 5, 99999, NODE_HEIGHT + 10);
				}
			}
		} finally {
			band.dispose();
		}
	}

	private void drawLayerLabels(GC gc, int unscaledH) {
		gc.setFont(layerFont);
		Color fg = gc.getDevice().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
		Color bg = gc.getDevice().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
		Color label = blend(fg, bg, 0.4f);
		try {
			gc.setForeground(label);
			int layerCount = model.getLayerCount();
			for (int layer = 0; layer < layerCount; layer++) {
				int y = unscaledH - PADDING - NODE_HEIGHT - layer * (NODE_HEIGHT + V_GAP);
				gc.drawText("L" + layer, 5, //$NON-NLS-1$
						y + (NODE_HEIGHT - gc.getFontMetrics().getHeight()) / 2, true);
			}
		} finally {
			label.dispose();
		}
	}

	private void drawNodes(GC gc, HoverContext ctx) {
		gc.setFont(nodeFont);

		Display d = Display.getCurrent();
		Color listBg = d.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
		Color listSel = d.getSystemColor(SWT.COLOR_LIST_SELECTION);
		Color widgetBg = d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
		Color widgetFg = d.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
		boolean dark = isDark(listBg);

		// Base node colors
		Color nodeNormalBg = adjustColor(listBg, dark ? 5 : -5, dark ? 5 : -3, dark ? 20 : -5);
		Color nodeHoverBg = blend(listBg, listSel, 0.25f);
		Color borderNormal = blend(widgetFg, listBg, 0.55f);
		Color borderSelected = blend(listSel, widgetFg, 0.35f);
		Color shadowColor = dark ? adjustColor(widgetBg, -20, -20, -20)
				: adjustColor(widgetBg, -35, -35, -35);

		// Dimmed
		Color dimmedBg = blend(nodeNormalBg, widgetBg, 0.75f);
		Color dimmedText = blend(d.getSystemColor(SWT.COLOR_LIST_FOREGROUND), widgetBg, 0.65f);

		// Teal accent — dependencies (what hovered depends on, below)
		Color tealBorder = dark ? new Color(64, 210, 160) : new Color(0, 150, 110);
		Color tealBg = blend(nodeNormalBg, tealBorder, 0.18f);

		// Amber accent — dependents (who depends on hovered, above)
		Color amberBorder = dark ? new Color(255, 170, 60) : new Color(200, 100, 0);
		Color amberBg = blend(nodeNormalBg, amberBorder, 0.18f);

		try {
			for (Map.Entry<String, Rectangle> entry : nodeBounds.entrySet()) {
				String id = entry.getKey();
				Rectangle r = entry.getValue();
				boolean isSelected = id.equals(selectedNode);
				boolean isHovered = id.equals(hoveredNode);
				boolean isDimmed = ctx.isDimmed(id);
				boolean isDependency = ctx.isDependency(id);
				boolean isDependent = ctx.isDependent(id);

				// Drop shadow (skipped for dimmed)
				if (!isDimmed) {
					gc.setBackground(shadowColor);
					gc.fillRectangle(r.x + 3, r.y + 3, r.width, r.height);
				}

				// Background
				Color bg;
				if (isDimmed) bg = dimmedBg;
				else if (isSelected) bg = listSel;
				else if (isHovered) bg = nodeHoverBg;
				else if (isDependency) bg = tealBg;
				else if (isDependent) bg = amberBg;
				else bg = nodeNormalBg;
				gc.setBackground(bg);
				gc.fillRectangle(r.x, r.y, r.width, r.height);

				// Border
				if (!isDimmed) {
					Color border;
					if (isSelected) border = borderSelected;
					else if (isDependency) border = tealBorder;
					else if (isDependent) border = amberBorder;
					else border = borderNormal;
					gc.setForeground(border);
					gc.setLineWidth(isDependency || isDependent ? 2 : isSelected ? 2 : 1);
					gc.drawRectangle(r.x, r.y, r.width, r.height);
				}

				// Label
				gc.setLineWidth(1);
				gc.setForeground(isDimmed ? dimmedText
						: isSelected ? d.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT)
								: d.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
				String lbl = truncateLabel(gc, id, r.width - 10);
				Point ext = gc.textExtent(lbl);
				gc.drawText(lbl, r.x + (r.width - ext.x) / 2, r.y + (r.height - ext.y) / 2, true);

				// Badge counts in small boxes (exported packages top-right, imported plug-ins bottom-left, imported packages bottom-right)
				if (!isDimmed) {
					GraphNode node = model.getNodes().get(id);
					if (node != null) {
						gc.setFont(badgeFont);
						int badgePad = 2;
						Color badgeBg = blend(bg, widgetFg, 0.15f);
						Color badgeFg = isSelected ? d.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT)
								: d.getSystemColor(SWT.COLOR_LIST_FOREGROUND);
						Color badgeBorder = blend(widgetFg, bg, 0.6f);
						try {
							if (node.exportedPackageCount() > 0) {
								String badge = String.valueOf(node.exportedPackageCount());
								Point be = gc.textExtent(badge);
								int bw = be.x + 2 * badgePad;
								int bh = be.y + 2 * badgePad;
								int bx = r.x + r.width - bw - 2;
								int by = r.y + 2;
								gc.setBackground(badgeBg);
								gc.fillRectangle(bx, by, bw, bh);
								gc.setForeground(badgeBorder);
								gc.setLineWidth(1);
								gc.drawRectangle(bx, by, bw, bh);
								gc.setForeground(badgeFg);
								gc.drawText(badge, bx + badgePad, by + badgePad, true);
							}
							if (node.importedPluginCount() > 0) {
								String badge = String.valueOf(node.importedPluginCount());
								Point be = gc.textExtent(badge);
								int bw = be.x + 2 * badgePad;
								int bh = be.y + 2 * badgePad;
								int bx = r.x + 2;
								int by = r.y + r.height - bh - 2;
								gc.setBackground(badgeBg);
								gc.fillRectangle(bx, by, bw, bh);
								gc.setForeground(badgeBorder);
								gc.setLineWidth(1);
								gc.drawRectangle(bx, by, bw, bh);
								gc.setForeground(badgeFg);
								gc.drawText(badge, bx + badgePad, by + badgePad, true);
							}
							if (node.importedPackageCount() > 0) {
								String badge = String.valueOf(node.importedPackageCount());
								Point be = gc.textExtent(badge);
								int bw = be.x + 2 * badgePad;
								int bh = be.y + 2 * badgePad;
								int bx = r.x + r.width - bw - 2;
								int by = r.y + r.height - bh - 2;
								gc.setBackground(badgeBg);
								gc.fillRectangle(bx, by, bw, bh);
								gc.setForeground(badgeBorder);
								gc.setLineWidth(1);
								gc.drawRectangle(bx, by, bw, bh);
								gc.setForeground(badgeFg);
								gc.drawText(badge, bx + badgePad, by + badgePad, true);
							}
						} finally {
							badgeBg.dispose();
							badgeBorder.dispose();
						}
						gc.setFont(nodeFont);
					}
				}
			}
		} finally {
			nodeNormalBg.dispose();
			nodeHoverBg.dispose();
			borderNormal.dispose();
			borderSelected.dispose();
			shadowColor.dispose();
			dimmedBg.dispose();
			dimmedText.dispose();
			tealBorder.dispose();
			tealBg.dispose();
			amberBorder.dispose();
			amberBg.dispose();
		}
	}

	private void drawEdges(GC gc, HoverContext ctx) {
		Display d = Display.getCurrent();
		Color widgetFg = d.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
		Color listBg = d.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
		boolean dark = isDark(listBg);

		// Normal edge colors (used when no hover is active)
		Color edgeNormal = blend(widgetFg, listBg, 0.4f);
		Color edgeReexport = dark ? new Color(255, 160, 64) : new Color(200, 80, 0);
		Color edgeOptional = d.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);

		// Hover-role edge colors
		Color edgeTeal = dark ? new Color(64, 210, 160) : new Color(0, 150, 110);   // to dependency
		Color edgeAmber = dark ? new Color(255, 170, 60) : new Color(200, 100, 0);  // from dependent

		// Dimmed
		Color edgeDimmed = blend(widgetFg, listBg, 0.85f);

		try {
			for (GraphEdge edge : model.getEdges()) {
				Rectangle from = nodeBounds.get(edge.fromId());
				Rectangle to = nodeBounds.get(edge.toId());
				if (from == null || to == null) continue;

				int x1 = from.x + from.width / 2;
				int y1 = from.y + from.height;
				int x2 = to.x + to.width / 2;
				int y2 = to.y;

				boolean toDep = hoveredNode != null && edge.fromId().equals(hoveredNode);
				boolean fromDep = hoveredNode != null && edge.toId().equals(hoveredNode);
				boolean edgeDimmedFlag = ctx.isDimmed(edge.fromId()) || ctx.isDimmed(edge.toId());

				Color lineColor;
				if (edgeDimmedFlag) {
					lineColor = edgeDimmed;
					gc.setLineWidth(1);
					gc.setLineStyle(SWT.LINE_SOLID);
				} else if (toDep) {
					// hovered → its dependency: teal
					lineColor = edgeTeal;
					gc.setLineWidth(2);
					gc.setLineStyle(SWT.LINE_SOLID);
				} else if (fromDep) {
					// dependent → hovered: amber
					lineColor = edgeAmber;
					gc.setLineWidth(2);
					gc.setLineStyle(SWT.LINE_SOLID);
				} else if (edge.reexported()) {
					lineColor = edgeReexport;
					gc.setLineWidth(2);
					gc.setLineStyle(SWT.LINE_SOLID);
				} else if (edge.optional()) {
					lineColor = edgeOptional;
					gc.setLineWidth(1);
					gc.setLineStyle(SWT.LINE_DASH);
				} else {
					lineColor = edgeNormal;
					gc.setLineWidth(1);
					gc.setLineStyle(SWT.LINE_SOLID);
				}

				gc.setForeground(lineColor);
				gc.drawLine(x1, y1, x2, y2);
				gc.setLineStyle(SWT.LINE_SOLID);

				if (!edgeDimmedFlag) {
					drawArrowHead(gc, lineColor, x1, y1, x2, y2);
				}
			}
		} finally {
			edgeNormal.dispose();
			edgeReexport.dispose();
			edgeTeal.dispose();
			edgeAmber.dispose();
			edgeDimmed.dispose();
		}
	}

	private void drawArrowHead(GC gc, Color color, int x1, int y1, int x2, int y2) {
		double angle = Math.atan2(y2 - y1, x2 - x1);
		int ax1 = (int) (x2 - ARROW_SIZE * Math.cos(angle - Math.PI / 6));
		int ay1 = (int) (y2 - ARROW_SIZE * Math.sin(angle - Math.PI / 6));
		int ax2 = (int) (x2 - ARROW_SIZE * Math.cos(angle + Math.PI / 6));
		int ay2 = (int) (y2 - ARROW_SIZE * Math.sin(angle + Math.PI / 6));
		Color saved = gc.getBackground();
		gc.setBackground(color);
		gc.fillPolygon(new int[] { x2, y2, ax1, ay1, ax2, ay2 });
		gc.setBackground(saved);
	}

	private String truncateLabel(GC gc, String text, int maxWidth) {
		if (gc.textExtent(text).x <= maxWidth) return text;
		String ellipsis = "..."; //$NON-NLS-1$
		for (int i = text.length() - 1; i > 0; i--) {
			String c = text.substring(0, i) + ellipsis;
			if (gc.textExtent(c).x <= maxWidth) return c;
		}
		return ellipsis;
	}

	// ---- Color helpers ---------------------------------------------------------

	private static boolean isDark(Color c) {
		return (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000 < 128;
	}

	private static Color adjustColor(Color base, int dr, int dg, int db) {
		return new Color(base.getDevice(), clamp(base.getRed() + dr),
				clamp(base.getGreen() + dg), clamp(base.getBlue() + db));
	}

	private static Color blend(Color c1, Color c2, float t) {
		return new Color(c1.getDevice(),
				clamp(Math.round(c1.getRed() + (c2.getRed() - c1.getRed()) * t)),
				clamp(Math.round(c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t)),
				clamp(Math.round(c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t)));
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	public void dispose() {
		disposeFonts();
	}
}
