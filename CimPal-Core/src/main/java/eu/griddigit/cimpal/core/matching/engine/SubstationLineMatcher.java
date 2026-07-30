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
 * <em>topology</em>: which substations connect to which, at what voltage.</p>
 * <ol>
 *   <li><b>Seed</b> with substations whose names match exactly (unique on both
 *       sides).</li>
 *   <li><b>Propagate</b> (Weisfeiler-Lehman): an unmatched substation is
 *       characterised by the multiset of {voltage, matched-neighbour} of its
 *       incident lines plus its transformer labels. It is auto-matched only when
 *       that signature is unique on both sides AND carries at least one
 *       matched-neighbour anchor (an intrinsic-only signature is not evidence).
 *       Iterate to a fixpoint; names break ties only one-to-one.</li>
 *   <li><b>Lines</b> between matched substations are bucketed by (endpoints,
 *       voltage); a bucket with exactly one line on each side matches; parallels
 *       are disambiguated by name then unit-invariant x/r ratio, else reported.</li>
 * </ol>
 *
 * <p>Deterministic and precision-first: every gate is two-sided and fails closed,
 * so an ambiguous or unconfirmable situation is reported rather than guessed.</p>
 */
public final class SubstationLineMatcher {

    /** {@code note} flags a value discrepancy (e.g. differing voltage/name) on an otherwise-confirmed match. */
    public record SubMatch(String pf, String igms, String note) {
    }

    public record LineMatch(LogicalLine pf, LogicalLine igms, String note) {
    }

    public record UnmatchedLine(LogicalLine line, String reason) {
    }

    public record UnmatchedSub(String id, String reason) {
    }

    /** Per source (IGMG) substation diagnostic row so a human can verify the logic. */
    public record SubDiag(
            String sourceId, String sourceName,
            int connections, String connectionsByVoltage, String transformers,
            String status, String method,
            String matchedId, String matchedName, String matchedConnections,
            int candidateCount
    ) {
    }

    public record Result(
            Map<String, String> substationMatch,
            List<SubMatch> substationMatches,
            List<UnmatchedSub> unmatchedSubstationsPf,
            List<UnmatchedSub> unmatchedSubstationsIgms,
            List<LineMatch> lineMatches,
            List<UnmatchedLine> unmatchedLinesPf,
            List<LogicalLine> unmatchedLinesIgms,
            Map<String, String> substationNames,
            List<SubDiag> substationDiagnostics
    ) {
    }

    private static final int MAX_WL_ITERATIONS = 30;
    private static final double RATIO_TOLERANCE = 0.10;

