package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.converters.SHACLFromRDF;
import eu.griddigit.cimpal.core.models.RDFtoSHACLOptions;
import eu.griddigit.cimpal.core.models.RdfsModelDefinition;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.PreferencesController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class RDFStoSHACLController implements Initializable {
    private MainController mainController;

    @FXML
    private TreeView<String> treeViewProfileConstraints;
    @FXML
    private ChoiceBox<String> fselectDatatypeMapDefineConstraints;
    @FXML
    private ChoiceBox<String> cbProfilesVersionCreateCompleteSMTab;
    @FXML
    private CheckBox cbApplyDefNsDesignTab;
    @FXML
    private TextField fPrefixCreateCompleteSMTab;
    @FXML
    private TextField fURICreateCompleteSMTab;
    @FXML
    private CheckBox cbApplyDefBaseURIDesignTab;
    @FXML
    private TextField fshapesBaseURICreateCompleteSMTab;
    @FXML
    private TextField fowlImportsCreateCompleteSMTab;
    @FXML
    private ChoiceBox<String> fcbRDFSformatShapes;
    @FXML
    private CheckBox cbRDFSSHACLabstract;
    @FXML
    private CheckBox cbRDFSSHACLinheritTree;
    @FXML
    private CheckBox cbRDFSSHACLoption1;
    @FXML
    private CheckBox cbRDFSSHACLoptionTypeWithOne;
    @FXML
    private CheckBox cbRDFSSHACLoptionDescr;
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
    private CheckBox cbRDFSSHACLoptionProperty;
    @FXML
    private CheckBox cbRDFSSHACLoptionCount;
    @FXML
    private ChoiceBox<String> shaclNSCommonType;
    @FXML
    private TextField fPrefixSHACLCommon;
    @FXML
    private TextField fURISHACLcommon;
    @FXML
    private CheckBox cbRDFSSHACLuri;
    @FXML
    private CheckBox cbRDFSSHACLncProp;
    @FXML
    private CheckBox cbRDFSSHACLvalidate;
    @FXML
    private CheckBox cbRDFSSHACLdatatypesplit;
    @FXML
    private Button btnConstructShacl;
    @FXML
    private Button btnApply;

    private static Preferences prefs;
    private ArrayList<String> packages;
    private List<File> selectedFile;
    private static List<RdfsModelDefinition> RDFSmodelsNames;
    private static ArrayList<Object> shapeModelsNames;
    private static ArrayList<Object> shapeModels;
    private static ArrayList<Object> shapeDatas;
    private static ArrayList<Model> RDFSmodels;
    private static String rdfFormatInput;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
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

        fselectDatatypeMapDefineConstraints.getItems().addAll(
                "No map; No save", // the map will not be generated and not saved
                "All profiles in one map", // the save choice will be for all profile in one file.
                "Per profile" //	the save option will be asked after every shacl file i.e. per profile.
        );

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
        fcbRDFSformatShapes.getItems().addAll(
                "RDFS (augmented, v2020) by CimSyntaxGen",
                "RDFS (augmented, v2019) by CimSyntaxGen",
                "Merged OWL CIMTool (NOT READY)"
        );
        fcbRDFSformatShapes.getSelectionModel().selectFirst();

        shaclNSCommonType.getItems().addAll(
                "Profile namespace",
                "Custom namespace"
        );
        shaclNSCommonType.getSelectionModel().selectLast();
        fPrefixSHACLCommon.setText("cc");
        fURISHACLcommon.setText("https://common-constraints.eu/");

    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    private void setProgressBar(double progress) {
        if (mainController != null) {
            mainController.setProgressBarValue(progress);
        }
    }

    private void resetProgressBar() {
        if (mainController != null) {
            mainController.resetProgressBar();
        }
    }

    @FXML
    //action for the tree in the Generate Shapes Tab
    private void actionTreeProfileConstraintsTab(MouseEvent mouseEvent) {

        String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().getFirst().getValue();
        //for (Object modelsNames : this.modelsNames) {
        for (RdfsModelDefinition modelsNames : RDFSmodelsNames) {
            if (selectedProfile.equals(modelsNames.getModelName())) {
                fPrefixCreateCompleteSMTab.setText(modelsNames.getNsPrefix());
                fURICreateCompleteSMTab.setText(modelsNames.getNsUri());
                fshapesBaseURICreateCompleteSMTab.setText(modelsNames.getBaseUri());
            }
        }

        btnConstructShacl.setDisable(false);
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
    //Action for button Create in the tab RDFS to SHACL (new implementation)
    private void actionBtnRDFStoShacl(ActionEvent actionEvent) throws IOException {

        Map<String, Boolean> rdfsToShaclGuiMapBool = new HashMap<>();
        Map<String, String> rdfsToShaclGuiMapStr = new HashMap<>();
        if (cbRDFSSHACLoptionDescr.isSelected()) {
            rdfsToShaclGuiMapBool.put("excludeMRID", true);
        } else {
            rdfsToShaclGuiMapBool.put("excludeMRID", false);
        }

        if (cbRDFSSHACLoptionProperty.isSelected()) {

            rdfsToShaclGuiMapBool.put("Closedshapes", true);
        } else {
            rdfsToShaclGuiMapBool.put("Closedshapes", false);
        }
        if (cbRDFSSHACLdatatypesplit.isSelected()) {
            rdfsToShaclGuiMapBool.put("SplitDatatypes", true);
        } else {
            rdfsToShaclGuiMapBool.put("SplitDatatypes", false);
        }
        //excludeMRID = cbRDFSSHACLoptionDescr.isSelected();

        //String cbvalue;
        //Model shaclRefModel = null;
        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem() == null) {
            //cbvalue = "";
            rdfsToShaclGuiMapStr.put("cbvalue", "");
        } else {
            //cbvalue = fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString();
            rdfsToShaclGuiMapStr.put("cbvalue", fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString());
        }
        if (cbRDFSSHACLoption1.isSelected()) {
            //associationValueTypeOption = 1;
            rdfsToShaclGuiMapBool.put("associationValueTypeOption", true);
        } else {
            //associationValueTypeOption = 0;
            rdfsToShaclGuiMapBool.put("associationValueTypeOption", false);
        }
        if (cbRDFSSHACLoptionTypeWithOne.isSelected()) {
            //associationValueTypeOptionSingle = 1;
            rdfsToShaclGuiMapBool.put("associationValueTypeOptionSingle", true);
        } else {
            //associationValueTypeOptionSingle = 0;
            rdfsToShaclGuiMapBool.put("associationValueTypeOptionSingle", false);
        }
        if (cbRDFSSHACLabstract.isSelected()) {
            //shapesOnAbstractOption = 1;
            rdfsToShaclGuiMapBool.put("shapesOnAbstractOption", true);
        } else {
            //shapesOnAbstractOption = 0;
            rdfsToShaclGuiMapBool.put("shapesOnAbstractOption", false);
        }
        if (cbRDFSSHACLinheritTree.isSelected()) {
            //exportInheritTree = 1;
            rdfsToShaclGuiMapBool.put("exportInheritTree", true);
        } else {
            //exportInheritTree = 0;
            rdfsToShaclGuiMapBool.put("exportInheritTree", false);
        }

        //shaclURIdatatypeAsResource = false;
        //shaclSkipNcPropertyReference = false;
        if (cbRDFSSHACLuri.isSelected()) {
            //shaclURIdatatypeAsResource = true;
            rdfsToShaclGuiMapBool.put("shaclURIdatatypeAsResource", true);
        } else {
            rdfsToShaclGuiMapBool.put("shaclURIdatatypeAsResource", false);
        }

        if (cbRDFSSHACLncProp.isSelected()) {
            //haclSkipNcPropertyReference = true;
            rdfsToShaclGuiMapBool.put("shaclSkipNcPropertyReference", true);
        } else {
            rdfsToShaclGuiMapBool.put("shaclSkipNcPropertyReference", false);
        }


        if (!treeViewProfileConstraints.getSelectionModel().getSelectedItems().isEmpty()) {
            //depending on the value of the choice box "Save datatype map"
            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("No map; No save")) {
                //shaclNodataMap = 1;
                rdfsToShaclGuiMapBool.put("shaclNodataMap", true);
            } else {
                //shaclNodataMap = 0;
                rdfsToShaclGuiMapBool.put("shaclNodataMap", false);
            }
            if (shapeModels == null) {
                shapeModels = new ArrayList<>();
            }
            if (shapeModelsNames == null) {
                shapeModelsNames = new ArrayList<>();
            }
            shapeDatas = new ArrayList<>();
            //Map<String, RDFDatatype> dataTypeMapFromShapesComplete = new HashMap<>(); // this is the complete map for the export in one .properties
            ArrayList<Integer> modelNumber = new ArrayList<>();
            List<String> profileList = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            List<String> namespaces = new ArrayList<>();
            List<String> baseURIs = new ArrayList<>();
            List<String> owlImports = new ArrayList<>();
            for (int sel = 0; sel < treeViewProfileConstraints.getSelectionModel().getSelectedItems().size(); sel++) {
                String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().get(sel).getValue();
                for (int i = 0; i < RDFSmodelsNames.size(); i++) {
                    RdfsModelDefinition modelDef = RDFSmodelsNames.get(i);
                    if (modelDef.getModelName().equals(selectedProfile)) {
                        modelNumber.add(i);
                        profileList.add(selectedProfile);
                        prefixes.add(modelDef.getNsPrefix());
                        namespaces.add(modelDef.getNsUri());
                        baseURIs.add(modelDef.getBaseUri());
                        owlImports.add(modelDef.getOwlImport());
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

            if (cbRDFSSHACLoptionBaseprofiles.isSelected()) { // load base profiles if the checkbox is selected
                //baseprofilesshaclglag = 1;
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag", true);
            } else {
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag", false);
            }

            if (cbRDFSSHACLoptionBaseprofilesIgnoreNS.isSelected()) {
                //baseprofilesshaclignorens = 1;
                rdfsToShaclGuiMapBool.put("baseprofilesshaclignorens", true);
            } else {
                rdfsToShaclGuiMapBool.put("baseprofilesshaclignorens", false);
            }

            if (cbRDFSSHACLoptionBaseprofiles2nd.isSelected()) { // load base profiles if the checkbox is selected
                //baseprofilesshaclglag2nd = 1;
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag2nd", true);
            } else {
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag2nd", false);
            }

            if (cbRDFSSHACLoptionBaseprofiles3rd.isSelected()) { // load base profiles if the checkbox is selected
                //baseprofilesshaclglag3rd = 1;
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag3rd", true);
            } else {
                rdfsToShaclGuiMapBool.put("baseprofilesshaclglag3rd", false);
            }


            //shaclflaginverse = 0;
            if (cbRDFSSHACLoptionInverse.isSelected()) {
                //shaclflaginverse = 1;
                rdfsToShaclGuiMapBool.put("shaclflaginverse", true);
            } else {
                rdfsToShaclGuiMapBool.put("shaclflaginverse", false);
            }

            //shaclflagCount = 0;
            if (cbRDFSSHACLoptionCount.isSelected()) {
                //shaclflagCount = 1;
                rdfsToShaclGuiMapBool.put("shaclflagCount", true);
                //shaclflagCountDefaultURI = 1;
                rdfsToShaclGuiMapBool.put("shaclflagCountDefaultURI", true);
                //shaclCommonPref = "";
                rdfsToShaclGuiMapStr.put("shaclCommonPref", "");
                //shaclCommonURI = "";
                rdfsToShaclGuiMapStr.put("shaclCommonURI", "");
                if (shaclNSCommonType.getSelectionModel().getSelectedItem().toString().equals("Custom namespace")) {
                    //shaclflagCountDefaultURI = 0;
                    rdfsToShaclGuiMapBool.put("shaclflagCountDefaultURI", false);
                    //shaclCommonPref = fPrefixSHACLCommon.getText();
                    rdfsToShaclGuiMapStr.put("shaclCommonPref", fPrefixSHACLCommon.getText());
                    //shaclCommonURI = fURISHACLcommon.getText();
                    rdfsToShaclGuiMapStr.put("shaclCommonURI", fURISHACLcommon.getText());
                }
            } else {
                rdfsToShaclGuiMapBool.put("shaclflagCount", false);
            }

            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("All profiles in one map")) {
                rdfsToShaclGuiMapBool.put("AllProfilesOneMap", true);
            } else {
                rdfsToShaclGuiMapBool.put("AllProfilesOneMap", false);
            }

            if (fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("Per profile")) {
                rdfsToShaclGuiMapBool.put("PerProfile", true);
            } else {
                rdfsToShaclGuiMapBool.put("PerProfile", false);
            }

            if (cbRDFSSHACLvalidate.isSelected()) { //do validation
                rdfsToShaclGuiMapBool.put("RDFSSHACLvalidate", true);
            } else {
                rdfsToShaclGuiMapBool.put("RDFSSHACLvalidate", false);
            }

            List<File> baseModelFiles1 = null;
            List<File> baseModelFiles2 = null;
            List<File> baseModelFiles3 = null;

            if (cbRDFSSHACLoptionBaseprofiles.isSelected()) {
                baseModelFiles1 = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select 1st Base profiles");
            }
            if (cbRDFSSHACLoptionBaseprofiles2nd.isSelected()) {
                baseModelFiles2 = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select 2nd Base profiles");
            }
            if (cbRDFSSHACLoptionBaseprofiles3rd.isSelected()) {
                baseModelFiles3 = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file", List.of("*.rdf"), "Select 3rd Base profiles");
            }


            String rdfFormatcbInput = fcbRDFSformatShapes.getSelectionModel().getSelectedItem().toString();
            RDFtoSHACLOptions.RdfsFormatShapes rdfsFormatShapes = RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2020;
            if (rdfFormatcbInput.equals("RDFS (augmented, v2019) by CimSyntaxGen")) {
                rdfsFormatShapes = RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2019;
            } else if (rdfFormatcbInput.equals("CIMTool-merged-owl")) {
                rdfsFormatShapes = RDFtoSHACLOptions.RdfsFormatShapes.CIMTOOL_MERGED_OWL;
            }

            setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

            RDFtoSHACLOptions.Builder builder = RDFtoSHACLOptions.builder()
                    .excludeMRID(cbRDFSSHACLoptionDescr.isSelected())
                    .closedShapes(cbRDFSSHACLoptionProperty.isSelected())
                    .splitDatatypes(cbRDFSSHACLdatatypesplit.isSelected())
                    .rdfsFormatShapes(rdfsFormatShapes)
                    .associationValueTypeOption(cbRDFSSHACLoption1.isSelected())
                    .associationValueTypeOptionSingle(cbRDFSSHACLoptionTypeWithOne.isSelected())
                    .shapesOnAbstractOption(cbRDFSSHACLabstract.isSelected())
                    .exportInheritTree(cbRDFSSHACLinheritTree.isSelected())
                    .shaclURIDatatypeAsResource(cbRDFSSHACLuri.isSelected())
                    .shaclSkipNcPropertyReference(cbRDFSSHACLncProp.isSelected())
                    .baseprofilesshaclglag(cbRDFSSHACLoptionBaseprofiles.isSelected())
                    .baseprofilesshaclignorens(cbRDFSSHACLoptionBaseprofilesIgnoreNS.isSelected())
                    .baseprofilesshaclglag2nd(cbRDFSSHACLoptionBaseprofiles2nd.isSelected())
                    .baseprofilesshaclglag3rd(cbRDFSSHACLoptionBaseprofiles3rd.isSelected())
                    .shaclFlagInverse(cbRDFSSHACLoptionInverse.isSelected())
                    .baseModelFiles1(baseModelFiles1)
                    .baseModelFiles2(baseModelFiles2)
                    .baseModelFiles3(baseModelFiles3)
                    .rdfsModelDefinitions(RDFSmodelsNames)
                    .rdfsModels(RDFSmodels)
                    .shaclOutputFormat(RDFtoSHACLOptions.SerializationFormat.TURTLE)
                    .iOprefix(prefs.get("IOprefix", ""))
                    .iOuri(prefs.get("IOuri", ""))
                    .cimsNamespace(prefs.get("cimsNamespace", ""))
                    .shaclFlagCountDefaultURI(rdfsToShaclGuiMapBool.get("shaclflagCountDefaultURI"))
                    .shaclFlagCount(rdfsToShaclGuiMapBool.get("shaclflagCount"))
                    .shaclCommonURI(rdfsToShaclGuiMapStr.get("shaclCommonURI"))
                    .shaclCommonPref(rdfsToShaclGuiMapStr.get("shaclCommonPref"));

            RDFtoSHACLOptions options = builder.build();

            SHACLFromRDF rdftoSHACL = new SHACLFromRDF(options);

            setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

            rdftoSHACL.convert();
            if (cbRDFSSHACLvalidate.isSelected()) {
                rdftoSHACL.validateShapeModels();
            }

            // save the generated shapes
            Path outputFolderPath = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom("Select output folder").toPath();
            rdftoSHACL.saveShapeModel(outputFolderPath);

            // save datatype map if requested
            if (cbRDFSSHACLdatatypesplit.isSelected()) {
                rdftoSHACL.saveShapeModelDT(outputFolderPath);
            }

            if (cbRDFSSHACLinheritTree.isSelected()) {
                rdftoSHACL.saveInheritanceModel(outputFolderPath);
            }

            // todo datatype map storing is not implemented
//            if(fselectDatatypeMapDefineConstraints.getSelectionModel().getSelectedItem().equals("All profiles in one map")) {
//                rdftoSHACL.saveProfilesMap(new File(outputFolderPath.toFile(), "datatype.properties"));
//            }


            // prepareShapesModelFromRDFS(rdfsToShaclGuiMapBool,rdfsToShaclGuiMapStr);


            setProgressBar(1);
            System.out.print("Generation of SHACL shapes is completed.\n");

        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please select a profile for which you would like to generate Shapes and the RDFS format.");
            alert.setHeaderText(null);
            alert.setTitle("Error - no profile selected");
            alert.showAndWait();
        }
    }

    @FXML
    //action for button Apply in tab Generate Shapes
    private void actionBtnApply() {

        if (treeViewProfileConstraints.getSelectionModel().getSelectedItems().size() == 1 &&
                (!cbApplyDefNsDesignTab.isSelected() || !cbApplyDefBaseURIDesignTab.isSelected())) {

            String selectedProfile = treeViewProfileConstraints.getSelectionModel().getSelectedItems().getFirst().getValue();
            for (RdfsModelDefinition modelsNames : RDFSmodelsNames) {
                if (selectedProfile.equals(modelsNames.getModelName())) {
                    int issueFound = 0;
                    if (!fPrefixCreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                        modelsNames.setNsPrefix(fPrefixCreateCompleteSMTab.getText());
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
                        modelsNames.setNsUri(fURICreateCompleteSMTab.getText());
                    } else if (fURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefNsDesignTab.isSelected()) {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setContentText("Please add URI of the namespace.");
                        alert.setHeaderText(null);
                        alert.setTitle("Error - the value is empty string");
                        alert.showAndWait();
                        issueFound = 1;
                    }
                    if (!fshapesBaseURICreateCompleteSMTab.getText().isEmpty() && !cbApplyDefBaseURIDesignTab.isSelected()) {//TODO: check if it is resource
                        modelsNames.setBaseUri(fshapesBaseURICreateCompleteSMTab.getText());
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
            //for (Object modelsName : this.modelsNames) {
            for (RdfsModelDefinition modelsName : RDFSmodelsNames) {
                if (selectedProfile.equals(modelsName.getModelName())) {
                    if (fowlImportsCreateCompleteSMTab.getText().isEmpty()) {
                        modelsName.setOwlImport("");
                        break;
                    } else {
                        modelsName.setOwlImport(fowlImportsCreateCompleteSMTab.getText());
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
        } else if (cbProfilesVersionCreateCompleteSMTab.getSelectionModel().getSelectedItem().toString().equals("LTDS profiles, release 1.0")) {
            profileVersion = 7; // LTDS profiles, release 1.0
        }
        if (cbApplyDefNsDesignTab.isSelected()) {
            ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
            //for (Object modelsNames : this.modelsNames) {
            for (RdfsModelDefinition modelsNames : RDFSmodelsNames) {
                for (TreeItem<String> treeitem : treeitems) {
                    if (treeitem.getValue().equals(modelsNames.getModelName())) {
                        switch (treeitem.getValue()) {
                            case "CoreEquipmentProfile", "EquipmentProfile" -> {
                                modelsNames.setNsPrefix("eq");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/EquipmentCore/3/1/Constraints#");
                                } else if (profileVersion == 3) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/CoreEquipment/Constraints#");
                                }
                            }
                            case "OperationProfile" -> {
                                modelsNames.setNsPrefix("op");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/Operation-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/EquipmentOperation/3/1/Constraints#");
                                } else if (profileVersion == 3) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/Operation/Constraints#");
                                }
                            }
                            case "ShortCircuitProfile" -> {
                                modelsNames.setNsPrefix("sc");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1/Constraints#");
                                } else if (profileVersion == 3) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/ShortCircuit/Constraints#");
                                }
                            }
                            case "SteadyStateHypothesisProfile" -> {
                                modelsNames.setNsPrefix("ssh");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1/Constraints#");
                                }
                            }
                            case "TopologyProfile" -> {
                                modelsNames.setNsPrefix("tp");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/Topology-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/Topology/4/1/Constraints#");
                                }
                            }
                            case "StateVariablesProfile" -> {
                                modelsNames.setNsPrefix("sv");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/StateVariables-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/StateVariables/4/1/Constraints#");
                                }
                            }
                            case "DiagramLayoutProfile" -> {
                                modelsNames.setNsPrefix("dl");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/DiagramLayout/3/1/Constraints#");
                                }
                            }
                            case "GeographicalLocationProfile" -> {
                                modelsNames.setNsPrefix("gl");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/GeographicalLocation/2/1/Constraints#");
                                }
                            }
                            case "DynamicsProfile" -> {
                                modelsNames.setNsPrefix("dy");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/Dynamics-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/Dynamics/3/1/Constraints#");
                                } else if (profileVersion == 4) {
                                    modelsNames.setNsUri("http://cim-profile.ucaiug.io/grid/Dynamics/Constraints/2.0#");
                                }
                            }
                            case "EquipmentBoundaryProfile" -> {
                                modelsNames.setNsPrefix("eqbd");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/EquipmentBoundary/3/1/Constraints#");
                                }
                            }
                            case "TopologyBoundaryProfile" -> {
                                modelsNames.setNsPrefix("tpbd");
                                if (profileVersion == 1) {
                                    modelsNames.setNsUri("http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/Constraints#");
                                } else if (profileVersion == 2) {
                                    modelsNames.setNsUri("http://entsoe.eu/CIM/TopologyBoundary/3/1/Constraints#");
                                }
                            }
                            case "FileHeaderProfile" -> {
                                modelsNames.setNsPrefix("fh");
                                modelsNames.setNsUri("http://iec.ch/TC57/61970-552/ModelDescription/Constraints#");
                            }
                            case "PowerSystemProjectProfile", "DocPowerSystemProjectProfile" -> {
                                modelsNames.setNsPrefix("psp");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/PowerSystemProject-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/PowerSystemProject-Simple/2.3#");
                                }
                            }
                            case "RemedialActionScheduleProfile", "DocRemedialActionScheduleProfile" -> {
                                modelsNames.setNsPrefix("ras");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/RemedialActionSchedule-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/RemedialActionSchedule-Simple/2.3#");
                                }
                            }
                            case "SecurityAnalysisResultProfile", "DocSecurityAnalysisResultProfile" -> {
                                modelsNames.setNsPrefix("sar");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/SecurityAnalysisResult-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/SecurityAnalysisResult-Simple/2.4#");
                                }
                            }
                            case "SensitivityMatrixProfile", "DocSensitivityMatrixProfile" -> {
                                modelsNames.setNsPrefix("sm");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/SensitivityMatrix-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/SensitivityMatrix-Simple/2.3#");
                                }
                            }
                            case "EquipmentReliabilityProfile", "DocEquipmentReliabilityProfile" -> {
                                modelsNames.setNsPrefix("er");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/EquipmentReliability-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/EquipmentReliability-Simple/2.3#");
                                }
                            }
                            case "RemedialActionProfile", "DocRemedialActionProfile" -> {
                                modelsNames.setNsPrefix("ra");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/RemedialAction-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/RemedialAction-Simple/2.3#");
                                }
                            }
                            case "SteadyStateInstructionProfile", "DocSteadyStateInstructionProfile" -> {
                                modelsNames.setNsPrefix("ssi");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/SteadyStateInstruction-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/SteadyStateInstruction-Simple/2.3#");
                                }
                            }
                            case "AvailabilityScheduleProfile", "DocAvailabilityScheduleProfile" -> {
                                modelsNames.setNsPrefix("as");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/AvailabilitySchedule-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/AvailabilitySchedule-Simple/2.3#");
                                }
                            }
                            case "AssessedElementProfile", "DocAssessedElementProfile" -> {
                                modelsNames.setNsPrefix("ae");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/AssessedElement-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/AssessedElement-Simple/2.3#");
                                }
                            }
                            case "StateInstructionScheduleProfile", "DocStateInstructionScheduleProfile" -> {
                                modelsNames.setNsPrefix("sis");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/StateInstructionSchedule-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/StateInstructionSchedule-Simple/2.3#");
                                }
                            }
                            case "ContingencyProfile", "DocContingencyProfile" -> {
                                modelsNames.setNsPrefix("co");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/Contingency-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/Contingency-Simple/2.3#");
                                }
                            }
                            case "DocumentHeaderProfile", "DocDocumentHeaderProfile" -> {
                                modelsNames.setNsPrefix("dh");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/DocumentHeader-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/DocumentHeader-Simple/2.3#");
                                }
                            }
                            case "DatasetMetadataProfile", "DocDatasetMetadataProfile" -> {
                                modelsNames.setNsPrefix("dm");
                                if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/DatasetMetadata-Simple/2.4#");
                                }
                            }
                            case "GridDisturbanceProfile", "DocGridDisturbanceProfile" -> {
                                modelsNames.setNsPrefix("gd");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/GridDisturbance-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/GridDisturbance-Simple/2.3#");
                                }
                            }
                            case "ImpactAssessmentMatrixProfile", "DocImpactAssessmentMatrixProfile" -> {
                                modelsNames.setNsPrefix("iam");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/ImpactAssessmentMatrix-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/ImpactAssessmentMatrix-Simple/2.3#");
                                }
                            }
                            case "MonitoringAreaProfile", "DocMonitoringAreaProfile" -> {
                                modelsNames.setNsPrefix("ma");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/MonitoringArea-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/MonitoringArea-Simple/2.3#");
                                }
                            }
                            case "ObjectRegistryProfile", "DocObjectRegistryProfile" -> {
                                modelsNames.setNsPrefix("or");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/ObjectRegistry-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/ObjectRegistry-Simple/2.2#");
                                }
                            }
                            case "PowerScheduleProfile", "DocPowerScheduleProfile" -> {
                                modelsNames.setNsPrefix("ps");
                                if (profileVersion == 5) {
                                    modelsNames.setNsUri("http://entsoe.eu/ns/CIM/PowerSchedule-EU/Constraints#");
                                } else if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/PowerSchedule-Simple/2.3#");
                                }
                            }
                            case "SteadyStateHypothesisScheduleProfile",
                                 "DocSteadyStateHypothesisScheduleProfile" -> {
                                modelsNames.setNsPrefix("shs");
                                if (profileVersion == 6) {
                                    modelsNames.setNsUri("https://ap-con.cim4.eu/SteadyStateHypothesisSchedule-Simple/1.0#");
                                }
                            }
                            case "DetailedModelConfigurationProfile", "DocDetailedModelConfigurationProfile" -> {
                                modelsNames.setNsPrefix("dmc");
                                modelsNames.setNsUri("http://cim-profile.ucaiug.io/grid/DetailedModelConfiguration/Constraints/1.0#");
                            }
                            case "DetailedModelParameterisationProfile",
                                 "DocDetailedModelParameterisationProfile" -> {
                                modelsNames.setNsPrefix("dmp");
                                modelsNames.setNsUri("http://cim-profile.ucaiug.io/grid/DetailedModelParameterisation/Constraints/1.0#");
                            }
                            case "SimulationSettingsProfile", "DocSimulationSettingsProfile" -> {
                                modelsNames.setNsPrefix("set");
                                modelsNames.setNsUri("http://cim-profile.ucaiug.io/grid/SimulationSettings/Constraints/1.0#");
                            }
                            case "SimulationResultsProfile", "DocSimulationResultsProfile" -> {
                                modelsNames.setNsPrefix("sr");
                                modelsNames.setNsUri("http://cim-profile.ucaiug.io/grid/SimulationResults/Constraints/1.0#");
                            }
                            case "DLProfile" -> {
                                modelsNames.setNsPrefix("dl");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/DiagramLayout/Constraints#");
                            }
                            case "EQProfile" -> {
                                modelsNames.setNsPrefix("eq");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/Equipment/Constraints#");
                            }
                            case "GLProfile" -> {
                                modelsNames.setNsPrefix("gl");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/GeographicalLocation/Constraints#");
                            }
                            case "SCProfile" -> {
                                modelsNames.setNsPrefix("sc");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuit/Constraints#");
                            }
                            case "SSHProfile" -> {
                                modelsNames.setNsPrefix("ssh");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/SteadyStateHypothesis/Constraints#");
                            }
                            case "SVProfile" -> {
                                modelsNames.setNsPrefix("sv");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/StateVariables/Constraints#");
                            }
                            case "TPProfile" -> {
                                modelsNames.setNsPrefix("tp");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/Topology/Constraints#");
                            }
                            case "LTDSShortCircuitResultProfile", "DocLTDSShortCircuitResultProfile" -> {
                                modelsNames.setNsPrefix("scr");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuitResults/Constraints#");
                            }
                            case "LTDSSystemCapacityProfile", "DocLTDSSystemCapacityProfile" -> {
                                modelsNames.setNsPrefix("syscap");
                                modelsNames.setNsUri("http://ofgem.gov.uk/ns/CIM/LTDS/SystemCapacity/Constraints#");
                            }
                        }
                    }
                }
            }
        }
        if (cbApplyDefBaseURIDesignTab.isSelected()) {
            ObservableList<TreeItem<String>> treeitems = treeViewProfileConstraints.getRoot().getChildren();
            //for (Object modelsNames : this.modelsNames) {
            for (RdfsModelDefinition modelsNames : RDFSmodelsNames) {
                for (TreeItem<String> treeitem : treeitems) {
                    if (treeitem.getValue().equals(modelsNames.getModelName())) {
                        switch (treeitem.getValue()) {
                            case "CoreEquipmentProfile", "EquipmentProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/EquipmentCore/3/1/Constraints");
                                } else if (profileVersion == 3) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/CoreEquipment/Constraints");
                                }
                            }
                            case "OperationProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/Operation-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/EquipmentOperation/3/1/Constraints");
                                } else if (profileVersion == 3) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/Operation/Constraints");
                                }
                            }
                            case "ShortCircuitProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1/Constraints");
                                } else if (profileVersion == 3) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/ShortCircuit/Constraints");
                                }
                            }
                            case "SteadyStateHypothesisProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1/Constraints");
                                }
                            }
                            case "TopologyProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/Topology-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/Topology/4/1/Constraints");
                                }
                            }
                            case "StateVariablesProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/StateVariables-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/StateVariables/4/1/Constraints");
                                }
                            }
                            case "DiagramLayoutProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/DiagramLayout/3/1/Constraints");
                                }
                            }
                            case "GeographicalLocationProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/GeographicalLocation/2/1/Constraints");
                                }
                            }
                            case "DynamicsProfile", "DocDynamicsProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/Dynamics-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/Dynamics/3/1/Constraints");
                                } else if (profileVersion == 4) {
                                    modelsNames.setBaseUri("http://cim-profile.ucaiug.io/grid/Dynamics/Constraints/2.0");
                                }
                            }
                            case "EquipmentBoundaryProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/EquipmentBoundary/3/1/Constraints");
                                }
                            }
                            case "TopologyBoundaryProfile" -> {
                                if (profileVersion == 1) {
                                    modelsNames.setBaseUri("http://iec.ch/TC57/ns/CIM/TopologyBoundary-EU/Constraints");
                                } else if (profileVersion == 2) {
                                    modelsNames.setBaseUri("http://entsoe.eu/CIM/TopologyBoundary/3/1/Constraints");
                                }
                            }
                            case "FileHeaderProfile" ->
                                    modelsNames.setBaseUri("http://iec.ch/TC57/61970-552/ModelDescription/Constraints");
                            case "PowerSystemProjectProfile", "DocPowerSystemProjectProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/PowerSystemProject-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/PowerSystemProject-Simple/2.3");
                                }
                            }
                            case "RemedialActionScheduleProfile", "DocRemedialActionScheduleProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/RemedialActionSchedule-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/RemedialActionSchedule-Simple/2.3");
                                }
                            }
                            case "SecurityAnalysisResultProfile", "DocSecurityAnalysisResultProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/SecurityAnalysisResult-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/SecurityAnalysisResult-Simple/2.4");
                                }
                            }
                            case "SensitivityMatrixProfile", "DocSensitivityMatrixProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/SensitivityMatrix-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/SensitivityMatrix-Simple/2.3");
                                }
                            }
                            case "EquipmentReliabilityProfile", "DocEquipmentReliabilityProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/EquipmentReliability-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/EquipmentReliability-Simple/2.3");
                                }
                            }
                            case "RemedialActionProfile", "DocRemedialActionProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/RemedialAction-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/RemedialAction-Simple/2.3");
                                }
                            }
                            case "SteadyStateInstructionProfile", "DocSteadyStateInstructionProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/SteadyStateInstruction-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/SteadyStateInstruction-Simple/2.3");
                                }
                            }
                            case "AvailabilityScheduleProfile", "DocAvailabilityScheduleProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/AvailabilitySchedule-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/AvailabilitySchedule-Simple/2.3");
                                }
                            }
                            case "AssessedElementProfile", "DocAssessedElementProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/AssessedElement-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/AssessedElement-Simple/2.3");
                                }
                            }
                            case "SecurityScheduleProfile", "DocSecurityScheduleProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/SecuritySchedule-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/SecuritySchedule-Simple/2.3");
                                }
                            }
                            case "ContingencyProfile", "DocContingencyProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/Contingency-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/Contingency-Simple/2.3");
                                }
                            }
                            case "DocumentHeaderProfile", "DocDocumentHeaderProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/DocumentHeader-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/DocumentHeader-Simple/2.3");
                                }
                            }
                            case "DatasetMetadataProfile", "DocDatasetMetadataProfile" -> {
                                if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/DatasetMetadata-Simple/2.4");
                                }
                            }
                            case "GridDisturbanceProfile", "DocGridDisturbanceProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/GridDisturbance-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/GridDisturbance-Simple/2.3");
                                }
                            }
                            case "ImpactAssessmentMatrixProfile", "DocImpactAssessmentMatrixProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/ImpactAssessmentMatrix-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/ImpactAssessmentMatrix-Simple/2.3");
                                }
                            }
                            case "MonitoringAreaProfile", "DocMonitoringAreaProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/MonitoringArea-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/MonitoringArea-Simple/2.3");
                                }
                            }
                            case "ObjectRegistryProfile", "DocObjectRegistryProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/ObjectRegistry-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/ObjectRegistry-Simple/2.2");
                                }
                            }
                            case "PowerScheduleProfile", "DocPowerScheduleProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/PowerSchedule-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/PowerSchedule-Simple/2.3");
                                }
                            }
                            case "StateInstructionScheduleProfile", "DocStateInstructionScheduleProfile" -> {
                                if (profileVersion == 5) {
                                    modelsNames.setBaseUri("http://entsoe.eu/ns/CIM/StateInstructionSchedule-EU/Constraints");
                                } else if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/StateInstructionSchedule-Simple/2.3");
                                }
                            }
                            case "SteadyStateHypothesisScheduleProfile",
                                 "DocSteadyStateHypothesisScheduleProfile" -> {
                                if (profileVersion == 6) {
                                    modelsNames.setBaseUri("https://ap-con.cim4.eu/SteadyStateHypothesisSchedule-Simple/1.0");
                                }
                            }
                            case "DetailedModelConfigurationProfile", "DocDetailedModelConfigurationProfile" ->
                                    modelsNames.setBaseUri("http://cim-profile.ucaiug.io/grid/DetailedModelConfiguration/Constraints/1.0");
                            case "DetailedModelParameterisationProfile",
                                 "DocDetailedModelParameterisationProfile" ->
                                    modelsNames.setBaseUri("http://cim-profile.ucaiug.io/grid/DetailedModelParameterisation/Constraints/1.0");
                            case "SimulationSettingsProfile", "DocSimulationSettingsProfile" ->
                                    modelsNames.setBaseUri("http://cim-profile.ucaiug.io/grid/SimulationSettings/Constraints/1.0");
                            case "SimulationResultsProfile", "DocSimulationResultsProfile" ->
                                    modelsNames.setBaseUri("http://cim-profile.ucaiug.io/grid/SimulationResults/Constraints/1.0");
                            case "DLProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/DiagramLayout/Constraints");
                            case "EQProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/Equipment/Constraints");
                            case "GLProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/GeographicalLocation/Constraints");
                            case "SCProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuit/Constraints");
                            case "SSHProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/SteadyStateHypothesis/Constraints");
                            case "SVProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/StateVariables/Constraints");
                            case "TPProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/Topology/Constraints");
                            case "LTDSShortCircuitResultProfile", "DocLTDSShortCircuitResultProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/ShortCircuitResults/Constraints");
                            case "LTDSSystemCapacityProfile", "DocLTDSSystemCapacityProfile" ->
                                    modelsNames.setBaseUri("http://ofgem.gov.uk/ns/CIM/LTDS/SystemCapacity/Constraints");
                        }
                    }
                }
            }
        }

    }

    //the action for button Open RDFS for profile
    //It opens RDF files for the profiles saved locally
    @FXML
    private void actionOpenRDFS() {
        //FileChooser filechooser = new FileChooser();
        List<File> file = null;
        if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("RDFS (augmented, v2019) by CimSyntaxGen")) {
            rdfFormatInput = "CimSyntaxGen-RDFS-Augmented-2019";
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDFS (augmented, v2019) by CimSyntaxGen files", List.of("*.rdf"), "");
        } else if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("RDFS (augmented, v2020) by CimSyntaxGen")) {
            rdfFormatInput = "CimSyntaxGen-RDFS-Augmented-2020";
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDFS (augmented, v2020) by CimSyntaxGen files", List.of("*.rdf"), "");
        } else if (fcbRDFSformatShapes.getSelectionModel().getSelectedItem().equals("Merged OWL CIMTool")) {
            rdfFormatInput = "CIMTool-merged-owl";
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Merged OWL CIMTool files", List.of("*.owl"), "");
        }
        this.selectedFile = file;


        if (file != null) {// the file is selected
            setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
            int iterSize = file.size();

            for (int m = 0; m < iterSize; m++) {
                //modelLoad(m); //TODO disable this when switch to the new RDFS to SHACL
                modelLoadRDFS(m);
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
            cbRDFSSHACLoptionTypeWithOne.setDisable(false);
            cbRDFSSHACLinheritTree.setDisable(false);
            cbRDFSSHACLvalidate.setDisable(false);
            cbRDFSSHACLoptionCount.setDisable(false);
            shaclNSCommonType.setDisable(false);
            cbRDFSSHACLabstract.setDisable(false);
            cbRDFSSHACLoptionDescr.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles2nd.setDisable(false);
            cbRDFSSHACLoptionBaseprofiles3rd.setDisable(false);
            cbRDFSSHACLoptionBaseprofilesIgnoreNS.setDisable(false);
            cbRDFSSHACLoptionInverse.setDisable(false);
            cbRDFSSHACLuri.setDisable(false);
            cbRDFSSHACLncProp.setDisable(false);
            cbRDFSSHACLoptionProperty.setDisable(false);
            cbRDFSSHACLdatatypesplit.setDisable(false);

            setProgressBar(1);
        }
    }

    //Loads model data
    private void modelLoadRDFS(int m) {

        if (m == 0) {
            RDFSmodels = new ArrayList<>(); // this is a collection of models (rdf profiles) that are imported
            RDFSmodelsNames = new ArrayList<>(); // this is a collection of the name of the profile packages
            //modelsOnt =new ArrayList<>();
        }
        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        //for the text of ontology model
        try {
            RDFDataMgr.read(model, new FileInputStream(this.selectedFile.get(m).toString()), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            GUIhelper.showUserFriendlyError("RDFS loading error", "A selected RDFS file could not be opened.", e);
        }

        RDFSmodels.add(model);
    }

    //sets package structure for the tree view in the tab Browse RDF
    private void setPackageStructure(int m) {

        //set package structure
        //Model model = (Model) this.models.get(m);
        Model model = (Model) RDFSmodels.get(m);
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

        RdfsModelDefinition mpak1 = new RdfsModelDefinition(this.packages.getFirst(), "", "", "", "");
        //this.modelsNames.add(mpak1);
        RDFSmodelsNames.add(mpak1);
    }

    //initializes the TreeView in the tab Generate Shapes - profile tree vew
    private void guiTreeProfileConstraintsInit() {
        TreeItem<String> rootMain;
        //define the Treeview
        rootMain = new TreeItem<>("Main root");
        rootMain.setExpanded(true);
        treeViewProfileConstraints.setRoot(rootMain); // sets the root to the eu.griddigit.cimpal.gui object
        treeViewProfileConstraints.setShowRoot(false);
        //for (Object modelsName : this.modelsNames) {
        for (RdfsModelDefinition modelsName : RDFSmodelsNames) {
            TreeItem<String> profileItem = new TreeItem<>(modelsName.getModelName()); // level for the Classes
            rootMain.getChildren().add(profileItem);
        }

        treeViewProfileConstraints.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }
}
