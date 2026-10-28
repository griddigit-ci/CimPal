/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.GridData.EndRec;
import eu.griddigit.cimpal.core.idmapping.GridData.TerminalRec;
import eu.griddigit.cimpal.core.idmapping.Network.Equipment;
import eu.griddigit.cimpal.core.idmapping.Network.NodeInfo;
import eu.griddigit.cimpal.core.idmapping.Refinement.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Matches what is inside a matched substation pair, and the elements of two matched lines, by
 * their position: busbars, switches, transformers, loads, machines and nodes are placed relative to
 * what is already matched (the line ends first) with {@link Refinement}.
 * <p>
 * Classes must agree in a first pass; a second pass lets switches of different classes match (a
 * Breaker in one model, a Switch in the other) when their whole surroundings agree, and notes it.
 */
final class TopologyMatcher {

    private final MatchContext ctx;
    /** Edge label of each transformer terminal: its winding's rank by rated voltage, highest first. */
    private final Map<String, String> windingLabels = new HashMap<>();
    /** While true, graphs are built with trusted matches only as anchors; see {@link #trusted}. */
    private boolean trustedOnly;
    /**
     * Whether a line end may be matched on a partly confirmed position. Such a match also pairs the
     * lines, which then anchor the far ends, so it waits until nothing stronger is left: parallel
     * circuits are better told apart at the end where their bays differ.
     */
    boolean weakLineEnds;

    TopologyMatcher(MatchContext ctx) {
        this.ctx = ctx;
        rankWindings(ctx.model1);
        rankWindings(ctx.model2);
    }

    private void rankWindings(Network net) {
        Map<String, List<EndRec>> byTransformer = new HashMap<>();
        for (EndRec end : net.data.ends) {
            byTransformer.computeIfAbsent(end.transformer(), t -> new ArrayList<>()).add(end);
        }
        for (List<EndRec> ends : byTransformer.values()) {
            TreeSet<Double> voltages = new TreeSet<>();
            for (EndRec end : ends) {
                if (end.ratedU() != null) {
                    voltages.add(end.ratedU());
                }
            }
            List<Double> ranked = new ArrayList<>(voltages.descendingSet());
            for (EndRec end : ends) {
                if (end.terminal() != null) {
                    windingLabels.put(end.terminal(), end.ratedU() == null ? "w?" : "w" + ranked.indexOf(end.ratedU()));
                }
            }
        }
    }

    // ---- substation pairs ----------------------------------------------------------------------

    /** Matches the inside of a matched substation pair; returns whether anything new matched. */
    boolean matchSubstation(String model1Sub, String model2Sub) {
        boolean any = false;
        Refinement last = null;
        for (boolean relaxed : new boolean[]{false, true}) {
            while (true) {
                last = localGraph(model1Sub, model2Sub, relaxed);
                List<Pair> pairs = last.next(relaxed);
                if (pairs.isEmpty()) {
                    break;
                }
                Refinement check = trusted(() -> localGraph(model1Sub, model2Sub, relaxed));
                if (!apply(pairs, "topology", null, relaxed, ctx.matches.isCertain(model1Sub), model1Sub, check)) {
                    break;
                }
                any = true;
            }
        }
        recordReasons(last, ctx.model1.nodesBySubstation.getOrDefault(model1Sub, List.of()), Side.MODEL1,
                ctx.model2.nodesBySubstation.getOrDefault(model2Sub, List.of()), Side.MODEL2);
        return any;
    }

    private Refinement localGraph(String model1Sub, String model2Sub, boolean relaxed) {
        Refinement g = new Refinement();
        addLocal(g, Side.MODEL1, model1Sub, relaxed);
        addLocal(g, Side.MODEL2, model2Sub, relaxed);
        return g;
    }

    private void addLocal(Refinement g, Side side, String sub, boolean relaxed) {
        Network net = ctx.net(side);
        List<String> nodeIris = net.nodesBySubstation.getOrDefault(sub, List.of());
        Set<String> local = new HashSet<>(nodeIris);
        Set<String> equipment = new LinkedHashSet<>();
        for (String nodeIri : nodeIris) {
            addNode(g, side, nodeIri);
            for (TerminalRec t : net.node(nodeIri).terminals) {
                equipment.add(t.equipment());
            }
        }
        for (String eqIri : equipment) {
            Equipment eq = net.equipment.get(eqIri);
            List<String> remote = new ArrayList<>();
            List<TerminalRec> inside = new ArrayList<>();
            for (TerminalRec t : eq.terminals) {
                if (t.node() != null && local.contains(t.node())) {
                    inside.add(t);
                } else {
                    remote.add(remoteToken(side, eq, t, sub));
                }
            }
            remote.sort(null);
            int e = addEquipment(g, side, eq, String.join(",", remote), relaxed);
            for (TerminalRec t : inside) {
                g.link(e, g.find(side, t.node()), windingLabels.getOrDefault(t.terminal(), "t"));
            }
        }
    }

