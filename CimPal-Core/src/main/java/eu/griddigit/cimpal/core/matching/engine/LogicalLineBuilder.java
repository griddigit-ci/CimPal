/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.AcLineSegmentRow;
import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.ModelTables;

import java.util.*;

/**
 * Phase 1: collapse series chains of ACLineSegments (plus pass-through
 * SeriesCompensators/Junctions) into logical lines.
 *
 * <p>Two line-eligible edges are merged when they meet at an <em>interior</em>
 * connectivity node: one that is not in a substation, has no non-line equipment
 * attached, and has degree &ge; 2. Merging at degree-3 interior nodes keeps a
 * T-connection as a single multi-ended logical line rather than splitting it
 * into pairs. Endpoints are the boundary CNs (in a substation, carrying other
 * equipment, or a loose/boundary end).</p>
 *
 * <p>Pure: consumes only {@link ModelTables}. Never contracts silently — every
 * irregular or unconfirmed chain is recorded in the logical line's notes so it
 * surfaces in the report.</p>
 */
public final class LogicalLineBuilder {

    private LogicalLineBuilder() {
    }

    public static List<LogicalLine> build(ModelTables tables) {
        GraphIndex idx = new GraphIndex(tables);

        // 1. Collect line-eligible equipment (from terminals + declared ACLS).
        LinkedHashSet<String> edges = new LinkedHashSet<>();
        for (var t : tables.terminals()) {
            if (t.eq() != null && idx.isLineEligible(t.eq())) edges.add(t.eq());
        }
        for (AcLineSegmentRow a : tables.acLineSegments()) {
            if (a.id() != null) edges.add(a.id());
        }

        // 2. Union-Find over edges; union those sharing an interior merge node.
        DisjointSet dsu = new DisjointSet(edges);
        for (String cn : idx.allConnectivityNodes()) {
            if (!isInteriorMergeNode(idx, cn)) continue;
            List<String> incident = idx.lineEdgesAt(cn);
            for (int i = 1; i < incident.size(); i++) {
                dsu.union(incident.get(0), incident.get(i));
            }
        }

        // 3. Group edges by representative root.
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String e : edges) {
            groups.computeIfAbsent(dsu.find(e), k -> new ArrayList<>()).add(e);
        }

