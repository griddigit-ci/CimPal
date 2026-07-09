/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application;

import eu.griddigit.cimpal.core.generators.ManifestGenerator;
import eu.griddigit.cimpal.core.models.*;
import eu.griddigit.cimpal.core.utils.AttributeInjector;
import eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import eu.griddigit.cimpal.main.application.PssePFcompare.comparePssePF;
import eu.griddigit.cimpal.main.application.controllers.*;
import eu.griddigit.cimpal.main.application.controllers.sparql.SparqlQueryTabController;
import eu.griddigit.cimpal.main.application.datagenerator.ExportFactory;
import eu.griddigit.cimpal.main.core.*;
import eu.griddigit.cimpal.main.gui.*;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;

import java.io.InputStream;

//import guru.nidi.graphviz.engine.Graphviz;
//import guru.nidi.graphviz.engine.Format;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import eu.griddigit.cimpal.main.util.ExcelTools;

import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static eu.griddigit.cimpal.main.core.ExportRDFSdescriptions.*;
import static eu.griddigit.cimpal.main.core.ModelManipulationFactory.LoadRDFAbout;
import static eu.griddigit.cimpal.main.core.ModelManipulationFactory.LoadRDFEnum;
import static eu.griddigit.cimpal.main.core.RdfConvert.fileSaveDialog;
import static eu.griddigit.cimpal.main.util.ExcelTools.CreateTemplateFromXMLQAR;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;


import javafx.scene.control.Alert;
import javafx.scene.control.ProgressIndicator;


import org.apache.jena.riot.RDFDataMgr;


import java.io.File;
import java.io.FileOutputStream;


public class MainController implements Initializable {

    public GUIhelper getGuiHelper() {
        return guiHelper;
    }

    private final GUIhelper guiHelper;
    private eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.CimPalWizardController cimPalWizardController;

    public TabPane tabPaneConstraintsDetails;
    public Tab tabCreateCompleteSM1;
    public Tab tabInstanceDataComparison;
    public Tab tabExcelToSHACL;
    public Tab tabRDFConvert;
    public Tab tabOutputWindow;
    public Tab tabSPARQLQuery;
    public Font x3;
    @FXML
    public TreeView treeViewInstanceData;
    public TextField fPrefixGenerateTab1;
    public TextField fshapesBaseURIDefineTab1;
    public TextField fURIGenerateTab1;
    public TextField fowlImportsDefineTab1;
    @FXML
    private TextArea foutputWindow;
    @FXML
    private ProgressBar progressBar;

    @FXML
    private TabPane tabPaneDown;
    @FXML
    private SplitPane mainSplitPane;
    @FXML
    private TitledPane outputSourceContainer;
    public static Preferences prefs;
    @FXML
    private CheckBox cbShowUnionModelOnly;

    private double outputSourceDividerPosition = 0.8146067415730337;

    @FXML
    private Tab tabTaskWizard;
    @FXML
    private Tab tabGenerateInstanceData;
    @FXML
    private Tab tabSHACLTester;
    @FXML
    private Tab tabSHACLOrganizer;
    @FXML
    private Tab tabRDFStoSHACL;
    @FXML
    private Tab tabConstraintsSourceCode;
    @FXML
    private ToggleButton btnShowSourceCodeDefineTab;
    @FXML
    private TextArea fsourceDefineTab;

    @FXML
    private Tab tabValidationByMapping;

    public static File rdfModel1;
    public static File rdfModel2;
    public static List<File> IDModel1;
    public static List<File> IDModel2;
    public static List<File> IDmapList;
    public static int IDmapSelect;
    public static File rdfModelExcelShacl;
    public static File xlsFileExcelShacl;
    public static File TtlChangesExcelToTtl;
    public static File XlsChangesExcelToTtl;
    public static RDFCompareResult rdfCompareResult;
    public static List<String> rdfsCompareFiles;

    private ArrayList<Object> models;
    private ArrayList<Object> modelsNames;
    public static ArrayList<Model> RDFSmodels;
    public static List<RdfsModelDefinition> RDFSmodelsNames;
    public static ArrayList<Object> shapeModelsNames;


    public static ArrayList<Object> shapeModels;
    private static Map<String, RDFDatatype> dataTypeMapFromShapes;
    private static int shaclNodataMap;
    private static String defaultShapesURI;
    public static String rdfFormatInput;

    public static TreeView treeViewConstraintsStatic;
    public static TreeView treeViewIDStatic;
    private static Map<TreeItem, String> treeMapConstraints;
    private static Map<TreeItem, String> treeMapID;
    private static Map<String, TreeItem> treeMapConstraintsInverse;
    public static Integer shapesOnAbstractOption;

    public static TextArea foutputWindowVar;
    public static boolean excludeMRID;
    public static List<File> inputXLS;
    public static List<File> rdfProfileFileList;

    public static int baseprofilesshaclglag;
    public static int baseprofilesshaclglag2nd;
    public static int baseprofilesshaclglag3rd;
    public static int baseprofilesshaclignorens;
    public static int shaclflaginverse;
    public static int shaclflagCount;
    public static int shaclflagCountDefaultURI;
    public static String shaclCommonPref;
    public static String shaclCommonURI;
    public static Model unionmodelbaseprofilesshacl;
    public static Model unionmodelbaseprofilesshacl2nd;
    public static Model unionmodelbaseprofilesshacl3rd;

    public static Model unionmodelbaseprofilesshaclinheritanceonly;
    public static String cim2URI;
    public static String cim3URI;
    public static String cimURI;


    public static boolean shaclSkipNcPropertyReference;

    public static Map<String, Model> InstanceModelMap;
    public static boolean treeID;
    //for the
    private double initialX;
    private double initialY;


