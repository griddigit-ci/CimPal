/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.GridData.ContainmentRec;
import eu.griddigit.cimpal.core.idmapping.GridData.ObjectInfo;
import eu.griddigit.cimpal.core.idmapping.GridData.ParamRec;
import eu.griddigit.cimpal.core.idmapping.GridData.TerminalRec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One side's topology built from its {@link GridData}: equipment with ordered terminals, nodes with
 * the substation they belong to, and the logical lines between substations.
 * <p>
 * A node's substation comes from its container; without one, from the non-line equipment at the
 * node, and then across switches. A node that still has none (for example one inside a Line
 * container) is a line-interior node, and the series elements through it are chained into one
 * logical line. With {@code collapsePassThrough}, a substation that holds nothing but line ends and
 * switches is treated the same way.
 */
final class Network {

    static final String BRANCH = "branch";
    static final String SWITCH = "switch";
    static final String BREAKER = "breaker";
    static final String BUSBAR = "busbar";
    static final String TRANSFORMER = "transformer";

    /** Whether a role is a switching device: a breaker or another switch. */
    static boolean isSwitch(String role) {
        return SWITCH.equals(role) || BREAKER.equals(role);
    }

    /** What an object is to the matching; also the order of the report. */
    enum Category { CONTAINER, EQUIPMENT, TRANSFORMER_END, NODE, TERMINAL, DEPENDENT, OTHER }

    static final Set<String> CONTAINER_TYPES = Set.of("Substation", "VoltageLevel", "Bay", "Line", "DCConverterUnit");

    static final class Equipment {
        final String iri;
        final String type;
        final String role;
        final List<TerminalRec> terminals = new ArrayList<>();
        String substation;
        Double nominalVoltage;

        Equipment(String iri, String type, String role) {
            this.iri = iri;
            this.type = type;
            this.role = role;
        }
    }

    static final class NodeInfo {
        final String iri;
        final List<TerminalRec> terminals = new ArrayList<>();
        String substation;
        String voltageLevel;
        Double nominalVoltage;
        /** Contained in a Line (or another non-substation container): never assigned a substation. */
        boolean lineContained;

        NodeInfo(String iri) {
            this.iri = iri;
        }
    }

    final Side side;
    final GridData data;
    final Map<String, Equipment> equipment = new LinkedHashMap<>();
    final Map<String, NodeInfo> nodes = new LinkedHashMap<>();
    final Map<String, TerminalRec> terminals = new HashMap<>();
    /** Declared substations taking part in the matching (pass-through ones excluded). */
    final Set<String> substations = new HashSet<>();
    final Set<String> passThrough = new HashSet<>();
    final Map<String, List<String>> nodesBySubstation = new HashMap<>();
    final List<LogicalLine> lines;
    final Map<String, LogicalLine> lineOfMember = new HashMap<>();
    final Set<String> endIris = new HashSet<>();
    final Set<String> dependentIris = new HashSet<>();
    /**
     * Station (the substation standing for a group) to the substations it groups. A substation
     * outside any group stands for itself.
     */
    final Map<String, List<String>> stationMembers = new LinkedHashMap<>();
    /** Grouped substation to the substation that stands for its station. */
    final Map<String, String> stationOf = new HashMap<>();
    int nodesWithoutSubstation;

    Network(Side side, GridData data, boolean collapsePassThrough) {
        this(side, data, collapsePassThrough, true);
    }

    Network(Side side, GridData data, boolean collapsePassThrough, boolean groupStations) {
        this.side = side;
        this.data = data;
        data.ends.forEach(e -> endIris.add(e.end()));
        data.dependents.forEach(d -> dependentIris.add(d.dependent()));
        for (TerminalRec t : data.terminals) {
            terminals.put(t.terminal(), t);
            Equipment eq = equipment.computeIfAbsent(t.equipment(),
                    iri -> new Equipment(iri, t.equipmentType(), t.role() == null ? "equipment" : t.role()));
            eq.terminals.add(t);
            if (t.node() != null) {
                nodes.computeIfAbsent(t.node(), NodeInfo::new).terminals.add(t);
            }
        }
        equipment.values().forEach(eq -> eq.terminals.sort(Comparator.comparingInt(TerminalRec::sequenceNumber)));
        for (Map.Entry<String, ObjectInfo> e : data.objects.entrySet()) {
            if ("Substation".equals(e.getValue().type())) {
                substations.add(e.getKey());
            }
        }
        resolveContainment();
        resolveNodeSubstations();
        for (String sub : substations) {
            stationMembers.put(sub, new ArrayList<>(List.of(sub)));
        }
        if (groupStations) {
            groupStations();
        }
        if (collapsePassThrough) {
            collapsePassThroughSubstations();
        }
        for (NodeInfo node : nodes.values()) {
            if (node.substation != null) {
                nodesBySubstation.computeIfAbsent(node.substation, s -> new ArrayList<>()).add(node.iri);
            } else {
                nodesWithoutSubstation++;
            }
        }
        lines = LogicalLine.build(this);
        for (LogicalLine line : lines) {
            for (String member : line.members) {
                lineOfMember.put(member, line);
            }
        }
    }

