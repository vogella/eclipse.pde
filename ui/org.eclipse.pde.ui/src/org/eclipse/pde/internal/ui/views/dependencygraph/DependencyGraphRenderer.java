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
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

import org.eclipse.pde.internal.ui.views.dependencygraph.DependencyGraphModel.GraphEdge;

/**
 * Renders the layered dependency graph onto a GC. Computes node positions and
 * draws boxes, labels, layer separators, and dependency arrows.
 */
public class DependencyGraphRenderer {

	static final int NODE_WIDTH = 180;
	static final int NODE_HEIGHT = 36;
	static final int H_GAP = 30;
	static final int V_GAP = 60;
	static final int LAYER_LABEL_WIDTH = 40;
	static final int PADDING = 30;
	static final int ARROW_SIZE = 7;

	private final DependencyGraphModel model;
	private final Map<String, Rectangle> nodeBounds = new HashMap<>();
	private float zoom = 1.0f;
	private Font nodeFont;
	private Font layerFont;
	private String hoveredNode;
	private String selectedNode;

	public DependencyGraphRenderer(DependencyGraphModel model) {
		this.model = model;
	}

	public void setZoom(float zoom) {
		this.zoom = Math.max(0.1f, Math.min(3.0f, zoom));
	}

	public float getZoom() {
		return zoom;
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

	/** Computes the total size needed for the graph at the current zoom */
	public Point computeSize() {
		int maxNodesInLayer = 0;
		int layerCount = model.getLayerCount();
		for (int i = 0; i < layerCount; i++) {
			maxNodesInLayer = Math.max(maxNodesInLayer, model.getNodesOnLayer(i).size());
		}
		int width = LAYER_LABEL_WIDTH + PADDING + maxNodesInLayer * (NODE_WIDTH + H_GAP) + PADDING;
		// layers are drawn bottom-up; layer 0 at bottom
		int height = PADDING + layerCount * (NODE_HEIGHT + V_GAP) + PADDING;
		return new Point(Math.round(width * zoom), Math.round(height * zoom));
	}

	/** Computes node bounds (unscaled) and stores them for hit-testing */
	private void layoutNodes() {
		nodeBounds.clear();
		int layerCount = model.getLayerCount();
		for (int layer = 0; layer < layerCount; layer++) {
			List<String> nodesOnLayer = model.getNodesOnLayer(layer);
			// y: layer 0 at bottom
			int y = PADDING + (layerCount - 1 - layer) * (NODE_HEIGHT + V_GAP);
			int x = LAYER_LABEL_WIDTH + PADDING;
			for (String nodeId : nodesOnLayer) {
				nodeBounds.put(nodeId, new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT));
				x += NODE_WIDTH + H_GAP;
			}
		}
	}

	/**
	 * Returns the plug-in ID at the given (zoomed) point, or null.
	 */
	public String hitTest(int zoomedX, int zoomedY) {
		int x = Math.round(zoomedX / zoom);
		int y = Math.round(zoomedY / zoom);
		for (Map.Entry<String, Rectangle> entry : nodeBounds.entrySet()) {
			Rectangle r = entry.getValue();
			if (r.contains(x, y)) {
				return entry.getKey();
			}
		}
		return null;
	}

	public void render(GC gc, Rectangle clipArea) {
		layoutNodes();
		createFonts(gc);

		gc.setAdvanced(true);
		gc.setAntialias(SWT.ON);
		gc.setTextAntialias(SWT.ON);

		// Apply zoom via transform
		org.eclipse.swt.graphics.Transform transform = new org.eclipse.swt.graphics.Transform(gc.getDevice());
		transform.scale(zoom, zoom);
		gc.setTransform(transform);

		drawLayerBackgrounds(gc);
		drawEdges(gc);
		drawNodes(gc);
		drawLayerLabels(gc);

		gc.setTransform(null);
		transform.dispose();
		disposeFonts();
	}

	private void createFonts(GC gc) {
		Display display = Display.getCurrent();
		FontData[] fontData = display.getSystemFont().getFontData();
		for (FontData fd : fontData) {
			fd.setHeight(9);
		}
		nodeFont = new Font(display, fontData);

		FontData[] layerFontData = display.getSystemFont().getFontData();
		for (FontData fd : layerFontData) {
			fd.setHeight(8);
			fd.setStyle(SWT.ITALIC);
		}
		layerFont = new Font(display, layerFontData);
	}

	private void disposeFonts() {
		if (nodeFont != null) {
			nodeFont.dispose();
			nodeFont = null;
		}
		if (layerFont != null) {
			layerFont.dispose();
			layerFont = null;
		}
	}