    /** What a terminal outside the substation leads to, in the shared key space. */
    private String remoteToken(Side side, Equipment eq, TerminalRec t, String sub) {
        if (t.node() == null) {
            return "unconnected";
        }
        Network net = ctx.net(side);
        LogicalLine line = net.lineOfMember.get(eq.iri);
        if (line != null) {
            List<String> far = new ArrayList<>(line.endpointKeys());
            far.remove(sub);
            List<String> shared = new ArrayList<>();
            for (String key : far) {
                shared.add(sharedKey(side, key));
            }
            shared.sort(null);
            return "line>" + String.join("+", shared);
        }
        NodeInfo n = net.node(t.node());
        return "to>" + (n == null || n.substation == null ? MatchContext.UNKNOWN : sharedKey(side, n.substation));
    }

    /** {@link MatchContext#sharedKey}, but while building a trusted graph only through trusted matches. */
    private String sharedKey(Side side, String key) {
        String shared = ctx.sharedKey(side, key);
        if (!trustedOnly || MatchContext.UNKNOWN.equals(shared)) {
            return shared;
        }
        String iri = key.startsWith(LogicalLine.OPEN) ? key.substring(LogicalLine.OPEN.length()) : key;
        return trustedMatch(side, iri) ? shared : MatchContext.UNKNOWN;
    }

    /**
     * Builds a graph in which only trusted matches (certain, or sure and waiting for their context)
     * anchor the others. A pair is sure only when this graph pins it down too: a position read off
     * a probable match is no better than that match.
     */
    private Refinement trusted(java.util.function.Supplier<Refinement> build) {
        trustedOnly = true;
        try {
            return build.get();
        } finally {
            trustedOnly = false;
        }
    }

    private boolean trustedMatch(Side side, String iri) {
        String model1 = side == Side.MODEL1 ? iri : ctx.matches.toModel1(iri);
        return model1 != null && (ctx.matches.isCertain(model1) || ctx.matches.pendingOnContext().containsKey(model1));
    }

    // ---- line pairs -----------------------------------------------------------------------------

    /** Matches the sections, interior nodes and attached equipment of two matched lines. */
    void alignLine(LogicalLine p, LogicalLine n, String method, String note, boolean certain, boolean structural) {
        if (p.members.size() == 1 && n.members.size() == 1) {
            ctx.matches.add(p.members.get(0), n.members.get(0), method, note, certain);
            if (structural) {
                ctx.matches.certainWhen(p.members.get(0), p);
            }
            if (p.interiorNodes.isEmpty() && n.interiorNodes.isEmpty()) {
                return;
            }
        } else if (p.branchCount > 1 && n.members.size() == 1 && n.branchCount == 1) {
            String target = n.members.get(0);
            String split = MatchContext.join(note, "model 1 line has " + p.branchCount + " sections, model 2 has 1");
            for (String member : p.members) {
                if (Network.BRANCH.equals(ctx.model1.equipment.get(member).role)) {
                    ctx.matches.addManyToOne(member, target, method, split, certain);
                    if (structural) {
                        ctx.matches.certainWhen(member, p);
                    }
                } else {
                    ctx.matches.reason(Side.MODEL1, member, "the model 2 line has a single section", List.of(target));
                }
            }
            for (String node : p.interiorNodes) {
                ctx.matches.reason(Side.MODEL1, node, "the model 2 line has a single section", List.of());
            }
            return;
        } else if (p.members.size() == 1 && p.branchCount == 1 && n.branchCount > 1) {
            mapToFirstSection(p.members.get(0), n, method, note);
            return;
        }
        Refinement last = null;
        for (boolean relaxed : new boolean[]{false, true}) {
            while (true) {
                last = lineGraph(p, n, relaxed);
                List<Pair> pairs = last.next(relaxed);
                if (pairs.isEmpty()) {
                    break;
                }
                Refinement check = trusted(() -> lineGraph(p, n, relaxed));
                if (!apply(pairs, method, note, relaxed, certain, structural ? p : null, check)) {
                    break;
                }
            }
        }
        List<String> model1Objects = new ArrayList<>(p.members);
        model1Objects.addAll(p.interiorNodes);
        model1Objects.addAll(p.attachments);
        List<String> model2Objects = new ArrayList<>(n.members);
        model2Objects.addAll(n.interiorNodes);
        model2Objects.addAll(n.attachments);
        recordLineReasons(last, model1Objects, Side.MODEL1);
        recordLineReasons(last, model2Objects, Side.MODEL2);
    }

