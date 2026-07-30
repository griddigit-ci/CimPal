/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.TransformerEndRow;

import java.util.*;

/**
 * Within-substation (topology) matching. Runs per matched substation pair and
 * matches the equipment inside by <em>where it sits in the reduced bay graph</em>.
 *
 * <p>Method: contract every switch (so a bay collapses to one reduced node),
 * anchor PF reduced nodes to IGMS reduced nodes using the already-matched lines
 * and transformers, then match transformers, busbars and injections that hang
 * off anchored nodes. Every gate is two-sided (unique on both PF and IGMS) and
 * conflicting anchors are dropped, so an ambiguous situation is reported rather
 * than guessed. Switches are treated as connectors and not individually mapped.</p>
 */
public final class TopologyMatcher {

    public static final Set<String> INJECTION_CLASSES = Set.of(
            "SynchronousMachine", "AsynchronousMachine", "EnergyConsumer", "ConformLoad",
            "NonConformLoad", "EnergySource", "LinearShuntCompensator", "NonlinearShuntCompensator",
            "StaticVarCompensator", "EquivalentInjection", "ExternalNetworkInjection");

    public record ElementMatch(String pfId, String type, String igmsId, String igmsName) {
    }

    public record UnmatchedEl(String id, String type, String name, String side, String reason) {
    }

    public record Result(List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
    }

    private TopologyMatcher() {
    }

    public static Result match(ModelTables pfTables, List<LogicalLine> pfLines,
                               ModelTables igmsTables, List<LogicalLine> igmsLines,
                               SubstationLineMatcher.Result phase2) {
        GraphIndex pf = new GraphIndex(pfTables);
        GraphIndex igms = new GraphIndex(igmsTables);

        List<ElementMatch> matches = new ArrayList<>();
        List<UnmatchedEl> unmatched = new ArrayList<>();

        for (Map.Entry<String, String> pair : phase2.substationMatch().entrySet()) {
            matchSubstation(pf, igms, pair.getKey(), pair.getValue(), phase2.lineMatches(), matches, unmatched);
        }
        return new Result(matches, unmatched);
    }

    private static void matchSubstation(GraphIndex pf, GraphIndex igms, String sPf, String sIgms,
                                        List<SubstationLineMatcher.LineMatch> lineMatches,
                                        List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
        Dsu pfRed = reducedNodes(pf, sPf);
        Dsu igmsRed = reducedNodes(igms, sIgms);

        // Collect reduced-node anchors with conflict detection (never first-wins).
        Anchors anchors = new Anchors();
        for (SubstationLineMatcher.LineMatch lm : lineMatches) {
            String pfCn = endpointCnInSubstation(lm.pf(), sPf);
            String igmsCn = endpointCnInSubstation(lm.igms(), sIgms);
            if (pfCn != null && igmsCn != null) {
                anchors.add(pfRed.find(pfCn), igmsRed.find(igmsCn));
            }
        }

        Map<String, List<TransformerEndRow>> pfEnds = endsByTrafo(pf.tables());
        Map<String, List<TransformerEndRow>> igmsEnds = endsByTrafo(igms.tables());
        matchTransformers(pf, igms, sPf, sIgms, pfEnds, igmsEnds, pfRed, igmsRed, anchors, matches, unmatched);

        Map<String, String> rootMap = anchors.resolved();

        matchByReducedNode(pf, igms, sPf, sIgms, pfRed, igmsRed, rootMap, "BusbarSection", matches, unmatched);
        for (String cls : INJECTION_CLASSES) {
            matchByReducedNode(pf, igms, sPf, sIgms, pfRed, igmsRed, rootMap, cls, matches, unmatched);
        }
    }

    /** Union CNs in the substation that are joined through a switch (switch = connector in EQ). */
    private static Dsu reducedNodes(GraphIndex g, String substation) {
        Set<String> cns = g.connectivityNodesInSubstation(substation);
        Dsu dsu = new Dsu(cns);
        for (String cn : cns) {
            for (String eq : g.equipmentAt(cn)) {
                if (!g.isSwitch(eq)) continue;
                List<String> switchCns = new ArrayList<>();
                for (String c : g.cnsOf(eq)) if (cns.contains(c)) switchCns.add(c);
                for (int i = 1; i < switchCns.size(); i++) dsu.union(switchCns.get(0), switchCns.get(i));
            }
        }
        return dsu;
    }

