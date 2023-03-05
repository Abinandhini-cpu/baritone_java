/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.builder;

import baritone.builder.DependencyGraphScaffoldingOverlay.CollapsedDependencyGraph;
import baritone.builder.DependencyGraphScaffoldingOverlay.CollapsedDependencyGraph.CollapsedDependencyGraphComponent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Given a DependencyGraphScaffoldingOverlay, put in scaffolding blocks until the entire graph is navigable from the root.
 * <p>
 * In other words, add scaffolding blocks to the schematic until the entire thing can theoretically be built from one
 * starting point, just by placing blocks against blocks. So like, anything floating in the air will get a connector down to the
 * ground (or over to something that's eventually connected to the ground). After this is done, nothing will be left floating in
 * midair with no connection to the rest of the build.
 */
public class Scaffolder {

    private final DependencyGraphScaffoldingOverlay overlayGraph;
    // NOTE: these next three fields are updated in-place as the overlayGraph is updated :)
    private final CollapsedDependencyGraph collapsedGraph;
    private final Int2ObjectMap<CollapsedDependencyGraphComponent> components;
    private final Long2ObjectMap<CollapsedDependencyGraphComponent> componentLocations;

    private final List<CollapsedDependencyGraphComponent> rootComponents;

    private Scaffolder(PlaceOrderDependencyGraph graph) {
        this.overlayGraph = new DependencyGraphScaffoldingOverlay(graph);
        this.collapsedGraph = overlayGraph.getCollapsedGraph();
        this.components = collapsedGraph.getComponents();
        this.componentLocations = collapsedGraph.getComponentLocations();

        this.rootComponents = calcRoots();
    }

    public static Scaffolder run(PlaceOrderDependencyGraph graph) {
        Scaffolder scaffolder = new Scaffolder(graph);
        while (scaffolder.rootComponents.size() > 1) {
            scaffolder.loop();
        }
        return scaffolder;
    }

    private List<CollapsedDependencyGraphComponent> calcRoots() {
        // since the components form a DAG (because all strongly connected components, and therefore all cycles, have been collapsed)
        // we can locate all root components by simply finding the ones with no incoming edges
        return components
                .values()
                .stream()
                .filter(component -> component.getIncoming().isEmpty())
                .collect(Collectors.toCollection(ArrayList::new)); // ensure arraylist since we will be mutating the list
    }

    private void loop() {
        if (rootComponents.size() <= 1) {
            throw new IllegalStateException();
        }
        CollapsedDependencyGraphComponent root = rootComponents.remove(rootComponents.size() - 1);
        if (!root.getIncoming().isEmpty()) {
            throw new IllegalStateException();
        }
        ScaffoldingSearchNode end = dijkstra(root);
        List<ScaffoldingSearchNode> path = new ArrayList<>();
        while (end != null) {
            path.add(end);
            end = end.prev;
        }
        if (!root.getPositions().contains(path.get(path.size() - 1).pos)) {
            throw new IllegalStateException();
        }
        if (!componentLocations.containsKey(path.get(0).pos)) {
            throw new IllegalStateException();
        }
        LongList toEnable = path
                .subList(1, path.size() - 1)
                .stream()
                .map(node -> node.pos)
                .collect(Collectors.toCollection(LongArrayList::new));
        enable(toEnable);
    }

    private void enable(LongList positions) {
        positions.forEach(pos -> {
            if (componentLocations.containsKey(pos)) {
                throw new IllegalStateException();
            }
        });
        int cid = collapsedGraph.lastComponentID().getAsInt();

        positions.forEach(overlayGraph::enable);

        int newCID = collapsedGraph.lastComponentID().getAsInt();
        for (int i = cid + 1; i <= newCID; i++) {
            if (components.get(i) != null && components.get(i).getIncoming().isEmpty()) {
                rootComponents.add(components.get(i));
            }
        }
        // this works because as we add new components and connect them up, we can say that
        rootComponents.removeIf(CollapsedDependencyGraphComponent::deleted);
        if (Main.DEBUG) {
            if (!rootComponents.equals(calcRoots())) {
                throw new IllegalStateException();
            }
        }
    }

    public void enableAncillaryScaffoldingAndRecomputeRoot(LongList positions) {
        System.out.println("TODO: should ancillary scaffolding even recompute the components? that scaffolding doesn't NEED to part of any component, and having all components be mutable even after the scaffolder is done is sketchy");
        getRoot();
        enable(positions);
        getRoot();
    }

    public CollapsedDependencyGraphComponent getRoot() { // TODO this should probably return a new class that is not mutable in-place
        if (rootComponents.size() != 1) {
            throw new IllegalStateException(); // this is okay because this can only possibly be called after Scaffolder.run is completed
        }
        CollapsedDependencyGraphComponent root = rootComponents.get(0);
        if (!root.getIncoming().isEmpty() || root.deleted()) {
            throw new IllegalStateException();
        }
        return root;
    }

    private void walkAllDescendents(CollapsedDependencyGraphComponent root, Set<CollapsedDependencyGraphComponent> set) {
        set.add(root);
        for (CollapsedDependencyGraphComponent component : root.getOutgoing()) {
            walkAllDescendents(component, set);
        }
    }

    // TODO refactor dijkstra into an implementation of IScaffolderStrategy that would be passed as an argument to Scaffolder
    private ScaffoldingSearchNode dijkstra(CollapsedDependencyGraphComponent root) {
        Set<CollapsedDependencyGraphComponent> exclusiveDescendents = new ObjectOpenHashSet<>();
        walkAllDescendents(root, exclusiveDescendents);
        exclusiveDescendents.remove(root);
        PriorityQueue<ScaffoldingSearchNode> openSet = new PriorityQueue<>(Comparator.comparingInt(node -> node.costSoFar));
        Long2ObjectOpenHashMap<ScaffoldingSearchNode> nodeMap = new Long2ObjectOpenHashMap<>();
        LongIterator it = root.getPositions().iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            nodeMap.put(l, new ScaffoldingSearchNode(l));
        }
        openSet.addAll(nodeMap.values());
        while (!openSet.isEmpty()) {
            ScaffoldingSearchNode node = openSet.poll();
            CollapsedDependencyGraphComponent tentativeComponent = componentLocations.get(node.pos);
            if (tentativeComponent != null) {
                if (exclusiveDescendents.contains(tentativeComponent)) {
                    // have gone back onto a descendent of this node
                    // sadly this can happen even at the same Y level even in Y_STRICT mode due to orientable blocks forming a loop
                    continue; // TODO does this need to be here? can I expand THROUGH an unrelated component? probably requires testing, this is quite a mind bending possibility
                } else {
                    // found a path to a component that isn't a descendent of the root
                    if (tentativeComponent != root) { // but if it IS the root, then we're just on our first loop iteration, we are far from done
                        return node; // all done! found a path to a component unrelated to this one, meaning we have successfully connected this part of the build with scaffolding back to the rest of it
                    }
                }
            }
            for (Face face : Face.VALUES) {
                if (overlayGraph.hypotheticalScaffoldingIncomingEdge(node.pos, face)) { // we don't have to worry about an incoming edge going into the frontier set because the root component is strongly connected and has no incoming edges from other SCCs, therefore any and all incoming edges will come from hypothetical scaffolding air locations
                    long neighborPos = face.offset(node.pos);
                    int newCost = node.costSoFar + edgeCost(face); // TODO future edge cost should include an added modifier for if neighborPos is in a favorable or unfavorable position e.g. above / under a diagonal depending on if map art or not
                    ScaffoldingSearchNode existingNode = nodeMap.get(neighborPos);
                    if (existingNode != null) {
                        // it's okay if neighbor isn't marked as "air" in the overlay - that's what we want to find - a path to another component
                        // however, we can't consider neighbors within the same component as a solution, clearly
                        // we can accomplish this and kill two birds with one stone by skipping all nodes already in the node map
                        // any position in the initial frontier is clearly in the node map, but also any node that has already been considered
                        // this prevents useless cycling of equivalent paths
                        // this is okay because all paths are equivalent, so there is no possible way to find a better path (because currently it's a fixed value for horizontal / vertical movements)
                        if (existingNode.costSoFar != newCost) {
                            throw new IllegalStateException();
                        }
                        continue; // nothing to do - we already have an equal-or-better path to this location
                    }
                    ScaffoldingSearchNode newNode = new ScaffoldingSearchNode(neighborPos);
                    newNode.costSoFar = newCost;
                    newNode.prev = node;
                    nodeMap.put(newNode.pos, newNode);
                    openSet.add(newNode);
                }
            }
        }
        return null;
    }

    private int edgeCost(Face face) {
        if (Main.STRICT_Y && face == Face.UP) {
            throw new IllegalStateException();
        }
        // gut feeling: give slight bias to moving horizontally
        // that will influence it to create horizontal bridges more often than vertical pillars
        // horizontal bridges are easier to maneuver around and over
        if (face.y == 0) {
            return 1;
        }
        return 2;
    }

    private static class ScaffoldingSearchNode {

        private final long pos;
        private int costSoFar;
        private ScaffoldingSearchNode prev;

        private ScaffoldingSearchNode(long pos) {
            this.pos = pos;
        }
    }

    // TODO should Scaffolder return a different class? "CompletedScaffolding" or something that has these methods as non-delegate, as well as getRoot returning a immutable equivalent of CollapsedDependencyGraphComponent?
    public boolean real(long pos) {
        return overlayGraph.real(pos);
    }

    public void forEachReal(Bounds.BoundsLongConsumer consumer) {
        overlayGraph.forEachReal(consumer);
    }

    public LongSets.UnmodifiableSet scaffolding() {
        return overlayGraph.scaffolding();
    }

    public boolean air(long pos) {
        return overlayGraph.air(pos);
    }
}
