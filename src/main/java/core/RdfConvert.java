/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;

import java.io.*;
import java.util.HashMap;
import java.util.Map;


public class RdfConvert {

    //RDF conversion
    public static void rdfConversion(File file, String sourceFormat, String targetFormat, String xmlBase,RDFFormat rdfFormat, String showXmlDeclaration, String showDoctypeDeclaration, String tab,String relativeURIs) throws IOException {

        Lang rdfSourceFormat;
        switch (sourceFormat) {
            case "RDF XML (.rdf)":
                rdfSourceFormat=Lang.RDFXML;
                break;

            case "RDF Turtle (.ttl)":
                rdfSourceFormat=Lang.TURTLE;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + sourceFormat);
        }

        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = new FileInputStream(file.toString());
        RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);


        switch (targetFormat) {
            case "RDF XML (.rdf)":
                OutputStream outXML = fileSaveDialog("Save RDF XML", "RDF XML", "*.rdf");
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
                            .format(RDFFormat.RDFXML_PLAIN)
                            .context(cxt)
                            .source(model)
                            .output(outXML);

                } finally {
                   outXML.close();
                }
                break;

            case "RDF Turtle (.ttl)":
                OutputStream outTTL = fileSaveDialog("Save RDF Turtle", "RDF Turtle", "*.ttl");
                try {
                    model.write(outTTL, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
                } finally {
                    outTTL.close();
                }
                break;
        }
    }

    //File save dialog
    public static OutputStream fileSaveDialog(String title, String extensionName, String extension) throws FileNotFoundException {
        File saveFile = null;
        FileChooser filechooserS = new FileChooser();
        filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(extensionName, extension));
        filechooserS.setInitialFileName(title.split(": ", 2)[1]);
        filechooserS.setTitle(title);
        saveFile = filechooserS.showSaveDialog(null);
        OutputStream out = new FileOutputStream(saveFile);
        return out;
    }

}
