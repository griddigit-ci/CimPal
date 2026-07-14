package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.converters.RDFConverter;
import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.apache.jena.riot.RDFFormat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

import static eu.griddigit.cimpal.main.core.RdfConvert.fileSaveDialog;

public class RDFConvertController implements Initializable {
    private MainController mainController;

    private static List<File> rdfConvertModelUnionDetailedFiles;
    private static File rdfConvertFile;
    private static List<File> rdfConvertFileList;

    @FXML
    private TextField fsourcePathTextField;
    @FXML
    private TextField fMainRdfPathTextField;
    @FXML
    private TextField fDeviationRdfPathTextField;
    @FXML
    private TextField fExtendedRdfPathTextField;
    @FXML
    private HBox mainRdfBox;
    @FXML
    private HBox deviationRdfBox;
    @FXML
    private HBox extRdfBox;
    @FXML
    private ChoiceBox<String> fsourceFormatChoiceBox;
    @FXML
    private ChoiceBox<String> ftargetFormatChoiceBox;
    @FXML
    private TextField frdfConvertXmlBase;
    @FXML
    private CheckBox fcbShowXMLDeclaration;
    @FXML
    private CheckBox fcbShowDoctypeDeclaration;
    @FXML
    private TextField fRDFconvertTab;
    @FXML
    private ChoiceBox<String> fcbRelativeURIs;
    @FXML
    private ChoiceBox<String> fcbRDFformat;
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
    private CheckBox fcbRDFConveraddowl;
    @FXML
    private CheckBox fcbRDFconvertModelUnionDetailed;
    @FXML
    private CheckBox fcbSortRDF;
    @FXML
    private CheckBox fcbRDFconvertInstanceData;
    @FXML
    private ChoiceBox fcbRDFsortOptions;
    @FXML
    private CheckBox fcbRDFconvertFixPackage;
    @FXML
    private Button fbtnRunRDFConvert;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        mainRdfBox.disableProperty().bind(fcbRDFconvertModelUnionDetailed.selectedProperty().not());
        deviationRdfBox.disableProperty().bind(fcbRDFconvertModelUnionDetailed.selectedProperty().not());
        extRdfBox.disableProperty().bind(fcbRDFconvertModelUnionDetailed.selectedProperty().not());

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

        fcbRDFsortOptions.getItems().addAll(
                "Sorting by local name",
                "Sorting by prefix"
        );

        ftargetFormatChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBRDFconvertTarget());

        rdfConvertModelUnionDetailedFiles = new LinkedList<>();
        rdfConvertFileList = new LinkedList<>();
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
    //action button Browse for RDFS convert
    private void actionBrowseRDFConvert() {
        resetProgressBar();
        //select file
        List<File> file;
        List<File> fileL;
        if (fcbRDFconvertModelUnion.isSelected()) {
            fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF file to convert", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

            if (!fileL.isEmpty()) {// the file is selected
                fsourcePathTextField.setText(fileL.toString());
                rdfConvertFileList = new LinkedList<>(fileL);
            } else {
                fsourcePathTextField.clear();
            }
        } else {
            file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF file to convert", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

            if (!file.isEmpty()) {// the file is selected
                //MainController.prefs.put("LastWorkingFolder", file.getParent());
                fsourcePathTextField.setText(file.getFirst().toString());
                rdfConvertFile = file.getFirst();
            } else {
                fsourcePathTextField.clear();
            }
        }
    }

    @FXML
    private void actionBrowseMainRDF() {
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Main RDF file", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

        if (file.getFirst() != null) {// the file is selected
            fMainRdfPathTextField.setText(file.getFirst().toString());
            rdfConvertModelUnionDetailedFiles.addAll(file);
        } else {
            fMainRdfPathTextField.clear();
        }
    }

    @FXML
    private void actionBrowseDeviationRDF() {
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Deviation RDF file", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

        if (file.getFirst() != null) {// the file is selected
            fDeviationRdfPathTextField.setText(file.getFirst().toString());
            rdfConvertModelUnionDetailedFiles.addAll(file);
        } else {
            fDeviationRdfPathTextField.clear();
        }
    }

    @FXML
    private void actionBrowseExtendedRDF() {
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Extended RDF file", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "");

        if (file.getFirst() != null) {// the file is selected
            fExtendedRdfPathTextField.setText(file.getFirst().toString());
            rdfConvertModelUnionDetailedFiles.addAll(file);
        } else {
            fExtendedRdfPathTextField.clear();
        }
    }

    @FXML
    //Action for button "Convert" related to the RDF Convert
    private void actionBtnRunRDFConvert() throws IOException {

        resetProgressBar();
        //format from
        String sourceFormatString = fsourceFormatChoiceBox.getSelectionModel().getSelectedItem();
        RDFConvertOptions.RDFFormats sourceFormat = RDFConvertOptions.RDFFormats.RDFXML;
        //format to
        String targetFormatString = ftargetFormatChoiceBox.getSelectionModel().getSelectedItem();
        RDFConvertOptions.RDFFormats targetFormat = RDFConvertOptions.RDFFormats.RDFXML;
        //xmlBase
        String xmlBase = null;

        if (!frdfConvertXmlBase.getText().isBlank()) {
            xmlBase = frdfConvertXmlBase.getText();
        }

        RDFFormat rdfFormat = RDFFormat.RDFXML_PLAIN;
        if (!fcbRDFformat.getSelectionModel().isSelected(-1)) {
            rdfFormat = switch (fcbRDFformat.getSelectionModel().getSelectedItem()) {
                case "RDFXML" -> RDFFormat.RDFXML;
                case "RDFXML_ABBREV" -> RDFFormat.RDFXML_ABBREV;
                case "RDFXML_PLAIN" -> RDFFormat.RDFXML_PLAIN;
                case "RDFXML_PRETTY" -> RDFFormat.RDFXML_PRETTY;
                case "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
                case "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN;
                default ->
                        throw new IllegalStateException("Unexpected value: " + fcbRDFformat.getSelectionModel().getSelectedItem());
            };
        }

        //sort options
        String sortOptions = "";
        if (targetFormatString.equals("RDF XML (.rdf or .xml)")) {
            sortOptions = fcbRDFsortOptions.getSelectionModel().getSelectedItem().toString();
        }

        String showXmlDeclaration = "false";
        if (targetFormatString.equals("RDF XML (.rdf or .xml)") && fcbShowXMLDeclaration.isSelected()) {
            showXmlDeclaration = "true";
        }

        String showDoctypeDeclaration = "false";
        if (targetFormatString.equals("RDF XML (.rdf or .xml)") && fcbShowDoctypeDeclaration.isSelected()) {
            showDoctypeDeclaration = "true";
        }
        String tab = "";
        if (targetFormatString.equals("RDF XML (.rdf or .xml)")) {
            tab = fRDFconvertTab.getText();
        }
        String relativeURIs = "";
        if (targetFormatString.equals("RDF XML (.rdf or .xml)")) {
            relativeURIs = fcbRelativeURIs.getSelectionModel().getSelectedItem().toString();
        }

        if (sourceFormatString.equals("RDF Turtle (.ttl)")) {
            sourceFormat = RDFConvertOptions.RDFFormats.TURTLE;
        } else if (sourceFormatString.equals("JSON-LD (.jsonld)")) {
            sourceFormat = RDFConvertOptions.RDFFormats.JSONLD;
        }

        if (targetFormatString.equals("RDF Turtle (.ttl)")) {
            targetFormat = RDFConvertOptions.RDFFormats.TURTLE;
        } else if (targetFormatString.equals("JSON-LD (.jsonld)")) {
            targetFormat = RDFConvertOptions.RDFFormats.JSONLD;
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
        boolean keepOntologyHeaders = true;

        // Ask only when this setting is relevant for standard model-union conversion.
        if (modelUnionFlag && !modelUnionFlagDetailed) {
            Alert alert = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Do you want to keep ontology headers in the merged model?",
                    ButtonType.YES,
                    ButtonType.NO
            );
            alert.setTitle("Headers");
            alert.setHeaderText("Ontology Headers");

            Optional<ButtonType> result = alert.showAndWait();
            keepOntologyHeaders = result.isPresent() && result.get() == ButtonType.YES;
        }

        // Create RDFConvertOptions object
        RDFConvertOptions.Builder builder = RDFConvertOptions.builder()
                .sourceFile(rdfConvertFile)
                .modelUnionFiles(rdfConvertFileList)
                .modelUnionDetailedFiles(rdfConvertModelUnionDetailedFiles)
                .sourceFormat(sourceFormat)
                .targetFormat(targetFormat)
                .xmlBase(xmlBase)
                .rdfXmlFormat(rdfFormat)
                .showXmlDeclaration(showXmlDeclaration)
                .showDoctypeDeclaration(showDoctypeDeclaration)
                .tabCharacter(tab)
                .relativeURIs(relativeURIs)
                .modelUnionFlag(modelUnionFlag)
                .inheritanceOnly(inheritanceOnly)
                .inheritanceList(inheritanceList)
                .inheritanceListConcrete(inheritanceListConcrete)
                .addOwl(addowl)
                .modelUnionFlagDetailed(modelUnionFlagDetailed)
                .sortRDF(sortRDF)
                .rdfSortOptions(rdfSortOptions)
                .stripPrefixes(stripPrefixes)
                .convertInstanceData(convertInstanceData)
                .modelUnionFixPackage(modelUnionFixPackage)
                .keepOntologyHeaders(keepOntologyHeaders);

        RDFConvertOptions options = builder.build();

        RDFConverter rdfConverter = new RDFConverter(options);
        // run the conversion
        rdfConverter.convert();

        // select the output file
        String filename = "";
        if (!modelUnionFlag && rdfConvertFile != null) {
            filename = rdfConvertFile.getName().split("\\.", 2)[0];
        } else {
            filename = "MultipleModels";
        }
        OutputStream out = null;
        switch (targetFormatString) {
            case "RDF XML (.rdf or .xml)" -> {
                out = fileSaveDialog("Save RDF XML for: " + filename, "RDF XML", "*.rdf");
            }
            case "RDF Turtle (.ttl)" -> {
                out = fileSaveDialog("Save RDF Turtle for: " + filename, "RDF Turtle", "*.ttl");
            }
            case "JSON-LD (.jsonld)" -> {
                out = fileSaveDialog("Save JSON-LD for: " + filename, "JSON-LD", "*.jsonld");
            }
        }
        // write the converted model to the output file
        rdfConverter.writeConvertedModel(out);

        // write the inheritance list if required
        if (options.isInheritanceList()) {
            OutputStream outInheritance = fileSaveDialog("Save inheritance for: " + filename + "Inheritance", "RDF Turtle", "*.ttl");
            ;

            rdfConverter.writeInheritanceModel(outInheritance);
        }

        // Clear the static variables after conversion
        fsourcePathTextField.clear();
        fMainRdfPathTextField.clear();
        fDeviationRdfPathTextField.clear();
        fExtendedRdfPathTextField.clear();
        if (!rdfConvertModelUnionDetailedFiles.isEmpty()) {
            rdfConvertModelUnionDetailedFiles.clear();
        }
        rdfConvertFile = null;
        if (!rdfConvertFileList.isEmpty()) {
            rdfConvertFileList.clear();
        }

        setProgressBar(1);
    }

    @FXML
    //Action for button "Reset" related to RDF Convert
    private void actionBrtResetRDFConvert() {

        resetProgressBar();
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
        fcbRDFconvertModelUnionDetailed.setSelected(false);
        fcbRDFconvertFixPackage.setSelected(false);
        fcbRDFconvertInstanceData.setSelected(false);
        fcbRDFformat.getSelectionModel().clearSelection();
        fcbShowXMLDeclaration.setSelected(true);
        fcbShowXMLDeclaration.setDisable(true);
        fcbShowDoctypeDeclaration.setSelected(false);
        fcbShowDoctypeDeclaration.setDisable(true);
        fRDFconvertTab.setText("2");
        fRDFconvertTab.setDisable(true);
        fcbRelativeURIs.getSelectionModel().clearSelection();
        fcbRelativeURIs.setDisable(true);
        fcbRDFformat.getSelectionModel().clearSelection();
        fcbRDFformat.setDisable(true);
        fcbSortRDF.setSelected(false);
        fcbSortRDF.setDisable(true);
        fcbRDFsortOptions.getSelectionModel().clearSelection();
        fcbRDFsortOptions.setDisable(true);
        fcbStripPrefixes.setSelected(false);
        fcbStripPrefixes.setDisable(true);
        fMainRdfPathTextField.clear();
        fDeviationRdfPathTextField.clear();
        fExtendedRdfPathTextField.clear();
        if (rdfConvertModelUnionDetailedFiles != null) {
            rdfConvertModelUnionDetailedFiles.clear();
        }
        rdfConvertFile = null;
        if (rdfConvertFileList != null) {
            rdfConvertFileList.clear();
        }
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

        resetProgressBar();
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
}
