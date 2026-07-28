/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.MatchingConfig;
import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.TransformerEndRow;

import java.util.*;

/**
 * Substation and line matching by <b>seeded structural propagation</b>.
 *
 * <p>Real PF and IGMS models disagree on names (PowerFactory descriptive names
 * vs 8-character station codes) and often on impedance units, so neither names
 * nor raw parameters can carry the match on their own. What is invariant is the
 * <em>topology</em>: which substations are connected to which, at what voltage.
 * So:</p>
 * <ol>
 *   <li><b>Seed</b> with the substations whose names match exactly (reliable
 *       anchors) plus any with a globally-unique structural signature.</li>
 *   <li><b>Propagate</b> (Weisfeiler-Lehman): an unmatched substation is
 *       characterised by the multiset of {voltage, matched-neighbour} of its
 *       incident lines plus its transformer labels. When that signature is
 *       unique on both sides it is matched, creating new anchors. Iterate to a
 *       fixpoint. Names break ties only within an otherwise-identical group.</li>
 *   <li><b>Lines</b> between matched substations are matched by voltage and
 *       count (NOT by absolute r/x, which differs by units); parallel circuits
 *       are disambiguated by r/x ratio and then name, else reported.</li>
 * </ol>
 *
 * <p>Deterministic, precision-first: nothing without a unique structural (or
 * name) match is emitted; the rest goes to the report.</p>
 */
public final class SubstationLineMatcher {

    public record SubMatch(String pf, String igms) {
    }

    public record LineMatch(LogicalLine pf, LogicalLine igms) {
    }

    public record UnmatchedLine(LogicalLine line, String reason) {
    }

    public record UnmatchedSub(String id, String reason) {
    }

    public record Result(
            Map<String, String> substationMatch,
            List<SubMatch> substationMatches,
            List<UnmatchedSub> unmatchedSubstationsPf,
            List<UnmatchedSub> unmatchedSubstationsIgms,
            List<LineMatch> lineMatches,
            List<UnmatchedLine> unmatchedLinesPf,
            List<LogicalLine> unmatchedLinesIgms,
            Map<String, String> substationNames
    ) {
    }

    private static final int MAX_WL_ITERATIONS = 30;

