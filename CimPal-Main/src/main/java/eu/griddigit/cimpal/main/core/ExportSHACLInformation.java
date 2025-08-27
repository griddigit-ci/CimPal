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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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
}
