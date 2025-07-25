/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.Main.util;


import eu.griddigit.cimpal.Main.application.MainController;
import javafx.scene.control.Alert;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.commons.io.FileUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.graph.GraphFactory;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.jena.riot.RDFParser;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static eu.griddigit.cimpal.Main.application.MainController.prefs;

public class ModelFactory {

    //Loads model data with datatype mapping
    public static Model modelLoadXMLmapping(InputStream inputStream, Map<String, RDFDatatype> dataTypeMap, String xmlBase) {
        // Create a Graph to hold the parsed data
        Graph graph = GraphFactory.createDefaultGraph();

        // Create a StreamRDF for handling parsed triples and datatypes
        DataTypeStreamRDF sink = new DataTypeStreamRDF(graph, dataTypeMap);

        // Use RDFParser to parse the input stream
        RDFParser.create().source(inputStream).lang(Lang.RDFXML).base(xmlBase).parse(sink);

        // Obtain the parsed graph and create a Model from it
        graph = sink.getGraph();
        Model model = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(graph);

        // Set namespace prefixes based on the sink's prefix mapping
        Map<String, String> prefixMapping = sink.getPrefixMapping();
        model.setNsPrefixes(prefixMapping);

        return model;
    }

    //Loads model data with datatype mapping - Multiple XML selected
    public static Model modelLoadMultipleXMLmapping(List files, Map<String, RDFDatatype> dataTypeMap, String xmlBase, Lang rdfSourceFormat) throws FileNotFoundException {
        // Create a Model to hold the union of parsed models
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();

        for (Object file : files) {
            // Open the input stream for each file
            InputStream inputStream = new FileInputStream(file.toString());

            // Create a Graph and StreamRDF for parsing
            Graph graph = GraphFactory.createDefaultGraph();
            DataTypeStreamRDF sink = new DataTypeStreamRDF(graph, dataTypeMap);

            // Use RDFParser to parse the file
            RDFParser.create().source(inputStream).lang(rdfSourceFormat).base(xmlBase).parse(sink);

            // Create a Model from the parsed Graph and set its prefixes
            graph = sink.getGraph();
            Model model = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(graph);
            Map<String, String> prefixMapping = sink.getPrefixMapping();
            model.setNsPrefixes(prefixMapping);

            // Combine prefixes and add the model to the union model
            prefixMap.putAll(model.getNsPrefixMap());
            modelUnion.add(model);
        }

        // Set the final prefixes for the union model
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }

    //Loads shape model data
    public static void shapeModelLoad(int m, List file) {

        if (MainController.shapeModels == null) {
            MainController.shapeModels = new ArrayList<>();
            MainController.shapeModelsNames = new ArrayList<>(); // this is a collection of the name of the profile packages
        }
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        // Model model = JenaUtil.createDefaultModel();
        try {
            if (file.get(m).toString().endsWith(".ttl")) {
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.TURTLE);
            } else if (file.get(m).toString().endsWith(".rdf")) {
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.RDFXML);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        MainController.shapeModels.add(model);
    }

    public static Model unzip(File selectedFile, Map<String, RDFDatatype> dataTypeMap, String xmlBase, Integer mappingType) {
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        Model model;
        try (ZipFile zipFile = new ZipFile(selectedFile);) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setContentText("Selected zip file contains folder. This is a violation of data exchange standard.");
                    alert.setHeaderText(null);
                    alert.setTitle("Error - violation of a zip file packaging requirement.");
                    alert.showAndWait();
                } else {
                    String destPath = selectedFile.getParent() + File.separator + entry.getName();

                    if (!isValidDestPath(selectedFile.getParent(), destPath)) {
                        throw new IOException("Final file output path is invalid: " + destPath);
                    }

                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        if (mappingType == 3) {//no mapping
                            model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                            RDFDataMgr.read(model, inputStream, xmlBase, Lang.RDFXML);
                            modelUnion.add(model);
                        } else if (mappingType == 1 || mappingType == 2) { //mapping from RDF file (1) //mapping from saved mapping file (2)
                            model = modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                            prefixMap.putAll(model.getNsPrefixMap());
                            modelUnion.add(model);
                        }


                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }

    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        // validate the destination path of a ZipFile entry,
        // and return true or false telling if it's valid or not.

        Path destPath = Paths.get(destPathStr);
        Path destPathNormalized = destPath.normalize(); //remove ../../ etc.

        return destPathNormalized.toString().startsWith(targetDir + File.separator);
    }


    //File(s) selection Filechooser
    public static List<File> fileChooserCustom(Boolean typeSingleFile, String titleExtensionFilter, List<String> extExtensionFilter, String title) {

        List<File> fileL = new LinkedList<>();
        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        filechooser.setInitialDirectory(new File(prefs.get("LastWorkingFolder", "")));
        filechooser.setTitle(title);

        try {
            if (typeSingleFile) {
                file = filechooser.showOpenDialog(null);
            } else {
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        } catch (Exception e) {
            if (typeSingleFile) {
                filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
                file = filechooser.showOpenDialog(null);
            } else {
                filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        }

        if (typeSingleFile) {
            if (file != null) {// the file is selected
                prefs.put("LastWorkingFolder", file.getParent());
                fileL.add(file);
            }
        } else {
            if (fileL != null) {// the file is selected
                prefs.put("LastWorkingFolder", fileL.getFirst().getParent());
            }
        }
        return fileL;
    }

    //Folder selection
    public static File folderChooserCustom() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        File selectedDirectory = null;

        // Validate the stored last working folder
        File initialDirectory = new File(prefs.get("LastWorkingFolder", ""));
        if (!initialDirectory.exists() || !initialDirectory.isDirectory()) {
            initialDirectory = FileUtils.getUserDirectory(); // Default to user's home directory
        }

        directoryChooser.setInitialDirectory(initialDirectory);
        directoryChooser.setTitle("Select Output Folder");

        try {
            selectedDirectory = directoryChooser.showDialog(null);
        } catch (Exception e) {
            directoryChooser.setInitialDirectory(FileUtils.getUserDirectory());
            selectedDirectory = directoryChooser.showDialog(null);
        }

        if (selectedDirectory != null) {
            prefs.put("LastWorkingFolder", selectedDirectory.getAbsolutePath());
        }

        return selectedDirectory;
    }

    //File(s) selection Filechooser
    public static File fileSaveCustom(String titleExtensionFilter, List<String> extExtensionFilter, String Dialogtitle, String filename) {

        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        filechooser.setInitialDirectory(new File(prefs.get("LastWorkingFolder", "")));
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
