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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.pde.core.plugin.IPluginBase;
import org.eclipse.pde.core.plugin.IPluginImport;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;

/**
 * Computes a layered dependency graph of workspace plug-ins. Plug-ins with no
 * workspace dependencies are placed on layer 0 (bottom), their dependents on
 * layer 1, and so forth.
 */
public class DependencyGraphModel {

	/** A node in the dependency graph representing a single workspace plug-in */
	public record GraphNode(String pluginId, IPluginModelBase model, IPluginImport[] imports) {
	}

	/** A directed edge from a dependent to a dependency */
	public record GraphEdge(String fromId, String toId, boolean reexported, boolean optional) {
	}

	private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
	private final List<GraphEdge> edges = new ArrayList<>();
	private final Map<String, Integer> layerAssignment = new HashMap<>();
	private int layerCount;

	/**
	 * Recomputes the graph from the current workspace plug-in models.
	 *
	 * @param reexportOnly if {@code true}, only re-exported dependencies are
	 *                     included
	 */
	public void compute(boolean reexportOnly) {
		nodes.clear();
		edges.clear();
		layerAssignment.clear();
		layerCount = 0;

		IPluginModelBase[] workspaceModels = PluginRegistry.getWorkspaceModels();
		Set<String> workspaceIds = new HashSet<>();

		for (IPluginModelBase model : workspaceModels) {
			IPluginBase base = model.getPluginBase();
			if (base != null && base.getId() != null) {
				workspaceIds.add(base.getId());
				nodes.put(base.getId(), new GraphNode(base.getId(), model, base.getImports()));
			}
		}

		for (GraphNode node : nodes.values()) {
			if (node.imports() == null) {
				continue;
			}
			for (IPluginImport imp : node.imports()) {
				if (imp.getId() == null || !workspaceIds.contains(imp.getId())) {
					continue;
				}
				if (reexportOnly && !imp.isReexported()) {
					continue;
				}
				edges.add(new GraphEdge(node.pluginId(), imp.getId(), imp.isReexported(), imp.isOptional()));
			}
		}

		computeLayers();
	}

	/**
	 * Assigns each node to a layer using longest-path layering. Nodes with no
	 * outgoing workspace dependencies get layer 0; dependents are placed on
	 * higher layers.
	 */
	private void computeLayers() {
		Map<String, Set<String>> outgoing = new HashMap<>();
		for (GraphEdge edge : edges) {
			outgoing.computeIfAbsent(edge.fromId(), k -> new LinkedHashSet<>()).add(edge.toId());
		}

		Map<String, Integer> memo = new HashMap<>();
		for (String id : nodes.keySet()) {
			computeLayer(id, outgoing, memo, new HashSet<>());
		}
		layerAssignment.putAll(memo);
		layerCount = memo.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
	}

	private int computeLayer(String id, Map<String, Set<String>> outgoing, Map<String, Integer> memo,
			Set<String> visiting) {
		if (memo.containsKey(id)) {
			return memo.get(id);
		}
		if (visiting.contains(id)) {
			// cycle detected - break it
			return 0;
		}
		visiting.add(id);
		Set<String> deps = outgoing.getOrDefault(id, Collections.emptySet());
		int maxDepLayer = -1;
		for (String dep : deps) {
			maxDepLayer = Math.max(maxDepLayer, computeLayer(dep, outgoing, memo, visiting));
		}
		int layer = maxDepLayer + 1;
		memo.put(id, layer);
		visiting.remove(id);
		return layer;
	}

	public Map<String, GraphNode> getNodes() {
		return Collections.unmodifiableMap(nodes);
	}

	public List<GraphEdge> getEdges() {
		return Collections.unmodifiableList(edges);
	}

	public int getLayer(String pluginId) {
		return layerAssignment.getOrDefault(pluginId, 0);
	}

	public int getLayerCount() {
		return layerCount;
	}

	/** Returns all node IDs on a given layer, sorted alphabetically. */
	public List<String> getNodesOnLayer(int layer) {
		List<String> result = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : layerAssignment.entrySet()) {
			if (entry.getValue() == layer) {
				result.add(entry.getKey());
			}
		}
		result.sort(String::compareTo);
		return result;
	}
}
