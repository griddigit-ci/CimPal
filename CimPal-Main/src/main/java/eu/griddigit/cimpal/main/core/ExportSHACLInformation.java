/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.main.core;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.topbraid.shacl.vocabulary.SH;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;

import static eu.griddigit.cimpal.main.util.ExcelTools.exportMapToExcel;
import static eu.griddigit.cimpal.main.util.ExcelTools.saveExcelFile;


public class ExportSHACLInformation {

    public static void shaclInformationExport(boolean singleFile, List<File> file) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        String fileName;
        for (File f : file) {
            Model model = ModelFactory.createDefaultModel(); // model is the rdf file
            try {
                RDFDataMgr.read(model, new FileInputStream(f), Lang.TURTLE);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            fileName = FilenameUtils.getBaseName(f.getAbsolutePath());
            if (!singleFile) {
                workbook = new XSSFWorkbook();
            }

            List<String> propertyShapeID = new LinkedList<>(); // list for the property shape uri
            propertyShapeID.add("PropertyShape/NodeShape ID");
            List<String> propertyShapeDescription = new LinkedList<>(); // list for the description of the property shape
            propertyShapeDescription.add("Constraint Description");
            List<String> propertyShapeName = new LinkedList<>(); // list for the name of the property shape
            propertyShapeName.add("Constraint Name");
            List<String> propertyShapeGroup = new LinkedList<>(); // list for the property shape group
            propertyShapeGroup.add("Group");
            List<String> propertyShapeType = new LinkedList<>(); // list for the type of the property shape = sparql or not
            propertyShapeType.add("Type");
            List<String> propertyShapeSeverity = new LinkedList<>(); // list for the severity of the shape
            propertyShapeSeverity.add("Severity");
            List<String> propertyShapeIn = new LinkedList<>(); // list for sh:in
            List<String> propertyShapeMinCount = new LinkedList<>(); // list for minCount
            List<String> propertyShapeMaxCount = new LinkedList<>(); // list for maxCount
            List<String> propertyShapePath = new LinkedList<>(); // list for sh:path
            List<String> propertyShapeDatatype = new LinkedList<>(); // list for sh:datatype
            List<String> propertyShapenodekind = new LinkedList<>(); // list for sh:nodeKind
            List<String> propertyShapeAppliesTo = new LinkedList<>(); // list for to which classes this applies - target class of NodeShape
            List<String> sparqlConstraintID = new LinkedList<>(); // list id of the sparql constraint
            sparqlConstraintID.add("SPARQL constraint ID");
            List<String> constraintMessage = new LinkedList<>(); // list message of constraint
            constraintMessage.add("Message");
            List<String> sparqlConstraintSelect = new LinkedList<>(); // list select of sparql constraint
            List<String> orderList = new LinkedList<>(); // list of order
            Map<String, List<String>> shaclInfo = new HashMap<>();

            orderList.add("PropertyShape/NodeShape ID");
            orderList.add("Constraint Name");
            orderList.add("Group");
            orderList.add("Type");
            orderList.add("SPARQL constraint ID");
            orderList.add("Severity");
            orderList.add("Message");
            orderList.add("Constraint Description");


            //iterate on all PropertyShape
            for (StmtIterator i = model.listStatements(null, RDF.type, SH.PropertyShape); i.hasNext(); ) {
                Statement stmtPS = i.next();
                propertyShapeID.add(model.getNsURIPrefix(stmtPS.getSubject().getNameSpace()) + ":" + stmtPS.getSubject().getLocalName());
                String name = model.listStatements(stmtPS.getSubject(), SH.name, (RDFNode) null).next().getObject().toString();
                propertyShapeName.add(name);
                Resource group = model.listStatements(stmtPS.getSubject(), SH.group, (RDFNode) null).next().getObject().asResource();
                propertyShapeGroup.add(model.getNsURIPrefix(group.getNameSpace()) + ":" + group.getLocalName());
                String description = model.listStatements(stmtPS.getSubject(), SH.description, (RDFNode) null).next().getObject().toString();
                propertyShapeDescription.add(description);
                Resource severity = model.listStatements(stmtPS.getSubject(), SH.severity, (RDFNode) null).next().getObject().asResource();
                propertyShapeSeverity.add(model.getNsURIPrefix(severity.getNameSpace()) + ":" + severity.getLocalName());

                if (model.listStatements(stmtPS.getSubject(), SH.sparql, (RDFNode) null).hasNext()) {
                    Statement sparqlStmt = model.listStatements(stmtPS.getSubject(), SH.sparql, (RDFNode) null).next();
                    propertyShapeType.add("SPARQL");
                    sparqlConstraintID.add(model.getNsURIPrefix(sparqlStmt.getObject().asResource().getNameSpace()) + ":" + sparqlStmt.getObject().asResource().getLocalName());
                    String message = "N/A";
                    if (model.listStatements(sparqlStmt.getObject().asResource(), SH.message, (RDFNode) null).hasNext()){
                        message = model.listStatements(sparqlStmt.getObject().asResource(), SH.message, (RDFNode) null).next().getObject().toString();
                    }

                    constraintMessage.add(message);
                } else {
                    propertyShapeType.add("Regular SHACL");
                    sparqlConstraintID.add("N/A");
                    String message = model.listStatements(stmtPS.getSubject(), SH.message, (RDFNode) null).next().getObject().toString();
                    constraintMessage.add(message);
                }
            }

            //iterate on all NodeShape
            for (StmtIterator i = model.listStatements(null, RDF.type, SH.NodeShape); i.hasNext(); ) {
                Statement stmtPS = i.next();
                if (model.listStatements(stmtPS.getSubject(), SH.severity, (RDFNode) null).hasNext()) {
                    propertyShapeID.add(model.getNsURIPrefix(stmtPS.getSubject().getNameSpace()) + ":" + stmtPS.getSubject().getLocalName());
                    String name = model.listStatements(stmtPS.getSubject(), SH.name, (RDFNode) null).next().getObject().toString();
                    propertyShapeName.add(name);
                    Resource group = model.listStatements(stmtPS.getSubject(), SH.group, (RDFNode) null).next().getObject().asResource();
                    propertyShapeGroup.add(model.getNsURIPrefix(group.getNameSpace()) + ":" + group.getLocalName());
                    String description = model.listStatements(stmtPS.getSubject(), SH.description, (RDFNode) null).next().getObject().toString();
                    propertyShapeDescription.add(description);
                    Resource severity = model.listStatements(stmtPS.getSubject(), SH.severity, (RDFNode) null).next().getObject().asResource();
                    propertyShapeSeverity.add(model.getNsURIPrefix(severity.getNameSpace()) + ":" + severity.getLocalName());

                    if (model.listStatements(stmtPS.getSubject(), SH.sparql, (RDFNode) null).hasNext()) {
                        Statement sparqlStmt = model.listStatements(stmtPS.getSubject(), SH.sparql, (RDFNode) null).next();
                        propertyShapeType.add("SPARQL");
                        sparqlConstraintID.add(model.getNsURIPrefix(sparqlStmt.getObject().asResource().getNameSpace()) + ":" + sparqlStmt.getObject().asResource().getLocalName());
                        String message = "N/A";
                        if (model.listStatements(sparqlStmt.getObject().asResource(), SH.message, (RDFNode) null).hasNext()) {
                            message = model.listStatements(sparqlStmt.getObject().asResource(), SH.message, (RDFNode) null).next().getObject().toString();
                        }

                        constraintMessage.add(message);
                    } else {
                        propertyShapeType.add("Regular SHACL");
                        sparqlConstraintID.add("N/A");
                        String message = model.listStatements(stmtPS.getSubject(), SH.message, (RDFNode) null).next().getObject().toString();
                        constraintMessage.add(message);
                    }
                }
            }

            shaclInfo.put("PropertyShape/NodeShape ID", propertyShapeID);
            shaclInfo.put("Constraint Name", propertyShapeName);
            shaclInfo.put("Group", propertyShapeGroup);
            shaclInfo.put("Type", propertyShapeType);
            shaclInfo.put("SPARQL constraint ID", sparqlConstraintID);
            shaclInfo.put("Severity", propertyShapeSeverity);
            shaclInfo.put("Message", constraintMessage);
            shaclInfo.put("Constraint Description", propertyShapeDescription);

            // do the excel export
            if (singleFile) {
                if (file.size()>1) {
                    workbook = exportMapToExcel(fileName, shaclInfo, orderList, workbook);
                }else if (file.size()==1){
                    workbook = exportMapToExcel("SHACL Constraints info", shaclInfo,orderList,workbook);
                    saveExcelFile(workbook, "Save descriptions from SHACL", "SHACL_Const_Info_"+fileName);
                }
            }else{
                workbook = exportMapToExcel("SHACL Constraints info", shaclInfo,orderList,workbook);
                saveExcelFile(workbook, "Save descriptions from SHACL", "SHACL_Const_Info_"+fileName);
            }
        }
        if (singleFile) {
            if (file.size()>1) {
                saveExcelFile(workbook, "Save descriptions from SHACL", "SHACL_Const_Info_All");
            }
        }
    }

