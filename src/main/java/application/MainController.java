/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package application;

import core.*;
import common.customWriter.CustomRDFFormat;
import gui.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;
import util.CompareFactory;
import util.ExcelTools;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static core.ExportRDFSdescriptions.rdfsDescriptions;
import static core.RdfConvert.fileSaveDialog;
import static core.RdfConvert.modelInheritance;
import static core.ShaclTools.fileSave;


public class MainController implements Initializable {

    public TabPane tabPaneConstraintsDetails;
    public Tab tabCreateCompleteSM1;
    public Tab tabCreateCompleteSM11;
    public Button btnRunExcelShape;
    public Button btnResetExcelShape;
    public Tab tabCreateCompleteSM2;
    public Button fbtnRunRDFConvert;
    public Tab tabOutputWindow;
    public Button btnResetIDComp;
    public Font x3;
    public Button btnResetRDFComp;
    @FXML
    public TreeView treeViewInstanceData;
    public TextField fPrefixGenerateTab1;
    public TextField fshapesBaseURIDefineTab1;
    public TextField fURIGenerateTab1;
    public TextField fowlImportsDefineTab1;
    // Shacl organizer
    @FXML
    public TextField fPathShaclFilesToOrganize;
    public TextField fPathXLSfileForShacl;
    public TextField fbaseURIShacl;
    public Button btnRunShaclOrganizer;
    public Button btnResetShaclOrganizer;
    public TextField fPrefixShaclOrganizer;
    public TextField fNSShaclOrganizer;
    @FXML
    private TableView tableViewBrowseID;
    public TableColumn itemColumnBrowseModifyID;
    public TableColumn valueColumnBrowseModifyID;
    @FXML
    private TextArea foutputWindow;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private TextField fPathRdffile1;
    @FXML
    private TextField fPathRdffile2;
    @FXML
    private Button btnRunRDFcompare;
    @FXML
    private TabPane tabPaneDown;
    public static Preferences prefs;
    @FXML
    private CheckBox cbShowUnionModelOnly;

    @FXML
    private Button btnConstructShacl;
    @FXML
    private CheckBox cbApplyDefNsDesignTab;
    @FXML
    private ChoiceBox fselectDatatypeMapDefineConstraints;
    @FXML
    private ChoiceBox fcbRDFSformat;
    @FXML
    private TreeView<String> treeViewProfileConstraints;
    @FXML
    private CheckBox cbApplyDefBaseURIDesignTab;
    @FXML
    private Button btnApply;
    @FXML
    private Tab tabCreateCompleteSM;
    @FXML
    private TextField fPrefixCreateCompleteSMTab;
    @FXML
    private TextField fURICreateCompleteSMTab;
    @FXML
    private TextField fshapesBaseURICreateCompleteSMTab;
    @FXML
    private TextField fowlImportsCreateCompleteSMTab;
    @FXML
    private ChoiceBox cbProfilesVersionCreateCompleteSMTab;
    @FXML
    private ChoiceBox fcbRDFSformatShapes;
    @FXML
    private TextField fPathRdffileForExcel;
    @FXML
    private ChoiceBox fcbRDFSformatForExcel;
    @FXML
    private TextField fbaseURIShapeExcel;
    @FXML
    private TextField fPrefixExcelShape;
    @FXML
    private TextField fNSexcelShape;
    @FXML
    private TextField fPathXLSfileForShape;
    @FXML
    private TextField fsourcePathTextField;
    @FXML
    private ChoiceBox fsourceFormatChoiceBox;
    @FXML
    private ChoiceBox ftargetFormatChoiceBox;
    @FXML
    private TextField frdfConvertXmlBase;
    @FXML
    private CheckBox fcbShowXMLDeclaration;
    @FXML
    private CheckBox fcbShowDoctypeDeclaration;
    @FXML
    private TextField fRDFconvertTab;
    @FXML
    private ChoiceBox fcbRelativeURIs;
    @FXML
    private ChoiceBox fcbRDFformat;
    @FXML
    private CheckBox fcbRDFConvertInheritanceOnly;
    @FXML
    private CheckBox fcbRDFconvertModelUnion;
    @FXML
    private CheckBox fcbRDFConverInheritanceList;
    @FXML
    private CheckBox fcbRDFConverInheritanceListConcrete;
    @FXML
    private CheckBox fcbStripPrefixes;
    @FXML
    private TreeView treeViewConstraints;
    //    @FXML
//    private TreeView treeViewInstanceData;
    @FXML
    private TextField fPrefixGenerateTab;
    @FXML
    private TextField fURIGenerateTab;
    @FXML
    private TextField fshapesBaseURIDefineTab;
    @FXML
    private TextField fowlImportsDefineTab;
    @FXML
    private TableView tableViewBrowseModify;
    @FXML
    private TableColumn itemColumnBrowseModify;
    @FXML
    private TableColumn valueColumnBrowseModify;
    @FXML
    private Tab tabConstraintsSourceCode;
    @FXML
    private ToggleButton btnShowSourceCodeDefineTab;
    @FXML
    private TextArea fsourceDefineTab;
    @FXML
    private ChoiceBox fcbIDformat;
    @FXML
    private Button btnRunIDcompare;
    @FXML
    private TextField fPathIDfile1;
    @FXML
    private TextField fPathIDfile2;
    @FXML
    private ChoiceBox fcbIDmap;
    @FXML
    private TextField fPathIDmap;
    @FXML
    private TextField fPathIDmapXmlBase;
    @FXML
    private CheckBox fcbIDcompCount;
    @FXML
    private CheckBox fcbIDcompSVonly;
    @FXML
    private CheckBox fcbIDcompIgnoreDL;
    @FXML
    private CheckBox fcbIDcompIgnoreSV;
    @FXML
    private CheckBox fcbIDcompIgnoreTP;
    @FXML
    private Button fBTbrowseIDmap;
    @FXML
    private CheckBox fcbIDcompSVonlyCN;
    @FXML
    private CheckBox cbRDFSSHACLoption1;
    @FXML
    private CheckBox fcbIDcompSolutionOverview;
    @FXML
    private CheckBox fcbIDcompShowDetails;
    @FXML
    private CheckBox fcbRDFcompareCimVersion;
    @FXML
    private CheckBox fcbRDFcompareProfileNS;
    @FXML
    private TextField fPrefixRDFCompare;
    @FXML
    private CheckBox cbRDFSSHACLoptionDescr;
    @FXML
    private CheckBox fcbRDFConveraddowl;
    @FXML
    private CheckBox fcbRDFconvertModelUnionDetailed;
    @FXML
    private CheckBox cbRDFSSHACLoptionBaseprofiles;
    @FXML
    private CheckBox cbRDFSSHACLoptionInverse;
    @FXML
    private CheckBox cbRDFSSHACLoptionBaseprofiles2nd;
    @FXML
    private CheckBox cbRDFSSHACLoptionBaseprofiles3rd;
    @FXML
    private CheckBox cbRDFSSHACLoptionBaseprofilesIgnoreNS;
    @FXML
    private CheckBox fcbSortRDF;
    @FXML
    private CheckBox fcbRDFconvertInstanceData;
    @FXML
    private ChoiceBox fcbRDFsortOptions;
    @FXML
    private CheckBox fcbRDFconvertFixPackage;


    public static File rdfModel1;
    public static File rdfModel2;
    public static List<File> IDModel1;
    public static List<File> IDModel2;
    public static List<File> IDmapList;
    public static int IDmapSelect;
    public static File rdfModelExcelShacl;
    public static File xlsFileExcelShacl;
    public static ArrayList<Object> compareResults;
    public static List<String> rdfsCompareFiles;
    private List<File> selectedFile;

    private ArrayList<Object> models;
    private ArrayList<Object> modelsNames;
    public static ArrayList<Object> shapeModelsNames;

    private ArrayList<String> packages;
    public static ArrayList<Object> shapeModels;
    public static ArrayList<Object> shapeDatas;
    private static Map<String, RDFDatatype> dataTypeMapFromShapes;
    private static int shaclNodataMap;
    public static File rdfConvertFile;
    public static List rdfConvertFileList;

    private static String defaultShapesURI;
    public static String rdfFormatInput;

    public static TreeView treeViewConstraintsStatic;
    public static TreeView treeViewIDStatic;
    private static Map<TreeItem, String> treeMapConstraints;
    private static Map<TreeItem, String> treeMapID;
    private static Map<String, TreeItem> treeMapConstraintsInverse;
    public static Integer associationValueTypeOption;

    public static TextArea foutputWindowVar;
    public static boolean excludeMRID;
    public static List<File> inputXLS;
    public static List<File> rdfProfileFileList;

    public static int baseprofilesshaclglag;
    public static int baseprofilesshaclglag2nd;
    public static int baseprofilesshaclglag3rd;
    public static int baseprofilesshaclignorens;
    public static int shaclflaginverse;
    public static Model unionmodelbaseprofilesshacl;
    public static Model unionmodelbaseprofilesshacl2nd;
    public static Model unionmodelbaseprofilesshacl3rd;
    public static Model unionmodelbaseprofilesshaclinheritance;
    public static Model unionmodelbaseprofilesshaclinheritance2nd;
    public static Model unionmodelbaseprofilesshaclinheritance3rd;
    public static Model unionmodelbaseprofilesshaclinheritanceonly;
    public static Model unionmodelbaseprofilesshaclinheritanceonly2nd;
    public static Model unionmodelbaseprofilesshaclinheritanceonly3rd;
    public static String cim2URI;
    public static String cim3URI;
    public static String cimURI;
    public static String cimPref;
    public static String cim2Pref;
    public static String cim3Pref;

    public static Map<String, Model> InstanceModelMap;
    public static boolean treeID;


    public MainController() {

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
        treeViewConstraintsStatic = treeViewConstraints;
        treeViewIDStatic = treeViewInstanceData;
        foutputWindowVar = foutputWindow;
        //initialization of the Browse and Modify table - SHACL Shapes Browse
        tableViewBrowseModify.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableViewBrowseModify.setEditable(false);
        tableViewBrowseModify.setPlaceholder(new Label("No shape properties present"));

        Callback<TableColumn, TableCell> cellFactory = p -> new ComboBoxCell();

        //set column 1
        itemColumnBrowseModify.setCellValueFactory(new PropertyValueFactory<TableColumnsSetup, String>("column1"));
        itemColumnBrowseModify.setCellFactory(cellFactory);
        itemColumnBrowseModify.setOnEditCommit(
                (EventHandler<TableColumn.CellEditEvent<TableColumnsSetup, String>>) t -> t.getTableView().getItems().get(
                        t.getTablePosition().getRow()).setColumn1(t.getNewValue())
        );

        //set column 2
        valueColumnBrowseModify.setCellValueFactory(new PropertyValueFactory<TableColumnsSetup, String>("column2"));
        Callback<TableColumn, TableCell> cellFactoryC2 = p -> new TextAreaEditTableCell();
        valueColumnBrowseModify.setCellFactory(cellFactoryC2);
        valueColumnBrowseModify.setOnEditCommit(
                (EventHandler<TableColumn.CellEditEvent<TableColumnsSetup, String>>) t -> t.getTableView().getItems().get(
                        t.getTablePosition().getRow()).setColumn2(t.getNewValue())
        );

        try {
            if (!Preferences.userRoot().nodeExists("CimPal")) {
                prefs = Preferences.userRoot().node("CimPal");
                //set the default preferences
                PreferencesController.prefDefault();
            } else {
                prefs = Preferences.userRoot().node("CimPal");
            }
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }

        cbProfilesVersionCreateCompleteSMTab.getItems().addAll(
                "IEC TS 61970-600-1&2 (CGMES 2.4.15)",
                "IEC 61970-600-1&2 (CGMES 3.0.0)",
                "IEC 61970-452:Ed4",
                "Network Codes profiles,releases before 2.3",
                "Network Codes profiles, release 2.3",
                "IEC 61970-457:Ed2",
                "LTDS profiles, release 1.0",
                "Other"
        );

        fselectDatatypeMapDefineConstraints.getItems().addAll(
                "No map; No save", // the map will not be generated and not saved
                "All profiles in one map", // the save choice will be for all profile in one file.
                "Per profile" //	the save option will be asked after every shacl file i.e. per profile.
        );

        fcbRDFSformat.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen",
                "RDFS (augmented) by CimSyntaxGen with CIMTool",
                "RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen",
                "Universal method inlc. SHACL Shapes"

        );
        fcbRDFSformat.getSelectionModel().selectLast();

        fcbRDFSformatShapes.getItems().addAll(
                "RDFS (augmented, v2020) by CimSyntaxGen",
                "RDFS (augmented, v2019) by CimSyntaxGen",
                "Merged OWL CIMTool (NOT READY)"
        );
        fcbRDFSformatShapes.getSelectionModel().selectFirst();

        fcbRDFSformatForExcel.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();

        fsourceFormatChoiceBox.getItems().addAll(
                "RDF XML (.rdf or .xml)",
                "RDF Turtle (.ttl)",
                "JSON-LD (.jsonld)"
        );
        fsourceFormatChoiceBox.getSelectionModel().clearSelection();

        ftargetFormatChoiceBox.getItems().addAll(
                "RDF XML (.rdf or .xml)",
                "RDF Turtle (.ttl)",
                "JSON-LD (.jsonld)"
        );
        ftargetFormatChoiceBox.getSelectionModel().clearSelection();

        fcbRelativeURIs.getItems().addAll(
                "same-document",
                "network",
                "absolute",
                "relative",
                "parent",
                "grandparent"

        );
        fcbRelativeURIs.getSelectionModel().selectFirst();

        fcbRDFformat.getItems().addAll(
                "RDFXML_PLAIN",
                "RDFXML",
                "RDFXML_PRETTY",
                "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)",
                "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)",
                "RDFXML_ABBREV"

        );
        fcbRDFformat.getSelectionModel().selectFirst();

        fcbIDformat.getItems().addAll(
                "IEC 61970-600-1&2 (CGMES 3.0.0)",
                "IEC 61970-45x (CIM17)",
                "IEC TS 61970-600-1&2 (CGMES 2.4.15)",
                "IEC 61970-45x (CIM16)",
                "Other CIM version"

        );


        fcbIDmap.getItems().addAll(
                "No datatypes mapping",
                "Generate from RDFS",
                "Use saved map"
        );

        fcbRDFsortOptions.getItems().addAll(
                "Sorting by local name",
                "Sorting by prefix"
        );

