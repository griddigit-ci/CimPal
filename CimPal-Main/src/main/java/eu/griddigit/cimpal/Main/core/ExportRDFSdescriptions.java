/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.Main.core;

import eu.griddigit.cimpal.Main.model.RDFAttributeData;
import eu.griddigit.cimpal.Main.util.ModelFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;


public class ExportRDFSdescriptions {

    public static void rdfsDescriptions(Model model) throws FileNotFoundException {
        List<String> rdfsItem = new LinkedList<>(); // list for the element - item
        List<String> rdfsItemDescription = new LinkedList<>(); // list for the description of the item
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item
        List<String> rdfsItemtype = new LinkedList<>(); // list for the type of the item
        List<String> rdfsItemAssociationUsed = new LinkedList<>(); // list for the association used of the item
        List<String> rdfsStereotype = new LinkedList<>(); // list for the stereotype of the item
        List<String> rdfsConcreteClass = new LinkedList<>(); // list for the concrete classes
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";


        //iterate on all subjects
        for (ResIterator i = model.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();
            int mult =0;

            rdfsItem.add(resItem.toString().split("#", 2)[1]);

            try {
                String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                rdfsItemDescription.add(rdfsComment);
            } catch (Exception e) {
                rdfsItemDescription.add("No description defined.");
            }

            for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                try {
                    rdfsItemMultiplicity.add(resItemNode.toString().split("#M:", 2)[1]);
                    mult=1;
                } catch (Exception e) {
                    mult=0;
                }
            }
            if (mult==0) {
                rdfsItemMultiplicity.add("N/A");
            }

            // add information for association used and other things
            // is it a class
            if (model.listStatements(resItem,RDF.type, ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#Class")).hasNext()){ // it is a class
                rdfsItemtype.add("Class"); // it is a class
                rdfsItemAssociationUsed.add("N/A");
                if (model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                    rdfsConcreteClass.add("Yes");
                }else if (model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "stereotype"), ResourceFactory.createPlainLiteral("Primitive")).hasNext() || model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "stereotype"), ResourceFactory.createPlainLiteral("CIMDatatype")).hasNext() || model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()){
                    rdfsConcreteClass.add("N/A");
                }else{
                    rdfsConcreteClass.add("No");
                }
            }else if (model.listStatements(resItem,RDF.type, ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")).hasNext()){ // it is a property
                if (model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "AssociationUsed"), ResourceFactory.createPlainLiteral("Yes")).hasNext()){
                    rdfsItemAssociationUsed.add("Yes");
                    rdfsItemtype.add("Association"); // it is an association
                }else if (model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "AssociationUsed"), ResourceFactory.createPlainLiteral("No")).hasNext()){
                    rdfsItemAssociationUsed.add("No");
                    rdfsItemtype.add("Association"); // it is an association
                }else{
                    rdfsItemtype.add("Attribute"); // it is an attribute
                    rdfsItemAssociationUsed.add("N/A");
                }
                rdfsConcreteClass.add("N/A");
            }else{
                if (model.listStatements(resItem,RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")).hasNext()) {
                    rdfsItemAssociationUsed.add("N/A");
                    rdfsConcreteClass.add("N/A");
                    rdfsItemtype.add("Package");
                }else {
                    rdfsItemAssociationUsed.add("N/A");
                    rdfsConcreteClass.add("N/A");
                    rdfsItemtype.add("Enum");
                }
            }

            //adding the stereotype
            boolean hasStereotype=false;
            String stereo="";
            List<Statement> stereotypes = model.listStatements(resItem,ResourceFactory.createProperty(rdfNs, "stereotype"), (RDFNode) null).toList();
            for (Statement stmt : stereotypes){
                if (stmt.getObject().isLiteral()){
                    hasStereotype = true;
                    if (stereo.isEmpty()) {
                        stereo = stmt.getObject().toString();
                    }else{
                        stereo = stereo + "; " + stmt.getObject().toString();
                    }
                }
            }
            if (hasStereotype) {
                rdfsStereotype.add(stereo);
            }else{
                rdfsStereotype.add("N/A");
            }
        }
        // do the excel export
        exportDesciption(rdfsItem,rdfsItemDescription,"RDFS descriptions","RDFSdescription","Save descriptions from RDFS", rdfsItemMultiplicity, rdfsItemtype, rdfsItemAssociationUsed, rdfsStereotype, rdfsConcreteClass);
    }

    public static Map<String, List<List<RDFAttributeData>>> getRDFDataForClasses(Model model) {
        // Map to store data for each class (sheet name -> List of Maps for rows)
        Map<String, List<List<RDFAttributeData>>> classData = new LinkedHashMap<>();

        // Parse all subjects in the RDF model
        ResIterator subjects = model.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.next();

            // Get the RDF type for the subject
            Statement typeStatement = subject.getProperty(RDF.type);
            if (typeStatement != null) {
                String className = typeStatement.getObject().toString().split("#")[1];

                // Initialize storage for this class if it doesn't exist
                classData.putIfAbsent(className, new LinkedList<>());

                // Collect attributes for the current subject (row)
                List<RDFAttributeData> rowData = new ArrayList<>();
                // Add the subject URI as a special column (e.g., "Subject")
                String[] splitSubjectUri = subject.getURI().split("#", -1);
                rowData.add(new RDFAttributeData("rdf" ,"id", splitSubjectUri[splitSubjectUri.length-1],
                        "Resource")); // Add the subject's URI
                StmtIterator properties = subject.listProperties();
                while (properties.hasNext()) {
                    Statement property = properties.next();
                    // Get the prefix and local name of the property
                    //String propertyUri = property.getPredicate().getURI();
                    String prefix = model.getNsURIPrefix(property.getPredicate().getNameSpace());
                    String propertyName = property.getPredicate().getLocalName();

                    // Determine and format the property value
                    RDFNode object = property.getObject();
                    String propertyValue;
                    String tpe;
                    if (object.isResource()) {
                        // If the object is a resource (URI), format it as prefix:localname
                        //String objectUri = object.toString();
                        String objectNs = object.asResource().getNameSpace();
                        String objectPrefix = model.getNsURIPrefix(objectNs);
                        propertyValue = (objectPrefix != null ? objectPrefix + ":" : (objectNs.startsWith("file://") ? "" : objectNs)) + object.asResource().getLocalName();
                        tpe = "Resource";
                    } else {
                        // Otherwise, just use the literal value
                        propertyValue = object.toString();
                        tpe = "Literal";
                    }
                    rowData.add(new RDFAttributeData(prefix != null ? prefix : "", propertyName, propertyValue, tpe));
                }

                // Add the row data to the corresponding class
                classData.get(className).add(rowData);
            }
        }
        return classData;
    }

    public static void exportRDFToExcel(Model model) {
        // Map to store data for each class (sheet name -> List of Maps for rows)
        Map<String, List<List<RDFAttributeData>>> classData = getRDFDataForClasses(model);

        // Create Excel workbook and populate it
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            for (Map.Entry<String, List<List<RDFAttributeData>>> entry : classData.entrySet()) {
                Resource classResource = model.createResource(entry.getKey());
                String sheetName = classResource.getLocalName(); // Retrieve local name directly from the resource
                //String sheetName = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1); // Use local name
                List<List<RDFAttributeData>> rows = entry.getValue();

                // Create a new sheet for this class
                XSSFSheet sheet = workbook.createSheet(sheetName);

                // Create headers (based on the first row's keys)
                if (!rows.isEmpty()) {
                    XSSFRow headerRow = sheet.createRow(0);
                    Set<String> headersSet = new HashSet<>();
                    for (List<RDFAttributeData> row : rows){
                        row.forEach(rdfAttributeData -> headersSet.add(rdfAttributeData.getFullName()));
                    }
                    List<String> headers = new ArrayList<>(headersSet);

                    int hCol = 0;
                    for (String header : headersSet) {
                        headerRow.createCell(hCol).setCellValue(header);
                        hCol++;
                    }

                    // Populate data rows
                    for (int i = 0; i < rows.size(); i++) {
                        XSSFRow dataRow = sheet.createRow(i + 1);
                        List<RDFAttributeData> row = rows.get(i);
                        for (RDFAttributeData data : row) {
                            int dCol = headers.indexOf(data.getFullName());
                            dataRow.createCell(dCol).setCellValue(data.getValue());
                        }
                    }
                }
            }


            File saveFile = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"),"Save RDF to Excel","");
            if (saveFile != null) {
                try {
                    FileOutputStream outputStream = new FileOutputStream(saveFile);
                    workbook.write(outputStream);
                    workbook.close();
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private static void exportDesciption(List rdfsItem, List rdfsItemDescription, String sheetname, String initialFileName, String title, List rdfsItemMultiplicity, List rdfsItemtype, List rdfsItemAssociationUsed, List rdfsStereotype, List rdfsConcreteClass) throws FileNotFoundException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Item");
        firstRow.createCell(1).setCellValue("Description");
        firstRow.createCell(2).setCellValue("Multiplicity");
        firstRow.createCell(3).setCellValue("Type");
        firstRow.createCell(4).setCellValue("Association used");
        firstRow.createCell(5).setCellValue("Stereotype");
        firstRow.createCell(6).setCellValue("Concrete class");

        for (int row=0; row<rdfsItem.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = rdfsItem.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = rdfsItemDescription.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = rdfsItemMultiplicity.get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = rdfsItemtype.get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }

            Object celValue4 = rdfsItemAssociationUsed.get(row);
            try {
                if (celValue4 != null && Double.parseDouble(celValue4.toString()) != 0.0) {
                    xssfRow.createCell(4).setCellValue(Double.parseDouble(celValue4.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(4).setCellValue(celValue4.toString());
            }

            Object celValue5 = rdfsStereotype.get(row);
            try {
                if (celValue5 != null && Double.parseDouble(celValue5.toString()) != 0.0) {
                    xssfRow.createCell(5).setCellValue(Double.parseDouble(celValue5.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(5).setCellValue(celValue5.toString());
            }

            Object celValue6 = rdfsConcreteClass.get(row);
            try {
                if (celValue6 != null && Double.parseDouble(celValue6.toString()) != 0.0) {
                    xssfRow.createCell(6).setCellValue(Double.parseDouble(celValue6.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(6).setCellValue(celValue6.toString());
            }
        }

        File saveFile = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"),title,"");
        if (saveFile != null) {
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
