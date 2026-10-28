/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.RDFNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One side's model as the query library returns it: plain tables keyed by object IRI, with no
 * Jena objects, so the model can be dropped once they are filled. All CIM knowledge stays in the
 * queries; this class only knows their column names.
 */
final class GridData {

    record ObjectInfo(String iri, String type, String id, String name, String shortName, String description) {
    }

    record TerminalRec(String terminal, String equipment, String equipmentType, String role,
                       int sequenceNumber, String node) {
    }

    record ContainmentRec(String container, String containerType, String voltageLevel, String substation) {
    }

    record VoltageLevelRec(String substation, Double nominalVoltage) {
    }

    record ParamRec(Double nominalVoltage, Double r, Double x, Double bch, Double length) {
    }

    record EndRec(String end, String transformer, Integer endNumber, Double ratedU, Double ratedS,
                  String terminal) {
    }

    record DependentRec(String dependent, String owner, String association, String key) {
    }

    record ReferenceRec(String referrer, String property, String target) {
    }

    final Map<String, ObjectInfo> objects = new HashMap<>();
    final List<TerminalRec> terminals = new ArrayList<>();
    final Map<String, ContainmentRec> containment = new HashMap<>();
    final Map<String, VoltageLevelRec> voltageLevels = new HashMap<>();
    final Map<String, ParamRec> parameters = new HashMap<>();
    final List<EndRec> ends = new ArrayList<>();
    final List<DependentRec> dependents = new ArrayList<>();
    final List<ReferenceRec> references = new ArrayList<>();
    /** Objects defined by the common files (boundary set, common data); never reported. */
    final Set<String> shared;

    private GridData(Set<String> shared) {
        this.shared = shared;
    }

    static GridData extract(Graph graph, QueryLibrary queries, Set<String> shared) {
        GridData data = new GridData(shared);
        queries.select(QueryLibrary.OBJECTS, graph, row -> {
            String iri = iri(row, "object");
            if (iri != null && !data.objects.containsKey(iri)) {
                data.objects.put(iri, new ObjectInfo(iri, str(row, "type"), localId(iri),
                        str(row, "name"), str(row, "shortName"), str(row, "description")));
            }
        });
        queries.select(QueryLibrary.TERMINALS, graph, row -> {
            String terminal = iri(row, "terminal");
            String equipment = iri(row, "equipment");
            if (terminal != null && equipment != null) {
                Double seq = num(row, "sequenceNumber");
                data.terminals.add(new TerminalRec(terminal, equipment, str(row, "equipmentType"),
                        str(row, "role"), seq == null ? 0 : seq.intValue(), iri(row, "node")));
            }
        });
        queries.select(QueryLibrary.CONTAINMENT, graph, row -> {
            String object = iri(row, "object");
            if (object != null) {
                data.containment.merge(object, new ContainmentRec(iri(row, "container"),
                        str(row, "containerType"), iri(row, "voltageLevel"), iri(row, "substation")),
                        GridData::richer);
            }
        });
        queries.select(QueryLibrary.VOLTAGE_LEVELS, graph, row -> {
            String vl = iri(row, "voltageLevel");
            if (vl != null) {
                data.voltageLevels.putIfAbsent(vl, new VoltageLevelRec(iri(row, "substation"),
                        num(row, "nominalVoltage")));
            }
        });
        queries.select(QueryLibrary.PARAMETERS, graph, row -> {
            String eq = iri(row, "equipment");
            if (eq != null) {
                data.parameters.putIfAbsent(eq, new ParamRec(num(row, "nominalVoltage"), num(row, "r"),
                        num(row, "x"), num(row, "bch"), num(row, "length")));
            }
        });
        queries.select(QueryLibrary.TRANSFORMER_ENDS, graph, row -> {
            String end = iri(row, "end");
            String transformer = iri(row, "transformer");
            if (end != null && transformer != null) {
                Double endNumber = num(row, "endNumber");
                data.ends.add(new EndRec(end, transformer, endNumber == null ? null : endNumber.intValue(),
                        num(row, "ratedU"), num(row, "ratedS"), iri(row, "terminal")));
            }
        });
        queries.select(QueryLibrary.DEPENDENTS, graph, row -> {
            String dependent = iri(row, "dependent");
            String owner = iri(row, "owner");
            if (dependent != null && owner != null) {
                data.dependents.add(new DependentRec(dependent, owner, str(row, "association"), str(row, "key")));
            }
        });
        queries.select(QueryLibrary.REFERENCES, graph, row -> {
            String referrer = iri(row, "referrer");
            String target = iri(row, "target");
            if (referrer != null && target != null) {
                data.references.add(new ReferenceRec(referrer, str(row, "property"), target));
            }
        });
        return data;
    }

    /** Keeps the containment row that resolves further (a substation beats none). */
    private static ContainmentRec richer(ContainmentRec a, ContainmentRec b) {
        int sa = (a.substation() != null ? 2 : 0) + (a.voltageLevel() != null ? 1 : 0);
        int sb = (b.substation() != null ? 2 : 0) + (b.voltageLevel() != null ? 1 : 0);
        return sb > sa ? b : a;
    }

    /**
     * The ID an object is known by: the local part of its IRI without the CGMES leading underscore,
     * e.g. {@code ...#_1234} and {@code urn:uuid:1234} both give {@code 1234}.
     */
    static String localId(String iri) {
        String id = iri;
        if (id.startsWith("urn:uuid:")) {
            id = id.substring("urn:uuid:".length());
        } else {
            int cut = Math.max(id.lastIndexOf('#'), id.lastIndexOf('/'));
            if (cut >= 0) {
                id = id.substring(cut + 1);
            }
        }
        return id.startsWith("_") ? id.substring(1) : id;
    }

    private static String iri(QuerySolution row, String var) {
        RDFNode node = row.get(var);
        return node != null && node.isURIResource() ? node.asResource().getURI() : null;
    }

    private static String str(QuerySolution row, String var) {
        RDFNode node = row.get(var);
        if (node == null) {
            return null;
        }
        String s = node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    private static Double num(QuerySolution row, String var) {
        RDFNode node = row.get(var);
        if (node == null || !node.isLiteral()) {
            return null;
        }
        Literal literal = node.asLiteral();
        try {
            double d = Double.parseDouble(literal.getLexicalForm().strip());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