    public MainController() {
        guiHelper = new GUIhelper();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                appendText(String.valueOf((char) b));
            }
        };
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
        treeID = false;
        treeViewIDStatic = treeViewInstanceData;
        foutputWindowVar = foutputWindow;
        //initialization of the Browse and Modify table - SHACL Shapes Browse
        Callback<TableColumn, TableCell> cellFactory = p -> new ComboBoxCell();

        try {
            if (!Preferences.userRoot().nodeExists("CimPal")) {
                prefs = Preferences.userRoot().node("CimPal");
                //set the default preferences
                PreferencesController.prefDefault();
            } else {
                prefs = Preferences.userRoot().node("CimPal");
            }
        } catch (BackingStoreException e) {
            GUIhelper.showUserFriendlyError("Preferences error", "Preferences could not be loaded. Please review details and send them to support.", e);
        }

        cimPalWizardController = new eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.CimPalWizardController(prefs);

        Platform.runLater(() -> {
            if (mainSplitPane != null && !mainSplitPane.getDividers().isEmpty()) {
                outputSourceDividerPosition = mainSplitPane.getDividerPositions()[0];
                mainSplitPane.getDividers().get(0).positionProperty().addListener((obs, oldValue, newValue) -> {
                    if (outputSourceContainer == null || outputSourceContainer.isExpanded()) {
                        outputSourceDividerPosition = newValue.doubleValue();
                    }
                });
            }

            if (outputSourceContainer != null) {
                outputSourceContainer.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                    if (!isExpanded) {
                        if (mainSplitPane != null && !mainSplitPane.getDividers().isEmpty()) {
                            outputSourceDividerPosition = mainSplitPane.getDividerPositions()[0];
                        }
                    } else {
                        Platform.runLater(() -> {
                            if (mainSplitPane != null && !mainSplitPane.getDividers().isEmpty()) {
                                mainSplitPane.setDividerPosition(0, outputSourceDividerPosition);
                            }
                        });
                    }
                });
            }
        });


        //TODO: see how to have this default on the screen
        defaultShapesURI = "/Constraints";


        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RDFComparisonTab.fxml"));
            tabCreateCompleteSM1.setContent(loader.load());
            RDFComparisonController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("RDF comparison tab error", "The RDF comparison tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/InstanceDataComparisonTab.fxml"));
            tabInstanceDataComparison.setContent(loader.load());
            InstanceDataComparisonController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Instance Data Comparison tab error", "The Instance Data Comparison tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RDFStoSHACLtab.fxml"));
            tabRDFStoSHACL.setContent(loader.load());
            RDFStoSHACLController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("RDFS to SHACL tab error", "The RDFS to SHACL tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ExcelToSHACLTab.fxml"));
            tabExcelToSHACL.setContent(loader.load());
            ExcelToSHACLController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Excel to SHACL tab error", "The Excel to SHACL tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/RDFConvertTab.fxml"));
            tabRDFConvert.setContent(loader.load());
            RDFConvertController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("RDF Convert tab error", "The RDF Convert tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SHACLOrganizerTab.fxml"));
            tabSHACLOrganizer.setContent(loader.load());
            SHACLOrganizerController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("SHACL Organizer tab error", "The SHACL Organizer tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SHACLTesterTab.fxml"));
            tabSHACLTester.setContent(loader.load());
            SHACLTesterController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("SHACL Tester tab error", "The SHACL Tester tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/GenerateInstanceDataTab.fxml"));
            tabGenerateInstanceData.setContent(loader.load());
            GenerateInstanceDataController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Generate Instance Data tab error", "The Generate Instance Data tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TaskWizardTab.fxml"));
            tabTaskWizard.setContent(loader.load());
            TaskWizardController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Task Wizard tab error", "The Task Wizard tab could not be loaded.", e);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SparqlQueryTab.fxml"));
            tabSPARQLQuery.setContent(loader.load());
            SparqlQueryTabController controller = loader.getController();
            controller.setMainController(this);
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("SPARQL Query tab error", "The SPARQL Query tab could not be loaded.", e);
        }

        initializeValidationByMappingTab();

    }

    private void initializeValidationByMappingTab() {
        try {
            URL fxmlUrl = getClass().getResource("/fxml/ValidationByMapping.fxml");

            if (fxmlUrl == null) {
                throw new IOException("FXML not found: /fxml/ValidationByMapping.fxml");
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent root = loader.load();

            tabValidationByMapping.setContent(root);

        } catch (Exception e) {
            GUIhelper.showUserFriendlyError(
                    "Validation by Mapping tab error",
                    "The Validation by Mapping tab could not be loaded.",
                    e
            );
        }
    }

    // ============ Progress Bar Control Methods ============

    /**
     * Sets the progress bar value (0.0 to 1.0, or use ProgressIndicator.INDETERMINATE_PROGRESS)
     */
    public void setProgressBarValue(double progress) {
        Platform.runLater(() -> progressBar.setProgress(progress));
    }

    /**
     * Sets the progress bar to indeterminate state (animated)
     */
    public void setProgressBarIndeterminate() {
        Platform.runLater(() -> progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS));
    }

    /**
     * Sets the progress bar to 0 (empty)
     */
    public void resetProgressBar() {
        Platform.runLater(() -> progressBar.setProgress(0));
    }

    /**
     * Sets the progress bar to 1 (full)
     */
    public void completeProgressBar() {
        Platform.runLater(() -> progressBar.setProgress(1));
    }

    /**
     * Gets the current progress bar value
     */
    public double getProgressBarValue() {
        return progressBar.getProgress();
    }

    /**
     * Gets the ProgressBar control (advanced use)
     */
    public ProgressBar getProgressBar() {
        return progressBar;
    }

    // ============ End Progress Bar Control Methods ============

    @FXML
    // action on menu PSSE-PowerFactory compare
    private void actionMenuPSSEPF() throws FileNotFoundException {
        comparePssePF.comparePssePFresults();
    }

    @FXML
    // action on menu QoCDC 3.2.1 xml to Excel
    private void actionMenuQoCDCxls() throws FileNotFoundException {
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select QoCDC xml", "*.xml"));
        filechooser.setInitialDirectory(new File(prefs.get("LastWorkingFolder", "")));
        File file = filechooser.showOpenDialog(null);

        if (file != null) {// the file is selected
            prefs.put("LastWorkingFolder", file.getParent());

            Model model = ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file.toString());
            RDFDataMgr.read(model, inputStream, "http://entsoe.eu/CIM/Extensions/CGM-BP/2020", Lang.RDFXML);

            List<String> ruleName = new LinkedList<>();
            List<String> ruleSeverity = new LinkedList<>();
            List<String> ruleLevel = new LinkedList<>();
            List<String> ruleDescription = new LinkedList<>();
            List<String> ruleMessage = new LinkedList<>();

            Property propName = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule.name");
            Property propSeverity = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule.severity");
            Property propDescription = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule.description");
            Property propLevel = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule.level");
            Property propMessage = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule.message");

            List<Statement> ruleStmts = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule")).toList();
            for (Statement ruleStmt : ruleStmts) {
                if (model.contains(ruleStmt.getSubject(), propName)) {
                    ruleName.add(model.getRequiredProperty(ruleStmt.getSubject(), propName).getObject().toString());
                } else {
                    ruleName.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(), propSeverity)) {
                    ruleSeverity.add(model.getRequiredProperty(ruleStmt.getSubject(), propSeverity).getObject().toString());
                } else {
                    ruleSeverity.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(), propLevel)) {
                    ruleLevel.add(model.getRequiredProperty(ruleStmt.getSubject(), propLevel).getObject().toString());
                } else {
                    ruleLevel.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(), propDescription)) {
                    ruleDescription.add(model.getRequiredProperty(ruleStmt.getSubject(), propDescription).getObject().toString());
                } else {
                    ruleDescription.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(), propMessage)) {
                    ruleMessage.add(model.getRequiredProperty(ruleStmt.getSubject(), propMessage).getObject().toString());
                } else {
                    ruleMessage.add("NA");
                }


            }
            ExportFactory.exportQoCDC(ruleName, ruleSeverity, ruleDescription, ruleMessage, ruleLevel, "QoCDC321", "QoCDC321", "Save QoCDC");


        }
    }


    public void appendText(String valueOf) {
        Platform.runLater(() -> foutputWindow.appendText(valueOf));
    }

    @FXML
    //action menu item Tools -> Export RDFS description
    private void actionRDFSexportDescriptionMenu() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf"), "");

        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        if (file.getFirst() != null) {// the file is selected
            try {
                RDFDataMgr.read(model, new FileInputStream(file.getFirst()), Lang.RDFXML);
            } catch (FileNotFoundException e) {
                GUIhelper.showUserFriendlyError("RDFS loading error", "The selected RDFS file could not be opened.", e);
                progressBar.setProgress(0);
            }
            rdfsDescriptions(model);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }

    @FXML
    //action menu item Tools -> Export RDFS description
    private void actionRDFSexportTripplesMenu() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf", "*.xml"), "");

        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        if (file.getFirst() != null) {// the file is selected
            try {
                RDFDataMgr.read(model, new FileInputStream(file.getFirst()), "", Lang.RDFXML);
            } catch (FileNotFoundException e) {
                GUIhelper.showUserFriendlyError("RDF loading error", "The selected RDF file could not be opened.", e);
                progressBar.setProgress(0);
            }
            exportRDFToExcel(model);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }

    @FXML
    //action menu item Tools -> Export SHACL constraints information to Excel
    private void actionMenuExportSHACLInfo() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open SHACL files
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "SHACL files", List.of("*.ttl"), "");

        if (file != null) {// the file is selected
            boolean singleFile = false;
            if (file.size() > 1) {
                //open a question/confirmation dialog to ask on the export
                Alert alert1 = new Alert(Alert.AlertType.CONFIRMATION);
                alert1.setContentText("More than one .ttl is selected. Would you like to export the information in multiple .xlsx files or as one file with multiple tabs?.");
                alert1.setHeaderText("Confirm the way of export.");
                alert1.setTitle("Confirmation needed");

                ButtonType btnOneFile = new ButtonType("Export in one file");
                ButtonType btnMultiple = new ButtonType("Export in multiple files");
                alert1.getButtonTypes().setAll(btnOneFile, btnMultiple);
                Optional<ButtonType> result1 = alert1.showAndWait();
                if (result1.get() == btnOneFile) {
                    singleFile = true;
                }
            } else if (file.size() == 1) {
                singleFile = true;
            }

            ExportSHACLInformation.shaclInformationExport(singleFile, file);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }

    @FXML
    //action menu item Tools -> Export SHACL constraints information to Excel
    private void actionMenuExportAllSHACLInfo() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open SHACL files
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "SHACL files", List.of("*.ttl"), "");

        if (file != null) {// the file is selected
            boolean singleFile = false;
            if (file.size() > 1) {
                //open a question/confirmation dialog to ask on the export
                Alert alert1 = new Alert(Alert.AlertType.CONFIRMATION);
                alert1.setContentText("More than one .ttl is selected. Would you like to export the information in multiple .xlsx files or as one file with multiple tabs?.");
                alert1.setHeaderText("Confirm the way of export.");
                alert1.setTitle("Confirmation needed");

                ButtonType btnOneFile = new ButtonType("Export in one file");
                ButtonType btnMultiple = new ButtonType("Export in multiple files");
                alert1.getButtonTypes().setAll(btnOneFile, btnMultiple);
                Optional<ButtonType> result1 = alert1.showAndWait();
                if (result1.get() == btnOneFile) {
                    singleFile = true;
                }
            } else if (file.size() == 1) {
                singleFile = true;
            }

            ChoiceDialog<String> dialog = new ChoiceDialog<>(
                    "EA Import Format",
                    Arrays.asList(
                            "Complete SHACL Predicate Export",
                            "EA Import Format"
                    )
            );

            dialog.setTitle("Export SHACL information");
            dialog.setHeaderText("Choose SHACL export type");
            dialog.setContentText("Export mode:");

            Optional<String> result = dialog.showAndWait();

            if (result.isEmpty()) {
                return;
            }

            switch (result.get()) {

                case "Complete SHACL Predicate Export":
                    ExportSHACLInformation.exportCompleteSHACLInformation(singleFile, file);
                    break;

                case "EA Import Format":
                    ExportSHACLInformation.exportSHACLForEAImport(singleFile, file);
                    break;
            }

            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }


    @FXML
    //action menu item Tools -> Model Transformation
    private void actionModelTransformationMenu() throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        //open original xml files
        List<File> fileOrigModelList = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Original model ", List.of("*.xml", "*.zip"), "");

        //open SHACL files
        List<File> fileSHACLTransList = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "SPARQL Transformation files", List.of("*.ttl", "*.trig"), "");

        if (fileOrigModelList != null && fileSHACLTransList != null) {// the file is selected

            ModelManipulationFactory.modelTransformation(fileOrigModelList, fileSHACLTransList);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }

    @FXML
    private void actionGenInfoFromRDFS() throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF files", List.of("*.rdf"), "");
        List<Model> listModels = new LinkedList<>();
        if (file != null) {// the file is selected
            for (File fil : file) {
                Model model = ModelFactory.createDefaultModel(); // model is the rdf file
                try {
                    RDFDataMgr.read(model, new FileInputStream(fil), Lang.RDFXML);
                    listModels.add(model);
                } catch (FileNotFoundException e) {
                    GUIhelper.showUserFriendlyError("RDFS loading error", "One of the selected RDFS files could not be opened.", e);
                    progressBar.setProgress(0);
                }
            }
            RdfConvert.generateRDFSserializationInfo(listModels);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }
    }


    @FXML
    //action menu item Tools -> Generate properties info from RDFS (xls, ttl, json)
    private void actionRDFSInstanceDataInfoMenu() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF files", List.of("*.rdf"), "");
        boolean templateOnly = false;
        if (file != null) {// the file is selected
            shaclNodataMap = 1; // as no mapping is to be used for this task
            ExportInstanceDataTemplate.rdfsContent(file, templateOnly);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }
    }

    @FXML
    //Action for button "Clear" related to the output window
    private void actionBtnClear() {
        if (tabPaneDown.getSelectionModel().getSelectedItem().getText().equals("Output window")) { //clears Output window
            foutputWindow.clear();
        }
    }

    @FXML
    //Action for menu item "Quit"
    private void menuQuit() {
        Platform.exit(); // Exit the eu.griddigit.cimpal.application
    }

    @FXML
    // action on menu Convert Reference data to Common data
    private void actionMenuConvertRefToComData() throws IOException {

        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Reference data RDF files", List.of("*.rdf"), "");
        if (file != null) {// the file is selected
            shaclNodataMap = 1; // as no mapping is to be used for this task
            ModelManipulationFactory.generateCommonData(file);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }
    }

    @FXML
    public void actionValidateByMapping() {
        if (tabPaneConstraintsDetails == null || tabValidationByMapping == null) {
            System.err.println("[ERROR] Validation by Mapping tab is not initialized.");
            return;
        }

        tabPaneConstraintsDetails.getSelectionModel().select(tabValidationByMapping);
    }

    @FXML
    // action on menu Validate SHACL shapes
    private void actionSHACLSHACL() {

        progressBar.setProgress(0);
        Model shaclRefModel = null;
        shapeModels = null;

        //select file
        List<File> fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "SHACL Shape file", List.of("*.rdf", "*.ttl"), "");

        if (fileL != null) {// the file is selected
            for (int m = 0; m < fileL.size(); m++) {
                eu.griddigit.cimpal.main.util.ModelFactory.shapeModelLoad(m, fileL); //loads shape model

                if (shaclRefModel == null) {
                    shaclRefModel = ModelManipulationFactory.LoadSHACLSHACL();
                }
                ValidationReport report = ShaclValidator.get().validate(shaclRefModel.getGraph(), ((Model) shapeModels.get(m)).getGraph());

                if (report.conforms()) {
                    System.out.printf("SHACL shapes: %s conform to SHACL-SHACL validation.\n", fileL.get(m).getName());
                } else {
                    System.out.printf("Validation failed. SHACL shapes: %s does not conform to the SHACL-SHACL shapes.\n", fileL.get(m).getName());
                    System.out.println("Validation problems:");
                    ShaclTools.printSHACLreport(report);
                }
            }


            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }
    }

    @FXML
    // action on menu Generation of instance data based on xls template
    private void actionMenuInstanceDataGenxls() throws IOException {

        System.out.print("Conversion in progress.\n");
        progressBar.setProgress(0);
        shaclNodataMap = 1; // as this mapping should not be used for this task
        //select file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Input template instance data XLS", List.of("*.xlsx"), "");

        if (file != null) {// the file is selected
            MainController.inputXLS = file;
            //select file
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF profile file", List.of("*.rdf", "*.ttl"), "");

            if (file != null) {// the file is selected
                MainController.rdfProfileFileList = file;

                //String xmlBase = "http://entsoe.eu/ns/nc";
                String xmlBase = "http://iec.ch/TC57/CIM100";
                //String xmlBase = "";

                //set properties for the export
                String formatGeneratedModel = "61970-552 CIM XML (.xml)"; //fcbGenDataFormat.getSelectionModel().getSelectedItem().toString();
                Map<String, Object> saveProperties = new HashMap<>();
                if (formatGeneratedModel.equals("61970-552 CIM XML (.xml)")) {
                    saveProperties.put("filename", "test");
                    saveProperties.put("showXmlDeclaration", "true");
                    saveProperties.put("showDoctypeDeclaration", "false");
                    saveProperties.put("tab", "2");
                    saveProperties.put("relativeURIs", "same-document");
                    saveProperties.put("showXmlEncoding", "true");
                    saveProperties.put("xmlBase", xmlBase);
                    saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
                    saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
                    saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
                    saveProperties.put("useFileDialog", true);
                    saveProperties.put("fileFolder", "C:");
                    saveProperties.put("dozip", false);
                    saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
                    saveProperties.put("showXmlBaseDeclaration", "true");
                    saveProperties.put("sortRDF", "true");
                    saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName

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

                } else if (formatGeneratedModel.equals("Custom RDF XML Plain (.xml)")) {
                    saveProperties.put("filename", "test");
                    saveProperties.put("showXmlDeclaration", "true");
                    saveProperties.put("showDoctypeDeclaration", "false");
                    saveProperties.put("tab", "2");
                    saveProperties.put("relativeURIs", "same-document");
                    saveProperties.put("showXmlEncoding", "true");
                    saveProperties.put("xmlBase", xmlBase);
                    saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
                    saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
                    saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
                    saveProperties.put("useFileDialog", true);
                    saveProperties.put("fileFolder", "C:");
                    saveProperties.put("dozip", false);
                    saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
                    saveProperties.put("showXmlBaseDeclaration", "false");

                    saveProperties.put("putHeaderOnTop", true);
                    saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
                    saveProperties.put("extensionName", "RDF XML");
                    saveProperties.put("fileExtension", "*.xml");
                    saveProperties.put("fileDialogTitle", "Save RDF XML for");
                }

                boolean profileModelUnionFlag = false;
                boolean instanceModelUnionFlag = false;
                boolean shaclModelUnionFlag = false;
                String eqbdID = null;
                String tpbdID = null;
                boolean persistentEQflag = false;

                Map<String, Boolean> inputData = new HashMap<>();
                inputData.put("rdfs", true);
                inputData.put("baseModel", false);
                inputData.put("shacl", false);

                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

                ModelManipulationFactory.generateDataFromXls(xmlBase, saveProperties, guiHelper);

                progressBar.setProgress(1);
                System.out.print("Conversion finished.\n");
            } else {
                System.out.print("Conversion terminated.\n");
            }
        } else {
            System.out.print("Conversion terminated.\n");
        }
    }

    @FXML
    //This is the menu item "Create datatypes map" - loads RDFfile(s) and creates the map
    private void actionMenuDatatypeMap() throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        Map<String, RDFDatatype> dataTypeMap = DataTypeMaping.mapDatatypesFromRDF();
        if (dataTypeMap != null) {
            File saveFile = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom("Datatypes mapping files", List.of("*.properties"), "", "");
            Properties properties = new Properties();
            for (Object key : dataTypeMap.keySet()) {
                properties.put(key, dataTypeMap.get(key).toString());
            }
            if (saveFile != null) {
                properties.store(new FileOutputStream(saveFile.toString()), null);
            }

            Model modeldatatype = ModelFactory.createDefaultModel();
            modeldatatype.setNsPrefix("xsd", XSD.NS);
            modeldatatype.setNsPrefix("rdf", RDF.uri);
            modeldatatype.setNsPrefix("rdfs", RDFS.uri);

            for (Map.Entry<String, RDFDatatype> set :
                    dataTypeMap.entrySet()) {
                modeldatatype.add(ResourceFactory.createStatement(ResourceFactory.createResource(set.getKey()), RDF.type, RDF.Property));
                modeldatatype.add(ResourceFactory.createStatement(ResourceFactory.createResource(set.getKey()), RDF.type, RDFS.Literal));
                modeldatatype.add(ResourceFactory.createStatement(ResourceFactory.createResource(set.getKey()), RDFS.range, ResourceFactory.createProperty(set.getValue().getURI())));
            }

            OutputStream outInt = fileSaveDialog("Save RDF for datatypes: RDFdatatypes", "RDF XML", "*.rdf");
            try (outInt) {
                modeldatatype.write(outInt, RDFFormat.RDFXML.getLang().getLabel().toUpperCase(), "");
            }

            try (OutputStream outInt1 = fileSaveDialog("Save XLSX for datatypes: RDFdatatypes", "Excel", "*.xlsx")) {
                modeldatatype.write(outInt, RDFFormat.RDFXML.getLang().getLabel().toUpperCase(), "");

                ExcelTools.exportToExcelMap(dataTypeMap, outInt1);
            }

            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }
    }

    @FXML
    // action on menu Preferences
    private void actionMenuPreferences() {
        try {
            Stage guiPrefStage = new Stage();
            //Scene for the menu Preferences
            FXMLLoader fxmlLoader = new FXMLLoader();
            Parent rootPreferences = fxmlLoader.load(getClass().getResource("/fxml/preferencesGui.fxml"));
            Scene preferences = new Scene(rootPreferences);
            guiPrefStage.setScene(preferences);
            guiPrefStage.setTitle("Preferences");
            guiPrefStage.initModality(Modality.APPLICATION_MODAL);
            PreferencesController.initData(guiPrefStage);
            guiPrefStage.showAndWait();

        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Preferences window error", "The Preferences window could not be opened.", e);
        }
    }

    @FXML
    // action on menu About
    private void actionMenuAbout() {
        try {
            Stage guiAboutStage = new Stage();
            //Scene for the menu Preferences
            FXMLLoader fxmlLoader = new FXMLLoader();
            Parent rootAbout = fxmlLoader.load(getClass().getResource("/fxml/aboutGui.fxml"));
            Scene about = new Scene(rootAbout);
            guiAboutStage.setScene(about);
            guiAboutStage.setTitle("About");
            guiAboutStage.initModality(Modality.APPLICATION_MODAL);
            AboutController.initData(guiAboutStage);
            guiAboutStage.showAndWait();

        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("About window error", "The About window could not be opened.", e);
        }
    }

    @FXML
    // action on menu Convert Reference Data
    private void actionMenuConvertRefData() throws IOException {
        //select file 1
        List<File> fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected
//            fPathIDfile1.setText(fileL.toString());
            MainController.IDModel1 = fileL;
        } else {
//            fPathIDfile1.clear();
        }

        List<File> fileL1 = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "ap file", List.of("*.properties"), "");

        if (fileL1 != null) {// the file is selected
//            fPathIDmap.setText(fileL1.toString());
            MainController.IDmapList = fileL1;

        }

        RdfConvert.refDataConvert();
        progressBar.setProgress(1);

    }

    @FXML
    // action on menu Generate QAR
    private void actionQARMenu() throws IOException, XMLStreamException {
        //select the xlsx file
        List<File> fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Excel file with IDs", List.of("*.xlsx"), "Select input data: ");

        if (fileL != null) {// the file is selected
            for (File xmlfile : fileL) {
                // load input data xls
                ArrayList<Object> inputXLSdata;
                File excel = new File(xmlfile.toString());
                FileInputStream fis = new FileInputStream(excel);
                XSSFWorkbook book = new XSSFWorkbook(fis);
                int Num = book.getNumberOfSheets();

                for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
                    //XSSFSheet sheet = book.getSheetAt(sheetnum);
                    //String sheetname = sheet.getSheetName();
                    inputXLSdata = ExcelTools.importXLSX(xmlfile.toString(), sheetnum);

                    List<String> processed = new LinkedList<>();
                    for (int row = 1; row < inputXLSdata.size(); row += 4) {
                        String fileName = ((LinkedList<?>) inputXLSdata.get(row)).getFirst().toString();
                        String fileNameNoExt = fileName.substring(0, fileName.length() - 4);
                        String[] fileNameParts = fileNameNoExt.split("_", 5);
                        String timestamp = fileNameParts[0];
                        String process = fileNameParts[1];
                        String tso = fileNameParts[2];
                        String profile = fileNameParts[3];
                        String version = fileNameParts[4];
                        String fileProfile = ((LinkedList<?>) inputXLSdata.get(row)).get(1).toString();
                        String mas = ((LinkedList<?>) inputXLSdata.get(row)).get(2).toString();
                        String fileID_0 = ((LinkedList<?>) inputXLSdata.get(row)).get(3).toString();
                        String fileID_1 = ((LinkedList<?>) inputXLSdata.get(row + 1)).get(3).toString();
                        String fileID_2 = ((LinkedList<?>) inputXLSdata.get(row + 2)).get(3).toString();
                        String fileID_3 = ((LinkedList<?>) inputXLSdata.get(row + 3)).get(3).toString();
                        String fileID_EQBD = "urn:uuid:1d663a7c-bea9-492f-8173-5ae1aaee33fe";
                        String fileID_TPBD = "urn:uuid:181474c1-7754-45a3-b11e-a7588766e00d";
                        if (!processed.contains(timestamp + process + tso + version)) {
                            // Initialize Velocity engine
                            Properties properties = new Properties();
                            properties.setProperty("resource.loader", "classpath");
                            properties.setProperty("classpath.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");

                            VelocityEngine velocityEngine = new VelocityEngine(properties);
                            velocityEngine.init();

                            // Create Velocity template from the file
                            Template template = velocityEngine.getTemplate("QAReportTemplate.vm");

                            // Prepare data for the template
                            List<String> resources = Arrays.asList(
                                    fileID_0,
                                    fileID_1,
                                    fileID_2,
                                    fileID_3,
                                    fileID_EQBD,
                                    fileID_TPBD
                            );

                            // Create context and add data
                            VelocityContext context = new VelocityContext();
                            ZonedDateTime now = ZonedDateTime.now();
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
                            String formattedNow = now.format(formatter);
                            context.put("created", formattedNow);
                            context.put("schemeVersion", "2.0");
                            context.put("serviceProvider", "Global");
                            context.put("rslVersion", "6.1.030");
                            context.put("igmCreated", "2025-11-17T11:42:21.000Z");
                            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");
                            LocalDateTime localDateTime = LocalDateTime.parse(timestamp, inputFormatter);
                            ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneOffset.UTC);
                            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX");
                            String output = zonedDateTime.format(outputFormatter);
                            context.put("igmScenarioTime", output);
                            context.put("tso", tso);
                            context.put("version", version.replaceFirst("^0+(?!$)", ""));
                            context.put("processType", process);
                            context.put("qualityIndicator", "Valid");
                            context.put("resources", resources);
                            context.put("qarid", "urn:uuid:" + UUID.randomUUID());

                            // Merge data and template
                            StringWriter stringWriter = new StringWriter();
                            template.merge(context, stringWriter);

                            // Get the generated XML content
                            String xmlContent = stringWriter.toString();

                            File xmlFileNew = switch (tso) {
                                case "RTEFRANCE" ->
                                        new File("GLOBAL_" + timestamp + "_" + process + "_" + "FR_IGM" + "_" + version + ".xml");
                                case "REE" ->
                                        new File("GLOBAL_" + timestamp + "_" + process + "_" + "ES_IGM" + "_" + version + ".xml");
                                case "REN" ->
                                        new File("GLOBAL_" + timestamp + "_" + process + "_" + "PT_IGM" + "_" + version + ".xml");
                                default -> null;
                            };

                            // Write to file
                            try (FileWriter writer = new FileWriter(xmlfile.getParent() + "\\Output\\" + xmlFileNew)) {
                                writer.write(xmlContent);
                                //System.out.println("XML file saved successfully!");
                            } catch (IOException e) {
                                GUIhelper.showUserFriendlyError("File write error", "A generated XML file could not be saved.", e);
                            }
                        }
                        processed.add(timestamp + process + tso + version);
                    }
                }
            }
        }

        progressBar.setProgress(1);

    }

    //@FXML
    //action for button Create in the tab RDFS to SHACL
