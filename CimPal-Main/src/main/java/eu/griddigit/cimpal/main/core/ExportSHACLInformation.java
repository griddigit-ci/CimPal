/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.main.core;

import javafx.stage.FileChooser;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.topbraid.shacl.vocabulary.SH;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static void exportSHACLForEAImport(boolean singleFile, List<File> files) {

        if (files == null || files.isEmpty()) {
            return;
        }

        String firstFileName = FilenameUtils.getBaseName(files.get(0).getAbsolutePath());
        File chosenOutputFile = chooseEaImportCsvOutputFile("SHACL_EA_Import_" + firstFileName + ".csv");

        if (chosenOutputFile == null) {
            return;
        }

        File outputFolder = chosenOutputFile.getParentFile();
        if (outputFolder == null) {
            return;
        }

        for (File f : files) {
            Model model = ModelFactory.createDefaultModel();
            String rawTtl = "";

            try {
                rawTtl = new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8
                );

                Lang lang = org.apache.jena.riot.RDFLanguages.filenameToLang(f.getName());
                RDFDataMgr.read(model, new FileInputStream(f), (lang != null ? lang : Lang.TURTLE));
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }

            String fileName = FilenameUtils.getBaseName(f.getAbsolutePath());

            TtlBlockIndex ttlBlocks = buildTtlBlockIndex(rawTtl);

            LinkedHashMap<String, List<String>> table = new LinkedHashMap<>();
            List<String> order = new LinkedList<>();

            addEaImportColumn(table, order, "SourceFile");
            addEaImportColumn(table, order, "SourceGroup");
            addEaImportColumn(table, order, "TargetPackage");
            addEaImportColumn(table, order, "PropertyShape/NodeShape ID");
            addEaImportColumn(table, order, "Payload");
            addEaImportColumn(table, order, "Constraint Name");

            addEaImportColumn(table, order, "SHACL Shape Kind");
            addEaImportColumn(table, order, "SHACL Constraint Type");
            addEaImportColumn(table, order, "EA Target Type");

            addEaImportColumn(table, order, "Target Class");
            addEaImportColumn(table, order, "Group");
            addEaImportColumn(table, order, "SPARQL constraint ID");
            addEaImportColumn(table, order, "Severity");
            addEaImportColumn(table, order, "Message");
            addEaImportColumn(table, order, "Constraint Description");
            addEaImportColumn(table, order, "Path");

            /*
             * First data row: TTL file header/support metadata.
             *
             * Important:
             * This must contain not only @prefix/@base, but also every support block
             * that is not exported as a normal NodeShape/PropertyShape row:
             * - owl:Ontology blocks
             * - sh:PropertyGroup blocks
             * - prefix declaration support blocks
             * - other non-shape top-level TTL blocks
             */
            appendEaImportRow(
                    table,
                    f.getName(),
                    fileName,
                    "",
                    "__HEADER__",
                    buildHeaderPayloadForEaImport(ttlBlocks),
                    "SHACL Header",
                    "Header",
                    "Header",
                    "Header",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "Prefixes/base/support blocks for round-trip",
                    ""
            );

            List<Resource> nodeShapes = collectTypedSubjects(model, SH.NodeShape);
            List<Resource> propertyShapes = collectTypedSubjects(model, SH.PropertyShape);

            Map<Resource, List<Resource>> singlePropertyContainersByProperty =
                    collectSinglePropertyContainersByProperty(model, nodeShapes);

            for (Resource shape : nodeShapes) {
                if (isSinglePropertyContainerNodeShape(model, shape)) {
                    continue;
                }

                appendShapeForEAImport(
                        model,
                        table,
                        f,
                        fileName,
                        ttlBlocks,
                        shape,
                        "NodeShape",
                        singlePropertyContainersByProperty
                );
            }

            for (Resource shape : propertyShapes) {
                appendShapeForEAImport(
                        model,
                        table,
                        f,
                        fileName,
                        ttlBlocks,
                        shape,
                        "PropertyShape",
                        singlePropertyContainersByProperty
                );
            }

            padTableColumns(table);

            File outFile = new File(outputFolder, "SHACL_EA_Import_" + fileName + ".csv");
            saveEaImportTableToCsv(table, order, outFile);
        }
    }

    private static void addEaImportColumn(Map<String, List<String>> table, List<String> order, String header) {
        List<String> col = new LinkedList<>();
        col.add(header);
        table.put(header, col);
        order.add(header);
    }

    private static void appendEaImportRow(
            Map<String, List<String>> table,
            String sourceFile,
            String sourceGroup,
            String targetPackage,
            String id,
            String payload,
            String constraintName,
            String shaclShapeKind,
            String shaclConstraintType,
            String eaTargetType,
            String targetClass,
            String group,
            String sparqlConstraintID,
            String severity,
            String message,
            String description,
            String path
    ) {
        table.get("SourceFile").add(sourceFile == null ? "" : sourceFile);
        table.get("SourceGroup").add(sourceGroup == null ? "" : sourceGroup);
        table.get("TargetPackage").add(targetPackage == null ? "" : targetPackage);
        table.get("PropertyShape/NodeShape ID").add(id == null ? "" : id);
        table.get("Payload").add(payload == null ? "" : payload);
        table.get("Constraint Name").add(constraintName == null ? "" : constraintName);

        table.get("SHACL Shape Kind").add(shaclShapeKind == null ? "" : shaclShapeKind);
        table.get("SHACL Constraint Type").add(shaclConstraintType == null ? "" : shaclConstraintType);
        table.get("EA Target Type").add(eaTargetType == null ? "" : eaTargetType);

        table.get("Target Class").add(targetClass == null ? "" : targetClass);
        table.get("Group").add(group == null ? "" : group);
        table.get("SPARQL constraint ID").add(sparqlConstraintID == null ? "" : sparqlConstraintID);
        table.get("Severity").add(severity == null ? "" : severity);
        table.get("Message").add(message == null ? "" : message);
        table.get("Constraint Description").add(description == null ? "" : description);
        table.get("Path").add(path == null ? "" : path);
    }

    private static void appendShapeForEAImport(
            Model model,
            Map<String, List<String>> table,
            File sourceFile,
            String sourceGroup,
            TtlBlockIndex ttlBlocks,
            Resource shape,
            String shapeKind,
            Map<Resource, List<Resource>> singlePropertyContainersByProperty
    ) {
        String id = toQName(model, shape);

        String name = firstNodeString(model, shape, SH.name);
        if (name.isEmpty()) {
            name = id;
        }

        String group = firstNodeString(model, shape, SH.group);
        String severity = firstNodeString(model, shape, SH.severity);
        String description = firstNodeString(model, shape, SH.description);

        List<String> sparqlIDs = allNodeStrings(model, shape, SH.sparql);
        String sparqlConstraintID = join(sparqlIDs);

        String shaclConstraintType = sparqlIDs.isEmpty() ? "Regular SHACL" : "SPARQL";

        String message = firstNodeString(model, shape, SH.message);
        if (!sparqlIDs.isEmpty()) {
            List<String> sparqlMessages = new ArrayList<>();

            StmtIterator spIt = model.listStatements(shape, SH.sparql, (RDFNode) null);
            while (spIt.hasNext()) {
                RDFNode spNode = spIt.next().getObject();

                if (spNode.isResource()) {
                    String spMsg = firstNodeString(model, spNode.asResource(), SH.message);
                    if (!spMsg.isEmpty()) {
                        sparqlMessages.add(spMsg);
                    }
                }
            }

            if (!sparqlMessages.isEmpty()) {
                message = join(sparqlMessages);
            }
        }

        String path = "";
        String targetClass = "";
        String eaTargetType = "ManualReview";

        if ("PropertyShape".equals(shapeKind)) {
            path = firstNodeString(model, shape, SH.path);

            List<String> targetClasses = new ArrayList<>();

            StmtIterator ownerIt = model.listStatements(null, SH.property, shape);
            while (ownerIt.hasNext()) {
                Resource nodeShape = ownerIt.next().getSubject();

                targetClasses.addAll(allNodeStrings(model, nodeShape, SH.targetClass));
                targetClasses.addAll(allNodeStrings(model, nodeShape, SH.targetSubjectsOf));
                targetClasses.addAll(allNodeStrings(model, nodeShape, SH.targetObjectsOf));
                targetClasses.addAll(allNodeStrings(model, nodeShape, SH.targetNode));
            }

            targetClass = join(uniqueStrings(targetClasses));

            eaTargetType = inferEaTargetTypeForPropertyShape(id, path);

        } else if ("NodeShape".equals(shapeKind)) {
            List<String> nodeTargets = new ArrayList<>();
            nodeTargets.addAll(allNodeStrings(model, shape, SH.targetClass));
            nodeTargets.addAll(allNodeStrings(model, shape, SH.targetSubjectsOf));
            nodeTargets.addAll(allNodeStrings(model, shape, SH.targetObjectsOf));
            nodeTargets.addAll(allNodeStrings(model, shape, SH.targetNode));

            targetClass = join(uniqueStrings(nodeTargets));

            if (isContainerOnlyNodeShape(model, shape)) {
                eaTargetType = "Container";
            } else {
                eaTargetType = "Class";
            }
        }

        String payload;

        if ("PropertyShape".equals(shapeKind)) {
            List<Resource> ownerContainers = singlePropertyContainersByProperty.get(shape);
            payload = buildShapePayloadFromTtlBlocks(model, ttlBlocks, shape, ownerContainers);
        } else {
            payload = buildShapePayloadFromTtlBlocks(model, ttlBlocks, shape, null);
        }

        appendEaImportRow(
                table,
                sourceFile.getName(),
                sourceGroup,
                "",
                id,
                payload,
                name,
                shapeKind,
                shaclConstraintType,
                eaTargetType,
                targetClass,
                group,
                sparqlConstraintID,
                severity,
                message,
                description,
                path
        );
    }

    private static Map<Resource, List<Resource>> collectSinglePropertyContainersByProperty(
            Model model,
            List<Resource> nodeShapes
    ) {
        Map<Resource, List<Resource>> map = new HashMap<>();

        for (Resource nodeShape : nodeShapes) {
            if (!isSinglePropertyContainerNodeShape(model, nodeShape)) {
                continue;
            }

            Resource propertyShape = getSinglePropertyShape(model, nodeShape);
            if (propertyShape == null) {
                continue;
            }

            map.computeIfAbsent(propertyShape, k -> new ArrayList<>()).add(nodeShape);
        }

        return map;
    }

    private static boolean isSinglePropertyContainerNodeShape(Model model, Resource nodeShape) {
        /*
         * If a NodeShape references exactly one PropertyShape via sh:property,
         * merge the NodeShape TTL block into the PropertyShape row.
         */
        int count = 0;

        StmtIterator it = model.listStatements(nodeShape, SH.property, (RDFNode) null);
        while (it.hasNext()) {
            it.next();
            count++;

            if (count > 1) {
                return false;
            }
        }

        return count == 1;
    }

    private static Resource getSinglePropertyShape(Model model, Resource nodeShape) {
        StmtIterator it = model.listStatements(nodeShape, SH.property, (RDFNode) null);

        if (!it.hasNext()) {
            return null;
        }

        RDFNode obj = it.next().getObject();

        if (it.hasNext()) {
            return null;
        }

        if (!obj.isResource()) {
            return null;
        }

        return obj.asResource();
    }

    private static String inferEaTargetTypeForPropertyShape(String id, String path) {
        /*
         * Helper/profile-level shapes are package-level rules.
         * These are not UML classes, attributes, or connectors.
         */
        if (isProfileLevelHelperShape(id)) {
            return "Package";
        }

        /*
         * rdf:type usually means the focus class itself is checked.
         * It should not automatically become Package.
         */
        if (path != null && path.trim().replaceAll("\\s+", "").equalsIgnoreCase("rdf:type")) {
            return "Class";
        }

        String tail = id;
        int colon = tail.indexOf(":");
        if (colon >= 0) {
            tail = tail.substring(colon + 1);
        }

        int dash = tail.indexOf("-");
        String head = dash >= 0 ? tail.substring(0, dash) : tail;

        int dot = head.indexOf(".");
        if (dot < 0) {
            return "Class";
        }

        String member = head.substring(dot + 1);

        if (!member.isEmpty() && Character.isUpperCase(member.charAt(0))) {
            return "AssociationCandidate";
        }

        return "Attribute";
    }

    private static boolean isProfileLevelHelperShape(String id) {
        String root = rootTokenFromShapeId(id);

        return "AllowedClasses".equals(root)
                || "ClassCount".equals(root);
    }

    private static String rootTokenFromShapeId(String id) {
        String tail = tailAfterFirstColon(id);

        int dash = tail.indexOf("-");
        String head = dash >= 0 ? tail.substring(0, dash) : tail;

        int dot = head.indexOf(".");
        if (dot >= 0) {
            head = head.substring(0, dot);
        }

        return head.trim();
    }

    private static String tailAfterFirstColon(String value) {
        if (value == null) {
            return "";
        }

        int idx = value.indexOf(":");
        return idx >= 0 ? value.substring(idx + 1).trim() : value.trim();
    }

    private static boolean isContainerOnlyNodeShape(Model model, Resource nodeShape) {
        /*
         * Container-only NodeShape:
         * - links to property shapes via sh:property
         * - has no direct validation content itself
         */

        if (model.contains(nodeShape, SH.sparql, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.message, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.severity, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.description, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.path, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.datatype, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.nodeKind, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.minCount, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.maxCount, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.in, (RDFNode) null)) return false;
        if (model.contains(nodeShape, SH.pattern, (RDFNode) null)) return false;

        return model.contains(nodeShape, SH.property, (RDFNode) null);
    }

    private static String firstNodeString(Model model, Resource subject, Property predicate) {
        StmtIterator it = model.listStatements(subject, predicate, (RDFNode) null);
        if (!it.hasNext()) {
            return "";
        }

        return nodeToString(model, it.next().getObject());
    }

    private static List<String> allNodeStrings(Model model, Resource subject, Property predicate) {
        List<String> out = new ArrayList<>();

        StmtIterator it = model.listStatements(subject, predicate, (RDFNode) null);
        while (it.hasNext()) {
            out.add(nodeToString(model, it.next().getObject()));
        }

        return out;
    }

    private static List<String> uniqueStrings(List<String> vals) {
        LinkedHashSet<String> set = new LinkedHashSet<>();

        for (String v : vals) {
            if (v != null && !v.isEmpty()) {
                set.add(v);
            }
        }

        return new ArrayList<>(set);
    }

    private static void padTableColumns(Map<String, List<String>> table) {
        int max = 0;

        for (List<String> col : table.values()) {
            if (col.size() > max) {
                max = col.size();
            }
        }

        for (List<String> col : table.values()) {
            while (col.size() < max) {
                col.add("");
            }
        }
    }

    private static class TtlBlockIndex {
        Map<String, String> blocksBySubject = new LinkedHashMap<>();
        String headerText = "";
        String supportText = "";
    }

    private static TtlBlockIndex buildTtlBlockIndex(String rawTtl) {
        TtlBlockIndex index = new TtlBlockIndex();

        String normalized = rawTtl
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        List<String> blocks = splitTtlIntoTopLevelBlocks(normalized);

        StringBuilder header = new StringBuilder();
        StringBuilder support = new StringBuilder();

        for (String block : blocks) {
            String trimmed = block.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (isTurtleDirectiveOrComment(trimmed)) {
                header.append(trimmed).append("\n\n");
                continue;
            }

            String subject = extractTtlSubjectQName(trimmed);

            if (subject != null && !subject.isEmpty()) {
                index.blocksBySubject.put(subject, trimmed);

                /*
                 * Store all non-shape top-level blocks in the header/support payload.
                 * Shape blocks are exported as their own rows.
                 */
                if (!isShapePayloadBlock(trimmed)) {
                    support.append(trimmed).append("\n\n");
                }
            }
        }

        index.headerText = header.toString().trim();
        index.supportText = support.toString().trim();

        return index;
    }

    private static String buildHeaderPayloadForEaImport(TtlBlockIndex ttlBlocks) {
        List<String> parts = new ArrayList<>();

        if (ttlBlocks.headerText != null && !ttlBlocks.headerText.trim().isEmpty()) {
            parts.add(ttlBlocks.headerText.trim());
        }

        if (ttlBlocks.supportText != null && !ttlBlocks.supportText.trim().isEmpty()) {
            parts.add(ttlBlocks.supportText.trim());
        }

        return String.join(System.lineSeparator() + System.lineSeparator(), parts);
    }

    private static List<String> splitTtlIntoTopLevelBlocks(String ttl) {
        List<String> blocks = new ArrayList<>();

        String[] lines = ttl.split("\n", -1);
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (current.length() > 0) {
                    current.append("\n");
                }
                continue;
            }

            if (current.length() == 0 && isTurtleDirectiveOrComment(trimmed)) {
                blocks.add(line);
                continue;
            }

            if (current.length() > 0) {
                current.append("\n");
            }

            current.append(line);

            if (isTerminatedTurtleBlock(current.toString())) {
                blocks.add(current.toString());
                current.setLength(0);
            }
        }

        if (current.length() > 0) {
            blocks.add(current.toString());
        }

        return blocks;
    }

    private static boolean isTurtleDirectiveOrComment(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);

        return lower.startsWith("@prefix")
                || lower.startsWith("@base")
                || lower.startsWith("prefix ")
                || lower.startsWith("base ")
                || lower.startsWith("#");
    }

    private static boolean isShapePayloadBlock(String block) {
        String b = block;

        return b.contains("sh:NodeShape")
                || b.contains("sh:PropertyShape")
                || b.contains("sh:SPARQLConstraint")
                || b.contains("http://www.w3.org/ns/shacl#NodeShape")
                || b.contains("http://www.w3.org/ns/shacl#PropertyShape")
                || b.contains("http://www.w3.org/ns/shacl#SPARQLConstraint");
    }

    private static String extractTtlSubjectQName(String block) {
        if (block == null) {
            return "";
        }

        String s = block.trim();
        if (s.isEmpty()) {
            return "";
        }

        int ws = -1;

        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                ws = i;
                break;
            }
        }

        if (ws < 0) {
            return "";
        }

        return s.substring(0, ws).trim();
    }

    private static boolean isTerminatedTurtleBlock(String text) {
        boolean inString = false;
        boolean inTripleString = false;
        boolean escaped = false;

        char lastOutsideString = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inTripleString) {
                if (c == '"' && i + 2 < text.length()
                        && text.charAt(i + 1) == '"'
                        && text.charAt(i + 2) == '"') {
                    inTripleString = false;
                    i += 2;
                }
                continue;
            }

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = false;
                }

                continue;
            }

            if (c == '"' && i + 2 < text.length()
                    && text.charAt(i + 1) == '"'
                    && text.charAt(i + 2) == '"') {
                inTripleString = true;
                i += 2;
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (!Character.isWhitespace(c)) {
                lastOutsideString = c;
            }
        }

        return !inString && !inTripleString && lastOutsideString == '.';
    }

    private static String buildShapePayloadFromTtlBlocks(
            Model model,
            TtlBlockIndex ttlBlocks,
            Resource shape,
            List<Resource> ownerContainers
    ) {
        List<String> parts = new ArrayList<>();

        if (ownerContainers != null) {
            for (Resource owner : ownerContainers) {
                String ownerId = toQName(model, owner);
                String ownerBlock = ttlBlocks.blocksBySubject.get(ownerId);

                if (ownerBlock != null && !ownerBlock.isEmpty()) {
                    parts.add(ownerBlock);
                } else {
                    parts.add("# WARNING: original TTL container NodeShape block not found for " + ownerId);
                }
            }
        }

        String shapeId = toQName(model, shape);
        String shapeBlock = ttlBlocks.blocksBySubject.get(shapeId);

        if (shapeBlock != null && !shapeBlock.isEmpty()) {
            parts.add(shapeBlock);
        } else {
            parts.add("# WARNING: original TTL block not found for " + shapeId);
        }

        StmtIterator spIt = model.listStatements(shape, SH.sparql, (RDFNode) null);
        while (spIt.hasNext()) {
            RDFNode spNode = spIt.next().getObject();

            if (spNode.isResource()) {
                String spId = toQName(model, spNode.asResource());
                String spBlock = ttlBlocks.blocksBySubject.get(spId);

                if (spBlock != null && !spBlock.isEmpty()) {
                    parts.add(spBlock);
                } else {
                    parts.add("# WARNING: original TTL SPARQL block not found for " + spId);
                }
            }
        }

        return String.join(System.lineSeparator() + System.lineSeparator(), parts);
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }

        String s = value;

        boolean mustQuote =
                s.contains(",")
                        || s.contains("\"")
                        || s.contains("\n")
                        || s.contains("\r")
                        || s.contains("\t")
                        || s.contains(";");

        if (mustQuote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }

        return s;
    }

    private static void saveEaImportTableToCsv(
            Map<String, List<String>> table,
            List<String> order,
            File outFile
    ) {
        if (outFile == null) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outFile.toPath(), StandardCharsets.UTF_8)) {

            int rowCount = 0;

            for (String colName : order) {
                List<String> col = table.get(colName);
                if (col != null && col.size() > rowCount) {
                    rowCount = col.size();
                }
            }

            for (int row = 0; row < rowCount; row++) {
                List<String> values = new ArrayList<>();

                for (String colName : order) {
                    List<String> col = table.get(colName);
                    String value = "";

                    if (col != null && row < col.size()) {
                        value = col.get(row);
                    }

                    values.add(csvEscape(value));
                }

                writer.write(String.join(",", values));
                writer.newLine();
            }

            System.out.println("Saved EA import CSV: " + outFile.getAbsolutePath());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static File chooseEaImportCsvOutputFile(String suggestedName) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save SHACL EA Import CSV files");
        fileChooser.setInitialFileName(suggestedName);

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv")
        );

        return fileChooser.showSaveDialog(null);
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
