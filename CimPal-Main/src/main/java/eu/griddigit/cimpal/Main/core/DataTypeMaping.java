/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.Main.core;

import javafx.scene.control.Alert;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.datatypes.xsd.impl.XSDDateTimeStampType;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class DataTypeMaping {


    public static Map<String, RDFDatatype> createMap(List<String> propertyString, List<String> datatype) {

        Map<String, RDFDatatype> dataTypeMap = new HashMap<>();

        for (int m=0; m<propertyString.size();m++) {
            //map CIM datatypes to xsd datatypes
            switch (datatype.get(m)) {
                case "http://www.w3.org/2001/XMLSchema#integer", "http://www.w3.org/2001/XMLSchema#int", "Integer" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDinteger);
                case "http://www.w3.org/2001/XMLSchema#float", "Float" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDfloat);
                case "http://www.w3.org/2001/XMLSchema#string", "String", "Version", "UUID","StringFixedLanguage" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDstring);
                case "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString", "LangString" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), RDFLangString.rdfLangString);
                case "http://www.w3.org/2001/XMLSchema#boolean", "Boolean" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDboolean);
                case "http://www.w3.org/2001/XMLSchema#date", "Date" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDdate);
                case "http://www.w3.org/2001/XMLSchema#dateTime", "DateTime" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDdateTime);
                case "http://www.w3.org/2001/XMLSchema#dateTimeStamp", "DateTimeStamp" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDateTimeStampType.XSDdateTimeStamp);
                case "http://www.w3.org/2001/XMLSchema#decimal", "Decimal" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDdecimal);
                case "http://www.w3.org/2001/XMLSchema#duration", "Duration" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDduration);
                case "http://www.w3.org/2001/XMLSchema#gMonthDay", "MonthDay" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDgMonthDay);
                case "http://www.w3.org/2001/XMLSchema#time", "Time" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDtime);
                case "http://www.w3.org/2001/XMLSchema#anyURI", "URI", "URL", "IRI", "StringIRI" ->
                        dataTypeMap.putIfAbsent(propertyString.get(m), XSDDatatype.XSDanyURI);
            }
        }
        return dataTypeMap;
    }

    // adds a map property to an existing datatype map. Maps properties to xsd types
    public static Map<String, RDFDatatype> addDatatypeMapProperty(Map<String, RDFDatatype> dataTypeMap, String propertyString, String datatype) {

            //map CIM datatypes to xsd datatypes
        switch (datatype) {
            case "http://www.w3.org/2001/XMLSchema#integer", "http://www.w3.org/2001/XMLSchema#int", "Integer" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDinteger);
            case "http://www.w3.org/2001/XMLSchema#float", "Float" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDfloat);
            case "http://www.w3.org/2001/XMLSchema#string", "String",  "Version", "UUID","StringFixedLanguage" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDstring);
            case "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString", "LangString" ->
                    dataTypeMap.putIfAbsent(propertyString, RDFLangString.rdfLangString);
            case "http://www.w3.org/2001/XMLSchema#boolean", "Boolean" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDboolean);
            case "http://www.w3.org/2001/XMLSchema#date", "Date" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdate);
            case "http://www.w3.org/2001/XMLSchema#dateTime", "DateTime" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdateTime);
            case "http://www.w3.org/2001/XMLSchema#dateTimeStamp", "DateTimeStamp" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDateTimeStampType.XSDdateTimeStamp);
            case "http://www.w3.org/2001/XMLSchema#decimal", "Decimal" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdecimal);
            case "http://www.w3.org/2001/XMLSchema#duration", "Duration" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDduration);
            case "http://www.w3.org/2001/XMLSchema#gMonthDay", "MonthDay" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDgMonthDay);
            case "http://www.w3.org/2001/XMLSchema#time", "Time" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDtime);
            case "http://www.w3.org/2001/XMLSchema#anyURI", "URI", "URL", "IRI", "StringIRI" ->
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDanyURI);
        }
        return dataTypeMap;
    }

    //method to prepare the mapping of the datatypes for a model
    public static ArrayList<Object> mapFromRDFSfile(Model model, String rdfNs, String concreteNs){
        ArrayList<Object> propertyStringDatatype = new ArrayList<>();
        List<String> propertyString=new ArrayList<>();
        List<String> datatype = new ArrayList<>();
        propertyStringDatatype.add(propertyString);
        propertyStringDatatype.add(datatype);

        for (ResIterator i = model.listResourcesWithProperty(model.getProperty(rdfNs, "stereotype")); i.hasNext(); ) {
            Resource resItem = i.next();
            for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                if (resItemNode.toString().equals(concreteNs)) {// the resource is concrete - it is a concrete class
                    int root = 0;
                    Resource classItem = resItem;

                    while (root == 0) {
                        propertyStringDatatype = DataTypeMaping.getLocalAttributesAssociationsForMap(classItem, model, propertyStringDatatype, rdfNs);
                        if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                            classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                        } else {
                            root = 1;
                        }
                    }
                }
            }
        }
        return propertyStringDatatype;
    }

    public static Map<String, RDFDatatype> mapDatatypesFromRDF (){
        //select the file
//        FileChooser filechooser = new FileChooser();
//        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
//        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
//        List<File> selFile = filechooser.showOpenMultipleDialog(null);
        List<File> selFile = eu.griddigit.cimpal.Main.util.ModelFactory.fileChooserCustom(false,"RDF files", List.of("*.rdf"),"");


        if (selFile != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", selFile.get(0).getParent());
            //System.out.println("Selected file: " + selFile);
            Model model = ModelFactory.createDefaultModel(); // model is the rdf file
            Map<String, RDFDatatype> dataTypeMap = new HashMap<>();
            for (File file : selFile) {

                try {
                    String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
                    String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
                    RDFDataMgr.read(model, new FileInputStream(file.toString()), Lang.RDFXML);
                    ArrayList<Object> propertyStringDatatype = DataTypeMaping.mapFromRDFSfile(model, rdfNs, concreteNs);
                    List<String> propertyString = (ArrayList) propertyStringDatatype.get(0);
                    List<String> datatype = (ArrayList) propertyStringDatatype.get(1);
                    dataTypeMap = DataTypeMaping.createMap(propertyString, datatype);
                    //System.out.println(dataTypeMap);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
            return dataTypeMap;
        }
        return null;
    }


    //prepare for the mapping of the datatypes. This method finds all inherited attributes and their datatypes
    private static ArrayList<Object> getLocalAttributesAssociationsForMap(Resource resItem, Model model, ArrayList<Object> propertyStringDatatype, String rdfNs) {
        for (ResIterator i=model.listResourcesWithProperty(RDFS.domain); i.hasNext();) {
            Resource resItemDomain = i.next();
            if (resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())){
                int isAttr=0;
                if (!model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                    isAttr = 1;
                }

                if (isAttr==1) {
                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        Resource resItemNew = model.getResource(resItemNode.toString());
                        String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
                        String rdfType;
                        if (rdfTypeInit.length == 0) {
                            rdfType = rdfTypeInit[0];
                        } else {
                            rdfType = rdfTypeInit[1];
                        }

                        if (rdfType.equals("Class")) { // if it is a class
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Primitive")){
                                //It is a primitive
                                ((ArrayList) propertyStringDatatype.get(0)).add(resItemDomain.toString());
                                ((ArrayList) propertyStringDatatype.get(1)).add(resItemNode.asResource().getLocalName());
                            }
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"CIMDatatype")){
                                // It is a CIMdatatype
                                Resource resItemDTvalue=model.getResource(resItemNew+".value");
                                for (NodeIterator jdt = model.listObjectsOfProperty(resItemDTvalue, model.getProperty(rdfNs, "dataType")); jdt.hasNext(); ) {
                                    RDFNode resItemNodedt = jdt.next();
                                    ((ArrayList) propertyStringDatatype.get(0)).add(resItemDomain.toString());
                                    ((ArrayList) propertyStringDatatype.get(1)).add(resItemNodedt.asResource().getLocalName());
                                }
                            }

                            //If the datatypes is a Compound
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Compound")){

                                ArrayList<Object> classCompound = new ArrayList<>();


                                // this adds the whole structure including nested compounds
                                propertyStringDatatype = getAttributesOfCompoundForMap(resItemNew,model,propertyStringDatatype,rdfNs);


                            }




                        }
                    }
                }
            }
        }
        return propertyStringDatatype;
    }

    //returns a structure that contains the attributes of a compound
    public static ArrayList<Object> getAttributesOfCompoundForMap(Resource resItem, Model model, ArrayList<Object> propertyStringDatatype, String rdfNs){





        for (ResIterator i=model.listResourcesWithProperty(RDFS.domain); i.hasNext();) {
            Resource resItemDomain = i.next();
            if (resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())){
                ArrayList<Object> compoundProperty = new ArrayList<>();
                int isAttr=0;
                if (!model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                    isAttr = 1;

                }

                if (isAttr==1) {


                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        Resource resItemNew = model.getResource(resItemNode.toString());
                        String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
                        String rdfType;
                        if (rdfTypeInit.length == 0) {
                            rdfType = rdfTypeInit[0];
                        } else {
                            rdfType = rdfTypeInit[1];
                        }

                        if (rdfType.equals("Class")) { // if it is a class
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Primitive")){

                                ((ArrayList) propertyStringDatatype.get(0)).add(resItemDomain.toString());
                                ((ArrayList) propertyStringDatatype.get(1)).add(resItemNode.asResource().getLocalName());

                            }

                            //If the datatypes is a Compound
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Compound")){
                                propertyStringDatatype = getAttributesOfCompoundForMap(resItemNew,model,propertyStringDatatype,rdfNs);


                            }
                        }
                    }
                }

            }
        }

        return propertyStringDatatype;

    }

    //Support methog to identify the datatypes in cases where the mapping information comes from a saved mapping file
    public static RDFDatatype mapFromMapDefaultFile(String value){
        RDFDatatype valueRDFdatatype = null;
        if (value.equals(XSDDatatype.XSDinteger.toString())) {
            valueRDFdatatype = XSDDatatype.XSDinteger;
        } else if (value.equals(XSDDatatype.XSDfloat.toString())){
            valueRDFdatatype = XSDDatatype.XSDfloat;
        } else if (value.equals(XSDDatatype.XSDstring.toString())){
            valueRDFdatatype = XSDDatatype.XSDstring;
        } else if (value.equals(XSDDatatype.XSDboolean.toString())){
            valueRDFdatatype = XSDDatatype.XSDboolean;
        } else if (value.equals(XSDDatatype.XSDdate.toString())){
            valueRDFdatatype = XSDDatatype.XSDdate;
        } else if (value.equals(XSDDatatype.XSDdateTime.toString())){
            valueRDFdatatype = XSDDatatype.XSDdateTime;
        } else if (value.equals(XSDDateTimeStampType.XSDdateTimeStamp.toString())){
            valueRDFdatatype = XSDDateTimeStampType.XSDdateTimeStamp;
        } else if (value.equals(XSDDatatype.XSDdecimal.toString())){
            valueRDFdatatype = XSDDatatype.XSDdecimal;
        } else if (value.equals(XSDDatatype.XSDduration.toString())){
            valueRDFdatatype = XSDDatatype.XSDduration;
        } else if (value.equals(XSDDatatype.XSDgMonthDay.toString())){
            valueRDFdatatype = XSDDatatype.XSDgMonthDay;
        } else if (value.equals(XSDDatatype.XSDtime.toString())) {
            valueRDFdatatype = XSDDatatype.XSDtime;
        } else if (value.equals(XSDDatatype.XSDanyURI.toString())) {
            valueRDFdatatype = XSDDatatype.XSDanyURI;
        } else if (value.equals(RDFLangString.rdfLangString.toString())) {
            valueRDFdatatype = RDFLangString.rdfLangString;
        }else{
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("The following datatype is not properly mapped:"+value);
            alert.setHeaderText(null);
            alert.setTitle("Error - unknown datatype");
            alert.showAndWait();
        }

//        if (value.contains("integer")) {
//            valueRDFdatatype = XSDDatatype.XSDinteger;
//        } else if (value.contains("float")){
//            valueRDFdatatype = XSDDatatype.XSDfloat;
//        } else if (value.contains("string")){
//            valueRDFdatatype = XSDDatatype.XSDstring;
//        } else if (value.contains("boolean")){
//            valueRDFdatatype = XSDDatatype.XSDboolean;
//        } else if (value.equals("date")){
//            valueRDFdatatype = XSDDatatype.XSDdate;
//        } else if (value.contains("dateTime") && !value.contains("dateTimeStamp")){
//            valueRDFdatatype = XSDDatatype.XSDdateTime;
//        } else if (value.contains("dateTimeStamp")){
//            valueRDFdatatype = XSDDateTimeStampType.XSDdateTimeStamp;
//        } else if (value.contains("decimal")){
//            valueRDFdatatype = XSDDatatype.XSDdecimal;
//        } else if (value.contains("duration")){
//            valueRDFdatatype = XSDDatatype.XSDduration;
//        } else if (value.contains("gMonthDay")){
//            valueRDFdatatype = XSDDatatype.XSDgMonthDay;
//        } else if (value.equals("time")) {
//            valueRDFdatatype = XSDDatatype.XSDtime;
//        } else if (value.contains("anyURI")) {
//            valueRDFdatatype = XSDDatatype.XSDanyURI;
//        } else if (value.contains("langString")) {
//            valueRDFdatatype = RDFLangString.rdfLangString;
//        }else{
//            Alert alert = new Alert(Alert.AlertType.ERROR);
//            alert.setContentText("The following datatype is not properly mapped:"+value);
//            alert.setHeaderText(null);
//            alert.setTitle("Error - unknown datatype");
//            alert.showAndWait();
//        }

        return valueRDFdatatype;
    }

}
