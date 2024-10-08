/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import application.MainController;
import common.customWriter.CustomRDFFormat;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import util.ExcelTools;

import java.io.*;
import java.util.*;

import static application.MainController.unionmodelbaseprofilesshaclinheritanceonly;

public class ModelManipulationFactory {

    public static Map<String, Map> loadDataForIGMMulDateTime(String xmlBase, Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag, Map<String,Boolean> inputData, Boolean shaclModelUnionFlag) throws FileNotFoundException {

        Map<String, Map> loadDataMap= new HashMap<>();
        Lang rdfProfileFormat=null;

        List<File> modelFiles = new LinkedList<>();
        //if (!profileModelUnionFlag && MainController.rdfProfileFile!=null) {
           // modelFiles.add(MainController.rdfProfileFile);
       // }else{
        modelFiles=MainController.rdfProfileFileList;
        //}
        ArrayList<Object> profileData=null;
        Model baseInstanceModel;

        Map<String,ArrayList<Object>> profileDataMap=new HashMap<>();
        Map<String,Model> profileDataMapAsModel=new HashMap<>();

        // load all profile models
        Map<String,Model> profileModelMap = null;
        if (inputData.get("rdfs")) {
            profileModelMap = InstanceDataFactory.modelLoad(modelFiles, xmlBase, rdfProfileFormat, false);


            String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
            String rdfNs = MainController.prefs.get("cimsNamespace", "");

            //make the profile data per profile

            for (Map.Entry<String, Model> entry : profileModelMap.entrySet()) {
                profileData = ShaclTools.constructShapeData(entry.getValue(), rdfNs, concreteNs);
                profileDataMap.put(entry.getKey(), profileData);
                profileDataMapAsModel.put(entry.getKey(), ShaclTools.profileDataMapAsModelTemp);
            }
        }

        loadDataMap.put("profileDataMap",profileDataMap);
        loadDataMap.put("profileDataMapAsModel",profileDataMapAsModel);
        loadDataMap.put("profileModelMap",profileModelMap);

        return loadDataMap;
    }

    public static Set<Resource> LoadRDFAbout(String xmlBase) throws FileNotFoundException {
        Set<Resource> rdfAboutList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }
        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase+"RdfAbout")){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    rdfAboutList.add(ResourceFactory.createResource(o_i.toString()));

                }
            }
        }
        return rdfAboutList;
    }
    public static Set<Resource> LoadRDFEnum(String xmlBase) throws FileNotFoundException {
        Set<Resource> RdfEnumList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }

        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase+"RdfEnum")){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    RdfEnumList.add(ResourceFactory.createResource(o_i.toString()));
                }
            }
        }
        return RdfEnumList;
    }

    public static void generateDataFromXls(String xmlBase, Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag,
                                           Map<String,Boolean> inputData, Boolean shaclModelUnionFlag, String eqbdID, String tpbdID,Map<String,Object> saveProperties,
                                           boolean persistentEQflag) throws IOException {

        //this is to load profile data - this is needed for the export
        Map<String, Map> loadDataMap = ModelManipulationFactory.loadDataForIGMMulDateTime(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag);

        int firstfile = 1;
        for (File xmlfile : MainController.inputXLS) {
            // load input data xls
            ArrayList<Object> inputXLSdata;
            File excel = new File(xmlfile.toString());
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            int Num = book.getNumberOfSheets();

            String[] originalNameInParts = new String[0];
            Map<String,String> prefmap = new HashMap<>();

            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                XSSFSheet sheet = book.getSheetAt(sheetnum);
                String sheetname = sheet.getSheetName();
                if (sheetname.equals("Config")){
                    ArrayList<Object> inputXLSdataConfig = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);
                    for (Object o : inputXLSdataConfig) {
                        String yesno = ((LinkedList<?>) o).get(2).toString();
                        if (yesno.equals("Yes")) {
                            String pref = ((LinkedList<?>) o).get(0).toString();
                            String ns = ((LinkedList<?>) o).get(1).toString();
                            prefmap.putIfAbsent(pref, ns);
                        }
                    }
                    break;
                }

            }

            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                XSSFSheet sheet = book.getSheetAt(sheetnum);
                String sheetname = sheet.getSheetName();
                if (!sheetname.equals("Config")) {
                    saveProperties.put("filename", sheetname + ".xml");
                    if (sheetnum == 0 && firstfile == 1) {
                        saveProperties.put("useFileDialog", true);
                    } else {
                        saveProperties.put("useFileDialog", false);
                        saveProperties.put("fileFolder", MainController.prefs.get("LastWorkingFolder", ""));
                    }
                    inputXLSdata = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);


                    Model model = ModelFactory.createDefaultModel();
                    model.setNsPrefixes(prefmap);
