/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Matches substations and the logical lines between them.
 * <ul>
 *   <li>Seeds: substations with the same name (unique on both sides).</li>
 *   <li>Lines between matched substations: one line on each side matches; parallel lines are told
 *       apart by name, then by voltage and parameters (r, x, bch, length), then by voltage.</li>
 *   <li>Substations from matched lines: the far end of a matched line whose near end is matched,
 *       or the end two matched lines have in common.</li>
 *   <li>Substations by their connections: the same matched neighbours, unique on both sides.</li>
 *   <li>When structure stalls, lines by voltage and parameters alone, first those with one end
 *       already matched, then any, again unique on both sides.</li>
 * </ul>
 * Every rule needs a unique answer on both sides; anything ambiguous stays unmatched.
 */
final class SubstationLineMatcher {

    private final MatchContext ctx;
    private final Map<String, List<LogicalLine>> model1LinesAt = new HashMap<>();
    private final Map<String, List<LogicalLine>> model2LinesAt = new HashMap<>();

    SubstationLineMatcher(MatchContext ctx) {
        this.ctx = ctx;
        index(ctx.model1, model1LinesAt);
        index(ctx.model2, model2LinesAt);
    }

    private static void index(Network net, Map<String, List<LogicalLine>> at) {
        for (LogicalLine line : net.lines) {
            for (String key : new HashSet<>(line.endpointKeys())) {
                at.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
            }
        }
    }

