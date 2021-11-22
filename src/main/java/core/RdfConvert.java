/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import application.MainController;
import application.rdfDiffResultController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.*;
import util.CompareFactory;

import java.io.*;
import java.util.*;


public class RdfConvert {

    private static Model modelInheritance;

    //RDF conversion
    public static void rdfConversion(File file, List files, String sourceFormat, String targetFormat, String xmlBase,RDFFormat rdfFormat,
                                     String showXmlDeclaration, String showDoctypeDeclaration, String tab,String relativeURIs, Boolean modelUnionFlag,
                                     Boolean inheritanceOnly,Boolean inheritanceList, Boolean inheritanceListConcrete) throws IOException {

        Lang rdfSourceFormat;
        switch (sourceFormat) {
            case "RDF XML (.rdf or .xml)":
                rdfSourceFormat=Lang.RDFXML;
                break;

            case "RDF Turtle (.ttl)":
                rdfSourceFormat=Lang.TURTLE;
                break;

            case "JSON-LD (.jsonld)":
                rdfSourceFormat=Lang.JSONLD;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + sourceFormat);
        }
        List modelFiles = new LinkedList();
        if (modelUnionFlag==false && file!=null) {
            modelFiles.add(file);
        }else{
            modelFiles=files;
        }

        // load all models
        Model model = util.ModelFactory.modelLoad(modelFiles,xmlBase,rdfSourceFormat);

        //in case only inheritance related structure should be converted
        if(inheritanceOnly==true){
            model = modelInheritance(model,inheritanceList,inheritanceListConcrete);
        }


        String filename="";
        if (modelUnionFlag==false && file!=null) {
            filename=file.getName().split("\\.",2)[0];
        }else{
            filename="MultipleModels";
        }

        switch (targetFormat) {
            case "RDF XML (.rdf or .xml)":
                OutputStream outXML = fileSaveDialog("Save RDF XML for: "+filename, "RDF XML", "*.rdf");
                if (outXML!=null) {
                    try {
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("showXmlDeclaration", showXmlDeclaration);
                        properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                        //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                        properties.put("xmlbase", xmlBase);
                        properties.put("tab", tab);
                        //properties.put("prettyTypes",new Resource[] {ResourceFactory.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")});
                        properties.put("relativeURIs", relativeURIs);


                        // Put a properties object into the Context.
                        Context cxt = new Context();
                        cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                        org.apache.jena.riot.RDFWriter.create()
                                .base(xmlBase)
                                .format(rdfFormat)
                                .context(cxt)
                                .source(model)
                                .output(outXML);
                        if (inheritanceList==true) {
                            fileSaveDialogInheritance(filename+"Inheritance",xmlBase);
                        }
                    } finally {
                        outXML.close();
                    }
                }
                break;

            case "RDF Turtle (.ttl)":
                OutputStream outTTL = fileSaveDialog("Save RDF Turtle for: "+filename, "RDF Turtle", "*.ttl");
                if (outTTL!=null) {
                    try {
                        model.write(outTTL, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
                        if (inheritanceList==true) {
                            fileSaveDialogInheritance(filename+"Inheritance",xmlBase);
                        }
                    } finally {
                        outTTL.close();
                    }
                }
                break;

            case "JSON-LD (.jsonld)":
                OutputStream outJsonLD = fileSaveDialog("Save JSON-LD for: "+filename, "JSON-LD", "*.jsonld");
                if (outJsonLD!=null) {
                    try {
                        model.write(outJsonLD, RDFFormat.JSONLD.getLang().getLabel().toUpperCase(), xmlBase);
                        if (inheritanceList==true) {
                            fileSaveDialogInheritance(filename+"Inheritance",xmlBase);
                        }
                    } finally {
                        outJsonLD.close();
                    }
                }
                break;
        }
    }

    //File save dialog for inheritance
    private static void fileSaveDialogInheritance(String filename, String xmlBase) throws IOException {
        OutputStream outInt = fileSaveDialog("Save inheritance for: "+filename, "RDF Turtle", "*.ttl");
        try {
            modelInheritance.write(outInt, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
        } finally {
            outInt.close();
        }

    }

    //File save dialog
    private static OutputStream fileSaveDialog(String title, String extensionName, String extension) throws FileNotFoundException {
        File saveFile;
        FileChooser filechooserS = new FileChooser();
        filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(extensionName, extension));
        filechooserS.setInitialFileName(title.split(": ", 2)[1]);
        filechooserS.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        filechooserS.setTitle(title);
        saveFile = filechooserS.showSaveDialog(null);
        OutputStream out=null;
        if (saveFile!=null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            out = new FileOutputStream(saveFile);
        }
        return out;
    }