//    private void actionBtnConstructShacl(ActionEvent actionEvent) throws IOException {
//        excludeMRID = cbRDFSSHACLoptionDescr.isSelected();
//
//        String cbvalue;
//        Model shaclRefModel = null;
//        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem() == null) {
//            cbvalue = "";
//        } else {
//            cbvalue = fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString();
//        }
//        if (cbRDFSSHACLoption1.isSelected()) {
//            associationValueTypeOption = 1;
//        } else {
//            associationValueTypeOption = 0;
//        }
//        if (cbRDFSSHACLoptionTypeWithOne.isSelected()) {
//            associationValueTypeOptionSingle = 1;
//        } else {
//            associationValueTypeOptionSingle = 0;
//        }
//        if (cbRDFSSHACLabstract.isSelected()) {
//            shapesOnAbstractOption = 1;
//        } else {
//            shapesOnAbstractOption = 0;
//        }
//        if (cbRDFSSHACLinheritTree.isSelected()) {
//            exportInheritTree = 1;
//        } else {
//            exportInheritTree = 0;
//        }
//
//        shaclURIdatatypeAsResource = false;
//        shaclSkipNcPropertyReference = false;
//        if (cbRDFSSHACLuri.isSelected()){
//            shaclURIdatatypeAsResource = true;
//        }
//
//        if (cbRDFSSHACLncProp.isSelected()){
//            shaclSkipNcPropertyReference = true;
//        }
//
//
//        if (!treeViewProfileConstraints.getSelectionModel().getSelectedItems().isEmpty()) {
//            //depending on the value of the choice box "Save datatype map"
//            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("No map; No save")) {
//                shaclNodataMap = 1;
//            } else {
//                shaclNodataMap = 0;
//            }
//            if (shapeModels == null) {
//                shapeModels = new ArrayList<>();
//            }
//            if (shapeModelsNames == null) {
//                shapeModelsNames = new ArrayList<>();
//            }
//            shapeDatas = new ArrayList<>();
//            Map<String, RDFDatatype> dataTypeMapFromShapesComplete = new HashMap<>(); // this is the complete map for the export in one .properties
//            ArrayList<Integer> modelNumber = new ArrayList<>();
//            List<String> profileList = new ArrayList<>();
//            List<String> prefixes = new ArrayList<>();
//            List<String> namespaces = new ArrayList<>();
//            List<String> baseURIs = new ArrayList<>();
//            List<String> owlImports = new ArrayList<>();
//            for (int sel = 0; sel < treeViewProfileConstraints.getSelectionModel().getSelectedItems().size(); sel++) {
//                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(sel).getValue();
//                for (int i = 0; i < this.modelsNames.size(); i++) {
//                    if (((ArrayList<?>) this.modelsNames.get(i)).get(0).equals(selectedProfile)) {
//                        modelNumber.add(i);
//                        profileList.add(selectedProfile);
//                        prefixes.add(((ArrayList<?>) this.modelsNames.get(i)).get(1).toString());
//                        namespaces.add(((ArrayList<?>) this.modelsNames.get(i)).get(2).toString());
//                        baseURIs.add(((ArrayList<?>) this.modelsNames.get(i)).get(3).toString());
//                        owlImports.add(((ArrayList<?>) this.modelsNames.get(i)).get(4).toString());
//                    }
//                }
//            }
//            // ask for confirmation before proceeding
//            String title = "Confirmation needed";
//            String header = "The shapes will be generated with the following basic conditions. Please review and confirm in order to proceed.";
//            String contextText = "Could you confirm the information below?";
//            String labelText = "Details:";
//            //TODO: make this nicer
//            //set the content of the details window
//            String detailedText = "The following profiles are selected: \n";
//            detailedText = detailedText + profileList + "\n";
//            detailedText = detailedText + "The namespaces for the selected profiles are: \n";
//            detailedText = detailedText + prefixes + "\n";
//            detailedText = detailedText + namespaces + "\n";
//            detailedText = detailedText + "The base URIs for the selected profiles are: \n";
//            detailedText = detailedText + baseURIs + "\n";
//            detailedText = detailedText + "The owl:imports for the selected profiles are: \n";
//            detailedText = detailedText + owlImports + "\n";
//            Alert alert = GUIhelper.expandableAlert(title, header, contextText, labelText, detailedText);
//            alert.getDialogPane().setExpanded(true);
//
//            Optional<ButtonType> result = alert.showAndWait();
//            if (result.get() != ButtonType.OK) {
//                return;
//            }
//
//            baseprofilesshaclglag = 0;
//            baseprofilesshaclglag2nd = 0;
//            baseprofilesshaclglag3rd = 0;
//            baseprofilesshaclignorens = 0;
//            if (cbRDFSSHACLoptionBaseprofiles.isSelected()) { // load base profiles if the checkbox is selected
//                baseprofilesshaclglag = 1;
//                //load base profiles for shacl
//                List<File> basefiles = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select Base profiles");
//                if (basefiles != null) {
//                    unionmodelbaseprofilesshacl = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(basefiles, "", Lang.RDFXML, true);
//                    unionmodelbaseprofilesshaclinheritance = modelInheritance(unionmodelbaseprofilesshacl, true, true);
//                    unionmodelbaseprofilesshaclinheritanceonly = modelInheritance; // this contains the inheritance of the classes under OWL2.members
//

    /// /                    if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim") != null) {
    /// /                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim");
    /// /                    }