//                model.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
//                model.setNsPrefix("nc", "http://entsoe.eu/ns/nc#");
//                //model.setNsPrefix("cims", MainController.prefs.get("cimsNamespace",""));
//                model.setNsPrefix("rdf", RDF.uri);
//                //model.setNsPrefix("owl", OWL.NS);
//                model.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
//                //model.setNsPrefix("xsd", XSD.NS);
//                model.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
//                model.setNsPrefix("dcat", "http://www.w3.org/ns/dcat#");
//                model.setNsPrefix("dcterms", "http://purl.org/dc/terms/#");
//                model.setNsPrefix("eumd", "http://entsoe.eu/ns/Metadata-European#");
//                model.setNsPrefix("prov", "http://www.w3.org/ns/prov#");
//                model.setNsPrefix("time", "http://www.w3.org/2006/time#");
//                model.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");


                    for (int row = 1; row < inputXLSdata.size(); row++) {
                        //String classURI = "";
                        if (!((LinkedList<?>) inputXLSdata.get(row)).isEmpty()) {
                            String classURI = ((LinkedList<?>) inputXLSdata.get(row)).getFirst().toString();
                            String classNS = ResourceFactory.createResource(classURI).asResource().getNameSpace();
                            String propertyURI = ((LinkedList<?>) inputXLSdata.get(row)).get(1).toString();
                            String propertyType = ((LinkedList<?>) inputXLSdata.get(row)).get(2).toString();
                            String datatype = ((LinkedList<?>) inputXLSdata.get(row)).get(3).toString();
                            String multiplicity = ((LinkedList<?>) inputXLSdata.get(row)).get(4).toString();
                            if (((LinkedList<?>) inputXLSdata.get(row)).size() > 5) {
                                for (int col = 5; col < ((LinkedList<?>) inputXLSdata.get(row)).size(); col += 2) {
                                    //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col).toString());
                                    //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col + 1).toString());
                                    String rdfid = ((LinkedList<?>) inputXLSdata.get(row)).get(col).toString();
                                    //System.out.println(row);
                                    //System.out.println(rdfid);
                                    String object = ((LinkedList<?>) inputXLSdata.get(row)).get(col + 1).toString();

                                    //Add triples to the model

                                    //Add the Class type
                                    if (rdfid.startsWith("urn:uuid:")) {
                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                    } else {
                                        if (rdfid.startsWith("http://")) {
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                        } else {
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                        }
                                    }


                                    switch (propertyType) {
                                        case "Attribute" -> { //add literal
                                            if (rdfid.startsWith("urn:uuid:")) {
                                                if (object.contains("LangXMLTag:")) {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createLangLiteral(object.split("LangXMLTag:", 2)[0], object.split("LangXMLTag:", 2)[1])));
                                                } else {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                                }
                                            } else {
                                                if (rdfid.startsWith("http://")) {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                                } else {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                                }
                                            }
                                        }
                                        case "Association" -> { //add resource
                                            if (rdfid.startsWith("urn:uuid:")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                            } else {
                                                if (rdfid.startsWith("http://")) {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                                } else {
                                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                                }
                                            }
                                        }
                                        case "Enumeration" -> //add enum
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createResource(object)));
                                    }

                                }
                            }
                        }
                    }


                    //save xml

                    if (sheetnum == 0) {
                        Map<String, Model> profileModelMap = loadDataMap.get("profileModelMap");
                        //this is related to the save of the data
                        Set<Resource> rdfAboutList = new HashSet<>();
                        Set<Resource> rdfEnumList = new HashSet<>();
                        originalNameInParts = FilenameUtils.getName(xmlfile.toString()).split("_", 2)[1].split(".xlsx", 2);


                        if ((boolean) saveProperties.get("useAboutRules")) {
//                            if (profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
//                                rdfAboutList = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
//                            }
                            rdfAboutList = LoadRDFAbout(xmlBase);
                            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                        }

                        if ((boolean) saveProperties.get("useEnumRules")) {
//                            for (ResIterator ii = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
//                                    ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
//                                Resource resItem = ii.next();
//                                for (ResIterator j = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
//                                    Resource resItemProp = j.next();
//                                    rdfEnumList.add(resItemProp);
//                                }
//                            }
                            rdfEnumList = LoadRDFEnum(xmlBase);
                        }

                        if (saveProperties.containsKey("rdfAboutList")) {
                            saveProperties.replace("rdfAboutList", rdfAboutList);
                        } else {
                            saveProperties.put("rdfAboutList", rdfAboutList);
                        }
                        if (saveProperties.containsKey("rdfEnumList")) {
                            saveProperties.replace("rdfEnumList", rdfEnumList);
                        } else {
                            saveProperties.put("rdfEnumList", rdfEnumList);
                        }

                        //if (extension.equals("zip")) {
                        //    fileName = fileName.replace(".zip", ".xml");
                        // }

                    }
                    saveProperties.replace("filename", saveProperties.get("filename").toString().split(".xml", 2)[0] + "_" + originalNameInParts[0] + ".xml");
                    InstanceDataFactory.saveInstanceData(model, saveProperties);
                    firstfile = 0;
                }
            }
        }
    }


    public static void generateDataFromXlsColumns(String xmlBase, Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag,
                                           Map<String,Boolean> inputData, Boolean shaclModelUnionFlag, String eqbdID, String tpbdID,Map<String,Object> saveProperties,
                                           boolean persistentEQflag) throws IOException {

        //this is to load profile data - this is needed for the export
        Map<String, Map> loadDataMap = ModelManipulationFactory.loadDataForIGMMulDateTime(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag);

        int firstfile = 1;
        for (File xmlfile : MainController.inputXLS) {
            // load input data xls
            ArrayList<Object> inputXLSdata;
            File excel = new File(xmlfile.toString());
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            int Num = book.getNumberOfSheets();

            String[] originalNameInParts = new String[0];
            Map<String,String> prefmap = new HashMap<>();

            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                XSSFSheet sheet = book.getSheetAt(sheetnum);
                String sheetname = sheet.getSheetName();
                if (sheetname.equals("Config")){
                    ArrayList<Object> inputXLSdataConfig = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);
                    for (Object o : inputXLSdataConfig) {
                        String yesno = ((LinkedList<?>) o).get(2).toString();
                        if (yesno.equals("Yes")) {
                            String pref = ((LinkedList<?>) o).get(0).toString();
                            String ns = ((LinkedList<?>) o).get(1).toString();
                            prefmap.putIfAbsent(pref, ns);
                        }
                    }
                    break;
                }

            }

            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                XSSFSheet sheet = book.getSheetAt(sheetnum);
                String sheetname = sheet.getSheetName();
                if (!sheetname.equals("Config")) {
                    saveProperties.put("filename", sheetname + ".xml");
                    if (sheetnum == 0 && firstfile == 1) {
                        saveProperties.put("useFileDialog", true);
                    } else {
                        saveProperties.put("useFileDialog", false);
                        saveProperties.put("fileFolder", MainController.prefs.get("LastWorkingFolder", ""));
                    }
                    inputXLSdata = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);


                    Model model = ModelFactory.createDefaultModel();
                    model.setNsPrefixes(prefmap);