    //Creates another model that contains only inheritance related properties
    private static Model modelInheritance(Model model, Boolean inheritanceList, Boolean inheritanceListConcrete)  {
        Model modelProcessed = ModelFactory.createDefaultModel();
        modelProcessed.setNsPrefixes(model.getNsPrefixMap());
        modelInheritance = ModelFactory.createDefaultModel();
        modelInheritance.setNsPrefixes(model.getNsPrefixMap());
        modelInheritance.setNsPrefix("owl",OWL2.NS);

        for (StmtIterator i = model.listStatements(); i.hasNext(); ) {
            Statement stmt = i.next();
            if (inheritanceList==false) {
                if (stmt.getPredicate().equals(RDF.type) || stmt.getPredicate().equals(RDFS.subClassOf) || stmt.getPredicate().equals(RDFS.subPropertyOf) ||
                        stmt.getPredicate().equals(RDFS.domain) || stmt.getPredicate().equals(RDFS.range)) {
                    modelProcessed.add(stmt);
                }
            }else {
                if (stmt.getPredicate().equals(RDF.type) || stmt.getPredicate().equals(RDFS.subClassOf) || stmt.getPredicate().equals(RDFS.subPropertyOf) ||
                        stmt.getPredicate().equals(RDFS.domain) || stmt.getPredicate().equals(RDFS.range)) {
                    modelProcessed.add(stmt);
                    if (stmt.getPredicate().equals(RDF.type)) {
                        Resource stmtSubject = stmt.getSubject();
                        modelInheritance=inheritanceStructure(stmtSubject, stmtSubject, modelInheritance, model, inheritanceListConcrete);

                    }


                }
            }
        }

        return modelProcessed;
    }