    private void resolveContainment() {
        for (Equipment eq : equipment.values()) {
            ContainmentRec c = data.containment.get(eq.iri);
            if (c != null) {
                eq.substation = c.substation();
            }
            ParamRec p = data.parameters.get(eq.iri);
            if (p != null) {
                eq.nominalVoltage = p.nominalVoltage();
            }
        }
        for (NodeInfo node : nodes.values()) {
            ContainmentRec c = data.containment.get(node.iri);
            if (c == null) {
                continue;
            }
            node.substation = c.substation();
            node.voltageLevel = c.voltageLevel();
            node.lineContained = c.substation() == null && c.voltageLevel() == null && c.container() != null;
            if (c.voltageLevel() != null && data.voltageLevels.containsKey(c.voltageLevel())) {
                node.nominalVoltage = data.voltageLevels.get(c.voltageLevel()).nominalVoltage();
            }
        }
    }

    /**
     * Gives uncontained nodes the substation of the non-line equipment around them (when that is
     * one substation), then spreads substations across switches until nothing changes. Equipment
     * without a container then takes the substation its nodes agree on.
     */
    private void resolveNodeSubstations() {
        for (NodeInfo node : nodes.values()) {
            if (node.substation != null || node.lineContained) {
                continue;
            }
            Set<String> found = new HashSet<>();
            for (TerminalRec t : node.terminals) {
                Equipment eq = equipment.get(t.equipment());
                if (!BRANCH.equals(eq.role) && eq.substation != null) {
                    found.add(eq.substation);
                }
            }
            if (found.size() == 1) {
                node.substation = found.iterator().next();
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Equipment eq : equipment.values()) {
                if (!isSwitch(eq.role)) {
                    continue;
                }
                Set<String> found = new HashSet<>();
                if (eq.substation != null) {
                    found.add(eq.substation);
                }
                for (TerminalRec t : eq.terminals) {
                    NodeInfo n = t.node() == null ? null : nodes.get(t.node());
                    if (n != null && n.substation != null) {
                        found.add(n.substation);
                    }
                }
                if (found.size() != 1) {
                    continue;
                }
                String sub = found.iterator().next();
                for (TerminalRec t : eq.terminals) {
                    NodeInfo n = t.node() == null ? null : nodes.get(t.node());
                    if (n != null && n.substation == null && !n.lineContained) {
                        n.substation = sub;
                        changed = true;
                    }
                }
            }
        }
        for (Equipment eq : equipment.values()) {
            if (eq.substation != null || BRANCH.equals(eq.role)) {
                continue;
            }
            Set<String> found = new HashSet<>();
            for (TerminalRec t : eq.terminals) {
                NodeInfo n = t.node() == null ? null : nodes.get(t.node());
                found.add(n == null ? null : n.substation);
            }
            if (found.size() == 1 && !found.contains(null)) {
                eq.substation = found.iterator().next();
            }
        }
        for (NodeInfo node : nodes.values()) {
            if (node.nominalVoltage != null) {
                continue;
            }
            for (TerminalRec t : node.terminals) {
                Equipment eq = equipment.get(t.equipment());
                if (eq.nominalVoltage != null && !TRANSFORMER.equals(eq.role)) {
                    node.nominalVoltage = eq.nominalVoltage;
                    break;
                }
            }
        }
    }

