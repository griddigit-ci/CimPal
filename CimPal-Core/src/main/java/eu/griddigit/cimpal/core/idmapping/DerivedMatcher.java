/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.GridData.ContainmentRec;
import eu.griddigit.cimpal.core.idmapping.GridData.DependentRec;
import eu.griddigit.cimpal.core.idmapping.GridData.EndRec;
import eu.griddigit.cimpal.core.idmapping.GridData.ObjectInfo;
import eu.griddigit.cimpal.core.idmapping.GridData.ReferenceRec;
import eu.griddigit.cimpal.core.idmapping.GridData.TerminalRec;
import eu.griddigit.cimpal.core.idmapping.Network.Equipment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Matches that follow from others: objects with the same ID first, then, once the topology is
 * matched, terminals, nodes, transformer ends, containers (bays, voltage levels, lines, leftover
 * substations), dependent objects, objects other objects refer to and, last, other objects by
 * unique name.
 */
final class DerivedMatcher {

    private final MatchContext ctx;

    DerivedMatcher(MatchContext ctx) {
        this.ctx = ctx;
    }

    /** Objects of the same class with the same ID on both sides. */
    int sameIds() {
        Map<String, ObjectInfo> model2ById = new HashMap<>();
        for (ObjectInfo info : ctx.model2.data.objects.values()) {
            model2ById.putIfAbsent(info.id() + '|' + info.type(), info);
        }
        int added = 0;
        for (ObjectInfo info : ctx.model1.data.objects.values()) {
            ObjectInfo other = model2ById.get(info.id() + '|' + info.type());
            if (other != null && ctx.matches.add(info.iri(), other.iri(), "same-id", null, true)) {
                added++;
            }
        }
        return added;
    }

    /** Everything that follows from the matched topology, in dependency order. */
    void run() {
        terminals();
        nodesFromTerminals();
        transformerEnds();
        containers("Bay", c -> "Bay".equals(c.containerType()) ? c.container() : null);
        containers("VoltageLevel", ContainmentRec::voltageLevel);
        containers("Line", c -> "Line".equals(c.containerType()) ? c.container() : null);
        containers("Substation", ContainmentRec::substation);
        containers("DCConverterUnit", c -> "DCConverterUnit".equals(c.containerType()) ? c.container() : null);
        if (ctx.options.isIncludeDependents()) {
            dependents();
        }
        if (ctx.options.isIncludeOthers()) {
            while (references()) {
                // limit types, load groups, areas and regions: each round may enable the next
            }
            othersByName();
        }
    }