    /**
     * A model 1 line in one section against a model 2 line in several (e.g. a tie line model 2 keeps
     * as two halves at the boundary): the model 1 section maps to the model 2 section at the line's first
     * end (by sorted key), with the other sections in the note; those stay unmatched on the model 2 side.
     */
    private void mapToFirstSection(String member, LogicalLine n, String method, String note) {
        String chosen = null;
        String where = null;
        List<LogicalLine.Endpoint> ends = new ArrayList<>(n.endpoints);
        ends.sort((a, b) -> a.key().compareTo(b.key()));
        for (LogicalLine.Endpoint end : ends) {
            Equipment via = ctx.model2.equipment.get(end.via());
            if (n.members.contains(end.via()) && via != null && Network.BRANCH.equals(via.role)) {
                chosen = end.via();
                where = end.key().startsWith(LogicalLine.OPEN) ? "the open end" : ctx.model2.name(end.key());
                break;
            }
        }
        if (chosen == null) {
            ctx.matches.reason(Side.MODEL1, member, "the model 2 line has " + n.branchCount + " sections", n.members);
            return;
        }
        List<String> others = new ArrayList<>();
        for (String m : n.members) {
            if (!m.equals(chosen)) {
                others.add(GridData.localId(m));
                ctx.matches.reason(Side.MODEL2, m, "part of a line matched as a whole to model 1 "
                        + GridData.localId(member), List.of(member));
            }
        }
        if (ctx.matches.add(member, chosen, method, MatchContext.join(note, "model 2 line has " + n.branchCount
                + " sections; mapped to the one at " + where + "; the others: " + String.join(", ", others)))) {
            ctx.matches.markPartial(member);
        }
    }

    private Refinement lineGraph(LogicalLine p, LogicalLine n, boolean relaxed) {
        Refinement g = new Refinement();
        addLine(g, Side.MODEL1, p, relaxed);
        addLine(g, Side.MODEL2, n, relaxed);
        return g;
    }

    private void addLine(Refinement g, Side side, LogicalLine line, boolean relaxed) {
        Network net = ctx.net(side);
        Set<String> interior = new HashSet<>(line.interiorNodes);
        for (String nodeIri : line.interiorNodes) {
            addNode(g, side, nodeIri);
        }
        List<String> elements = new ArrayList<>(line.members);
        elements.addAll(line.attachments);
        for (String eqIri : elements) {
            Equipment eq = net.equipment.get(eqIri);
            int e = addEquipment(g, side, eq, "", relaxed);
            for (TerminalRec t : eq.terminals) {
                if (t.node() != null && interior.contains(t.node())) {
                    g.link(e, g.find(side, t.node()), "t");
                }
            }
        }
        int i = 0;
        for (LogicalLine.Endpoint end : line.endpoints) {
            int pseudo = g.add(side, "end#" + i++, "END:" + sharedKey(side, end.key()), true, false, null);
            Integer at = interior.contains(end.node()) ? g.find(side, end.node()) : g.find(side, end.via());
            if (at != null) {
                g.link(pseudo, at, "t");
            }
        }
    }

    // ---- shared ---------------------------------------------------------------------------------

    private int addNode(Refinement g, Side side, String nodeIri) {
        String partner = partner(side, nodeIri);
        Network net = ctx.net(side);
        return g.add(side, nodeIri, partner == null ? "N" : "M:" + partner, partner != null, false,
                Names.key(net.name(nodeIri)));
    }

    private int addEquipment(Refinement g, Side side, Equipment eq, String remote, boolean relaxed) {
        String partner = partner(side, eq.iri);
        boolean isSwitch = Network.isSwitch(eq.role);
        String cls = relaxed && isSwitch ? "switch" : eq.type;
        String label = partner != null ? "M:" + partner : "E|" + cls + "|" + eq.terminals.size() + "|" + remote;
        return g.add(side, eq.iri, label, partner != null, !isSwitch, Names.key(ctx.net(side).name(eq.iri)));
    }