    /**
     * Pin PF reduced nodes to IGMS reduced nodes via a matched transformer's
     * winding nodes - but ONLY for windings whose ratedU is unique on both sides,
     * so we never rely on endNumber ordering to pair equal-voltage windings.
     */
    private static void anchorTransformerNodes(GraphIndex pf, GraphIndex igms, Dsu pfRed, Dsu igmsRed,
                                               List<TransformerEndRow> pfEnds, List<TransformerEndRow> igmsEnds,
                                               Anchors anchors) {
        if (pfEnds == null || igmsEnds == null) return;
        Map<Integer, Long> ca = countByRatedU(pfEnds);
        Map<Integer, Long> cb = countByRatedU(igmsEnds);
        Map<Integer, TransformerEndRow> aByV = new HashMap<>();
        Map<Integer, TransformerEndRow> bByV = new HashMap<>();
        for (TransformerEndRow te : pfEnds) {
            Integer v = ratedU(te);
            if (v != null && ca.get(v) == 1L) aByV.put(v, te);
        }
        for (TransformerEndRow te : igmsEnds) {
            Integer v = ratedU(te);
            if (v != null && cb.get(v) == 1L) bByV.put(v, te);
        }
        for (Map.Entry<Integer, TransformerEndRow> e : aByV.entrySet()) {
            TransformerEndRow b = bByV.get(e.getKey());
            if (b == null) continue;
            String pfCn = pf.cnOfTerminal(e.getValue().terminal());
            String igmsCn = igms.cnOfTerminal(b.terminal());
            if (pfCn != null && igmsCn != null) anchors.add(pfRed.find(pfCn), igmsRed.find(igmsCn));
        }
    }

    /**
     * Match equipment of one class hanging off anchored reduced nodes. Within a
     * node, equipment is matched one-to-one by unique name on BOTH sides, then a
     * remaining lone pair is matched; anything ambiguous is reported.
     */
    private static void matchByReducedNode(GraphIndex pf, GraphIndex igms, String sPf, String sIgms,
                                           Dsu pfRed, Dsu igmsRed, Map<String, String> rootMap,
                                           String cls, List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
        Map<String, List<String>> pfByRoot = equipmentByRoot(pf, sPf, pfRed, cls);
        Map<String, List<String>> igmsByRoot = equipmentByRoot(igms, sIgms, igmsRed, cls);
        Set<String> usedIgms = new HashSet<>();

        for (Map.Entry<String, List<String>> e : pfByRoot.entrySet()) {
            String igmsRoot = rootMap.get(e.getKey());
            List<String> pfEqs = new ArrayList<>(e.getValue());
            if (igmsRoot == null) {
                for (String pfEq : pfEqs) {
                    unmatched.add(new UnmatchedEl(Names.mrid(pfEq), cls, safe(pf.eqName(pfEq)), "IGMG",
                            "no topological anchor to a matched IGMS node (likely granularity difference)"));
                }
                continue;
            }
            List<String> igmsEqs = new ArrayList<>(igmsByRoot.getOrDefault(igmsRoot, List.of()));
            igmsEqs.removeIf(usedIgms::contains);

            // PF-side name multiplicity at this node (for one-to-one name matching).
            Map<String, Integer> pfNameCount = new HashMap<>();
            for (String pfEq : pfEqs) {
                String n = norm(pf.eqName(pfEq));
                if (n != null) pfNameCount.merge(n, 1, Integer::sum);
            }

            // Pass 1: exact-name match, unique on BOTH sides.
            Iterator<String> pit = pfEqs.iterator();
            while (pit.hasNext()) {
                String pfEq = pit.next();
                String pfName = norm(pf.eqName(pfEq));
                if (pfName == null || pfNameCount.getOrDefault(pfName, 0) != 1) continue;
                List<String> byName = new ArrayList<>();
                for (String ie : igmsEqs) if (pfName.equals(norm(igms.eqName(ie)))) byName.add(ie);
                if (byName.size() == 1) {
                    String igmsEq = byName.get(0);
                    matches.add(new ElementMatch(Names.mrid(pfEq), cls, Names.mrid(igmsEq), safe(igms.eqName(igmsEq))));
                    usedIgms.add(igmsEq);
                    igmsEqs.remove(igmsEq);
                    pit.remove();
                }
            }
            // Pass 2: single remaining candidate on each side.
            if (pfEqs.size() == 1 && igmsEqs.size() == 1) {
                String pfEq = pfEqs.get(0);
                String igmsEq = igmsEqs.get(0);
                matches.add(new ElementMatch(Names.mrid(pfEq), cls, Names.mrid(igmsEq), safe(igms.eqName(igmsEq))));
                usedIgms.add(igmsEq);
            } else {
                for (String pfEq : pfEqs) {
                    unmatched.add(new UnmatchedEl(Names.mrid(pfEq), cls, safe(pf.eqName(pfEq)), "IGMG",
                            "no unique " + cls + " counterpart at the matched node (symmetric; names do not disambiguate)"));
                }
            }
        }
        for (Map.Entry<String, List<String>> e : igmsByRoot.entrySet()) {
            for (String igmsEq : e.getValue()) {
                if (!usedIgms.contains(igmsEq)) {
                    unmatched.add(new UnmatchedEl(Names.mrid(igmsEq), cls, safe(igms.eqName(igmsEq)), "PF",
                            "no counterpart on PF side at the matched node"));
                }
            }
        }
    }

