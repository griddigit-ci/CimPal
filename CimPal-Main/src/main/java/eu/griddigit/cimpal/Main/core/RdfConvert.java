/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.Main.core;

import eu.griddigit.cimpal.Main.application.MainController;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.*;

import java.io.*;
import java.util.*;


public class RdfConvert {

    public static Model modelInheritance;

    //File save dialog for serialisation
    private static void fileSaveDialogSerialization(String filename, String xmlBase, Model model) throws IOException {
        OutputStream outInt = fileSaveDialog("Save RDFS serialisation info: " + filename, "RDF Turtle", "*.ttl");
        try {
            model.write(outInt, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
        } finally {
            outInt.close();
        }

    }

    //File save dialog
    public static OutputStream fileSaveDialog(String title, String extensionName, String extension) throws FileNotFoundException {
//        File saveFile;
//        FileChooser filechooserS = new FileChooser();
//        filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(extensionName, extension));
//        filechooserS.setInitialFileName(title.split(": ", 2)[1]);
//        filechooserS.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
//        filechooserS.setTitle(title);
        File saveFile = eu.griddigit.cimpal.Main.util.ModelFactory.fileSaveCustom(extensionName, List.of(extension), title, title.split(": ", 2)[1]);
        try {
//            try {
//                saveFile = filechooserS.showSaveDialog(null);
//            } catch (Exception e) {
//                filechooserS.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
//                saveFile = filechooserS.showSaveDialog(null);
//            }
            OutputStream out = null;
            if (saveFile != null) {
                //MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
                out = new FileOutputStream(saveFile);
            }
            return out;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    //Creates another model that contains only inheritance related properties
    public static Model modelInheritance(Model model, Boolean inheritanceList, Boolean inheritanceListConcrete) {
        Model modelProcessed = ModelFactory.createDefaultModel();
        modelProcessed.setNsPrefixes(model.getNsPrefixMap());
        modelInheritance = ModelFactory.createDefaultModel();
        modelInheritance.setNsPrefixes(model.getNsPrefixMap());
        modelInheritance.setNsPrefix("owl", OWL2.NS);

        for (StmtIterator i = model.listStatements(); i.hasNext(); ) {
            Statement stmt = i.next();
            if (!inheritanceList) {
                if (stmt.getPredicate().equals(RDF.type) || stmt.getPredicate().equals(RDFS.subClassOf) || stmt.getPredicate().equals(RDFS.subPropertyOf) ||
                        stmt.getPredicate().equals(RDFS.domain) || stmt.getPredicate().equals(RDFS.range)) {
                    modelProcessed.add(stmt);
                }
            } else {
                if (stmt.getPredicate().equals(RDF.type) || stmt.getPredicate().equals(RDFS.subClassOf) || stmt.getPredicate().equals(RDFS.subPropertyOf) ||
                        stmt.getPredicate().equals(RDFS.domain) || stmt.getPredicate().equals(RDFS.range)) {
                    modelProcessed.add(stmt);
                    if (stmt.getPredicate().equals(RDF.type)) {
                        Resource stmtSubject = stmt.getSubject();
                        modelInheritance = inheritanceStructure(stmtSubject, stmtSubject, modelInheritance, model, inheritanceListConcrete);

                    }


                }
            }
        }

        return modelProcessed;
    }

    // Adds the inheritance structure in the model
    private static Model inheritanceStructure(Resource stmtSubject, Resource res, Model modelInheritance, Model model, Boolean inheritanceListConcrete) {

        for (ResIterator j = model.listSubjectsWithProperty(RDFS.subClassOf, res); j.hasNext(); ) {
            Resource resSub = j.next();
            //check if the class is concrete

            if (inheritanceListConcrete) {
                boolean addConcrete = false;
                if (model.listObjectsOfProperty(resSub, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype")).hasNext()) {
                    for (NodeIterator k = model.listObjectsOfProperty(resSub, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype")); k.hasNext(); ) {
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
            } else {
                modelInheritance.add(stmtSubject, OWL2.members, resSub);
                modelInheritance.add(stmtSubject, RDF.type, OWL2.Class);
            }
            modelInheritance = inheritanceStructure(stmtSubject, resSub, modelInheritance, model, inheritanceListConcrete);
        }


        return modelInheritance;
    }

    // Generate information for datasets serialisation
    public static void generateRDFSserializationInfo(List<Model> listModels) throws IOException {

        //create a model
        Model mainModel = ModelFactory.createDefaultModel(); // the model that should be exported

        Set<Resource> rdfAboutList = new HashSet<>();
        Set<Resource> rdfEnumList = new HashSet<>();
        String cims = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        String ser = "http://griddigit.eu/RDFS/Serialization#";
        Property stereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype");
        Property enumeration = ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration");
        String profileURI = null;
        String shortName = null;
//        for (Model m : listModels) {
//            //get metadata for CGMES v3 with Ontology header
//            if (m.listStatements(null, RDF.type, OWL2.Ontology).hasNext()) {
//                Statement header = m.listStatements(null, RDF.type, OWL2.Ontology).nextStatement();
//                profileURI = m.getRequiredProperty(header.getSubject(), OWL2.versionIRI).getObject().toString();
//                shortName = m.getRequiredProperty(header.getSubject(), DCAT.keyword).getObject().toString();
//            } else {//get metadata for CGMES v2.4 no header
//                if (m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURI")).hasNext()) {
//                    //get the shortname and get the URI
//                    Statement entsoeURI = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURI")).nextStatement();
//                    profileURI = m.getRequiredProperty(entsoeURI.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                    Statement domain = m.getRequiredProperty(entsoeURI.getSubject(), RDFS.domain);
//                    for (StmtIterator i = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("shortName")); i.hasNext(); ) {
//                        Statement stmt = i.next();
//                        if (m.getRequiredProperty(stmt.getSubject(), RDFS.domain).getObject().equals(domain.getObject())) {
//                            shortName = m.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                        }
//                    }
//                } else if (m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIcore")).hasNext()) {
//                    //get the shortname and get the URI
//                    Statement entsoeURI = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIcore")).nextStatement();
//                    profileURI = m.getRequiredProperty(entsoeURI.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                    Statement domain = m.getRequiredProperty(entsoeURI.getSubject(), RDFS.domain);
//                    for (StmtIterator i = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("shortName")); i.hasNext(); ) {
//                        Statement stmt = i.next();
//                        if (m.getRequiredProperty(stmt.getSubject(), RDFS.domain).getObject().equals(domain.getObject())) {
//                            shortName = m.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                        }
//                    }
//                } else if (m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIoperation")).hasNext()) {
//                    //get the shortname and get the URI
//                    Statement entsoeURI = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIoperation")).nextStatement();
//                    profileURI = m.getRequiredProperty(entsoeURI.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                    Statement domain = m.getRequiredProperty(entsoeURI.getSubject(), RDFS.domain);
//                    for (StmtIterator i = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("shortName")); i.hasNext(); ) {
//                        Statement stmt = i.next();
//                        if (m.getRequiredProperty(stmt.getSubject(), RDFS.domain).getObject().equals(domain.getObject())) {
//                            shortName = m.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                        }
//                    }
//                } else if (m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIshortCircuit")).hasNext()) {
//                    //get the shortname and get the URI
//                    Statement entsoeURI = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("entsoeURIshortCircuit")).nextStatement();
//                    profileURI = m.getRequiredProperty(entsoeURI.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                    Statement domain = m.getRequiredProperty(entsoeURI.getSubject(), RDFS.domain);
//                    for (StmtIterator i = m.listStatements(null, RDFS.label, ResourceFactory.createPlainLiteral("shortName")); i.hasNext(); ) {
//                        Statement stmt = i.next();
//                        if (m.getRequiredProperty(stmt.getSubject(), RDFS.domain).getObject().equals(domain.getObject())) {
//                            shortName = m.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
//                        }
//                    }
//                }
//            }
//            String profileNS = profileURI + "#";
//            mainModel.setNsPrefix(shortName.toLowerCase(), profileNS);
//            //mainModel.setNsPrefix("ser", ser);
//            mainModel.setNsPrefix("owl", OWL2.NS);
//            mainModel.setNsPrefix("rdfs", RDFS.uri);
//            mainModel.setNsPrefix("cim", m.getNsPrefixURI("cim"));
//
//            if (m.listSubjectsWithProperty(stereotype, "Description").hasNext()) {
//                rdfAboutList = m.listSubjectsWithProperty(stereotype, "Description").toSet();
//                mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfAbout"), RDF.type, RDFS.Class));
//                for (Resource item : rdfAboutList) {
//                    mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfAbout"), OWL2.members, item));
//                }
//                //plus header class
//            }
//
//            for (ResIterator ii = m.listSubjectsWithProperty(stereotype,enumeration); ii.hasNext(); ) {
//                Resource resItem = ii.next();
//                mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfEnum"), RDF.type, RDFS.Class));
//                for (ResIterator j = m.listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
//                    Resource resItemProp = j.next();
//                    mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfEnum"), OWL2.members, resItemProp));
//                }
//            }


        for (Model m : listModels) {
            // Check for metadata related to CGMES v3 with Ontology header
            if (m.listStatements(null, RDF.type, OWL2.Ontology).hasNext()) {
                Statement header = m.listStatements(null, RDF.type, OWL2.Ontology).nextStatement();
                profileURI = m.getRequiredProperty(header.getSubject(), OWL2.versionIRI).getObject().toString();
                shortName = m.getRequiredProperty(header.getSubject(), DCAT.keyword).getObject().toString();
            } else {
                // Check for different versions of metadata for CGMES v2.4 without header
                String[] versions = {"entsoeURI", "entsoeURIcore", "entsoeURIoperation", "entsoeURIshortCircuit"};
                for (String version : versions) {
                    if (m.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral(version, "en")).hasNext()) {
                        Statement entsoeURI = m.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral(version, "en")).nextStatement();
                        profileURI = m.getRequiredProperty(entsoeURI.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
                        Statement domain = m.getRequiredProperty(entsoeURI.getSubject(), RDFS.domain);
                        StmtIterator stmtIterator = m.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("shortName", "en"));
                        while (stmtIterator.hasNext()) {
                            Statement stmt = stmtIterator.next();
                            if (m.getRequiredProperty(stmt.getSubject(), RDFS.domain).getObject().equals(domain.getObject())) {
                                shortName = m.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(cims, "isFixed")).getObject().toString();
                            }
                        }
                        break;
                    }
                }
            }

            // Set namespace prefixes
            String profileNS = profileURI + "#";
            if (shortName != null) {
                mainModel.setNsPrefix(shortName.toLowerCase(), profileNS);
            }
            mainModel.setNsPrefix("owl", OWL2.NS);
            mainModel.setNsPrefix("rdfs", RDFS.uri);
            mainModel.setNsPrefix("cim", m.getNsPrefixURI("cim"));
            if (m.getNsPrefixURI("entsoe") != null) {
                mainModel.setNsPrefix("entsoe", m.getNsPrefixURI("entsoe"));
            } else {
                mainModel.setNsPrefix("eu", m.getNsPrefixURI("eu"));
            }

            // Process Description and Enumeration
            if (m.listSubjectsWithProperty(stereotype, "Description").hasNext()) {
                rdfAboutList = m.listSubjectsWithProperty(stereotype, "Description").toSet();
                mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfAbout"), RDF.type, RDFS.Class));
                for (Resource item : rdfAboutList) {
                    mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfAbout"), OWL2.members, item));
                }
            }

            for (ResIterator ii = m.listSubjectsWithProperty(stereotype, enumeration); ii.hasNext(); ) {
                Resource resItem = ii.next();
                mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfEnum"), RDF.type, RDFS.Class));
                for (ResIterator j = m.listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
                    Resource resItemProp = j.next();
                    mainModel.add(ResourceFactory.createStatement(ResourceFactory.createResource(profileNS + "RdfEnum"), OWL2.members, resItemProp));
                }
            }
        }


        //we need to do ttl export of mainModel and save
        fileSaveDialogSerialization("RDFSSerialisation", "", mainModel);
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

        Model model1 = ModelFactory.createDefaultModel();
        Map prefixMap = model1.getNsPrefixMap();

        for (File item : MainController.IDModel1) {
            if (item.getName().toLowerCase().endsWith(".zip")) {

                model1single = eu.griddigit.cimpal.Main.util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);

                model1single = eu.griddigit.cimpal.Main.util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);

            }
            prefixMap.putAll(model1single.getNsPrefixMap());
            model1.add(model1single);
        }
        model1.setNsPrefixes(prefixMap);

        Model modelResult = ModelFactory.createDefaultModel();
        modelResult.setNsPrefixes(prefixMap);
        modelResult.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");
        modelResult.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");
        modelResult.setNsPrefix("dc", "http://purl.org/dc/elements/1.1/");
        modelResult.setNsPrefix("dcterms", DCTerms.NS);


        Resource schemeRes = ResourceFactory.createResource("http://publications.europa.eu/resource/authority/baseVoltage");
        modelResult.add(schemeRes, RDF.type, SKOS.ConceptScheme);//ResourceFactory.createProperty("http://www.w3.org/2004/02/skos/core#ConceptScheme")
        modelResult.add(schemeRes, OWL2.versionInfo, ResourceFactory.createPlainLiteral("22 Nov 2021"));
        modelResult.add(schemeRes, RDFS.label, ResourceFactory.createPlainLiteral("Base Voltage"));
        modelResult.add(schemeRes, SKOS.prefLabel, ResourceFactory.createPlainLiteral("Base Voltage"));


        for (StmtIterator i = model1.listStatements(null, RDF.type, ResourceFactory.createProperty(xmlBase, "#BaseVoltage")); i.hasNext(); ) {
            Statement stmtItem = i.next();

            //get the object of the BaseVoltage.nominalVoltage attribute
            String nominalVoltage = model1.listStatements(stmtItem.getSubject(), ResourceFactory.createProperty(xmlBase, "#BaseVoltage.nominalVoltage"), (RDFNode) null).next().getObject().asLiteral().getString();

            // Create the concept for that base voltage
            Resource resNewStmt = ResourceFactory.createResource("http://publications.europa.eu/resource/authority/baseVoltage" + "/" + nominalVoltage + "kV");
            modelResult.add(resNewStmt, RDF.type, SKOS.Concept);
            modelResult.add(resNewStmt, ResourceFactory.createProperty(xmlBase, "#IdentifiedObject.mRID"), stmtItem.getSubject().asResource().getLocalName().split("_", 2)[1]);
            modelResult.add(resNewStmt, SKOS.inScheme, schemeRes);


            for (StmtIterator k = model1.listStatements(stmtItem.getSubject(), (Property) null, (RDFNode) null); k.hasNext(); ) {
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


        RDFWriter.create()
                .base(xmlBase)
                .format(RDFFormat.RDFXML_ABBREV)
                .context(cxt)
                .source(modelResult)
                .output(outInt);

    }

}