    // Adds the inheritance structure in the model
    private static Model inheritanceStructure(Resource stmtSubject, Resource res, Model modelInheritance, Model model, Boolean inheritanceListConcrete) {

            for (ResIterator j = model.listSubjectsWithProperty(RDFS.subClassOf,res); j.hasNext(); ) {
                Resource resSub = j.next();
                //check if the class is concrete

                if (inheritanceListConcrete==true ) {
                    Boolean addConcrete=false;
                    if (model.listObjectsOfProperty(resSub,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype")).hasNext()) {
                        for (NodeIterator k = model.listObjectsOfProperty(resSub,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype")); k.hasNext(); ) {
                            RDFNode objC = k.next();
                            if (objC.isResource()) {
                                if (objC.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                                    addConcrete = true;
                                }
                            }
                        }
                    }//TODO add else if to support other ways to identify if the class is concrete
                    if (addConcrete) {
                        modelInheritance.add(stmtSubject, OWL2.members, resSub);
                        modelInheritance.add(stmtSubject, RDF.type, OWL2.Class);
                    }
                }else{
                    modelInheritance.add(stmtSubject, OWL2.members, resSub);
                    modelInheritance.add(stmtSubject, RDF.type, OWL2.Class);
                }
                modelInheritance=inheritanceStructure(stmtSubject, resSub, modelInheritance, model, inheritanceListConcrete);
            }


        return modelInheritance;
    }

    // convert CGMES xml to SKOS for the reference data
    public static void refDataConvert() throws IOException {

        // load the data
        // change the format
        // save it in xml


        String xmlBase = "http://iec.ch/TC57/CIM100";
        Map<String, RDFDatatype> dataTypeMap = new HashMap<>();


        //if the datatypes map is from RDFS - make union of RDFS and generate map
        if (MainController.IDmapList != null) {// the file is selected
            for (File item : MainController.IDmapList) {
                Properties properties = new Properties();
                properties.load(new FileInputStream(item.toString()));
                for (Object key : properties.keySet()) {
                    String value = properties.get(key).toString();
                    RDFDatatype valueRDFdatatype = DataTypeMaping.mapFromMapDefaultFile(value);
                    dataTypeMap.put(key.toString(), valueRDFdatatype);
                }
            }
        }
        // if model 1 is more that 1 zip or xml - merge

        Model model1single = null;

        Model model1 = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map prefixMap = model1.getNsPrefixMap();

        for (File item : MainController.IDModel1) {
            if (item.getName().toLowerCase().endsWith(".zip")) {

                model1single = util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);

                model1single = util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);

            }
            prefixMap.putAll(model1single.getNsPrefixMap());
            model1.add(model1single);
        }
        model1.setNsPrefixes(prefixMap);

        Model modelResult = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        modelResult.setNsPrefixes(prefixMap);
        modelResult.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        modelResult.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");
        modelResult.setNsPrefix("dc", "http://purl.org/dc/elements/1.1/");
        modelResult.setNsPrefix("dcterms", DCTerms.NS);


        Resource schemeRes = ResourceFactory.createResource("http://publications.europa.eu/resource/authority/baseVoltage");
        modelResult.add(schemeRes,RDF.type, SKOS.ConceptScheme  );//ResourceFactory.createProperty("http://www.w3.org/2004/02/skos/core#ConceptScheme")
        modelResult.add(schemeRes,OWL2.versionInfo, ResourceFactory.createPlainLiteral("22 Nov 2021") );
        modelResult.add(schemeRes,RDFS.label, ResourceFactory.createPlainLiteral("Base Voltage") );
        modelResult.add(schemeRes,SKOS.prefLabel, ResourceFactory.createPlainLiteral("Base Voltage") );


        for (StmtIterator i = model1.listStatements(new SimpleSelector((Resource) null , RDF.type, ResourceFactory.createProperty(xmlBase,"#BaseVoltage"))); i.hasNext(); ) {
            Statement stmtItem = i.next();

            //get the object of the BaseVoltage.nominalVoltage attribute
            String nominalVoltage= model1.listStatements(new SimpleSelector(stmtItem.getSubject() , ResourceFactory.createProperty(xmlBase,"#BaseVoltage.nominalVoltage"), (RDFNode) null)).next().getObject().asLiteral().getString();

            // Create the concept for that base voltage
            Resource resNewStmt=ResourceFactory.createResource("http://publications.europa.eu/resource/authority/baseVoltage"+"/"+nominalVoltage+"kV");
            modelResult.add(resNewStmt,RDF.type, SKOS.Concept  );
            modelResult.add(resNewStmt, ResourceFactory.createProperty(xmlBase,"#IdentifiedObject.mRID"), stmtItem.getSubject().asResource().getLocalName().split("_",2)[1]);
            modelResult.add(resNewStmt,SKOS.inScheme, schemeRes );


            for (StmtIterator k = model1.listStatements(new SimpleSelector(stmtItem.getSubject() , (Property) null, (RDFNode) null)); k.hasNext(); ) {
                Statement stmtItemForClass = k.next();
                switch (stmtItemForClass.getPredicate().asResource().getLocalName()) {
                    case "IdentifiedObject.name":
                        modelResult.add(resNewStmt, stmtItemForClass.getPredicate(), stmtItemForClass.getObject());
                        modelResult.add(resNewStmt, SKOS.prefLabel, stmtItemForClass.getObject());
                        modelResult.add(resNewStmt, DCTerms.identifier, stmtItemForClass.getObject());
                        break;
                    case "IdentifiedObject.shortName":
                    case "IdentifiedObject.description":
                    case "BaseVoltage.nominalVoltage":
                        modelResult.add(resNewStmt, stmtItemForClass.getPredicate(), stmtItemForClass.getObject());
                        break;
                }
            }
        }
        //do the export of modelResult
        OutputStream outInt = fileSaveDialog("Save ref data: ....", "RDF XML", "*.xml");
        //modelResult.write(outInt, RDFFormat..getLang().getLabel().toUpperCase(), xmlBase);
        Map<String, Object> properties = new HashMap<>();
        properties.put("showXmlDeclaration", "true");
        properties.put("showDoctypeDeclaration", "false");
        //properties.put("showXmlEncoding", showXmlEncoding); // works only with the custom format
        //properties.put("blockRules", "daml:collection,parseTypeLiteralPropertyElt,"
        //        +"parseTypeResourcePropertyElt,parseTypeCollectionPropertyElt"
        //        +"sectionReification,sectionListExpand,idAttr,propertyAttr"); //???? not sure
       //if (putHeaderOnTop) {
        //    properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});
       // }
        properties.put("xmlbase", xmlBase);
        properties.put("tab", "2");
        properties.put("relativeURIs", "true");




        // Put a properties object into the Context.
        Context cxt = new Context();
        cxt.set(SysRIOT.sysRdfWriterProperties, properties);


        org.apache.jena.riot.RDFWriter.create()
                .base(xmlBase)
                .format(RDFFormat.RDFXML_ABBREV)
                .context(cxt)
                .source(modelResult)
                .output(outInt);

    }

}