    /** Match transformers within a substation pair: unique name on both sides, then unique winding multiset. */
    private static void matchTransformers(GraphIndex pf, GraphIndex igms, String sPf, String sIgms,
                                          Map<String, List<TransformerEndRow>> pfEnds,
                                          Map<String, List<TransformerEndRow>> igmsEnds,
                                          Dsu pfRed, Dsu igmsRed, Anchors anchors,
                                          List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
        List<String> pfTr = trafosInSubstation(pfEnds, sPf);
        List<String> igTr = trafosInSubstation(igmsEnds, sIgms);
        Set<String> used = new HashSet<>();
        List<String> pfRemain = new ArrayList<>();

        // PF-side name multiplicity (one-to-one name matching).
        Map<String, Integer> pfNameCount = new HashMap<>();
        for (String p : pfTr) {
            String nm = norm(trafoName(pfEnds, p, pfEnds));
            if (nm != null) pfNameCount.merge(nm, 1, Integer::sum);
        }

        // pass 1: exact name, unique on BOTH sides
        for (String p : pfTr) {
            String nm = norm(trafoName(pfEnds, p, pfEnds));
            if (nm == null || pfNameCount.getOrDefault(nm, 0) != 1) { pfRemain.add(p); continue; }
            String uniq = null;
            int cnt = 0;
            for (String q : igTr) {
                if (used.contains(q)) continue;
                if (nm.equals(norm(trafoName(igmsEnds, q, igmsEnds)))) { uniq = q; cnt++; }
            }
            if (cnt == 1) {
                emitTrafo(p, uniq, pfEnds, igmsEnds, pf, igms, pfRed, igmsRed, anchors, matches);
                used.add(uniq);
            } else {
                pfRemain.add(p);
            }
        }
        // pass 2: unique winding-voltage multiset on both sides
        Map<String, List<String>> pfByMs = new LinkedHashMap<>();
        for (String p : pfRemain) pfByMs.computeIfAbsent(multisetKey(pfEnds.get(p)), k -> new ArrayList<>()).add(p);
        Map<String, List<String>> igByMs = new LinkedHashMap<>();
        for (String q : igTr) if (!used.contains(q)) igByMs.computeIfAbsent(multisetKey(igmsEnds.get(q)), k -> new ArrayList<>()).add(q);
        for (Map.Entry<String, List<String>> e : pfByMs.entrySet()) {
            List<String> pl = e.getValue();
            List<String> ql = igByMs.getOrDefault(e.getKey(), List.of());
            if (pl.size() == 1 && ql.size() == 1 && !used.contains(ql.get(0))) {
                emitTrafo(pl.get(0), ql.get(0), pfEnds, igmsEnds, pf, igms, pfRed, igmsRed, anchors, matches);
                used.add(ql.get(0));
            } else {
                for (String p : pl) {
                    unmatched.add(new UnmatchedEl(Names.mrid(p), "PowerTransformer",
                            safe(trafoName(pfEnds, p, pfEnds)), "IGMG",
                            "no unique winding-voltage/name match in matched substation"));
                }
            }
        }
        for (String q : igTr) {
            if (!used.contains(q)) {
                unmatched.add(new UnmatchedEl(Names.mrid(q), "PowerTransformer",
                        safe(trafoName(igmsEnds, q, igmsEnds)), "PF",
                        "no unique winding-voltage/name match in matched substation"));
            }
        }
    }

    private static void emitTrafo(String p, String q,
                                  Map<String, List<TransformerEndRow>> pfEnds, Map<String, List<TransformerEndRow>> igmsEnds,
                                  GraphIndex pf, GraphIndex igms, Dsu pfRed, Dsu igmsRed, Anchors anchors,
                                  List<ElementMatch> matches) {
        matches.add(new ElementMatch(Names.mrid(p), "PowerTransformer", Names.mrid(q),
                safe(trafoName(igmsEnds, q, igmsEnds))));
        anchorTransformerNodes(pf, igms, pfRed, igmsRed, pfEnds.get(p), igmsEnds.get(q), anchors);
    }

    // ---- helpers ----

