/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.core;

import eu.griddigit.cimpal.application.MainController;
import eu.griddigit.cimpal.customWriter.CustomRDFFormat;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import eu.griddigit.cimpal.util.ExcelTools;

import java.io.*;
import java.util.*;

public class ModelManipulationFactory {

    public static Map<String, Map> loadDataForIGMMulDateTime(String xmlBase, Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag, Map<String, Boolean> inputData, Boolean shaclModelUnionFlag) throws IOException {

        Map<String, Map> loadDataMap = new HashMap<>();
        Lang rdfProfileFormat = null;

        List<File> modelFiles = new LinkedList<>();
        //if (!profileModelUnionFlag && MainController.rdfProfileFile!=null) {
        // modelFiles.add(MainController.rdfProfileFile);
        // }else{
        modelFiles = MainController.rdfProfileFileList;
        //}
        ArrayList<Object> profileData = null;
        Model baseInstanceModel;

        Map<String, ArrayList<Object>> profileDataMap = new HashMap<>();
        Map<String, Model> profileDataMapAsModel = new HashMap<>();

        // load all profile models
        Map<String, Model> profileModelMap = null;
        if (inputData.get("rdfs")) {
            profileModelMap = InstanceDataFactory.modelLoad(modelFiles, xmlBase, rdfProfileFormat, false);


            String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
            String rdfNs = MainController.prefs.get("cimsNamespace", "");

            //make the profile data per profile

            for (Map.Entry<String, Model> entry : profileModelMap.entrySet()) {
                MainController.shapesOnAbstractOption = 0;
                profileData = ShaclTools.constructShapeData(entry.getValue(), rdfNs, concreteNs);
                profileDataMap.put(entry.getKey(), profileData);
                profileDataMapAsModel.put(entry.getKey(), ShaclTools.profileDataMapAsModelTemp);
            }
        }

        loadDataMap.put("profileDataMap", profileDataMap);
        loadDataMap.put("profileDataMapAsModel", profileDataMapAsModel);
        loadDataMap.put("profileModelMap", profileModelMap);

        return loadDataMap;
    }

