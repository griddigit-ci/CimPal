/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package core;

import application.MainController;
import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.swing.*;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


public class ExportInstanceDataTemplate {

    public static void rdfsContent(Model model) throws FileNotFoundException {
        String cimsNs = MainController.prefs.get("cimsNamespace","");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);

        List<String> rdfsClassName = new LinkedList<>(); // list for the name of the class without namespace
        List<String> rdfsClass = new LinkedList<>(); // list for the classes
        List<String> rdfsAttrAssoc = new LinkedList<>(); // list for the property attribute or association
        List<String> rdfsAttrOrAssocFlag = new LinkedList<>(); // list for the identification if is attribute or association
        List<String> rdfsItemAttrDatatype = new LinkedList<>(); // list for the datatype in case of attribute
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item


        for (int cl = 0; cl < ((ArrayList) shapeData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and record for each concrete class
            //add the Class
            String classLocalName = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(0)).get(2).toString();
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(0)).get(0).toString();

            //add NodeShape for the CIM class
            //shapeModel = ShaclTools.addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);
            //check if the class is stereotyped Description
            //boolean classDescripStereo=false;
            //if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(0)).get(4).toString().equals("Yes")){
            //    classDescripStereo=true;
            //}


            for (int atas = 1; atas < ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).size(); atas++) {
                // this is to loop on the attributes and associations (including inherited) for a given class and add PropertyNode for each attribute or association

                /*
                //every time a new property is added the reference is also added to the ShapeNode of the class
                ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                *//*
                 * propertyNodeFeatures structure
                 * 0 - type of check: cardinality, datatype, associationValueType
                 * 1 - message
                 * 2 - name
                 * 3 - description
                 * 4 - severity
                 * 5 - cardinality
                 * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                 * in case of enumeration 6 is set to Enumeration
                 * in case of compound 6 is set to Compound
                 * 7 - is a list of uri of the enumeration attributes
                 * 8 - order
                 * 9 - group
                 * 10 - the list of concrete classes for association - the value type at the used end
                 * 11 - classFullURI for the targetClass of the NodeShape
                 * 12 - the uri of the compound class to be used in sh:class
                 * 13 - path for the attributes of the compound
                 *//*
                for (int i = 0; i < 14; i++) {
                    propertyNodeFeatures.add("");
                }*/

                if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                    if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                        //Cardinality check
                        //propertyNodeFeatures.set(0, "cardinality");
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                        //String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                        //propertyNodeFeatures.set(5, cardinality);
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                       // propertyNodeFeatures.set(1, "Missing required association.");
                        //propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                        //propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                       //propertyNodeFeatures.set(4, "Violation");
                        //propertyNodeFeatures.set(8, atas - 1); // this is the order
                        //propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();
                        rdfsClassName.add(classLocalName);
                        rdfsClass.add(classFullURI);
                        rdfsAttrAssoc.add(propertyFullURI);
                        rdfsAttrOrAssocFlag.add("Association");
                        rdfsItemAttrDatatype.add("N/A");
                        rdfsItemMultiplicity.add(cardinality);


                        //shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                        /*//Association check for target class - only for concrete classes in the profile
                        if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                            propertyNodeFeatures.set(0, "associationValueType");
                            //String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                            //localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                            //propertyNodeFeatures.set(5, cardinality);
                            //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");
                            propertyNodeFeatures.set(1, "Not correct target class.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                            propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                            List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10);
                            propertyNodeFeatures.set(10, concreteClasses);
                            //String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();
                            propertyNodeFeatures.set(11, classFullURI);

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        }*/
                    }

                } else if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute

                    //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                    //String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(4).toString();
                    String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(1).toString();

                    String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                    rdfsClassName.add(classLocalName);
                    rdfsClass.add(classFullURI);
                    rdfsAttrAssoc.add(propertyFullURI);

                    rdfsItemMultiplicity.add(cardinality);
                    //add datatypes checks depending on it is Primitive, Datatype or Enumeration
                    switch (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(8).toString()) {
                        case "Primitive": {
                            String datatypePrimitive = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypePrimitive);
                            rdfsAttrOrAssocFlag.add("Attribute");
                            break;
                        }
                        case "CIMDatatype": {
                            String datatypePrimitive = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypePrimitive);
                            rdfsAttrOrAssocFlag.add("Attribute");
                            break;
                        }
                        case "Compound": {
                            String datatypeCompound = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypeCompound);
                            rdfsAttrOrAssocFlag.add("Compound");
                            break;
                        }
                        case "Enumeration":
                            //propertyNodeFeatures.set(6, "Enumeration");
                            //propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                            //this adds the structure which is a list of possible enumerated values
                            //propertyNodeFeatures.set(7, ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10));
                            rdfsItemAttrDatatype.add(((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10).toString());
                            rdfsAttrOrAssocFlag.add("Enumeration");
                            break;
                    }


                    /*if (MainController.excludeMRID) { // user selects that mRID should be skipped for description classes
                        if (classDescripStereo) { //the class is stereotyped description
                            if (!localNameAttr.equals("IdentifiedObject.mRID")){ //the attribute is not mRID
                                shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                            }
                        }else{
                            shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                        }
                    }else{
                        shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                    }

                    //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                    // for each attribute of the compound
                    if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(8).toString().equals("Compound")) {
                        ArrayList shapeDataCompound = ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10));
                        shapeModel = addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI);
                    }*/

                }
            }
        }



       /* List<String> rdfsItem = new LinkedList<>(); // list for the element - item
        List<String> rdfsItemDescription = new LinkedList<>(); // list for the description of the item
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item
        List<String> rdfsItemtype = new LinkedList<>(); // list for the type of the item
        List<String> rdfsItemAssociationUsed = new LinkedList<>(); // list for the association used of the item
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";*/

        // do the excel export
        exportDesciption(rdfsClass,rdfsAttrAssoc,"InstanceDataTemplate","InstanceDataTemplate","Save instance data template from RDFS", rdfsAttrOrAssocFlag, rdfsItemAttrDatatype, rdfsItemMultiplicity);
    }




    private static void exportDesciption(List rdfsItem, List rdfsItemDescription, String sheetname, String initialFileName, String title, List rdfsItemMultiplicity, List rdfsItemtype, List rdfsItemAssociationUsed) throws FileNotFoundException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet;

    /*    for (int sh=0; sh<rdfsClassName.size();sh++) {
            System.out.println(rdfsClassName.get(sh).toString());
            System.out.println(workbook.getSheetIndex(rdfsClassName.get(sh).toString()));
            if (sh>0) {
                if (!rdfsClassName.get(sh).toString().equals(rdfsClassName.get(sh - 1).toString())) {
*/
        //if (workbook.getSheetIndex(rdfsClassName.get(sh).toString())==-1) {
        //sheet = workbook.createSheet(String.valueOf(sh));
        sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow = sheet.createRow(0);
        //firstRow.createCell(0).setCellValue(rdfsClassName.get(sh).toString());
        //XSSFRow secondRow = sheet.createRow(1);
        ///set titles of columns
        firstRow.createCell(0).setCellValue("Class");
        firstRow.createCell(1).setCellValue("Property-AttributeAssociation");
        firstRow.createCell(2).setCellValue("Type");
        firstRow.createCell(3).setCellValue("Datatype");
        firstRow.createCell(4).setCellValue("Multiplicity");
/*                } else {
                    sheet = workbook.getSheet(rdfsClassName.get(sh).toString());
                }
            } else {
                //if (workbook.getSheetIndex(rdfsClassName.get(sh).toString())==-1) {
                sheet = workbook.createSheet(String.valueOf(sh));
                XSSFRow firstRow = sheet.createRow(0);
                firstRow.createCell(0).setCellValue(rdfsClassName.get(sh).toString());
                XSSFRow secondRow = sheet.createRow(1);
                ///set titles of columns
                secondRow.createCell(0).setCellValue("Class");
                secondRow.createCell(1).setCellValue("Property-AttributeAssociation");
                secondRow.createCell(2).setCellValue("Type");
                secondRow.createCell(3).setCellValue("Datatype");
                secondRow.createCell(4).setCellValue("Multiplicity");
            }*/
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
        }



        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
        filechooser.setInitialFileName(initialFileName);
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        filechooser.setTitle(title);
        File saveFile = filechooser.showSaveDialog(null);
        if (saveFile != null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
                outputStream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
