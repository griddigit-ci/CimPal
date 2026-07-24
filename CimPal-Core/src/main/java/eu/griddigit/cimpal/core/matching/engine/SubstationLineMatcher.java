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
 * Deterministic substation and line matching.
 *
 * <p>Simple, precision-first, no scoring or iterative refinement:</p>
 * <ol>
 *   <li><b>Substations</b> are matched by an exact signature - the multiset of
 *       incident logical-line voltages plus the transformer winding-voltage
 *       labels. A substation matches when its signature occurs exactly once on
 *       each side (names break ties only within an otherwise-symmetric group).</li>
 *   <li><b>Lines</b> are matched between matched substation pairs: an IGMS
 *       logical line is a match for a PF one when the endpoints correspond, the
 *       voltage is equal, and r / x / length agree within tolerance. Exactly one
 *       qualifying candidate is a match; zero or several go to the report.</li>
 * </ol>
 *
 * <p>Anything not uniquely matched is returned for human processing rather than
 * guessed.</p>
 */
public final class SubstationLineMatcher {

    public record SubMatch(String pf, String igms) {
    }

    public record LineMatch(LogicalLine pf, LogicalLine igms) {
    }

    public record UnmatchedLine(LogicalLine line, String reason) {
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

    public record UnmatchedSub(String id, String reason) {
    }

    private static final double NAME_TIEBREAK_MIN = 0.60;

    private SubstationLineMatcher() {
    }

    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               MatchingConfig config) {
        SubGraph pf = new SubGraph(pfTables, pfLines);
        SubGraph igms = new SubGraph(igmsTables, igmsLines);

        // ---- 1. Substation matching: single-pass exact signature ----
        Map<String, String> pfToIgms = new LinkedHashMap<>();
        Map<String, String> igmsToPf = new LinkedHashMap<>();

        Map<String, List<String>> pfBySig = groupBySignature(pf);
        Map<String, List<String>> igmsBySig = groupBySignature(igms);

        for (Map.Entry<String, List<String>> e : pfBySig.entrySet()) {
            List<String> pfSubs = e.getValue();
            List<String> igmsSubs = igmsBySig.get(e.getKey());
            if (igmsSubs == null || igmsSubs.isEmpty()) continue;

            if (pfSubs.size() == 1 && igmsSubs.size() == 1) {
                pfToIgms.put(pfSubs.get(0), igmsSubs.get(0));
                igmsToPf.put(igmsSubs.get(0), pfSubs.get(0));
            } else {
                nameTiebreak(pf, igms, pfSubs, igmsSubs, pfToIgms, igmsToPf);
            }
        }

        List<SubMatch> subMatches = new ArrayList<>();
        pfToIgms.forEach((p, i) -> subMatches.add(new SubMatch(p, i)));

        List<UnmatchedSub> unmatchedSubsPf = new ArrayList<>();
        for (String s : pf.substationIds()) {
            if (!pfToIgms.containsKey(s)) {
                unmatchedSubsPf.add(new UnmatchedSub(s, "no unique signature match on IGMS side"));
            }
        }
        List<UnmatchedSub> unmatchedSubsIgms = new ArrayList<>();
        for (String s : igms.substationIds()) {
            if (!igmsToPf.containsKey(s)) {
                unmatchedSubsIgms.add(new UnmatchedSub(s, "no unique signature match on PF side"));
            }
        }

        // ---- 2. Line matching between matched substation pairs ----
        Map<String, List<LogicalLine>> igmsByEndpoints = new HashMap<>();
        for (LogicalLine l : igmsLines) {
            Set<String> subs = distinctSubs(l);
            if (subs.size() < 2) continue;
            igmsByEndpoints.computeIfAbsent(endpointKey(subs), k -> new ArrayList<>()).add(l);
        }

        List<LineMatch> lineMatches = new ArrayList<>();
        List<UnmatchedLine> unmatchedLinesPf = new ArrayList<>();
        Set<String> usedIgms = new HashSet<>();

        for (LogicalLine pfLine : pfLines) {
            Set<String> pfSubs = distinctSubs(pfLine);
            if (pfSubs.size() < 2) {
                unmatchedLinesPf.add(new UnmatchedLine(pfLine, "boundary/dangling: fewer than two resolved substation endpoints"));
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
                unmatchedLinesPf.add(new UnmatchedLine(pfLine, "one or more endpoint substations unmatched"));
                continue;
            }
            List<LogicalLine> candidates = igmsByEndpoints.getOrDefault(endpointKey(mapped), List.of());
            List<LogicalLine> qualifying = new ArrayList<>();
            for (LogicalLine cand : candidates) {
                if (usedIgms.contains(cand.id())) continue;
                if (linesMatch(pfLine, cand, config)) qualifying.add(cand);
            }
            if (qualifying.size() == 1) {
                LogicalLine chosen = qualifying.get(0);
                usedIgms.add(chosen.id());
                lineMatches.add(new LineMatch(pfLine, chosen));
            } else if (qualifying.isEmpty()) {
                unmatchedLinesPf.add(new UnmatchedLine(pfLine,
                        "no IGMS line between matched substations agrees on voltage and r/x/length within tolerance"));
            } else {
                // Parallel circuits within tolerance: break the tie by name if it is unique.
                LogicalLine byName = uniqueByName(pfLine, qualifying);
                if (byName != null) {
                    usedIgms.add(byName.id());
                    lineMatches.add(new LineMatch(pfLine, byName));
                } else {
                    unmatchedLinesPf.add(new UnmatchedLine(pfLine,
                            qualifying.size() + " candidate IGMS lines within tolerance (parallel/ambiguous); names do not disambiguate - needs human review"));
                }
            }
        }

        List<LogicalLine> unmatchedLinesIgms = new ArrayList<>();
        for (LogicalLine l : igmsLines) {
            if (!usedIgms.contains(l.id())) unmatchedLinesIgms.add(l);
        }

        Map<String, String> names = new LinkedHashMap<>();
        for (String s : pf.substationIds()) names.put(s, pf.name(s));
        for (String s : igms.substationIds()) names.put(s, igms.name(s));

        return new Result(pfToIgms, subMatches, unmatchedSubsPf, unmatchedSubsIgms,
                lineMatches, unmatchedLinesPf, unmatchedLinesIgms, names);
    }