	private void drawLayerBackgrounds(GC gc) {
		int layerCount = model.getLayerCount();
		for (int layer = 0; layer < layerCount; layer++) {
			int y = PADDING + (layerCount - 1 - layer) * (NODE_HEIGHT + V_GAP);
			Color bg = (layer % 2 == 0) ? new Color(245, 245, 250) : new Color(250, 250, 255);
			gc.setBackground(bg);
			gc.fillRectangle(0, y - 5, 5000, NODE_HEIGHT + 10);
			bg.dispose();
		}
	}

	private void drawLayerLabels(GC gc) {
		gc.setFont(layerFont);
		gc.setForeground(new Color(120, 120, 140));
		int layerCount = model.getLayerCount();
		for (int layer = 0; layer < layerCount; layer++) {
			int y = PADDING + (layerCount - 1 - layer) * (NODE_HEIGHT + V_GAP);
			gc.drawText("L" + layer, 5, y + (NODE_HEIGHT - gc.getFontMetrics().getHeight()) / 2, true); //$NON-NLS-1$
		}
	}

	private void drawNodes(GC gc) {
		gc.setFont(nodeFont);
		for (Map.Entry<String, Rectangle> entry : nodeBounds.entrySet()) {
			String id = entry.getKey();
			Rectangle r = entry.getValue();
			boolean isSelected = id.equals(selectedNode);
			boolean isHovered = id.equals(hoveredNode);

			// Background
			Color bg;
			if (isSelected) {
				bg = new Color(70, 130, 220);
			} else if (isHovered) {
				bg = new Color(200, 220, 250);
			} else {
				bg = new Color(230, 240, 255);
			}
			gc.setBackground(bg);
			gc.fillRoundRectangle(r.x, r.y, r.width, r.height, 8, 8);
			bg.dispose();

			// Border
			Color border = isSelected ? new Color(40, 80, 160) : new Color(120, 150, 200);
			gc.setForeground(border);
			gc.setLineWidth(isSelected ? 2 : 1);
			gc.drawRoundRectangle(r.x, r.y, r.width, r.height, 8, 8);
			border.dispose();

			// Label (truncate if needed)
			gc.setForeground(isSelected ? gc.getDevice().getSystemColor(SWT.COLOR_WHITE)
					: gc.getDevice().getSystemColor(SWT.COLOR_BLACK));
			String label = truncateLabel(gc, id, r.width - 10);
			Point textExtent = gc.textExtent(label);
			gc.drawText(label, r.x + (r.width - textExtent.x) / 2, r.y + (r.height - textExtent.y) / 2, true);
		}
	}

	private void drawEdges(GC gc) {
		for (GraphEdge edge : model.getEdges()) {
			Rectangle fromRect = nodeBounds.get(edge.fromId());
			Rectangle toRect = nodeBounds.get(edge.toId());
			if (fromRect == null || toRect == null) {
				continue;
			}

			// from is on a higher layer, to is on a lower layer
			// Arrow goes from bottom-center of 'from' to top-center of 'to'
			int x1 = fromRect.x + fromRect.width / 2;
			int y1 = fromRect.y + fromRect.height;
			int x2 = toRect.x + toRect.width / 2;
			int y2 = toRect.y;

			Color lineColor;
			if (edge.reexported()) {
				lineColor = new Color(220, 100, 40);
				gc.setLineWidth(2);
			} else if (edge.optional()) {
				lineColor = new Color(150, 150, 150);
				gc.setLineWidth(1);
				gc.setLineStyle(SWT.LINE_DASH);
			} else {
				lineColor = new Color(100, 130, 180);
				gc.setLineWidth(1);
			}
			gc.setForeground(lineColor);
			gc.drawLine(x1, y1, x2, y2);
			drawArrowHead(gc, x1, y1, x2, y2);
			gc.setLineStyle(SWT.LINE_SOLID);
			lineColor.dispose();
		}
	}

	private void drawArrowHead(GC gc, int x1, int y1, int x2, int y2) {
		double angle = Math.atan2(y2 - y1, x2 - x1);
		int ax1 = (int) (x2 - ARROW_SIZE * Math.cos(angle - Math.PI / 6));
		int ay1 = (int) (y2 - ARROW_SIZE * Math.sin(angle - Math.PI / 6));
		int ax2 = (int) (x2 - ARROW_SIZE * Math.cos(angle + Math.PI / 6));
		int ay2 = (int) (y2 - ARROW_SIZE * Math.sin(angle + Math.PI / 6));
		gc.fillPolygon(new int[] { x2, y2, ax1, ay1, ax2, ay2 });
	}

	private String truncateLabel(GC gc, String text, int maxWidth) {
		if (gc.textExtent(text).x <= maxWidth) {
			return text;
		}
		String ellipsis = "..."; //$NON-NLS-1$
		for (int i = text.length() - 1; i > 0; i--) {
			String candidate = text.substring(0, i) + ellipsis;
			if (gc.textExtent(candidate).x <= maxWidth) {
				return candidate;
			}
		}
		return ellipsis;
	}

	public void dispose() {
		disposeFonts();
	}
}