        // 4. Build one logical line per group.
        List<LogicalLine> lines = new ArrayList<>();
        for (List<String> members : groups.values()) {
            lines.add(buildLine(idx, tables, members));
        }
        return lines;
    }

    private static boolean isInteriorMergeNode(GraphIndex idx, String cn) {
        return idx.cnSubstation(cn) == null
                && !idx.hasNonLineEquipment(cn)
                && idx.lineEdgesAt(cn).size() >= 2;
    }

    private static LogicalLine buildLine(GraphIndex idx, ModelTables tables, List<String> membersRaw) {
        List<String> members = new ArrayList<>(new LinkedHashSet<>(membersRaw));
        Collections.sort(members);

        List<String> notes = new ArrayList<>();
        List<String> aclsMembers = new ArrayList<>();
        boolean hasPassThrough = false;
        for (String m : members) {
            String cls = idx.eqClass(m);
            if ("ACLineSegment".equals(cls)) {
                aclsMembers.add(m);
            } else if (cls != null) {
                hasPassThrough = true;
            }
        }
        if (hasPassThrough) {
            notes.add("contains SeriesCompensator/Junction pass-through (impedance not summed for those)");
        }

        // Sum electrical parameters over ACLS members only.
        double r = 0, x = 0, bch = 0, length = 0;
        Double nomV = null;
        for (String m : aclsMembers) {
            AcLineSegmentRow a = idx.acls(m);
            if (a == null) continue;
            r += nz(a.r());
            x += nz(a.x());
            bch += nz(a.bch());
            length += nz(a.length());
            if (nomV == null && a.nomV() != null) nomV = a.nomV();
        }

        // Endpoints: CNs of member edges that are not interior merge nodes.
        LinkedHashSet<String> endpointCns = new LinkedHashSet<>();
        boolean irregularTerminalCount = false;
        for (String m : members) {
            List<String> cns = idx.cnsOf(m);
            if (cns.size() != 2 && !"Junction".equals(idx.eqClass(m))) {
                irregularTerminalCount = true;
            }
            for (String cn : cns) {
                if (!isInteriorMergeNode(idx, cn)) endpointCns.add(cn);
            }
        }
        if (irregularTerminalCount) {
            notes.add("member with irregular terminal count (!=2)");
        }

        List<String> endpointCnList = new ArrayList<>(endpointCns);
        List<String> endpointSubs = new ArrayList<>();
        LinkedHashSet<String> distinctSubs = new LinkedHashSet<>();
        for (String cn : endpointCnList) {
            String sub = idx.cnSubstation(cn);
            endpointSubs.add(sub); // nullable => unresolved endpoint
            if (sub != null) distinctSubs.add(sub);
        }

        // ACLineSegment.BaseVoltage is usually absent; fall back to the nominal
        // voltage seen at the line's connectivity nodes (their VoltageLevel).
        if (nomV == null) {
            for (String cn : endpointCnList) {
                Double v = idx.cnNominalVoltage(cn);
                if (v != null) { nomV = v; break; }
            }
        }
        if (nomV == null) {
            for (String m : members) {
                for (String cn : idx.cnsOf(m)) {
                    Double v = idx.cnNominalVoltage(cn);
                    if (v != null) { nomV = v; break; }
                }
                if (nomV != null) break;
            }
        }

        if (endpointCnList.isEmpty()) {
            notes.add("no resolvable endpoints (isolated)");
        } else if (endpointCnList.size() == 1) {
            notes.add("dangling / boundary: single endpoint");
        } else if (endpointCnList.size() > 3) {
            notes.add("complex junction: " + endpointCnList.size() + " endpoints");
        }
        if (members.size() > 1 && distinctSubs.size() < 2) {
            notes.add("collapse not confirmed by two distinct substations (missing-container guard)");
        }

        boolean multiEnded = endpointCnList.size() > 2;
        String name = representativeName(idx, aclsMembers, members);
        String id = members.isEmpty() ? "EMPTY" : members.get(0);

        return new LogicalLine(
                id, tables.side(), name,
                List.copyOf(members), aclsMembers.size(),
                r, x, bch, length, nomV,
                List.copyOf(endpointCnList), endpointSubs,
                multiEnded, List.copyOf(notes));
    }

    private static String representativeName(GraphIndex idx, List<String> aclsMembers, List<String> members) {
        // Prefer a shared Line-container name across the ACLS members.
        String sharedContainerName = null;
        boolean shared = !aclsMembers.isEmpty();
        for (String m : aclsMembers) {
            AcLineSegmentRow a = idx.acls(m);
            String cn = (a == null) ? null : a.lineName();
            if (sharedContainerName == null) {
                sharedContainerName = cn;
            } else if (!sharedContainerName.equals(cn)) {
                shared = false;
                break;
            }
        }
        if (shared && sharedContainerName != null) return sharedContainerName;

        for (String m : aclsMembers) {
            AcLineSegmentRow a = idx.acls(m);
            if (a != null && a.name() != null) return a.name();
        }
        for (String m : members) {
            String n = idx.eqName(m);
            if (n != null) return n;
        }
        return null;
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    /** Minimal union-find keyed by string id. */
    private static final class DisjointSet {
        private final Map<String, String> parent = new HashMap<>();

        DisjointSet(Collection<String> items) {
            for (String i : items) parent.put(i, i);
        }

        String find(String a) {
            String p = parent.get(a);
            if (p == null) {
                parent.put(a, a);
                return a;
            }
            while (!p.equals(parent.get(p))) {
                parent.put(p, parent.get(parent.get(p)));
                p = parent.get(p);
            }
            // path-compress from a
            String root = p;
            String cur = a;
            while (!cur.equals(root)) {
                String next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
    }
}
