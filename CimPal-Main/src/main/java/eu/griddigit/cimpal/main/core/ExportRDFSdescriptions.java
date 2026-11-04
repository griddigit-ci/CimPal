/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.core;

import eu.griddigit.cimpal.core.utils.ExcelSheets;
import eu.griddigit.cimpal.core.utils.RdfTypeSheets;
import eu.griddigit.cimpal.core.utils.RdfsDescriptions;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.model.RDFAttributeData;
import eu.griddigit.cimpal.main.util.ModelFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ExportRDFSdescriptions {

    private static final Logger LOG = LoggerFactory.getLogger(ExportRDFSdescriptions.class);


    /** Tools ▸ Export RDFS descriptions: Core's {@link RdfsDescriptions}, saved where the user chooses. */
    public static void rdfsDescriptions(Model model) throws FileNotFoundException {
        saveWorkbook(RdfsDescriptions.workbook(model), "Save descriptions from RDFS", "RDFSdescription");
    }

        // Map to store data for each class (sheet name -> List of Maps for rows)
    public static Map<String, List<List<RDFAttributeData>>> getRDFDataForClasses(Model model, List<String> templateClasses) {
        Map<String, List<List<RDFAttributeData>>> classData = new LinkedHashMap<>();

        // Parse all subjects in the RDF model
        ResIterator subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.next();

            // Get the RDF type for the subject
            StmtIterator typeStatements = model.listStatements(subject, RDF.type, (RDFNode) null);
            if (typeStatements.hasNext()) {
                List<RDFNode> typeObjects = new ArrayList<>();

                // Collect all RDF.type objects
                while (typeStatements.hasNext()) {
                    Statement stmt = typeStatements.nextStatement();
                    typeObjects.add(stmt.getObject());
                }

                RDFNode selectedType = null;
                if (typeObjects.size() > 1) {
                    // Multiple types found
                    for (RDFNode typeObj : typeObjects) {
                        if (templateClasses.contains(typeObj.toString())) {
                            selectedType = typeObj;
                            break;
                        }
                    }
                    if (selectedType == null) {
                        selectedType = showTypeSelectionDialog(subject, typeObjects);
                        if (selectedType == null) {
                            selectedType = typeObjects.getFirst();
                        }
                    }
                } else {
                    // Only one type found
                    selectedType = typeObjects.getFirst();
                }

                String className = selectedType.toString();
                classData.putIfAbsent(className, new LinkedList<>());

                List<RDFAttributeData> rowData = new ArrayList<>();
                // Add the subject URI as a special column (e.g., "Subject")
                String[] splitSubjectUri = subject.getURI().split("#", -1);
                rowData.add(new RDFAttributeData("rdf" ,"id", splitSubjectUri[splitSubjectUri.length-1],
                        "Resource")); // Add the subject's URI
                StmtIterator properties = subject.listProperties();
                while (properties.hasNext()) {
                    Statement property = properties.next();

                    boolean isRdfType = property.getPredicate().equals(RDF.type);
                    boolean isSelectedType = isRdfType && property.getObject().equals(selectedType);

                    if (isSelectedType) {
                        continue;
                    }

                    String prefix;
                    String propertyName;

                    if (property.getPredicate().equals(RDF.type)) {
                        prefix = property.getPredicate().getNameSpace();
                        propertyName = property.getPredicate().getLocalName();
                    } else {
                        prefix = model.getNsURIPrefix(property.getPredicate().getNameSpace());
                        propertyName = property.getPredicate().getLocalName();
                    }

                    RDFNode object = property.getObject();
                    String propertyValue;
                    String tpe;
                    if (object.isResource()) {
                        if (property.getPredicate().equals(RDF.type)) {
                            propertyValue = object.asResource().getURI();
                        } else {
                            String objectNs = object.asResource().getNameSpace();
                            String objectPrefix = model.getNsURIPrefix(objectNs);
                            propertyValue = (objectPrefix != null ? objectPrefix + ":" : (objectNs.startsWith("file://") ? "" : objectNs)) + object.asResource().getLocalName();
                        }
                        tpe = "Resource";
                    } else {
                        propertyValue = object.toString();
                        tpe = "Literal";
                    }
                    if (property.getPredicate().equals(RDF.type)) {
                        rowData.add(new RDFAttributeData(
                                property.getPredicate().getNameSpace(),
                                property.getPredicate().getLocalName(),
                                propertyValue,
                                tpe
                        ));
                    } else {
                        rowData.add(new RDFAttributeData(prefix != null ? prefix : "", propertyName, propertyValue, tpe));
                    }                }

                classData.get(className).add(rowData);
            }
        }
        return classData;
    }

    public static Map<String, List<List<RDFAttributeData>>> getRDFDataForClasses(Model model) {
        List<String> modelClasses = new ArrayList<>();

        ResIterator subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.next();

            StmtIterator typeStatements = model.listStatements(subject, RDF.type, (RDFNode) null);
            while (typeStatements.hasNext()) {
                RDFNode typeObj = typeStatements.nextStatement().getObject();

                if (typeObj.isResource() && !modelClasses.contains(typeObj.toString())) {
                    modelClasses.add(typeObj.toString());
                }
            }
        }

        return getRDFDataForClasses(model, modelClasses);
    }

    private static RDFNode showTypeSelectionDialog(Resource subject, List<RDFNode> typeObjects) {
        String[] options = typeObjects.stream()
                .map(type -> type.toString().split("#")[1]) // Get local names
                .toArray(String[]::new);

        String message = "Subject: " + subject.getLocalName() + "\nMultiple RDF types found. Select the primary one:";

        int choice = javax.swing.JOptionPane.showOptionDialog(
                null,
                message,
                "Select RDF Type",
                javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        return (choice >= 0) ? typeObjects.get(choice) : null;
    }

    /** Tools ▸ Export RDF triples to xls: Core's {@link RdfTypeSheets}, saved where the user chooses. */
    public static void exportRDFToExcel(Model model) {
        saveWorkbook(RdfTypeSheets.workbook(model), "Save RDF to Excel", "");
    }

    private static void saveWorkbook(XSSFWorkbook workbook, String title, String initialFileName) {
        File saveFile = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"), title, initialFileName);
        try {
            if (saveFile == null) {
                workbook.close();
            } else {
                ExcelSheets.save(workbook, saveFile.toPath());
            }
        } catch (IOException e) {
            LOG.error("Could not save the workbook", e);
            GUIhelper.showUserFriendlyError("Save error", "The workbook could not be saved.", e);
        }
    }
}