    public static Set<Resource> LoadRDFAbout(String xmlBase) throws FileNotFoundException {
        Set<Resource> rdfAboutList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        } else {
            throw new FileNotFoundException("File not found for serialization.");
        }
        for (StmtIterator it = model.listStatements(null, RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase + "RdfAbout")) {
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    rdfAboutList.add(ResourceFactory.createResource(o_i.toString()));

                }
            }
        }
        return rdfAboutList;
    }

    public static Model LoadSHACLSHACL() {
        Model shaclModel = ModelFactory.createDefaultModel();
        InputStream inputStream = InstanceDataFactory.class.getResourceAsStream("/shaclttl/shacl-shaclFixed.ttl");

        if (inputStream != null) {
            RDFDataMgr.read(shaclModel, inputStream, "", Lang.TURTLE);
        } else {
            try {
                throw new FileNotFoundException("File not found for shacl validation.");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return shaclModel;
    }

    public static Set<Resource> LoadRDFEnum(String xmlBase) throws FileNotFoundException {
        Set<Resource> RdfEnumList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        } else {
            throw new FileNotFoundException("File not found for serialization.");
        }

        for (StmtIterator it = model.listStatements(null, RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase + "RdfEnum")) {
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    RdfEnumList.add(ResourceFactory.createResource(o_i.toString()));
                }
            }
        }
        return RdfEnumList;
    }

    public static void generateDataFromXls(String xmlBase, Map<String, Object> saveProperties) throws IOException {

        //this is to load profile data - this is needed for the export
        //Map<String, Map> loadDataMap = ModelManipulationFactory.loadDataForIGMMulDateTime(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag);

        int firstfile = 1;
        for (File xmlfile : MainController.inputXLS) {
            // load input data xls
            ArrayList<Object> inputXLSdata;
            File excel = new File(xmlfile.toString());
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            int Num = book.getNumberOfSheets();

            String[] originalNameInParts = new String[0];
            Map<String, String> prefmap = new HashMap<>();

            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                XSSFSheet sheet = book.getSheetAt(sheetnum);
                String sheetname = sheet.getSheetName();
                if (sheetname.equals("Config")) {
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
                        //Map<String, Model> profileModelMap = loadDataMap.get("profileModelMap");
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

    public static void generateDataFromXlsV2(String xmlBase, File xmlfile, Map<String, Object> saveProperties) throws Exception {
        ArrayList<Object> headerXlsData = null;
        String headerClassName = "";
        Map<String, ArrayList<Object>> classesXlsData = new HashMap<>();
        Map<String, String> prefMap = new HashMap<>();
        int dataStartFrom = (int) saveProperties.get("dataStartsFrom");

        FileInputStream fis = new FileInputStream(xmlfile);
        XSSFWorkbook book = new XSSFWorkbook(fis);

        // getting the data from the config sheet
        XSSFSheet configSheet = book.getSheet("Config");
        if (configSheet != null) {
            ArrayList<Object> inputXLSDataConfig = ExcelTools.importXLSX(xmlfile.toString(), book.getSheetIndex(configSheet));
            inputXLSDataConfig.removeFirst();
            for (Object o : inputXLSDataConfig) {
                // getting namespaces
                if (((LinkedList<?>) o).size() >= 3) {
                    String yesno = ((LinkedList<?>) o).get(2).toString();
                    if (yesno.equals("Yes")) {
                        String pref = ((LinkedList<?>) o).get(0).toString();
                        String ns = ((LinkedList<?>) o).get(1).toString();
                        prefMap.putIfAbsent(pref, ns);
                    }
                }
                if (((LinkedList<?>) o).size() == 1){
                    // getting classes to print when exceeding namespace rows
                    String className = ((LinkedList<?>) o).getFirst().toString();
                    if (!className.isEmpty()) {
                        int classSheetIdx = book.getSheetIndex(className);
                        if (classSheetIdx != -1) {
                            // className = className.replace("|",":");
                            classesXlsData.putIfAbsent(className, ExcelTools.importXLSXnullSupport(xmlfile.toString(), classSheetIdx));
                        } else
                            throw new Exception("Couldn't find the sheet for class: " + className);
                    }
                    continue;
                }
                else if (((LinkedList<?>) o).size() < 4)
                    continue;

                // getting classes to print

                String className = ((LinkedList<?>) o).get(3).toString();
                if (!className.isEmpty()) {
                    int classSheetIdx = book.getSheetIndex(className);
                    if (classSheetIdx != -1) {
                        // className = className.replace("|",":");
                        classesXlsData.putIfAbsent(className, ExcelTools.importXLSXnullSupport(xmlfile.toString(), classSheetIdx));
                    } else
                        throw new Exception("Couldn't find the sheet for class: " + className);
                }
                // getting header class

                if (headerClassName.isEmpty()) {
                    headerClassName = ((LinkedList<?>) o).get(4).toString();
                    if (!headerClassName.isEmpty()) {
                        int headerSheetIdx = book.getSheetIndex(headerClassName);
                        if (headerSheetIdx != -1)
                            headerXlsData = ExcelTools.importXLSXnullSupport(xmlfile.toString(), headerSheetIdx);
                        else
                            throw new Exception("Couldn't find header class sheet.");
                    }
                }
            }
            if (headerXlsData == null)
                throw new Exception("Missing header class from config");
        } else {
            throw new Exception("Config sheet is missing from the xls data.");
        }

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefixes(prefMap);

        // Add header class
        int headerCols = ((LinkedList<?>) headerXlsData.get(1)).size();
        try {
            headerClassName = ((LinkedList<?>) headerXlsData.getFirst()).get(1).toString();
        } catch (NullPointerException e) {
            System.out.println("Missing header class name.");
            return;
        }
        // getting rdfid column
        int rdfidCol = -1;
        for (int i = 0; i < headerCols; i++) {
            if (((LinkedList<?>) headerXlsData.get(1)).get(i).equals("rdf:id")) {
                rdfidCol = i;
                break;
            }
        }
        if (rdfidCol == -1)
            throw new Exception("Header rdf:id missing from xls.");

        if (headerXlsData.size() > dataStartFrom) {

            String headRdfid = ((LinkedList<?>) headerXlsData.get(dataStartFrom)).get(rdfidCol).toString();
            Resource headRdfidRes = ResourceFactory.createResource(headRdfid);

            // put header data into the model
            // add header class
            String[] splitClassName = headerClassName.split(":");
            String headerClassWNS;
            try {
                String namePref = prefMap.get(splitClassName[0]);
                headerClassWNS = namePref + splitClassName[1];

                saveProperties.put("headerClassResource", namePref + splitClassName[1]);
            } catch (NullPointerException e) {
                throw new Exception("Missing prefix in config for class: " + headerClassName + "\nMissing prefix: " + splitClassName[0]);
            }
            model.add(ResourceFactory.createStatement(headRdfidRes, RDF.type, ResourceFactory.createProperty(headerClassWNS)));

            for (int i = 0; i < headerCols; i++) {
                if (i != rdfidCol && i < ((LinkedList<?>) headerXlsData.get(dataStartFrom)).size()) {
                    Object value = ((LinkedList<?>) headerXlsData.get(dataStartFrom)).get(i);
                    Object propertyURI_obj = ((LinkedList<?>) headerXlsData.get(1)).get(i);
                    if (value != null && propertyURI_obj != null) {
                        String propertyURI = propertyURI_obj.toString();
                        try {
                            String[] splitPropUri;
                            String propPref;
                            if (propertyURI.startsWith("http")) {
                                splitPropUri = propertyURI.split("#");
                                propPref = splitPropUri[0] + "#";
                            } else {
                                splitPropUri = propertyURI.split(":");
                                propPref = prefMap.get(splitPropUri[0]);
                            }

                            if (propPref == null || !prefMap.containsValue(propPref))
                                throw new Exception("Property URI not found: " + splitPropUri[0] + " in class: " + headerClassName);
                            propertyURI = propPref + splitPropUri[1];
                        } catch (NullPointerException e) {
                            throw new Exception("Missing prefix in config for property: " + propertyURI);
                        }
                        Property propertyURIProp = ResourceFactory.createProperty(propertyURI);
                        String propertyType = ((LinkedList<?>) headerXlsData.get(2)).get(i).toString();
                        String object = value.toString();
                        switch (propertyType) {
                            case "Literal" -> { //add literal
                                model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createPlainLiteral(object)));
                            }
                            case "Resource" -> { //add resource
                                if(object.startsWith("http")){
                                    model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createResource(object)));
                                }
                                else {
                                    model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createResource(xmlBase + "#" + object)));
                                }
                            }
                            case "Enumeration" -> { //add enum
                                if (object.split("#").length > 1 && object.startsWith("http")) { // if we have it as a http://...#
                                    model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createResource(object)));
                                } else if (object.split("#").length > 1) { // if it doesn't have http://
                                    model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createResource("http://" + object)));
                                } else { // if there is the prefix with ':'
                                    String[] objSplit = object.split(":", 2);
                                    String prefixUri = prefMap.get(objSplit[0]);
                                    model.add(ResourceFactory.createStatement(headRdfidRes, propertyURIProp, ResourceFactory.createResource(prefixUri + objSplit[1])));
                                }
                            }
                        }
                    }
                }
            }
        } else {
            System.out.println("Header Class is empty!");
        }

        // put other classes into the model
        for (Map.Entry<String, ArrayList<Object>> entry : classesXlsData.entrySet()) {
            ArrayList<Object> classXlsData = entry.getValue();
            String className;
            try {
                className = ((LinkedList<?>) classXlsData.getFirst()).get(1).toString();
            } catch (IndexOutOfBoundsException | NullPointerException e) {
                System.out.println("Missing class name at sheet: " + entry.getKey());
                continue;
            }

            int cols = ((LinkedList<?>) classXlsData.get(1)).size();
            rdfidCol = -1;
            for (int i = 0; i < cols; i++) {
                if (((LinkedList<?>) classXlsData.get(1)).get(i).equals("rdf:id")) {
                    rdfidCol = i;
                    break;
                }
            }
            if (rdfidCol == -1)
                throw new Exception("rdf:id missing at class sheet: " + className);

            String[] splitClassName = className.split(":");
            String classWNS;
            try {
                String propPref = prefMap.get(splitClassName[0]);
                classWNS = propPref + splitClassName[1];
            } catch (NullPointerException e) {
                throw new Exception("Missing prefix in config for class: " + className + "\nMissing prefix: " + splitClassName[0]);
            }

            for (int i = dataStartFrom; i < classXlsData.size(); i++) { // loop on the rows/class instance
                if (((LinkedList<?>) classXlsData.get(i)).get(rdfidCol) != null) {
                    String idxls = ((LinkedList<?>) classXlsData.get(i)).get(rdfidCol).toString();
                    String rdfid;
                    if (idxls.startsWith("http") || idxls.startsWith("urn:uuid")) {
                        rdfid = idxls;
                    }else {
                        rdfid = xmlBase + "#" + idxls;
                    }

                    Resource rdfidRes = ResourceFactory.createResource(rdfid);

                    model.add(ResourceFactory.createStatement(rdfidRes, RDF.type, ResourceFactory.createProperty(classWNS)));

                    for (int j = 0; j < cols; j++) {
                        if (j != rdfidCol && j < ((LinkedList<?>) classXlsData.get(i)).size()) {
                            Object value = ((LinkedList<?>) classXlsData.get(i)).get(j);
                            Object propertyURI_obj = ((LinkedList<?>) classXlsData.get(1)).get(j);
                            if (value != null && propertyURI_obj != null) {
                                String propertyURI = propertyURI_obj.toString();


                                try {
                                    String[] splitPropUri;
                                    String propPref;
                                    if (propertyURI.startsWith("http")) {
                                        splitPropUri = propertyURI.split("#");
                                        propPref = splitPropUri[0] + "#";
                                    } else {
                                        splitPropUri = propertyURI.split(":");
                                        propPref = prefMap.get(splitPropUri[0]);
                                    }

                                    if (propPref == null || !prefMap.containsValue(propPref))
                                        throw new Exception("Property URI not found: " + splitPropUri[0] + " in class: " + className);
                                    propertyURI = propPref + splitPropUri[1];
                                } catch (ArrayIndexOutOfBoundsException e) {
                                    throw new Exception("Invalid property URI format (namespace:property) at class: " + className + " property: " + propertyURI);
                                } catch (NullPointerException e) {
                                    throw new Exception("Missing prefix in config for property: " + propertyURI);
                                }
                                Property propertyURIProp = ResourceFactory.createProperty(propertyURI);
                                String propertyType = ((LinkedList<?>) classXlsData.get(2)).get(j).toString();
                                String object = value.toString();

                                switch (propertyType) {
                                    case "Literal" -> { //add literal
                                        model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createPlainLiteral(object)));
                                    }
                                    case "Resource" -> { //add resource
                                        if (object.startsWith("http")) {
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createProperty(object)));
                                        }else if (!object.contains("http") && object.contains(":")) {
                                            String[] objSplit = object.split(":", 2);
                                            String prefixUri = prefMap.get(objSplit[0]);
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createResource(prefixUri + objSplit[1])));
                                        }else {
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createProperty(xmlBase + "#" + object)));
                                        }
                                    }
                                    case "Enumeration" -> { //add enum
                                        if (object.split("#").length > 1 && object.startsWith("http")) { // if we have it as a http://...#
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createResource(object)));
                                        } else if (object.split("#").length > 1) { // if it doesn't have http://
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createResource("http://" + object)));
                                        } else { // if there is the prefix with ':'
                                            String[] objSplit = object.split(":", 2);
                                            String prefixUri = prefMap.get(objSplit[0]);
                                            model.add(ResourceFactory.createStatement(rdfidRes, propertyURIProp, ResourceFactory.createResource(prefixUri + objSplit[1])));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // save file
        Set<Resource> rdfAboutList = new HashSet<>();
        Set<Resource> rdfEnumList = new HashSet<>();


        if ((boolean) saveProperties.get("useAboutRules")) {
            rdfAboutList = LoadRDFAbout(xmlBase);
            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
        }

        if ((boolean) saveProperties.get("useEnumRules")) {
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

        saveProperties.replace("filename", saveProperties.get("filename").toString() + ".xml");
        saveProperties.put("fileFolder", MainController.prefs.get("LastWorkingFolder", ""));
        InstanceDataFactory.saveInstanceData(model, saveProperties);

        fis.close();
    }

//    public static void generateDataFromXlsColumns(String xmlBase, Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag,
//                                           Map<String,Boolean> inputData, Boolean shaclModelUnionFlag, String eqbdID, String tpbdID,Map<String,Object> saveProperties,
//                                           boolean persistentEQflag) throws IOException {
//
//        //this is to load profile data - this is needed for the export
//        Map<String, Map> loadDataMap = ModelManipulationFactory.loadDataForIGMMulDateTime(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag);
//
//        int firstfile = 1;
//        for (File xmlfile : MainController.inputXLS) {
//            // load input data xls
//            ArrayList<Object> inputXLSdata;
//            File excel = new File(xmlfile.toString());
//            FileInputStream fis = new FileInputStream(excel);
//            XSSFWorkbook book = new XSSFWorkbook(fis);
//            int Num = book.getNumberOfSheets();
//
//            String[] originalNameInParts = new String[0];
//            Map<String,String> prefmap = new HashMap<>();
//
//            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
//                XSSFSheet sheet = book.getSheetAt(sheetnum);
//                String sheetname = sheet.getSheetName();
//                if (sheetname.equals("Config")){
//                    ArrayList<Object> inputXLSdataConfig = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);
//                    for (Object o : inputXLSdataConfig) {
//                        String yesno = ((LinkedList<?>) o).get(2).toString();
//                        if (yesno.equals("Yes")) {
//                            String pref = ((LinkedList<?>) o).get(0).toString();
//                            String ns = ((LinkedList<?>) o).get(1).toString();
//                            prefmap.putIfAbsent(pref, ns);
//                        }
//                    }
//                    break;
//                }
//
//            }
//
//            for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
//                XSSFSheet sheet = book.getSheetAt(sheetnum);
//                String sheetname = sheet.getSheetName();
//                if (!sheetname.equals("Config")) {
//                    saveProperties.put("filename", sheetname + ".xml");
//                    if (sheetnum == 0 && firstfile == 1) {
//                        saveProperties.put("useFileDialog", true);
//                    } else {
//                        saveProperties.put("useFileDialog", false);
//                        saveProperties.put("fileFolder", MainController.prefs.get("LastWorkingFolder", ""));
//                    }
//                    inputXLSdata = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);
//
//
//                    Model model = ModelFactory.createDefaultModel();
//                    model.setNsPrefixes(prefmap);
////                model.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
////                model.setNsPrefix("nc", "http://entsoe.eu/ns/nc#");
////                //model.setNsPrefix("cims", MainController.prefs.get("cimsNamespace",""));
////                model.setNsPrefix("rdf", RDF.uri);
////                //model.setNsPrefix("owl", OWL.NS);
////                model.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
////                //model.setNsPrefix("xsd", XSD.NS);
////                model.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
////                model.setNsPrefix("dcat", "http://www.w3.org/ns/dcat#");
////                model.setNsPrefix("dcterms", "http://purl.org/dc/terms/#");
////                model.setNsPrefix("eumd", "http://entsoe.eu/ns/Metadata-European#");
////                model.setNsPrefix("prov", "http://www.w3.org/ns/prov#");
////                model.setNsPrefix("time", "http://www.w3.org/2006/time#");
////                model.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");
//
//                    //here the new way of template TODO finish it, but make the template first
//                    for (int col = 0; col < inputXLSdata.size(); col++) {
//                        String classURI = ((LinkedList<?>) inputXLSdata.get(col)).getFirst().toString();
//                        String classNS = ResourceFactory.createResource(classURI).asResource().getNameSpace();
//                        String propertyURI = ((LinkedList<?>) inputXLSdata.get(col)).get(1).toString();
//                        String propertyType = ((LinkedList<?>) inputXLSdata.get(col)).get(2).toString();
//                        String datatype = ((LinkedList<?>) inputXLSdata.get(col)).get(3).toString();
//                        String multiplicity = ((LinkedList<?>) inputXLSdata.get(col)).get(4).toString();
//                        if (((LinkedList<?>) inputXLSdata.get(col)).size() > 5) {
//                            for (int row = 5; row < ((LinkedList<?>) inputXLSdata.get(col)).size(); col += 2) { //TODO below to be revised
//                                //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col).toString());
//                                //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col + 1).toString());
//                                String rdfid = ((LinkedList<?>) inputXLSdata.get(row)).get(col).toString();
//                                //System.out.println(row);
//                                //System.out.println(rdfid);
//                                String object = ((LinkedList<?>) inputXLSdata.get(row)).get(col + 1).toString();
//
//                                //Add triples to the model
//
//                                //Add the Class type
//                                if (rdfid.startsWith("urn:uuid:")) {
//                                    model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
//                                } else {
//                                    if (rdfid.startsWith("http://")) {
//                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
//                                    } else {
//                                        model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
//                                    }
//                                }
//
//
//                                switch (propertyType) {
//                                    case "Attribute" -> { //add literal
//                                        if (rdfid.startsWith("urn:uuid:")) {
//                                            if (object.contains("LangXMLTag:")) {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createLangLiteral(object.split("LangXMLTag:", 2)[0], object.split("LangXMLTag:", 2)[1])));
//                                            } else {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
//                                            }
//                                        } else {
//                                            if (rdfid.startsWith("http://")) {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
//                                            } else {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
//                                            }
//                                        }
//                                    }
//                                    case "Association" -> { //add resource
//                                        if (rdfid.startsWith("urn:uuid:")) {
//                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
//                                        } else {
//                                            if (rdfid.startsWith("http://")) {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
//                                            } else {
//                                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
//                                            }
//                                        }
//                                    }
//                                    case "Enumeration" -> //add enum
//                                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createResource(object)));
//                                }
//
//                            }
//                        }
//
//                    }
//                    //end new way of template
//
//                    //save xml
//
//                    if (sheetnum == 0) {
//                        Map<String, Model> profileModelMap = loadDataMap.get("profileModelMap");
//                        //this is related to the save of the data
//                        Set<Resource> rdfAboutList = new HashSet<>();
//                        Set<Resource> rdfEnumList = new HashSet<>();
//                        originalNameInParts = FilenameUtils.getName(xmlfile.toString()).split("_", 2)[1].split(".xlsx", 2);
//
//
//                        if ((boolean) saveProperties.get("useAboutRules")) {
////                            if (profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
////                                rdfAboutList = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
////                            }
//                            rdfAboutList = LoadRDFAbout(xmlBase);
//                            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
//                        }
//
//                        if ((boolean) saveProperties.get("useEnumRules")) {

    /// /                            for (ResIterator ii = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
    /// /                                    ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
    /// /                                Resource resItem = ii.next();
    /// /                                for (ResIterator j = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
    /// /                                    Resource resItemProp = j.next();
    /// /                                    rdfEnumList.add(resItemProp);
    /// /                                }
    /// /                            }
//                            rdfEnumList = LoadRDFEnum(xmlBase);
//                        }
//
//                        if (saveProperties.containsKey("rdfAboutList")) {
//                            saveProperties.replace("rdfAboutList", rdfAboutList);
//                        } else {
//                            saveProperties.put("rdfAboutList", rdfAboutList);
//                        }
//                        if (saveProperties.containsKey("rdfEnumList")) {
//                            saveProperties.replace("rdfEnumList", rdfEnumList);
//                        } else {
//                            saveProperties.put("rdfEnumList", rdfEnumList);
//                        }
//
//                        //if (extension.equals("zip")) {
//                        //    fileName = fileName.replace(".zip", ".xml");
//                        // }
//
//                    }
//                    saveProperties.replace("filename", saveProperties.get("filename").toString().split(".xml", 2)[0] + "_" + originalNameInParts[0] + ".xml");
//                    InstanceDataFactory.saveInstanceData(model, saveProperties);
//                    firstfile = 0;
//                }
//            }
//        }
//    }
    public static void generateCommonData(List<File> file) throws IOException {
        Model model = eu.griddigit.cimpal.util.ModelFactory.modelLoad(file, "", Lang.RDFXML, false);
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
                modelComData.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, cimres));
                for (StmtIterator j = model.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                    Statement stmtProp = j.next();
                    if ((!stmtProp.getPredicate().getNameSpace().equals(dcat) && !stmtProp.getPredicate().getNameSpace().equals(dcterms) && !stmtProp.getPredicate().getNameSpace().equals(skos)) && !stmtProp.getPredicate().equals(RDF.type)) {
                        if (stmtProp.getObject().isResource()) {
                            modelComData.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), stmtProp.getPredicate(), ResourceFactory.createResource("http://delete.eu#_" + stmtProp.getObject().asResource().getLocalName())));
                        } else {
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
        saveProperties.put("xmlBase", "http://delete.eu#");
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", true);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");

        saveProperties.replace("filename", "ConvertedCommonData.xml");
        InstanceDataFactory.saveInstanceData(modelComData, saveProperties);
    }
}

