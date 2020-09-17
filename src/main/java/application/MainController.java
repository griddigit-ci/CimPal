/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package application;

import core.ComparisonRDFSprofile;
import core.DataTypeMaping;
import core.ShaclTools;
import gui.GUIhelper;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;
import util.ExcelTools;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static core.ExportRDFSdescriptions.rdfsDescriptions;


public class MainController implements Initializable {

    @FXML
    private TextArea foutputWindow;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private TextField fPathRdffile1;
    @FXML
    private TextField fPathRdffile2;
    @FXML
    private Button btnResetRDFComp;
    @FXML
    private Button btnRunRDFcompare;
    @FXML
    private TabPane tabPaneDown;
    public static Preferences prefs;

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
    private  TextField fURICreateCompleteSMTab;
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
    private Button btnRunExcelShape;
    @FXML
    private TextField fPathXLSfileForShape;

    public static File rdfModel1;
    public static File rdfModel2;
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

    private static String defaultShapesURI;


    public MainController() {

    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                appendText(String.valueOf((char)b));
            }
        };
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));

        try {
            if (!Preferences.userRoot().nodeExists("CimPal")){
                prefs = Preferences.userRoot().node("CimPal");
                //set the default preferences
                PreferencesController.prefDefault();
            }else{
                prefs = Preferences.userRoot().node("CimPal");
            }
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }

        cbProfilesVersionCreateCompleteSMTab.getItems().addAll(
                "IEC TS 61970-600-1&2 (CGMES 2.4.15)",
                "IEC 61970-600-1&2 (CGMES 3.0.0)"
        );

        fselectDatatypeMapDefineConstraints.getItems().addAll(
                "No map; No save", // the map will not be generated and not saved
                "All profiles in one map", // the save choice will be for all profile in one file.
                "Per profile" //	the save option will be asked after every shacl file i.e. per profile.
        );

        fcbRDFSformat.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformat.getSelectionModel().selectFirst();

        fcbRDFSformatShapes.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformatShapes.getSelectionModel().selectFirst();

        fcbRDFSformatForExcel.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();


        //TODO: see how to have this default on the screen
        defaultShapesURI="/constraints/";
    }


    public void appendText(String valueOf) {
        Platform.runLater(() -> foutputWindow.appendText(valueOf));
    }

    @FXML
    //action menu item Tools -> Export RDFS description
    private void actionRDFSexportDescriptionMenu(ActionEvent actionEvent) throws FileNotFoundException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        //open RDFS file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        File file = filechooser.showOpenDialog(null);

        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        if (file != null) {// the file is selected

            try {
                RDFDataMgr.read(model, new FileInputStream(file), Lang.RDFXML);
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
    //Action for button "Clear" related to the output window
    private void actionBtnClear(ActionEvent actionEvent) {
        if (tabPaneDown.getSelectionModel().getSelectedItem().getText().equals("Output window")) { //clears Output window
            foutputWindow.clear();
        }
    }

    @FXML
    //Action for menu item "Quit"
    private void menuQuit(ActionEvent actionEvent) {
        Platform.exit(); // Exit the application
    }

    @FXML
    //Action for button "Reset" related to the RDFS Comparison
    private void actionBtnResetRDFComp(ActionEvent actionEvent) {
        fPathRdffile1.clear();
        fPathRdffile2.clear();
        btnRunRDFcompare.setDisable(true);
        progressBar.setProgress(0);

    }

    @FXML
    //Action for button "Reset" related to the Excel to Shacl
    private void actionBtnResetExcelShape(ActionEvent actionEvent) {
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
    private void actionBrowseRDFfile1(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        //select file 1
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        File file = filechooser.showOpenDialog(null);

        if (file != null) {// the file is selected

            fPathRdffile1.setText(file.toString());
            MainController.rdfModel1=file;
            if (!fPathRdffile2.getText().equals("")) {
                btnRunRDFcompare.setDisable(false);
            }

        } else{
            fPathRdffile1.clear();
            btnRunRDFcompare.setDisable(true);
        }
    }

    @FXML
    //action button Browse for RDFS comparison - file 2
    private void actionBrowseRDFfile2(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        //select file 2
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        File file = filechooser.showOpenDialog(null);

        if (file != null) {// the file is selected

            fPathRdffile2.setText(file.toString());
            MainController.rdfModel2=file;
            if (!fPathRdffile1.getText().equals("")) {
                btnRunRDFcompare.setDisable(false);
            }

        }else{
            fPathRdffile2.clear();
            btnRunRDFcompare.setDisable(true);
        }
    }

    @FXML
    //action button RDF file Browse for Excel to SHACL
    private void actionBrowseRDFfileForExcel(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        File file = filechooser.showOpenDialog(null);

        if (file != null) {// the file is selected

            fPathRdffileForExcel.setText(file.toString());
            MainController.rdfModelExcelShacl=file;

        } else{
            fPathRdffileForExcel.clear();
        }
    }

    @FXML
    //action button XLS file Browse for Excel to SHACL
    private void actionBrowseExcelfileForShape(ActionEvent actionEvent) {
        progressBar.setProgress(0);
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
        File file = filechooser.showOpenDialog(null);

        if (file != null) {// the file is selected

            fPathXLSfileForShape.setText(file.toString());
            MainController.xlsFileExcelShacl=file;

        } else{
            fPathXLSfileForShape.clear();
              }
    }





    @FXML
    //action menu item Tools -> RDFS difference
    private void actionBtnRunRDFcompare(ActionEvent actionEvent) {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        Model model1 = ModelFactory.createDefaultModel(); // model for rdf file1
        Model model2 = ModelFactory.createDefaultModel(); // model for rdf file2

        try {
            RDFDataMgr.read(model1, new FileInputStream(MainController.rdfModel1), Lang.RDFXML);
            RDFDataMgr.read(model2, new FileInputStream(MainController.rdfModel2), Lang.RDFXML);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        rdfsCompareFiles = new LinkedList<>();
        rdfsCompareFiles.add(MainController.rdfModel1.getName());
        rdfsCompareFiles.add(MainController.rdfModel2.getName());

        compareResults = ComparisonRDFSprofile.compareRDFSprofile(model1, model2);

        if (compareResults.size() != 0) {

            try {
                Stage guiRdfDiffResultsStage = new Stage();
                //Scene for the menu RDF differences
                FXMLLoader fxmlLoader = new FXMLLoader();
                Parent rootRDFdiff = fxmlLoader.load(getClass().getResource("/fxml/rdfDiffResult.fxml"));
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
    //This is the menu item "Create datatypes map" - loads RDFfile(s) and creates the map
    private void actionMenuDatatypeMap(ActionEvent actionEvent) throws IOException {
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        Map dataTypeMap = DataTypeMaping.mapDatatypesFromRDF();
        if (dataTypeMap!=null) {
/*            //open a question/confirmation dialog to ask if the mapping of the datatypes should be saved
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setContentText("Would you like to save the mapping of the datatypes?");
            alert.setHeaderText(null);
            alert.setTitle("Save of datatypes mapping");

            ButtonType btnYes = new ButtonType("Yes");
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(btnYes, btnCancel);
            Optional<ButtonType> result = alert.showAndWait();
            if (result.get() == btnYes) {*/
                FileChooser filechooser = new FileChooser();
                filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Datatypes mapping files", "*.properties"));
                File saveFile = filechooser.showSaveDialog(null);

                Properties properties = new Properties();

                for (Object key : dataTypeMap.keySet()) {
                    properties.put(key, dataTypeMap.get(key).toString());
                }
                if (saveFile!=null) {
                    properties.store(new FileOutputStream(saveFile.toString()), null);
                }
            //}
            progressBar.setProgress(1);
        }else {
            progressBar.setProgress(0);
        }
    }

    @FXML
    // action on menu Preferences
    private void actionMenuPreferences(ActionEvent actionEvent) {
        try {
            Stage guiPrefStage = new Stage();
            //Scene for the menu Preferences
            FXMLLoader fxmlLoader=new FXMLLoader();
            Parent rootPreferences = fxmlLoader.load(getClass().getResource("/fxml/preferencesGui.fxml"));
            Scene preferences = new Scene(rootPreferences);
            guiPrefStage.setScene(preferences);
            guiPrefStage.setTitle("Preferences");
            guiPrefStage.initModality(Modality.APPLICATION_MODAL);
            //PreferencesController PreferencesController=fxmlLoader.getController();
            PreferencesController.initData(guiPrefStage);
            guiPrefStage.showAndWait();

        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    // action on menu About
    private void actionMenuAbout(ActionEvent actionEvent) {
        try {
            Stage guiAboutStage = new Stage();
            //Scene for the menu Preferences
            FXMLLoader fxmlLoader=new FXMLLoader();
            Parent rootAbout = fxmlLoader.load(getClass().getResource("/fxml/aboutGui.fxml"));
            Scene about = new Scene(rootAbout);
            guiAboutStage.setScene(about);
            guiAboutStage.setTitle("About");
            guiAboutStage.initModality(Modality.APPLICATION_MODAL);
            AboutController.initData(guiAboutStage);
            guiAboutStage.showAndWait();

        }catch (IOException e) {
            e.printStackTrace();
        }
    }


    //the action for button Open RDFS for profile
    //It opens RDF files for the profiles saved locally
    @FXML
    private void actionOpenRDFS(ActionEvent actionEvent) {
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        this.selectedFile = filechooser.showOpenMultipleDialog(null);

        if (this.selectedFile != null) {// the file is selected
            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            //System.out.println("Selected file: " + this.selectedFile);
            int iterSize = this.selectedFile.size();

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

            progressBar.setProgress(1);
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
        //OntModel modelOnt = ModelFactory.createOntologyModel( OntModelSpec.RDFS_MEM );
        try {
            RDFDataMgr.read(model, new FileInputStream(this.selectedFile.get(m).toString()), Lang.RDFXML);
            //RDFDataMgr.read(modelOnt, new FileInputStream(this.selectedFile.get(m).toString()), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        this.models.add(model);
        //modelsOnt.add(modelOnt);
    }

    //initializes the TreeView in the tab Generate Shapes - profile tree vew
    private void guiTreeProfileConstraintsInit(){
        TreeItem<String> rootMain;
        //define the Treeview
        rootMain = new TreeItem<>("Main root");
        rootMain.setExpanded(true);
        treeViewProfileConstraints.setRoot(rootMain); // sets the root to the gui object
        treeViewProfileConstraints.setShowRoot(false);
        for (Object modelsName : this.modelsNames) {
            TreeItem<String> profileItem = new TreeItem<>(((ArrayList) modelsName).get(0).toString()); // level for the Classes
            rootMain.getChildren().add(profileItem);
        }

        treeViewProfileConstraints.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }


    //sets package structure for the tree view in the tab Browse RDF
    private void setPackageStructure(int m) {

        //set package structure
        Model model = (Model) this.models.get(m);
        this.packages = new ArrayList<>();
        for (ResIterator i = model.listSubjects(); i.hasNext(); ) { //iterate on the items found in the rdf file
            Resource resItem = i.next();
            String[] rdfTypeInit = resItem.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
            String rdfType;
            if (rdfTypeInit.length == 0) {
                rdfType = rdfTypeInit[0];
            } else {
                rdfType = rdfTypeInit[1];
            }
            String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]; // the first part of the rdfs:label
            if (rdfType.equals("ClassCategory")) { // if it is a package
                this.packages.add(rdfsLabel);
            }
        }

        int multiPackage=0;
        if (this.packages.size() != 1) {
            for (int pak = 0; pak < this.packages.size(); pak++) {
                if (this.packages.get(pak).contains("Profile")){
                    multiPackage=pak;
                    break;
                }
            }
        }

        if (this.packages.size() == 1) {
            ArrayList<String> mpak1 = new ArrayList<>();
            mpak1.add(this.packages.get(0));
            mpak1.add(""); //reserved for the prefix of the profile
            mpak1.add(""); // reserved for the URI of the profile
            mpak1.add(""); // reserved for the baseURI of the profile
            mpak1.add(""); // reserved for owl:imports
            this.modelsNames.add(mpak1);
        } else {
            ArrayList<String> mpak = new ArrayList<>();
            mpak.add(this.packages.get(multiPackage));
            mpak.add(""); //reserved for the prefix of the profile
            mpak.add(""); // reserved for the URI of the profile
            mpak.add(""); // reserved for the baseURI of the profile
            mpak.add(""); // reserved for owl:imports
            this.modelsNames.add(mpak);
        }
    }

    @FXML
    //action on check box in constraints Create complete shapes model Tab
    private void cbApplyDefBaseURI(ActionEvent actionEvent) {
        if (cbApplyDefBaseURIDesignTab.isSelected()) {
            fshapesBaseURICreateCompleteSMTab.setText("");
            fshapesBaseURICreateCompleteSMTab.setDisable(true);
        }else{
            fshapesBaseURICreateCompleteSMTab.setDisable(false);
        }
    }

    @FXML
    //action on check box in constraints Create complete shapes model Tab
    private void cbApplyDefNamespace(ActionEvent actionEvent) {
        if (cbApplyDefNsDesignTab.isSelected()) {
            fPrefixCreateCompleteSMTab.setText("");
            fPrefixCreateCompleteSMTab.setDisable(true);
            fURICreateCompleteSMTab.setText("");
            fURICreateCompleteSMTab.setDisable(true);
        }else{
            fPrefixCreateCompleteSMTab.setDisable(false);
            fURICreateCompleteSMTab.setDisable(false);
        }
    }


    @FXML
    //action for button Apply in tab Generate Shapes
    private void actionBtnApply(ActionEvent actionEvent) {

        if (tabCreateCompleteSM.isSelected()) {
            if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size() == 1 &&
                    (!cbApplyDefNsDesignTab.isSelected() || !cbApplyDefBaseURIDesignTab.isSelected())) {

                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(0).getValue();
                for (Object modelsNames : this.modelsNames) {
                    if (selectedProfile.equals(((ArrayList) modelsNames).get(0))) {
                        int issueFound = 0;
                        if (!fPrefixCreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                            ((ArrayList) modelsNames).set(1, fPrefixCreateCompleteSMTab.getText());
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
                            ((ArrayList) modelsNames).set(2, fURICreateCompleteSMTab.getText());
                        } else if (fURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setContentText("Please add URI of the namespace.");
                            alert.setHeaderText(null);
                            alert.setTitle("Error - the value is empty string");
                            alert.showAndWait();
                            issueFound = 1;
                        }
                        if (!fshapesBaseURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefBaseURIDesignTab.isSelected()) {//TODO: check if it is resource
                            ((ArrayList) modelsNames).set(3, fshapesBaseURICreateCompleteSMTab.getText());
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
                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(0).getValue();
                for (int i=0; i<this.modelsNames.size();i++) {
                    if (selectedProfile.equals(((ArrayList) this.modelsNames.get(i)).get(0).toString())) {
                        if (fowlImportsCreateCompleteSMTab.getText().isEmpty()) {
                            ((ArrayList) this.modelsNames.get(i)).set(4, "");
                            break;
                        } else {
                            ((ArrayList) this.modelsNames.get(i)).set(4, fowlImportsCreateCompleteSMTab.getText());
                            break;
                        }
                    }
                }
            }

            //TODO: temporary option until a way to automate is found
            if (cbApplyDefNsDesignTab.isSelected()) {
                ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
                for (Object modelsNames : this.modelsNames) {
                    for (TreeItem<String> treeitem : treeitems) {
                        if (treeitem.getValue().equals(((ArrayList) modelsNames).get(0))) {
                            switch (treeitem.getValue()) {
                                case "CoreEquipmentProfile":
                                    ((ArrayList) modelsNames).set(1, "eq");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/constraints/3.0#");
                                    break;
                                case "OperationProfile":
                                    ((ArrayList) modelsNames).set(1, "op");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Operation-EU/constraints/3.0#");
                                    break;
                                case "ShortCircuitProfile":
                                    ((ArrayList) modelsNames).set(1, "sc");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/constraints/3.0#");
                                    break;
                                case "SteadyStateHypothesisProfile":
                                    ((ArrayList) modelsNames).set(1, "ssh");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/constraints/3.0#");
                                    break;
                                case "TopologyProfile":
                                    ((ArrayList) modelsNames).set(1, "tp");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Topology-EU/constraints/3.0#");
                                    break;
                                case "StateVariablesProfile":
                                    ((ArrayList) modelsNames).set(1, "sv");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/StateVariable-EU/constraints/3.0#");
                                    break;
                                case "DiagramLayoutProfile":
                                    ((ArrayList) modelsNames).set(1, "dl");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/constraints/3.0#");
                                    break;
                                case "GeographicalLocationProfile":
                                    ((ArrayList) modelsNames).set(1, "gl");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/constraints/3.0#");
                                    break;
                                case "DynamicsProfile":
                                    ((ArrayList) modelsNames).set(1, "dy");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/Dynamics/constraints/1.0#");
                                    break;
                                case "EquipmentBoundaryProfile":
                                    ((ArrayList) modelsNames).set(1, "eqbd");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/constraints/3.0#");
                                    break;
                                case "TopologyBoundaryProfile":
                                    ((ArrayList) modelsNames).set(1, "tpbd");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/constraints/3.0#");
                                    break;
                                case "FileHeaderProfile":
                                    ((ArrayList) modelsNames).set(1, "fh");
                                    ((ArrayList) modelsNames).set(2, "http://iec.ch/TC57/61970-552/ModelDescription/constraints/1.0#");
                                    break;
                            }
                        }
                    }
                }
              /*  String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(0).getValue();
                for (Object modelsNames : this.modelsNames) {
                    if (selectedProfile.equals(((ArrayList) modelsNames).get(0))) {
                        fPrefixCreateCompleteSMTab.setText(((ArrayList) modelsNames).get(1).toString());
                        fURICreateCompleteSMTab.setText(((ArrayList) modelsNames).get(2).toString());
                    }
                }*/
            }
            if (cbApplyDefBaseURIDesignTab.isSelected()) {
                ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
                for (Object modelsNames : this.modelsNames) {
                    for (TreeItem<String> treeitem : treeitems) {
                        if (treeitem.getValue().equals(((ArrayList) modelsNames).get(0))) {
                            switch (treeitem.getValue()) {
                                case "CoreEquipmentProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/constraints/3.0");
                                    break;
                                case "OperationProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Operation-EU/constraints/3.0");
                                    break;
                                case "ShortCircuitProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/constraints/3.0");
                                    break;
                                case "SteadyStateHypothesisProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/constraints/3.0");
                                    break;
                                case "TopologyProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Topology-EU/constraints/3.0");
                                    break;
                                case "StateVariablesProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/StateVariable-EU/constraints/3.0");
                                    break;
                                case "DiagramLayoutProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/constraints/3.0");
                                    break;
                                case "GeographicalLocationProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/constraints/3.0");
                                    break;
                                case "DynamicsProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/Dynamics/constraints/1.0");
                                    break;
                                case "EquipmentBoundaryProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/constraints/3.0");
                                    break;
                                case "TopologyBoundaryProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/constraints/3.0");
                                    break;
                                case "FileHeaderProfile":
                                    ((ArrayList) modelsNames).set(3, "http://iec.ch/TC57/61970-552/ModelDescription/constraints/1.0");
                                    break;
                            }
                        }
                    }
                }
               /* String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(0).getValue();
                for (Object modelsNames : this.modelsNames) {
                    if (selectedProfile.equals(((ArrayList) modelsNames).get(0))) {
                        fshapesBaseURICreateCompleteSMTab.setText(((ArrayList) modelsNames).get(3).toString());
                    }
                }*/
            }
        }
    }

    @FXML
    //action for the tree in the Generate Shapes Tab
    private void actionTreeProfileConstraintsTab(MouseEvent mouseEvent) {

        String selectedProfile=treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(0).getValue();
        for (Object modelsNames : this.modelsNames) {
            if (selectedProfile.equals(((ArrayList) modelsNames).get(0))) {
                fPrefixCreateCompleteSMTab.setText(((ArrayList) modelsNames).get(1).toString());
                fURICreateCompleteSMTab.setText(((ArrayList) modelsNames).get(2).toString());
                fshapesBaseURICreateCompleteSMTab.setText(((ArrayList) modelsNames).get(3).toString());
            }
        }

       btnConstructShacl.setDisable(false);
    }

    @FXML
    //action for button Create in the tab Constraints detail/Create complete shapes model
    private void actionBtnConstructShacl(ActionEvent actionEvent) throws IOException {

        String cbvalue;
        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem()==null) {
            cbvalue="";
        }else{
            cbvalue=fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString();
        }

        if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size()!=0 && cbvalue.equals("RDFS (augmented) by CimSyntaxGen")) {
            //depending on the value of the choice box "Save datatype map"
            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("No map; No save")) {
                shaclNodataMap  = 1;
            }else{
                shaclNodataMap = 0;
            }
            if (shapeModels==null) {
                shapeModels = new ArrayList<>();
                //shapeModelsNames = new ArrayList<>();
                //shapeModelsNames = this.modelsNames; // this is to ensure that there is shapeModelsNames when tree is clicked
            }
            if (shapeModelsNames==null){
                shapeModelsNames = new ArrayList<>();
            }
            shapeDatas = new ArrayList<>();
            //dataTypeMapFromShapes = new HashMap<>();
            Map<String, RDFDatatype> dataTypeMapFromShapesComplete = new HashMap<>(); // this is the complete map for the export in one .properties
            ArrayList<Integer> modelNumber = new ArrayList<>();
            List<String> profileList= new ArrayList<>();
            List<String> prefixes= new ArrayList<>();
            List<String> namespaces= new ArrayList<>();
            List<String> baseURIs= new ArrayList<>();
            List<String> owlImports= new ArrayList<>();
            for (int sel = 0; sel < treeViewProfileConstraints.getSelectionModel().getSelectedItems().size(); sel++) {
                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(sel).getValue();
                for (int i = 0; i < this.modelsNames.size(); i++) {
                    if (((ArrayList) this.modelsNames.get(i)).get(0).equals(selectedProfile)) {
                        modelNumber.add(i);
                        profileList.add(selectedProfile);
                        prefixes.add(((ArrayList) this.modelsNames.get(i)).get(1).toString());
                        namespaces.add(((ArrayList) this.modelsNames.get(i)).get(2).toString());
                        baseURIs.add(((ArrayList) this.modelsNames.get(i)).get(3).toString());
                        owlImports.add(((ArrayList) this.modelsNames.get(i)).get(4).toString());
                    }
                }
            }
            // ask for confirmation before proceeding
            String title="Confirmation needed";
            String header="The shapes will be generated with the following basic conditions. Please review and confirm in order to proceed.";
            String contextText="Could you confirm the information below?";
            String labelText="Details:";
            //TODO: make this nicer
            //set the content of the details window
            String detailedText="The following profiles are selected: \n";
            detailedText=detailedText+ profileList +"\n";
            detailedText=detailedText+"The namespaces for the selected profiles are: \n";
            detailedText=detailedText+ prefixes +"\n";
            detailedText=detailedText+ namespaces +"\n";
            detailedText=detailedText+"The base URIs for the selected profiles are: \n";
            detailedText=detailedText+ baseURIs +"\n";
            detailedText=detailedText+"The owl:imports for the selected profiles are: \n";
            detailedText=detailedText+ owlImports +"\n";
            Alert alert=GUIhelper.expandableAlert(title, header, contextText, labelText, detailedText);
            alert.getDialogPane().setExpanded(true);
            //Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            //alert.setTitle("Confirmation needed");
            //alert.setHeaderText("The shapes will be generated with the following basic conditions. Please review and confirm in order to proceed.");
            //alert.setContentText(profileList+"Are you ok with this?");

            Optional<ButtonType> result = alert.showAndWait();
            if (result.get() == ButtonType.OK){
                // ... user chose OK
            } else {
                return;
            }

            progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

            for (int sel = 0; sel < modelNumber.size(); sel++) {
                int m=modelNumber.get(sel);

                //here the preparation starts
                dataTypeMapFromShapes = new HashMap<>();

                Model model = (Model) this.models.get(m);
                String rdfNs = MainController.prefs.get("cimsNamespace","");
                String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
                ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, rdfNs, concreteNs);

                shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles
                //here the preparation ends

                String nsPrefixprofile =((ArrayList) this.modelsNames.get(m)).get(1).toString(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile
                String nsURIprofile = ((ArrayList) this.modelsNames.get(m)).get(2).toString(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile

         /*           String baseURI=null;
                if (fshapesBaseURICreateCompleteSMTab.getText().isEmpty()) {
                    //open a question/confirmation dialog to ask about the baseURI as it is empty
                    Alert alert1 = new Alert(Alert.AlertType.CONFIRMATION);
                    alert1.setContentText("The baseURI for shapes is not declared. You need to either define the baseURI or use the same URI as the namespase URI.");
                    alert1.setHeaderText(null);
                    alert1.setTitle("Define shapes baseURI");

                    ButtonType btnSame = new ButtonType("Same URI as the namespace");
                    ButtonType btnDefine = new ButtonType("Define", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert1.getButtonTypes().setAll(btnSame, btnDefine);
                    Optional<ButtonType> result1 = alert1.showAndWait();
                    if (result1.get() == btnSame) {
                        baseURI = nsURIprofile;
                    }
                }else{*/
                    String baseURI=((ArrayList) this.modelsNames.get(m)).get(3).toString();
                    //}
                    String owlImport=((ArrayList) this.modelsNames.get(m)).get(4).toString();
                    //generate the shape model
                    Model shapeModel=ShaclTools.createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData);

                    //add the owl:imports
                    shapeModel= ShaclTools.addOWLimports(shapeModel,baseURI,owlImport);

                    shapeModels.add(shapeModel);
                    shapeModelsNames.add(this.modelsNames.get(m));
                    //System.out.println(dataTypeMapFromShapes);
                    //RDFDataMgr.write(System.out, shapeModel, RDFFormat.TURTLE);

                    //open the ChoiceDialog for the save file and save the file in different formats
                    String titleSaveAs = "Save as for shape model: "+ ((ArrayList) this.modelsNames.get(m)).get(0).toString();
                    File savedFile=ShaclTools.saveShapesFile(shapeModel, baseURI,0,titleSaveAs);

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
                        String fileName=FilenameUtils.getBaseName(String.valueOf(savedFile));
                        properties.store(new FileOutputStream(savedFile.getParent()+"\\"+fileName+".properties"), null);

                    }
                    //add the shapes in the tree view


                    //exporting the model to ttl ready function
                /*
                    File file = FileManager.createFile(filePathWithExtension);
                    OutputStream out = new FileOutputStream(file);
                    if (gzip) {
                        out = new GZIPOutputStream(out);
                    }
                    try {
                        RDFDataMgr.write(out, model, format);
                    }
                    finally {
                        out.close();
                    }

                 */
                }
                if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("All profiles in one map")) {
                    FileChooser filechooser = new FileChooser();
                    filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Datatypes mapping files", "*.properties"));
                    filechooser.setInitialFileName("CompleteDatatypeMap");
                    File saveFile = filechooser.showSaveDialog(null);

                    if (saveFile != null) {
                        Properties properties = new Properties();

                        for (Object key : dataTypeMapFromShapesComplete.keySet()) {
                            properties.put(key, dataTypeMapFromShapesComplete.get(key).toString());
                        }
                        properties.store(new FileOutputStream(saveFile.toString()), null);
                    }
                }
                //guiTreeDesignShapesInit();
                //tabPaneLeft.getSelectionModel().select(1);
            }else {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Please select a profile for which you would like to generate Shapes and the RDFS format.");
                alert.setHeaderText(null);
                alert.setTitle("Error - no profile selected");
                alert.showAndWait();
            }
        }

    public static Map getDataTypeMapFromShapes(){
        return dataTypeMapFromShapes;
    }

    public static void setDataTypeMapFromShapes(Map dataTypeMapFromShapes){
        MainController.dataTypeMapFromShapes = dataTypeMapFromShapes;
    }

    public static int getShaclNodataMap(){
        return shaclNodataMap;
    }

    @FXML
    //action menu "Excel to SHACL"
    private void actionBtnRunExcelShape(ActionEvent actionEvent) throws IOException {

        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        if (fPathRdffileForExcel.getText().isBlank() || fPathXLSfileForShape.getText().isBlank() || fcbRDFSformatForExcel.getSelectionModel().getSelectedItem()==null
        || fbaseURIShapeExcel.getText().isBlank() || fPrefixExcelShape.getText().isBlank() || fNSexcelShape.getText().isBlank()){
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please complete all fields.");
            alert.setHeaderText(null);
            alert.setTitle("Error - not all fields are filled in");
            alert.showAndWait();
            progressBar.setProgress(0);
            return;
        }


        //open the rdfs for the profile
        /*FileChooser filechooserRDF = new FileChooser();
        filechooserRDF.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF files", "*.rdf"));
        File fileRDF = filechooserRDF.showOpenDialog(null);*/

        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        try {
            RDFDataMgr.read(model, new FileInputStream(MainController.rdfModelExcelShacl), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        shaclNodataMap  = 1; // as no mapping is to be used for this task
        String cimsNs = MainController.prefs.get("cimsNamespace","");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);


        //select the xlsx file and read it
      /*  FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
        File file = filechooser.showOpenDialog(null);*/
        ArrayList<Object> dataExcel=null;

        //if (file != null) {// the file is selected

            dataExcel = ExcelTools.importXLSX(String.valueOf(MainController.xlsFileExcelShacl));
            //System.out.println(dataExcel);
       // }
        //TODO: this can be made with many additional options e.g. what to be added and in which model to be added. Now it is primitive to solve a simple task
        //create a new Shapes model
        Model shapeModel = JenaUtil.createDefaultModel();
        //add the namespaces
        shapeModel.setNsPrefix("sh", SH.NS);
        shapeModel.setNsPrefix("dash", DASH.NS);
        shapeModel.setNsPrefix(MainController.prefs.get("prefixEU",""), MainController.prefs.get("uriEU",""));
        shapeModel.setNsPrefix("cims", MainController.prefs.get("cimsNamespace",""));
        shapeModel.setNsPrefix("rdf", RDF.uri);
        shapeModel.setNsPrefix("owl", OWL.NS);
        shapeModel.setNsPrefix("cim", MainController.prefs.get("CIMnamespace",""));
        shapeModel.setNsPrefix("xsd", XSD.NS);
        shapeModel.setNsPrefix("rdfs", RDFS.uri);
        if (!MainController.prefs.get("prefixOther","").equals("") && !MainController.prefs.get("uriOther","").equals("")){
            shapeModel.setNsPrefix(MainController.prefs.get("prefixOther",""), MainController.prefs.get("uriOther",""));
        }


        String baseURI = fbaseURIShapeExcel.getText();
        String nsURIprofilePrefix=fPrefixExcelShape.getText();
        String nsURIprofile=fNSexcelShape.getText();
        shapeModel.setNsPrefix(nsURIprofilePrefix, nsURIprofile);



        //add the shapes in the model - i.e. check what is in the dataExcel and generate the Shapes out of it
        if (dataExcel!=null) {
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
                String attributeName= ((LinkedList) dataExcel.get(row)).get(8).toString(); //here map to "path" column

                for (int classRDF=0; classRDF< ((ArrayList) shapeData.get(0)).size(); classRDF++) { // this loops the concrete classes in the RDFS
                    for (int attr=1; attr < ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).size(); attr++){ //this loops the attributes of a concrete class including the inherited
                        if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).get(attr)).get(0).equals("Attribute")){ //this is when the property is an attribute
                            String className= ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).get(0)).get(2).toString(); // this is the name of the class
                            String attributeNameRDFS= ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).get(attr)).get(4).toString(); // this is the localName of the attribute


                            if (attributeName.equals(attributeNameRDFS)){

                                Resource propertyShapeResource = ResourceFactory.createResource(nsURIprofile + ((LinkedList) dataExcel.get(row)).get(7).toString());
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
                                    r.addProperty(SH.message, ((LinkedList) dataExcel.get(row)).get(4).toString());

                                    // add the path
                                    RDFNode o5 = shapeModel.createResource(((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).get(attr)).get(1).toString());
                                    r.addProperty(SH.path, o5);

                                    //add the name
                                    r.addProperty(SH.name, ((LinkedList) dataExcel.get(row)).get(2).toString());

                                    //add description
                                    r.addProperty(SH.description, ((LinkedList) dataExcel.get(row)).get(3).toString());

                                    //add severity
                                    RDFNode o8 = shapeModel.createResource(SH.NS + ((LinkedList) dataExcel.get(row)).get(5).toString());
                                    r.addProperty(SH.severity, o8);

                                    //add columns Constraint 1/Value 1

                                    if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("minExclusive")){
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10),XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minExclusive, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("maxExclusive")){
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10),XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxExclusive, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("maxInclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10),XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxInclusive, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("minInclusive")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10),XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minInclusive, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("maxLength")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.maxLength, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("minLength")) {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.minLength, constr1);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("equals")) {
                                        String prefix = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[0];
                                        String attribute = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix)+attribute);
                                        r.addProperty(SH.equals, eq);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("disjoint")) {
                                        String prefix = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[0];
                                        String attribute = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix)+attribute);
                                        r.addProperty(SH.disjoint, eq);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("lessThan")) {
                                        String prefix = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[0];
                                        String attribute = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix)+attribute);
                                        r.addProperty(SH.lessThan, eq);
                                    }else if (((LinkedList) dataExcel.get(row)).get(9).toString().equals("lessThanOrEquals")) {
                                        String prefix = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[0];
                                        String attribute = ((LinkedList) dataExcel.get(row)).get(10).toString().split(":",2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix)+attribute);
                                        r.addProperty(SH.lessThanOrEquals, eq);
                                    }

                                    if (((LinkedList) dataExcel.get(row)).size()==13) {
                                        //add columns Constraint 2/Value 2
                                        if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("minExclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minExclusive, constr2);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("maxExclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxExclusive, constr2);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("maxInclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxInclusive, constr2);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("minInclusive")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minInclusive, constr2);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("maxLength")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.maxLength, constr2);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("minLength")) {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.minLength, constr2);

                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("equals")) {
                                            String prefix = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.equals, eq);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("disjoint")) {
                                            String prefix = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.disjoint, eq);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("lessThan")) {
                                            String prefix = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThan, eq);
                                        } else if (((LinkedList) dataExcel.get(row)).get(11).toString().equals("lessThanOrEquals")) {
                                            String prefix = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThanOrEquals, eq);
                                        }
                                    }
                                }

                                //Adding of the NodeShape if it is not existing
                                Resource nodeShapeResource = ResourceFactory.createResource(nsURIprofile + className);
                                //System.out.println(nodeShapeResource);
                                if (!shapeModel.containsResource(nodeShapeResource)) { // creates only if it is missing
                                    //RDFList orRDFlist = shapeModel.createList(classNames.iterator());
                                    //nodeShapeResourceOr.addProperty(SH.or, orRDFlist);
                                    String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(classRDF)).get(0)).get(0).toString();
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
        File savedFile=ShaclTools.saveShapesFile(shapeModel, baseURI,0,titleSaveAs);

      /*  //save the model as ttl
        FileChooser filechooserS = new FileChooser();
        filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Shapes files", "*.ttl"));
        //filechooserS.setInitialFileName(title.split(": ",2)[1]);
        filechooserS.setTitle("Save Shapes...");
        File saveFile = filechooserS.showSaveDialog(null);
        RDFFormat rdfFormat = RDFFormat.TURTLE;

        if (saveFile != null) {
            OutputStream out = new FileOutputStream(saveFile);
            try {
                shapeModel.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);//String.valueOf(rdfFormat)
            } finally {
                out.close();
            }
        }*/
        progressBar.setProgress(1);

    }

}