    /**
     * Objects outside the topology by the matched objects that refer to them through the same
     * property: more than half of the votes on both sides; returns whether anything matched.
     */
    private boolean references() {
        Map<String, Map<String, Integer>> votes = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> backVotes = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Integer> backTotals = new HashMap<>();
        Set<String> shaky = new HashSet<>();
        countVotes(Side.MODEL1, votes, totals, shaky);
        countVotes(Side.MODEL2, backVotes, backTotals, new HashSet<>());
        boolean progress = false;
        for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
            Map.Entry<String, Integer> best = best(e.getValue());
            if (best == null || 2 * best.getValue() <= totals.get(e.getKey())) {
                continue;
            }
            String target = best.getKey();
            Map<String, Integer> back = backVotes.get(target);
            Map.Entry<String, Integer> backBest = back == null ? null : best(back);
            if (backBest == null || !backBest.getKey().equals(e.getKey())
                    || 2 * backBest.getValue() <= backTotals.get(target)
                    || !Objects.equals(ctx.model1.type(e.getKey()), ctx.model2.type(target))) {
                continue;
            }
            int total = totals.get(e.getKey());
            String note = best.getValue() < total ? best.getValue() + " of " + total + " referring objects agree" : null;
            boolean sure = note == null && backBest.getValue().equals(backTotals.get(target)) && !shaky.contains(e.getKey());
            progress |= ctx.matches.add(e.getKey(), target, "references", note, sure);
        }
        return progress;
    }

    /**
     * Votes of one side's matched referrers for the other side's targets of the same property;
     * {@code shaky} collects the targets that a probable match votes for.
     */
    private void countVotes(Side side, Map<String, Map<String, Integer>> votes, Map<String, Integer> totals,
                            Set<String> shaky) {
        Network net = ctx.net(side);
        Network other = ctx.net(side == Side.MODEL1 ? Side.MODEL2 : Side.MODEL1);
        Map<String, Set<String>> otherTargets = new HashMap<>();
        for (ReferenceRec r : other.data.references) {
            otherTargets.computeIfAbsent(r.referrer() + '|' + r.property(), k -> new HashSet<>()).add(r.target());
        }
        for (ReferenceRec r : net.data.references) {
            if (ctx.matches.matched(side, r.target()) || net.category(r.target()) != Network.Category.OTHER) {
                continue;
            }
            String partner = side == Side.MODEL1 ? ctx.matches.toModel2(r.referrer()) : ctx.matches.toModel1(r.referrer());
            if (partner == null) {
                continue;
            }
            totals.merge(r.target(), 1, Integer::sum);
            if (!(side == Side.MODEL1 ? ctx.matches.isCertain(r.referrer()) : ctx.matches.isCertainModel2(r.referrer()))) {
                shaky.add(r.target());
            }
            Set<String> targets = otherTargets.get(partner + '|' + r.property());
            if (targets != null && targets.size() == 1) {
                String t = targets.iterator().next();
                if (!ctx.matches.matched(side == Side.MODEL1 ? Side.MODEL2 : Side.MODEL1, t)) {
                    votes.computeIfAbsent(r.target(), k -> new HashMap<>()).merge(t, 1, Integer::sum);
                }
            }
        }
    }

    /** Terminals of matched equipment: by matched node, then the one left, then by sequence number. */
    private void terminals() {
        for (Map.Entry<String, String> e : List.copyOf(ctx.matches.model1ToModel2().entrySet())) {
            Equipment a = ctx.model1.equipment.get(e.getKey());
            Equipment b = ctx.model2.equipment.get(e.getValue());
            if (a == null || b == null) {
                continue;
            }
            List<TerminalRec> left = new ArrayList<>(a.terminals);
            List<TerminalRec> right = new ArrayList<>(b.terminals);
            left.removeIf(t -> ctx.matches.matched(Side.MODEL1, t.terminal()));
            right.removeIf(t -> ctx.matches.matched(Side.MODEL2, t.terminal()));
            String owner = ctx.matches.note(e.getKey()) != null && "topology".equals(ctx.matches.method(e.getKey()))
                    ? "see the note on its equipment" : null;
            boolean sure = ctx.matches.isCertain(e.getKey());
            for (TerminalRec t : List.copyOf(left)) {
                String node = ctx.matches.toModel2(t.node());
                if (node == null) {
                    continue;
                }
                List<TerminalRec> at = right.stream().filter(r -> node.equals(r.node())).toList();
                if (at.size() == 1 && ctx.matches.add(t.terminal(), at.get(0).terminal(), "equipment", owner, sure)) {
                    left.remove(t);
                    right.remove(at.get(0));
                }
            }
            if (ctx.matches.isPartial(e.getKey())) {
                // a line section matched to part of a line, or several to one: only terminals at matched nodes pair
                continue;
            }
            if (left.size() == 1 && right.size() == 1 && a.terminals.size() == b.terminals.size()) {
                ctx.matches.add(left.get(0).terminal(), right.get(0).terminal(), "equipment", owner, sure);
            } else if (!left.isEmpty() && left.size() == right.size()) {
                for (TerminalRec t : left) {
                    for (TerminalRec r : right) {
                        if (t.sequenceNumber() > 0 && t.sequenceNumber() == r.sequenceNumber()) {
                            ctx.matches.add(t.terminal(), r.terminal(), "equipment",
                                    MatchContext.join(owner, "by sequence number"));
                        }
                    }
                }
            }
        }
    }

    /**
     * Nodes of matched terminals: when all terminals of both nodes are matched and lead to each
     * other. A node whose terminals are only partly matched may have been merged with another.
     */
    private void nodesFromTerminals() {
        Map<String, Set<String>> proposals = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        for (TerminalRec t : ctx.model1.data.terminals) {
            String other = ctx.matches.toModel2(t.terminal());
            TerminalRec r = other == null ? null : ctx.model2.terminals.get(other);
            if (t.node() == null || r == null || r.node() == null) {
                continue;
            }
            proposals.computeIfAbsent(t.node(), k -> new HashSet<>()).add(r.node());
            reverse.computeIfAbsent(r.node(), k -> new HashSet<>()).add(t.node());
        }
        for (Map.Entry<String, Set<String>> e : proposals.entrySet()) {
            if (e.getValue().size() != 1) {
                ctx.matches.reason(Side.MODEL1, e.getKey(), "its terminals lead to different nodes", List.copyOf(e.getValue()));
                continue;
            }
            String target = e.getValue().iterator().next();
            if (reverse.get(target).size() == 1 && allTerminalsMatched(Side.MODEL1, e.getKey())
                    && allTerminalsMatched(Side.MODEL2, target)) {
                boolean sure = ctx.model2.node(target) != null && ctx.model1.node(e.getKey()).terminals.stream()
                        .allMatch(t -> ctx.matches.isCertain(t.terminal()));
                ctx.matches.add(e.getKey(), target, "terminals", null, sure);
            } else {
                ctx.matches.reason(Side.MODEL1, e.getKey(), "its matched terminals do not account for the whole node",
                        List.of(target));
            }
        }
    }

    /** Whether every terminal at the node is matched, so the node's counterpart is fully determined. */
    private boolean allTerminalsMatched(Side side, String node) {
        Network.NodeInfo info = ctx.net(side).node(node);
        if (info == null) {
            return false;
        }
        for (TerminalRec t : info.terminals) {
            if (!ctx.matches.matched(side, t.terminal())) {
                return false;
            }
        }
        return true;
    }

    /** Ends of matched transformers: through their matched terminals, else by rated-voltage rank. */
    private void transformerEnds() {
        Map<String, List<EndRec>> model2Ends = new HashMap<>();
        for (EndRec end : ctx.model2.data.ends) {
            model2Ends.computeIfAbsent(end.transformer(), t -> new ArrayList<>()).add(end);
        }
        Map<String, List<EndRec>> model1Ends = new HashMap<>();
        for (EndRec end : ctx.model1.data.ends) {
            model1Ends.computeIfAbsent(end.transformer(), t -> new ArrayList<>()).add(end);
        }
        for (Map.Entry<String, List<EndRec>> e : model1Ends.entrySet()) {
            String other = ctx.matches.toModel2(e.getKey());
            List<EndRec> theirs = other == null ? null : model2Ends.get(other);
            if (theirs == null) {
                continue;
            }
            for (EndRec end : e.getValue()) {
                String terminal = ctx.matches.toModel2(end.terminal());
                for (EndRec candidate : theirs) {
                    if (terminal != null && terminal.equals(candidate.terminal())) {
                        ctx.matches.add(end.end(), candidate.end(), "transformer", null, ctx.matches.isCertain(e.getKey()));
                    }
                }
            }
            List<EndRec> left = e.getValue().stream().filter(x -> !ctx.matches.matched(Side.MODEL1, x.end())).toList();
            List<EndRec> right = theirs.stream().filter(x -> !ctx.matches.matched(Side.MODEL2, x.end())).toList();
            Map<Integer, EndRec> leftRank = rankByVoltage(left);
            Map<Integer, EndRec> rightRank = rankByVoltage(right);
            if (leftRank != null && rightRank != null && leftRank.size() == rightRank.size()) {
                for (Map.Entry<Integer, EndRec> r : leftRank.entrySet()) {
                    ctx.matches.add(r.getValue().end(), rightRank.get(r.getKey()).end(), "transformer",
                            "by rated voltage rank", ctx.matches.isCertain(e.getKey()));
                }
            }
        }
    }

    /** Ends ranked by rated voltage, highest first; null when a voltage is missing or repeated. */
    private static Map<Integer, EndRec> rankByVoltage(List<EndRec> ends) {
        List<EndRec> sorted = new ArrayList<>(ends);
        if (sorted.stream().anyMatch(e -> e.ratedU() == null)) {
            return null;
        }
        sorted.sort((x, y) -> Double.compare(y.ratedU(), x.ratedU()));
        Map<Integer, EndRec> ranked = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0 && sorted.get(i).ratedU().equals(sorted.get(i - 1).ratedU())) {
                return null;
            }
            ranked.put(i, sorted.get(i));
        }
        return ranked;
    }

    /**
     * Containers of one class by the matched objects they hold: a model 1 container matches the new
     * container that more than half of its matched members point to, when that container's matched
     * members point back to it by more than half too.
     */
    private void containers(String type, Function<ContainmentRec, String> containerOf) {
        Map<String, Map<String, Integer>> votes = new HashMap<>();
        Map<String, Map<String, Integer>> backVotes = new HashMap<>();
        Map<String, Integer> model1MatchedMembers = new HashMap<>();
        Map<String, Integer> model2MatchedMembers = new HashMap<>();
        Set<String> shaky = new HashSet<>();
        for (Map.Entry<String, ContainmentRec> e : ctx.model1.data.containment.entrySet()) {
            String container = containerOf.apply(e.getValue());
            String other = ctx.matches.toModel2(e.getKey());
            if (container == null || other == null) {
                continue;
            }
            ContainmentRec oc = ctx.model2.data.containment.get(other);
            String otherContainer = oc == null ? null : containerOf.apply(oc);
            model1MatchedMembers.merge(container, 1, Integer::sum);
            if (!ctx.matches.isCertain(e.getKey())) {
                shaky.add(container);
            }
            if (otherContainer != null) {
                votes.computeIfAbsent(container, k -> new HashMap<>()).merge(otherContainer, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, ContainmentRec> e : ctx.model2.data.containment.entrySet()) {
            String container = containerOf.apply(e.getValue());
            String other = ctx.matches.toModel1(e.getKey());
            if (container == null || other == null) {
                continue;
            }
            ContainmentRec oc = ctx.model1.data.containment.get(other);
            String otherContainer = oc == null ? null : containerOf.apply(oc);
            model2MatchedMembers.merge(container, 1, Integer::sum);
            if (otherContainer != null) {
                backVotes.computeIfAbsent(container, k -> new HashMap<>()).merge(otherContainer, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Map<String, Integer>> e : votes.entrySet()) {
            String model1Container = e.getKey();
            if (ctx.matches.matched(Side.MODEL1, model1Container) || !type.equals(ctx.model1.type(model1Container))) {
                continue;
            }
            Map.Entry<String, Integer> best = best(e.getValue());
            if (best == null || 2 * best.getValue() <= model1MatchedMembers.get(model1Container)) {
                continue;
            }
            String model2Container = best.getKey();
            if (!type.equals(ctx.model2.type(model2Container)) || !backVotes.containsKey(model2Container)) {
                continue;
            }
            Map.Entry<String, Integer> back = best(backVotes.get(model2Container));
            if (back == null || !back.getKey().equals(model1Container)
                    || 2 * back.getValue() <= model2MatchedMembers.get(model2Container)) {
                continue;
            }
            int total = model1MatchedMembers.get(model1Container);
            String note = best.getValue() < total
                    ? best.getValue() + " of " + total + " matched members agree" : null;
            boolean unanimous = best.getValue() == total && back.getValue().equals(model2MatchedMembers.get(model2Container));
            ctx.matches.add(model1Container, model2Container, "containment", note, unanimous && !shaky.contains(model1Container));
        }
    }

    private static Map.Entry<String, Integer> best(Map<String, Integer> counts) {
        Map.Entry<String, Integer> best = null;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (best == null || e.getValue() > best.getValue()) {
                best = e;
                tie = false;
            } else if (e.getValue().equals(best.getValue())) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /**
     * Dependents of matched owners, in rounds: per owner pair, association and class, the one
     * dependent on each side, or else those with unique equal names.
     */
    private void dependents() {
        Map<String, List<DependentRec>> model1ByOwner = byOwner(ctx.model1.data.dependents);
        Map<String, List<DependentRec>> model2ByOwner = byOwner(ctx.model2.data.dependents);
        boolean progress = true;
        while (progress) {
            progress = false;
            Map<String, Set<String>> proposals = new LinkedHashMap<>();
            Map<String, Set<String>> reverse = new HashMap<>();
            Set<String> underUncertainOwner = new HashSet<>();
            for (Map.Entry<String, List<DependentRec>> e : model1ByOwner.entrySet()) {
                String other = ctx.matches.toModel2(e.getKey());
                List<DependentRec> theirs = other == null ? null : model2ByOwner.get(other);
                if (theirs == null) {
                    continue;
                }
                if (!ctx.matches.isCertain(e.getKey())) {
                    e.getValue().forEach(d -> underUncertainOwner.add(d.dependent()));
                }
                Map<String, List<String>> left = group(Side.MODEL1, e.getValue());
                Map<String, List<String>> right = group(Side.MODEL2, theirs);
                for (Map.Entry<String, List<String>> g : left.entrySet()) {
                    List<String> ours = g.getValue();
                    List<String> cands = right.getOrDefault(g.getKey(), List.of());
                    if (ours.size() == 1 && cands.size() == 1) {
                        propose(proposals, reverse, ours.get(0), cands.get(0));
                    } else if (!ours.isEmpty() && !cands.isEmpty()) {
                        Map<String, List<String>> model1Names = byName(Side.MODEL1, ours);
                        Map<String, List<String>> model2Names = byName(Side.MODEL2, cands);
                        for (Map.Entry<String, List<String>> n : model1Names.entrySet()) {
                            List<String> match = model2Names.get(n.getKey());
                            if (n.getValue().size() == 1 && match != null && match.size() == 1) {
                                propose(proposals, reverse, n.getValue().get(0), match.get(0));
                            }
                        }
                        for (String d : ours) {
                            ctx.matches.reason(Side.MODEL1, d, "ambiguous: " + cands.size() + " candidates under the matched owner", cands);
                        }
                    } else {
                        for (String d : ours) {
                            ctx.matches.reason(Side.MODEL1, d, "no counterpart under the matched owner", List.of());
                        }
                    }
                }
            }
            for (Map.Entry<String, Set<String>> e : proposals.entrySet()) {
                if (e.getValue().size() == 1) {
                    String target = e.getValue().iterator().next();
                    if (reverse.get(target).size() == 1 && ctx.matches.add(e.getKey(), target, "owner", null,
                            !underUncertainOwner.contains(e.getKey()))) {
                        progress = true;
                    }
                }
            }
        }
    }

    private static Map<String, List<DependentRec>> byOwner(List<DependentRec> dependents) {
        Map<String, List<DependentRec>> map = new LinkedHashMap<>();
        for (DependentRec d : dependents) {
            map.computeIfAbsent(d.owner(), k -> new ArrayList<>()).add(d);
        }
        return map;
    }

    /** A key value normalised so that "3" and "3.0" compare equal. */
    private static String keyValue(String key) {
        try {
            return MatchContext.fmt(Double.parseDouble(key));
        } catch (NumberFormatException ex) {
            return key;
        }
    }

    /** Unmatched dependents keyed by association, class and key. */
    private Map<String, List<String>> group(Side side, List<DependentRec> dependents) {
        Network net = ctx.net(side);
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (DependentRec d : dependents) {
            if (ctx.matches.matched(side, d.dependent())) {
                continue;
            }
            String key = d.association() + '|' + net.type(d.dependent()) + (d.key() == null ? "" : '|' + keyValue(d.key()));
            List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
            if (!list.contains(d.dependent())) {
                list.add(d.dependent());
            }
        }
        return map;
    }

    private Map<String, List<String>> byName(Side side, List<String> iris) {
        Map<String, List<String>> map = new HashMap<>();
        for (String iri : iris) {
            String key = Names.key(ctx.net(side).name(iri));
            if (key != null) {
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(iri);
            }
        }
        return map;
    }

    private static void propose(Map<String, Set<String>> proposals, Map<String, Set<String>> reverse, String model1, String model2) {
        proposals.computeIfAbsent(model1, k -> new HashSet<>()).add(model2);
        reverse.computeIfAbsent(model2, k -> new HashSet<>()).add(model1);
    }

    /** Remaining objects outside the topology, matched when class and name are unique on both sides. */
    private void othersByName() {
        Map<String, List<String>> model1 = othersKeyed(Side.MODEL1);
        Map<String, List<String>> model2 = othersKeyed(Side.MODEL2);
        for (Map.Entry<String, List<String>> e : model1.entrySet()) {
            List<String> other = model2.get(e.getKey());
            if (e.getValue().size() == 1 && other != null && other.size() == 1) {
                ctx.matches.add(e.getValue().get(0), other.get(0), "name-only", "matched by class and name only");
            }
        }
    }

    private Map<String, List<String>> othersKeyed(Side side) {
        Network net = ctx.net(side);
        Map<String, List<String>> map = new HashMap<>();
        for (ObjectInfo info : net.data.objects.values()) {
            if (net.data.shared.contains(info.iri()) || ctx.matches.matched(side, info.iri())
                    || net.category(info.iri()) != Network.Category.OTHER) {
                continue;
            }
            String key = Names.key(info.name());
            if (key != null) {
                map.computeIfAbsent(info.type() + '|' + key, k -> new ArrayList<>()).add(info.iri());
            }
        }
        return map;
    }
}