    private SubstationLineMatcher() {
    }

    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               MatchingConfig config) {
        SubGraph pf = new SubGraph(pfTables, pfLines);
        SubGraph igms = new SubGraph(igmsTables, igmsLines);

        Map<String, String> pfToIgms = new LinkedHashMap<>();
        Map<String, String> igmsToPf = new LinkedHashMap<>();
        Map<String, Integer> pairId = new HashMap<>();
        int[] nextPair = {0};

        // ---- Seed: exact substation-name matches (reliable anchors) ----
        Map<String, List<String>> pfByName = groupByName(pf);
        Map<String, List<String>> igmsByName = groupByName(igms);
        for (Map.Entry<String, List<String>> e : pfByName.entrySet()) {
            List<String> ps = e.getValue();
            List<String> is = igmsByName.get(e.getKey());
            if (is != null && ps.size() == 1 && is.size() == 1) {
                link(ps.get(0), is.get(0), pfToIgms, igmsToPf, pairId, nextPair);
            }
        }

        // ---- Propagate: Weisfeiler-Lehman refinement to a fixpoint ----
        for (int iter = 0; iter < MAX_WL_ITERATIONS; iter++) {
            Map<String, List<String>> pfBySig = signatures(pf, pfToIgms.keySet(), pairId);
            Map<String, List<String>> igmsBySig = signatures(igms, igmsToPf.keySet(), pairId);
            boolean progressed = false;
            for (Map.Entry<String, List<String>> e : pfBySig.entrySet()) {
                List<String> ps = e.getValue();
                List<String> is = igmsBySig.get(e.getKey());
                if (is == null || is.isEmpty()) continue;
                if (ps.size() == 1 && is.size() == 1) {
                    link(ps.get(0), is.get(0), pfToIgms, igmsToPf, pairId, nextPair);
                    progressed = true;
                } else {
                    progressed |= nameTiebreak(pf, igms, ps, is, pfToIgms, igmsToPf, pairId, nextPair);
                }
            }
            if (!progressed) break;
        }

        List<SubMatch> subMatches = new ArrayList<>();
        pfToIgms.forEach((p, i) -> subMatches.add(new SubMatch(p, i)));

        List<UnmatchedSub> unmatchedSubsPf = new ArrayList<>();
        for (String s : pf.substationIds()) {
            if (!pfToIgms.containsKey(s)) unmatchedSubsPf.add(new UnmatchedSub(s, "no name and no unique structural match on IGMS side"));
        }
        List<UnmatchedSub> unmatchedSubsIgms = new ArrayList<>();
        for (String s : igms.substationIds()) {
            if (!igmsToPf.containsKey(s)) unmatchedSubsIgms.add(new UnmatchedSub(s, "no name and no unique structural match on PF side"));
        }

        // ---- Lines between matched substations (voltage + count; r/x only to disambiguate parallels) ----
        LineOutcome lo = matchLines(pfLines, igmsLines, pfToIgms, config);

        Map<String, String> names = new LinkedHashMap<>();
        for (String s : pf.substationIds()) names.put(s, pf.name(s));
        for (String s : igms.substationIds()) names.put(s, igms.name(s));

        return new Result(pfToIgms, subMatches, unmatchedSubsPf, unmatchedSubsIgms,
                lo.matches, lo.unmatchedPf, lo.unmatchedIgms, names);
    }

    private static void link(String pfSub, String igmsSub, Map<String, String> pfToIgms,
                             Map<String, String> igmsToPf, Map<String, Integer> pairId, int[] nextPair) {
        pfToIgms.put(pfSub, igmsSub);
        igmsToPf.put(igmsSub, pfSub);
        int id = nextPair[0]++;
        pairId.put(pfSub, id);
        pairId.put(igmsSub, id);
    }

    private static boolean nameTiebreak(SubGraph pf, SubGraph igms, List<String> ps, List<String> is,
                                        Map<String, String> pfToIgms, Map<String, String> igmsToPf,
                                        Map<String, Integer> pairId, int[] nextPair) {
        boolean progressed = false;
        Set<String> usedIgms = new HashSet<>();
        for (String p : ps) {
            if (pfToIgms.containsKey(p)) continue;
            String pk = nameKey(pf.name(p));
            if (pk == null) continue;
            String uniq = null;
            int cnt = 0;
            for (String i : is) {
                if (igmsToPf.containsKey(i) || usedIgms.contains(i)) continue;
                if (pk.equals(nameKey(igms.name(i)))) { uniq = i; cnt++; }
            }
            if (cnt == 1) {
                link(p, uniq, pfToIgms, igmsToPf, pairId, nextPair);
                usedIgms.add(uniq);
                progressed = true;
            }
        }
        return progressed;
    }

    /**
     * Structural signature of each unmatched substation. Built from matched
     * neighbours (via pair ids) plus own line-voltage degree and transformer
     * labels. Substations with no matched neighbour keep only their intrinsic
     * signature and match only if it happens to be globally unique.
     */
    private static Map<String, List<String>> signatures(SubGraph g, Set<String> matched, Map<String, Integer> pairId) {
        Map<String, List<String>> bySig = new LinkedHashMap<>();
        for (String sub : g.substationIds()) {
            if (matched.contains(sub)) continue;
            bySig.computeIfAbsent(g.signature(sub, pairId), k -> new ArrayList<>()).add(sub);
        }
        return bySig;
    }

    private static Map<String, List<String>> groupByName(SubGraph g) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String sub : g.substationIds()) {
            String k = nameKey(g.name(sub));
            if (k != null) byName.computeIfAbsent(k, x -> new ArrayList<>()).add(sub);
        }
        return byName;
    }

    // ---- line matching ----

    private record LineOutcome(List<LineMatch> matches, List<UnmatchedLine> unmatchedPf, List<LogicalLine> unmatchedIgms) {
    }

    private static LineOutcome matchLines(List<LogicalLine> pfLines, List<LogicalLine> igmsLines,
                                          Map<String, String> pfToIgms, MatchingConfig config) {
        Map<String, List<LogicalLine>> igmsByEndpoints = new HashMap<>();
        for (LogicalLine l : igmsLines) {
            Set<String> subs = distinctSubs(l);
            if (subs.size() >= 2) igmsByEndpoints.computeIfAbsent(endpointKey(subs), k -> new ArrayList<>()).add(l);
        }

        List<LineMatch> matches = new ArrayList<>();
        List<UnmatchedLine> unmatchedPf = new ArrayList<>();
        Set<String> usedIgms = new HashSet<>();

        for (LogicalLine pfLine : pfLines) {
            Set<String> pfSubs = distinctSubs(pfLine);
            if (pfSubs.size() < 2) {
                unmatchedPf.add(new UnmatchedLine(pfLine, "boundary/dangling: fewer than two resolved substation endpoints"));
                continue;
            }
            Set<String> mapped = new LinkedHashSet<>();
            boolean allMatched = true;
            for (String s : pfSubs) {
                String im = pfToIgms.get(s);
                if (im == null) { allMatched = false; break; }
                mapped.add(im);
            }
            if (!allMatched) {
                unmatchedPf.add(new UnmatchedLine(pfLine, "one or more endpoint substations unmatched"));
                continue;
            }
            List<LogicalLine> candidates = new ArrayList<>();
            for (LogicalLine c : igmsByEndpoints.getOrDefault(endpointKey(mapped), List.of())) {
                if (!usedIgms.contains(c.id()) && voltageEqual(pfLine.nomV(), c.nomV(), config.getVoltageTolerance())) {
                    candidates.add(c);
                }
            }
            if (candidates.isEmpty()) {
                unmatchedPf.add(new UnmatchedLine(pfLine, "no IGMS line between the matched substations at this voltage"));
            } else if (candidates.size() == 1) {
                LogicalLine c = candidates.get(0);
                usedIgms.add(c.id());
                matches.add(new LineMatch(pfLine, c));
            } else {
                // Parallel circuits: disambiguate by r/x ratio, then by name.
                LogicalLine chosen = disambiguateParallel(pfLine, candidates);
                if (chosen != null) {
                    usedIgms.add(chosen.id());
                    matches.add(new LineMatch(pfLine, chosen));
                } else {
                    unmatchedPf.add(new UnmatchedLine(pfLine, candidates.size()
                            + " parallel IGMS lines between the same substations at this voltage; r/x-ratio and name do not disambiguate"));
                }
            }
        }

        List<LogicalLine> unmatchedIgms = new ArrayList<>();
        for (LogicalLine l : igmsLines) {
            if (!usedIgms.contains(l.id())) unmatchedIgms.add(l);
        }
        return new LineOutcome(matches, unmatchedPf, unmatchedIgms);
    }

    /** Pick the unique parallel candidate by closest x/r ratio, then exact name; null if not unique. */
    private static LogicalLine disambiguateParallel(LogicalLine pf, List<LogicalLine> candidates) {
        // by name first (names sometimes carry circuit identity)
        String pk = nameKey(pf.name());
        if (pk != null) {
            LogicalLine byName = null;
            int cnt = 0;
            for (LogicalLine c : candidates) {
                if (pk.equals(nameKey(c.name()))) { byName = c; cnt++; }
            }
            if (cnt == 1) return byName;
        }
        // by x/r ratio (unit-invariant): closest, and clearly closest
        Double pfRatio = ratio(pf);
        if (pfRatio != null) {
            LogicalLine best = null;
            double bestD = Double.MAX_VALUE, secondD = Double.MAX_VALUE;
            for (LogicalLine c : candidates) {
                Double r = ratio(c);
                if (r == null) continue;
                double d = Math.abs(r - pfRatio) / Math.max(Math.abs(pfRatio), 1e-9);
                if (d < bestD) { secondD = bestD; bestD = d; best = c; }
                else if (d < secondD) { secondD = d; }
            }
            if (best != null && bestD <= 0.10 && secondD - bestD >= 0.10) return best;
        }
        return null;
    }

    private static Double ratio(LogicalLine l) {
        if (l.totalR() == 0.0) return null;
        return l.totalX() / l.totalR();
    }

    // ---- helpers ----

    private static boolean voltageEqual(Double a, Double b, double tolKv) {
        if (a == null || b == null) return true;
        return Math.abs(a - b) <= tolKv;
    }

    private static Set<String> distinctSubs(LogicalLine l) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : l.endpointSubstations()) if (s != null) out.add(s);
        return out;
    }

    private static String endpointKey(Set<String> subs) {
        List<String> sorted = new ArrayList<>(subs);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    private static String nameKey(String name) {
        if (name == null) return null;
        String k = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return k.isEmpty() ? null : k;
    }

    /** Substation graph: names, incident lines (neighbour + voltage), transformer labels. */
    private static final class SubGraph {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, List<String>> neighbours = new HashMap<>();
        private final Map<String, List<Integer>> neighbourV = new HashMap<>();
        private final Map<String, Map<Integer, Integer>> degreeByV = new HashMap<>();
        private final Map<String, List<String>> transformerLabels = new HashMap<>();

        SubGraph(ModelTables tables, List<LogicalLine> lines) {
            for (var s : tables.substations()) {
                if (s.substation() != null) names.putIfAbsent(s.substation(), s.substationName());
            }
            Map<String, List<TransformerEndRow>> byTrafo = new LinkedHashMap<>();
            for (TransformerEndRow te : tables.transformerEnds()) {
                if (te.trafo() != null) byTrafo.computeIfAbsent(te.trafo(), k -> new ArrayList<>()).add(te);
            }
            for (var e : byTrafo.entrySet()) {
                String sub = e.getValue().stream().map(TransformerEndRow::substation).filter(Objects::nonNull).findFirst().orElse(null);
                if (sub == null) continue;
                List<Integer> rated = new ArrayList<>();
                for (TransformerEndRow te : e.getValue()) if (te.ratedU() != null) rated.add((int) Math.round(te.ratedU()));
                Collections.sort(rated);
                transformerLabels.computeIfAbsent(sub, k -> new ArrayList<>()).add("T" + rated);
                names.putIfAbsent(sub, null);
            }
            for (LogicalLine l : lines) {
                List<String> subs = new ArrayList<>(distinctSubs(l));
                if (subs.size() < 2) continue;
                int v = l.nomV() == null ? -1 : (int) Math.round(l.nomV());
                for (int i = 0; i < subs.size(); i++) {
                    for (int j = 0; j < subs.size(); j++) {
                        if (i == j) continue;
                        String a = subs.get(i), b = subs.get(j);
                        neighbours.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                        neighbourV.computeIfAbsent(a, k -> new ArrayList<>()).add(v);
                        degreeByV.computeIfAbsent(a, k -> new HashMap<>()).merge(v, 1, Integer::sum);
                        names.putIfAbsent(a, null);
                        names.putIfAbsent(b, null);
                    }
                }
            }
            for (var v : transformerLabels.values()) Collections.sort(v);
        }

        Set<String> substationIds() { return names.keySet(); }

        String name(String sub) { return names.get(sub); }

        String signature(String sub, Map<String, Integer> pairId) {
            List<String> matchedNbr = new ArrayList<>();
            List<String> nbrs = neighbours.getOrDefault(sub, List.of());
            List<Integer> vs = neighbourV.getOrDefault(sub, List.of());
            for (int i = 0; i < nbrs.size(); i++) {
                Integer pid = pairId.get(nbrs.get(i));
                if (pid != null) matchedNbr.add("N" + vs.get(i) + ":P" + pid);
            }
            Collections.sort(matchedNbr);

            StringBuilder sb = new StringBuilder();
            for (String t : matchedNbr) sb.append(t).append(";");
            sb.append("|");
            Map<Integer, Integer> deg = degreeByV.getOrDefault(sub, Map.of());
            List<Integer> dv = new ArrayList<>(deg.keySet());
            Collections.sort(dv);
            for (int v : dv) sb.append("L").append(v).append("x").append(deg.get(v)).append(";");
            sb.append("|");
            for (String t : transformerLabels.getOrDefault(sub, List.of())) sb.append(t).append(";");
            return sb.toString();
        }
    }
}
