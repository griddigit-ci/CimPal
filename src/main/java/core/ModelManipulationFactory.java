/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import application.MainController;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import util.ExcelTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

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


                    Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
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
                        String classURI = ((LinkedList<?>) inputXLSdata.get(row)).get(0).toString();
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
                                    case "Attribute": //add literal
                                        if (rdfid.startsWith("urn:uuid:")) {
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                        } else {
                                            if (rdfid.startsWith("http://")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                            } else {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                                            }
                                        }
                                        break;
                                    case "Association": //add resource
                                        if (rdfid.startsWith("urn:uuid:")) {
                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                        } else {
                                            if (rdfid.startsWith("http://")) {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                            } else {
                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                                            }
                                        }
                                        break;
                                    case "Enumeration": //add enum
                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createResource(object)));
                                        break;
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
                            if (profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
                                rdfAboutList = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
                            }
                            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                        }

                        if ((boolean) saveProperties.get("useEnumRules")) {
                            for (ResIterator ii = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
                                    ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
                                Resource resItem = ii.next();
                                for (ResIterator j = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
                                    Resource resItemProp = j.next();
                                    rdfEnumList.add(resItemProp);
                                }
                            }
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
}