    private static void nameTiebreak(SubGraph pf, SubGraph igms,
                                     List<String> pfSubs, List<String> igmsSubs,
                                     Map<String, String> pfToIgms, Map<String, String> igmsToPf) {
        Set<String> usedIgms = new HashSet<>();
        for (String pfSub : pfSubs) {
            String bestIgms = null;
            double best = -1, second = -1;
            for (String igmsSub : igmsSubs) {
                if (usedIgms.contains(igmsSub) || igmsToPf.containsKey(igmsSub)) continue;
                double sim = Names.similarity(pf.name(pfSub), igms.name(igmsSub));
                if (sim > best) { second = best; best = sim; bestIgms = igmsSub; }
                else if (sim > second) { second = sim; }
            }
            if (bestIgms != null && best >= NAME_TIEBREAK_MIN && best - Math.max(second, 0) >= 0.15) {
                pfToIgms.put(pfSub, bestIgms);
                igmsToPf.put(bestIgms, pfSub);
                usedIgms.add(bestIgms);
            }
        }
    }

    /** A candidate line qualifies when voltage is equal and r/x/length agree within tolerance. */
    private static boolean linesMatch(LogicalLine a, LogicalLine b, MatchingConfig c) {
        if (!voltageEqual(a.nomV(), b.nomV(), c.getVoltageTolerance())) return false;
        return withinTol(a.totalR(), b.totalR(), c.getRTolerance())
                && withinTol(a.totalX(), b.totalX(), c.getXTolerance())
                && withinTol(a.totalLength(), b.totalLength(), c.getLengthTolerance());
    }

    /** True when both values are ~0 (no information) or their relative difference is within tolerance. */
    private static boolean withinTol(double a, double b, double tol) {
        double denom = Math.max(Math.max(Math.abs(a), Math.abs(b)), 1e-9);
        if (denom < 1e-6) return true; // both effectively zero
        return Math.abs(a - b) / denom <= tol;
    }

    private static boolean voltageEqual(Double a, Double b, double tolKv) {
        if (a == null || b == null) return true; // unknown voltage: do not gate out
        return Math.abs(a - b) <= tolKv;
    }

    private static Map<String, List<String>> groupBySignature(SubGraph g) {
        Map<String, List<String>> bySig = new LinkedHashMap<>();
        for (String sub : g.substationIds()) {
            bySig.computeIfAbsent(g.signature(sub), k -> new ArrayList<>()).add(sub);
        }
        return bySig;
    }

    /** The single candidate whose name equals the PF line's name, or null if not unique. */
    private static LogicalLine uniqueByName(LogicalLine pfLine, List<LogicalLine> candidates) {
        if (pfLine.name() == null) return null;
        String target = pfLine.name().trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) return null;
        LogicalLine found = null;
        for (LogicalLine c : candidates) {
            if (c.name() != null && target.equals(c.name().trim().toLowerCase(Locale.ROOT))) {
                if (found != null) return null; // not unique
                found = c;
            }
        }
        return found;
    }

    private static Set<String> distinctSubs(LogicalLine l) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : l.endpointSubstations()) {
            if (s != null) out.add(s);
        }
        return out;
    }

    private static String endpointKey(Set<String> subs) {
        List<String> sorted = new ArrayList<>(subs);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    /**
     * Substation graph: incident-line voltage multiset + transformer labels,
     * used to build the exact matching signature.
     */
    private static final class SubGraph {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, Map<Integer, Integer>> lineDegreeByV = new HashMap<>();
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
                List<TransformerEndRow> ends = e.getValue();
                String sub = ends.stream().map(TransformerEndRow::substation).filter(Objects::nonNull).findFirst().orElse(null);
                if (sub == null) continue;
                List<Integer> rated = new ArrayList<>();
                for (TransformerEndRow te : ends) {
                    if (te.ratedU() != null) rated.add((int) Math.round(te.ratedU()));
                }
                Collections.sort(rated);
                transformerLabels.computeIfAbsent(sub, k -> new ArrayList<>()).add("T" + rated);
                names.putIfAbsent(sub, null);
            }
            for (LogicalLine l : lines) {
                Set<String> subs = distinctSubs(l);
                if (subs.size() < 2) continue;
                int v = l.nomV() == null ? -1 : (int) Math.round(l.nomV());
                for (String sub : subs) {
                    lineDegreeByV.computeIfAbsent(sub, k -> new HashMap<>()).merge(v, 1, Integer::sum);
                    names.putIfAbsent(sub, null);
                }
            }
            for (var v : transformerLabels.values()) Collections.sort(v);
        }

        Set<String> substationIds() {
            return names.keySet();
        }

        String name(String sub) {
            return names.get(sub);
        }

        String signature(String sub) {
            StringBuilder sb = new StringBuilder();
            Map<Integer, Integer> deg = lineDegreeByV.getOrDefault(sub, Map.of());
            List<Integer> vs = new ArrayList<>(deg.keySet());
            Collections.sort(vs);
            for (int v : vs) sb.append("L").append(v).append("x").append(deg.get(v)).append(";");
            for (String t : transformerLabels.getOrDefault(sub, List.of())) sb.append(t).append(";");
            return sb.toString();
        }
    }
}
