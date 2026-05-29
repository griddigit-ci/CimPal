package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.comparators.ComparisonIRDFSprofile;
import eu.griddigit.cimpal.core.comparators.ComparisonIRDFSprofileCIMTool;
import eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes;
import eu.griddigit.cimpal.core.interfaces.IRDFComparator;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.rdfDiffResultController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.CompareFactory;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

public class RDFComparisonController implements Initializable {
    private MainController mainController;

    @FXML
    private TextField fPathRdffile1;
    @FXML
    private TextField fPathRdffile2;
    @FXML
    private Button btnRunRDFcompare;
    @FXML
    private ChoiceBox<String> fcbRDFSformat;
    @FXML
    private CheckBox fcbRDFcompareCimVersion;
    @FXML
    private CheckBox fcbRDFcompareProfileNS;
    @FXML
    private TextField fPrefixRDFCompare;
    @FXML
    public Button btnResetRDFComp;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fcbRDFSformat.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen",
                "RDFS (augmented) by CimSyntaxGen with CIMTool",
                "RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen",
                "Universal method inlc. SHACL Shapes"
        );
        fcbRDFSformat.getSelectionModel().selectLast();
        fcbRDFSformat.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> actionCBfcbRDFSformat());
        actionCBfcbRDFSformat();
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

    //Action for choice box "RDF scope and format" related to RDF comparison
    private void actionCBfcbRDFSformat() {
        resetProgressBar();

        if (fcbRDFSformat.getSelectionModel().isEmpty()) {
            fcbRDFcompareCimVersion.setDisable(true);
            fcbRDFcompareCimVersion.setSelected(false);
            fcbRDFcompareProfileNS.setDisable(true);
            fcbRDFcompareProfileNS.setSelected(false);
            fPrefixRDFCompare.setDisable(true);
            fPrefixRDFCompare.clear();
            return;
        }

        String selectedFormat = fcbRDFSformat.getSelectionModel().getSelectedItem();
        boolean enableAdvancedOptions = selectedFormat.equals("RDFS IEC 61970-501:Ed2 (CD) by CimSyntaxGen")
                || selectedFormat.equals("Universal method inlc. SHACL Shapes");

        fcbRDFcompareCimVersion.setDisable(!enableAdvancedOptions);
        fcbRDFcompareProfileNS.setDisable(!enableAdvancedOptions);
        fPrefixRDFCompare.setDisable(!enableAdvancedOptions);

        if (!enableAdvancedOptions) {
            fcbRDFcompareCimVersion.setSelected(false);
            fcbRDFcompareProfileNS.setSelected(false);
            fPrefixRDFCompare.clear();
        }
    }

    @FXML
    //action button Browse for RDFS comparison - file 1
    private void actionBrowseRDFfile1() {
        resetProgressBar();
        List<File> file;
        if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen with CIMTool")) {
            //select file 1
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf", "*.legacy-rdfs-augmented"), "");
        } else if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("Universal method inlc. SHACL Shapes")) {
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Universal method inlc. SHACL Shapes", List.of("*.rdf", "*.ttl"), "");
        } else return;

        if (!file.isEmpty() && file.getFirst() != null) {// the file is selected

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
        resetProgressBar();
        List<File> file;
        if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen") ||
                fcbRDFSformat.getSelectionModel().getSelectedItem().equals("RDFS (augmented) by CimSyntaxGen with CIMTool")) {
            //select file 2
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf", "*.legacy-rdfs-augmented"), "");
        } else if (fcbRDFSformat.getSelectionModel().getSelectedItem().equals("Universal method inlc. SHACL Shapes")) {
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Universal method inlc. SHACL Shapes", List.of("*.rdf", "*.ttl"), "");
        } else return;

        if (!file.isEmpty() && file.getFirst() != null) {// the file is selected
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
    //Action for button "Reset" related to the RDFS Comparison
    private void actionBtnResetRDFComp() {
        fPathRdffile1.clear();
        fPathRdffile2.clear();
        btnRunRDFcompare.setDisable(true);
        resetProgressBar();

    }

    @FXML
    public void actionBtnRunRDFcompare() throws IOException {
        if (fcbRDFSformat.getSelectionModel().getSelectedItem() == null) {
            return;
        }

        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        Lang rdfSourceFormat1 = Lang.RDFXML;
        Lang rdfSourceFormat2 = Lang.RDFXML;
        switch (fcbRDFSformat.getSelectionModel().getSelectedItem()) {
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
                throw new IllegalStateException("Unexpected value: " + fcbRDFSformat.getSelectionModel().getSelectedItem());
        }

        List<File> modelFiles1 = new LinkedList<>();
        modelFiles1.add(MainController.rdfModel1);
        List<File> modelFiles2 = new LinkedList<>();
        modelFiles2.add(MainController.rdfModel2);

        Model model1 = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(modelFiles1, null, rdfSourceFormat1, false, false).get("unionModel");
        Model model2Temp = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(modelFiles2, null, rdfSourceFormat2, false, false).get("unionModel");

        Model model2;
        boolean error = false;
        if (fcbRDFcompareCimVersion.isSelected() && !fcbRDFcompareProfileNS.isSelected()) {
            model2 = CompareFactory.renameNamespaceURIresources(model2Temp, "cim", model1.getNsPrefixURI("cim"));
        } else if (!fcbRDFcompareCimVersion.isSelected() && fcbRDFcompareProfileNS.isSelected()) {
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
                    model2 = model2Temp;
                }
            }
        } else if (fcbRDFcompareCimVersion.isSelected() && fcbRDFcompareProfileNS.isSelected()) {
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
        } else {
            model2 = model2Temp;
        }

        if (error) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("No header in the file and the profile namespace prefix is not declared.");
            alert.setHeaderText(null);
            alert.setTitle("Error");
            alert.showAndWait();
            resetProgressBar();
            return;
        }

        MainController.rdfsCompareFiles = new LinkedList<>();
        MainController.rdfsCompareFiles.add(MainController.rdfModel1.getName());
        MainController.rdfsCompareFiles.add(MainController.rdfModel2.getName());

        IRDFComparator rdfComparator;
        switch (fcbRDFSformat.getSelectionModel().getSelectedItem()) {
            case "RDFS (augmented) by CimSyntaxGen":
                rdfComparator = new ComparisonIRDFSprofile();
                break;
            case "RDFS (augmented) by CimSyntaxGen with CIMTool":
                rdfComparator = new ComparisonIRDFSprofileCIMTool();
                break;
            case "Universal method inlc. SHACL Shapes":
                rdfComparator = new ComparisonSHACLshapes();
                break;
            default:
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Unexpected RDFS format selected.");
                alert.setHeaderText(null);
                alert.setTitle("Error");
                alert.showAndWait();
                resetProgressBar();
                return;
        }

        MainController.rdfCompareResult = rdfComparator.compare(model1, model2);

        if (!MainController.rdfCompareResult.hasDifference()) {
            try {
                Stage guiRdfDiffResultsStage = new Stage();
                Parent rootRDFdiff = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/fxml/rdfDiffResult.fxml")));
                Scene rdfDiffscene = new Scene(rootRDFdiff);
                guiRdfDiffResultsStage.setScene(rdfDiffscene);
                guiRdfDiffResultsStage.setTitle("Comparison RDFS profiles");
                guiRdfDiffResultsStage.initModality(Modality.APPLICATION_MODAL);
                rdfDiffResultController.initData(guiRdfDiffResultsStage);
                setProgressBar(1);
                guiRdfDiffResultsStage.showAndWait();
            } catch (IOException e) {
                GUIhelper.showUserFriendlyError("Comparison view error", "The RDFS comparison result window could not be opened.", e);
            }
        } else {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setContentText("The two models are identical if the CIM namespace would be the same.");
            alert.setHeaderText(null);
            alert.setTitle("Information");
            setProgressBar(1);
            alert.showAndWait();
        }
    }
}
