/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package core;

import application.MainController;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static core.RdfConvert.fileSaveDialog;


public class ExportInstanceDataTemplate {

    public static void rdfsContent(Model model) throws FileNotFoundException {
        String cimsNs = MainController.prefs.get("cimsNamespace","");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        Map<String,String> prefMap = model.getNsPrefixMap();
        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);

        List<String> rdfsClassName = new LinkedList<>(); // list for the name of the class without namespace
        List<String> rdfsClass = new LinkedList<>(); // list for the classes
        List<String> rdfsAttrAssoc = new LinkedList<>(); // list for the property attribute or association
        List<String> rdfsAttrOrAssocFlag = new LinkedList<>(); // list for the identification if is attribute or association
        List<String> rdfsItemAttrDatatype = new LinkedList<>(); // list for the datatype in case of attribute
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item


        for (int cl = 0; cl < ((ArrayList<?>) shapeData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and record for each concrete class
            //add the Class
            String classLocalName = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(2).toString();
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).getFirst().toString();

            for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).size(); atas++) {
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

                if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                        //Cardinality check
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
                        rdfsClassName.add(classLocalName);
                        rdfsClass.add(classFullURI);
                        rdfsAttrAssoc.add(propertyFullURI);
                        rdfsAttrOrAssocFlag.add("Association");
                        rdfsItemAttrDatatype.add("N/A");
                        rdfsItemMultiplicity.add(cardinality);
                    }

                } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                    String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString();
                    String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                    rdfsClassName.add(classLocalName);
                    rdfsClass.add(classFullURI);
                    rdfsAttrAssoc.add(propertyFullURI);

                    rdfsItemMultiplicity.add(cardinality);
                    //add datatypes checks depending on it is Primitive, Datatype or Enumeration
                    switch (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString()) {
                        case "Primitive": {
                            String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypePrimitive);
                            rdfsAttrOrAssocFlag.add("Attribute");
                            break;
                        }
                        case "CIMDatatype": {
                            String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypePrimitive);
                            rdfsAttrOrAssocFlag.add("Attribute");
                            break;
                        }
                        case "Compound": {
                            String datatypeCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                            rdfsItemAttrDatatype.add(datatypeCompound);
                            rdfsAttrOrAssocFlag.add("Compound");
                            break;
                        }
                        case "Enumeration":
                            rdfsItemAttrDatatype.add(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString());
                            rdfsAttrOrAssocFlag.add("Enumeration");
                            break;
                    }
                }
            }
        }

        // do the excel export
        exportDesciption(rdfsClass,rdfsAttrAssoc, rdfsAttrOrAssocFlag,rdfsItemAttrDatatype,rdfsItemMultiplicity);
        // do the RDF export
        exportDesciptionInRDF(rdfsClass,rdfsAttrAssoc, rdfsAttrOrAssocFlag,rdfsItemAttrDatatype,rdfsItemMultiplicity,prefMap);
    }




    private static void exportDesciption(List<String> rdfsItem, List<String> rdfsItemDescription, List<String> rdfsItemAssociationUsed,List<String> rdfsItemtype,List<String> rdfsItemMultiplicity) throws FileNotFoundException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet;

        sheet = workbook.createSheet("InstanceDataTemplate");
        XSSFRow firstRow = sheet.createRow(0);
        ///set titles of columns
        firstRow.createCell(0).setCellValue("Class");
        firstRow.createCell(1).setCellValue("Property-AttributeAssociation");
        firstRow.createCell(2).setCellValue("Multiplicity");
        firstRow.createCell(3).setCellValue("Datatype");
        firstRow.createCell(4).setCellValue("Type");

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

        File saveFile = util.ModelFactory.filesavecustom("Excel files", List.of("*.xlsx"),"","");
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
    }
    private static void exportDesciptionInRDF(List<String> rdfsItem, List<String> rdfsItemDescription, List<String> rdfsItemKind,List<String> rdfsItemtype,List<String> rdfsItemMultiplicity,Map<String,String> prefMap) throws FileNotFoundException {
        // rdfsItem is the class
        // rdfsItemDescription is the property
        // rdfsItemtype - the datatype
        // rdfsItemKind - attribute.association, enum
        // rdfsItemMultiplicity - multiplicity


        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        model.setNsPrefixes(prefMap);
        model.setNsPrefix("owl",OWL2.NS);
        model.setNsPrefix("cims","http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#");
        int listcount = 0;
        for (String property : rdfsItemDescription){
            Resource sub = ResourceFactory.createResource(property);
            model.add(ResourceFactory.createStatement(sub, RDF.type,RDF.Property));
            Resource subClass = ResourceFactory.createResource(rdfsItem.get(listcount));
            model.add(ResourceFactory.createStatement(subClass, RDF.type, RDFS.Class));
            model.add(ResourceFactory.createStatement(sub, RDFS.domain,subClass));
            model.add(ResourceFactory.createStatement(subClass, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#","stereotype"), ResourceFactory.createPlainLiteral("concrete")));
            RDFNode minC;
            RDFNode maxC;
            if (rdfsItemMultiplicity.get(listcount).contains("..")){
                minC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount).split("\\.\\.",2)[0]);
                maxC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount).split("\\.\\.",2)[1]);
            }else{
                minC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount));
                maxC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount));
            }
            model.add(ResourceFactory.createStatement(sub, OWL2.minCardinality,minC));
            model.add(ResourceFactory.createStatement(sub, OWL2.maxCardinality,maxC));
            switch (rdfsItemKind.get(listcount)) {
                case "Association" -> model.add(ResourceFactory.createStatement(sub, RDFS.range, RDFS.Resource));
                case "Enumeration" -> model.add(ResourceFactory.createStatement(sub, RDFS.range, RDF.List));
                case "Attribute" -> {
                    model.add(ResourceFactory.createStatement(sub, RDFS.range, RDFS.Literal));
                    model.add(ResourceFactory.createStatement(sub, RDFS.range, ResourceFactory.createPlainLiteral(rdfsItemtype.get(listcount))));
                }
            }
            listcount=listcount+1;
        }
        OutputStream outTTL = fileSaveDialog("Save RDF Turtle for: " + "AllProperties", "RDF Turtle", "*.ttl");
        model.write(outTTL, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), "");
    }
}