        //Adding action to the choice box
        ftargetFormatChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBRDFconvertTarget());

        //Adding action to the choice box
        fcbIDformat.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBIDformat());

        //Adding action to the choice box
        fcbIDmap.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBIDmap());

        //Adding action to the choice box
        fcbRDFSformat.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBfcbRDFSformat());

        //TODO: see how to have this default on the screen
        defaultShapesURI = "/Constraints";
    }


    public void appendText(String valueOf) {
        Platform.runLater(() -> foutputWindow.appendText(valueOf));
    }

    @FXML
    //action menu item Tools -> Export RDFS description
    private void actionRDFSexportDescriptionMenu() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = util.ModelFactory.filechoosercustom(true, "RDF files", List.of("*.rdf"), "");

        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        if (file.getFirst() != null) {// the file is selected
            try {
                RDFDataMgr.read(model, new FileInputStream(file.getFirst()), Lang.RDFXML);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                progressBar.setProgress(0);
            }
            rdfsDescriptions(model);
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
        List<File> file = util.ModelFactory.filechoosercustom(false, "SHACL files", List.of("*.ttl"), "");

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
    private void actionGenInfoFromRDFS() throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = util.ModelFactory.filechoosercustom(false, "RDF files", List.of("*.rdf"), "");
        List<Model> listModels = new LinkedList<>();
        if (file != null) {// the file is selected
            for (File fil : file) {
                Model model = ModelFactory.createDefaultModel(); // model is the rdf file
                try {
                    RDFDataMgr.read(model, new FileInputStream(fil), Lang.RDFXML);
                    listModels.add(model);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
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
    //action menu item Tools -> Instance data Excel template based on RDFS
    private void actionRDFSInstanceDataTemplateMenu() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = util.ModelFactory.filechoosercustom(false, "RDF files", List.of("*.rdf"), "");
        boolean templateOnly = true;
        if (file != null) {// the file is selected
            shaclNodataMap = 1; // as no mapping is to be used for this task
            ExportInstanceDataTemplate.rdfsContent(file, templateOnly);
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
        List<File> file = util.ModelFactory.filechoosercustom(false, "RDF files", List.of("*.rdf"), "");
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
        Platform.exit(); // Exit the application
    }

    @FXML
    //Action for button "Reset" related to the RDFS Comparison
    private void actionBtnResetRDFComp() {
        fPathRdffile1.clear();
        fPathRdffile2.clear();
        btnRunRDFcompare.setDisable(true);
        progressBar.setProgress(0);

    }

    @FXML
    //Action for button "Reset" related to the Instance data Comparison
    private void actionBtnResetIDComp() {
        fPathIDfile1.clear();
        fPathIDfile2.clear();
        fPathIDmap.clear();
        fPathIDmapXmlBase.clear();
        fcbIDformat.getSelectionModel().clearSelection();
        fcbIDmap.getSelectionModel().clearSelection();
        btnRunIDcompare.setDisable(true);
        fcbIDcompIgnoreSV.setSelected(false);
        fcbIDcompIgnoreDL.setSelected(false);
        fcbIDcompIgnoreTP.setSelected(false);
        fcbIDcompSVonly.setSelected(false);
        fcbIDcompCount.setSelected(false);
        fcbIDcompSVonlyCN.setSelected(false);
        fcbIDcompSolutionOverview.setSelected(false);
        fcbIDcompShowDetails.setSelected(false);
        progressBar.setProgress(0);

    }

    @FXML
    //Action for button "Reset" related to the Excel to Shacl
    private void actionBtnResetExcelShape() {
        fPathRdffileForExcel.clear();
        fPathXLSfileForShape.clear();
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();
        fbaseURIShapeExcel.clear();
        fPrefixExcelShape.clear();
        fNSexcelShape.clear();
        progressBar.setProgress(0);

    }

    @FXML
    //action button Browse for RDFS comparison - file 1
    private void actionBrowseRDFfile1() {
        progressBar.setProgress(0);
        List<File> file = null;
        if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen with CIMTool") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen")) {
            //select file 1
            file = util.ModelFactory.filechoosercustom(true, "RDF files", List.of("*.rdf", "*.legacy-rdfs-augmented"), "");
        } else if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("Universal method inlc. SHACL Shapes")) {
            file = util.ModelFactory.filechoosercustom(true, "Universal method inlc. SHACL Shapes", List.of("*.rdf", "*.ttl"), "");
        }

        assert file != null;
        if (file.getFirst() != null) {// the file is selected

            fPathRdffile1.setText(file.getFirst().toString());
            MainController.rdfModel1 = file.getFirst();
            if (!fPathRdffile2.getText().isEmpty()) {
                btnRunRDFcompare.setDisable(false);
            }
        } else {
            fPathRdffile1.clear();
            btnRunRDFcompare.setDisable(true);
        }
    }

    @FXML
    //action button Browse for RDFS comparison - file 2
    private void actionBrowseRDFfile2() {
        progressBar.setProgress(0);
        List<File> file = null;
        if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen with CIMTool") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen")) {
            //select file 2
            file = util.ModelFactory.filechoosercustom(true, "RDF files", List.of("*.rdf", "*.legacy-rdfs-augmented"), "");
        } else if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("Universal method inlc. SHACL Shapes")) {
            file = util.ModelFactory.filechoosercustom(true, "Universal method inlc. SHACL Shapes", List.of("*.rdf", "*.ttl"), "");
        }

        assert file != null;
        if (file.getFirst() != null) {// the file is selected
            fPathRdffile2.setText(file.getFirst().toString());
            MainController.rdfModel2 = file.getFirst();
            if (!fPathRdffile1.getText().isEmpty()) {
                btnRunRDFcompare.setDisable(false);
            }

        } else {
            fPathRdffile2.clear();
            btnRunRDFcompare.setDisable(true);
        }
    }

    @FXML
    //action button Browse for Instance data comparison - file 1
    private void actionBrowseIDfile1() {
        progressBar.setProgress(0);

        //select file 1
        List<File> fileL = util.ModelFactory.filechoosercustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDfile1.setText(fileL.toString());
            MainController.IDModel1 = fileL;
        } else {
            fPathIDfile1.clear();
        }
    }

    @FXML
    //action button Browse for Instance data comparison - file 2
    private void actionBrowseIDfile2() {
        progressBar.setProgress(0);
        //select file 1
        List<File> fileL = util.ModelFactory.filechoosercustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDfile2.setText(fileL.toString());
            MainController.IDModel2 = fileL;
        } else {
            fPathIDfile2.clear();
        }
    }

    @FXML
    //action button Browse for Instance data comparison - mapping
    private void actionBrowseIDmap() {
        progressBar.setProgress(0);
        MainController.IDmapSelect = 0;
        List<File> fileL = null;
        //FileChooser filechooser = new FileChooser();

        if (fcbIDmap.getSelectionModel().getSelectedItem().equals("Generate from RDFS")) {
            MainController.IDmapSelect = 1;
            //select file
            fileL = util.ModelFactory.filechoosercustom(false, "RDF files", List.of("*.rdf"), "");
        } else if (fcbIDmap.getSelectionModel().getSelectedItem().equals("Use saved map")) {
            MainController.IDmapSelect = 2;
            //select file
            fileL = util.ModelFactory.filechoosercustom(false, "Map file", List.of("*.properties"), "");
        }

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDmap.setText(fileL.toString());
            MainController.IDmapList = fileL;

            if (!fPathIDfile1.getText().isEmpty() && !fPathIDfile2.getText().isEmpty()) {
                btnRunIDcompare.setDisable(false);
            }

        } else {
            fPathIDmap.clear();
            btnRunIDcompare.setDisable(true);
        }
    }

    @FXML
    //action button RDF file Browse for Excel to SHACL
    private void actionBrowseRDFfileForExcel() {
        progressBar.setProgress(0);
        //select file
        List<File> file = util.ModelFactory.filechoosercustom(true, "RDF files", List.of("*.rdf"), "");
        if (file.getFirst() != null) {// the file is selected
            fPathRdffileForExcel.setText(file.getFirst().toString());
            MainController.rdfModelExcelShacl = file.getFirst();

        } else {
            fPathRdffileForExcel.clear();
        }
    }

    @FXML
    //action button XLS file Browse for Excel to SHACL
    private void actionBrowseExcelfileForShape() {
        progressBar.setProgress(0);
        //select file
        List<File> file = util.ModelFactory.filechoosercustom(true, "Excel files", List.of("*.xlsx"), "");

        if (file.getFirst() != null) {// the file is selected
            fPathXLSfileForShape.setText(file.getFirst().toString());
            MainController.xlsFileExcelShacl = file.getFirst();

        } else {
            fPathXLSfileForShape.clear();
        }
    }

    @FXML
    //action button Run in instance data comparison
    private void actionBtnRunIDcompare() throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        String xmlBase = fPathIDmapXmlBase.getText();
        Map<String, RDFDatatype> dataTypeMap = new HashMap<>();
        compareResults = new ArrayList<>();

        //if the datatypes map is from RDFS - make union of RDFS and generate map
        if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("Generate from RDFS") && MainController.IDmapSelect == 1) {


        } else if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("Use saved map") && MainController.IDmapSelect == 2) {
            //if the datatypes map is previously saved - load it
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
        }
        // if model 1 is more than 1 zip or xml - merge

        Model model1single = null;
        Model model2single = null;
        Model model1 = ModelFactory.createDefaultModel();
        Map prefixMap = model1.getNsPrefixMap();

        for (File item : MainController.IDModel1) {
            if (item.getName().toLowerCase().endsWith(".zip")) {
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model1single = util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 3);
                } else {
                    model1single = util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
                }
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model1single = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model1single, inputStream, xmlBase, Lang.RDFXML);
                } else {
                    model1single = util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                }
            }
            prefixMap.putAll(model1single.getNsPrefixMap());
            model1.add(model1single);
        }
        model1.setNsPrefixes(prefixMap);


        // if model 2 is more than 1 zip or xml - merge
        Model model2 = ModelFactory.createDefaultModel();
        prefixMap = model2.getNsPrefixMap();

        for (File item : MainController.IDModel2) {
            if (item.getName().toLowerCase().endsWith(".zip")) {
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model2single = util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 3);
                } else {
                    model2single = util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
                }
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model2single = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model2single, inputStream, xmlBase, Lang.RDFXML);
                } else {
                    model2single = util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                }

            }
            prefixMap.putAll(model2single.getNsPrefixMap());
            model2.add(model2single);
        }
        model2.setNsPrefixes(prefixMap);
        ComparisonSHACLshapes.modelsABPrefMap = prefixMap;

        //proceed with the comparison

        rdfsCompareFiles = new LinkedList<>();
        if (MainController.IDModel1.size() == 1) {
            rdfsCompareFiles.add(MainController.IDModel1.getFirst().getName());
        } else {
            rdfsCompareFiles.add("Model 1");
        }
        if (MainController.IDModel2.size() == 1) {
            rdfsCompareFiles.add(MainController.IDModel2.getFirst().getName());
        } else {
            rdfsCompareFiles.add("Model 2");
        }

        LinkedList<Integer> options = new LinkedList<>();
        options.add(0); //1 is ignore sv classes
        options.add(0);
        options.add(0);
        options.add(0);
        options.add(0);
        options.add(0);
        if (fcbIDcompIgnoreSV.isSelected()) {
            options.set(0, 1);
        }
        if (fcbIDcompIgnoreDL.isSelected()) {
            options.set(1, 1);
        }
        if (fcbIDcompCount.isSelected()) {
            options.set(3, 1);
            compareResults = CompareFactory.compareCountClasses(compareResults, model1, model2);
        }
        if (fcbIDcompIgnoreTP.isSelected()) {
            options.set(4, 1);
        }
        if (fcbIDcompSVonlyCN.isSelected()) {
            options.set(5, 1);
        }
        if (fcbIDcompSVonly.isSelected()) {
            options.set(2, 1);
            compareResults = ComparisonInstanceData.compareSolution(compareResults, model1, model2, xmlBase, options);
        }
        if (!fcbIDcompCount.isSelected() && !fcbIDcompSVonly.isSelected()) {
            compareResults = ComparisonInstanceData.compareInstanceData(compareResults, model1, model2, options);
        }

        if (fcbIDcompSolutionOverview.isSelected()) {
            compareResults = ComparisonInstanceData.compareSolution(compareResults, model1, model2, xmlBase, options);
            List<String> solutionOverviewResult = ComparisonInstanceData.solutionOverview();
        }


        if (!compareResults.isEmpty()) {

            if (fcbIDcompShowDetails.isSelected()) {
                try {
                    Stage guiRdfDiffResultsStage = new Stage();
                    //Scene for the menu RDF differences
                    //FXMLLoader fxmlLoader = new FXMLLoader();
                    Parent rootRDFdiff = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/fxml/rdfDiffResult.fxml")));
                    Scene rdfDiffscene = new Scene(rootRDFdiff);
                    guiRdfDiffResultsStage.setScene(rdfDiffscene);
                    guiRdfDiffResultsStage.setTitle("Comparison Instance data");
                    guiRdfDiffResultsStage.initModality(Modality.APPLICATION_MODAL);
                    rdfDiffResultController.initData(guiRdfDiffResultsStage);
                    guiRdfDiffResultsStage.showAndWait();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setContentText("The two models are identical.");
            alert.setHeaderText(null);
            alert.setTitle("Information");
            alert.showAndWait();
        }

        progressBar.setProgress(1);

    }

    @FXML
    //action button Run in RDF comparison
    private void actionBtnRunRDFcompare() throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        Lang rdfSourceFormat1 = Lang.RDFXML;
        Lang rdfSourceFormat2 = Lang.RDFXML;
        switch (fcbRDFSformat.getSelectionModel().getSelectedItem().toString()) {
            case "RDFS (augmented) by CimSyntaxGen":
            case "RDFS (augmented) by CimSyntaxGen with CIMTool":
            case "RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen":
                rdfSourceFormat1 = Lang.RDFXML;
                rdfSourceFormat2 = Lang.RDFXML;
                break;

            case "Universal method inlc. SHACL Shapes":
                if (MainController.rdfModel1.getName().endsWith(".ttl")) {
                    rdfSourceFormat1 = Lang.TURTLE;
                } else if (MainController.rdfModel1.getName().endsWith(".rdf")) {
                    rdfSourceFormat1 = Lang.RDFXML;
                }
                if (MainController.rdfModel2.getName().endsWith(".ttl")) {
                    rdfSourceFormat2 = Lang.TURTLE;
                } else if (MainController.rdfModel2.getName().endsWith(".rdf")) {
                    rdfSourceFormat2 = Lang.RDFXML;
                }
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + fcbRDFSformat.getSelectionModel().getSelectedItem().toString());
        }

        List<File> modelFiles1 = new LinkedList<>();
        modelFiles1.add(MainController.rdfModel1);
        List<File> modelFiles2 = new LinkedList<>();
        modelFiles2.add(MainController.rdfModel2);

        Model model1 = util.ModelFactory.modelLoad(modelFiles1, null, rdfSourceFormat1, false);
        Model model2Temp = util.ModelFactory.modelLoad(modelFiles2, null, rdfSourceFormat2, false);

        Model model2 = null;
        boolean error = false;
        if (fcbRDFcompareCimVersion.isSelected() && !fcbRDFcompareProfileNS.isSelected()) {//rename cim namespace in the second model
            model2 = CompareFactory.renameNamespaceURIresources(model2Temp, "cim", model1.getNsPrefixURI("cim"));
        } else if (!fcbRDFcompareCimVersion.isSelected() && fcbRDFcompareProfileNS.isSelected()) {//rename only profile namespace in the second model
            String prefixProfile = fPrefixRDFCompare.getText();
            String profileURI = model1.getNsPrefixURI(prefixProfile);
            if (profileURI != null) {
                model2 = CompareFactory.renameNamespaceURIresources(model2Temp, prefixProfile, profileURI);
            } else {
                String profilePrefixURI = CompareFactory.getProfileURI(model2Temp);
                if (profilePrefixURI != null) {
                    model2 = CompareFactory.renameNamespaceURIresources(model2Temp, profilePrefixURI, model1.getNsPrefixURI(profilePrefixURI));
                } else {
                    error = true;
                }
            }

        } else if (fcbRDFcompareCimVersion.isSelected() && fcbRDFcompareProfileNS.isSelected()) {//rename both cim namespace and profile namespace in the second model
            model2 = CompareFactory.renameNamespaceURIresources(model2Temp, "cim", model1.getNsPrefixURI("cim"));
            String prefixProfile = fPrefixRDFCompare.getText();
            String profileURI = model1.getNsPrefixURI(prefixProfile);
            if (profileURI != null) {
                model2 = CompareFactory.renameNamespaceURIresources(model2, prefixProfile, profileURI);
            } else {
                String profilePrefixURI = CompareFactory.getProfileURI(model2);
                if (profilePrefixURI != null) {
                    model2 = CompareFactory.renameNamespaceURIresources(model2, profilePrefixURI, model1.getNsPrefixURI(profilePrefixURI));
                } else {
                    error = true;
                }
            }

        } else {// no rename
            model2 = model2Temp;
        }


        if (error) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("No header in the file and the profile namespace prefix is not declared.");
            alert.setHeaderText(null);
            alert.setTitle("Error");
            alert.showAndWait();
            return;
        }

        rdfsCompareFiles = new LinkedList<>();
        rdfsCompareFiles.add(MainController.rdfModel1.getName());
        rdfsCompareFiles.add(MainController.rdfModel2.getName());

        switch (fcbRDFSformat.getSelectionModel().getSelectedItem().toString()) {
            case "RDFS (augmented) by CimSyntaxGen":
                compareResults = ComparisonRDFSprofile.compareRDFSprofile(model1, model2);
                break;
            case "RDFS (augmented) by CimSyntaxGen with CIMTool":
                compareResults = ComparisonRDFSprofileCIMTool.compareRDFSprofileCIMTool(model1, model2);
                break;
            case "RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen":
                compareResults = ComparissonRDFS501Ed2.compareRDFS501Ed2(model1, model2);
                break;
            case "Universal method inlc. SHACL Shapes":
                compareResults = ComparisonSHACLshapes.compareSHACLshapes(model1, model2);
                break;
        }


        if (!compareResults.isEmpty()) {

            try {
                Stage guiRdfDiffResultsStage = new Stage();
                //Scene for the menu RDF differences
                Parent rootRDFdiff = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/fxml/rdfDiffResult.fxml")));
                Scene rdfDiffscene = new Scene(rootRDFdiff);
                guiRdfDiffResultsStage.setScene(rdfDiffscene);
                guiRdfDiffResultsStage.setTitle("Comparison RDFS profiles");
                guiRdfDiffResultsStage.initModality(Modality.APPLICATION_MODAL);
                rdfDiffResultController.initData(guiRdfDiffResultsStage);
                progressBar.setProgress(1);
                guiRdfDiffResultsStage.showAndWait();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setContentText("The two models are identical if the CIM namespace would be the same.");
            alert.setHeaderText(null);
            alert.setTitle("Information");
            progressBar.setProgress(1);
            alert.showAndWait();
        }
    }


    @FXML
    // action on menu Convert Reference data to Common data
    private void actionMenuConvertRefToComData() throws IOException {

        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        List<File> file = util.ModelFactory.filechoosercustom(false, "Reference data RDF files", List.of("*.rdf"), "");
        if (file != null) {// the file is selected
            shaclNodataMap = 1; // as no mapping is to be used for this task
            ModelManipulationFactory.generateCommonData(file);
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
        List<File> file = util.ModelFactory.filechoosercustom(false, "Input template instance data XLS", List.of("*.xlsx"), "");

        if (file != null) {// the file is selected
            MainController.inputXLS = file;
            //select file
            file = util.ModelFactory.filechoosercustom(false, "RDF profile file", List.of("*.rdf", "*.ttl"), "");

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

                ModelManipulationFactory.generateDataFromXls(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag, eqbdID, tpbdID, saveProperties, persistentEQflag);

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
            File saveFile = util.ModelFactory.filesavecustom("Datatypes mapping files", List.of("*.properties"), "", "");
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    @FXML
    // action on menu Convert Reference Data
    private void actionMenuConvertRefData() throws IOException {
        //select file 1
        List<File> fileL = util.ModelFactory.filechoosercustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected
            fPathIDfile1.setText(fileL.toString());
            MainController.IDModel1 = fileL;
        } else {
            fPathIDfile1.clear();
        }

        List<File> fileL1 = util.ModelFactory.filechoosercustom(false, "ap file", List.of("*.properties"), "");

        if (fileL1 != null) {// the file is selected
            fPathIDmap.setText(fileL1.toString());
            MainController.IDmapList = fileL1;

        }

        RdfConvert.refDataConvert();
        progressBar.setProgress(1);

    }

    //the action for button Open RDFS for profile
    //It opens RDF files for the profiles saved locally
    @FXML
    private void actionOpenRDFS() {
        //FileChooser filechooser = new FileChooser();
        List<File> file = null;
        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("RDFS (augmented, v2019) by CimSyntaxGen")) {
            rdfFormatInput = "CimSyntaxGen-RDFS-Augmented-2019";
            file = util.ModelFactory.filechoosercustom(false, "RDFS (augmented, v2019) by CimSyntaxGen files", List.of("*.rdf"), "");
        } else if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("RDFS (augmented, v2020) by CimSyntaxGen")) {
            rdfFormatInput = "CimSyntaxGen-RDFS-Augmented-2020";
            file = util.ModelFactory.filechoosercustom(false, "RDFS (augmented, v2020) by CimSyntaxGen files", List.of("*.rdf"), "");
        } else if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("Merged OWL CIMTool")) {
            rdfFormatInput = "CIMTool-merged-owl";
            file = util.ModelFactory.filechoosercustom(false, "Merged OWL CIMTool files", List.of("*.owl"), "");
        }
        this.selectedFile = file;


        if (file != null) {// the file is selected
            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            int iterSize = file.size();

            for (int m = 0; m < iterSize; m++) {
                modelLoad(m);
                setPackageStructure(m);
            }

            guiTreeProfileConstraintsInit();

            treeViewProfileConstraints.setDisable(false);
            fselectDatatypeMapDefineConstraints.setDisable(false);
            cbProfilesVersionCreateCompleteSMTab.setDisable(false);
            cbApplyDefNsDesignTab.setDisable(false);
            cbApplyDefBaseURIDesignTab.setDisable(false);
            btnApply.setDisable(false);
            cbRDFSSHACLoption1.setDisable(false);
            cbRDFSSHACLoptionDescr.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles2nd.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles3rd.setDisable(false);
            cbRDFSSHACLoptionBaseprofilesIgnoreNS.setDisable(false);
            cbRDFSSHACLoptionInverse.setDisable(false);

            progressBar.setProgress(1);
        }
    }


    @FXML
    //action button Browse for RDFS convert
    private void actionBrowseRDFConvert() {
        progressBar.setProgress(0);
        //select file
        List<File> file;
        List<File> fileL;
        if (fcbRDFconvertModelUnion.isSelected()) {
            fileL = util.ModelFactory.filechoosercustom(false, "RDF file to convert", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

            if (fileL != null) {// the file is selected
                fsourcePathTextField.setText(fileL.toString());
                MainController.rdfConvertFileList = fileL;
            } else {
                fsourcePathTextField.clear();
            }
        } else {
            file = util.ModelFactory.filechoosercustom(true, "RDF file to convert", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

            if (file.getFirst() != null) {// the file is selected
                //MainController.prefs.put("LastWorkingFolder", file.getParent());
                fsourcePathTextField.setText(file.getFirst().toString());
                MainController.rdfConvertFile = file.getFirst();
            } else {
                fsourcePathTextField.clear();
            }
        }
    }

    @FXML
    //Action for button "Convert" related to the RDF Convert
    private void actionBtnRunRDFConvert() throws IOException {

        progressBar.setProgress(0);
        //format from
        String sourceFormat = fsourceFormatChoiceBox.getSelectionModel().getSelectedItem().toString();
        //format to
        String targetFormat = ftargetFormatChoiceBox.getSelectionModel().getSelectedItem().toString();
        //xmlBase
        String xmlBase = null;

        if (!frdfConvertXmlBase.getText().isBlank()) {
            xmlBase = frdfConvertXmlBase.getText();
        }

        RDFFormat rdfFormat = RDFFormat.RDFXML_PLAIN;
        if (!fcbRDFformat.getSelectionModel().isSelected(-1)) {
            rdfFormat = switch (fcbRDFformat.getSelectionModel().getSelectedItem().toString()) {
                case "RDFXML" -> RDFFormat.RDFXML;
                case "RDFXML_ABBREV" -> RDFFormat.RDFXML_ABBREV;
                case "RDFXML_PLAIN" -> RDFFormat.RDFXML_PLAIN;
                case "RDFXML_PRETTY" -> RDFFormat.RDFXML_PRETTY;
                case "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
                case "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN;
                default ->
                        throw new IllegalStateException("Unexpected value: " + fcbRDFformat.getSelectionModel().getSelectedItem().toString());
            };
        }

        //sort options
        String sortOptions="";
        if (targetFormat.equals("RDF XML (.rdf or .xml)")) {
            sortOptions = fcbRDFsortOptions.getSelectionModel().getSelectedItem().toString();
        }

        String showXmlDeclaration = "false";
        if (targetFormat.equals("RDF XML (.rdf or .xml)") && fcbShowXMLDeclaration.isSelected()) {
            showXmlDeclaration = "true";
        }

        String showDoctypeDeclaration = "false";
        if (targetFormat.equals("RDF XML (.rdf or .xml)") && fcbShowDoctypeDeclaration.isSelected()) {
            showDoctypeDeclaration = "true";
        }
        String tab = "";
        if (targetFormat.equals("RDF XML (.rdf or .xml)")) {
            tab = fRDFconvertTab.getText();
        }
        String relativeURIs = "";
        if (targetFormat.equals("RDF XML (.rdf or .xml)")) {
            relativeURIs = fcbRelativeURIs.getSelectionModel().getSelectedItem().toString();
        }
        String sortRDF = "false";
        if (fcbSortRDF.isSelected()) {
            sortRDF = "true";
        }

        String rdfSortOptions = "false";
        if (sortOptions.equals("Sorting by prefix")) {
            rdfSortOptions = "true";
        }

        boolean stripPrefixes = false;
        if (fcbStripPrefixes.isSelected()) {
            stripPrefixes = true;
        }

        boolean modelUnionFlag = fcbRDFconvertModelUnion.isSelected();

        boolean modelUnionFlagDetailed = fcbRDFconvertModelUnionDetailed.isSelected();

        String convertInstanceData = "false";
        if (fcbRDFconvertInstanceData.isSelected())
            convertInstanceData = "true";

        boolean inheritanceOnly = fcbRDFConvertInheritanceOnly.isSelected();
        boolean inheritanceList = fcbRDFConverInheritanceList.isSelected();

        boolean inheritanceListConcrete = fcbRDFConverInheritanceListConcrete.isSelected();

        boolean addowl = fcbRDFConveraddowl.isSelected();
        boolean modelUnionFixPackage = fcbRDFconvertFixPackage.isSelected();

        RdfConvert.rdfConversion(MainController.rdfConvertFile, MainController.rdfConvertFileList, sourceFormat,
                targetFormat, xmlBase, rdfFormat, showXmlDeclaration, showDoctypeDeclaration, tab,
                relativeURIs, modelUnionFlag, inheritanceOnly, inheritanceList, inheritanceListConcrete, addowl, modelUnionFlagDetailed, sortRDF, rdfSortOptions, stripPrefixes, convertInstanceData,modelUnionFixPackage);

        progressBar.setProgress(1);
    }

    @FXML
    //Action for button "Reset" related to RDF Convert
    private void actionBrtResetRDFConvert() {

        progressBar.setProgress(0);
        fsourcePathTextField.clear();
        fsourceFormatChoiceBox.getSelectionModel().clearSelection();
        ftargetFormatChoiceBox.getSelectionModel().clearSelection();
        frdfConvertXmlBase.clear();
        fcbRDFconvertModelUnion.setSelected(false);
        fcbRDFConvertInheritanceOnly.setSelected(false);
        fcbRDFConverInheritanceList.setSelected(false);
        fcbRDFConverInheritanceList.setDisable(true);
        fcbRDFConverInheritanceListConcrete.setSelected(false);
        fcbRDFConverInheritanceListConcrete.setDisable(true);

    }

    @FXML
    //Action for check box "Process only inheritance related properties" related to RDF Convert
    private void actionRDFConvertInheritanceOnly() {
        if (fcbRDFConvertInheritanceOnly.isSelected()) {
            fcbRDFConverInheritanceList.setDisable(false);
        } else {
            fcbRDFConverInheritanceList.setDisable(true);
            fcbRDFConverInheritanceList.setSelected(false);
            fcbRDFConverInheritanceListConcrete.setSelected(false);
            fcbRDFConverInheritanceListConcrete.setDisable(true);
        }
    }

    @FXML
    //Action for check box "Generate inheritance list in Turtle" related to RDF Convert
    private void actionRDFConvertInheritanceList() {
        if (fcbRDFConverInheritanceList.isSelected()) {
            fcbRDFConverInheritanceListConcrete.setDisable(false);
        } else {
            fcbRDFConverInheritanceListConcrete.setDisable(true);
            fcbRDFConverInheritanceListConcrete.setSelected(false);
        }
    }

    //Action for choice box "Target format" related to RDF Convert
    private void actionCBRDFconvertTarget() {

        progressBar.setProgress(0);
        if (!ftargetFormatChoiceBox.getSelectionModel().isSelected(-1)) {
            if (ftargetFormatChoiceBox.getSelectionModel().getSelectedItem().toString().equals("RDF XML (.rdf or .xml)")) {
                fcbShowXMLDeclaration.setDisable(false);
                fcbShowDoctypeDeclaration.setDisable(false);
                fRDFconvertTab.setDisable(false);
                fcbRelativeURIs.setDisable(false);
                fcbRelativeURIs.getSelectionModel().selectFirst();
                fcbRDFformat.setDisable(false);
                fcbRDFformat.getSelectionModel().selectFirst();
                fcbSortRDF.setDisable(false);
                fcbRDFsortOptions.setDisable(false);
                fcbRDFsortOptions.getSelectionModel().selectFirst();
                fcbStripPrefixes.setDisable(false);
            } else {
                fcbShowXMLDeclaration.setDisable(true);
                fcbShowXMLDeclaration.setSelected(true);
                fcbShowDoctypeDeclaration.setDisable(true);
                fcbShowDoctypeDeclaration.setSelected(false);
                fRDFconvertTab.setDisable(true);
                fRDFconvertTab.setText("2");
                fcbRelativeURIs.setDisable(true);
                fcbRelativeURIs.getSelectionModel().clearSelection();
                fcbRDFformat.setDisable(true);
                fcbRDFformat.getSelectionModel().clearSelection();
                fcbSortRDF.setDisable(true);
                fcbRDFsortOptions.setDisable(true);
                fcbRDFsortOptions.getSelectionModel().clearSelection();
                fcbStripPrefixes.setDisable(true);
            }
        } else {
            fcbShowXMLDeclaration.setDisable(true);
            fcbShowXMLDeclaration.setSelected(true);
            fcbShowDoctypeDeclaration.setDisable(true);
            fcbShowDoctypeDeclaration.setSelected(false);
            fRDFconvertTab.setDisable(true);
            fRDFconvertTab.setText("2");
            fcbRelativeURIs.setDisable(true);
            fcbRelativeURIs.getSelectionModel().clearSelection();
            fcbRDFformat.setDisable(true);
            fcbRDFformat.getSelectionModel().clearSelection();
            fcbSortRDF.setDisable(true);
            fcbRDFsortOptions.setDisable(true);
            fcbRDFsortOptions.getSelectionModel().clearSelection();
            fcbStripPrefixes.setDisable(true);
        }


    }

    //Action for choice box "Profile version" related to Instance data comparison
    private void actionCBIDformat() {

        progressBar.setProgress(0);
        if (!fcbIDformat.getSelectionModel().isSelected(-1)) {
            if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("Other CIM version")) {
                fPathIDmapXmlBase.setEditable(true);
                fPathIDmapXmlBase.clear();
            } else if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-600-1&2 (CGMES 3.0.0)") ||
                    fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-45x (CIM17)")) {
                fPathIDmapXmlBase.setEditable(false);
                fPathIDmapXmlBase.setText("http://iec.ch/TC57/CIM100");
            } else if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC TS 61970-600-1&2 (CGMES 2.4.15)") ||
                    fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-45x (CIM16)")) {
                fPathIDmapXmlBase.setEditable(false);
                fPathIDmapXmlBase.setText("http://iec.ch/TC57/2013/CIM-schema-cim16");
            }
        } else {
            fPathIDmapXmlBase.setEditable(false);
        }

    }

    //Action for choice box "Datatypes map" related to Instance data comparison
    private void actionCBIDmap() {

        progressBar.setProgress(0);
        if (!fcbIDmap.getSelectionModel().isSelected(-1)) {
            fPathIDmap.clear();
            fBTbrowseIDmap.setDisable(fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping"));
        } else {
            fBTbrowseIDmap.setDisable(false);
            fPathIDmap.clear();
        }

    }

    //Action for choice box "RDF scope and format" related to RDF comparison
    private void actionCBfcbRDFSformat() {

        progressBar.setProgress(0);
        if (!fcbRDFSformat.getSelectionModel().isSelected(-1)) {
            if (fcbRDFSformat.getSelectionModel().getSelectedItem().toString().equals("RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen") ||
                    fcbRDFSformat.getSelectionModel().getSelectedItem().toString().equals("Universal method inlc. SHACL Shapes")) {
                fcbRDFcompareCimVersion.setDisable(false);
                fcbRDFcompareProfileNS.setDisable(false);
                fPrefixRDFCompare.setDisable(false);
            } else {
                fcbRDFcompareCimVersion.setDisable(true);
                fcbRDFcompareCimVersion.setSelected(false);
                fcbRDFcompareProfileNS.setDisable(true);
                fcbRDFcompareProfileNS.setSelected(false);
                fPrefixRDFCompare.setDisable(true);
                fPrefixRDFCompare.clear();
            }
        } else {
            fcbRDFcompareCimVersion.setDisable(true);
            fcbRDFcompareCimVersion.setSelected(false);
            fcbRDFcompareProfileNS.setDisable(true);
            fcbRDFcompareProfileNS.setSelected(false);
            fPrefixRDFCompare.setDisable(true);
            fPrefixRDFCompare.clear();
        }

    }

    //Loads model data
    private void modelLoad(int m) {

        if (m == 0) {
            this.models = new ArrayList<>(); // this is a collection of models (rdf profiles) that are imported
            this.modelsNames = new ArrayList<>(); // this is a collection of the name of the profile packages
            //modelsOnt =new ArrayList<>();
        }
        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        //for the text of ontology model
        try {
            RDFDataMgr.read(model, new FileInputStream(this.selectedFile.get(m).toString()), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        this.models.add(model);
    }

    //initializes the TreeView in the tab Generate Shapes - profile tree vew
    private void guiTreeProfileConstraintsInit() {
        TreeItem<String> rootMain;
        //define the Treeview
        rootMain = new TreeItem<>("Main root");
        rootMain.setExpanded(true);
        treeViewProfileConstraints.setRoot(rootMain); // sets the root to the gui object
        treeViewProfileConstraints.setShowRoot(false);
        for (Object modelsName : this.modelsNames) {
            TreeItem<String> profileItem = new TreeItem<>(((ArrayList<?>) modelsName).getFirst().toString()); // level for the Classes
            rootMain.getChildren().add(profileItem);
        }

        treeViewProfileConstraints.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }


    //sets package structure for the tree view in the tab Browse RDF
    private void setPackageStructure(int m) {

        //set package structure
        Model model = (Model) this.models.get(m);
        this.packages = new ArrayList<>();

        if (rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2019") || rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2020")) {
            for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")); i.hasNext(); ) {
                Statement resItem = i.next();
                String label = model.getRequiredProperty(resItem.getSubject(), RDFS.label).getObject().asLiteral().getString();
                if (label.endsWith("Profile") && (!label.startsWith("Doc") || label.startsWith("Document"))) {
                    this.packages.add(label);
                }
            }

        } else if (rdfFormatInput.equals("CIMTool-merged-owl")) {
            this.packages.add(this.selectedFile.get(m).getName());
        }

        ArrayList<String> mpak1 = new ArrayList<>();
        mpak1.add(this.packages.getFirst());
        mpak1.add(""); //reserved for the prefix of the profile
        mpak1.add(""); // reserved for the URI of the profile
        mpak1.add(""); // reserved for the baseURI of the profile
        mpak1.add(""); // reserved for owl:imports
        this.modelsNames.add(mpak1);
    }

    @FXML
    //action on check box in constraints Create complete shapes model Tab
    private void cbApplyDefBaseURI() {
        if (cbApplyDefBaseURIDesignTab.isSelected()) {
            fshapesBaseURICreateCompleteSMTab.setText("");
            fshapesBaseURICreateCompleteSMTab.setDisable(true);
        } else {
            fshapesBaseURICreateCompleteSMTab.setDisable(false);
        }
    }

    @FXML
    //action on check box in constraints Create complete shapes model Tab
    private void cbApplyDefNamespace() {
        if (cbApplyDefNsDesignTab.isSelected()) {
            fPrefixCreateCompleteSMTab.setText("");
            fPrefixCreateCompleteSMTab.setDisable(true);
            fURICreateCompleteSMTab.setText("");
            fURICreateCompleteSMTab.setDisable(true);
        } else {
            fPrefixCreateCompleteSMTab.setDisable(false);
            fURICreateCompleteSMTab.setDisable(false);
        }
    }


    @FXML
    //action for button Apply in tab Generate Shapes
    private void actionBtnApply() {

        if (tabCreateCompleteSM.isSelected()) {
            if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size() == 1 &&
                    (!cbApplyDefNsDesignTab.isSelected() || !cbApplyDefBaseURIDesignTab.isSelected())) {

                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().getFirst().getValue();
                for (Object modelsNames : this.modelsNames) {
                    if (selectedProfile.equals(((ArrayList<?>) modelsNames).getFirst())) {
                        int issueFound = 0;
                        if (!fPrefixCreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                            ((ArrayList<String>) modelsNames).set(1, fPrefixCreateCompleteSMTab.getText());
                        } else if (fPrefixCreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                            Alert alert = new Alert(Alert.AlertType.WARNING);
                            alert.setContentText("Please confirm that you would like to use a namespace with empty prefix.");
                            alert.setHeaderText(null);
                            alert.setTitle("Warning - the prefix is empty");
                            ButtonType btnYes = new ButtonType("Yes");
                            ButtonType btnNo = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
                            alert.getButtonTypes().setAll(btnYes, btnNo);
                            Optional<ButtonType> result = alert.showAndWait();
                            if (result.get() == btnNo) {
                                return;
                            }
                        }
                        if (!fURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {//TODO: check if it is resource
                            ((ArrayList<String>) modelsNames).set(2, fURICreateCompleteSMTab.getText());
                        } else if (fURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Please add URI of the namespace.");
                            alert.setHeaderText(null);
                            alert.setTitle("Error - the value is empty string");
                            alert.showAndWait();
                            issueFound = 1;
                        }
                        if (!fshapesBaseURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefBaseURIDesignTab.isSelected()) {//TODO: check if it is resource
                            ((ArrayList<String>) modelsNames).set(3, fshapesBaseURICreateCompleteSMTab.getText());
                        } else if (fshapesBaseURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefBaseURIDesignTab.isSelected()) {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Please add the base URI of the shapes model.");
                            alert.setHeaderText(null);
                            alert.setTitle("Error - the value is empty string");
                            alert.showAndWait();
                            issueFound = 1;
                        }
                        if (issueFound == 1) {
                            return;
                        }
                    }
                }
            } else if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size() != 1 &&
                    (!cbApplyDefNsDesignTab.isSelected() || !cbApplyDefBaseURIDesignTab.isSelected())) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Please select only one profile to apply the namespace or base URI information.");
                alert.setHeaderText(null);
                alert.setTitle("Error - no profile or multiple profiles selected");
                alert.showAndWait();
                return;
            }

            if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size() == 1) {
                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().getFirst().getValue();
                for (Object modelsName : this.modelsNames) {
                    if (selectedProfile.equals(((ArrayList<?>) modelsName).getFirst().toString())) {
                        if (fowlImportsCreateCompleteSMTab.getText().isEmpty()) {
                            ((ArrayList) modelsName).set(4, "");
                            break;
                        } else {
                            ((ArrayList) modelsName).set(4, fowlImportsCreateCompleteSMTab.getText());
                            break;
                        }
                    }
                }
            }

            //TODO: temporary option until a way to automate is implemented
            int profileVersion = 0;
            if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-600-1&2 (CGMES 3.0.0)")) {
                profileVersion = 1; // CGMESv3
            } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("IEC TS 61970-600-1&2 (CGMES 2.4.15)")) {
                profileVersion = 2; // CGMESv2.4
            } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-452:Ed4")) {
                profileVersion = 3; // IEC 61970-452:Ed4
            } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-457:Ed2")) {
                profileVersion = 4; // IEC 61970-457:Ed2
            } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("Network Codes profiles,releases before 2.3")) {
                profileVersion = 5; // Network Codes profiles, releases before 2.3
            } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("Network Codes profiles, release 2.3")) {
                profileVersion = 6; // Network Codes profiles, release 2.3
            }else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("LTDS profiles, release 1.0")) {
                profileVersion = 7; // LTDS profiles, release 1.0
            }
            if (cbApplyDefNsDesignTab.isSelected()) {
                ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
                for (Object modelsNames : this.modelsNames) {
                    for (TreeItem<String> treeitem : treeitems) {
                        if (treeitem.getValue().equals(((ArrayList<?>) modelsNames).get(0))) {
                            switch (treeitem.getValue()) {
                                case "CoreEquipmentProfile", "EquipmentProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "eq");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/EquipmentCore/3/1/Constraints#");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/CoreEquipment/Constraints#");
                                    }
                                }
                                case "OperationProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "op");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Operation-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/EquipmentOperation/3/1/Constraints#");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Operation/Constraints#");
                                    }
                                }
                                case "ShortCircuitProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sc");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/EquipmentShortCircuit/3/1/Constraints#");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/ShortCircuit/Constraints#");
                                    }
                                }
                                case "SteadyStateHypothesisProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ssh");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1/Constraints#");
                                    }
                                }
                                case "TopologyProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "tp");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Topology-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/Topology/4/1/Constraints#");
                                    }
                                }
                                case "StateVariablesProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sv");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/StateVariables-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/StateVariables/4/1/Constraints#");
                                    }
                                }
                                case "DiagramLayoutProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dl");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/DiagramLayout/3/1/Constraints#");
                                    }
                                }
                                case "GeographicalLocationProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "gl");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/GeographicalLocation/2/1/Constraints#");
                                    }
                                }
                                case "DynamicsProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dy");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Dynamics-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/Dynamics/3/1/Constraints#");
                                    } else if (profileVersion == 4) {
                                        ((ArrayList) modelsNames).set(2, "http://cim-profile.ucaiug.io/grid/Dynamics/Constraints/2.0#");
                                    }
                                }
                                case "EquipmentBoundaryProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "eqbd");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/EquipmentBoundary/3/1/Constraints#");
                                    }
                                }
                                case "TopologyBoundaryProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "tpbd");
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/Constraints#");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/CIM/TopologyBoundary/3/1/Constraints#");
                                    }
                                }
                                case "FileHeaderProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "fh");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/61970-552/ModelDescription/Constraints#");
                                }
                                case "PowerSystemProjectProfile", "DocPowerSystemProjectProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "psp");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/PowerSystemProject-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/PowerSystemProject-Simple/2.3#");
                                    }
                                }
                                case "RemedialActionScheduleProfile", "DocRemedialActionScheduleProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ras");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/RemedialActionSchedule-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/RemedialActionSchedule-Simple/2.3#");
                                    }
                                }
                                case "SecurityAnalysisResultProfile", "DocSecurityAnalysisResultProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sar");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/SecurityAnalysisResult-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/SecurityAnalysisResult-Simple/2.4#");
                                    }
                                }
                                case "SensitivityMatrixProfile", "DocSensitivityMatrixProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sm");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/SensitivityMatrix-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/SensitivityMatrix-Simple/2.3#");
                                    }
                                }
                                case "EquipmentReliabilityProfile", "DocEquipmentReliabilityProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "er");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/EquipmentReliability-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/EquipmentReliability-Simple/2.3#");
                                    }
                                }
                                case "RemedialActionProfile", "DocRemedialActionProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ra");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/RemedialAction-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/RemedialAction-Simple/2.3#");
                                    }
                                }
                                case "SteadyStateInstructionProfile", "DocSteadyStateInstructionProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ssi");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/SteadyStateInstruction-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/SteadyStateInstruction-Simple/2.3#");
                                    }
                                }
                                case "AvailabilityScheduleProfile", "DocAvailabilityScheduleProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "as");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/AvailabilitySchedule-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/AvailabilitySchedule-Simple/2.3#");
                                    }
                                }
                                case "AssessedElementProfile", "DocAssessedElementProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ae");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/AssessedElement-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/AssessedElement-Simple/2.3#");
                                    }
                                }
                                case "StateInstructionScheduleProfile", "DocStateInstructionScheduleProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sis");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/StateInstructionSchedule-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/StateInstructionSchedule-Simple/2.3#");
                                    }
                                }
                                case "ContingencyProfile", "DocContingencyProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "co");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/Contingency-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/Contingency-Simple/2.3#");
                                    }
                                }
                                case "DocumentHeaderProfile", "DocDocumentHeaderProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dh");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/DocumentHeader-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/DocumentHeader-Simple/2.3#");
                                    }
                                }
                                case "DatasetMetadataProfile", "DocDatasetMetadataProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dm");
                                    if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/DatasetMetadata-Simple/2.4#");
                                    }
                                }
                                case "GridDisturbanceProfile", "DocGridDisturbanceProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "gd");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/GridDisturbance-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/GridDisturbance-Simple/2.3#");
                                    }
                                }
                                case "ImpactAssessmentMatrixProfile", "DocImpactAssessmentMatrixProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "iam");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/ImpactAssessmentMatrix-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/ImpactAssessmentMatrix-Simple/2.3#");
                                    }
                                }
                                case "MonitoringAreaProfile", "DocMonitoringAreaProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ma");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/MonitoringArea-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/MonitoringArea-Simple/2.3#");
                                    }
                                }
                                case "ObjectRegistryProfile", "DocObjectRegistryProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "or");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/ObjectRegistry-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/ObjectRegistry-Simple/2.2#");
                                    }
                                }
                                case "PowerScheduleProfile", "DocPowerScheduleProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ps");
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(2, "http://entsoe.eu/ns/CIM/PowerSchedule-EU/Constraints#");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/PowerSchedule-Simple/2.3#");
                                    }
                                }
                                case "SteadyStateHypothesisScheduleProfile",
                                     "DocSteadyStateHypothesisScheduleProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "shs");
                                    if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(2, "https://ap-con.cim4.eu/SteadyStateHypothesisSchedule-Simple/1.0#");
                                    }
                                }
                                case "DetailedModelConfigurationProfile", "DocDetailedModelConfigurationProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dmc");
                                    ((ArrayList) modelsNames).set(2, "http://cim-profile.ucaiug.io/grid/DetailedModelConfiguration/Constraints/1.0#");
                                }
                                case "DetailedModelParameterisationProfile",
                                     "DocDetailedModelParameterisationProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dmp");
                                    ((ArrayList) modelsNames).set(2, "http://cim-profile.ucaiug.io/grid/DetailedModelParameterisation/Constraints/1.0#");
                                }
                                case "SimulationSettingsProfile", "DocSimulationSettingsProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "set");
                                    ((ArrayList) modelsNames).set(2, "http://cim-profile.ucaiug.io/grid/SimulationSettings/Constraints/1.0#");
                                }
                                case "SimulationResultsProfile", "DocSimulationResultsProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sr");
                                    ((ArrayList) modelsNames).set(2, "http://cim-profile.ucaiug.io/grid/SimulationResults/Constraints/1.0#");
                                }
                                case "DLProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "dl");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/DiagramLayout/Constraints#");
                                }
                                case "EQProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "eq");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/Equipment/Constraints#");
                                }
                                case "GLProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "gl");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/GeographicalLocation/Constraints#");
                                }
                                case "SCProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sc");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuit/Constraints#");
                                }
                                case "SSHProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "ssh");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/SteadyStateHypothesis/Constraints#");
                                }
                                case "SVProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "sv");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/StateVariables/Constraints#");
                                }
                                case "TPProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "tp");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/Topology/Constraints#");
                                }
                                case "LTDSShortCircuitResultProfile", "DocLTDSShortCircuitResultProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "scr");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuitResults/Constraints#");
                                }
                                case "LTDSSystemCapacityProfile", "DocLTDSSystemCapacityProfile" -> {
                                    ((ArrayList) modelsNames).set(1, "syscap");
                                    ((ArrayList) modelsNames).set(2, "http://ofgem.gov.uk/ns/CIM/LTDS/SystemCapacity/Constraints#");
                                }
                            }
                        }
                    }
                }
            }
            if (cbApplyDefBaseURIDesignTab.isSelected()) {
                ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
                for (Object modelsNames : this.modelsNames) {
                    for (TreeItem<String> treeitem : treeitems) {
                        if (treeitem.getValue().equals(((ArrayList) modelsNames).get(0))) {
                            switch (treeitem.getValue()) {
                                case "CoreEquipmentProfile", "EquipmentProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/EquipmentCore/3/1/Constraints");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/CoreEquipment/Constraints");
                                    }
                                }
                                case "OperationProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Operation-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/EquipmentOperation/3/1/Constraints");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Operation/Constraints");
                                    }
                                }
                                case "ShortCircuitProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/EquipmentShortCircuit/3/1/Constraints");
                                    } else if (profileVersion == 3) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/ShortCircuit/Constraints");
                                    }
                                }
                                case "SteadyStateHypothesisProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1/Constraints");
                                    }
                                }
                                case "TopologyProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Topology-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/Topology/4/1/Constraints");
                                    }
                                }
                                case "StateVariablesProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/StateVariables-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/StateVariables/4/1/Constraints");
                                    }
                                }
                                case "DiagramLayoutProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/DiagramLayout/3/1/Constraints");
                                    }
                                }
                                case "GeographicalLocationProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/GeographicalLocation/2/1/Constraints");
                                    }
                                }
                                case "DynamicsProfile", "DocDynamicsProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Dynamics-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/Dynamics/3/1/Constraints");
                                    } else if (profileVersion == 4) {
                                        ((ArrayList) modelsNames).set(3, "http://cim-profile.ucaiug.io/grid/Dynamics/Constraints/2.0");
                                    }
                                }
                                case "EquipmentBoundaryProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/EquipmentBoundary/3/1/Constraints");
                                    }
                                }
                                case "TopologyBoundaryProfile" -> {
                                    if (profileVersion == 1) {
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/Constraints");
                                    } else if (profileVersion == 2) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/CIM/TopologyBoundary/3/1/Constraints");
                                    }
                                }
                                case "FileHeaderProfile" ->
                                        ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/61970-552/ModelDescription/Constraints");
                                case "PowerSystemProjectProfile", "DocPowerSystemProjectProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/PowerSystemProject-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/PowerSystemProject-Simple/2.3");
                                    }
                                }
                                case "RemedialActionScheduleProfile", "DocRemedialActionScheduleProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/RemedialActionSchedule-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/RemedialActionSchedule-Simple/2.3");
                                    }
                                }
                                case "SecurityAnalysisResultProfile", "DocSecurityAnalysisResultProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/SecurityAnalysisResult-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/SecurityAnalysisResult-Simple/2.4");
                                    }
                                }
                                case "SensitivityMatrixProfile", "DocSensitivityMatrixProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/SensitivityMatrix-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/SensitivityMatrix-Simple/2.3");
                                    }
                                }
                                case "EquipmentReliabilityProfile", "DocEquipmentReliabilityProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/EquipmentReliability-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/EquipmentReliability-Simple/2.3");
                                    }
                                }
                                case "RemedialActionProfile", "DocRemedialActionProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/RemedialAction-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/RemedialAction-Simple/2.3");
                                    }
                                }
                                case "SteadyStateInstructionProfile", "DocSteadyStateInstructionProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/SteadyStateInstruction-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/SteadyStateInstruction-Simple/2.3");
                                    }
                                }
                                case "AvailabilityScheduleProfile", "DocAvailabilityScheduleProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/AvailabilitySchedule-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/AvailabilitySchedule-Simple/2.3");
                                    }
                                }
                                case "AssessedElementProfile", "DocAssessedElementProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/AssessedElement-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/AssessedElement-Simple/2.3");
                                    }
                                }
                                case "SecurityScheduleProfile", "DocSecurityScheduleProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/SecuritySchedule-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/SecuritySchedule-Simple/2.3");
                                    }
                                }
                                case "ContingencyProfile", "DocContingencyProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/Contingency-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/Contingency-Simple/2.3");
                                    }
                                }
                                case "DocumentHeaderProfile", "DocDocumentHeaderProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/DocumentHeader-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/DocumentHeader-Simple/2.3");
                                    }
                                }
                                case "DatasetMetadataProfile", "DocDatasetMetadataProfile" -> {
                                    if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/DatasetMetadata-Simple/2.4");
                                    }
                                }
                                case "GridDisturbanceProfile", "DocGridDisturbanceProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/GridDisturbance-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/GridDisturbance-Simple/2.3");
                                    }
                                }
                                case "ImpactAssessmentMatrixProfile", "DocImpactAssessmentMatrixProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/ImpactAssessmentMatrix-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/ImpactAssessmentMatrix-Simple/2.3");
                                    }
                                }
                                case "MonitoringAreaProfile", "DocMonitoringAreaProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/MonitoringArea-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/MonitoringArea-Simple/2.3");
                                    }
                                }
                                case "ObjectRegistryProfile", "DocObjectRegistryProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/ObjectRegistry-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/ObjectRegistry-Simple/2.2");
                                    }
                                }
                                case "PowerScheduleProfile", "DocPowerScheduleProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/PowerSchedule-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/PowerSchedule-Simple/2.3");
                                    }
                                }
                                case "StateInstructionScheduleProfile", "DocStateInstructionScheduleProfile" -> {
                                    if (profileVersion == 5) {
                                        ((ArrayList) modelsNames).set(3, "http://entsoe.eu/ns/CIM/StateInstructionSchedule-EU/Constraints");
                                    } else if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/StateInstructionSchedule-Simple/2.3");
                                    }
                                }
                                case "SteadyStateHypothesisScheduleProfile",
                                     "DocSteadyStateHypothesisScheduleProfile" -> {
                                    if (profileVersion == 6) {
                                        ((ArrayList) modelsNames).set(3, "https://ap-con.cim4.eu/SteadyStateHypothesisSchedule-Simple/1.0");
                                    }
                                }
                                case "DetailedModelConfigurationProfile", "DocDetailedModelConfigurationProfile" ->
                                        ((ArrayList) modelsNames).set(3, "http://cim-profile.ucaiug.io/grid/DetailedModelConfiguration/Constraints/1.0");
                                case "DetailedModelParameterisationProfile",
                                     "DocDetailedModelParameterisationProfile" ->
                                        ((ArrayList) modelsNames).set(3, "http://cim-profile.ucaiug.io/grid/DetailedModelParameterisation/Constraints/1.0");
                                case "SimulationSettingsProfile", "DocSimulationSettingsProfile" ->
                                        ((ArrayList) modelsNames).set(3, "http://cim-profile.ucaiug.io/grid/SimulationSettings/Constraints/1.0");
                                case "SimulationResultsProfile", "DocSimulationResultsProfile" ->
                                        ((ArrayList) modelsNames).set(3, "http://cim-profile.ucaiug.io/grid/SimulationResults/Constraints/1.0");
                                case "DLProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/DiagramLayout/Constraints");
                                case "EQProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/Equipment/Constraints");
                                case "GLProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/GeographicalLocation/Constraints");
                                case "SCProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuit/Constraints");
                                case "SSHProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/SteadyStateHypothesis/Constraints");
                                case "SVProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/StateVariables/Constraints");
                                case "TPProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/Topology/Constraints");
                                case "LTDSShortCircuitResultProfile", "DocLTDSShortCircuitResultProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuitResults/Constraints");
                                case "LTDSSystemCapacityProfile", "DocLTDSSystemCapacityProfile" ->
                                    ((ArrayList) modelsNames).set(3, "http://ofgem.gov.uk/ns/CIM/LTDS/SystemCapacity/Constraints");
                            }
                        }
                    }
                }
            }
        }
    }

    @FXML
    //action for the tree in the Generate Shapes Tab
    private void actionTreeProfileConstraintsTab(MouseEvent mouseEvent) {

        String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().getFirst().getValue();
        for (Object modelsNames : this.modelsNames) {
            if (selectedProfile.equals(((ArrayList<?>) modelsNames).getFirst())) {
                fPrefixCreateCompleteSMTab.setText(((ArrayList<?>) modelsNames).get(1).toString());
                fURICreateCompleteSMTab.setText(((ArrayList<?>) modelsNames).get(2).toString());
                fshapesBaseURICreateCompleteSMTab.setText(((ArrayList<?>) modelsNames).get(3).toString());
            }
        }

        btnConstructShacl.setDisable(false);
    }

    @FXML
    //action for button Create in the tab RDFS to SHACL
    private void actionBtnConstructShacl(ActionEvent actionEvent) throws IOException {
        excludeMRID = cbRDFSSHACLoptionDescr.isSelected();

        String cbvalue;
        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem() == null) {
            cbvalue = "";
        } else {
            cbvalue = fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString();
        }
        if (cbRDFSSHACLoption1.isSelected()) {
            associationValueTypeOption = 1;
        } else {
            associationValueTypeOption = 0;
        }


        if (!treeViewProfileConstraints.getSelectionModel().getSelectedItems().isEmpty()) {
            //depending on the value of the choice box "Save datatype map"
            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("No map; No save")) {
                shaclNodataMap = 1;
            } else {
                shaclNodataMap = 0;
            }
            if (shapeModels == null) {
                shapeModels = new ArrayList<>();
            }
            if (shapeModelsNames == null) {
                shapeModelsNames = new ArrayList<>();
            }
            shapeDatas = new ArrayList<>();
            Map<String, RDFDatatype> dataTypeMapFromShapesComplete = new HashMap<>(); // this is the complete map for the export in one .properties
            ArrayList<Integer> modelNumber = new ArrayList<>();
            List<String> profileList = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            List<String> namespaces = new ArrayList<>();
            List<String> baseURIs = new ArrayList<>();
            List<String> owlImports = new ArrayList<>();
            for (int sel = 0; sel < treeViewProfileConstraints.getSelectionModel().getSelectedItems().size(); sel++) {
                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(sel).getValue();
                for (int i = 0; i < this.modelsNames.size(); i++) {
                    if (((ArrayList<?>) this.modelsNames.get(i)).get(0).equals(selectedProfile)) {
                        modelNumber.add(i);
                        profileList.add(selectedProfile);
                        prefixes.add(((ArrayList<?>) this.modelsNames.get(i)).get(1).toString());
                        namespaces.add(((ArrayList<?>) this.modelsNames.get(i)).get(2).toString());
                        baseURIs.add(((ArrayList<?>) this.modelsNames.get(i)).get(3).toString());
                        owlImports.add(((ArrayList<?>) this.modelsNames.get(i)).get(4).toString());
                    }
                }
            }
            // ask for confirmation before proceeding
            String title = "Confirmation needed";
            String header = "The shapes will be generated with the following basic conditions. Please review and confirm in order to proceed.";
            String contextText = "Could you confirm the information below?";
            String labelText = "Details:";
            //TODO: make this nicer
            //set the content of the details window
            String detailedText = "The following profiles are selected: \n";
            detailedText = detailedText + profileList + "\n";
            detailedText = detailedText + "The namespaces for the selected profiles are: \n";
            detailedText = detailedText + prefixes + "\n";
            detailedText = detailedText + namespaces + "\n";
            detailedText = detailedText + "The base URIs for the selected profiles are: \n";
            detailedText = detailedText + baseURIs + "\n";
            detailedText = detailedText + "The owl:imports for the selected profiles are: \n";
            detailedText = detailedText + owlImports + "\n";
            Alert alert = GUIhelper.expandableAlert(title, header, contextText, labelText, detailedText);
            alert.getDialogPane().setExpanded(true);

            Optional<ButtonType> result = alert.showAndWait();
            if (result.get() != ButtonType.OK) {
                return;
            }

            baseprofilesshaclglag = 0;
            baseprofilesshaclglag2nd = 0;
            baseprofilesshaclglag3rd = 0;
            baseprofilesshaclignorens = 0;
            if (cbRDFSSHACLoptionBaseprofiles.isSelected()) { // load base profiles if the checkbox is selected
                baseprofilesshaclglag = 1;
                //load base profiles for shacl
                List<File> basefiles = util.ModelFactory.filechoosercustom(false, "RDF file", List.of("*.rdf"), "Select Base profiles");
                if (basefiles != null) {
                    unionmodelbaseprofilesshacl = util.ModelFactory.modelLoad(basefiles, "", Lang.RDFXML, true);
                    unionmodelbaseprofilesshaclinheritance = modelInheritance(unionmodelbaseprofilesshacl, true, true);
                    unionmodelbaseprofilesshaclinheritanceonly = modelInheritance; // this contains the inheritance of the classes under OWL2.members

//                    if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim") != null) {
//                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim");
//                    }
                    if (cbRDFSSHACLoptionBaseprofilesIgnoreNS.isSelected()) {
                        baseprofilesshaclignorens = 1;
                    }

                    if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim16") != null) {
                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim16");
                        cimPref = "cim16";
                    } else if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim17") != null) {
                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim17");
                        cimPref = "cim17";
                    } else if (unionmodelbaseprofilesshacl.getNsPrefixURI("cim18") != null) {
                        cimURI = unionmodelbaseprofilesshacl.getNsPrefixURI("cim18");
                        cimPref = "cim18";
                    }

                    if (cbRDFSSHACLoptionBaseprofiles2nd.isSelected()) { // load base profiles if the checkbox is selected
                        baseprofilesshaclglag2nd = 1;
                        //load base profiles for shacl
                        List<File> basefiles2nd = util.ModelFactory.filechoosercustom(false, "RDF file", List.of("*.rdf"), "Select 2nd set of Base profiles");
                        if (basefiles2nd != null) {
                            unionmodelbaseprofilesshacl2nd = util.ModelFactory.modelLoad(basefiles2nd, "", Lang.RDFXML, true);
                            unionmodelbaseprofilesshaclinheritance2nd = modelInheritance(unionmodelbaseprofilesshacl2nd, true, true);
                            unionmodelbaseprofilesshaclinheritanceonly2nd = modelInheritance; // this contains the inheritance of the classes under OWL2.members

                            if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim16") != null) {
                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim16");
                                cim2Pref = "cim16";
                            } else if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim17") != null) {
                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim17");
                                cim2Pref = "cim17";
                            } else if (unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim18") != null) {
                                cim2URI = unionmodelbaseprofilesshacl2nd.getNsPrefixURI("cim18");
                                cim2Pref = "cim18";
                            }

                            if (cim2Pref.equals("cim16") || cim2Pref.equals("cim17") || cim2Pref.equals("cim18")) {
                                unionmodelbaseprofilesshacl.add(unionmodelbaseprofilesshacl2nd);
                                unionmodelbaseprofilesshaclinheritance.add(unionmodelbaseprofilesshaclinheritance2nd);
                                unionmodelbaseprofilesshaclinheritanceonly.add(unionmodelbaseprofilesshaclinheritanceonly2nd);
                            }

                            if (cbRDFSSHACLoptionBaseprofiles3rd.isSelected()) { // load base profiles if the checkbox is selected
                                baseprofilesshaclglag3rd = 1;
                                //load base profiles for shacl
                                List<File> basefiles3rd = util.ModelFactory.filechoosercustom(false, "RDF file", List.of("*.rdf"), "Select 3rd set of Base profiles");
                                if (basefiles3rd != null) {
                                    unionmodelbaseprofilesshacl3rd = util.ModelFactory.modelLoad(basefiles3rd, "", Lang.RDFXML, true);
                                    unionmodelbaseprofilesshaclinheritance3rd = modelInheritance(unionmodelbaseprofilesshacl3rd, true, true);
                                    unionmodelbaseprofilesshaclinheritanceonly3rd = modelInheritance; // this contains the inheritance of the classes under OWL2.members

                                    if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim16") != null) {
                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim16");
                                        cim3Pref = "cim16";
                                    } else if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim17") != null) {
                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim17");
                                        cim3Pref = "cim17";
                                    } else if (unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim18") != null) {
                                        cim3URI = unionmodelbaseprofilesshacl3rd.getNsPrefixURI("cim18");
                                        cim3Pref = "cim18";
                                    }

                                    if (cim3Pref.equals("cim16") || cim3Pref.equals("cim17") || cim3Pref.equals("cim18")) {
                                        unionmodelbaseprofilesshacl.add(unionmodelbaseprofilesshacl3rd);
                                        unionmodelbaseprofilesshaclinheritance.add(unionmodelbaseprofilesshaclinheritance3rd);
                                        unionmodelbaseprofilesshaclinheritanceonly.add(unionmodelbaseprofilesshaclinheritanceonly3rd);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            shaclflaginverse = 0;
            if (cbRDFSSHACLoptionInverse.isSelected()) {
                shaclflaginverse = 1;
            }

            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

            for (int m : modelNumber) {
                //here the preparation starts
                dataTypeMapFromShapes = new HashMap<>();

                Model model = (Model) this.models.get(m);
                String rdfNs = MainController.prefs.get("cimsNamespace", "");
                String rdfCase = "";
                if (rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2019") && cbvalue.equals("RDFS (augmented, v2019) by CimSyntaxGen")) {
                    rdfCase = "RDFS2019";
                } else if (rdfFormatInput.equals("CimSyntaxGen-RDFS-Augmented-2020") && cbvalue.equals("RDFS (augmented, v2020) by CimSyntaxGen")) {
                    rdfCase = "RDFS2020";
                    //this option is adding header to the SHACL that is generated.
                } else if (rdfFormatInput.equals("CIMTool-merged-owl") && cbvalue.equals("CIMTool-merged-owl")) {
                    rdfCase = "CIMToolOWL";
                }


                switch (rdfCase) {
                    case "RDFS2019" -> {
                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);

                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles

                        //here the preparation ends

                        String nsPrefixprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile

                        String nsURIprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile

                        String baseURI = ((ArrayList<?>) this.modelsNames.get(m)).get(3).toString();
                        //}
                        String owlImport = ((ArrayList<?>) this.modelsNames.get(m)).get(4).toString();
                        //generate the shape model
                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData);


                        if (baseprofilesshaclglag == 1) {
                            shapeModel.setNsPrefix(cimPref, cimURI);
                        }

                        if (baseprofilesshaclglag2nd == 1) {
                            shapeModel.setNsPrefix(cim2Pref, cim2URI);
                        }

                        if (baseprofilesshaclglag3rd == 1) {
                            shapeModel.setNsPrefix(cim3Pref, cim3URI);
                        }

                        //add the owl:imports
                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);

                        shapeModels.add(shapeModel);
                        shapeModelsNames.add(this.modelsNames.get(m));

                        //open the ChoiceDialog for the save file and save the file in different formats
                        String titleSaveAs = "Save as for shape model: " + ((ArrayList<?>) this.modelsNames.get(m)).get(0).toString();

                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);

                        //this is used for the printing of the complete map in option "All profiles in one map"
                        for (Object key : dataTypeMapFromShapes.keySet()) {
                            dataTypeMapFromShapesComplete.putIfAbsent((String) key, dataTypeMapFromShapes.get(key));
                        }
                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
                            Properties properties = new Properties();

                            for (Object key : dataTypeMapFromShapes.keySet()) {
                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
                            }
                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);

                        }
                        //add the shapes in the tree view


                        //exporting the model to ttl ready function

                    }
                    case "RDFS2020" -> {
                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);

                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles

                        //here the preparation ends

                        String nsPrefixprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile

                        String nsURIprofile = ((ArrayList<?>) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile

                        String baseURI = ((ArrayList<?>) this.modelsNames.get(m)).get(3).toString();
                        //}
                        String owlImport = ((ArrayList<?>) this.modelsNames.get(m)).get(4).toString();
                        //generate the shape model
                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData);

                        //add the owl:imports
                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);

                        //add header
                        if (model.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
                            shapeModel = ShaclTools.addSHACLheader(shapeModel, baseURI);
                        }

                        if (baseprofilesshaclglag == 1) {
                            shapeModel.setNsPrefix(cimPref, cimURI);
                        }

                        if (baseprofilesshaclglag2nd == 1) {
                            shapeModel.setNsPrefix(cim2Pref, cim2URI);
                        }

                        if (baseprofilesshaclglag3rd == 1) {
                            shapeModel.setNsPrefix(cim3Pref, cim3URI);
                        }

                        shapeModels.add(shapeModel);
                        shapeModelsNames.add(this.modelsNames.get(m));
                        //optimise prefixes, strip unused prefixes
                        //if (stripPrefixes){
                        Map<String, String> modelPrefMap = shapeModel.getNsPrefixMap();
                        LinkedList<String> uniqueNamespacesList = new LinkedList<>();
                        for (StmtIterator ns = shapeModel.listStatements(); ns.hasNext(); ) {
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
                            shapeModel.removeNsPrefix(entryTR.getKey());
                        }


                        // Create a new HashMap to store the unique key-value pairs
                        HashMap<String, String> uniqueMap = new HashMap<>();

                        // Create a Set to track unique values
                        Set<String> uniqueValues = new HashSet<>();

                        //Iterate through the original HashMap
                        Map<String, String> origPrefMap = shapeModel.getNsPrefixMap();
                        for (Map.Entry<String, String> entry : origPrefMap.entrySet()) {
                            if (uniqueValues.add(entry.getValue())) {
                                // If the value was added to the set, it means it's unique
                                uniqueMap.put(entry.getKey(), entry.getValue());
                            }
                        }
                        //TODO check if there is cim just to avoid that cim was deleted instead of cim16,cim17 or cim18 in cases where the namespace was the same
                        shapeModel.clearNsPrefixMap();
                        shapeModel.setNsPrefixes(uniqueMap);
                        //}


                        //open the ChoiceDialog for the save file and save the file in different formats
                        String titleSaveAs = "Save as for shape model: " + ((ArrayList<?>) this.modelsNames.get(m)).getFirst().toString();
                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);

                        //this is used for the printing of the complete map in option "All profiles in one map"
                        for (Object key : dataTypeMapFromShapes.keySet()) {
                            dataTypeMapFromShapesComplete.putIfAbsent((String) key, dataTypeMapFromShapes.get(key));
                        }
                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
                            Properties properties = new Properties();

                            for (Object key : dataTypeMapFromShapes.keySet()) {
                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
                            }
                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);

                        }

                    }
                    case "CIMToolOWL" -> {
                        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);

                        shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles

                        //here the preparation ends

                        String nsPrefixprofile = ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile

                        String nsURIprofile = ((ArrayList) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile


                        String baseURI = ((ArrayList) this.modelsNames.get(m)).get(3).toString();
                        //}
                        String owlImport = ((ArrayList) this.modelsNames.get(m)).get(4).toString();
                        //generate the shape model
                        Model shapeModel = ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData);

                        //add the owl:imports
                        shapeModel = ShaclTools.addOWLimports(shapeModel, baseURI, owlImport);

                        shapeModels.add(shapeModel);
                        shapeModelsNames.add(this.modelsNames.get(m));

                        //open the ChoiceDialog for the save file and save the file in different formats
                        String titleSaveAs = "Save as for shape model: " + ((ArrayList) this.modelsNames.get(m)).get(0).toString();
                        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);

                        //this is used for the printing of the complete map in option "All profiles in one map"
                        for (Object key : dataTypeMapFromShapes.keySet()) {
                            dataTypeMapFromShapesComplete.putIfAbsent((String) key, dataTypeMapFromShapes.get(key));
                        }
                        //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user
                        if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
                            Properties properties = new Properties();

                            for (Object key : dataTypeMapFromShapes.keySet()) {
                                properties.put(key, dataTypeMapFromShapes.get(key).toString());
                            }
                            String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
                            properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);

                        }
                    }
                }
            }

            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("All profiles in one map")) {
                File saveFile = util.ModelFactory.filesavecustom("Datatypes mapping files", List.of("*.properties"), "", "");

                if (saveFile != null) {
                    //MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
                    Properties properties = new Properties();

                    for (Object key : dataTypeMapFromShapesComplete.keySet()) {
                        properties.put(key, dataTypeMapFromShapesComplete.get(key).toString());
                    }
                    properties.store(new FileOutputStream(saveFile.toString()), null);
                }
            }
            progressBar.setProgress(1);

        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please select a profile for which you would like to generate Shapes and the RDFS format.");
            alert.setHeaderText(null);
            alert.setTitle("Error - no profile selected");
            alert.showAndWait();
        }
    }

    @FXML
    //Action for button "Load Data" related to tab Instance Data Browser
    private void actionBtnLoadInstanceData(ActionEvent actionEvent) throws FileNotFoundException {
        progressBar.setProgress(0);
        treeID = true;

        //select file
        List<File> fileL = util.ModelFactory.filechoosercustom(false, "Instance files", List.of("*.xml", "*.zip"), "");
        String xmlBase = "http://griddigit.eu#";

        InstanceModelMap = InstanceDataFactory.modelLoad(fileL, xmlBase, null, false);

        guiTreeInstanceDataInit(); //initializes the tree
    }

    @FXML
    //Action for button "Open" related to tab SHACL Shape Browser
    private void actionBtnOpenShapeBrowser(ActionEvent actionEvent) {
        progressBar.setProgress(0);

        //select file
        List<File> fileL = util.ModelFactory.filechoosercustom(false, "SHACL Shape file", List.of("*.rdf", "*.ttl"), "");

        if (fileL != null) {// the file is selected
            for (int m = 0; m < fileL.size(); m++) {
                util.ModelFactory.shapeModelLoad(m, fileL); //loads shape model
                ArrayList<String> mpak1 = new ArrayList<>();
                mpak1.add(FilenameUtils.getBaseName(((File) fileL.get(m)).getName())); // adds the name without the extension.
                Map<String, String> prefixMap = ((Model) shapeModels.get(m)).getNsPrefixMap();
                for (Object o : prefixMap.values()) {
                    String value = o.toString();
                    if (value.contains(defaultShapesURI) || value.toLowerCase().contains("constraints")) {
                        mpak1.add(((Model) shapeModels.get(m)).getNsURIPrefix(value)); //reserved for the prefix of the profile/shapes
                        mpak1.add(value); // reserved for the URI of the profile/shapes
                        mpak1.add(value); // reserved for the baseURI of the shapes
                        mpak1.add("");// reserved for the owl:imports
                        break;
                    }
                }
                shapeModelsNames.add(mpak1);
            }
            guiTreeDesignShapesInit(); //initializes the tree in the design shapes tab //Perhaps not forMalte
        }
    }

    @FXML
    //Action for button "Unload Shapes" related to tab SHACL Shape Browser
    private void actionBrtUnloadInstanceData(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        MainController.InstanceModelMap = null;
        treeViewIDStatic.setRoot(null);
        //fPrefixGenerateTab.clear();
        //fURIGenerateTab.clear();
        //fshapesBaseURIDefineTab.clear();
        //fowlImportsDefineTab.clear();
        fsourceDefineTab.clear();
        tableViewBrowseID.getItems().clear();
    }

    @FXML
    //Action for button "Unload Shapes" related to tab SHACL Shape Browser
    private void actionBrtUnloadShapes(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        MainController.shapeModels = null;
        MainController.shapeModelsNames = null;
        treeViewConstraints.setRoot(null);
        fPrefixGenerateTab.clear();
        fURIGenerateTab.clear();
        fshapesBaseURIDefineTab.clear();
        fowlImportsDefineTab.clear();
        fsourceDefineTab.clear();
        tableViewBrowseModify.getItems().clear();
    }

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

    //initializes the TreeView in the SHACL Shape Browser
    private static void guiTreeDesignShapesInit() {

        TreeItem<String> rootMain = treeViewConstraintsStatic.getRoot();
        if (rootMain == null) {
            //define the Treeview
            rootMain = new CheckBoxTreeItem<>("Main root");
            rootMain.setExpanded(true);
            treeViewConstraintsStatic.setRoot(rootMain); // sets the root to the gui object
            treeViewConstraintsStatic.setShowRoot(false);
            treeMapConstraints = new HashMap<>();
            treeMapConstraintsInverse = new HashMap<>();
        }
        for (int sm = 0; sm < shapeModels.size(); sm++) {
            Model shapeModel = (Model) shapeModels.get(sm);
            String profileItemString = ((ArrayList<?>) shapeModelsNames.get(sm)).get(1).toString() + ":" + ((ArrayList<?>) shapeModelsNames.get(sm)).get(0).toString();
            if (!treeMapConstraints.containsValue(profileItemString)) {
                TreeItem<String> profileItem = new TreeItem<>(profileItemString);
                rootMain.getChildren().add(profileItem);
                treeMapConstraints.putIfAbsent(profileItem, profileItemString);
                treeMapConstraintsInverse.putIfAbsent(profileItemString, profileItem);

                //showing the PropertyGroup
                for (ResIterator i = shapeModel.listSubjectsWithProperty(RDF.type, shapeModel.getProperty(SH.NS, "PropertyGroup")); i.hasNext(); ) {
                    Resource resItem = i.next();
                    String propertyGroupItemString = resItem.getLocalName();
                    String propertyGroupPrefix = shapeModel.getNsURIPrefix(resItem.getNameSpace());
                    TreeItem<String> propertyGroupItem = new TreeItem<>(propertyGroupPrefix + ":" + propertyGroupItemString); // level for the NodeShapes
                    treeMapConstraintsInverse.get(profileItemString).getChildren().add(propertyGroupItem);
                }

                for (ResIterator i = shapeModel.listSubjectsWithProperty(RDF.type, shapeModel.getProperty(SH.NS, "SPARQLConstraint")); i.hasNext(); ) {
                    Resource resItem = i.next();
                    String sparqlItemString = resItem.getLocalName();
                    String sparqlPrefix = shapeModel.getNsURIPrefix(resItem.getNameSpace());
                    TreeItem<String> sparqlItem = new TreeItem<>(sparqlPrefix + ":" + sparqlItemString); // level for the SPARQLConstraint
                    treeMapConstraintsInverse.get(profileItemString).getChildren().add(sparqlItem);
                }

                for (ResIterator i = shapeModel.listSubjectsWithProperty(RDF.type, shapeModel.getProperty(SH.NS, "NodeShape")); i.hasNext(); ) {
                    Resource resItem = i.next();
                    String nodeShapeItemString = resItem.getLocalName();
                    String nodeShapePrefix = shapeModel.getNsURIPrefix(resItem.getNameSpace());
                    TreeItem<String> nodeShapeItem = new TreeItem<>(nodeShapePrefix + ":" + nodeShapeItemString); // level for the NodeShapes
                    treeMapConstraintsInverse.get(profileItemString).getChildren().add(nodeShapeItem);
                    for (NodeIterator j = shapeModel.listObjectsOfProperty(resItem, shapeModel.getProperty(SH.NS, "sparql")); j.hasNext(); ) { // this is if the NodeShape has sparqlConstraint
                        RDFNode resItemObject = j.next();
                        String sparqlConstraintPrefix = shapeModel.getNsURIPrefix(resItemObject.asResource().getNameSpace());
                        TreeItem<String> sparqlConstraintItem = new TreeItem<>(sparqlConstraintPrefix + ":" + resItemObject.asResource().getLocalName()); // level for the sparqlConstraint
                        nodeShapeItem.getChildren().add(sparqlConstraintItem);
                    }
                    for (NodeIterator j = shapeModel.listObjectsOfProperty(resItem, shapeModel.getProperty(SH.NS, "property")); j.hasNext(); ) {
                        RDFNode resItemObject = j.next();
                        String propertyShapePrefix = shapeModel.getNsURIPrefix(resItemObject.asResource().getNameSpace());
                        TreeItem<String> propertyShapeItem = new TreeItem<>(propertyShapePrefix + ":" + resItemObject.asResource().getLocalName()); // level for the PropertyShapes
                        nodeShapeItem.getChildren().add(propertyShapeItem);
                        for (NodeIterator sc = shapeModel.listObjectsOfProperty(resItem, shapeModel.getProperty(SH.NS, "sparql")); sc.hasNext(); ) { // this is if the PropertyShape has sparqlConstraint
                            RDFNode resItemObjectSC = sc.next();
                            String sparqlConstraintPrefix = shapeModel.getNsURIPrefix(resItemObjectSC.asResource().getNameSpace());
                            TreeItem<String> sparqlConstraintItem = new TreeItem<>(sparqlConstraintPrefix + ":" + resItemObjectSC.asResource().getLocalName()); // level for the sparqlConstraint
                            propertyShapeItem.getChildren().add(sparqlConstraintItem);
                        }
                    }
                }
            }
        }
        //treeViewConstraints.setCellFactory(CheckBoxTreeCell.<String>forTreeView()); //this sets checkbox in front
        treeViewConstraintsStatic.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

    }

    //initializes the TreeView in the Instance Data Browser
    public void guiTreeInstanceDataInit() {

        TreeItem<String> rootMain = treeViewIDStatic.getRoot();
        if (rootMain == null) {
            //define the Treeview
            //rootMain = new CheckBoxTreeItem<>("Main root");
            rootMain = new TreeItem<>("Main root");
            rootMain.setExpanded(true);
            //treeViewInstanceData.setShowExpandable(true);
            treeViewIDStatic.setRoot(rootMain); // sets the root to the gui object
            treeViewIDStatic.setShowRoot(false);
            treeMapID = new HashMap<>();
            //treeMapConstraintsInverse = new HashMap<>();

        }
        LinkedList<String> tvlevel1 = new LinkedList<>();
        for (Map.Entry<String, Model> entry : InstanceModelMap.entrySet()) {
            String key = entry.getKey();
            tvlevel1.add(key);
        }
        Collections.sort(tvlevel1);
        TaggedTreeItem<String> tItem;
        if (cbShowUnionModelOnly.isSelected()) {
            tItem = new TaggedTreeItem<>("Union Model");
            rootMain.getChildren().add(tItem);
            treeMapID.putIfAbsent(tItem, "unionModel");
            tItem.setTag("fileOrModel");

            LinkedList<String> classList = InstanceDataFactory.getClassesForTree(InstanceModelMap.get("unionModel"));
            for (String i : classList) {
                TaggedTreeItem<String> cItem = new TaggedTreeItem<>(i);
                tItem.getChildren().add(cItem);
                cItem.setTag("classType");
            }
        } else {
            tItem = new TaggedTreeItem<>("Union Model");
            rootMain.getChildren().add(tItem);
            treeMapID.putIfAbsent(tItem, "unionModel");
            tItem.setTag("fileOrModel");

            LinkedList<String> classList = InstanceDataFactory.getClassesForTree(InstanceModelMap.get("unionModel"));
            for (String i : classList) {
                TaggedTreeItem<String> cItem = new TaggedTreeItem<>(i);
                tItem.getChildren().add(cItem);
                cItem.setTag("classType");
            }

            for (String key : tvlevel1) {
                if (!treeMapID.containsValue(key) && !key.equals("modelUnionWithoutHeader") && !key.equals("unionModel")) {
                    tItem = new TaggedTreeItem<>(key.split(".xml\\|", 2)[0]);
                    rootMain.getChildren().add(tItem);
                    treeMapID.putIfAbsent(tItem, key);
                    tItem.setTag("fileOrModel");

                    classList = InstanceDataFactory.getClassesForTree(InstanceModelMap.get(key));
                    for (String i : classList) {
                        TaggedTreeItem<String> cItem = new TaggedTreeItem<>(i);
                        tItem.getChildren().add(cItem);
                        cItem.setTag("classType");
                    }
                }
            }
        }

        treeViewIDStatic.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

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
                    String tiName = ti.getValue().toString();
                    LinkedList<String> classInstance = InstanceDataFactory.getClassInstancesForTree(selectedModel, tiName);

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
                    String tiName = ti.getValue().toString();
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
                    String tiName = ti.getValue().toString();
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
                    if (selectedModel.listStatements(ResourceFactory.createResource("http://griddigit.eu#" + tiName.split("\\|", 2)[1]), RDF.type, (RDFNode) null).hasNext()) {
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
    //select action on the tree view Instance data
    private void selectActionTreeItemID(MouseEvent mouseEvent) throws IOException {
        //Initialisation GUI
        tableViewBrowseID.getItems().clear();

        Node node = mouseEvent.getPickResult().getIntersectedNode();
        // Accept clicks only on node cells, and not on empty spaces of the TreeView
        if (node instanceof Text || (node instanceof TreeCell && ((TreeCell) node).getText() != null)) {
            ArrayList<Object> parentInfo = getParent(treeViewIDStatic); //returns a structure containing the profile parent, the tree level, the selected item

            ObservableList<TableColumnsSetup> tableData = tableViewBrowseID.getItems();
            tableViewBrowseID.setItems(tableData);

        }

    }

    @FXML
    //select action on the tree view SHACL Shapes
    private void selectActionTreeItemConstraits(MouseEvent mouseEvent) throws IOException {
        //Initialisation GUI
        tableViewBrowseModify.getItems().clear();

        fPrefixGenerateTab.setText(""); // namespace "Prefix"
        fURIGenerateTab.setText(""); //namespace "URI"
        fshapesBaseURIDefineTab.setText(""); //baseURI
        fowlImportsDefineTab.setText(""); // "owl:imports"

        Node node = mouseEvent.getPickResult().getIntersectedNode();
        // Accept clicks only on node cells, and not on empty spaces of the TreeView
        if (node instanceof Text || (node instanceof TreeCell && ((TreeCell) node).getText() != null)) {
            //String name = (String) ((TreeItem)treeViewRDFS.getSelectionModel().getSelectedItem()).getValue();
            ArrayList<Object> parentInfo = getParent(treeViewConstraints); //returns a structure containing the profile parent, the tree level, the selected item

            int m = 0;
            for (int i = 0; i < shapeModelsNames.size(); i++) {
                //if (((ArrayList) shapeModelsNames.get(i)).get(0).equals(parentInfo.get(0))) {
                if (parentInfo.get(0).equals(((ArrayList) shapeModelsNames.get(i)).get(1) + ":" + ((ArrayList) shapeModelsNames.get(i)).get(0))) {
                    m = i;
                }
            }

            Model shapeModel = (Model) shapeModels.get(m);
            //selectedShapeModel=m; // used for the combobox constraints

            String prefix = ((TreeItem<String>) parentInfo.get(2)).getValue().split(":", 2)[0];
            // set namespace "Prefix"
            fPrefixGenerateTab.setText(prefix);
            String uri = shapeModel.getNsPrefixURI(prefix);
            //set namespace "URI"
            fURIGenerateTab.setText(uri);
            //set baseURI
            String baseURI = ((ArrayList) shapeModelsNames.get(m)).get(3).toString();
            fshapesBaseURIDefineTab.setText(baseURI);
            //set Owl:imports
            int printSource = 0; //important for the printing of the source of the elements
            if (((TreeView<String>) treeViewConstraints).getSelectionModel().getSelectedItems().get(0).getValue().equals(parentInfo.get(0))) {
                //this is when the parent (the shape model/profile) is selected
                String owlImports = ShaclTools.getOWLimports(shapeModel, uri);
                fowlImportsDefineTab.setText(owlImports);

                //set "As source code" for the whole shape model
                if (btnShowSourceCodeDefineTab.isSelected()) {
                    ByteArrayOutputStream os = new ByteArrayOutputStream();

                    shapeModel.write(os, "TURTLE", baseURI);
                    String stringForGui = os.toString("UTF-8");
                    fsourceDefineTab.setText(stringForGui);
                    os.close();
                }
                //fshapeTypeDefineTab.setText("Type: Shape model");
                return;
            } else {
                //this is when other thing is selected is selected
                //set "As source code" for the whole shape model
                if (btnShowSourceCodeDefineTab.isSelected()) {// the button to show the source is selected
                    printSource = 1;
                }
            }


            String name = ((TreeItem<String>) parentInfo.get(2)).getValue().split(":", 2)[1];
            // set "Shape IRI"
            //fShapeIRIDefineTab.setText(((TreeItem<String>) parentInfo.get(2)).getValue());
            Resource resItem = shapeModel.getResource(uri + name);
            //selectedShapeResource=resItem; // used for the combobox constraints

            //This is used for the printing of the source code
            Model shapeModelPart = JenaUtil.createDefaultModel();
            shapeModelPart.setNsPrefixes(shapeModel.getNsPrefixMap()); // set the same prefixes like the shapeModel
            List<Statement> shapeModelPartStmt = new LinkedList<>();

            //gives all statements related to the resource. 0 means that the resource trippe is not included
            List<Statement> statementsList = ShaclTools.listShapeStatements(shapeModel, resItem, 1);

            ObservableList<TableColumnsSetup> tableData = tableViewBrowseModify.getItems();

            ArrayList<String> shaclNodeShapeProperties = ShaclTools.getListShaclNodeShapeProperties();
            ArrayList<String> shaclPropertyShapeProperties = ShaclTools.getListShaclPropertyShapeProperties();
            ArrayList<String> shaclPropertyGroupProperties = ShaclTools.getListShaclPropertyGroupProperties();
            ArrayList<String> shaclSPARQLConstraintProperties = ShaclTools.getListShaclSPARQLConstraintProperties();
            ArrayList<String> shaclTypeProperties = ShaclTools.getListShacltypeProperties();


            String ShapeType = "";
            for (int stmt = 0; stmt < statementsList.size(); stmt++) {
                if (stmt == 0) {
                    ShapeType = statementsList.get(stmt).getObject().asResource().getLocalName();
                    String propertyValue;
                    String propertyValuePrefix = shapeModel.getNsURIPrefix(statementsList.get(stmt).getObject().asResource().getNameSpace());
                    String propertyValueLN = statementsList.get(stmt).getObject().asResource().getLocalName();
                    propertyValue = propertyValuePrefix + ":" + propertyValueLN;
                    shapeModelPartStmt.add(statementsList.get(stmt)); // add in the partial list for the source code
                    tableData.add(new TableColumnsSetup("rdf:type",
                            propertyValue, FXCollections.<String>observableArrayList(shaclTypeProperties)));
                } else {
                    if (!statementsList.get(stmt).getPredicate().equals(RDF.type)) {
                        //if (!ShapeType.equals(statementsList.get(stmt).getObject().asResource().getLocalName())) {
                        String propertyValue = "";
                        String shapePropertyPrefix = shapeModel.getNsURIPrefix(statementsList.get(stmt).getPredicate().getNameSpace());
                        String shapePropertyLN = statementsList.get(stmt).getPredicate().getLocalName();

                        if (statementsList.get(stmt).getObject().isResource()) {
                            if (!statementsList.get(stmt).getObject().isAnon()) {
                                String propertyValuePrefix = shapeModel.getNsURIPrefix(statementsList.get(stmt).getObject().asResource().getNameSpace());
                                String propertyValueLN = statementsList.get(stmt).getObject().asResource().getLocalName();
                                propertyValue = propertyValuePrefix + ":" + propertyValueLN;
                                shapeModelPartStmt.add(statementsList.get(stmt)); // add in the partial list for the source code
                            } else { //if the object is a blank node
                                //it is assumed that this is a list, e.g. this works for sh:in and sh:or

                                Resource list = statementsList.get(stmt).getObject().asResource();
                                RDFList rdfList = list.as(RDFList.class);
                                ExtendedIterator<RDFNode> items = rdfList.iterator();

                                // add in the the blank node info for the source code
                                Resource blankNode = shapeModelPart.getResource(statementsList.get(stmt).getSubject().getURI());
                                RDFList orRDFlist = shapeModelPart.createList(rdfList.iterator());
                                blankNode.addProperty(statementsList.get(stmt).getPredicate(), orRDFlist);

                                int firstTime = 0;
                                while (items.hasNext()) {
                                    Resource item = items.next().asResource();

                                    String propertyValuePrefix = shapeModel.getNsURIPrefix(item.getNameSpace());
                                    String propertyValueLN = item.getLocalName();
                                    if (firstTime == 0) {
                                        propertyValue = propertyValuePrefix + ":" + propertyValueLN;

                                        firstTime = 1;
                                    } else {
                                        propertyValue = propertyValue + " , " + propertyValuePrefix + ":" + propertyValueLN;
                                    }
                                }
                            }
                        } else if (statementsList.get(stmt).getObject().isLiteral()) {
                            propertyValue = statementsList.get(stmt).getObject().asLiteral().getString();//TODO see if the datatype should be shown
                            shapeModelPartStmt.add(statementsList.get(stmt)); // add in the partial list for the source code
                        } else {
                            propertyValue = statementsList.get(stmt).getObject().toString();
                            shapeModelPartStmt.add(statementsList.get(stmt)); // add in the partial list for the source code
                        }
                        switch (ShapeType) {
                            case "NodeShape":

                                tableData.add(new TableColumnsSetup(shapePropertyPrefix + ":" + shapePropertyLN,
                                        propertyValue, FXCollections.<String>observableArrayList(shaclNodeShapeProperties)));

                                break;
                            case "PropertyShape":
                                tableData.add(new TableColumnsSetup(shapePropertyPrefix + ":" + shapePropertyLN,
                                        propertyValue, FXCollections.<String>observableArrayList(shaclPropertyShapeProperties)));
                                break;
                            case "PropertyGroup":
                                tableData.add(new TableColumnsSetup(shapePropertyPrefix + ":" + shapePropertyLN,
                                        propertyValue, FXCollections.<String>observableArrayList(shaclPropertyGroupProperties)));
                                break;
                            case "SPARQLConstraint":
                                tableData.add(new TableColumnsSetup(shapePropertyPrefix + ":" + shapePropertyLN,
                                        propertyValue, FXCollections.<String>observableArrayList(shaclSPARQLConstraintProperties)));
                                //TODO also for the rest
                                break;
                        }
                        //}
                    }
                }
            }
            tableViewBrowseModify.setItems(tableData);


            // set "As source code" this is when other thing is selected is selected
            if (printSource == 1) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                //LocationMapper.setGlobalLocationMapper(new LocationMapper());

                //TODO see how to cut prefix information not to be printed

                //List<Statement> result = ShaclTools.listShapeStatements(shapeModel, resItem, 1);
                shapeModelPart.add(shapeModelPartStmt);

                shapeModelPart.write(os, "TURTLE", baseURI);
                String stringForGui = os.toString(StandardCharsets.UTF_8);
                fsourceDefineTab.setText(stringForGui);
                os.close();
            }
        }

    }

    //find parent in a tree view
    private ArrayList<Object> getParent(TreeView<String> treeViewToProcess) {
        int parentFound = 0;
        String parentProfile = ""; // this is the name of the profile
        TreeItem<String> selectedItem = treeViewToProcess.getSelectionModel().getSelectedItem();
        TreeItem<String> selectedItemP = selectedItem;
        int treeLevel = 0; //treeLevel=1 if it is the profile level; 2 if it is NodeShape level (for the constraints) or PropertyGroup;
        // 3 is it is PropertyShape level (for the constraints); 4 is if there is a sparql constraint on the PropertyShape
        while (parentFound == 0) {
            if (selectedItemP.getParent().getValue() != null) {
                if (selectedItemP.getParent().getValue().equals("Main root")) {
                    parentProfile = selectedItemP.getValue();
                    treeLevel++;
                    parentFound = 1;
                } else {
                    selectedItemP = selectedItemP.getParent();
                    treeLevel++;
                }
            }
        }
        ArrayList<Object> getParentInfo = new ArrayList<>();
        getParentInfo.add(parentProfile);
        getParentInfo.add(treeLevel);
        getParentInfo.add(selectedItem);
        return getParentInfo;
    }

    @FXML
    // //action for tab pane down - the tab pane with the source code
    private void actionTabConstraintsSourceCode() {
        btnShowSourceCodeDefineTab.setDisable(!tabConstraintsSourceCode.isSelected() || treeViewConstraints.getRoot() == null);
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

    @FXML
    //action menu "Excel to SHACL"
    private void actionBtnRunExcelShape(ActionEvent actionEvent) throws IOException {

        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        if (fPathRdffileForExcel.getText().isBlank() || fPathXLSfileForShape.getText().isBlank() || fcbRDFSformatForExcel.getSelectionModel().getSelectedItem() == null
                || fbaseURIShapeExcel.getText().isBlank() || fPrefixExcelShape.getText().isBlank() || fNSexcelShape.getText().isBlank()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please complete all fields.");
            alert.setHeaderText(null);
            alert.setTitle("Error - not all fields are filled in");
            alert.showAndWait();
            progressBar.setProgress(0);
            return;
        }


        //open the rdfs for the profile
        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        try {
            RDFDataMgr.read(model, new FileInputStream(MainController.rdfModelExcelShacl), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        shaclNodataMap = 1; // as no mapping is to be used for this task
        String cimsNs = MainController.prefs.get("cimsNamespace", "");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);

        //select the xlsx file and read it
        ArrayList<Object> dataExcel = null;

        //if (file != null) {// the file is selected

        dataExcel = ExcelTools.importXLSX(String.valueOf(MainController.xlsFileExcelShacl), 0);

        //TODO: this can be made with many additional options e.g. what to be added and in which model to be added. Now it is primitive to solve a simple task
        //create a new Shapes model
        Model shapeModel = JenaUtil.createDefaultModel();
        //add the namespaces
        shapeModel.setNsPrefix("sh", SH.NS);
        shapeModel.setNsPrefix("dash", DASH.NS);
        shapeModel.setNsPrefix(MainController.prefs.get("prefixEU", ""), MainController.prefs.get("uriEU", ""));
        shapeModel.setNsPrefix("cims", MainController.prefs.get("cimsNamespace", ""));
        shapeModel.setNsPrefix("rdf", RDF.uri);
        shapeModel.setNsPrefix("owl", OWL.NS);
        shapeModel.setNsPrefix("cim", MainController.prefs.get("CIMnamespace", ""));
        shapeModel.setNsPrefix("xsd", XSD.NS);
        shapeModel.setNsPrefix("rdfs", RDFS.uri);
        if (!MainController.prefs.get("prefixOther", "").equals("") && !MainController.prefs.get("uriOther", "").equals("")) {
            shapeModel.setNsPrefix(MainController.prefs.get("prefixOther", ""), MainController.prefs.get("uriOther", ""));
        }

        String baseURI = fbaseURIShapeExcel.getText();
        String nsURIprofilePrefix = fPrefixExcelShape.getText();
        String nsURIprofile = fNSexcelShape.getText();
        shapeModel.setNsPrefix(nsURIprofilePrefix, nsURIprofile);

        //add the shapes in the model - i.e. check what is in the dataExcel and generate the Shapes out of it
        if (dataExcel != null) {
            //adding the PropertyGroup
            String localNameGroup = "ValueConstraintsGroup";
            ArrayList<Object> groupFeatures = new ArrayList<>();
            /*
             * groupFeatures structure
             * 0 - name
             * 1 - description
             * 2 - the value for rdfs:label
             * 3 - order
             */
            for (int i = 0; i < 4; i++) {
                groupFeatures.add("");
            }
            groupFeatures.set(0, "ValueConstraints");
            groupFeatures.set(1, "This group of validation rules relate to value constraints validation of properties(attributes).");
            groupFeatures.set(2, "ValueConstraints");
            groupFeatures.set(3, 0);

            shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

            for (int row = 1; row < dataExcel.size(); row++) { //loop on the rows in the xlsx
                //String localName = ((LinkedList) dataExcel.get(row)).get(6).toString();// here map to "NodeShape" column
                String attributeName = ((LinkedList<?>) dataExcel.get(row)).get(8).toString(); //here map to "path" column

                for (int classRDF = 0; classRDF < ((ArrayList<?>) shapeData.getFirst()).size(); classRDF++) { // this loops the concrete classes in the RDFS
                    for (int attr = 1; attr < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).size(); attr++) { //this loops the attributes of a concrete class including the inherited
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).getFirst().equals("Attribute")) { //this is when the property is an attribute
                            String className = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).get(2).toString(); // this is the name of the class
                            String attributeNameRDFS = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).get(4).toString(); // this is the localName of the attribute


                            if (attributeName.equals(attributeNameRDFS)) {

                                Resource propertyShapeResource = ResourceFactory.createResource(nsURIprofile + ((LinkedList<?>) dataExcel.get(row)).get(7).toString());
                                if (!shapeModel.containsResource(propertyShapeResource)) { // creates only if it is missing

                                    //adding the PropertyShape
                                    Resource r = shapeModel.createResource(propertyShapeResource.toString());
                                    r.addProperty(RDF.type, SH.PropertyShape);

                                    //add the group
                                    RDFNode o1g = shapeModel.createResource(nsURIprofile + localNameGroup);
                                    r.addProperty(SH.group, o1g);

                                    //add the order
                                    RDFNode o1o = shapeModel.createTypedLiteral(row, XSDDatatype.XSDinteger.getURI());
                                    r.addProperty(SH.order, o1o);

                                    //add the message
                                    r.addProperty(SH.message, ((LinkedList<?>) dataExcel.get(row)).get(4).toString());

                                    // add the path
                                    RDFNode o5 = shapeModel.createResource(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).get(1).toString());
                                    r.addProperty(SH.path, o5);

                                    //add the name
                                    r.addProperty(SH.name, ((LinkedList<?>) dataExcel.get(row)).get(2).toString());

                                    //add description
                                    r.addProperty(SH.description, ((LinkedList<?>) dataExcel.get(row)).get(3).toString());

                                    //add severity
                                    RDFNode o8 = shapeModel.createResource(SH.NS + ((LinkedList<?>) dataExcel.get(row)).get(5).toString());
                                    r.addProperty(SH.severity, o8);

                                    //add columns Constraint 1/Value 1

                                    if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("minExclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minExclusive, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("maxExclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxExclusive, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("maxInclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxInclusive, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("minInclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minInclusive, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("maxLength")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.maxLength, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("minLength")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.minLength, constr1);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("equals")) {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.equals, eq);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("disjoint")) {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.disjoint, eq);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("lessThan")) {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.lessThan, eq);
                                    } else if (((LinkedList<?>) dataExcel.get(row)).get(9).toString().equals("lessThanOrEquals")) {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.lessThanOrEquals, eq);
                                    }

                                    if (((LinkedList<?>) dataExcel.get(row)).size() == 13) {
                                        //add columns Constraint 2/Value 2
                                        if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("minExclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minExclusive, constr2);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("maxExclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxExclusive, constr2);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("maxInclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxInclusive, constr2);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("minInclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minInclusive, constr2);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("maxLength")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.maxLength, constr2);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("minLength")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.minLength, constr2);

                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("equals")) {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.equals, eq);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("disjoint")) {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.disjoint, eq);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("lessThan")) {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThan, eq);
                                        } else if (((LinkedList<?>) dataExcel.get(row)).get(11).toString().equals("lessThanOrEquals")) {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThanOrEquals, eq);
                                        }
                                    }
                                }

                                //Adding of the NodeShape if it is not existing
                                Resource nodeShapeResource = ResourceFactory.createResource(nsURIprofile + className);
                                if (!shapeModel.containsResource(nodeShapeResource)) { // creates only if it is missing
                                    String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).getFirst().toString();
                                    shapeModel = ShaclTools.addNodeShape(shapeModel, nsURIprofile, className, classFullURI);
                                }
                                //add the property to the NodeShape
                                RDFNode o = shapeModel.createResource(propertyShapeResource.toString());
                                shapeModel.getResource(String.valueOf(nodeShapeResource)).addProperty(SH.property, o);

                            }
                        }
                    }
                }
            }
        }

        //open the ChoiceDialog for the save file and save the file in different formats
        String titleSaveAs = "Save as for shape model: ";
        File savedFile = ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);

        progressBar.setProgress(1);
    }

    public void actionBrowseShaclFilesToOrganize(ActionEvent actionEvent) {
        selectedFile = util.ModelFactory.filechoosercustom(false, "SHACL Shape file", List.of("*.rdf", "*.ttl"), "");
        if (selectedFile != null){
            StringBuilder paths = new StringBuilder();
            for (int m = 0; m < selectedFile.size(); m++){
                paths.append(", ").append(selectedFile.get(m).toString());
            }
            fPathShaclFilesToOrganize.setText(paths.toString());
        }
    }

    public void actionBrowseExcelfileForShacl(ActionEvent actionEvent) {
        List<File> file = util.ModelFactory.filechoosercustom(true, "Input template instance data XLS", List.of("*.xlsx"), "");

        if (!file.isEmpty()) {
            MainController.inputXLS = file;
            fPathXLSfileForShacl.setText(file.getFirst().toString());
        }
    }

    public void actionBtnRunShaclOrganizer(ActionEvent actionEvent) throws IOException {
        progressBar.setProgress(0);
        if (selectedFile != null) {
            for (int m = 0; m < selectedFile.size(); m++) {
                util.ModelFactory.shapeModelLoad(m, selectedFile);
            }
        }
        ArrayList<Object> inputXLSdata;
        inputXLSdata = ExcelTools.importXLSX(inputXLS.getFirst().toString(), 0);

        ShaclTools.splitShaclPerXlsInput(inputXLSdata);

        progressBar.setProgress(1);
    }

    public void actionBtnResetShaclOrganizer(ActionEvent actionEvent) {
        fPathShaclFilesToOrganize.clear();
        fPathXLSfileForShacl.clear();
        MainController.inputXLS = null;
        selectedFile = null;
    }
}