    /**
     * Matches stations whose names (through the alias table) are unique and equal on both sides. A
     * station grouping several substations goes by any of their names, and all names of a station
     * must point to the same station on the other side.
     */
    int seedByName() {
        Map<String, Set<String>> model1ByName = byName(Side.MODEL1);
        Map<String, Set<String>> model2ByName = byName(Side.MODEL2);
        Map<String, Set<String>> proposals = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : model1ByName.entrySet()) {
            Set<String> other = model2ByName.get(e.getKey());
            if (e.getValue().size() == 1 && other != null && other.size() == 1) {
                String model1 = e.getValue().iterator().next();
                String model2 = other.iterator().next();
                proposals.computeIfAbsent(model1, k -> new HashSet<>()).add(model2);
                reverse.computeIfAbsent(model2, k -> new HashSet<>()).add(model1);
            }
        }
        int added = 0;
        for (Map.Entry<String, Set<String>> e : proposals.entrySet()) {
            if (e.getValue().size() != 1) {
                continue;
            }
            String model2 = e.getValue().iterator().next();
            if (reverse.get(model2).size() == 1 && matchStations(e.getKey(), model2, "name", null, true)) {
                added++;
            }
        }
        return added;
    }

    private Map<String, Set<String>> byName(Side side) {
        Network net = ctx.net(side);
        Map<String, Set<String>> map = new HashMap<>();
        for (String station : net.substations) {
            if (ctx.matches.matched(side, station)) {
                continue;
            }
            for (String name : net.stationNames(station)) {
                String key = ctx.names.canonical(name);
                if (key != null) {
                    map.computeIfAbsent(key, k -> new HashSet<>()).add(station);
                }
            }
        }
        return map;
    }

    /**
     * Matches two stations, noting when either groups several substations; certain only when
     * {@code sure} and the names agree or one abbreviates the other.
     */
    private boolean matchStations(String model1, String model2, String method, String note, boolean sure) {
        String split = splitNote(model1, model2);
        boolean certain = sure && namesSimilar(model1, model2);
        return ctx.matches.add(model1, model2, method, MatchContext.join(MatchContext.join(note, nameNote(model1, model2)), split),
                certain);
    }

    private String splitNote(String model1, String model2) {
        List<String> a = ctx.model1.stationNames(model1);
        List<String> b = ctx.model2.stationNames(model2);
        if (a.size() <= 1 && b.size() <= 1) {
            return null;
        }
        return "one station: model 1 " + String.join(" + ", a) + ", new " + String.join(" + ", b);
    }

    /** Whether some name of the one station is equal to, or abbreviates, some name of the other. */
    private boolean namesSimilar(String model1, String model2) {
        for (String a : ctx.model1.stationNames(model1)) {
            for (String b : ctx.model2.stationNames(model2)) {
                if (ctx.names.similar(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One pass of the structural rules; returns whether anything new matched. */
    boolean structuralPass() {
        boolean changed = matchLinesBetweenMatchedEnds();
        changed |= inferSubstationsFromLines();
        changed |= matchSubstationsByConnections();
        return changed;
    }

    // ---- lines between matched substations ------------------------------------------------

    private boolean matchLinesBetweenMatchedEnds() {
        Map<String, List<LogicalLine>> model2Buckets = new LinkedHashMap<>();
        for (LogicalLine n : ctx.model2.lines) {
            if (!ctx.lineModel2ToModel1.containsKey(n) && !n.endpoints.isEmpty()) {
                model2Buckets.computeIfAbsent(String.join("|", n.endpointKeys()), k -> new ArrayList<>()).add(n);
            }
        }
        Map<String, List<LogicalLine>> model1Buckets = new LinkedHashMap<>();
        for (LogicalLine p : ctx.model1.lines) {
            if (ctx.lineModel1ToModel2.containsKey(p) || p.endpoints.isEmpty()) {
                continue;
            }
            List<String> mapped = mappedKeys(p);
            if (mapped != null) {
                model1Buckets.computeIfAbsent(String.join("|", mapped), k -> new ArrayList<>()).add(p);
            }
        }
        boolean changed = false;
        for (Map.Entry<String, List<LogicalLine>> e : model1Buckets.entrySet()) {
            List<LogicalLine> model1Lines = e.getValue();
            List<LogicalLine> model2Lines = model2Buckets.getOrDefault(e.getKey(), new ArrayList<>());
            if (model2Lines.isEmpty()) {
                for (LogicalLine p : model1Lines) {
                    reason(Side.MODEL1, p, "no line between the matched substations in model 2", List.of());
                }
                continue;
            }
            changed |= resolveBucket(model1Lines, model2Lines);
        }
        return changed;
    }

    /** The model 1 line's endpoint keys on the model 2 side, sorted; null while any end is unmatched. */
    private List<String> mappedKeys(LogicalLine p) {
        List<String> mapped = new ArrayList<>();
        for (String key : p.endpointKeys()) {
            String m = ctx.mapKey(key);
            if (m == null) {
                return null;
            }
            mapped.add(m);
        }
        mapped.sort(null);
        return mapped;
    }

    /** Matches lines that connect the same matched substations. */
    private boolean resolveBucket(List<LogicalLine> model1Lines, List<LogicalLine> model2Lines) {
        List<LogicalLine> ps = new ArrayList<>(model1Lines);
        List<LogicalLine> ns = new ArrayList<>(model2Lines);
        boolean changed = false;
        boolean progress = true;
        while (progress && !ps.isEmpty() && !ns.isEmpty()) {
            progress = false;
            if (ps.size() == 1 && ns.size() == 1) {
                LogicalLine p = ps.get(0);
                LogicalLine n = ns.get(0);
                boolean sure = ctx.voltagesAgree(p.nominalVoltage, n.nominalVoltage);
                if (ctx.matchLines(p, n, "line-structure", ctx.lineDifferences(p, n), sure)) {
                    return true;
                }
                break;
            }
            List<BiPredicate<LogicalLine, LogicalLine>> rules = List.of(
                    (p, n) -> !intersection(p.nameKeys, n.nameKeys).isEmpty()
                            && ctx.voltagesAgree(p.nominalVoltage, n.nominalVoltage),
                    (p, n) -> ctx.voltagesAgree(p.nominalVoltage, n.nominalVoltage) && ctx.parametersAgree(p, n, 1),
                    (p, n) -> ctx.voltagesAgree(p.nominalVoltage, n.nominalVoltage)
                            && p.nominalVoltage != null && n.nominalVoltage != null);
            for (int r = 0; r < rules.size(); r++) {
                List<LogicalLine[]> pairs = mutualUnique(ps, ns, rules.get(r));
                // told apart by name: certain; by parameters or voltage alone: parallel lines may share them
                boolean byName = r == 0;
                for (LogicalLine[] pair : pairs) {
                    String note = MatchContext.join(ctx.lineDifferences(pair[0], pair[1]),
                            byName ? null : "parallel lines told apart by " + (r == 1 ? "parameters" : "voltage"));
                    if (ctx.matchLines(pair[0], pair[1], "line-structure", note, byName)) {
                        ps.remove(pair[0]);
                        ns.remove(pair[1]);
                        changed = true;
                        progress = true;
                    }
                }
                if (progress) {
                    break;
                }
            }
        }
        for (LogicalLine p : ps) {
            reason(Side.MODEL1, p, ns.isEmpty() ? "no line left between the matched substations in model 2"
                    : "ambiguous: " + ns.size() + " parallel lines in model 2", firstMembers(ns));
        }
        for (LogicalLine n : ns) {
            reason(Side.MODEL2, n, ps.isEmpty() ? "no line left between the matched substations in model 1"
                    : "ambiguous: " + ps.size() + " parallel lines in model 1", firstMembers(ps));
        }
        return changed;
    }

    /** Pairs (p, n) where rule holds for p only with n and for n only with p. */
    private static List<LogicalLine[]> mutualUnique(List<LogicalLine> ps, List<LogicalLine> ns,
                                                    BiPredicate<LogicalLine, LogicalLine> rule) {
        Map<LogicalLine, List<LogicalLine>> forward = new LinkedHashMap<>();
        Map<LogicalLine, List<LogicalLine>> backward = new HashMap<>();
        for (LogicalLine p : ps) {
            for (LogicalLine n : ns) {
                if (rule.test(p, n)) {
                    forward.computeIfAbsent(p, k -> new ArrayList<>()).add(n);
                    backward.computeIfAbsent(n, k -> new ArrayList<>()).add(p);
                }
            }
        }
        List<LogicalLine[]> pairs = new ArrayList<>();
        for (Map.Entry<LogicalLine, List<LogicalLine>> e : forward.entrySet()) {
            if (e.getValue().size() == 1 && backward.get(e.getValue().get(0)).size() == 1) {
                pairs.add(new LogicalLine[]{e.getKey(), e.getValue().get(0)});
            }
        }
        return pairs;
    }

    // ---- substations from matched lines ----------------------------------------------------

    private boolean inferSubstationsFromLines() {
        List<LogicalLine[]> pairs = new ArrayList<>();
        ctx.lineModel1ToModel2.forEach((p, n) -> pairs.add(new LogicalLine[]{p, n}));
        return inferEnds(pairs, 1, "line-topology");
    }

    /**
     * Matches the unmatched ends of line pairs: the one end left of a line whose other ends are
     * matched, or the end that several lines have in common. An end is matched when the lines
     * touching it agree on one counterpart (and the counterpart's lines on it), with at least
     * {@code minSupport} lines behind it.
     */
    private boolean inferEnds(List<LogicalLine[]> pairs, int minSupport, String method) {
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, Set<String>> reverse = new HashMap<>();
        Map<String, Integer> support = new HashMap<>();
        Map<String, Integer> reverseSupport = new HashMap<>();
        for (LogicalLine[] pair : pairs) {
            List<String> up = new ArrayList<>(pair[0].endpointKeys());
            List<String> un = new ArrayList<>(pair[1].endpointKeys());
            boolean consistent = true;
            for (String key : List.copyOf(up)) {
                String m = ctx.mapKey(key);
                if (m != null) {
                    consistent &= un.remove(m);
                    up.remove(key);
                }
            }
            for (String key : un) {
                consistent &= !ctx.model2KeyMatched(key);
            }
            if (!consistent || up.isEmpty() || up.size() != un.size() || up.size() > 2) {
                continue;
            }
            if (up.size() == 2 && (up.get(0).equals(up.get(1)) || un.get(0).equals(un.get(1)))) {
                continue;
            }
            for (String u : up) {
                intersect(candidates, u, un);
                support.merge(u, 1, Integer::sum);
            }
            for (String v : un) {
                intersect(reverse, v, up);
                reverseSupport.merge(v, 1, Integer::sum);
            }
        }
        boolean changed = false;
        for (String model1Key : candidates.keySet().stream().sorted().toList()) {
            Set<String> c = candidates.get(model1Key);
            if (c.size() != 1 || support.get(model1Key) < minSupport) {
                continue;
            }
            String target = c.iterator().next();
            Set<String> back = reverse.get(target);
            if (back != null && back.size() == 1 && back.contains(model1Key) && reverseSupport.get(target) >= minSupport) {
                changed |= matchKeys(model1Key, target, method);
            }
        }
        return changed;
    }

    private static void intersect(Map<String, Set<String>> map, String key, Collection<String> values) {
        Set<String> current = map.get(key);
        if (current == null) {
            map.put(key, new HashSet<>(values));
        } else {
            current.retainAll(values);
        }
    }

    /** Matches the substations (or open-end nodes) behind two endpoint keys. */
    private boolean matchKeys(String model1Key, String model2Key, String method) {
        boolean model1Open = model1Key.startsWith(LogicalLine.OPEN);
        if (model1Open != model2Key.startsWith(LogicalLine.OPEN)) {
            return false;
        }
        if (model1Open) {
            return ctx.matches.add(model1Key.substring(LogicalLine.OPEN.length()),
                    model2Key.substring(LogicalLine.OPEN.length()), method, null, "line-topology".equals(method));
        }
        if (!ctx.model1.substations.contains(model1Key) || !ctx.model2.substations.contains(model2Key)) {
            return false;
        }
        return matchStations(model1Key, model2Key, method, null, "line-topology".equals(method));
    }

    // ---- substations by their connections --------------------------------------------------

    private boolean matchSubstationsByConnections() {
        boolean changed = false;
        for (int variant = 0; variant < 3 && !changed; variant++) {
            Map<String, List<String>> model1Sigs = signatures(Side.MODEL1, variant);
            Map<String, List<String>> model2Sigs = signatures(Side.MODEL2, variant);
            for (Map.Entry<String, List<String>> e : model1Sigs.entrySet()) {
                List<String> other = model2Sigs.get(e.getKey());
                if (e.getValue().size() == 1 && other != null && other.size() == 1) {
                    String model1Sub = e.getValue().get(0);
                    String model2Sub = other.get(0);
                    changed |= matchStations(model1Sub, model2Sub, "line-topology",
                            variant == 2 ? "some connections differ" : null, variant < 2);
                }
            }
        }
        return changed;
    }

    /**
     * Unmatched substations by connection signature: per line, the far ends in the shared key
     * space. Variant 0 includes line voltages, 1 drops them, 2 also drops unmatched far ends.
     * A signature without any matched far end is not used.
     */
    private Map<String, List<String>> signatures(Side side, int variant) {
        Network net = ctx.net(side);
        Map<String, List<LogicalLine>> linesAt = side == Side.MODEL1 ? model1LinesAt : model2LinesAt;
        Map<String, List<String>> sigs = new HashMap<>();
        for (String sub : net.substations) {
            if (ctx.matches.matched(side, sub)) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            boolean anchored = false;
            for (LogicalLine line : linesAt.getOrDefault(sub, List.of())) {
                List<String> far = new ArrayList<>(line.endpointKeys());
                far.remove(sub);
                List<String> shared = new ArrayList<>();
                for (String key : far) {
                    String s = ctx.sharedKey(side, key);
                    if (!MatchContext.UNKNOWN.equals(s)) {
                        anchored = true;
                    } else if (variant == 2) {
                        continue;
                    }
                    shared.add(s);
                }
                if (variant == 2 && shared.isEmpty()) {
                    continue;
                }
                shared.sort(null);
                String token = String.join(",", shared);
                if (variant == 0) {
                    token += "@" + (line.nominalVoltage == null ? "?" : Math.round(line.nominalVoltage));
                }
                tokens.add(token);
            }
            if (!anchored) {
                continue;
            }
            tokens.sort(null);
            sigs.computeIfAbsent(String.join("|", tokens), k -> new ArrayList<>()).add(sub);
        }
        return sigs;
    }

    // ---- lines by parameters ----------------------------------------------------------------

    /**
     * Pairs unmatched lines by voltage and parameters (unique on both sides) and matches the
     * substations at their ends when at least two such lines agree on them; the lines themselves
     * then match by structure in the next pass. A parameter match alone is never enough: two
     * different lines can have the same values. With {@code anchoredOnly}, only lines with at least
     * one matched end, whose candidate must reach the same matched substations; otherwise lines
     * without matched ends, with at least three parameters agreeing.
     */
    boolean matchByParameters(boolean anchoredOnly) {
        List<LogicalLine> ps = new ArrayList<>();
        for (LogicalLine p : ctx.model1.lines) {
            if (!ctx.lineModel1ToModel2.containsKey(p) && mappedKeys(p) == null) {
                ps.add(p);
            }
        }
        List<LogicalLine> ns = new ArrayList<>();
        for (LogicalLine n : ctx.model2.lines) {
            if (!ctx.lineModel2ToModel1.containsKey(n)) {
                ns.add(n);
            }
        }
        BiPredicate<LogicalLine, LogicalLine> rule = (p, n) -> {
            if (p.endpoints.size() != n.endpoints.size() || p.nominalVoltage == null || n.nominalVoltage == null
                    || !ctx.voltagesAgree(p.nominalVoltage, n.nominalVoltage)) {
                return false;
            }
            int anchors = compatibleEnds(p, n);
            if (anchors < 0 || (anchoredOnly && anchors == 0) || (!anchoredOnly && anchors > 0)) {
                return false;
            }
            return ctx.parametersAgree(p, n, anchoredOnly ? 2 : 3);
        };
        return inferEnds(mutualUnique(ps, ns, rule), 2, "line-parameters");
    }

    /**
     * How many of p's ends are matched and reached by n; -1 when n contradicts p (a matched end
     * that n does not reach, or an end of n matched to a substation p does not reach).
     */
    private int compatibleEnds(LogicalLine p, LogicalLine n) {
        List<String> theirs = new ArrayList<>(n.endpointKeys());
        int anchors = 0;
        for (String key : p.endpointKeys()) {
            String m = ctx.mapKey(key);
            if (m != null) {
                if (!theirs.remove(m)) {
                    return -1;
                }
                anchors++;
            }
        }
        for (String key : theirs) {
            if (ctx.model2KeyMatched(key)) {
                return -1;
            }
        }
        return anchors;
    }

    /**
     * Lines whose already matched elements (same ID, or matched inside a substation) all lie on
     * one unmatched line of the other model are that line.
     */
    boolean linesFromMatchedMembers() {
        boolean changed = false;
        for (LogicalLine p : ctx.model1.lines) {
            if (ctx.lineModel1ToModel2.containsKey(p)) {
                continue;
            }
            Set<LogicalLine> targets = new HashSet<>();
            for (String member : p.members) {
                String other = ctx.matches.toModel2(member);
                LogicalLine n = other == null ? null : ctx.model2.lineOfMember.get(other);
                if (n != null) {
                    targets.add(n);
                }
            }
            if (targets.size() == 1) {
                LogicalLine n = targets.iterator().next();
                if (!ctx.lineModel2ToModel1.containsKey(n)) {
                    boolean sure = p.members.stream().allMatch(m -> ctx.matches.toModel2(m) == null
                            || ctx.matches.isCertain(m) || ctx.matches.pendingOnContext().containsKey(m));
                    changed |= ctx.matchLines(p, n, "line-chain", ctx.lineDifferences(p, n), sure);
                }
            }
        }
        return changed;
    }

    // ---- reporting ------------------------------------------------------------------------------

    /** Reasons for the lines still unmatched once matching is done. */
    void recordFinalReasons() {
        for (LogicalLine p : ctx.model1.lines) {
            if (!ctx.lineModel1ToModel2.containsKey(p) && mappedKeys(p) == null) {
                reason(Side.MODEL1, p, p.endpoints.isEmpty() ? "line not connected to any substation"
                        : "line end substation not matched", List.of());
            }
        }
        for (LogicalLine n : ctx.model2.lines) {
            if (!ctx.lineModel2ToModel1.containsKey(n)) {
                boolean ends = true;
                for (String key : n.endpointKeys()) {
                    ends &= ctx.model2KeyMatched(key);
                }
                if (!ends) {
                    reason(Side.MODEL2, n, n.endpoints.isEmpty() ? "line not connected to any substation"
                            : "line end substation not matched", List.of());
                }
            }
        }
    }

    private void reason(Side side, LogicalLine line, String why, List<String> candidates) {
        for (String member : line.members) {
            ctx.matches.reason(side, member, why, candidates);
        }
    }

    private static List<String> firstMembers(List<LogicalLine> lines) {
        List<String> out = new ArrayList<>();
        for (LogicalLine line : lines) {
            out.add(line.members.get(0));
        }
        return out;
    }

    private String nameNote(String model1Sub, String model2Sub) {
        for (String x : ctx.model1.stationNames(model1Sub)) {
            for (String y : ctx.model2.stationNames(model2Sub)) {
                String kx = ctx.names.canonical(x);
                if (kx != null && kx.equals(ctx.names.canonical(y))) {
                    return null;
                }
            }
        }
        String a = ctx.model1.name(model1Sub);
        String b = ctx.model2.name(model2Sub);
        return "name differs (model 1 '" + (a == null ? "" : a) + "' vs model 2 '" + (b == null ? "" : b) + "')";
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }
}