    /** The shared identity of a matched element (the model 2 IRI), or null. */
    private String partner(Side side, String iri) {
        if (trustedOnly && !trustedMatch(side, iri)) {
            return null;
        }
        if (side == Side.MODEL1) {
            return ctx.matches.toModel2(iri);
        }
        return ctx.matches.toModel1(iri) == null ? null : iri;
    }

    /**
     * Records the pairs. A pair's position is sure when its whole surroundings agree and its class
     * is the same (a symmetric position told apart by an equal name counts as sure); it is certain
     * when its context, the station or line pair, is certain too, and otherwise waits for that
     * context in {@link MatchContext#promote()}.
     */
    private boolean apply(List<Pair> pairs, String method, String lineNote, boolean relaxed, boolean contextCertain,
                          Object context, Refinement check) {
        boolean progress = false;
        for (Pair pair : pairs) {
            String note = lineNote;
            if (pair.byName()) {
                note = MatchContext.join(note, "symmetric position, told apart by name");
            } else if (pair.depth() == 0 && pair.stableDepth() > 0) {
                note = MatchContext.join(note, "the only one of its class left here; position not confirmed");
            } else if (pair.depth() < pair.stableDepth()) {
                note = MatchContext.join(note, "surroundings agree " + pair.depth() + " step(s) deep only");
            }
            String model1Type = ctx.model1.type(pair.model1());
            String model2Type = ctx.model2.type(pair.model2());
            boolean classDiffers = relaxed && model1Type != null && !model1Type.equals(model2Type);
            if (classDiffers) {
                note = MatchContext.join(note, "class differs (model 1 " + model1Type + " vs model 2 " + model2Type + ")");
            }
            boolean positionSure = !classDiffers && check.confirms(pair.model1(), pair.model2());
            if (!positionSure && !weakLineEnds && ctx.model1.lineOfMember.containsKey(pair.model1())
                    && ctx.model2.lineOfMember.containsKey(pair.model2())) {
                continue;
            }
            if (ctx.matches.add(pair.model1(), pair.model2(), method, note, contextCertain && positionSure)) {
                progress = true;
                if (positionSure) {
                    ctx.matches.certainWhen(pair.model1(), context);
                }
                LogicalLine lp = ctx.model1.lineOfMember.get(pair.model1());
                LogicalLine ln = ctx.model2.lineOfMember.get(pair.model2());
                if (lp != null && ln != null) {
                    ctx.matchLines(lp, ln, method, ctx.lineDifferences(lp, ln), positionSure);
                }
            }
        }
        return progress;
    }

    private void recordReasons(Refinement g, List<String> model1Nodes, Side model1Side, List<String> model2Nodes, Side model2Side) {
        if (g == null) {
            return;
        }
        recordFor(g, model1Nodes, model1Side);
        recordFor(g, model2Nodes, model2Side);
    }

    private void recordFor(Refinement g, List<String> nodeIris, Side side) {
        Network net = ctx.net(side);
        Set<String> objects = new LinkedHashSet<>(nodeIris);
        for (String nodeIri : nodeIris) {
            for (TerminalRec t : net.node(nodeIri).terminals) {
                LogicalLine line = net.lineOfMember.get(t.equipment());
                boolean lineMatched = line != null
                        && (side == Side.MODEL1 ? ctx.lineModel1ToModel2.containsKey(line) : ctx.lineModel2ToModel1.containsKey(line));
                if (!lineMatched) {
                    // a section of a matched line keeps the reason its line alignment gave
                    objects.add(t.equipment());
                }
            }
        }
        recordLineReasons(g, new ArrayList<>(objects), side);
    }

    private void recordLineReasons(Refinement g, List<String> objects, Side side) {
        if (g == null) {
            return;
        }
        for (String iri : objects) {
            if (ctx.matches.matched(side, iri)) {
                continue;
            }
            List<String> candidates = g.candidates(side, iri);
            String why = candidates.size() > 1
                    ? "ambiguous: " + candidates.size() + " elements in the same position"
                    : candidates.size() == 1 ? "ambiguous: shares its position with another element"
                    : "no counterpart in the same position";
            ctx.matches.reason(side, iri, why, candidates);
        }
    }
}
