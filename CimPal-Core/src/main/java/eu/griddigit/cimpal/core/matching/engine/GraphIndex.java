/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.AcLineSegmentRow;
import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.TerminalRow;

import java.util.*;

/**
 * Precomputed connectivity index over one model's neutral tables. Nodes are
 * ConnectivityNodes; edges are conducting equipment. Everything the engine
 * needs about who-connects-to-what is derived once here.
 */
public final class GraphIndex {

    /** Equipment classes treated as part of a line for chain collapsing. */
    public static final Set<String> LINE_ELIGIBLE = Set.of(
            "ACLineSegment", "SeriesCompensator", "Junction");

    /** Switching devices - treated as pass-through connectors in the reduced bay graph. */
    public static final Set<String> SWITCH_CLASSES = Set.of(
            "Switch", "Breaker", "Disconnector", "LoadBreakSwitch", "GroundDisconnector",
            "Fuse", "Jumper", "ProtectedSwitch", "Sectionaliser", "DisconnectingCircuitBreaker");

    private final ModelTables tables;

    // cn -> terminals attached; eq -> terminals; eq -> class local name
    private final Map<String, List<TerminalRow>> byCn = new HashMap<>();
    private final Map<String, List<TerminalRow>> byEq = new HashMap<>();
    private final Map<String, String> eqClass = new HashMap<>();
    private final Map<String, String> eqName = new HashMap<>();
    private final Map<String, AcLineSegmentRow> aclsById = new HashMap<>();
    // cn -> resolved substation (first non-null), and cn -> whether non-line equipment attaches
    private final Map<String, String> cnSubstation = new HashMap<>();
    private final Map<String, String> cnContainerType = new HashMap<>();
    private final Map<String, String> terminalToCn = new HashMap<>();
    private final Map<String, Double> cnNomV = new HashMap<>();

    public GraphIndex(ModelTables tables) {
        this.tables = tables;
        for (TerminalRow t : tables.terminals()) {
            if (t.terminal() != null && t.cn() != null) {
                terminalToCn.putIfAbsent(t.terminal(), t.cn());
            }
            if (t.cn() != null) {
                byCn.computeIfAbsent(t.cn(), k -> new ArrayList<>()).add(t);
                if (t.substation() != null) {
                    cnSubstation.putIfAbsent(t.cn(), t.substation());
                }
                if (t.containerType() != null) {
                    cnContainerType.putIfAbsent(t.cn(), Names.localName(t.containerType()));
                }
                if (t.nomV() != null) {
                    cnNomV.putIfAbsent(t.cn(), t.nomV());
                }
            }
            if (t.eq() != null) {
                byEq.computeIfAbsent(t.eq(), k -> new ArrayList<>()).add(t);
                if (t.eqType() != null) {
                    eqClass.putIfAbsent(t.eq(), Names.localName(t.eqType()));
                }
                if (t.eqName() != null) {
                    eqName.putIfAbsent(t.eq(), t.eqName());
                }
            }
        }
        for (AcLineSegmentRow a : tables.acLineSegments()) {
            if (a.id() != null) aclsById.put(a.id(), a);
        }
    }

    /** ConnectivityNode a terminal attaches to (null if unknown). */
    public String cnOfTerminal(String terminal) { return terminalToCn.get(terminal); }

    /** Nominal voltage seen at a connectivity node (null if unknown). */
    public Double cnNominalVoltage(String cn) { return cnNomV.get(cn); }

    public boolean isSwitch(String eq) {
        String c = eqClass.get(eq);
        return c != null && SWITCH_CLASSES.contains(c);
    }

    /** All connectivity nodes resolved to the given substation. */
    public Set<String> connectivityNodesInSubstation(String substation) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : cnSubstation.entrySet()) {
            if (substation.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    public ModelTables tables() { return tables; }

    public String eqClass(String eq) { return eqClass.get(eq); }

    public String eqName(String eq) { return eqName.get(eq); }

    public AcLineSegmentRow acls(String eq) { return aclsById.get(eq); }

    public String cnSubstation(String cn) { return cnSubstation.get(cn); }

    public String cnContainerType(String cn) { return cnContainerType.get(cn); }

    public boolean isLineEligible(String eq) {
        String c = eqClass.get(eq);
        return c != null && LINE_ELIGIBLE.contains(c);
    }

    /** Distinct connectivity nodes an equipment attaches to. */
    public List<String> cnsOf(String eq) {
        List<TerminalRow> ts = byEq.get(eq);
        if (ts == null) return List.of();
        LinkedHashSet<String> cns = new LinkedHashSet<>();
        for (TerminalRow t : ts) {
            if (t.cn() != null) cns.add(t.cn());
        }
        return new ArrayList<>(cns);
    }

    /** Distinct equipment attached to a connectivity node. */
    public List<String> equipmentAt(String cn) {
        List<TerminalRow> ts = byCn.get(cn);
        if (ts == null) return List.of();
        LinkedHashSet<String> eqs = new LinkedHashSet<>();
        for (TerminalRow t : ts) {
            if (t.eq() != null) eqs.add(t.eq());
        }
        return new ArrayList<>(eqs);
    }

    /** Line-eligible equipment attached to a connectivity node. */
    public List<String> lineEdgesAt(String cn) {
        List<String> out = new ArrayList<>();
        for (String eq : equipmentAt(cn)) {
            if (isLineEligible(eq)) out.add(eq);
        }
        return out;
    }

    /** True if any non-line-eligible equipment attaches to this CN. */
    public boolean hasNonLineEquipment(String cn) {
        for (String eq : equipmentAt(cn)) {
            if (!isLineEligible(eq)) return true;
        }
        return false;
    }

    public Set<String> allConnectivityNodes() {
        return byCn.keySet();
    }
}