//                model.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
//                model.setNsPrefix("nc", "http://entsoe.eu/ns/nc#");
//                //model.setNsPrefix("cims", MainController.prefs.get("cimsNamespace",""));
//                model.setNsPrefix("rdf", RDF.uri);
//                //model.setNsPrefix("owl", OWL.NS);
//                model.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
//                //model.setNsPrefix("xsd", XSD.NS);
//                model.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
//                model.setNsPrefix("dcat", "http://www.w3.org/ns/dcat#");
//                model.setNsPrefix("dcterms", "http://purl.org/dc/terms/#");
//                model.setNsPrefix("eumd", "http://entsoe.eu/ns/Metadata-European#");
//                model.setNsPrefix("prov", "http://www.w3.org/ns/prov#");
//                model.setNsPrefix("time", "http://www.w3.org/2006/time#");
//                model.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");

                    //here the new way of template TODO finish it, but make the template first
                    for (int col = 0; col < inputXLSdata.size(); col++) {
                        String classURI = ((LinkedList<?>) inputXLSdata.get(col)).getFirst().toString();
                        String classNS = ResourceFactory.createResource(classURI).asResource().getNameSpace();
                        String propertyURI = ((LinkedList<?>) inputXLSdata.get(col)).get(1).toString();
                        String propertyType = ((LinkedList<?>) inputXLSdata.get(col)).get(2).toString();
                        String datatype = ((LinkedList<?>) inputXLSdata.get(col)).get(3).toString();
                        String multiplicity = ((LinkedList<?>) inputXLSdata.get(col)).get(4).toString();
                        if (((LinkedList<?>) inputXLSdata.get(col)).size() > 5) {
                            for (int row = 5; row < ((LinkedList<?>) inputXLSdata.get(col)).size(); col += 2) { //TODO below to be revised
                                //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col).toString());
                                //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col + 1).toString());
                                String rdfid = ((LinkedList<?>) inputXLSdata.get(row)).get(col).toString();
                                //System.out.println(row);
                                //System.out.println(rdfid);
                                String object = ((LinkedList<?>) inputXLSdata.get(row)).get(col + 1).toString();

                                //Add triples to the model

                                //Add the Class type
                                if (rdfid.startsWith("urn:uuid:")) {
                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                } else {
                                    if (rdfid.startsWith("http://")) {
                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                    } else {
                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                                    }
                                }


                                switch (propertyType) {
                                    case "Attribute" -> { //add literal
                                        if (rdfid.startsWith("urn:uuid:")) {
                                            if (object.contains("LangXMLTag:")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createLangLiteral(object.split("LangXMLTag:", 2)[0], object.split("LangXMLTag:", 2)[1])));
                                            } else {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                            }
                                        } else {
                                            if (rdfid.startsWith("http://")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                            } else {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                            }
                                        }
                                    }
                                    case "Association" -> { //add resource
                                        if (rdfid.startsWith("urn:uuid:")) {
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                        } else {
                                            if (rdfid.startsWith("http://")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                            } else {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                            }
                                        }
                                    }
                                    case "Enumeration" -> //add enum
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createResource(object)));
                                }

                            }
                        }

                    }
                    //end new way of template

                    //save xml

                    if (sheetnum == 0) {
                        Map<String, Model> profileModelMap = loadDataMap.get("profileModelMap");
                        //this is related to the save of the data
                        Set<Resource> rdfAboutList = new HashSet<>();
                        Set<Resource> rdfEnumList = new HashSet<>();
                        originalNameInParts = FilenameUtils.getName(xmlfile.toString()).split("_", 2)[1].split(".xlsx", 2);


                        if ((boolean) saveProperties.get("useAboutRules")) {
//                            if (profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
//                                rdfAboutList = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
//                            }
                            rdfAboutList = LoadRDFAbout(xmlBase);
                            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                        }

                        if ((boolean) saveProperties.get("useEnumRules")) {
//                            for (ResIterator ii = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
//                                    ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
//                                Resource resItem = ii.next();
//                                for (ResIterator j = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
//                                    Resource resItemProp = j.next();
//                                    rdfEnumList.add(resItemProp);
//                                }
//                            }
                            rdfEnumList = LoadRDFEnum(xmlBase);
                        }

                        if (saveProperties.containsKey("rdfAboutList")) {
                            saveProperties.replace("rdfAboutList", rdfAboutList);
                        } else {
                            saveProperties.put("rdfAboutList", rdfAboutList);
                        }
                        if (saveProperties.containsKey("rdfEnumList")) {
                            saveProperties.replace("rdfEnumList", rdfEnumList);
                        } else {
                            saveProperties.put("rdfEnumList", rdfEnumList);
                        }

                        //if (extension.equals("zip")) {
                        //    fileName = fileName.replace(".zip", ".xml");
                        // }

                    }
                    saveProperties.replace("filename", saveProperties.get("filename").toString().split(".xml", 2)[0] + "_" + originalNameInParts[0] + ".xml");
                    InstanceDataFactory.saveInstanceData(model, saveProperties);
                    firstfile = 0;
                }
            }
        }
    }


    public static void generateCommonData(List<File> file) throws IOException {
        Model model = util.ModelFactory.modelLoad(file, "", Lang.RDFXML,false);
        Map<String, String> prefixMap = model.getNsPrefixMap();
        String cimURI = prefixMap.get("cim");
        String nc = prefixMap.get("nc");
        String dcat = prefixMap.get("dcat");
        String dcterms = prefixMap.get("dcterms");
        String skos = prefixMap.get("skos");

        Model modelComData = ModelFactory.createDefaultModel();
        modelComData.setNsPrefixes(prefixMap);


        for (StmtIterator i = model.listStatements(null, RDF.type, (RDFNode) null); i.hasNext(); ) {
            Statement stmt = i.next();
            if (stmt.getObject().asResource().getNameSpace().equals(cimURI) || stmt.getObject().asResource().getNameSpace().equals(nc)) {
                //get the rdfid
                String rdfid = "http://delete.eu#_" + stmt.getSubject().getLocalName();
                Resource cimres = stmt.getObject().asResource();
                modelComData.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid),RDF.type,cimres));
                for (StmtIterator j = model.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                    Statement stmtProp = j.next();
                    if ((!stmtProp.getPredicate().getNameSpace().equals(dcat) && !stmtProp.getPredicate().getNameSpace().equals(dcterms) && !stmtProp.getPredicate().getNameSpace().equals(skos)) && !stmtProp.getPredicate().equals(RDF.type)){
                        if (stmtProp.getObject().isResource()) {
                            modelComData.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), stmtProp.getPredicate(), ResourceFactory.createResource("http://delete.eu#_" +stmtProp.getObject().asResource().getLocalName())));
                        }else{
                            modelComData.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), stmtProp.getPredicate(), stmtProp.getObject()));
                        }
                    }
                }
            }

        }



        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase","http://delete.eu#");
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", true);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");
        saveProperties.put("sortRDF","true");
        saveProperties.put("sortRDFprefix","false"); // if true the sorting is on the prefix, if false on the localName

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");

        saveProperties.replace("filename", "ConvertedCommonData.xml");
        InstanceDataFactory.saveInstanceData(modelComData, saveProperties);
    }
}

