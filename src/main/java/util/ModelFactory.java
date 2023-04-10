/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package util;


import application.MainController;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import org.apache.commons.io.FileUtils;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.graph.GraphFactory;
import org.topbraid.jenax.util.JenaUtil;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModelFactory {

    //Loads one or many models
    public static Model modelLoad(List<File> files, String xmlBase, Lang rdfSourceFormat) throws FileNotFoundException {
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        for (Object file : files) {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file.toString());
            RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());
            modelUnion.add(model);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }


    //Loads model data with datatype mapping
    public static Model modelLoadXMLmapping(InputStream inputStream, Map dataTypeMap, String xmlBase) {
        //in case of xml files and if mapping should be from RDF file or saved datatype mapping file

        Graph graph = GraphFactory.createDefaultGraph();
        util.DataTypeStreamRDF sink = new util.DataTypeStreamRDF(graph, dataTypeMap);

        RDFDataMgr.parse(sink, inputStream, xmlBase, Lang.RDFXML);
        graph=sink.getGraph();
        Model model = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(graph); // that should be the model that includes the datatypes definitions
        Map prefixMapping=sink.getPrefixMapping();
        model.setNsPrefixes(prefixMapping);

        return model;

    }

    //Loads model data with datatype mapping - Multiple XML selected
    public static Model modelLoadMultipleXMLmapping(List files, Map dataTypeMap, String xmlBase,Lang rdfSourceFormat) throws FileNotFoundException {
        //in case of xml files and if mapping should be from RDF file or saved datatype mapping file

        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map prefixMap = modelUnion.getNsPrefixMap();
        for (int i=0; i<files.size();i++) {
            InputStream inputStream = new FileInputStream(files.get(i).toString());

            Graph graph = GraphFactory.createDefaultGraph();
            util.DataTypeStreamRDF sink = new util.DataTypeStreamRDF(graph, dataTypeMap);

            RDFDataMgr.parse(sink, inputStream, xmlBase, rdfSourceFormat);
            graph=sink.getGraph();
            Model model = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(graph); // that should be the model that includes the datatypes definitions
            Map prefixMapping=sink.getPrefixMapping();
            model.setNsPrefixes(prefixMapping);

            prefixMap.putAll(model.getNsPrefixMap());
            modelUnion.add(model);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;

    }

    //Loads shape model data
    public static void shapeModelLoad(int m, List file)  {

        if (MainController.shapeModels == null) {
            MainController.shapeModels = new ArrayList<>();
            MainController.shapeModelsNames = new ArrayList<>(); // this is a collection of the name of the profile packages
        }
        Model model = JenaUtil.createDefaultModel();
        try {
            if (file.get(m).toString().endsWith(".ttl")) {
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.TURTLE);
            }else if (file.get(m).toString().endsWith(".rdf")){
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.RDFXML);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        MainController.shapeModels.add(model);
    }

    public static Model unzip(File selectedFile, Map dataTypeMap, String xmlBase, Integer mappingType) {
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map prefixMap = modelUnion.getNsPrefixMap();
        Model model;
        try{
            ZipFile zipFile = new ZipFile(selectedFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();



            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();
                if(entry.isDirectory()){
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setContentText("Selected zip file contains folder. This is a violation of data exchange standard.");
                    alert.setHeaderText(null);
                    alert.setTitle("Error - violation of a zip file packaging requirement.");
                    alert.showAndWait();
                } else {
                    String destPath = selectedFile.getParent() + File.separator+ entry.getName();

                    if(! isValidDestPath(selectedFile.getParent(), destPath)){
                        throw new IOException("Final file output path is invalid: " + destPath);
                    }

                    try(InputStream inputStream = zipFile.getInputStream(entry)){
                        if (mappingType==3) {//no mapping
                            model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                            RDFDataMgr.read(model, inputStream, xmlBase, Lang.RDFXML);
                            modelUnion.add(model);
                        }else if (mappingType==1 || mappingType==2){ //mapping from RDF file (1) //mapping from saved mapping file (2)
                            model = modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                            prefixMap.putAll(model.getNsPrefixMap());
                            modelUnion.add(model);
                        }


                    }
                }
            }
        } catch(IOException e){
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }

    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        // validate the destination path of a ZipFile entry,
        // and return true or false telling if it's valid or not.

        Path destPath           = Paths.get(destPathStr);
        Path destPathNormalized = destPath.normalize(); //remove ../../ etc.

        return destPathNormalized.toString().startsWith(targetDir + File.separator);
    }


    //File(s) selection Filechooser
    public static List<File> filechoosercustom(Boolean typeSingleFile, String titleExtensionFilter , List<String> extExtensionFilter) {

        List<File> fileL = null;
        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));

        try {
            if (typeSingleFile) {
                file = filechooser.showOpenDialog(null);
            }else{
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        } catch (Exception e) {
            if (typeSingleFile) {
                filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
                file = filechooser.showOpenDialog(null);
            }else{
                filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        }

        if (typeSingleFile) {
            if (file != null) {// the file is selected
                MainController.prefs.put("LastWorkingFolder", file.getParent());
                fileL.add(file);
            }
        }else{
            if (fileL != null) {// the file is selected
                MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            }
        }
        return fileL;
    }

    //File(s) selection Filechooser
    public static File filesavecustom(String titleExtensionFilter , List<String> extExtensionFilter, String Dialogtitle, String filename) {

        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
        filechooser.setTitle(Dialogtitle);
        filechooser.setInitialFileName(filename);

        try {
            file = filechooser.showSaveDialog(null);
        } catch (Exception e) {
            filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
            file = filechooser.showSaveDialog(null);
        }

        return file;
    }

}
