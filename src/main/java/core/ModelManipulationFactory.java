/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2022, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import application.MainController;
import customWriter.CustomRDFFormat;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;

public class ModelManipulationFactory {

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

            }else if (newBDModelMap.containsKey("Border-"+toTSOname+"-"+fromTSOname+".xml")){
                Model borderModel = newBDModelMap.get("Border-"+toTSOname+"-"+fromTSOname+".xml");
                //add the BoundaryPoint
                borderModel=addBP(instanceModelBD,borderModel,resItem);
                newBDModelMap.put("Border-"+toTSOname+"-"+fromTSOname+".xml",borderModel);

            }else{
                Model newBoderModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                newBoderModel.setNsPrefixes(instanceModelBD.getNsPrefixMap());
                //add the header statements
                Resource headerRes=ResourceFactory.createResource("urn:uuid:"+UUID.randomUUID());
                newBoderModel.add(ResourceFactory.createStatement(headerRes,RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                for (StmtIterator n = instanceModelBD.listStatements(new SimpleSelector(instanceModelBD.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).nextResource(), null,(RDFNode) null)); n.hasNext();) {
                Statement stmt = n.next();
                newBoderModel.add(ResourceFactory.createStatement(headerRes,stmt.getPredicate(),stmt.getObject()));
                }
                //add the BoundaryPoint
                newBoderModel=addBP(instanceModelBD,newBoderModel,resItem);
                //add the model to the map
                newBDModelMap.put("Border-"+fromTSOname+"-"+toTSOname+".xml",newBoderModel);
            }
        }


        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, profileModelMap);

    }

    //action menu item Tools -> Split Boundary and Reference data (CGMES v3.0)
    public static void SplitBoundaryAndRefData() throws IOException {


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

        List<String> keepInBD= new LinkedList<>();
        keepInBD.add("ConnectivityNode");
        keepInBD.add("BoundaryPoint");
        keepInBD.add("Line");
        keepInBD.add("Substation");
        keepInBD.add("VoltageLevel");
        keepInBD.add("Bay");
        keepInBD.add("Terminal");
        keepInBD.add("Junction");
        keepInBD.add("FullModel");

        Map<String,Model> newBDModelMap=new HashMap<>();

        //create the new model for BP
        Model newBoderModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        newBoderModel.setNsPrefix("cim","http://iec.ch/TC57/CIM100#");
        newBoderModel.setNsPrefix("eu","http://iec.ch/TC57/CIM100-European#");
        newBoderModel.setNsPrefix("md","http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newBoderModel.setNsPrefix("rdf","http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        //create the new model for ref data
        Model newRefModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        newRefModel.setNsPrefix("cim","http://iec.ch/TC57/CIM100#");
        newRefModel.setNsPrefix("eu","http://iec.ch/TC57/CIM100-European#");
        newRefModel.setNsPrefix("md","http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newRefModel.setNsPrefix("rdf","http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        assert instanceModelBD != null;
        Map<String, String> oldPrefix = instanceModelBD.getNsPrefixMap();
        int keepExtensions = 1; // keep extensions
        if (oldPrefix.containsKey("cgmbp")){
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setContentText("The Boundary Set includes non CIM (cgmbp) extensions. Do you want to keep them in the split Boundary Set?");
            alert.setHeaderText(null);
            alert.setTitle("Question - cgmbp extensions are present");
            ButtonType btnYes = new ButtonType("Yes");
            ButtonType btnNo = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(btnYes, btnNo);
            Optional<ButtonType> result = alert.showAndWait();

            if (result.get() == btnNo) {
                keepExtensions=0;
            }else{
                if (oldPrefix.containsKey("cgmbp")){
                    newBoderModel.setNsPrefix("cgmbp","http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                    newRefModel.setNsPrefix("cgmbp","http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                }
            }
        }


        for (StmtIterator i = instanceModelBD.listStatements(null,RDF.type,(RDFNode) null); i.hasNext(); ) {
            Statement stmt = i.next();
            if (keepExtensions==1) {
                if (keepInBD.contains(stmt.getObject().asResource().getLocalName())) {
                    for (StmtIterator k = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                        Statement stmtKeep = k.next();
                        newBoderModel.add(stmtKeep);
                        if (stmt.getObject().asResource().getLocalName().equals("FullModel")){
                            newRefModel.add(stmtKeep);
                        }
                    }
                } else {
                    for (StmtIterator r = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); r.hasNext(); ) {
                        Statement stmtRef = r.next();
                        newRefModel.add(stmtRef);
                    }
                }
            }else{
                if (!stmt.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")){
                    if (keepInBD.contains(stmt.getObject().asResource().getLocalName())) {
                        for (StmtIterator k = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                            Statement stmtKeep = k.next();
                            if (!stmtKeep.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                                newBoderModel.add(stmtKeep);
                                if (stmt.getObject().asResource().getLocalName().equals("FullModel")){
                                    newRefModel.add(stmtKeep);
                                }
                            }
                        }
                    } else {
                        for (StmtIterator r = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); r.hasNext(); ) {
                            Statement stmtRef = r.next();
                            if (!stmtRef.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                                newRefModel.add(stmtRef);
                            }
                        }
                    }
                }
            }
        }


        newBDModelMap.put("BoundaryData.xml",newBoderModel);
        newBDModelMap.put("ReferenceData.xml",newRefModel);
        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, profileModelMap);

    }

    //action menu item Tools -> Convert CGMES v2.4 Boundary Set to CGMES v3.0
    public static void ConvertBoundarySetCGMESv2v3() throws IOException {


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
        //Map<String,Model> conversionInstruction=new HashMap<>();

        // load all profile models
        Map<String,Model> profileModelMap = null;


        loadDataMap.put("profileDataMap",profileDataMap);
        loadDataMap.put("profileDataMapAsModel",profileDataMapAsModel);
        loadDataMap.put("profileModelMap",profileModelMap);
        loadDataMap.put("conversionInstruction",profileModelMap);


        /*//select file
        FileChooser filechooserC = new FileChooser();
        filechooserC.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Conversion Instruction EQBD", "*.rdf","*.xml", "*.ttl"));
        filechooserC.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        File fileC=null;


        try {
            fileC = filechooserC.showOpenDialog(null);
        }catch (Exception e) {
            filechooserC.setInitialDirectory(new File("C:/"));
            fileC = filechooserC.showOpenDialog(null);
        }


        Model modelComversionInstruction=null;
        if (fileC != null ) {// the file is selected

            MainController.prefs.put("LastWorkingFolder", fileC.getParent());
            List modelFiles = new LinkedList();
            modelFiles.add(fileC);

            Lang rdfSourceFormat;
            int extdot=fileC.getName().lastIndexOf(".");

            switch (fileC.getName().substring(extdot)) {
                case ".rdf":
                case ".xml":
                    rdfSourceFormat=Lang.RDFXML;
                    break;

                case ".ttl":
                    rdfSourceFormat=Lang.TURTLE;
                    break;

                case ".jsonld":
                    rdfSourceFormat=Lang.JSONLD;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + fileC.getName().substring(extdot));
            }

            // load all models
            modelComversionInstruction = util.ModelFactory.modelLoad(modelFiles,xmlBase,rdfSourceFormat);

        }*/


        //xmlBase = "http://iec.ch/TC57/2013/CIM-schema-cim16";
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

        //create the new model
        Model newBoderModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        newBoderModel.setNsPrefix("cim","http://iec.ch/TC57/CIM100#");
        newBoderModel.setNsPrefix("eu","http://iec.ch/TC57/CIM100-European#");
        newBoderModel.setNsPrefix("md","http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newBoderModel.setNsPrefix("rdf","http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        //check for extensions

        assert instanceModelBD != null;
        Map<String, String> oldPrefix = instanceModelBD.getNsPrefixMap();
        int keepExtensions = 1; // keep extensions
        if (oldPrefix.containsKey("cgmbp")){
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setContentText("The Boundary Set includes non CIM (cgmbp) extensions. Do you want to keep them in the converted Boundary Set?");
            alert.setHeaderText(null);
            alert.setTitle("Question - cgmbp extensions are present");
            ButtonType btnYes = new ButtonType("Yes");
            ButtonType btnNo = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(btnYes, btnNo);
            Optional<ButtonType> result = alert.showAndWait();

            if (result.get() == btnNo) {
                keepExtensions=0;
            }else{
                if (oldPrefix.containsKey("cgmbp")){
                    newBoderModel.setNsPrefix("cgmbp","http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                }
            }
        }

        //conversion process
        String cim17NS="http://iec.ch/TC57/CIM100#";
        String cim16NS="http://iec.ch/TC57/2013/CIM-schema-cim16#";
        String euNS="http://iec.ch/TC57/CIM100-European#";
        List<String> skipList= new LinkedList<>();
        skipList.add("ConnectivityNode.boundaryPoint");
        List<String> getMRID= new LinkedList<>();
        getMRID.add("ConnectivityNode");
        getMRID.add("Line");
        getMRID.add("EnergySchedulingType");
        getMRID.add("GeographicalRegion");
        getMRID.add("SubGeographicalRegion");
        getMRID.add("Terminal");
        getMRID.add("Substation");
        getMRID.add("BaseVoltage");
        getMRID.add("VoltageLevel");
        getMRID.add("Bay");
        getMRID.add("Junction");

        Property mrid=ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");


        for (StmtIterator i = instanceModelBD.listStatements(new SimpleSelector(null, RDF.type, (RDFNode) null)); i.hasNext(); ) { // loop on all classes
            Statement stmt = i.next();

            //add the header statements
            if (stmt.getObject().asResource().getLocalName().equals("FullModel")) {
                for (StmtIterator n = instanceModelBD.listStatements(new SimpleSelector(stmt.getSubject(), null,(RDFNode) null)); n.hasNext();) {
                    Statement stmtH = n.next();
                    if (stmtH.getPredicate().getLocalName().equals("Model.profile")){
                        newBoderModel.add(ResourceFactory.createStatement(stmtH.getSubject(),stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0")));
                    }else {
                        newBoderModel.add(stmtH);
                    }
                }
            }else {
                Resource newSub;
                Property newPre;
                RDFNode newObj;
                Resource newBPres=null;

                if (stmt.getObject().asResource().getLocalName().equals("ConnectivityNode")) {
                    //Create BoundaryPoint
                    String uuidBP = String.valueOf(UUID.randomUUID());
                    newBPres = ResourceFactory.createResource(euNS + "_" + uuidBP);
                    RDFNode euBP = ResourceFactory.createProperty(euNS, "BoundaryPoint");
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, RDF.type, euBP));
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, mrid, ResourceFactory.createPlainLiteral(uuidBP)));
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, ResourceFactory.createProperty(euNS,"BoundaryPoint.ConnectivityNode"), ResourceFactory.createProperty(stmt.getSubject().toString())));
                }

                int addmrid=1;

                for (StmtIterator a = instanceModelBD.listStatements(new SimpleSelector(stmt.getSubject(), null, (RDFNode) null)); a.hasNext(); ) { // loop on all attributes
                    Statement stmtA = a.next();
                    if (!skipList.contains(stmtA.getPredicate().getLocalName())) {
                        if (stmtA.getSubject().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                            newSub = rebaseResource(stmtA.getSubject(), cim17NS);
                        } else if (stmtA.getSubject().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                            newSub = rebaseResource(stmtA.getSubject(), euNS);
                        } else {
                            newSub = stmtA.getSubject();
                        }
                        if (stmtA.getPredicate().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                            newPre = rebaseProperty(stmtA.getPredicate(), cim17NS);
                        } else if (stmtA.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                            newPre = rebaseProperty(stmtA.getPredicate(), euNS);
                        } else {
                            newPre = stmtA.getPredicate();
                        }
                        if (stmtA.getObject().isResource()) {
                            if (stmtA.getObject().asResource().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                                newObj = rebaseRDFNode(stmtA.getObject(), cim17NS);
                            } else if (stmtA.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                                newObj = rebaseRDFNode(stmtA.getObject(), euNS);
                            } else {
                                newObj = stmtA.getObject();
                            }
                        } else {
                            newObj = stmtA.getObject();
                        }
                        if (stmt.getObject().asResource().getLocalName().equals("ConnectivityNode")) {
                            String stmtAPredicate=stmtA.getPredicate().getLocalName();
                            switch (stmtAPredicate) {
                                case "ConnectivityNode.toEndName":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndName");
                                    newSub=newBPres;
                                    break;
                                case "ConnectivityNode.fromEndName":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndName");
                                    newSub=newBPres;
                                    break;
                                case "ConnectivityNode.toEndNameTso":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndNameTso");
                                    newSub=newBPres;
                                    break;
                                case "ConnectivityNode.toEndIsoCode":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndIsoCode");
                                    newSub=newBPres;
                                    break;
                                case "ConnectivityNode.fromEndIsoCode":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndIsoCode");
                                    newSub=newBPres;
                                    break;
                                case "ConnectivityNode.fromEndNameTso":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndNameTso");
                                    newSub=newBPres;
                                    break;
                                case "IdentifiedObject.name":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.name");
                                    newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                    newSub=newBPres;
                                    break;
                                case "IdentifiedObject.description":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.description");
                                    newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                    newSub=newBPres;
                                    Resource lineSub = instanceModelBD.getRequiredProperty(stmtA.getSubject(),ResourceFactory.createProperty(cim16NS,"ConnectivityNode.ConnectivityNodeContainer")).getObject().asResource();
                                    String lineDesc = instanceModelBD.getRequiredProperty(lineSub,ResourceFactory.createProperty(cim16NS,"IdentifiedObject.description")).getObject().toString();
                                    if (lineDesc.contains("HVDC")){
                                        newBoderModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(euNS,"BoundaryPoint.isDirectCurrent"), ResourceFactory.createPlainLiteral("true")));
                                    }
                                    break;
                                case "IdentifiedObject.shortName":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.shortName");
                                    newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                    newSub=newBPres;
                                    break;
                                case "IdentifiedObject.energyIdentCodeEic":
                                    newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.energyIdentCodeEic");
                                    newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                    newSub=newBPres;
                                    break;
                            }
                        }


                        newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                    }
                    if (getMRID.contains(stmt.getObject().asResource().getLocalName()) && addmrid==1){
                        newBoderModel.add(ResourceFactory.createStatement(stmt.getSubject(), mrid, ResourceFactory.createPlainLiteral(stmt.getSubject().getLocalName().substring(1))));
                        addmrid=0;
                    }
                }

            }
            /*//process ConnectivityNode
            if (stmt.getObject().asResource().getLocalName().equals("ConnectivityNode")){
                //Create BoundaryPoint
                String uuidBP= String.valueOf(UUID.randomUUID());
                Resource newBPres=ResourceFactory.createResource("http://iec.ch/TC57/CIM100#"+"_"+uuidBP);
                RDFNode euBP=ResourceFactory.createProperty(modelComversionInstruction.getNsPrefixURI("eu"),"BoundaryPoint");
                newBoderModel.add(ResourceFactory.createStatement(newBPres,RDF.type,euBP));
                newBoderModel.add(ResourceFactory.createStatement(newBPres,ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID"),ResourceFactory.createPlainLiteral(uuidBP)));

                //check attributes of ConnectivityNode
                for (StmtIterator c = instanceModelBD.listStatements(new SimpleSelector(stmt.getSubject(), null, (RDFNode) null)); c.hasNext();) {
                    Statement stcn = c.next();
                    if (modelComversionInstruction.contains(stcn.getPredicate(),ResourceFactory.createProperty("http://griddigit.eu/CGMES/ConversionInstruction/1.0#change"))){
                        if (modelComversionInstruction.contains(ResourceFactory.createStatement(stcn.getPredicate(),ResourceFactory.createProperty("http://griddigit.eu/CGMES/ConversionInstruction/1.0#change"),ResourceFactory.createPlainLiteral("maAndDelete"))) && modelComversionInstruction.contains(stcn.getPredicate(),OWL2.sameAs)) {
                            String newres=modelComversionInstruction.getRequiredProperty(stcn.getPredicate(),OWL2.sameAs).getObject().toString();
                            //add the new attribute
                            newBoderModel.add(ResourceFactory.createStatement(newBPres, ResourceFactory.createProperty(newres), stcn.getObject()));
                        }
                    }else{
                        if (stcn.getPredicate().asResource().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")){
                            newBoderModel.add(ResourceFactory.createStatement(ResourceFactory.createResource("http://iec.ch/TC57/CIM100#" + stcn.getSubject().getLocalName()), ResourceFactory.createProperty(modelComversionInstruction.getNsPrefixURI("cim17"),stcn.getPredicate().getLocalName()), stcn.getObject()));
                        }else {
                            newBoderModel.add(ResourceFactory.createStatement(ResourceFactory.createResource("http://iec.ch/TC57/CIM100#" + stcn.getSubject().getLocalName()), stcn.getPredicate(), stcn.getObject()));
                        }
                    }
                }


                for (StmtIterator k = modelComversionInstruction.listStatements(new SimpleSelector(null, RDFS.domain, euBP)); k.hasNext();){
                    Statement stbp = k.next();
                    if (modelComversionInstruction.contains(stbp.getSubject(), OWL2.sameAs)) {
                        newBoderModel.add(ResourceFactory.createStatement(newBPres, (Property) stbp.getSubject(), (RDFNode) instanceModelBD.getRequiredProperty(stmt.getSubject(), (Property) modelComversionInstruction.getRequiredProperty(stbp.getSubject(), OWL2.sameAs).getObject())));
                    }else{
                        if (modelComversionInstruction.contains(stbp.getSubject(), RDFS.range)) {
                            if (modelComversionInstruction.getRequiredProperty(stbp.getSubject(), RDFS.range).getObject().asResource().getLocalName().equals("ConnectivityNode")) {
                                newBoderModel.add(ResourceFactory.createStatement(newBPres, (Property) stbp.getSubject(), ResourceFactory.createResource("http://iec.ch/TC57/CIM100#"+stmt.getSubject().getLocalName())));
                            }
                        }
                    }
                }



            }*/


        }

        //filter extensions
        List<Statement> StmtDeleteList = new LinkedList<>();
        int deleteClass;
        if (keepExtensions==0){
            for (StmtIterator i = newBoderModel.listStatements(new SimpleSelector(null, RDF.type, (RDFNode) null)); i.hasNext(); ) { // loop on all classes
                Statement stmt = i.next();
                deleteClass=0;
                if (stmt.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")){
                    StmtDeleteList.add(stmt);
                    deleteClass=1;
                }
                for (StmtIterator a = newBoderModel.listStatements(new SimpleSelector(stmt.getSubject(), null, (RDFNode) null)); a.hasNext(); ) { // loop on all attributes
                    Statement stmtA = a.next();
                    if (deleteClass==1) {
                        StmtDeleteList.add(stmtA);
                    }else{
                        if (stmtA.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                            StmtDeleteList.add(stmtA);
                        }
                    }
                }
            }
            newBoderModel.remove(StmtDeleteList);
        }



        //add the model to the map
        newBDModelMap.put("ConvertedBoundaryCGMESv3"+".xml",newBoderModel);



        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, profileModelMap);

    }


    //add BP
    private static Model addBP(Model modelSource, Model newModel, Resource resItem) {
        for (StmtIterator bp = modelSource.listStatements(new SimpleSelector(resItem, null,(RDFNode) null)); bp.hasNext();) {
            Statement stmt = bp.next();
            newModel.add(stmt);
            // Add ConnectivityNode
            if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                for (StmtIterator cn = modelSource.listStatements(new SimpleSelector(stmt.getObject().asResource(), null,(RDFNode) null)); cn.hasNext();) {
                    Statement stmtcn = cn.next();
                    newModel.add(stmtcn);
                    //Add Line container
                    if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                        for (StmtIterator con = modelSource.listStatements(new SimpleSelector(stmtcn.getObject().asResource(), null,(RDFNode) null)); con.hasNext();) {
                            Statement stmtcon = con.next();
                            newModel.add(stmtcon);
                        }
                    }
                }
            }
        }
        return newModel;
    }


    //Replace namespace
    private static Property rebaseProperty(Property prop, String newBase)  {

        return ResourceFactory.createProperty(newBase+prop.getLocalName());
    }

    //Replace namespace
    private static RDFNode rebaseRDFNode(RDFNode prop, String newBase)  {

        return ResourceFactory.createProperty(newBase+prop.asResource().getLocalName());
    }

    //Replace namespace
    private static Resource rebaseResource(Resource res, String newBase)  {

        return ResourceFactory.createResource(newBase+res.getLocalName());
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