    /**
     * Joins substations that are one physical station: those linked by a transformer or a switch
     * with terminals in both (one model may keep a station's voltage levels as separate
     * substations, FINGLAS / FINGLAS1 / FINGLAS2, where the other has one). The member with the
     * most nodes stands for the station; nodes and equipment of the others are moved to it.
     */
    private void groupStations() {
        Map<String, String> parent = new HashMap<>();
        for (Equipment eq : equipment.values()) {
            if (!TRANSFORMER.equals(eq.role) && !isSwitch(eq.role)) {
                continue;
            }
            String first = null;
            for (TerminalRec t : eq.terminals) {
                NodeInfo n = node(t.node());
                if (n == null || n.substation == null || !substations.contains(n.substation)) {
                    continue;
                }
                if (first == null) {
                    first = n.substation;
                } else {
                    union(parent, first, n.substation);
                }
            }
        }
        if (parent.isEmpty()) {
            return;
        }
        Map<String, Integer> size = new HashMap<>();
        for (NodeInfo n : nodes.values()) {
            if (n.substation != null) {
                size.merge(n.substation, 1, Integer::sum);
            }
        }
        Map<String, List<String>> groups = new HashMap<>();
        for (String sub : List.copyOf(parent.keySet())) {
            groups.computeIfAbsent(find(parent, sub), r -> new ArrayList<>()).add(sub);
        }
        for (List<String> members : groups.values()) {
            members.sort(Comparator.comparingInt((String s) -> -size.getOrDefault(s, 0)).thenComparing(s -> s));
            String station = members.get(0);
            for (String member : members) {
                stationOf.put(member, station);
                if (!member.equals(station)) {
                    stationMembers.remove(member);
                    substations.remove(member);
                }
            }
            stationMembers.put(station, new ArrayList<>(members));
        }
        for (NodeInfo n : nodes.values()) {
            if (n.substation != null) {
                n.substation = stationOf.getOrDefault(n.substation, n.substation);
            }
        }
        for (Equipment eq : equipment.values()) {
            if (eq.substation != null) {
                eq.substation = stationOf.getOrDefault(eq.substation, eq.substation);
            }
        }
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra.compareTo(rb) < 0 ? rb : ra, ra.compareTo(rb) < 0 ? ra : rb);
        }
    }

    private static String find(Map<String, String> parent, String x) {
        parent.putIfAbsent(x, x);
        String root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        parent.put(x, root);
        return root;
    }

    /** The station standing for {@code substation} (itself when it is not grouped). */
    String station(String substation) {
        return substation == null ? null : stationOf.getOrDefault(substation, substation);
    }

    /** The names of a station's substations, the standing one first. */
    List<String> stationNames(String station) {
        List<String> names = new ArrayList<>();
        for (String member : stationMembers.getOrDefault(station, List.of(station))) {
            String name = name(member);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * A substation that carries only line ends, switches and at most one busbar section (no
     * transformer, load, machine or other equipment), with at least two line ends and fewer breakers
     * than line ends, is a junction on a line: a PowerFactory terminal ({@code Term_...}), a cable
     * sealing end, a tee point. Its nodes become line-interior, so the lines through it chain into
     * one. A switching station has a breaker for every line and stays a substation.
     */
    private void collapsePassThroughSubstations() {
        Map<String, int[]> profile = new HashMap<>();
        Map<String, Set<String>> breakers = new HashMap<>();
        for (NodeInfo node : nodes.values()) {
            if (node.substation == null) {
                continue;
            }
            int[] counts = profile.computeIfAbsent(node.substation, s -> new int[3]);
            for (TerminalRec t : node.terminals) {
                String role = equipment.get(t.equipment()).role;
                if (BRANCH.equals(role)) {
                    counts[0]++;
                } else if (BUSBAR.equals(role)) {
                    counts[1]++;
                } else if (BREAKER.equals(role)) {
                    breakers.computeIfAbsent(node.substation, s -> new HashSet<>()).add(t.equipment());
                } else if (!SWITCH.equals(role)) {
                    counts[2]++;
                }
            }
        }
        for (Map.Entry<String, int[]> e : profile.entrySet()) {
            int[] c = e.getValue();
            int breakerCount = breakers.getOrDefault(e.getKey(), Set.of()).size();
            if (c[2] == 0 && c[1] <= 1 && c[0] >= 2 && breakerCount < c[0]) {
                passThrough.add(e.getKey());
            }
        }
        if (passThrough.isEmpty()) {
            return;
        }
        substations.removeAll(passThrough);
        for (NodeInfo node : nodes.values()) {
            if (node.substation != null && passThrough.contains(node.substation)) {
                node.substation = null;
            }
        }
        for (Equipment eq : equipment.values()) {
            if (eq.substation != null && passThrough.contains(eq.substation)) {
                eq.substation = null;
            }
        }
    }

    Category category(String iri) {
        if (equipment.containsKey(iri)) {
            return Category.EQUIPMENT;
        }
        if (nodes.containsKey(iri)) {
            return Category.NODE;
        }
        if (terminals.containsKey(iri)) {
            return Category.TERMINAL;
        }
        if (endIris.contains(iri)) {
            return Category.TRANSFORMER_END;
        }
        String type = type(iri);
        if (type != null && CONTAINER_TYPES.contains(type)) {
            return Category.CONTAINER;
        }
        return dependentIris.contains(iri) ? Category.DEPENDENT : Category.OTHER;
    }

    NodeInfo node(String iri) {
        return iri == null ? null : nodes.get(iri);
    }

    /** The substation of a terminal's node, or null. */
    String substationOf(TerminalRec t) {
        NodeInfo n = node(t.node());
        return n == null ? null : n.substation;
    }

    ObjectInfo info(String iri) {
        return data.objects.get(iri);
    }

    String name(String iri) {
        ObjectInfo info = data.objects.get(iri);
        return info == null ? null : info.name();
    }

    String type(String iri) {
        ObjectInfo info = data.objects.get(iri);
        if (info != null) {
            return info.type();
        }
        Equipment eq = equipment.get(iri);
        return eq == null ? null : eq.type;
    }
}
