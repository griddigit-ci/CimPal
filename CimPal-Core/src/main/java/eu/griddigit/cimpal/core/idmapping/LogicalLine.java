/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.GridData.ContainmentRec;
import eu.griddigit.cimpal.core.idmapping.GridData.ParamRec;
import eu.griddigit.cimpal.core.idmapping.GridData.TerminalRec;
import eu.griddigit.cimpal.core.idmapping.Network.Equipment;
import eu.griddigit.cimpal.core.idmapping.Network.NodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A connection between substations as one unit: the series elements (and switches outside
 * substations) chained through line-interior nodes, e.g.
 * {@code Node1(substation 1) - ACLineSegment 1 - Node2(Line) - ACLineSegment 2 - Node3(substation 2)}.
 * A chain that branches at an interior node (a tee) is one logical line with three or more ends.
 * An interior node where the chain stops without reaching a substation (a boundary point) is an
 * open end, keyed by the node itself, so a shared boundary node anchors both models.
 */
final class LogicalLine {

    static final String OPEN = "open:";

    /** Where the line leaves its chain: the node, the substation (or open-end) key, and the element that connects. */
    record Endpoint(String node, String key, String via) {
    }

    final int index;
    final List<String> members = new ArrayList<>();
    final List<String> interiorNodes = new ArrayList<>();
    final List<Endpoint> endpoints = new ArrayList<>();
    /** Equipment hanging off interior nodes without belonging to a substation (e.g. boundary injections). */
    final List<String> attachments = new ArrayList<>();
    final Set<String> nameKeys = new HashSet<>();
    int branchCount;
    Double nominalVoltage;
    Double r;
    Double x;
    Double bch;
    Double length;

    private LogicalLine(int index) {
        this.index = index;
    }

    /** The endpoint keys, sorted; the line's identity in terms of what it connects. */
    List<String> endpointKeys() {
        List<String> keys = new ArrayList<>();
        for (Endpoint e : endpoints) {
            keys.add(e.key());
        }
        keys.sort(null);
        return keys;
    }

    static List<LogicalLine> build(Network net) {
        Map<String, List<String>> membersAtNode = new HashMap<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (Equipment eq : net.equipment.values()) {
            if (isMember(net, eq)) {
                candidates.add(eq.iri);
                for (TerminalRec t : eq.terminals) {
                    if (interior(net, t.node())) {
                        membersAtNode.computeIfAbsent(t.node(), n -> new ArrayList<>()).add(eq.iri);
                    }
                }
            }
        }
        List<LogicalLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String start : candidates) {
            if (!seen.add(start)) {
                continue;
            }
            LogicalLine line = new LogicalLine(lines.size());
            Set<String> nodesSeen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String member = queue.poll();
                line.members.add(member);
                for (TerminalRec t : net.equipment.get(member).terminals) {
                    if (interior(net, t.node()) && nodesSeen.add(t.node())) {
                        for (String next : membersAtNode.getOrDefault(t.node(), List.of())) {
                            if (seen.add(next)) {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            line.interiorNodes.addAll(nodesSeen);
            line.describe(net, new HashSet<>(line.members), membersAtNode);
            lines.add(line);
        }
        return lines;
    }

    /** Series elements always; switches only when all their nodes are outside substations. */
    private static boolean isMember(Network net, Equipment eq) {
        if (Network.BRANCH.equals(eq.role)) {
            return !eq.terminals.isEmpty();
        }
        if (!Network.isSwitch(eq.role) || eq.terminals.size() < 2) {
            return false;
        }
        for (TerminalRec t : eq.terminals) {
            if (!interior(net, t.node())) {
                return false;
            }
        }
        return true;
    }

    private static boolean interior(Network net, String node) {
        NodeInfo n = net.node(node);
        return n != null && n.substation == null;
    }

    private void describe(Network net, Set<String> memberSet, Map<String, List<String>> membersAtNode) {
        for (String member : members) {
            for (TerminalRec t : net.equipment.get(member).terminals) {
                NodeInfo n = net.node(t.node());
                if (n != null && n.substation != null) {
                    endpoints.add(new Endpoint(n.iri, n.substation, member));
                }
            }
        }
        for (String nodeIri : interiorNodes) {
            boolean reachesSubstation = false;
            for (TerminalRec t : net.node(nodeIri).terminals) {
                if (memberSet.contains(t.equipment())) {
                    continue;
                }
                String sub = substationAround(net, net.equipment.get(t.equipment()));
                if (sub != null) {
                    endpoints.add(new Endpoint(nodeIri, sub, t.equipment()));
                    reachesSubstation = true;
                } else if (!attachments.contains(t.equipment())) {
                    attachments.add(t.equipment());
                }
            }
            List<String> here = membersAtNode.getOrDefault(nodeIri, List.of());
            if (!reachesSubstation && here.size() == 1) {
                endpoints.add(new Endpoint(nodeIri, OPEN + nodeIri, here.get(0)));
            }
        }
        List<ParamRec> params = new ArrayList<>();
        for (String member : members) {
            Equipment eq = net.equipment.get(member);
            if (Network.BRANCH.equals(eq.role)) {
                branchCount++;
                ParamRec p = net.data.parameters.get(member);
                params.add(p == null ? new ParamRec(null, null, null, null, null) : p);
                if (eq.nominalVoltage != null && (nominalVoltage == null || eq.nominalVoltage > nominalVoltage)) {
                    nominalVoltage = eq.nominalVoltage;
                }
            }
            addName(net.name(member));
            ContainmentRec c = net.data.containment.get(member);
            if (c != null && "Line".equals(c.containerType())) {
                addName(net.name(c.container()));
            }
        }
        if (nominalVoltage == null) {
            for (Endpoint e : endpoints) {
                NodeInfo n = net.node(e.node());
                if (n != null && n.nominalVoltage != null && (nominalVoltage == null || n.nominalVoltage > nominalVoltage)) {
                    nominalVoltage = n.nominalVoltage;
                }
            }
        }
        if (!params.isEmpty()) {
            r = sum(params, ParamRec::r);
            x = sum(params, ParamRec::x);
            bch = sum(params, ParamRec::bch);
            length = sum(params, ParamRec::length);
        }
    }

    private void addName(String name) {
        String key = Names.key(name);
        if (key != null) {
            nameKeys.add(key);
        }
    }

    /** The substation an element at an interior node belongs to: its own, or that of its other nodes. */
    private static String substationAround(Network net, Equipment eq) {
        if (eq.substation != null) {
            return eq.substation;
        }
        String found = null;
        for (TerminalRec t : eq.terminals) {
            NodeInfo n = net.node(t.node());
            if (n != null && n.substation != null) {
                if (found != null && !found.equals(n.substation)) {
                    return null;
                }
                found = n.substation;
            }
        }
        return found;
    }

    /** The sum over all elements, or null when any element lacks the value. */
    private static Double sum(List<ParamRec> params, Function<ParamRec, Double> getter) {
        double total = 0;
        for (ParamRec p : params) {
            Double v = getter.apply(p);
            if (v == null) {
                return null;
            }
            total += v;
        }
        return total;
    }
}
