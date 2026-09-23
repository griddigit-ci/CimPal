/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.main.util;


import eu.griddigit.cimpal.core.utils.DataTypeStreamRDF;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.PathMemory;
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

import static eu.griddigit.cimpal.main.application.MainController.prefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ModelFactory.class);


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
            LOG.error("Unhandled exception", e);
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

    /**
     * True when {@code destPathStr} resolves strictly inside {@code targetDir}.
     * <p>
     * Compares normalised absolute {@link Path} objects. The previous implementation compared
     * unnormalised strings by prefix, which is not a containment check: a relative or
     * non-normalised {@code targetDir} makes the comparison unsound.
     */
    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        Path base = Paths.get(targetDir).toAbsolutePath().normalize();
        Path dest = Paths.get(destPathStr).toAbsolutePath().normalize();
        return dest.startsWith(base) && !dest.equals(base);
    }


    //File(s) selection Filechooser
    public static List<File> fileChooserCustom(Boolean typeSingleFile, String titleExtensionFilter, List<String> extExtensionFilter, String title) {
        return fileChooserCustom(typeSingleFile, titleExtensionFilter, extExtensionFilter, title, null);
    }

    /**
     * As {@link #fileChooserCustom(Boolean, String, List, String)}, but remembering the chosen
     * location under {@code memoryKey} so this particular dialog reopens where it was last used.
     */
    public static List<File> fileChooserCustom(Boolean typeSingleFile, String titleExtensionFilter, List<String> extExtensionFilter, String title, String memoryKey) {

        List<File> fileL = new LinkedList<>();
        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        // PathMemory only sets a directory once it has verified one exists, so the chooser is never
        // handed a dead path.
        PathMemory.prepare(filechooser, memoryKey);
        filechooser.setTitle(title);

        try {
            if (typeSingleFile) {
                file = filechooser.showOpenDialog(null);
            } else {
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        } catch (Exception e) {
            if (typeSingleFile) {
                filechooser.setInitialDirectory(FileUtils.getUserDirectory());
                file = filechooser.showOpenDialog(null);
            } else {
                filechooser.setInitialDirectory(FileUtils.getUserDirectory());
                fileL = filechooser.showOpenMultipleDialog(null);
            }
        }

        if (typeSingleFile) {
            if (file != null) {// the file is selected
                prefs.put("LastWorkingFolder", file.getParent());
                PathMemory.remember(memoryKey, file);
                fileL.add(file);
            }
        } else {
            if (fileL != null) {// the file is selected
                prefs.put("LastWorkingFolder", fileL.getFirst().getParent());
                PathMemory.remember(memoryKey, fileL.getFirst());
            }
        }
        return fileL;
    }

    //Folder selection
    public static File folderChooserCustom() {
        return folderChooserCustom("Select Output Folder", null);
    }

    public static File folderChooserCustom(String title) {
        return folderChooserCustom(title, null);
    }

    /**
     * As {@link #folderChooserCustom(String)}, but remembering the chosen folder under
     * {@code memoryKey} so this particular dialog reopens where it was last used.
     */
    public static File folderChooserCustom(String title, String memoryKey) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        File selectedDirectory;

        PathMemory.prepare(directoryChooser, memoryKey);
        directoryChooser.setTitle(title);

        try {
            selectedDirectory = directoryChooser.showDialog(null);
        } catch (Exception e) {
            directoryChooser.setInitialDirectory(FileUtils.getUserDirectory());
            selectedDirectory = directoryChooser.showDialog(null);
        }

        if (selectedDirectory != null) {
            prefs.put("LastWorkingFolder", selectedDirectory.getAbsolutePath());
            PathMemory.remember(memoryKey, selectedDirectory);
        }

        return selectedDirectory;
    }

    //File(s) selection Filechooser
    public static File fileSaveCustom(String titleExtensionFilter, List<String> extExtensionFilter, String Dialogtitle, String filename) {
        return fileSaveCustom(titleExtensionFilter, extExtensionFilter, Dialogtitle, filename, null);
    }

    /**
     * As {@link #fileSaveCustom(String, List, String, String)}, but remembering the chosen location
     * under {@code memoryKey} so this particular save dialog reopens where it was last used.
     */
    public static File fileSaveCustom(String titleExtensionFilter, List<String> extExtensionFilter, String Dialogtitle, String filename, String memoryKey) {

        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        PathMemory.prepare(filechooser, memoryKey);
        filechooser.setTitle(Dialogtitle);
        filechooser.setInitialFileName(filename);

        try {
            file = filechooser.showSaveDialog(null);
        } catch (Exception e) {
            filechooser.setInitialDirectory(FileUtils.getUserDirectory());
            file = filechooser.showSaveDialog(null);
        }

        if (file != null && file.getParent() != null) {
            prefs.put("LastWorkingFolder", file.getParent());
            PathMemory.remember(memoryKey, file);
        }

        return file;
    }


}
