/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2022, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import application.MainController;
import customWriter.CustomRDFFormat;
import javafx.event.ActionEvent;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.util.StringUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import util.ExcelTools;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ModelManipulationFactory {

    private static Map<String,Model> baseInstanceModelMapOriginal;


    //action menu item Tools -> Split boundary per TSO border
    public static void SplitBoundaryPerBorder() throws IOException {


        Map<String, Map> loadDataMap= new HashMap<>();
        String xmlBase = "http://iec.ch/TC57/CIM100";
        //String xmlBase = "";

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;



        Map<String,ArrayList<Object>> profileDataMap=new HashMap<>();
        Map<String,Model> profileDataMapAsModel=new HashMap<>();

        // load all profile models
        Map<String,Model> profileModelMap = null;


        loadDataMap.put("profileDataMap",profileDataMap);
        loadDataMap.put("profileDataMapAsModel",profileDataMapAsModel);
        loadDataMap.put("profileModelMap",profileModelMap);

        // load base instance models
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Boundary equipment", "*.xml"));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File file;

        try {
            file = filechooser.showOpenDialog(null);
        } catch (Exception k){
            filechooser.setInitialDirectory(new File("C:\\\\"));
            file = filechooser.showOpenDialog(null);
        }
        List<File> baseInstanceModelFiles = new LinkedList<>();
        if (file != null) {// the file is selected

            baseInstanceModelFiles.add(file);

            Map<String,Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(baseInstanceModelFiles, xmlBase, null);
            loadDataMap.put("baseInstanceModelMap",baseInstanceModelMap);

        }
        Map<String,Model> instanceModelMap= loadDataMap.get("baseInstanceModelMap");
        Model instanceModelBD = null;
        //Model instanceModelBD = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (Map.Entry<String, Model> entry : instanceModelMap.entrySet()) {
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")) {
                if (entry.getKey().equals("EQBD")) {
                   instanceModelBD=entry.getValue();
                }
            }

        }

        Map<String,Model> newBDModelMap=new HashMap<>();

        assert instanceModelBD != null;
        for (ResIterator i = instanceModelBD.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint")); i.hasNext(); ) {
            Resource resItem = i.next();
            String fromTSOname = instanceModelBD.getRequiredProperty(resItem,ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint.fromEndNameTso")).getObject().toString();
            String toTSOname = instanceModelBD.getRequiredProperty(resItem,ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint.toEndNameTso")).getObject().toString();

            if (newBDModelMap.containsKey("Border-"+fromTSOname+"-"+toTSOname+".xml")){
                Model borderModel = newBDModelMap.get("Border-"+fromTSOname+"-"+toTSOname+".xml");
                //add the BoundaryPoint
                borderModel=addBP(instanceModelBD,borderModel,resItem);
                newBDModelMap.put("Border-"+fromTSOname+"-"+toTSOname+".xml",borderModel);
                /*for (StmtIterator bp = instanceModelBD.listStatements(new SimpleSelector(resItem,(Property) null,(RDFNode) null)); bp.hasNext();) {
                    Statement stmt = bp.next();
                    borderModel.add(stmt);
                    // Add ConnectivityNode
                    if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                        for (StmtIterator cn = instanceModelBD.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)); cn.hasNext();) {
                            Statement stmtcn = cn.next();
                            borderModel.add(stmtcn);
                            //Add Line container
                            if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                                for (StmtIterator con = instanceModelBD.listStatements(new SimpleSelector(stmtcn.getObject().asResource(),(Property) null,(RDFNode) null)); con.hasNext();) {
                                    Statement stmtcon = con.next();
                                    borderModel.add(stmtcon);
                                }
                            }
                        }
                    }
                }*/

            }else if (newBDModelMap.containsKey("Border-"+toTSOname+"-"+fromTSOname+".xml")){
                Model borderModel = newBDModelMap.get("Border-"+toTSOname+"-"+fromTSOname+".xml");
                //add the BoundaryPoint - same as for the previous if
                borderModel=addBP(instanceModelBD,borderModel,resItem);
                newBDModelMap.put("Border-"+toTSOname+"-"+fromTSOname+".xml",borderModel);
                /*for (StmtIterator bp = instanceModelBD.listStatements(new SimpleSelector(resItem,(Property) null,(RDFNode) null)); bp.hasNext();) {
                    Statement stmt = bp.next();
                    borderModel.add(stmt);
                    // Add ConnectivityNode
                    if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                        for (StmtIterator cn = instanceModelBD.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)); cn.hasNext();) {
                            Statement stmtcn = cn.next();
                            borderModel.add(stmtcn);
                            //Add Line container
                            if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                                for (StmtIterator con = instanceModelBD.listStatements(new SimpleSelector(stmtcn.getObject().asResource(),(Property) null,(RDFNode) null)); con.hasNext();) {
                                    Statement stmtcon = con.next();
                                    borderModel.add(stmtcon);
                                }
                            }
                        }
                    }
                }*/

            }else{
                Model newBoderModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                newBoderModel.setNsPrefixes(instanceModelBD.getNsPrefixMap());
                //add the header statements
                Resource headerRes=ResourceFactory.createResource("urn:uuid:"+UUID.randomUUID());
                newBoderModel.add(ResourceFactory.createStatement(headerRes,RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                for (StmtIterator n = instanceModelBD.listStatements(new SimpleSelector(instanceModelBD.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).nextResource(),(Property) null,(RDFNode) null)); n.hasNext();) {
                Statement stmt = n.next();
                newBoderModel.add(ResourceFactory.createStatement(headerRes,stmt.getPredicate(),stmt.getObject()));
                }
                //add the BoundaryPoint- same as for the previous if, but different model where the statements are added
                newBoderModel=addBP(instanceModelBD,newBoderModel,resItem);
                /*for (StmtIterator bp = instanceModelBD.listStatements(new SimpleSelector(resItem,(Property) null,(RDFNode) null)); bp.hasNext();) {
                    Statement stmt = bp.next();
                    newBoderModel.add(stmt);
                    // Add ConnectivityNode
                    if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                        for (StmtIterator cn = instanceModelBD.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)); cn.hasNext();) {
                            Statement stmtcn = cn.next();
                            newBoderModel.add(stmtcn);
                            //Add Line container
                            if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                                for (StmtIterator con = instanceModelBD.listStatements(new SimpleSelector(stmtcn.getObject().asResource(),(Property) null,(RDFNode) null)); con.hasNext();) {
                                    Statement stmtcon = con.next();
                                    newBoderModel.add(stmtcon);
                                }
                            }
                        }
                    }
                }*/
                //add the model to the map
                newBDModelMap.put("Border-"+fromTSOname+"-"+toTSOname+".xml",newBoderModel);
            }
        }


        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, profileModelMap);

    }

    //Save data
    private static Model addBP(Model modelSource, Model newModel, Resource resItem) throws IOException {
        for (StmtIterator bp = modelSource.listStatements(new SimpleSelector(resItem,(Property) null,(RDFNode) null)); bp.hasNext();) {
            Statement stmt = bp.next();
            newModel.add(stmt);
            // Add ConnectivityNode
            if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                for (StmtIterator cn = modelSource.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)); cn.hasNext();) {
                    Statement stmtcn = cn.next();
                    newModel.add(stmtcn);
                    //Add Line container
                    if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                        for (StmtIterator con = modelSource.listStatements(new SimpleSelector(stmtcn.getObject().asResource(),(Property) null,(RDFNode) null)); con.hasNext();) {
                            Statement stmtcon = con.next();
                            newModel.add(stmtcon);
                        }
                    }
                }
            }
        }
        return newModel;
    }


    //Save data
    public static void saveInstanceModelData(Map<String, Model> instanceDataModelMap, Map<String,Object> saveProperties, Map<String,Model> profileModelMap) throws IOException {

        boolean useFileDialog=(boolean) saveProperties.get("useFileDialog");
        if (!useFileDialog){
            DirectoryChooser folderchooser = new DirectoryChooser();
            folderchooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
            File file;

            try {
                file = folderchooser.showDialog(null);
            } catch (Exception k){
                folderchooser.setInitialDirectory(new File("C:\\\\"));
                file = folderchooser.showDialog(null);
            }
            saveProperties.replace("fileFolder", file);
        }

        for (Map.Entry<String, Model> entry : instanceDataModelMap.entrySet()) {

            //this is related to the save of the data
            Set<Resource> rdfAboutList = new HashSet<>();
            Set<Resource> rdfEnumList = new HashSet<>();
            if ((boolean) saveProperties.get("useAboutRules")) {
                if (profileModelMap!=null) {
                    if (profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
                        rdfAboutList = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
                    }
                }
                rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
            }

            if ((boolean) saveProperties.get("useEnumRules")) {
                if (profileModelMap!=null) {
                    for (ResIterator i = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
                            ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); i.hasNext(); ) {
                        Resource resItem = i.next();
                        for (ResIterator j = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
                            Resource resItemProp = j.next();
                            rdfEnumList.add(resItemProp);
                        }
                    }
                }
            }

            if (saveProperties.containsKey("rdfAboutList")) {
                saveProperties.replace("rdfAboutList", rdfAboutList);
            }else{
                saveProperties.put("rdfAboutList", rdfAboutList);
            }
            if (saveProperties.containsKey("rdfEnumList")) {
                saveProperties.replace("rdfEnumList", rdfEnumList);
            }else{
                saveProperties.put("rdfEnumList", rdfEnumList);
            }

            //if (MainController.newNameList.size()!=0){
            //    saveProperties.replace("filename", MainController.newNameList.get(entry.getKey()));
            //}else{
            saveProperties.replace("filename", entry.getKey());
            //}


            InstanceDataFactory.saveInstanceData(entry.getValue(), saveProperties);
        }
    }

}

