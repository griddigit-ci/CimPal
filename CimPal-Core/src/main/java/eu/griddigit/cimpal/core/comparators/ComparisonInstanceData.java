/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * Adapted for Core (no JavaFX) from eu.griddigit.cimpal.main.core.ComparisonInstanceData.
 */
package eu.griddigit.cimpal.core.comparators;

import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

/**
 * Core (no-JavaFX) comparator for CIM instance-data (IGM/EQ/SSH) models.
 *
 * <p>The {@code options} LinkedList controls which profile types are skipped:
 * <ul>
 *   <li>options[0] == 1 → ignore SV (state-variable) classes
 *   <li>options[1] == 1 → ignore DL (diagram-layout) classes
 *   <li>options[4] == 1 → ignore TP (topology) classes
 * </ul>
 */
public class ComparisonInstanceData {

    /**
     * Compare two CIM instance-data models and return a result with all differences.
     *
     * @param result  an (initially empty) RDFCompareResult to collect differences into
     * @param modelA  first model
     * @param modelB  second model
     * @param options skip-list control (see class Javadoc)
     * @return populated result
     */
    public static RDFCompareResult compareInstanceData(
            RDFCompareResult result, Model modelA, Model modelB, LinkedList<Integer> options) {

        // Build a combined namespace-prefix map for value normalisation
        Map<String, String> prefMap = new HashMap<>(modelA.getNsPrefixMap());
        prefMap.putAll(modelB.getNsPrefixMap());

        LinkedList<String> skiplist = new LinkedList<>();
        if (options.get(0) == 1) { // SV
            skiplist.add("SvVoltage");
            skiplist.add("SvInjection");
            skiplist.add("SvStatus");
            skiplist.add("SvPowerFlow");
            skiplist.add("SvSwitch");
            skiplist.add("TopologicalIsland");
            skiplist.add("DCTopologicalIsland");
            skiplist.add("SvShuntCompensatorSections");
            skiplist.add("SvTapStep");
        }
        if (options.get(1) == 1) { // DL
            skiplist.add("DiagramObject");
            skiplist.add("DiagramObjectPoint");
            skiplist.add("DiagramObjectStyle");
            skiplist.add("VisibilityLayer");
            skiplist.add("DiagramStyle");
            skiplist.add("Diagram");
            skiplist.add("DiagramObjectGluePoint");
            skiplist.add("TextDiagramObject");
        }
        if (options.size() > 4 && options.get(4) == 1) { // TP
            skiplist.add("DCTopologicalNode");
            skiplist.add("TopologicalNode");
        }

        // Forward pass: A vs B
        result = compareModels(result, modelA, modelB, 0, skiplist, prefMap);
        // Reverse pass: B vs A — finds elements present in B but absent in A
        result = compareModels(result, modelB, modelA, 1, skiplist, prefMap);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private comparison helpers
    // (Adapted from eu.griddigit.cimpal.main.util.CompareFactory)
    // -----------------------------------------------------------------------

    private static RDFCompareResult compareModels(
            RDFCompareResult result, Model modelA, Model modelB,
            int reverse, LinkedList<String> skiplist, Map<String, String> prefMap) {

        for (ResIterator i = modelA.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();
            if (!resItem.isAnon()) {
                try {
                    String classType = resItem.getRequiredProperty(RDF.type)
                            .getObject().asResource().getLocalName();
                    if (!skiplist.contains(classType)) {
                        result = compareModelsDetail(result, modelA, modelB, reverse, resItem, prefMap);
                    }
                } catch (Exception e) {
                    // skip resources without rdf:type
                }
            }
        }
        return result;
    }

    private static RDFCompareResult compareModelsDetail(
            RDFCompareResult result, Model modelA, Model modelB,
            int reverse, Resource resItem, Map<String, String> prefMap) {

        String rdfType;
        try {
            rdfType = getRDFtype(modelA, resItem);
        } catch (Exception e) {
            rdfType = "";
        }

        if (modelB.contains(resItem.getRequiredProperty(RDF.type))) {
            // Class exists in both models — compare attributes / associations
            if (reverse == 0) {
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) {
                    Statement resItemStmt = j.next();
                    if (resItemStmt.getPredicate().equals(RDF.type)) continue;

                    if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                        if (resItemStmt.getObject().isAnon()) {
                            Map<String, String> bnResult = compareBlankNode(modelA, modelB, resItemStmt, reverse);
                            if (!bnResult.isEmpty()) {
                                result = addResult(result, resItem.getLocalName(),
                                        nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                        bnResult.get("modelA"), bnResult.get("modelB"), rdfType, prefMap);
                            }
                        } else {
                            if (!modelB.contains(resItemStmt)) {
                                if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {
                                    if (resItemStmt.getPredicate().getLocalName().equals("comment")) {
                                        String valA = modelA.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(), (RDFNode) null)
                                                .nextStatement().getObject().asLiteral().getString();
                                        String valB = modelB.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(), (RDFNode) null)
                                                .nextStatement().getObject().asLiteral().getString();
                                        if (!valA.equals(valB)) {
                                            result = addResult(result, resItem.getLocalName(),
                                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                                    resItemStmt.getObject().toString(),
                                                    modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(),
                                                    rdfType, prefMap);
                                        }
                                    } else if (resItemStmt.getObject().isLiteral()) {
                                        Literal lit = resItemStmt.getObject().asLiteral();
                                        String dtUri = lit.getDatatype().getURI();
                                        if (dtUri.equals(XSDDatatype.XSDdecimal.getURI())
                                                || dtUri.equals(XSDDatatype.XSDinteger.getURI())
                                                || dtUri.equals(XSDDatatype.XSDfloat.getURI())
                                                || dtUri.equals(XSDDatatype.XSDdateTime.getURI())
                                                || dtUri.equals(XSDDatatype.XSDdateTimeStamp.getURI())) {
                                            Object litValue = lit.getValue();
                                            if (!litValue.equals(modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate())
                                                    .getObject().asLiteral().getValue())) {
                                                result = addResult(result, resItem.getLocalName(),
                                                        nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                                        resItemStmt.getObject().toString(),
                                                        modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(),
                                                        rdfType, prefMap);
                                            }
                                        } else {
                                            result = addResult(result, resItem.getLocalName(),
                                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                                    resItemStmt.getObject().toString(),
                                                    modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(),
                                                    rdfType, prefMap);
                                        }
                                    } else {
                                        result = addResult(result, resItem.getLocalName(),
                                                nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                                resItemStmt.getObject().toString(),
                                                modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(),
                                                rdfType, prefMap);
                                    }
                                } else {
                                    // Attribute missing entirely in B
                                    result = addResult(result, resItem.getLocalName(),
                                            nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                            resItemStmt.getObject().toString(), "N/A", rdfType, prefMap);
                                }
                            }
                        }
                    } else { // OWL2.oneOf
                        if (modelB.contains(resItem, OWL2.oneOf)) {
                            List<RDFNode> listA = resItemStmt.getList().asJavaList();
                            List<RDFNode> listB = modelB.getRequiredProperty(resItem, OWL2.oneOf).getList().asJavaList();
                            if (compareRDFlist(listA, listB)) {
                                result = addResult(result, resItem.getLocalName(),
                                        nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                        listA.toString(), listB.toString(), rdfType, prefMap);
                            }
                        } else {
                            result = addResult(result, resItem.getLocalName(),
                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                    resItemStmt.getList().asJavaList().toString(), "N/A", rdfType, prefMap);
                        }
                    }
                }
            } else { // reverse == 1
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) {
                    Statement resItemStmt = j.next();
                    if (resItemStmt.getPredicate().equals(RDF.type)) continue;

                    if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                        if (resItemStmt.getObject().isAnon()) {
                            Map<String, String> bnResult = compareBlankNode(modelA, modelB, resItemStmt, reverse);
                            if (!bnResult.isEmpty()) {
                                result = addResult(result, resItem.getLocalName(),
                                        nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                        bnResult.get("modelA"), bnResult.get("modelB"), rdfType, prefMap);
                            }
                        } else {
                            if (!modelB.contains(resItemStmt)) {
                                if (!modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {
                                    result = addResult(result, resItem.getLocalName(),
                                            nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                            "N/A", resItemStmt.getObject().toString(), rdfType, prefMap);
                                } else {
                                    List<Statement> multiList = modelA.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(), (RDFNode) null).toList();
                                    if (multiList.size() > 1) {
                                        for (Statement st : multiList) {
                                            if (!modelB.contains(st)) {
                                                result = addResult(result, resItem.getLocalName(),
                                                        nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                                        "N/A", resItemStmt.getObject().toString(), rdfType, prefMap);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else { // OWL2.oneOf
                        if (!modelB.contains(resItem, OWL2.oneOf)) {
                            result = addResult(result, resItem.getLocalName(),
                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                    "N/A", resItemStmt.getList().asJavaList().toString(), rdfType, prefMap);
                        }
                    }
                }
            }
        } else {
            // Class not in modelB at all
            if (!resItem.getRequiredProperty(RDF.type).getObject().equals(OWL2.Thing)) {
                if (resItem.getNameSpace().equals("urn:uuid:")) {
                    String label = "diff/new md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
                    String predLabel = "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
                    if (reverse == 0) {
                        result = addResult(result, resItem.getLocalName(), predLabel, label, "N/A", rdfType, prefMap);
                    } else {
                        result = addResult(result, resItem.getLocalName(), predLabel, "N/A", label, rdfType, prefMap);
                    }
                } else {
                    String typeLocalName = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
                    String nsPrefix2 = modelA.getNsURIPrefix(resItem.getNameSpace());
                    String predLabel = nsPrefix2 + ":" + typeLocalName;
                    if (typeLocalName.equals("Property") || typeLocalName.equals("Class")) {
                        if (reverse == 0) {
                            result = addResult(result, resItem.getLocalName(), predLabel, "New class:", "N/A", rdfType, prefMap);
                        } else {
                            result = addResult(result, resItem.getLocalName(), predLabel, "N/A", "New class:", rdfType, prefMap);
                        }
                    } else {
                        String detail = "New property: " + nsPrefix2 + ":" + resItem.getLocalName()
                                + " in class: " + nsPrefix2 + ":" + typeLocalName;
                        if (reverse == 0) {
                            result = addResult(result, resItem.getLocalName(), predLabel, detail, "N/A", rdfType, prefMap);
                        } else {
                            result = addResult(result, resItem.getLocalName(), predLabel, "N/A", detail, rdfType, prefMap);
                        }
                    }
                }
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) {
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)
                            && !resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                        if (reverse == 0) {
                            result = addResult(result, resItem.getLocalName(),
                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                    resItemStmt.getObject().toString(), "N/A", rdfType, prefMap);
                        } else {
                            result = addResult(result, resItem.getLocalName(),
                                    nsPrefix(modelA, resItemStmt) + ":" + resItemStmt.getPredicate().getLocalName(),
                                    "N/A", resItemStmt.getObject().toString(), rdfType, prefMap);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static String nsPrefix(Model model, Statement stmt) {
        String ns = stmt.getPredicate().getNameSpace();
        String prefix = model.getNsURIPrefix(ns);
        return prefix != null ? prefix : ns;
    }

    private static RDFCompareResult addResult(RDFCompareResult result,
            String item, String property, String valueModelA, String valueModelB,
            String rdfType, Map<String, String> prefMap) {
        if (valueModelA == null || valueModelB == null) return result;
        if (valueModelA.isEmpty() || valueModelB.isEmpty()) return result;

        // Substitute long namespace URIs with short prefixes for readability
        for (Map.Entry<String, String> entry : prefMap.entrySet()) {
            String ns = entry.getValue();
            String prefix = entry.getKey();
            if (ns != null && !ns.isBlank()) {
                if (valueModelA.contains(ns)) valueModelA = valueModelA.replace(ns, prefix + ":");
                if (valueModelB.contains(ns)) valueModelB = valueModelB.replace(ns, prefix + ":");
            }
        }
        result.addEntry(new RDFCompareResultEntry(item, rdfType, property, valueModelA, valueModelB));
        return result;
    }

    private static Map<String, String> compareBlankNode(Model modelA, Model modelB, Statement stmt, int reverse) {
        Map<String, String> resultMap = new HashMap<>();
        Map<Boolean, List<RDFNode>> isBNlist = isBlankNodeAlist(stmt);

        if (isBNlist.containsKey(Boolean.TRUE)) {
            List<RDFNode> listA = isBNlist.get(Boolean.TRUE);
            if (reverse == 0) {
                if (modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).hasNext()) {
                    Map<Boolean, List<RDFNode>> isBNlistB = isBlankNodeAlist(
                            modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).next());
                    List<RDFNode> listB = isBNlistB.get(Boolean.TRUE);
                    if (compareRDFlist(listA, listB)) {
                        resultMap.put("modelA", listA != null ? listA.toString() : "list is null");
                        resultMap.put("modelB", listB != null ? listB.toString() : "list is null");
                    }
                } else {
                    resultMap.put("modelA", listA != null ? listA.toString() : "list is null");
                    resultMap.put("modelB", "N/A");
                }
            } else {
                if (!modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).hasNext()) {
                    resultMap.put("modelA", "N/A");
                    resultMap.put("modelB", listA != null ? listA.toString() : "list is null");
                }
            }
        }
        return resultMap;
    }

    private static Map<Boolean, List<RDFNode>> isBlankNodeAlist(Statement stmt) {
        Map<Boolean, List<RDFNode>> map = new HashMap<>();
        List<RDFNode> list = null;
        boolean isList;
        try {
            list = stmt.getList().asJavaList();
            isList = true;
        } catch (Exception e) {
            isList = false;
        }
        map.put(isList, list);
        return map;
    }

    /** Returns true if lists are different. */
    private static boolean compareRDFlist(List<RDFNode> list1, List<RDFNode> list2) {
        if (list1 == null || list2 == null) return true;
        if (list1.size() != list2.size()) return true;
        for (RDFNode item : list1) {
            if (!list2.contains(item)) return true;
        }
        return false;
    }

    /**
     * Returns the qualified name (prefix:localName) of the rdf:type of {@code resource}.
     */
    public static String getRDFtype(Model model, Resource resource) {
        Statement rdfType = model.getRequiredProperty(resource, RDF.type);
        Resource rdfTypeRes = rdfType.getObject().asResource();
        return model.getNsURIPrefix(rdfTypeRes.getNameSpace()) + ":" + rdfTypeRes.getLocalName();
    }
}