    public static void exportCompleteSHACLInformation(boolean singleFile, List<File> files) {
        XSSFWorkbook workbook = new XSSFWorkbook();

        for (File f : files) {
            Model model = ModelFactory.createDefaultModel();
            try {
                Lang lang = org.apache.jena.riot.RDFLanguages.filenameToLang(f.getName());
                RDFDataMgr.read(model, new FileInputStream(f), (lang != null ? lang : Lang.TURTLE));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            String fileName = FilenameUtils.getBaseName(f.getAbsolutePath());
            if (!singleFile) {
                workbook = new XSSFWorkbook();
            }

            // Collect subjects for each category
            List<Resource> nodeShapes = collectTypedSubjects(model, SH.NodeShape);
            List<Resource> propertyShapes = collectTypedSubjects(model, SH.PropertyShape);
            List<Resource> propertyGroups = collectPropertyGroups(model);
            List<Resource> sparqlConstraints = collectSparqlConstraints(model);

            // Export each category if present
            if (!nodeShapes.isEmpty()) {
                Map<String, List<String>> table = buildWidePredicateTable(model, nodeShapes, true);
                List<String> order = new LinkedList<>(table.keySet());
                String sheet = (singleFile || files.size() > 1) ? "NodeShapes" : "NodeShapes (all)";
                workbook = exportMapToExcel(sheet, table, order, workbook);
            }

            if (!propertyShapes.isEmpty()) {
                Map<String, List<String>> table = buildWidePredicateTable(model, propertyShapes, true);
                List<String> order = new LinkedList<>(table.keySet());
                String sheet = (singleFile || files.size() > 1) ? "PropertyShapes" : "PropertyShapes (all)";
                workbook = exportMapToExcel(sheet, table, order, workbook);
            }

            if (!sparqlConstraints.isEmpty()) {
                Map<String, List<String>> table = buildWidePredicateTable(model, sparqlConstraints, true);
                List<String> order = new LinkedList<>(table.keySet());
                String sheet = (singleFile || files.size() > 1) ? "SPARQLConstraints" : "SPARQLConstraints (all)";
                workbook = exportMapToExcel(sheet, table, order, workbook);
            }

            if (!propertyGroups.isEmpty()) {
                Map<String, List<String>> table = buildWidePredicateTable(model, propertyGroups, true);
                List<String> order = new LinkedList<>(table.keySet());
                String sheet = (singleFile || files.size() > 1) ? "PropertyGroups" : "PropertyGroups (all)";
                workbook = exportMapToExcel(sheet, table, order, workbook);
            }

            if (!singleFile) {
                saveExcelFile(workbook, "Save SHACL by type", "SHACL_ByType_" + fileName);
                workbook = new XSSFWorkbook();
            }
        }

        if (singleFile) {
            if (files.size() > 1) {
                saveExcelFile(workbook, "Save SHACL by type", "SHACL_ByType_All");
            } else if (files.size() == 1) {
                String base = FilenameUtils.getBaseName(files.get(0).getAbsolutePath());
                saveExcelFile(workbook, "Save SHACL by type", "SHACL_ByType_" + base);
            }
        }
    }

    // Build a wide predicate table for given subjects; `sh:name` goes first if present.
    private static Map<String, List<String>> buildWidePredicateTable(Model model, List<Resource> subjects, boolean nameFirst) {
        // Union of predicates over the provided subjects (include rdf:type)
        java.util.TreeSet<Property> predicates = new java.util.TreeSet<>(java.util.Comparator.comparing(p -> toQName(model, p)));
        boolean hasShName = false;
        for (Resource s : subjects) {
            StmtIterator it = model.listStatements(s, null, (RDFNode) null);
            while (it.hasNext()) {
                Statement st = it.next();
                if (!st.getPredicate().equals(RDF.type)) {
                    predicates.add(st.getPredicate());
                    if (st.getPredicate().equals(SH.name)) {
                        hasShName = true;
                    }
                }
            }
        }

        // Order predicates, forcing sh:name first if present
        List<Property> orderedPredicates = new ArrayList<>();
        if (nameFirst && hasShName) {
            orderedPredicates.add(SH.name);
        }
        for (Property p : predicates) {
            if (nameFirst && hasShName && p.equals(SH.name)) continue;
            orderedPredicates.add(p);
        }

        // Prepare table with headers in the decided order
        LinkedHashMap<String, List<String>> table = new LinkedHashMap<>();
        for (Property p : orderedPredicates) {
            addHeader(table, toQName(model, p));
        }

        // Sort subjects by `sh:name` (if present), else by QName
        subjects.sort((a, b) -> {
            String an = firstLiteralOrEmpty(model, a, SH.name);
            String bn = firstLiteralOrEmpty(model, b, SH.name);
            if (!an.isEmpty() || !bn.isEmpty()) return an.compareTo(bn);
            return toQName(model, a).compareTo(toQName(model, b));
        });

        // Fill rows
        for (Resource s : subjects) {
            for (Property p : orderedPredicates) {
                List<String> values = new ArrayList<>();
                model.listStatements(s, p, (RDFNode) null).forEachRemaining(st -> values.add(nodeToString(model, st.getObject())));
                appendCell(table, toQName(model, p), join(values));
            }
        }

        return table;
    }

    private static String firstLiteralOrEmpty(Model model, Resource s, Property p) {
        StmtIterator it = model.listStatements(s, p, (RDFNode) null);
        while (it.hasNext()) {
            RDFNode obj = it.next().getObject();
            if (obj.isLiteral()) return obj.asLiteral().getString();
            return nodeToString(model, obj);
        }
        return "";
    }

    private static List<Resource> collectTypedSubjects(Model model, Resource type) {
        LinkedHashSet<Resource> subjects = new LinkedHashSet<>();
        model.listStatements(null, RDF.type, type).forEachRemaining(st -> subjects.add(st.getSubject()));
        // Stable order
        List<Resource> list = new ArrayList<>(subjects);
        list.sort((a, b) -> toQName(model, a).compareTo(toQName(model, b)));
        return list;
    }

    private static List<Resource> collectPropertyGroups(Model model) {
        LinkedHashSet<Resource> groups = new LinkedHashSet<>();
        // Typed sh:PropertyGroup
        model.listStatements(null, RDF.type, SH.PropertyGroup).forEachRemaining(st -> groups.add(st.getSubject()));
        // Objects of sh:group
        model.listStatements(null, SH.group, (RDFNode) null).forEachRemaining(st -> {
            if (st.getObject().isResource()) groups.add(st.getObject().asResource());
        });
        List<Resource> list = new ArrayList<>(groups);
        list.sort((a, b) -> toQName(model, a).compareTo(toQName(model, b)));
        return list;
    }

    private static List<Resource> collectSparqlConstraints(Model model) {
        LinkedHashSet<Resource> constraints = new LinkedHashSet<>();
        // Objects of sh:sparql
        model.listStatements(null, SH.sparql, (RDFNode) null).forEachRemaining(st -> {
            if (st.getObject().isResource()) constraints.add(st.getObject().asResource());
        });
        // Also include typed sh:SPARQLConstraint if present
        Resource SPARQL_CONSTRAINT = ResourceFactory.createResource("http://www.w3.org/ns/shacl#SPARQLConstraint");
        model.listStatements(null, RDF.type, SPARQL_CONSTRAINT).forEachRemaining(st -> constraints.add(st.getSubject()));
        List<Resource> list = new ArrayList<>(constraints);
        list.sort((a, b) -> toQName(model, a).compareTo(toQName(model, b)));
        return list;
    }


    private static void addHeader(Map<String, List<String>> table, String header) {
        List<String> col = new LinkedList<>();
        col.add(header);
        table.put(header, col);
    }

    private static void appendCell(Map<String, List<String>> table, String header, String value) {
        table.get(header).add(value == null ? "" : value);
    }

    private static String join(List<String> vals) {
        if (vals == null || vals.isEmpty()) return "";
        return String.join(" | ", vals);
    }

    private static String nodeToString(Model model, RDFNode node) {
        if (node == null) return "";
        if (node.isLiteral()) {
            Literal l = node.asLiteral();
            String s = l.getLexicalForm();
            String lang = l.getLanguage();
            if (lang != null && !lang.isEmpty()) s += "@" + lang;
            return s;
        }
        if (node.isResource()) {
            Resource r = node.asResource();
            if (r.canAs(RDFList.class)) {
                try {
                    RDFList list = r.as(RDFList.class);
                    List<String> items = new ArrayList<>();
                    for (java.util.Iterator<RDFNode> it = list.iterator(); it.hasNext();) {
                        items.add(nodeToString(model, it.next()));
                    }
                    return "[" + String.join("; ", items) + "]";
                } catch (Exception ignore) {}
            }
            return toQName(model, r);
        }
        return node.toString();
    }

    private static String safeQName(Model model, String uri) {
        if (uri == null) return "";
        Resource dt = ResourceFactory.createResource(uri);
        return toQName(model, dt);
    }

    private static String toQName(Model model, Resource r) {
        if (r == null) return "";
        if (r.isAnon()) return "_:" + r.getId().getLabelString();
        String ns = r.getNameSpace();
        String prefix = (ns == null) ? null : model.getNsURIPrefix(ns);
        return (prefix != null) ? (prefix + ":" + r.getLocalName()) : r.getURI();
    }

    private static String toQName(Model model, Property p) {
        if (p == null) return "";
        String ns = p.getNameSpace();
        String prefix = (ns == null) ? null : model.getNsURIPrefix(ns);
        return (prefix != null) ? (prefix + ":" + p.getLocalName()) : p.getURI();
    }
}
