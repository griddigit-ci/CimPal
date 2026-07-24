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
 * matches the equipment inside by <em>where it sits in the reduced bay graph</em>,
 * not by attributes alone.
 *
 * <p>Method (the design's reduced-bay-graph idea, kept deterministic):</p>
 * <ol>
 *   <li>Contract every switch (Breaker/Disconnector/...): connectivity nodes
 *       joined through a switch become one reduced node. In EQ there is no
 *       open/closed state, so every switch is a connector.</li>
 *   <li>Anchor reduced nodes across the two models using the elements already
 *       matched in the line/substation phase: each matched line's endpoint node
 *       and each matched transformer's winding node pins a PF reduced node to its
 *       IGMS counterpart.</li>
 *   <li>Match transformers (by winding-voltage multiset in the substation),
 *       busbars and injections that hang off anchored reduced nodes.</li>
 * </ol>
 *
 * <p>Switches themselves are treated as connectors and not individually mapped
 * (node-breaker vs simplified granularity differs between models). Functional
 * equipment with no counterpart is returned for human review.</p>
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
            matchSubstation(pf, igms, pair.getKey(), pair.getValue(),
                    pfTables, igmsTables, phase2.lineMatches(), matches, unmatched);
        }
        return new Result(matches, unmatched);
    }

    private static void matchSubstation(GraphIndex pf, GraphIndex igms, String sPf, String sIgms,
                                        ModelTables pfT, ModelTables igmsT,
                                        List<SubstationLineMatcher.LineMatch> lineMatches,
                                        List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
        Dsu pfRed = reducedNodes(pf, sPf);
        Dsu igmsRed = reducedNodes(igms, sIgms);

        // reduced-node correspondence: PF root -> IGMS root
        Map<String, String> rootMap = new HashMap<>();

        // --- anchors from matched lines: pin the PF line-endpoint node in this substation
        // to the IGMS line-endpoint node of its matched counterpart. After switch
        // contraction the busbars and bays of the same bay share this reduced node. ---
        for (SubstationLineMatcher.LineMatch lm : lineMatches) {
            String pfCn = endpointCnInSubstation(lm.pf(), sPf);
            String igmsCn = endpointCnInSubstation(lm.igms(), sIgms);
            if (pfCn != null && igmsCn != null) {
                rootMap.putIfAbsent(pfRed.find(pfCn), igmsRed.find(igmsCn));
            }
        }

        // --- transformers: match within the substation by name, then by winding-voltage multiset ---
        Map<String, List<TransformerEndRow>> pfEnds = endsByTrafo(pfT);
        Map<String, List<TransformerEndRow>> igmsEnds = endsByTrafo(igmsT);
        matchTransformers(pf, igms, sPf, sIgms, pfEnds, igmsEnds, pfRed, igmsRed, rootMap, matches, unmatched);

        // --- busbars and injections off anchored reduced nodes ---
        matchByReducedNode(pf, igms, sPf, sIgms, pfRed, igmsRed, rootMap,
                "BusbarSection", matches, unmatched);
        for (String cls : INJECTION_CLASSES) {
            matchByReducedNode(pf, igms, sPf, sIgms, pfRed, igmsRed, rootMap, cls, matches, unmatched);
        }
    }

    /** Union CNs in the substation that are joined through a switch. */
    private static Dsu reducedNodes(GraphIndex g, String substation) {
        Set<String> cns = g.connectivityNodesInSubstation(substation);
        Dsu dsu = new Dsu(cns);
        for (String cn : cns) {
            for (String eq : g.equipmentAt(cn)) {
                if (!g.isSwitch(eq)) continue;
                List<String> switchCns = new ArrayList<>();
                for (String c : g.cnsOf(eq)) {
                    if (cns.contains(c)) switchCns.add(c);
                }
                for (int i = 1; i < switchCns.size(); i++) {
                    dsu.union(switchCns.get(0), switchCns.get(i));
                }
            }
        }
        return dsu;
    }

    /** Pin PF reduced nodes to IGMS reduced nodes via a matched transformer's winding nodes. */
    private static void anchorTransformerNodes(GraphIndex pf, GraphIndex igms, Dsu pfRed, Dsu igmsRed,
                                               List<TransformerEndRow> pfEnds, List<TransformerEndRow> igmsEnds,
                                               Map<String, String> rootMap) {
        if (pfEnds == null || igmsEnds == null) return;
        List<TransformerEndRow> a = new ArrayList<>(pfEnds);
        List<TransformerEndRow> b = new ArrayList<>(igmsEnds);
        a.sort(Comparator.comparing(t -> ratedKey(t)));
        b.sort(Comparator.comparing(t -> ratedKey(t)));
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            String pfCn = pf.cnOfTerminal(a.get(i).terminal());
            String igmsCn = igms.cnOfTerminal(b.get(i).terminal());
            if (pfCn != null && igmsCn != null) {
                rootMap.putIfAbsent(pfRed.find(pfCn), igmsRed.find(igmsCn));
            }
        }
    }

    /**
     * Match equipment of one class that hangs off anchored reduced nodes. When a
     * reduced node carries several equipment of the class (e.g. two busbars of a
     * double-busbar arrangement joined by a bus coupler), they are disambiguated
     * by name; the leftover 1:1 is matched, and anything still ambiguous is
     * reported.
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
                    unmatched.add(new UnmatchedEl(Names.mrid(pfEq), cls, safe(pf.eqName(pfEq)), "PF",
                            "no topological anchor to a matched IGMS node (likely granularity difference)"));
                }
                continue;
            }
            List<String> igmsEqs = new ArrayList<>(igmsByRoot.getOrDefault(igmsRoot, List.of()));
            igmsEqs.removeIf(usedIgms::contains);

            // Pass 1: unique exact-name match within the node.
            Iterator<String> pit = pfEqs.iterator();
            while (pit.hasNext()) {
                String pfEq = pit.next();
                String pfName = norm(pf.eqName(pfEq));
                if (pfName == null) continue;
                List<String> byName = new ArrayList<>();
                for (String ie : igmsEqs) {
                    if (pfName.equals(norm(igms.eqName(ie)))) byName.add(ie);
                }
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
                    unmatched.add(new UnmatchedEl(Names.mrid(pfEq), cls, safe(pf.eqName(pfEq)), "PF",
                            "no unique " + cls + " counterpart at the matched node (symmetric; names do not disambiguate)"));
                }
            }
        }
        // IGMS equipment of this class not matched.
        for (Map.Entry<String, List<String>> e : igmsByRoot.entrySet()) {
            for (String igmsEq : e.getValue()) {
                if (!usedIgms.contains(igmsEq)) {
                    unmatched.add(new UnmatchedEl(Names.mrid(igmsEq), cls, safe(igms.eqName(igmsEq)), "IGMS",
                            "no counterpart on PF side at the matched node"));
                }
            }
        }
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    // ---- helpers ----

    private static Map<String, List<String>> equipmentByRoot(GraphIndex g, String substation, Dsu red, String cls) {
        Map<String, List<String>> byRoot = new LinkedHashMap<>();
        Set<String> cns = g.connectivityNodesInSubstation(substation);
        Set<String> seen = new HashSet<>();
        for (String cn : cns) {
            for (String eq : g.equipmentAt(cn)) {
                if (!cls.equals(g.eqClass(eq)) || !seen.add(eq)) continue;
                // primary CN of this equipment inside the substation
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

    /** Match transformers within a substation pair: unique name first, then unique winding multiset. */
    private static void matchTransformers(GraphIndex pf, GraphIndex igms, String sPf, String sIgms,
                                          Map<String, List<TransformerEndRow>> pfEnds,
                                          Map<String, List<TransformerEndRow>> igmsEnds,
                                          Dsu pfRed, Dsu igmsRed, Map<String, String> rootMap,
                                          List<ElementMatch> matches, List<UnmatchedEl> unmatched) {
        List<String> pfTr = trafosInSubstation(pfEnds, sPf);
        List<String> igTr = trafosInSubstation(igmsEnds, sIgms);
        Set<String> used = new HashSet<>();
        List<String> pfRemain = new ArrayList<>();

        // pass 1: unique exact-name match
        for (String p : pfTr) {
            String nm = norm(trafoName(pfEnds, p, pfEnds));
            String uniq = null;
            int cnt = 0;
            if (nm != null) {
                for (String q : igTr) {
                    if (used.contains(q)) continue;
                    if (nm.equals(norm(trafoName(igmsEnds, q, igmsEnds)))) { uniq = q; cnt++; }
                }
            }
            if (cnt == 1) {
                emitTrafo(p, uniq, pfEnds, igmsEnds, pf, igms, pfRed, igmsRed, rootMap, matches);
                used.add(uniq);
            } else {
                pfRemain.add(p);
            }
        }
        // pass 2: unique winding-voltage multiset
        Map<String, List<String>> pfByMs = new LinkedHashMap<>();
        for (String p : pfRemain) pfByMs.computeIfAbsent(multisetKey(pfEnds.get(p)), k -> new ArrayList<>()).add(p);
        Map<String, List<String>> igByMs = new LinkedHashMap<>();
        for (String q : igTr) if (!used.contains(q)) igByMs.computeIfAbsent(multisetKey(igmsEnds.get(q)), k -> new ArrayList<>()).add(q);
        for (Map.Entry<String, List<String>> e : pfByMs.entrySet()) {
            List<String> pl = e.getValue();
            List<String> ql = igByMs.getOrDefault(e.getKey(), List.of());
            if (pl.size() == 1 && ql.size() == 1 && !used.contains(ql.get(0))) {
                emitTrafo(pl.get(0), ql.get(0), pfEnds, igmsEnds, pf, igms, pfRed, igmsRed, rootMap, matches);
                used.add(ql.get(0));
            } else {
                for (String p : pl) {
                    unmatched.add(new UnmatchedEl(Names.mrid(p), "PowerTransformer",
                            safe(trafoName(pfEnds, p, pfEnds)), "PF",
                            "no unique winding-voltage/name match in matched substation"));
                }
            }
        }
        for (String q : igTr) {
            if (!used.contains(q)) {
                unmatched.add(new UnmatchedEl(Names.mrid(q), "PowerTransformer",
                        safe(trafoName(igmsEnds, q, igmsEnds)), "IGMS",
                        "no unique winding-voltage/name match in matched substation"));
            }
        }
    }

    private static void emitTrafo(String p, String q,
                                  Map<String, List<TransformerEndRow>> pfEnds, Map<String, List<TransformerEndRow>> igmsEnds,
                                  GraphIndex pf, GraphIndex igms, Dsu pfRed, Dsu igmsRed, Map<String, String> rootMap,
                                  List<ElementMatch> matches) {
        matches.add(new ElementMatch(Names.mrid(p), "PowerTransformer", Names.mrid(q),
                safe(trafoName(igmsEnds, q, igmsEnds))));
        anchorTransformerNodes(pf, igms, pfRed, igmsRed, pfEnds.get(p), igmsEnds.get(q), rootMap);
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
        if (ends != null) {
            for (TransformerEndRow te : ends) {
                if (te.ratedU() != null) rated.add((int) Math.round(te.ratedU()));
            }
        }
        Collections.sort(rated);
        return rated.toString();
    }

    private static Map<String, List<TransformerEndRow>> endsByTrafo(ModelTables t) {
        Map<String, List<TransformerEndRow>> byTrafo = new LinkedHashMap<>();
        for (TransformerEndRow te : t.transformerEnds()) {
            if (te.trafo() != null) byTrafo.computeIfAbsent(te.trafo(), k -> new ArrayList<>()).add(te);
        }
        return byTrafo;
    }

    private static String trafoName(Map<String, List<TransformerEndRow>> ends, String trafo,
                                    Map<String, List<TransformerEndRow>> fallback) {
        List<TransformerEndRow> list = ends.get(trafo);
        if (list == null) list = fallback.get(trafo);
        if (list == null) return "";
        for (TransformerEndRow te : list) {
            if (te.trafoName() != null) return te.trafoName();
        }
        return "";
    }

    /** The endpoint connectivity node of a logical line that lies in the given substation. */
    private static String endpointCnInSubstation(LogicalLine line, String substation) {
        List<String> cns = line.endpointCns();
        List<String> subs = line.endpointSubstations();
        for (int i = 0; i < Math.min(cns.size(), subs.size()); i++) {
            if (substation.equals(subs.get(i))) return cns.get(i);
        }
        return null;
    }

    private static String ratedKey(TransformerEndRow t) {
        int r = t.ratedU() == null ? 0 : (int) Math.round(t.ratedU());
        int n = t.endNum() == null ? 0 : t.endNum();
        return String.format("%08d_%03d", r, n);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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
