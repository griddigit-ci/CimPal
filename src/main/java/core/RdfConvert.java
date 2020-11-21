/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


public class RdfConvert {

    private static Model modelInheritance;

    //RDF conversion
    public static void rdfConversion(File file, List files, String sourceFormat, String targetFormat, String xmlBase,RDFFormat rdfFormat,
                                     String showXmlDeclaration, String showDoctypeDeclaration, String tab,String relativeURIs, Boolean modelUnionFlag,
                                     Boolean inheritanceOnly,Boolean inheritanceList, Boolean inheritanceListConcrete) throws IOException {

        Lang rdfSourceFormat;
        switch (sourceFormat) {
            case "RDF XML (.rdf)":
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
            case "RDF XML (.rdf)":
                OutputStream outXML = fileSaveDialog("Save RDF XML for: "+filename, "RDF XML", "*.rdf");
                if (outXML!=null) {
                    try {
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("showXmlDeclaration", showXmlDeclaration);
                        properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                        //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                        properties.put("xmlbase", xmlBase);
                        properties.put("tab", tab);
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
        filechooserS.setTitle(title);
        saveFile = filechooserS.showSaveDialog(null);
        OutputStream out=null;
        if (saveFile!=null) {
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


}