    private SubstationLineMatcher() {
    }

    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               MatchingConfig config) {
        return match(pfTables, pfLines, igmsTables, igmsLines, config, Map.of(), AliasDictionary.empty());
    }

    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               MatchingConfig config, Map<String, String> idSeeds) {
        return match(pfTables, pfLines, igmsTables, igmsLines, config, idSeeds, AliasDictionary.empty());
    }

    /**
     * @param idSeeds source-substation-id -> matched-substation-id pairs already
     *                established by the same-id pass; used as reliable anchors.
     * @param dict    substation alias dictionary (code &lt;-&gt; name); empty if none.
     */
    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               MatchingConfig config, Map<String, String> idSeeds, AliasDictionary dict) {
        SubGraph pf = new SubGraph(pfTables, pfLines);
        SubGraph igms = new SubGraph(igmsTables, igmsLines);

        Map<String, String> pfToIgms = new LinkedHashMap<>();
        Map<String, String> igmsToPf = new LinkedHashMap<>();
        Map<String, Integer> pairId = new HashMap<>();
        Map<String, String> method = new HashMap<>();
        int[] nextPair = {0};

        // ---- Seed 0: same-id substation matches (strongest anchors) ----
        for (Map.Entry<String, String> e : idSeeds.entrySet()) {
            if (pf.substationIds().contains(e.getKey()) && igms.substationIds().contains(e.getValue())
                    && !pfToIgms.containsKey(e.getKey()) && !igmsToPf.containsKey(e.getValue())) {
                link(e.getKey(), e.getValue(), pfToIgms, igmsToPf, pairId, nextPair, method, "same-id");
            }
        }

        // ---- Seed 1: exact substation-name matches, unique on BOTH sides ----
        Map<String, List<String>> pfByName = groupByName(pf, dict);
        Map<String, List<String>> igmsByName = groupByName(igms, dict);
        for (Map.Entry<String, List<String>> e : pfByName.entrySet()) {
            List<String> ps = e.getValue();
            List<String> is = igmsByName.get(e.getKey());
            if (is != null && ps.size() == 1 && is.size() == 1
                    && !pfToIgms.containsKey(ps.get(0)) && !igmsToPf.containsKey(is.get(0))) {
                link(ps.get(0), is.get(0), pfToIgms, igmsToPf, pairId, nextPair, method, "name");
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
                // Auto-match only on a signature anchored to an already-matched neighbour;
                // an empty/intrinsic-only signature carries no structural evidence.
                if (ps.size() == 1 && is.size() == 1 && hasMatchedNeighbour(e.getKey())) {
                    link(ps.get(0), is.get(0), pfToIgms, igmsToPf, pairId, nextPair, method, "structural-connections");
                    progressed = true;
                } else {
                    progressed |= nameTiebreak(pf, igms, ps, is, pfToIgms, igmsToPf, pairId, nextPair, method, dict);
                }
            }
            if (!progressed) break;
        }

        List<SubMatch> subMatches = new ArrayList<>();
        pfToIgms.forEach((p, i) -> subMatches.add(new SubMatch(p, i, substationNote(pf, igms, p, i, dict))));

        List<UnmatchedSub> unmatchedSubsPf = new ArrayList<>();
        for (String s : pf.substationIds()) {
            if (!pfToIgms.containsKey(s)) unmatchedSubsPf.add(new UnmatchedSub(s, "no same-id, name or unique structural match"));
        }
        List<UnmatchedSub> unmatchedSubsIgms = new ArrayList<>();
        for (String s : igms.substationIds()) {
            if (!igmsToPf.containsKey(s)) unmatchedSubsIgms.add(new UnmatchedSub(s, "no counterpart on source side"));
        }

        LineOutcome lo = matchLines(pfLines, igmsLines, pfToIgms, config);

        Map<String, String> names = new LinkedHashMap<>();
        for (String s : pf.substationIds()) names.put(s, pf.name(s));
        for (String s : igms.substationIds()) names.put(s, igms.name(s));

        List<SubDiag> diagnostics = buildDiagnostics(pf, igms, pfToIgms, method, pairId);

        return new Result(pfToIgms, subMatches, unmatchedSubsPf, unmatchedSubsIgms,
                lo.matches, lo.unmatchedPf, lo.unmatchedIgms, names, diagnostics);
    }

    /** A signature has a matched-neighbour anchor iff its (leading) neighbour segment is non-empty. */
    private static boolean hasMatchedNeighbour(String signature) {
        return !signature.startsWith("|");
    }

    private static void link(String pfSub, String igmsSub, Map<String, String> pfToIgms,
                             Map<String, String> igmsToPf, Map<String, Integer> pairId, int[] nextPair,
                             Map<String, String> method, String label) {
        pfToIgms.put(pfSub, igmsSub);
        igmsToPf.put(igmsSub, pfSub);
        int id = nextPair[0]++;
        pairId.put(pfSub, id);
        pairId.put(igmsSub, id);
        method.put(pfSub, label);
    }

    /** Per source substation: connection stats + how (or whether) it matched. */
    private static List<SubDiag> buildDiagnostics(SubGraph pf, SubGraph igms, Map<String, String> pfToIgms,
                                                  Map<String, String> method, Map<String, Integer> pairId) {
        // matched-side signature index for candidate counting of unmatched source subs
        Map<String, Integer> igmsSigCount = new HashMap<>();
        for (String s : igms.substationIds()) {
            if (!pfToIgms.containsValue(s)) igmsSigCount.merge(igms.signature(s, pairId), 1, Integer::sum);
        }
        List<SubDiag> out = new ArrayList<>();
        for (String s : pf.substationIds()) {
            String matched = pfToIgms.get(s);
            boolean isMatched = matched != null;
            int cand = isMatched ? 1 : igmsSigCount.getOrDefault(pf.signature(s, pairId), 0);
            out.add(new SubDiag(
                    Names.mrid(s), safe(pf.name(s)),
                    pf.connectionsTotal(s), pf.connectionsByVoltage(s), pf.transformersStr(s),
                    isMatched ? "MATCHED" : "UNMATCHED",
                    isMatched ? method.getOrDefault(s, "structural-connections") : "",
                    isMatched ? Names.mrid(matched) : "",
                    isMatched ? safe(igms.name(matched)) : "",
                    isMatched ? igms.connectionsByVoltage(matched) : "",
                    cand));
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Flag value discrepancies on a confirmed substation match: same object in
     * both models but a differing name and/or connection-voltage profile. The
     * match still stands (topology confirmed it); the note surfaces the mismatch
     * for a human, since voltages/names are known to differ between the models.
     */
    private static String substationNote(SubGraph pf, SubGraph igms, String source, String matched, AliasDictionary dict) {
        List<String> notes = new ArrayList<>();
        String sn = pf.name(source), mn = igms.name(matched);
        if (sn != null && mn != null && !java.util.Objects.equals(dict.canonical(sn), dict.canonical(mn))) {
            notes.add("name differs (IGMG '" + sn + "' vs PF '" + mn + "')");
        }
        String sv = pf.connectionsByVoltage(source), mv = igms.connectionsByVoltage(matched);
        if (!sv.equals(mv)) {
            notes.add("connection/voltage profile differs (IGMG [" + sv + "] vs PF [" + mv + "])");
        }
        return String.join("; ", notes);
    }

    /**
     * Break a structural tie by name, one-to-one in BOTH directions: link p to i
     * only when p's name key is unique among the still-unmatched PF members AND
     * i is the only still-unmatched IGMS member with that key.
     */
    private static boolean nameTiebreak(SubGraph pf, SubGraph igms, List<String> ps, List<String> is,
                                        Map<String, String> pfToIgms, Map<String, String> igmsToPf,
                                        Map<String, Integer> pairId, int[] nextPair, Map<String, String> method,
                                        AliasDictionary dict) {
        Map<String, Integer> pfCount = new HashMap<>();
        for (String p : ps) {
            if (pfToIgms.containsKey(p)) continue;
            String k = dict.canonical(pf.name(p));
            if (k != null) pfCount.merge(k, 1, Integer::sum);
        }
        boolean progressed = false;
        Set<String> usedIgms = new HashSet<>();
        for (String p : ps) {
            if (pfToIgms.containsKey(p)) continue;
            String pk = dict.canonical(pf.name(p));
            if (pk == null || pfCount.getOrDefault(pk, 0) != 1) continue; // PF side must be unique
            String uniq = null;
            int cnt = 0;
            for (String i : is) {
                if (igmsToPf.containsKey(i) || usedIgms.contains(i)) continue;
                if (pk.equals(dict.canonical(igms.name(i)))) { uniq = i; cnt++; }
            }
            if (cnt == 1) {
                link(p, uniq, pfToIgms, igmsToPf, pairId, nextPair, method, "name+connections");
                usedIgms.add(uniq);
                progressed = true;
            }
        }
        return progressed;
    }

    private static Map<String, List<String>> signatures(SubGraph g, Set<String> matched, Map<String, Integer> pairId) {
        Map<String, List<String>> bySig = new LinkedHashMap<>();
        for (String sub : g.substationIds()) {
            if (matched.contains(sub)) continue;
            bySig.computeIfAbsent(g.signature(sub, pairId), k -> new ArrayList<>()).add(sub);
        }
        return bySig;
    }

    private static Map<String, List<String>> groupByName(SubGraph g, AliasDictionary dict) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String sub : g.substationIds()) {
            String k = dict.canonical(g.name(sub));
            if (k != null) byName.computeIfAbsent(k, x -> new ArrayList<>()).add(sub);
        }
        return byName;
    }

    // ---- line matching ----

    private record LineOutcome(List<LineMatch> matches, List<UnmatchedLine> unmatchedPf, List<LogicalLine> unmatchedIgms) {
    }

    /**
     * Match lines between matched substations. Bucketing is by <b>endpoint set
     * only</b> (voltage-agnostic, since voltages can differ between the models):
     * first match voltage-consistent 1-1 pairs within the bucket, then match the
     * remaining lines across differing voltages - flagging the voltage discrepancy
     * as a note rather than refusing the match. Parallels are disambiguated by
     * name then x/r ratio; genuinely ambiguous ones are reported.
     */
    private static LineOutcome matchLines(List<LogicalLine> pfLines, List<LogicalLine> igmsLines,
                                          Map<String, String> pfToIgms, MatchingConfig config) {
        List<UnmatchedLine> unmatchedPf = new ArrayList<>();

        Map<String, List<LogicalLine>> igByEndpoints = new HashMap<>();
        for (LogicalLine l : igmsLines) {
            Set<String> subs = distinctSubs(l);
            if (subs.size() >= 2) igByEndpoints.computeIfAbsent(endpointKey(subs), k -> new ArrayList<>()).add(l);
        }

        Map<String, List<LogicalLine>> pfByEndpoints = new LinkedHashMap<>();
        for (LogicalLine l : pfLines) {
            Set<String> subs = distinctSubs(l);
            if (subs.size() < 2) {
                unmatchedPf.add(new UnmatchedLine(l, "boundary/dangling: fewer than two resolved substation endpoints"));
                continue;
            }
            Set<String> mapped = new LinkedHashSet<>();
            boolean allMatched = true;
            for (String s : subs) {
                String im = pfToIgms.get(s);
                if (im == null) { allMatched = false; break; }
                mapped.add(im);
            }
            if (!allMatched) {
                unmatchedPf.add(new UnmatchedLine(l, "one or more endpoint substations unmatched"));
                continue;
            }
            pfByEndpoints.computeIfAbsent(endpointKey(mapped), k -> new ArrayList<>()).add(l);
        }

        List<LineMatch> matches = new ArrayList<>();
        Set<String> usedIgms = new HashSet<>();
        for (Map.Entry<String, List<LogicalLine>> e : pfByEndpoints.entrySet()) {
            List<LogicalLine> pfList = new ArrayList<>(e.getValue());
            List<LogicalLine> igList = igByEndpoints.getOrDefault(e.getKey(), List.of());

            // Pass 1: voltage-consistent, unique on both sides at that voltage.
            Iterator<LogicalLine> pit = pfList.iterator();
            while (pit.hasNext()) {
                LogicalLine pfLine = pit.next();
                Integer pv = voltageBucket(pfLine.nomV());
                if (pv == null) continue;
                long pfSameV = pfList.stream().filter(x -> pv.equals(voltageBucket(x.nomV()))).count();
                List<LogicalLine> sameV = new ArrayList<>();
                for (LogicalLine c : igList) if (!usedIgms.contains(c.id()) && pv.equals(voltageBucket(c.nomV()))) sameV.add(c);
                if (pfSameV == 1 && sameV.size() == 1) {
                    usedIgms.add(sameV.get(0).id());
                    matches.add(new LineMatch(pfLine, sameV.get(0), ""));
                    pit.remove();
                }
            }
            // Pass 2: remaining lines (voltage differs, or parallels) - match with a note.
            List<LogicalLine> avail = new ArrayList<>();
            for (LogicalLine c : igList) if (!usedIgms.contains(c.id())) avail.add(c);
            if (pfList.size() == 1 && avail.size() == 1) {
                LogicalLine c = avail.get(0);
                usedIgms.add(c.id());
                matches.add(new LineMatch(pfList.get(0), c, voltageNote(pfList.get(0), c, config)));
            } else {
                for (LogicalLine pfLine : pfList) {
                    List<LogicalLine> rem = new ArrayList<>();
                    for (LogicalLine c : igList) if (!usedIgms.contains(c.id())) rem.add(c);
                    LogicalLine chosen = disambiguateParallel(pfLine, rem);
                    if (chosen != null) {
                        usedIgms.add(chosen.id());
                        matches.add(new LineMatch(pfLine, chosen, voltageNote(pfLine, chosen, config)));
                    } else {
                        unmatchedPf.add(new UnmatchedLine(pfLine, rem.size()
                                + " candidate line(s) between the same substations; name and x/r ratio do not uniquely disambiguate"));
                    }
                }
            }
        }

        List<LogicalLine> unmatchedIgms = new ArrayList<>();
        for (LogicalLine l : igmsLines) {
            if (!usedIgms.contains(l.id())) unmatchedIgms.add(l);
        }
        return new LineOutcome(matches, unmatchedPf, unmatchedIgms);
    }

    /** Flag a voltage discrepancy on a matched line pair (matched by topology). */
    private static String voltageNote(LogicalLine a, LogicalLine b, MatchingConfig config) {
        Double va = a.nomV(), vb = b.nomV();
        if (va == null || vb == null) return "voltage unresolved on one side (matched by topology)";
        if (Math.abs(va - vb) > config.getVoltageTolerance()) {
            return "voltage differs (IGMG " + va + "kV vs PF " + vb + "kV)";
        }
        return "";
    }

    /**
     * Pick the unique parallel candidate: by exact name if that is unique, else
     * by clearly-closest x/r ratio (unit-invariant). A candidate with an
     * unknown ratio blocks a confident ratio decision. Returns null if not unique.
     */
    private static LogicalLine disambiguateParallel(LogicalLine pf, List<LogicalLine> candidates) {
        if (candidates.isEmpty()) return null;
        String pk = nameKey(pf.name());
        if (pk != null) {
            LogicalLine byName = null;
            int cnt = 0;
            for (LogicalLine c : candidates) {
                if (pk.equals(nameKey(c.name()))) { byName = c; cnt++; }
            }
            if (cnt == 1) return byName;
        }
        Double pfRatio = ratio(pf);
        if (pfRatio == null) return null;
        int nullRatio = 0;
        LogicalLine best = null;
        double bestD = Double.MAX_VALUE, secondD = Double.MAX_VALUE;
        for (LogicalLine c : candidates) {
            Double r = ratio(c);
            if (r == null) { nullRatio++; continue; }
            double d = Math.abs(r - pfRatio) / Math.max(Math.abs(pfRatio), 1e-9);
            if (d < bestD) { secondD = bestD; bestD = d; best = c; }
            else if (d < secondD) { secondD = d; }
        }
        if (best != null && nullRatio == 0 && bestD <= RATIO_TOLERANCE && secondD - bestD >= RATIO_TOLERANCE) {
            return best;
        }
        return null;
    }

    private static Double ratio(LogicalLine l) {
        if (l.totalR() == 0.0) return null;
        return l.totalX() / l.totalR();
    }

    // ---- helpers ----

    private static Integer voltageBucket(Double v) {
        return v == null ? null : (int) Math.round(v);
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
            // The substation universe is ONLY declared cim:Substation (Map04). Equipment
            // containers that are not substations (e.g. a transformer held in a
            // VoltageLevel) must not become phantom substation nodes.
            Set<String> declared = new HashSet<>();
            for (var s : tables.substations()) {
                if (s.substation() != null) {
                    declared.add(s.substation());
                    names.putIfAbsent(s.substation(), s.substationName());
                }
            }
            Map<String, List<TransformerEndRow>> byTrafo = new LinkedHashMap<>();
            for (TransformerEndRow te : tables.transformerEnds()) {
                if (te.trafo() != null) byTrafo.computeIfAbsent(te.trafo(), k -> new ArrayList<>()).add(te);
            }
            for (var e : byTrafo.entrySet()) {
                String sub = e.getValue().stream().map(TransformerEndRow::substation).filter(Objects::nonNull).findFirst().orElse(null);
                if (sub == null || !declared.contains(sub)) continue; // only attribute to real substations
                List<Integer> rated = new ArrayList<>();
                for (TransformerEndRow te : e.getValue()) if (te.ratedU() != null) rated.add((int) Math.round(te.ratedU()));
                Collections.sort(rated);
                transformerLabels.computeIfAbsent(sub, k -> new ArrayList<>()).add("T" + rated);
            }
            for (LogicalLine l : lines) {
                List<String> subs = new ArrayList<>();
                for (String s : distinctSubs(l)) if (declared.contains(s)) subs.add(s);
                if (subs.size() < 2) continue;
                int v = l.nomV() == null ? -1 : (int) Math.round(l.nomV());
                for (int i = 0; i < subs.size(); i++) {
                    for (int j = 0; j < subs.size(); j++) {
                        if (i == j) continue;
                        String a = subs.get(i), b = subs.get(j);
                        neighbours.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                        neighbourV.computeIfAbsent(a, k -> new ArrayList<>()).add(v);
                        degreeByV.computeIfAbsent(a, k -> new HashMap<>()).merge(v, 1, Integer::sum);
                    }
                }
            }
            for (var v : transformerLabels.values()) Collections.sort(v);
        }

        Set<String> substationIds() { return names.keySet(); }

        String name(String sub) { return names.get(sub); }

        /** Total incident lines (degree) at this substation. */
        int connectionsTotal(String sub) {
            int n = 0;
            for (int c : degreeByV.getOrDefault(sub, Map.of()).values()) n += c;
            return n;
        }

        /** Human-readable degree breakdown, e.g. "220kV:3, 110kV:5". */
        String connectionsByVoltage(String sub) {
            Map<Integer, Integer> deg = degreeByV.getOrDefault(sub, Map.of());
            List<Integer> vs = new ArrayList<>(deg.keySet());
            vs.sort(Collections.reverseOrder());
            List<String> parts = new ArrayList<>();
            for (int v : vs) parts.add((v < 0 ? "?" : v + "kV") + ":" + deg.get(v));
            return String.join(", ", parts);
        }

        /** Transformer winding-voltage labels present at this substation. */
        String transformersStr(String sub) {
            List<String> t = transformerLabels.getOrDefault(sub, List.of());
            return t.isEmpty() ? "" : String.join(" ", t);
        }

        /**
         * Structural signature - deliberately <b>voltage-agnostic</b>: which
         * already-matched substations this one connects to (by pair id, as a
         * multiset), the total connection count, and the transformer rated-voltage
         * labels. Nominal/line voltages are known to differ between the two models
         * for the same object, so they are NOT part of the identity key (they are
         * only reported as a note on the resulting match).
         */
        String signature(String sub, Map<String, Integer> pairId) {
            List<String> matchedNbr = new ArrayList<>();
            for (String n : neighbours.getOrDefault(sub, List.of())) {
                Integer pid = pairId.get(n);
                if (pid != null) matchedNbr.add("N:P" + pid);
            }
            Collections.sort(matchedNbr);

            StringBuilder sb = new StringBuilder();
            for (String t : matchedNbr) sb.append(t).append(";");
            sb.append("|D").append(connectionsTotal(sub)).append("|");
            for (String t : transformerLabels.getOrDefault(sub, List.of())) sb.append(t).append(";");
            return sb.toString();
        }
    }
}
