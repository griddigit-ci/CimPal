/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * Adapted for Core (no JavaFX) from eu.griddigit.cimpal.main.core.ShaclTools.
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RIOT;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Core (no-JavaFX) utility for splitting a set of SHACL shape models into separate files
 * according to a mapping defined in an Excel sheet.
 *
 * <p>Adapted from {@code eu.griddigit.cimpal.main.core.ShaclTools#splitShaclPerXlsInput}.
 */
public class ShaclOrganizer {

    /**
     * Split SHACL constraints from the provided shape models into per-file output according
     * to the mapping table in {@code inputXLSdata}.
     *
     * <p>Each row in {@code inputXLSdata} (skipping row 0 which is the header) must have
     * at least these columns:
     * <ol>
     *   <li>constraintName — the sh:name value to look up</li>
     *   <li>subfolder — first path component under outputDir</li>
     *   <li>filename — file name (including extension)</li>
     *   <li>newPrefix — "keep" to keep existing prefixes (other values not yet implemented)</li>
     *   <li>newPrefixNS — namespace for newPrefix; use "skip" to skip this row</li>
     *   <li>newBaseURI — base URI written to the Turtle output</li>
     *   <li>newGroupURI — (reserved for future use)</li>
     *   <li>newGroupName — (reserved for future use)</li>
     * </ol>
     *
     * @param inputXLSdata  row data from the template Excel file (row 0 = header)
     * @param shapeModels   the SHACL models to search for each constraint
     * @param outputDir     root directory where split files are written
     */
    public static void splitShaclPerXlsInput(
            ArrayList<Object> inputXLSdata,
            List<Model> shapeModels,
            Path outputDir) throws IOException {

        Map<String, Model> splitShaclMap = new LinkedHashMap<>();
        Map<String, String> baseMap = new LinkedHashMap<>();

        int constraintCount = 0;

        for (Object inputXLSrow : inputXLSdata) {
            List<?> currentRow = (List<?>) inputXLSrow;
            if (currentRow.size() < 6) continue; // skip malformed rows

            String constraintName  = currentRow.get(0).toString();
            String subFolder       = currentRow.get(1).toString();
            String fileName        = currentRow.get(2).toString();
            String newPrefix       = currentRow.get(3).toString();
            String newPrefixNS     = currentRow.get(4).toString();
            String newBaseURI      = currentRow.get(5).toString();

            if (newPrefixNS.equals("skip")) continue; // skip this row

            String constraintFilePath = outputDir.resolve(subFolder).resolve(fileName).toString();

            Model shaclModel;
            if (splitShaclMap.containsKey(constraintFilePath)) {
                shaclModel = splitShaclMap.get(constraintFilePath);
            } else {
                shaclModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                splitShaclMap.put(constraintFilePath, shaclModel);
                baseMap.put(constraintFilePath, newBaseURI);
            }

            boolean constraintFound = false;

            for (Model shModel : shapeModels) {
                Statement leadStmt = null;

                for (StmtIterator i = shModel.listStatements(null, SH.name, (RDFNode) null); i.hasNext(); ) {
                    Statement stmt = i.next();
                    String nameVal = stmt.getObject().asLiteral().getString();
                    if (nameVal.equals(constraintName)
                            || (nameVal.contains(constraintName) && nameVal.contains("|"))) {
                        leadStmt = stmt;
                        constraintFound = true;
                    }
                }

                if (leadStmt == null) continue;

                LinkedList<Statement> stmtToSave = new LinkedList<>();
                Resource sparqlURI = ResourceFactory.createResource("https://griddigit.eu/sparql/empty");
                boolean hasSparql = false;
                Resource groupURI = ResourceFactory.createResource("https://griddigit.eu/group/empty");
                boolean hasGroup = false;

                for (StmtIterator i = shModel.listStatements(leadStmt.getSubject(), null, (RDFNode) null); i.hasNext(); ) {
                    Statement stmt = i.next();
                    if (stmt.getObject().isAnon()) {
                        if (newPrefix.equals("keep")) shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                    } else {
                        if (newPrefix.equals("keep")) stmtToSave.add(stmt);
                    }
                    if (stmt.getPredicate().equals(SH.sparql)) { sparqlURI = stmt.getObject().asResource(); hasSparql = true; }
                    if (stmt.getPredicate().equals(SH.group))  { groupURI  = stmt.getObject().asResource(); hasGroup  = true; }
                }

                if (hasSparql) {
                    for (StmtIterator i = shModel.listStatements(sparqlURI, null, (RDFNode) null); i.hasNext(); ) {
                        Statement stmt = i.next();
                        if (stmt.getObject().isAnon()) {
                            if (newPrefix.equals("keep")) shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                        } else {
                            if (newPrefix.equals("keep")) stmtToSave.add(stmt);
                        }
                    }
                }

                if (hasGroup) {
                    for (StmtIterator i = shModel.listStatements(groupURI, null, (RDFNode) null); i.hasNext(); ) {
                        Statement stmt = i.next();
                        if (newPrefix.equals("keep")) stmtToSave.add(stmt);
                    }
                }

                // Gather the NodeShape that references this PropertyShape
                for (StmtIterator i = shModel.listStatements(null, SH.property, leadStmt.getSubject()); i.hasNext(); ) {
                    Statement stmt = i.next();
                    if (stmt.getObject().isAnon()) {
                        if (newPrefix.equals("keep")) shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                    } else {
                        if (newPrefix.equals("keep")) stmtToSave.add(stmt);
                    }
                    for (StmtIterator j = shModel.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                        Statement stmtNode = j.next();
                        if (!stmtNode.getPredicate().equals(SH.property)) {
                            if (stmtNode.getObject().isAnon()) {
                                if (newPrefix.equals("keep")) shaclModel = addBlankNode(shModel, shaclModel, stmtNode, false, null);
                            } else {
                                if (newPrefix.equals("keep")) stmtToSave.add(stmtNode);
                            }
                        }
                    }
                }

                if (hasSparql) {
                    for (StmtIterator i = shModel.listStatements(null, RDF.type, OWL2.Ontology); i.hasNext(); ) {
                        Statement stmt = i.next();
                        for (StmtIterator j = shModel.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                            Statement stmtOnt = j.next();
                            if (stmtOnt.getObject().isAnon()) {
                                if (newPrefix.equals("keep")) shaclModel = addBlankNode(shModel, shaclModel, stmtOnt, false, null);
                            } else {
                                if (newPrefix.equals("keep")) stmtToSave.add(stmtOnt);
                            }
                        }
                    }
                }

                shaclModel.add(stmtToSave);
                shaclModel.setNsPrefixes(shModel.getNsPrefixMap());
                splitShaclMap.replace(constraintFilePath, shaclModel);
            }

            if (!constraintFound) {
                System.out.println("This constraint was not found: " + constraintName);
            }
            constraintCount++;
        }

        System.out.println("Total number of constraints in the input: " + constraintCount);

        // Write each accumulated model to its target file
        for (Map.Entry<String, Model> entry : splitShaclMap.entrySet()) {
            String filePath = entry.getKey();
            Model shaclModelToSave = entry.getValue();
            if (!shaclModelToSave.isEmpty()) {
                String saveBaseURI = baseMap.get(filePath);
                Path filePathObj = Path.of(filePath);
                Files.createDirectories(filePathObj.getParent());

                try (OutputStream out = Files.newOutputStream(filePathObj)) {
                    RDFWriter.create()
                            .base(saveBaseURI)
                            .set(RIOT.symTurtleOmitBase, false)
                            .set(RIOT.symTurtleIndentStyle, "wide")
                            .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                            .set(RIOT.symTurtleMultilineLiterals, true)
                            .lang(Lang.TURTLE)
                            .source(shaclModelToSave)
                            .output(out);
                    System.out.println("Model saved successfully to " + filePath);
                } catch (IOException e) {
                    System.err.println("Error saving model to file: " + e.getMessage());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private blank-node helpers
    // (Adapted from eu.griddigit.cimpal.main.core.ShaclTools and
    //  eu.griddigit.cimpal.main.util.CompareFactory)
    // -----------------------------------------------------------------------

    private static Model addBlankNode(Model modelOrig, Model modelTarget, Statement stmt,
                                      boolean blankInBlank, RDFNode obj) {
        if (blankInBlank) {
            for (StmtIterator i = modelOrig.listStatements(obj.asResource(), null, (RDFNode) null); i.hasNext(); ) {
                Statement stmtB = i.next();
                modelTarget.add(stmtB);
                if (stmtB.getObject().isAnon()) {
                    modelTarget = addBlankNode(modelOrig, modelTarget, stmtB, false, null);
                }
            }
        } else {
            Map<Boolean, List<RDFNode>> isBNlistB = isBlankNodeAlist(stmt);
            if (isBNlistB.containsKey(Boolean.TRUE)) {
                RDFList objectlist = modelOrig.getList(stmt.getObject().asResource());
                RDFList pathRDFlist = modelTarget.createList(objectlist.iterator());
                for (RDFNode objO : objectlist.asJavaList()) {
                    if (objO.isAnon()) {
                        modelTarget = addBlankNode(modelOrig, modelTarget, stmt, true, objO);
                    }
                }
                Resource r = modelTarget.createResource(stmt.getSubject().toString());
                r.addProperty(stmt.getPredicate(), pathRDFlist);
            } else {
                modelTarget.add(stmt);
                for (StmtIterator i = modelOrig.listStatements(stmt.getObject().asResource(), null, (RDFNode) null); i.hasNext(); ) {
                    modelTarget.add(i.next());
                }
            }
        }
        return modelTarget;
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
}
