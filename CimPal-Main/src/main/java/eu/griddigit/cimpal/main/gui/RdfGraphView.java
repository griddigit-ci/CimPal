/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.gui;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A node-link diagram of RDF resources and the relationships between them.
 * <p>
 * Drawn with plain JavaFX shapes rather than a graph library: the layout is a compact
 * force-directed pass over primitive arrays, so there is no extra dependency to ship and every
 * shape takes its colour from the active theme through {@code base.css}.
 * <p>
 * Not a general-purpose graph widget - it renders one snapshot at a time. Call
 * {@link #show(List, int)} with the edges to draw; each call replaces the previous drawing.
 */
public final class RdfGraphView extends Pane {

    /** One relationship: a directed edge between two resources. */
    public record Edge(String fromId, String fromLabel, String toId, String toLabel, String label) {
    }

    /** Layout iterations. Enough to untangle a few hundred nodes without a visible pause. */
    private static final int ITERATIONS = 420;

    /**
     * Strength of the pull towards the centre. Small on purpose: enough to stop disconnected
     * components drifting out of frame, not enough to collapse the drawing into a ball and leave
     * the local structure unreadable.
     */
    private static final double GRAVITY = 0.012;

    /** Above this many nodes the per-node labels overlap into noise, so they are left off. */
    private static final int MAX_LABELLED_NODES = 50;

    private static final double NODE_RADIUS = 6.0;
    private static final double MIN_SCALE = 0.05;
    private static final double MAX_SCALE = 6.0;

    /**
     * Everything is drawn into this one Group, so pan and zoom are a single transform on it
     * rather than a recalculation of every shape's coordinates.
     */
    private final Group content = new Group();

    private final List<LayoutNode> nodes = new ArrayList<>();
    private double scale = 1.0;
    private double dragAnchorX;
    private double dragAnchorY;

    /** Told the id of whichever node was last clicked, so the tab can report or reveal it. */
    private Consumer<String> onNodeClicked = id -> { };
    private LayoutMode layoutMode = LayoutMode.FORCE_DIRECTED;

    /** Layouts offered by the visualisation toolbar. */
    public enum LayoutMode {
        FORCE_DIRECTED("Force-directed"),
        RADIAL("Radial"),
        GRID("Grid");

        private final String label;

        LayoutMode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    public RdfGraphView() {
        getStyleClass().add("rdf-graph-view");
        getChildren().add(content);
        // Clip to the viewport so a panned diagram cannot paint over its neighbours. Sized from
        // width/height rather than layoutBounds: the clip starts at 0x0, and layoutBounds on a
        // Pane holding a single unmanaged Group does not track the pane's own size, which left
        // the whole drawing clipped away.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        setOnScroll(event -> {
            // Zoom about the pointer, so the thing under the cursor stays under the cursor.
            double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double next = clamp(scale * factor);
            if (next == scale) {
                return;
            }
            Point2D before = content.parentToLocal(event.getX(), event.getY());
            scale = next;
            content.setScaleX(scale);
            content.setScaleY(scale);
            Point2D after = content.parentToLocal(event.getX(), event.getY());
            content.setTranslateX(content.getTranslateX() + (after.getX() - before.getX()) * scale);
            content.setTranslateY(content.getTranslateY() + (after.getY() - before.getY()) * scale);
            event.consume();
        });

        setOnMousePressed(event -> {
            dragAnchorX = event.getX() - content.getTranslateX();
            dragAnchorY = event.getY() - content.getTranslateY();
        });
        setOnMouseDragged(event -> {
            content.setTranslateX(event.getX() - dragAnchorX);
            content.setTranslateY(event.getY() - dragAnchorY);
        });
    }

    public void setOnNodeClicked(Consumer<String> listener) {
        this.onNodeClicked = listener == null ? id -> { } : listener;
    }

    public void setLayoutMode(LayoutMode layoutMode) {
        this.layoutMode = layoutMode == null ? LayoutMode.FORCE_DIRECTED : layoutMode;
    }

    /**
     * Replaces the drawing with the given edges.
     *
     * @param maxNodes stop adding nodes past this many; a diagram of thousands of nodes is an
     *                 unreadable hairball long before it is slow.
     * @return how many nodes and edges were actually drawn
     */
    public Rendered show(List<Edge> edges, int maxNodes) {
        content.getChildren().clear();
        nodes.clear();
        content.setTranslateX(0);
        content.setTranslateY(0);
        scale = 1.0;
        content.setScaleX(1);
        content.setScaleY(1);

        Map<String, LayoutNode> byId = new LinkedHashMap<>();
        List<int[]> links = new ArrayList<>();
        List<String> linkLabels = new ArrayList<>();
        boolean truncated = false;

        for (Edge edge : edges) {
            LayoutNode from = byId.get(edge.fromId());
            LayoutNode to = byId.get(edge.toId());
            if ((from == null || to == null) && byId.size() >= maxNodes) {
                truncated = true;
                continue;
            }
            if (from == null) {
                from = addNode(byId, edge.fromId(), edge.fromLabel());
            }
            if (to == null) {
                to = addNode(byId, edge.toId(), edge.toLabel());
            }
            if (from != to) {
                links.add(new int[]{from.index, to.index});
                linkLabels.add(edge.label());
                from.degree++;
                to.degree++;
            }
        }

        nodes.addAll(byId.values());
        if (nodes.isEmpty()) {
            return new Rendered(0, 0, false);
        }

        layout(links);
        draw(links, linkLabels);
        return new Rendered(nodes.size(), links.size(), truncated);
    }

    /** What {@link #show(List, int)} managed to draw. */
    public record Rendered(int nodeCount, int edgeCount, boolean truncated) {
    }

    private LayoutNode addNode(Map<String, LayoutNode> byId, String id, String label) {
        LayoutNode node = new LayoutNode(byId.size(), id, label);
        byId.put(id, node);
        return node;
    }

    /**
     * Fruchterman-Reingold: every pair of nodes repels, every edge pulls its ends together, and
     * the maximum step shrinks each round so the arrangement settles instead of oscillating.
     */
    private void layout(List<int[]> links) {
        int n = nodes.size();
        double area = Math.max(400.0 * n, 250_000.0);
        double aspect = getWidth() > 0 && getHeight() > 0 ? getWidth() / getHeight() : 16.0 / 9.0;
        double width = Math.sqrt(area * aspect);
        double height = area / width;
        double k = Math.sqrt(area / n);

        if (layoutMode == LayoutMode.RADIAL) {
            radialLayout(width, height);
            return;
        }
        if (layoutMode == LayoutMode.GRID) {
            gridLayout(width, height);
            return;
        }

        // A ring start beats a random one: it is deterministic, so the same filter always yields
        // the same picture, and no two nodes begin on top of each other.
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n;
            double radius = 0.42 + 0.45 * ((i % 7) / 7.0);
            nodes.get(i).x = width / 2 + width * radius * Math.cos(angle) / 2;
            nodes.get(i).y = height / 2 + height * radius * Math.sin(angle) / 2;
        }

        double temperature = Math.min(width, height) / 7;
        double cooling = temperature / (ITERATIONS + 1);

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (LayoutNode node : nodes) {
                node.dx = 0;
                node.dy = 0;
            }

            for (int i = 0; i < n; i++) {
                LayoutNode a = nodes.get(i);
                for (int j = i + 1; j < n; j++) {
                    LayoutNode b = nodes.get(j);
                    double deltaX = a.x - b.x;
                    double deltaY = a.y - b.y;
                    double distance = Math.max(0.01, Math.hypot(deltaX, deltaY));
                    double repulsion = k * k / distance;
                    double ux = deltaX / distance;
                    double uy = deltaY / distance;
                    a.dx += ux * repulsion;
                    a.dy += uy * repulsion;
                    b.dx -= ux * repulsion;
                    b.dy -= uy * repulsion;
                }
            }

            for (int[] link : links) {
                LayoutNode a = nodes.get(link[0]);
                LayoutNode b = nodes.get(link[1]);
                double deltaX = a.x - b.x;
                double deltaY = a.y - b.y;
                double distance = Math.max(0.01, Math.hypot(deltaX, deltaY));
                double attraction = distance * distance / k;
                double ux = deltaX / distance;
                double uy = deltaY / distance;
                a.dx -= ux * attraction;
                a.dy -= uy * attraction;
                b.dx += ux * attraction;
                b.dy += uy * attraction;
            }

            // Gravity towards the centre. Without it a graph with several disconnected
            // components - the normal case for an RDFS profile, where many subjects share no
            // resource-valued property - has nothing opposing repulsion, so the components drift
            // apart indefinitely and the finished drawing has to be scaled to near-invisibility
            // to fit. A weak pull keeps them in one frame without distorting local structure.
            for (LayoutNode node : nodes) {
                node.dx += (width / 2 - node.x) * GRAVITY;
                node.dy += (height / 2 - node.y) * GRAVITY;
            }

            for (LayoutNode node : nodes) {
                double step = Math.hypot(node.dx, node.dy);
                if (step > 0.01) {
                    double limited = Math.min(step, temperature);
                    node.x += node.dx / step * limited;
                    node.y += node.dy / step * limited;
                }
                // Standard FR also confines nodes to the frame it computed the forces for.
                node.x = Math.clamp(node.x, NODE_RADIUS * 2, width - NODE_RADIUS * 2);
                node.y = Math.clamp(node.y, NODE_RADIUS * 2, height - NODE_RADIUS * 2);
            }
            temperature -= cooling;
        }
        separateOverlaps(width, height);
    }

    /** Places isolated or very dense data in readable, deterministic alternatives to the force layout. */
    private void radialLayout(double width, double height) {
        double maximumRadius = Math.min(width, height) * 0.42;
        double spacing = NODE_RADIUS * 4.0;
        int next = 0;
        for (double radius = Math.max(spacing, maximumRadius / 4);
             next < nodes.size(); radius += spacing) {
            // The outermost ring may need to carry the remaining nodes; its spacing is still
            // better than stacking all of them at one radius.
            int capacity = Math.max(6, (int) Math.floor(2 * Math.PI * radius / spacing));
            int count = Math.min(capacity, nodes.size() - next);
            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count - Math.PI / 2;
                LayoutNode node = nodes.get(next++);
                node.x = width / 2 + radius * Math.cos(angle);
                node.y = height / 2 + radius * Math.sin(angle);
            }
        }
    }

    private void gridLayout(double width, double height) {
        int columns = (int) Math.ceil(Math.sqrt(nodes.size() * width / height));
        int rows = (int) Math.ceil((double) nodes.size() / columns);
        for (int i = 0; i < nodes.size(); i++) {
            LayoutNode node = nodes.get(i);
            node.x = width * ((i % columns) + 0.5) / columns;
            node.y = height * ((i / columns) + 0.5) / rows;
        }
    }

    /** Final collision pass: force layouts can settle with two high-degree nodes touching. */
    private void separateOverlaps(double width, double height) {
        double minimum = NODE_RADIUS * 3.5;
        for (int pass = 0; pass < 12; pass++) {
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    LayoutNode a = nodes.get(i), b = nodes.get(j);
                    double dx = b.x - a.x, dy = b.y - a.y;
                    double distance = Math.hypot(dx, dy);
                    if (distance >= minimum) continue;
                    if (distance < 0.01) { dx = 1; dy = 0; distance = 1; }
                    double move = (minimum - distance) / 2;
                    double ux = dx / distance, uy = dy / distance;
                    a.x = Math.clamp(a.x - ux * move, NODE_RADIUS, width - NODE_RADIUS);
                    a.y = Math.clamp(a.y - uy * move, NODE_RADIUS, height - NODE_RADIUS);
                    b.x = Math.clamp(b.x + ux * move, NODE_RADIUS, width - NODE_RADIUS);
                    b.y = Math.clamp(b.y + uy * move, NODE_RADIUS, height - NODE_RADIUS);
                }
            }
        }
    }

    private void draw(List<int[]> links, List<String> linkLabels) {
        // Edges first, so nodes and their labels sit on top of the lines.
        for (int i = 0; i < links.size(); i++) {
            int[] link = links.get(i);
            LayoutNode from = nodes.get(link[0]);
            LayoutNode to = nodes.get(link[1]);

            Line line = new Line(from.x, from.y, to.x, to.y);
            line.getStyleClass().add("rdf-graph-edge");

            Polygon head = new Polygon(0, 0, -7, -3, -7, 3);
            head.getStyleClass().add("rdf-graph-arrow");
            aimArrow(head, from, to);

            // Predicate labels only once the graph is small enough for them to be legible.
            Text label = null;
            if (links.size() <= 120) {
                label = new Text((from.x + to.x) / 2, (from.y + to.y) / 2 - 2, linkLabels.get(i));
                label.getStyleClass().add("rdf-graph-edge-text");
            }

            EdgeShape shape = new EdgeShape(from, to, line, head, label);
            from.edges.add(shape);
            to.edges.add(shape);

            content.getChildren().addAll(line, head);
            if (label != null) {
                content.getChildren().add(label);
            }
        }

        for (LayoutNode node : nodes) {
            // Radius carries the degree, so the hubs of the graph are visible at a glance.
            Circle circle = new Circle(node.x, node.y, NODE_RADIUS + Math.min(6, node.degree * 0.6));
            circle.getStyleClass().add("rdf-graph-node");
            circle.setId(node.label);
            Tooltip.install(circle, new Tooltip(node.label));

            Text text = null;
            if (nodes.size() <= MAX_LABELLED_NODES) {
                text = new Text(node.x + 10, node.y + 4, node.label);
                text.getStyleClass().add("rdf-graph-node-text");
            }

            node.circle = circle;
            node.text = text;

            circle.setOnMousePressed(event -> {
                onNodeClicked.accept(node.id);
                event.consume();
            });
            circle.setOnMouseDragged(event -> {
                Point2D local = content.sceneToLocal(event.getSceneX(), event.getSceneY());
                node.x = local.getX();
                node.y = local.getY();
                refresh(node);
                event.consume();
            });

            content.getChildren().add(circle);
            if (text != null) {
                content.getChildren().add(text);
            }
        }
    }

    /** Re-points one node's own shapes and every edge touching it, after it has been dragged. */
    private static void refresh(LayoutNode node) {
        node.circle.setCenterX(node.x);
        node.circle.setCenterY(node.y);
        if (node.text != null) {
            node.text.setX(node.x + 10);
            node.text.setY(node.y + 4);
        }
        node.edges.forEach(EdgeShape::reposition);
    }

    /** Puts the arrow head on the target's rim, pointing along the edge. */
    private static void aimArrow(Polygon head, LayoutNode from, LayoutNode to) {
        double angle = Math.atan2(to.y - from.y, to.x - from.x);
        double rim = NODE_RADIUS + 6;
        head.setLayoutX(to.x - Math.cos(angle) * rim);
        head.setLayoutY(to.y - Math.sin(angle) * rim);
        head.setRotate(Math.toDegrees(angle));
    }

    /** Scales and centres the drawing so all of it is visible in the current viewport. */
    public void fitToView() {
        if (nodes.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double minX = nodes.stream().mapToDouble(n -> n.x).min().orElse(0);
        double maxX = nodes.stream().mapToDouble(n -> n.x).max().orElse(0);
        double minY = nodes.stream().mapToDouble(n -> n.y).min().orElse(0);
        double maxY = nodes.stream().mapToDouble(n -> n.y).max().orElse(0);

        double margin = 60;
        double width = Math.max(1, maxX - minX) + margin * 2;
        double height = Math.max(1, maxY - minY) + margin * 2;

        // Never magnify past 1:1 - fitting a small graph should centre it, not blow it up.
        scale = clamp(Math.min(1.0, Math.min(getWidth() / width, getHeight() / height)));
        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setTranslateX(getWidth() / 2 - (minX + maxX) / 2 * scale);
        content.setTranslateY(getHeight() / 2 - (minY + maxY) / 2 * scale);
    }

    public void zoom(double factor) {
        double next = clamp(scale * factor);
        if (next != scale) {
            scale = next;
            content.setScaleX(scale);
            content.setScaleY(scale);
        }
    }

    public void clear() {
        content.getChildren().clear();
        nodes.clear();
    }

    private static double clamp(double value) {
        return Math.clamp(value, MIN_SCALE, MAX_SCALE);
    }

    /** The three shapes that make up one drawn edge, plus the nodes they span. */
    private record EdgeShape(LayoutNode from, LayoutNode to, Line line, Polygon arrow, Text label) {
        void reposition() {
            line.setStartX(from.x);
            line.setStartY(from.y);
            line.setEndX(to.x);
            line.setEndY(to.y);
            aimArrow(arrow, from, to);
            if (label != null) {
                label.setX((from.x + to.x) / 2);
                label.setY((from.y + to.y) / 2 - 2);
            }
        }
    }

    /** Mutable during layout, then holds the shapes so a drag can move them. */
    private static final class LayoutNode {
        private final int index;
        private final String id;
        private final String label;
        private double x;
        private double y;
        private double dx;
        private double dy;
        private int degree;
        private Circle circle;
        private Text text;
        private final List<EdgeShape> edges = new ArrayList<>();

        LayoutNode(int index, String id, String label) {
            this.index = index;
            this.id = id;
            this.label = label;
        }
    }
}
