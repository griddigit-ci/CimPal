/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.Main.core;

import eu.griddigit.cimpal.Main.application.MainController;
import eu.griddigit.cimpal.Main.customWriter.CustomRDFFormat;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.*;

import java.io.*;
import java.util.*;


public class RdfConvert {

    public static Model modelInheritance;

    //RDF conversion
    public static void rdfConversion(File file, List<File> files, String sourceFormat, String targetFormat, String xmlBase, RDFFormat rdfFormat,
                                     String showXmlDeclaration, String showDoctypeDeclaration, String tab, String relativeURIs, Boolean modelUnionFlag,
                                     Boolean inheritanceOnly, Boolean inheritanceList, Boolean inheritanceListConcrete, Boolean addowl, Boolean modelUnionFlagDetailed,
                                     String sortRDF, String rdfSortOptions, boolean stripPrefixes, String convertInstanceData, Boolean modelUnionFixPackage) throws IOException {

        Lang rdfSourceFormat = switch (sourceFormat) {
            case "RDF XML (.rdf or .xml)" -> Lang.RDFXML;
            case "RDF Turtle (.ttl)" -> Lang.TURTLE;
            case "JSON-LD (.jsonld)" -> Lang.JSONLD;
            default -> throw new IllegalStateException("Unexpected value: " + sourceFormat);
        };
        List<File> modelFiles = new LinkedList<File>();
        if (!modelUnionFlagDetailed) {
            if (!modelUnionFlag && file != null) {
                modelFiles.add(file);
            } else {
                modelFiles = files;
            }
        }

        Model model;

        List<File> fileDet1;
        List<File> fileDet2 = null;
        List<File> fileDet3 = null;

        if (modelUnionFlagDetailed) {
            //put first the main RDF
            fileDet1 = eu.griddigit.cimpal.Main.util.ModelFactory.fileChooserCustom(true, "RDF file", List.of("*.rdf", "*.xml", "*.ttl"), "Main RDF file");
            if (!fileDet1.isEmpty()) {
                if (fileDet1.getFirst() != null) {
                    modelFiles.add(fileDet1.getFirst());
                }
            }

            fileDet2 = eu.griddigit.cimpal.Main.util.ModelFactory.fileChooserCustom(true, "RDF file", List.of("*.rdf", "*.xml", "*.ttl"), "Deviation RDF file");

            if (!fileDet2.isEmpty()) {
                if (fileDet2.getFirst() != null) {
                    modelFiles.add(fileDet2.getFirst());
                }
            }

            fileDet3 = eu.griddigit.cimpal.Main.util.ModelFactory.fileChooserCustom(true, "RDF file", List.of("*.rdf", "*.xml", "*.ttl"), "Extended RDF file");

            if (!fileDet3.isEmpty()) {
                if (fileDet3.getFirst() != null) {
                    modelFiles.add(fileDet3.getFirst());
                }
            }

            model = ModelFactory.createDefaultModel();
            Model modelOrig = ModelFactory.createDefaultModel();
            Map<String, String> prefixMap = model.getNsPrefixMap();
            int count = 1;
            for (File modelFile : modelFiles) {
                Model modelPart = ModelFactory.createDefaultModel();
                InputStream inputStream = new FileInputStream(modelFile.toString());
                RDFDataMgr.read(modelPart, inputStream, xmlBase, rdfSourceFormat);
                prefixMap.putAll(modelPart.getNsPrefixMap());
                model.add(modelPart);
                if (count == 1) {
                    modelOrig = modelPart;
                }
                count = count + 1;
            }
            model.setNsPrefixes(prefixMap);

            List<Statement> stmtToDeleteClass = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory"), (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                if (stmt.getObject().asResource().getLocalName().equals("Package_LTDSnotDefined")) {
                    //delete all classes
                    List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    stmtToDeleteClass.addAll(stdelete);
                    //check if the class is an enumeration
                    if (model.listStatements(stmt.getSubject(),ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype"),ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")).hasNext()) {
                        for (Statement stmpProp : stdelete) {
                            List<Statement> stdeleteProp = model.listStatements(null, RDF.type, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp);
                            for (Statement stmpPropEn : stdeleteProp) {
                                List<Statement> stdeletePropEn = model.listStatements(stmpProp.getSubject(), null, (RDFNode) null).toList();
                                stmtToDeleteClass.addAll(stdeletePropEn);
                            }
                        }
                    }else {
                        //delete all attributes and associations with domain of the deleted classes
                        for (Statement stmpProp : stdelete) {
                            List<Statement> stdeleteProp = model.listStatements(null, RDFS.domain, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp);
                            for (Statement stmpProp1 : stdeleteProp) {
                                if (stmpProp1.getSubject().getLocalName().split("\\.", 2)[0].equals(stmpProp.getSubject().getLocalName())) {
                                    List<Statement> stdeleteProp1 = model.listStatements(stmpProp1.getSubject(), null, (RDFNode) null).toList();
                                    stmtToDeleteClass.addAll(stdeleteProp1);
                                }
                            }

                            //delete all attributes and associations with range of the deleted classes
                            List<Statement> stdeleteProp1 = model.listStatements(null, RDFS.range, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp1);
                            for (Statement stmpProp2 : stdeleteProp1) {
                                List<Statement> stdeleteProp2 = model.listStatements(stmpProp2.getSubject(), null, (RDFNode) null).toList();
                                stmtToDeleteClass.addAll(stdeleteProp2);
                            }
                        }
                    }
                }
            }

            for (StmtIterator i = model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("LTDSnotDefined", "en")); i.hasNext(); ) {
                Statement stmt = i.next();
                List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                stmtToDeleteClass.addAll(stdelete);
            }

            model.remove(stmtToDeleteClass);

            List<Statement> stmtToDeleteProperty = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype"), (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                if (stmt.getObject().toString().equals("LTDSnotDefined")) {
                    List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    stmtToDeleteProperty.addAll(stdelete);
                }
            }

            //delete double multiplicity
            for (StmtIterator k = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity"), (RDFNode) null); k.hasNext(); ) {
                Statement stmt = k.next();
                List<Statement> multi = model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity"), (RDFNode) null).toList();
                if (multi.size() > 1) {
                    for (Statement stmtM : multi) {
                        if (modelOrig.contains(stmtM)) {
                            stmtToDeleteProperty.add(stmtM);
                        }
                    }
                }
            }

            model.remove(stmtToDeleteProperty);

        } else {
            // load all models
            model = eu.griddigit.cimpal.Core.utils.ModelFactory.modelLoad(modelFiles, xmlBase, rdfSourceFormat, false);
        }


        //in case only inheritance related structure should be converted
        if (inheritanceOnly) {
            model = modelInheritance(model, inheritanceList, inheritanceListConcrete);
        }

        List<Statement> stmttoadd = new LinkedList<>();
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        if (addowl) {
            for (ResIterator i = model.listSubjectsWithProperty(RDF.type); i.hasNext(); ) {
                Resource resItem = i.next();
                //String className = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();

                RDFNode obje = resItem.getRequiredProperty(RDF.type).getObject();
                if (obje.equals(RDFS.Class)) {
                    stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.Class));
                } else if (obje.equals(RDF.Property)) {
                    stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.ObjectProperty));
                }

                for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                    RDFNode resItemNodeDescr = k.next();
                    if (resItemNodeDescr.toString().equals("enum")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.NamedIndividual));
                        break;
                    }
                    if (resItemNodeDescr.toString().equals("CIMDatatype")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.DatatypeProperty));
                        break;
                    }
                    if (resItemNodeDescr.toString().equals("Primitive")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.DatatypeProperty));
                        break;
                    }
                }
            }
        }

        model.add(stmttoadd);

        if (modelUnionFlagDetailed) {
            //ensure one ontology class
            if (!Objects.requireNonNull(fileDet2).isEmpty() || !Objects.requireNonNull(fileDet3).isEmpty()) {
                List<Statement> stmtToDeleteOntology = new LinkedList<>();
                int maxstmt = 0;
                Resource maxstmpst = null;
                for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")); i.hasNext(); ) {
                    Statement stmt = i.next();
                    List<Statement> stdeletep = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    int maxstmt0 = stdeletep.size();
                    if (maxstmt0 >= maxstmt) {
                        maxstmt = maxstmt0;
                        maxstmpst = stmt.getSubject();
                    }
                    stmtToDeleteOntology.addAll(stdeletep);
                }
                model.remove(stmtToDeleteOntology);


                List<Statement> stmtToAddOntology = new LinkedList<>();
                for (Statement stmtadd : stmtToDeleteOntology) {
                    if (stmtadd.getSubject().equals(maxstmpst)) {
                        stmtToAddOntology.add(stmtadd);
                    }
                }
                model.add(stmtToAddOntology);
            }
        }

        //optimise prefixes, strip unused prefixes
        if (stripPrefixes) {
            Map<String, String> modelPrefMap = model.getNsPrefixMap();
            LinkedList<String> uniqueNamespacesList = new LinkedList<>();
            for (StmtIterator ns = model.listStatements(); ns.hasNext(); ) {
                Statement stmtNS = ns.next();
                if (!uniqueNamespacesList.contains(stmtNS.getSubject().getNameSpace())) {
                    uniqueNamespacesList.add(stmtNS.getSubject().getNameSpace());
                }
                if (!uniqueNamespacesList.contains(stmtNS.getPredicate().getNameSpace())) {
                    uniqueNamespacesList.add(stmtNS.getPredicate().getNameSpace());
                }
                if (stmtNS.getObject().isResource()) {
                    if (!uniqueNamespacesList.contains(stmtNS.getObject().asResource().getNameSpace())) {
                        uniqueNamespacesList.add(stmtNS.getObject().asResource().getNameSpace());
                    }
                }
            }
            LinkedList<Map.Entry<String, String>> entryToRemove = new LinkedList<>();
            for (Map.Entry<String, String> entry : modelPrefMap.entrySet()) {
                //String key = entry.getKey();
                String value = entry.getValue();

                // Check if either the key or value is present in uniqueNamespacesList
                if (!uniqueNamespacesList.contains(value)) {
                    entryToRemove.add(entry);
                }
            }
            for (Map.Entry<String, String> entryTR : entryToRemove) {
                model.removeNsPrefix(entryTR.getKey());
            }
        }

        if (modelUnionFixPackage) {
            //add package statements
            String packageName = "";
            String packageURI = "";
            for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")); i.hasNext(); ) {
                Statement stmt = i.next();
                for (StmtIterator k = model.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                    Statement stmtP = k.next();
                    if (stmtP.getPredicate().equals(DCAT.keyword)) {
                        packageName = stmtP.getObject().asLiteral().getString();
                        packageURI = stmtP.getSubject().getNameSpace();
                    }
                }
            }
            //delete existing packages
            List<Statement> stmtToDeleteOldPackage = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")); i.hasNext(); ) {
                Statement stmt = i.next();
                for (StmtIterator k = model.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                    Statement stmtP = k.next();
                    stmtToDeleteOldPackage.add(stmtP);
                }
            }
            model.remove(stmtToDeleteOldPackage);

            //add the new package
            Resource packageRes = ResourceFactory.createResource(packageURI+"Package_"+packageName+"Profile");
            model.add(ResourceFactory.createStatement(packageRes, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")));
            model.add(ResourceFactory.createStatement(packageRes,RDFS.comment,ResourceFactory.createPlainLiteral("This is a package for the "+packageName+" profile.")));
            model.add(ResourceFactory.createStatement(packageRes,RDFS.label,ResourceFactory.createLangLiteral(packageName+"Profile","en")));

            //replace cims:belongsToCategory
            List<Statement> stmtToAddPackage = new LinkedList<>();
            List<Statement> stmtToDeletePackage = new LinkedList<>();
            Property belongsToCategory = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#","belongsToCategory");
            for (StmtIterator i = model.listStatements(null, belongsToCategory, (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                stmtToDeletePackage.add(stmt);
                stmtToAddPackage.add(ResourceFactory.createStatement(stmt.getSubject(),belongsToCategory,ResourceFactory.createProperty(packageRes.toString())));
            }
            model.remove(stmtToDeletePackage);
            model.add(stmtToAddPackage);
        }


        String filename = "";
        if (!modelUnionFlag && file != null) {
            filename = file.getName().split("\\.", 2)[0];
        } else {
            filename = "MultipleModels";
        }

        switch (targetFormat) {
            case "RDF XML (.rdf or .xml)" -> {

                //register custom format
                CustomRDFFormat.RegisterCustomFormatWriters();
                String showXmlEncoding = "true"; //saveProperties.get("showXmlEncoding").toString();
                boolean putHeaderOnTop = true; //(boolean) saveProperties.get("putHeaderOnTop");
                String headerClassResource = null;
                if (model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).hasNext()) {
                    headerClassResource = "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"; //saveProperties.get("headerClassResource").toString();
                } else if (model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
                    headerClassResource = "http://www.w3.org/2002/07/owl#Ontology";
                }

                boolean useAboutRules = false;//(boolean) saveProperties.get("useAboutRules");   //switch to trigger file chooser and adding the property
                boolean useEnumRules = false;//(boolean) saveProperties.get("useEnumRules");   //switch to trigger special treatment when Enum is reference
                Set<Resource> rdfAboutList = null; //(Set<Resource>) saveProperties.get("rdfAboutList");
                Set<Resource> rdfEnumList = null;//(Set<Resource>) saveProperties.get("rdfEnumList");
                OutputStream outXML = fileSaveDialog("Save RDF XML for: " + filename, "RDF XML", "*.rdf");
                if (outXML != null) {
                    try {
                        if (rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY || rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN) {
                            Map<String, Object> properties = new HashMap<>();
                            properties.put("showXmlDeclaration", showXmlDeclaration);
                            properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                            properties.put("showXmlEncoding", showXmlEncoding); // works only with the custom format
                            //properties.put("blockRules", "daml:collection,parseTypeLiteralPropertyElt,"
                            //        +"parseTypeResourcePropertyElt,parseTypeCollectionPropertyElt"
                            //        +"sectionReification,sectionListExpand,idAttr,propertyAttr"); //???? not sure
                            if (putHeaderOnTop) {
                                properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});
                            }
                            properties.put("xmlbase", xmlBase);
                            properties.put("tab", tab);
                            properties.put("relativeURIs", relativeURIs);
                            properties.put("instanceData", convertInstanceData);
                            properties.put("sortRDF", sortRDF);
                            properties.put("sortRDFprefix", rdfSortOptions);
                            properties.put("showXmlBaseDeclaration", "true");

                            if (useAboutRules) {
                                properties.put("aboutRules", rdfAboutList);
                            }

                            if (useEnumRules) {
                                properties.put("enumRules", rdfEnumList);
                            }

                            // Put a properties object into the Context.
                            Context cxt = new Context();
                            cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                            RDFWriter.create()
                                    .base(xmlBase)
                                    .format(rdfFormat)
                                    .context(cxt)
                                    .source(model)
                                    .output(outXML);

                        } else {
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

                                RDFWriter.create()
                                        .base(xmlBase)
                                        .format(rdfFormat)
                                        .context(cxt)
                                        .source(model)
                                        .output(outXML);
                                if (inheritanceList) {
                                    fileSaveDialogInheritance(filename + "Inheritance", xmlBase);
                                }
                            } finally {
                                outXML.close();
                            }
                        }
                    } finally {
                        outXML.flush();
                        outXML.close();
                    }
                }
            }
            case "RDF Turtle (.ttl)" -> {
                OutputStream outTTL = fileSaveDialog("Save RDF Turtle for: " + filename, "RDF Turtle", "*.ttl");
                if (outTTL != null) {
                    try {
                        model.write(outTTL, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
                        if (inheritanceList) {
                            fileSaveDialogInheritance(filename + "Inheritance", xmlBase);
                        }
                    } finally {
                        outTTL.close();
                    }
                }
            }
            case "JSON-LD (.jsonld)" -> {
                OutputStream outJsonLD = fileSaveDialog("Save JSON-LD for: " + filename, "JSON-LD", "*.jsonld");
                if (outJsonLD != null) {
                    try {
                        model.write(outJsonLD, RDFFormat.JSONLD.getLang().getLabel().toUpperCase(), xmlBase);

                        if (inheritanceList) {
                            fileSaveDialogInheritance(filename + "Inheritance", xmlBase);
                        }
                    } finally {
                        outJsonLD.close();
                    }
                }
            }
        }
    }

    //File save dialog for inheritance
    private static void fileSaveDialogInheritance(String filename, String xmlBase) throws IOException {
        OutputStream outInt = fileSaveDialog("Save inheritance for: " + filename, "RDF Turtle", "*.ttl");
        try {
            modelInheritance.write(outInt, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
        } finally {
            outInt.close();
        }

    }

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