//                    if (cbRDFSSHACLoptionBaseprofilesIgnoreNS.isSelected()) {
//                        baseprofilesshaclignorens = 1;
//                    }
//
//                    if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim16") != null) {
//                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim16");
//                        cimPref = "cim16";
//                    } else if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim17") != null) {
//                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim17");
//                        cimPref = "cim17";
//                    } else if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim18") != null) {
//                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim18");
//                        cimPref = "cim18";
//                    }
//
//                    if (cbRDFSSHACLoptionBaseprofiles2nd.isSelected()) { // load base profiles if the checkbox is selected
//                        baseprofilesshaclglag2nd = 1;
//                        //load base profiles for shacl
//                        List<File> basefiles2nd = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select 2nd set of Base profiles");
//                        if (basefiles2nd != null) {
//                            unionmodelbaseprofilesshacl2nd = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(basefiles2nd, "", Lang.RDFXML, true);
//                            unionmodelbaseprofilesshaclinheritance2nd = modelInheritance(unionmodelbaseprofilesshacl2nd, true, true);
//                            unionmodelbaseprofilesshaclinheritanceonly2nd = modelInheritance; // this contains the inheritance of the classes under OWL2.members
//
//                            if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim16") != null) {
//                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim16");
//                                cim2Pref = "cim16";
//                            } else if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim17") != null) {
//                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim17");
//                                cim2Pref = "cim17";
//                            } else if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim18") != null) {
//                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim18");
//                                cim2Pref = "cim18";
//                            }
//
//                            if (cim2Pref.equals("cim16") || cim2Pref.equals("cim17") || cim2Pref.equals("cim18")) {
//                                unionmodelbaseprofilesshacl.add(unionmodelbaseprofilesshacl2nd);
//                                unionmodelbaseprofilesshaclinheritance.add(unionmodelbaseprofilesshaclinheritance2nd);
//                                unionmodelbaseprofilesshaclinheritanceonly.add(unionmodelbaseprofilesshaclinheritanceonly2nd);
//                            }
//
//                            if (cbRDFSSHACLoptionBaseprofiles3rd.isSelected()) { // load base profiles if the checkbox is selected
//                                baseprofilesshaclglag3rd = 1;
//                                //load base profiles for shacl
//                                List<File> basefiles3rd = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select 3rd set of Base profiles");
//                                if (basefiles3rd != null) {
//                                    unionmodelbaseprofilesshacl3rd = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(basefiles3rd, "", Lang.RDFXML, true);
//                                    unionmodelbaseprofilesshaclinheritance3rd = modelInheritance(unionmodelbaseprofilesshacl3rd, true, true);
//                                    unionmodelbaseprofilesshaclinheritanceonly3rd = modelInheritance; // this contains the inheritance of the classes under OWL2.members
//
//                                    if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim16") != null) {
//                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim16");
//                                        cim3Pref = "cim16";
//                                    } else if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim17") != null) {
//                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim17");
//                                        cim3Pref = "cim17";
//                                    } else if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim18") != null) {
//                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim18");
//                                        cim3Pref = "cim18";
//                                    }
//
//                                    if (cim3Pref.equals("cim16") || cim3Pref.equals("cim17") || cim3Pref.equals("cim18")) {
//                                        unionmodelbaseprofilesshacl.add(unionmodelbaseprofilesshacl3rd);
//                                        unionmodelbaseprofilesshaclinheritance.add(unionmodelbaseprofilesshaclinheritance3rd);
//                                        unionmodelbaseprofilesshaclinheritanceonly.add(unionmodelbaseprofilesshaclinheritanceonly3rd);
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//
//            shaclflaginverse = 0;
//            if (cbRDFSSHACLoptionInverse.isSelected()) {
//                shaclflaginverse = 1;
//            }
//
//            shaclflagCount = 0;
//            if (cbRDFSSHACLoptionCount.isSelected()) {
//                shaclflagCount = 1;
//                shaclflagCountDefaultURI = 1;
//                shaclCommonPref = "";
//                shaclCommonURI = "";
//                if (shaclNSCommonType.getSelectionModel().getSelectedItem().toString().equals("Custom namespace")) {
//                    shaclflagCountDefaultURI = 0;
//                    shaclCommonPref = fPrefixSHACLCommon.getText();
//                    shaclCommonURI = fURISHACLcommon.getText();
//                }
//            }
//
//
//            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
//
//            for (int m : modelNumber) {
//                //here the preparation starts
//                dataTypeMapFromShapes = new HashMap<>();
//
//                Model model = (Model) this.models.get(m);
//                String rdfNs = MainController.prefs.get("cimsNamespace", "");
//                String rdfCase = "";
//                if (rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2019") && cbvalue.equals("RDFS (augmented, v2019) by CimSyntaxGen")) {
//                    rdfCase = "RDFS2019";
//                } else if (rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2020") && cbvalue.equals("RDFS (augmented, v2020) by CimSyntaxGen")) {
//                    rdfCase = "RDFS2020";
//                    //this option is adding header to the SHACL that is generated.
//                } else if (rdfFormatInput.equals("CIMTool-merged-owl") && cbvalue.equals("CIMTool-merged-owl")) {
//                    rdfCase = "CIMToolOWL";
//                }
//
//
//                switch (rdfCase) {
//                    case "RDFS2019" -> {
//                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
//                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);
//
//                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles
//
//                        //here the preparation ends
//
//                        String nsPrefixprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile
//
//                        String nsURIprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile
//
//                        String baseURI = ((ArrayList<?>) this.modelsNames.get(m)).get(3).toString();
//                        //}
//                        String owlImport = ((ArrayList<?>) this.modelsNames.get(m)).get(4).toString();
//                        //generate the shape model
//                        Map<String,Boolean> rdfsToShaclGuiMapBool = new HashMap<>();
//                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData,rdfsToShaclGuiMapBool);
//
//
//                        if (baseprofilesshaclglag == 1) {
//                            shapeModel.setNsPrefix(cimPref, cimURI);
//                        }
//
//                        if (baseprofilesshaclglag2nd == 1) {
//                            shapeModel.setNsPrefix(cim2Pref, cim2URI);
//                        }
//
//                        if (baseprofilesshaclglag3rd == 1) {
//                            shapeModel.setNsPrefix(cim3Pref, cim3URI);
//                        }
//
//                        //add the owl:imports
//                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);
//
//                        shapeModels.add(shapeModel);
//                        shapeModelsNames.add(this.modelsNames.get(m));
//
//                        //open the ChoiceDialog for the save file and save the file in different formats
//                        String titleSaveAs = "Save as for shape model: " + ((ArrayList<?>) this.modelsNames.get(m)).get(0).toString();
//
//                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);
//
//                        //this is used for the printing of the complete map in option "All profiles in one map"
//                        for (Object key : dataTypeMapFromShapes.keySet()) {
//                            dataTypeMapFromShapesComplete.putIfAbsent((String) key, dataTypeMapFromShapes.get(key));
//                        }
//                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
//                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
//                            Properties properties = new Properties();
//
//                            for (Object key : dataTypeMapFromShapes.keySet()) {
//                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
//                            }
//                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
//                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);
//
//                        }
//                        //add the shapes in the tree view
//
//
//                        //exporting the model to ttl ready function
//
//                    }
//                    case "RDFS2020" -> {
//                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
//
//                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);
//
//                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles
//
//                        //here the preparation ends
//
//                        String nsPrefixprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile
//
//                        String nsURIprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile
//
//                        String baseURI = ((ArrayList<?>) this.modelsNames.get(m)).get(3).toString();
//                        //}
//                        String owlImport = ((ArrayList<?>) this.modelsNames.get(m)).get(4).toString();
//                        //generate the shape model
//                        Map<String,Boolean> rdfsToShaclGuiMapBool = new HashMap<>();
//                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData,rdfsToShaclGuiMapBool);
//
//                        //add the owl:imports
//                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);
//
//                        //add header
//                        if (model.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
//                            shapeModel = ShaclTools.addSHACLheader(shapeModel, baseURI);
//                        }
//
//                        if (baseprofilesshaclglag == 1) {
//                            shapeModel.setNsPrefix(cimPref, cimURI);
//                        }
//
//                        if (baseprofilesshaclglag2nd == 1) {
//                            shapeModel.setNsPrefix(cim2Pref, cim2URI);
//                        }
//
//                        if (baseprofilesshaclglag3rd == 1) {
//                            shapeModel.setNsPrefix(cim3Pref, cim3URI);
//                        }
//
//                        shapeModels.add(shapeModel);
//                        shapeModelsNames.add(this.modelsNames.get(m));
//                        //optimise prefixes, strip unused prefixes
//                        //if (stripPrefixes){
//                        Map<String, String> modelPrefMap = shapeModel.getNsPrefixMap();
//                        LinkedList<String> uniqueNamespacesList = new LinkedList<>();
//                        for (StmtIterator ns = shapeModel.listStatements(); ns.hasNext(); ) {
//                            Statement stmtNS = ns.next();
//                            if (!uniqueNamespacesList.contains(stmtNS.getSubject().getNameSpace())) {
//                                uniqueNamespacesList.add(stmtNS.getSubject().getNameSpace());
//                            }
//                            if (!uniqueNamespacesList.contains(stmtNS.getPredicate().getNameSpace())) {
//                                uniqueNamespacesList.add(stmtNS.getPredicate().getNameSpace());
//                            }
//                            if (stmtNS.getObject().isResource()) {
//                                if (!uniqueNamespacesList.contains(stmtNS.getObject().asResource().getNameSpace())) {
//                                    uniqueNamespacesList.add(stmtNS.getObject().asResource().getNameSpace());
//                                }
//                            }
//                        }
//                        LinkedList<Map.Entry<String, String>> entryToRemove = new LinkedList<>();
//                        for (Map.Entry<String, String> entry : modelPrefMap.entrySet()) {
//                            //String key = entry.getKey();
//                            String value = entry.getValue();
//
//                            // Check if either the key or value is present in uniqueNamespacesList
//                            if (!uniqueNamespacesList.contains(value)) {
//                                entryToRemove.add(entry);
//                            }
//                        }
//                        for (Map.Entry<String, String> entryTR : entryToRemove) {
//                            shapeModel.removeNsPrefix(entryTR.getKey());
//                        }
//
//
//                        // Create a new HashMap to store the unique key-value pairs
//                        HashMap<String, String> uniqueMap = new HashMap<>();
//
//                        // Create a Set to track unique values
//                        Set<String> uniqueValues = new HashSet<>();
//
//                        //Iterate through the original HashMap
//                        Map<String, String> origPrefMap = shapeModel.getNsPrefixMap();
//                        for (Map.Entry<String, String> entry : origPrefMap.entrySet()) {
//                            if (uniqueValues.add(entry.getValue())) {
//                                // If the value was added to the set, it means it's unique
//                                uniqueMap.put(entry.getKey(), entry.getValue());
//                            }
//                        }
//                        //TODO check if there is cim just to avoid that cim was deleted instead of cim16,cim17 or cim18 in cases where the namespace was the same
//                        shapeModel.clearNsPrefixMap();
//                        shapeModel.setNsPrefixes(uniqueMap);
//                        //}
//
//                        if (cbRDFSSHACLvalidate.isSelected()) { //do validation
//                            if (shaclRefModel == null) {
//                                shaclRefModel = ModelManipulationFactory.LoadSHACLSHACL();
//                            }
//                            ValidationReport report = ShaclValidator.get().validate(shaclRefModel.getGraph(), shapeModel.getGraph());
//
//                            if (report.conforms()) {
//                                System.out.print("Generated SHACL shapes conform to SHACL-SHACL validation.\n");
//                            } else {
//                                System.out.println("Validation failed. Data does not conform to the SHACL-SHACL shapes.\n");
//                                System.out.println("Validation problems:");
//                                ShaclTools.printSHACLreport(report);
//                            }
//                        }
//
//                        //open the ChoiceDialog for the save file and save the file in different formats
//                        String titleSaveAs = "Save as for shape model: " + ((ArrayList<?>) this.modelsNames.get(m)).getFirst().toString();
//                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);
//
//                        //this is used for the printing of the complete map in option "All profiles in one map"
//                        for (String key : dataTypeMapFromShapes.keySet()) {
//                            dataTypeMapFromShapesComplete.putIfAbsent(key, dataTypeMapFromShapes.get(key));
//                        }
//                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
//                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
//                            Properties properties = new Properties();
//
//                            for (String key : dataTypeMapFromShapes.keySet()) {
//                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
//                            }
//                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
//                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);
//
//                        }
//
//                        if (unionmodelbaseprofilesshaclinheritance == null && baseprofilesshaclglag == 0) {
//                            Model modelInh = modelInheritance(model, true, true);
//                            String titleSaveAsInh = "Save as for inheritance model: InheritanceStructure";
//                            ShaclTools.saveShapesFile(modelInh, "", 0, titleSaveAsInh);
//                        }
//
//                    }
//                    case "CIMToolOWL" -> {
//                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
//                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);
//
//                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles
//
//                        //here the preparation ends
//
//                        String nsPrefixprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile
//
//                        String nsURIprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile
//
//
//                        String baseURI = ((ArrayList<?>) this.modelsNames.get(m)).get(3).toString();
//                        //}
//                        String owlImport = ((ArrayList<?>) this.modelsNames.get(m)).get(4).toString();
//                        //generate the shape model
//                        Map<String,Boolean> rdfsToShaclGuiMapBool = new HashMap<>();
//                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData,rdfsToShaclGuiMapBool);
//
//                        //add the owl:imports
//                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);
//
//                        shapeModels.add(shapeModel);
//                        shapeModelsNames.add(this.modelsNames.get(m));
//
//                        //open the ChoiceDialog for the save file and save the file in different formats
//                        String titleSaveAs = "Save as for shape model: " + ((ArrayList<?>) this.modelsNames.get(m)).get(0).toString();
//                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);
//
//                        //this is used for the printing of the complete map in option "All profiles in one map"
//                        for (String key : dataTypeMapFromShapes.keySet()) {
//                            dataTypeMapFromShapesComplete.putIfAbsent(key, dataTypeMapFromShapes.get(key));
//                        }
//                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
//                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
//                            Properties properties = new Properties();
//
//                            for (String key : dataTypeMapFromShapes.keySet()) {
//                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
//                            }
//                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
//                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);
//
//                        }
//                    }
//                }
//            }
//
//            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("All profiles in one map")) {
//                File saveFile = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom("Datatypes mapping files", List.of("*.properties"), "", "");
//
//                if (saveFile != null) {
//                    //MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
//                    Properties properties = new Properties();
//
//                    for (String key : dataTypeMapFromShapesComplete.keySet()) {
//                        properties.put(key, dataTypeMapFromShapesComplete.get(key).toString());
//                    }
//                    properties.store(new FileOutputStream(saveFile.toString()), null);
//                }
//            }
//
//            if (cbRDFSSHACLinheritTree.isSelected() && unionmodelbaseprofilesshaclinheritance != null) {
//                //open the ChoiceDialog for the save file and save the file in different formats
//                String titleSaveAs = "Save as for inheritance model: InheritanceStructure";
//                ShaclTools.saveShapesFile(unionmodelbaseprofilesshaclinheritance, "", 0, titleSaveAs);
//            }
//
//            progressBar.setProgress(1);
//            System.out.print("Generation of SHACL shapes is completed.\n");
//
//        } else {
//            Alert alert = new Alert(Alert.AlertType.ERROR);
//            alert.setContentText("Please select a profile for which you would like to generate Shapes and the RDFS format.");
//            alert.setHeaderText(null);
//            alert.setTitle("Error - no profile selected");
//            alert.showAndWait();
//        }
//    }


    @FXML
    // action on button "Show/Hide source code" in the SHACL Shapes Browser
    private void actionBtnShowSourceCodeShacl(ActionEvent actionEvent) {
        if (btnShowSourceCodeDefineTab.isSelected()) {
            btnShowSourceCodeDefineTab.setText("Hide");
        } else {
            btnShowSourceCodeDefineTab.setText("Show");
            fsourceDefineTab.clear();
        }
    }

    //set expand in the Instance data tree view
    public static void treeInstanceDataExpand(TaggedTreeItem<String> expandedItem) {

        String tagValue = expandedItem.getTag();
        String modelname;
        Model selectedModel = null;
        switch (tagValue) {
            case "fileOrModel":
                modelname = expandedItem.getValue();
                for (Map.Entry<String, Model> entry : InstanceModelMap.entrySet()) {
                    String key = entry.getKey();
                    Model value = entry.getValue();
                    if (key.equals("unionModel") && modelname.equals("Union Model")) {
                        selectedModel = value;
                        break;
                    } else {
                        if (key.startsWith(modelname)) {
                            selectedModel = value;
                            break;
                        }
                    }
                }
                for (TreeItem<String> ti : expandedItem.getChildren()) {
                    String tiName = ti.getValue();
                    LinkedList<String> classInstance = InstanceDataFactory.getClassInstancesForTree(Objects.requireNonNull(selectedModel), tiName);

                    for (String i : classInstance) {
                        TaggedTreeItem<String> cInstanceItem = new TaggedTreeItem<>(i);
                        if (!containsChild(ti, i)) {
                            ti.getChildren().add(cInstanceItem);
                            cInstanceItem.setTag("classInstance");
                        }
                    }
                }
                break;
            case "classType":
                //modelname = expandedItem.getParent().getValue();
                modelname = MainController.getParentModelInTree(expandedItem).getValue();
                for (Map.Entry<String, Model> entry : InstanceModelMap.entrySet()) {
                    String key = entry.getKey();
                    Model value = entry.getValue();
                    if (key.equals("unionModel") && modelname.equals("Union Model")) {
                        selectedModel = value;
                        break;
                    } else {
                        if (key.startsWith(modelname)) {
                            selectedModel = value;
                            break;
                        }
                    }
                }
                for (TreeItem<String> ti : expandedItem.getChildren()) {
                    String tiName = ti.getValue();
                    LinkedList<String> classProperty = InstanceDataFactory.getClassPropertiesForTree(selectedModel, expandedItem.getValue(), tiName);

                    for (String i : classProperty) {
                        TaggedTreeItem<String> cPropItem = new TaggedTreeItem<>(i);
                        //if (!containsChild(ti, i)) {
                        ti.getChildren().add(cPropItem);
                        cPropItem.setTag("classProperty");
                        //}
                    }
                }
                break;
            case "classInstance":
                //modelname = expandedItem.getParent().getParent().getValue();
                modelname = MainController.getParentModelInTree(expandedItem).getValue();
                for (Map.Entry<String, Model> entry : InstanceModelMap.entrySet()) {
                    String key = entry.getKey();
                    Model value = entry.getValue();
                    if (key.equals("unionModel") && modelname.equals("Union Model")) {
                        selectedModel = value;
                        break;
                    } else {
                        if (key.startsWith(modelname)) {
                            selectedModel = value;
                            break;
                        }
                    }
                }
                for (TreeItem<String> ti : expandedItem.getChildren()) {
                    String tiName = ti.getValue();
                    String classInstance = InstanceDataFactory.getPropertiesRefClassForTree(selectedModel, expandedItem.getValue(), expandedItem.getParent().getValue(), tiName);

                    TaggedTreeItem<String> classItem = new TaggedTreeItem<>(classInstance);
                    if (!containsChild(ti, classInstance)) {
                        ti.getChildren().add(classItem);
                        classItem.setTag("classInstance");
                    }
                }
                break;
            case "classProperty":
                //modelname = expandedItem.getParent().getParent().getParent().getValue();
                modelname = MainController.getParentModelInTree(expandedItem).getValue();
                for (Map.Entry<String, Model> entry : InstanceModelMap.entrySet()) {
                    String key = entry.getKey();
                    Model value = entry.getValue();
                    if (key.equals("unionModel") && modelname.equals("Union Model")) {
                        selectedModel = value;
                        break;
                    } else {
                        if (key.startsWith(modelname)) {
                            selectedModel = value;
                            break;
                        }
                    }
                }
                for (TreeItem<String> ti : expandedItem.getChildren()) {
                    String tiName = ti.getValue();
                    String className = null;
                    if (Objects.requireNonNull(selectedModel).listStatements(ResourceFactory.createResource("http://griddigit.eu#" + tiName.split("\\|", 2)[1]), RDF.type, (RDFNode) null).hasNext()) {
                        Statement typeStmt = selectedModel.listStatements(ResourceFactory.createResource("http://griddigit.eu#" + tiName.split("\\|", 2)[1]), RDF.type, (RDFNode) null).next();
                        className = selectedModel.getNsURIPrefix(typeStmt.getObject().asResource().getNameSpace()) + ":" + typeStmt.getObject().asResource().getLocalName();
                    }
                    LinkedList<String> classProperty = InstanceDataFactory.getClassPropertiesForTree(selectedModel, className, tiName);

                    for (String i : classProperty) {
                        TaggedTreeItem<String> cPropItem = new TaggedTreeItem<>(i);
                        ti.getChildren().add(cPropItem);
                        cPropItem.setTag("classProperty");
                    }
                }
                break;
        }


    }

    public static boolean containsChild(TreeItem<String> parent, String childValue) {
        for (TreeItem<String> child : parent.getChildren()) {
            if (child.getValue().equals(childValue)) {
                return true;
            }
        }
        return false;
    }

    public static TaggedTreeItem<String> getParentModelInTree(TaggedTreeItem<String> child) {
        TaggedTreeItem currentItem = (TaggedTreeItem) child.getParent();
        while (currentItem != null) {
            Object tag = currentItem.getTag();
            if (tag != null && tag.equals("fileOrModel")) {
                break;
            }
            currentItem = (TaggedTreeItem) currentItem.getParent();
        }
        return currentItem;
    }

    @FXML
    // //action for tab pane down - the tab pane with the source code
    private void actionTabConstraintsSourceCode() {
        btnShowSourceCodeDefineTab.setDisable(!tabConstraintsSourceCode.isSelected());
    }


    public static Map getDataTypeMapFromShapes() {
        return dataTypeMapFromShapes;
    }

    public static void setDataTypeMapFromShapes(Map dataTypeMapFromShapes) {
        MainController.dataTypeMapFromShapes = dataTypeMapFromShapes;
    }

    public static int getShaclNodataMap() {
        return shaclNodataMap;
    }

    public static void setShaclNodataMap(int shaclNodataMap) {
        MainController.shaclNodataMap = shaclNodataMap;
    }

    @FXML
    private void actionRestoreTtlFromConstraintCsv() {
        ShaclTools.restoreTtlFromConstraintCsv();
    }


    @FXML
    public void actionCreateQARTemplate(ActionEvent actionEvent) throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        List<File> files = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "QAR models", List.of("*.xml", "*.zip"), "Select QAR models.");

        if (files != null) {// the file is selected
            CreateTemplateFromXMLQAR(files);
            progressBar.setProgress(1);
        } else {
            progressBar.setProgress(0);
        }

    }

    @FXML
    public void actionGenerateManifest(ActionEvent actionEvent) {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        try {
            List<File> selectedFiles = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                    false,
                    "Model files",
                    List.of("*.xml", "*.zip"),
                    "Select model file(s) for manifest generation"
            );

            if (selectedFiles == null || selectedFiles.isEmpty()) {
                progressBar.setProgress(0);
                return;
            }

            Map<String, Model> loadedModels = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadPerFiles(
                    selectedFiles,
                    "",
                    Lang.RDFXML
            );
            if (loadedModels == null || loadedModels.isEmpty()) {
                throw new IOException("Could not load selected file(s).");
            }

            File outputFile = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom(
                    "Turtle file",
                    List.of("*.ttl"),
                    "Save manifest.ttl",
                    "manifest.ttl"
            );

            if (outputFile == null) {
                progressBar.setProgress(0);
                return;
            }

            String accessUrl = selectedFiles.size() == 1 ? selectedFiles.getFirst().toURI().toString() : null;
            ManifestGenerator.generateManifestTtl(loadedModels, selectedFiles, accessUrl, outputFile);

            progressBar.setProgress(1);
            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.setTitle("Manifest generated");
            ok.setHeaderText(null);
            ok.setContentText("Manifest saved to:\n" + outputFile.getAbsolutePath() + "\n\nProcessed files: " + selectedFiles.size());
            ok.showAndWait();
        } catch (Exception e) {
            progressBar.setProgress(0);
            GUIhelper.showUserFriendlyError("Manifest generation failed", "The manifest could not be generated. Please review details and share them with support.", e);
        }
    }

    @FXML
    public void actionRunSPARQLQuery(ActionEvent actionEvent) {
        if (tabPaneConstraintsDetails != null && tabSPARQLQuery != null) {
            tabPaneConstraintsDetails.getSelectionModel().select(tabSPARQLQuery);
        }
    }

    @FXML
    public void actionPopulateMissingAttributes(ActionEvent actionEvent) {

        try {
            List<File> selectedModelFiles = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                    false,
                    "Model files",
                    List.of("*.xml", "*.zip"),
                    "Select model file(s) to populate missing attributes"
            );

            List<File> selectedExcelFile = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                    true,
                    "Template file",
                    List.of("*.xlsx"),
                    "Select excel template file with missing attributes"
            );

            if (selectedModelFiles == null || selectedModelFiles.isEmpty()) {
                progressBar.setProgress(0);
                GUIhelper.showUserFriendlyError("Model files not selected", "Please select one or more model files to populate missing attributes.", null);
                return;
            }

            if (selectedExcelFile == null || selectedExcelFile.isEmpty()) {
                progressBar.setProgress(0);
                GUIhelper.showUserFriendlyError("Excel template file not selected", "Please select an excel template file with missing attributes to populate the models.", null);
                return;
            }

            File outputFolder = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                    "Output folder"
            );

            if (outputFolder == null) {
                progressBar.setProgress(0);
                GUIhelper.showUserFriendlyError("Output folder not selected", "Please select an output folder to save the populated models.", null);
                return;
            }
            ArrayList<Object> xlsData = ExcelTools.importXLSX(selectedExcelFile.getFirst().getAbsolutePath(), "ClassAttributes");

            Map<String, Map<String, String>> classAttributesMap = null;

            String xmlBase = "https://cim.ucaiug.io/ns";

            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

            for (File selectedFile : selectedModelFiles) {
                Model model = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(List.of(selectedFile), xmlBase, Lang.RDFXML, false, false)
                        .get("unionModel");

                String cimNamespace = model.getNsPrefixURI("cim");
                if (cimNamespace != null) {
                    xmlBase = cimNamespace.endsWith("#")
                            ? cimNamespace.substring(0, cimNamespace.length() - 1)
                            : cimNamespace;
                }

                if (classAttributesMap == null) {
                    classAttributesMap = AttributeInjector.getClassAttributesMap(xlsData, model.getNsPrefixMap());
                }

                Model extendedModel = AttributeInjector.injectAttributes(model, classAttributesMap);

                Map<String, Object> saveProperties = new HashMap<>();

                saveProperties.put("filename", selectedFile.getName());
                saveProperties.put("showXmlDeclaration", "true");
                saveProperties.put("showDoctypeDeclaration", "false");
                saveProperties.put("tab", "2");
                saveProperties.put("relativeURIs", "same-document");
                saveProperties.put("showXmlEncoding", "true");
                saveProperties.put("xmlBase", xmlBase);
                saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
                saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
                saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
                saveProperties.put("useFileDialog", false);
                saveProperties.put("fileDialogTitle", "");
                saveProperties.put("fileFolder", outputFolder.getAbsolutePath());
                saveProperties.put("dozip", false);
                saveProperties.put("instanceData", "false"); //this is to only print the ID and not with namespace
                saveProperties.put("showXmlBaseDeclaration", "false");
                saveProperties.put("sortRDF", "true");
                saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
                saveProperties.put("putHeaderOnTop", true);
                saveProperties.put("headerClassResource", AttributeInjector.getModelHeader(model));
                saveProperties.put("extensionName", "RDF XML");
                saveProperties.put("fileExtension", "*.xml");

                Set<Resource> rdfAboutList = new HashSet<>();
                Set<Resource> rdfEnumList = new HashSet<>();

                String keyword = model.listStatements(null, ResourceFactory.createProperty("http://www.w3.org/ns/dcat#keyword"), (RDFNode) null)
                        .toList()
                        .stream()
                        .map(stmt -> stmt.getObject().asLiteral().getString().toLowerCase())
                        .findFirst()
                        .orElse(null);
                if ((boolean) saveProperties.get("useAboutRules")) {
                    rdfAboutList = LoadRDFAbout(xmlBase, keyword);
                    rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                }
                if ((boolean) saveProperties.get("useEnumRules")) {
                    rdfEnumList = LoadRDFEnum(xmlBase, keyword);
                }
                if (saveProperties.containsKey("rdfAboutList")) {
                    saveProperties.replace("rdfAboutList", rdfAboutList);
                } else {
                    saveProperties.put("rdfAboutList", rdfAboutList);
                }
                if (saveProperties.containsKey("rdfEnumList")) {
                    saveProperties.replace("rdfEnumList", rdfEnumList);
                } else {
                    saveProperties.put("rdfEnumList", rdfEnumList);
                }

                InstanceDataFactory.saveInstanceData(extendedModel, saveProperties);
            }

            progressBar.setProgress(1);
        } catch (Exception e) {
            progressBar.setProgress(0);
            GUIhelper.showUserFriendlyError("Populate missing attributes failed", "The operation could not be completed. Please review details and share them with support.", e);
        }
    }


}