    private static Map<Integer, Long> countByRatedU(List<TransformerEndRow> ends) {
        Map<Integer, Long> c = new HashMap<>();
        for (TransformerEndRow te : ends) {
            Integer v = ratedU(te);
            if (v != null) c.merge(v, 1L, Long::sum);
        }
        return c;
    }

    private static Integer ratedU(TransformerEndRow te) {
        return te.ratedU() == null ? null : (int) Math.round(te.ratedU());
    }

    private static Map<String, List<String>> equipmentByRoot(GraphIndex g, String substation, Dsu red, String cls) {
        Map<String, List<String>> byRoot = new LinkedHashMap<>();
        Set<String> cns = g.connectivityNodesInSubstation(substation);
        Set<String> seen = new HashSet<>();
        for (String cn : cns) {
            for (String eq : g.equipmentAt(cn)) {
                if (!cls.equals(g.eqClass(eq)) || !seen.add(eq)) continue;
                String primaryCn = null;
                for (String c : g.cnsOf(eq)) {
                    if (cns.contains(c)) { primaryCn = c; break; }
                }
                if (primaryCn == null) continue;
                byRoot.computeIfAbsent(red.find(primaryCn), k -> new ArrayList<>()).add(eq);
            }
        }
        return byRoot;
    }

    private static Map<String, List<TransformerEndRow>> endsByTrafo(ModelTables t) {
        Map<String, List<TransformerEndRow>> byTrafo = new LinkedHashMap<>();
        for (TransformerEndRow te : t.transformerEnds()) {
            if (te.trafo() != null) byTrafo.computeIfAbsent(te.trafo(), k -> new ArrayList<>()).add(te);
        }
        return byTrafo;
    }

    private static List<String> trafosInSubstation(Map<String, List<TransformerEndRow>> ends, String substation) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<TransformerEndRow>> e : ends.entrySet()) {
            if (e.getValue().stream().anyMatch(t -> substation.equals(t.substation()))) out.add(e.getKey());
        }
        return out;
    }

    private static String multisetKey(List<TransformerEndRow> ends) {
        List<Integer> rated = new ArrayList<>();
        if (ends != null) for (TransformerEndRow te : ends) if (te.ratedU() != null) rated.add((int) Math.round(te.ratedU()));
        Collections.sort(rated);
        return rated.toString();
    }

    private static String trafoName(Map<String, List<TransformerEndRow>> ends, String trafo,
                                    Map<String, List<TransformerEndRow>> fallback) {
        List<TransformerEndRow> list = ends.get(trafo);
        if (list == null) list = fallback.get(trafo);
        if (list == null) return "";
        for (TransformerEndRow te : list) if (te.trafoName() != null) return te.trafoName();
        return "";
    }

    private static String endpointCnInSubstation(LogicalLine line, String substation) {
        List<String> cns = line.endpointCns();
        List<String> subs = line.endpointSubstations();
        for (int i = 0; i < Math.min(cns.size(), subs.size()); i++) {
            if (substation.equals(subs.get(i))) return cns.get(i);
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Reduced-node anchor set with conflict detection. An anchor is a PF-root to
     * IGMS-root correspondence; a PF root mapped to two different IGMS roots, or
     * an IGMS root claimed by two different PF roots, marks the offenders
     * conflicted and {@link #resolved()} drops them (so their equipment falls to
     * the report instead of being matched first-wins).
     */
    private static final class Anchors {
        private final Map<String, String> map = new HashMap<>();
        private final Map<String, String> igmsOwner = new HashMap<>();
        private final Set<String> conflicted = new HashSet<>();

        void add(String pfRoot, String igmsRoot) {
            if (pfRoot == null || igmsRoot == null) return;
            String prev = map.get(pfRoot);
            if (prev != null) {
                if (!prev.equals(igmsRoot)) conflicted.add(pfRoot);
                return;
            }
            String owner = igmsOwner.get(igmsRoot);
            if (owner != null && !owner.equals(pfRoot)) {
                conflicted.add(pfRoot);
                conflicted.add(owner);
                return;
            }
            map.put(pfRoot, igmsRoot);
            igmsOwner.put(igmsRoot, pfRoot);
        }

        Map<String, String> resolved() {
            Map<String, String> r = new HashMap<>(map);
            r.keySet().removeAll(conflicted);
            return r;
        }
    }

    /** Minimal union-find keyed by string id. */
    private static final class Dsu {
        private final Map<String, String> parent = new HashMap<>();

        Dsu(Collection<String> items) {
            for (String i : items) parent.put(i, i);
        }

        String find(String a) {
            String p = parent.getOrDefault(a, a);
            parent.putIfAbsent(a, a);
            while (!p.equals(parent.get(p))) {
                parent.put(p, parent.get(parent.get(p)));
                p = parent.get(p);
            }
            return p;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
    }
}
