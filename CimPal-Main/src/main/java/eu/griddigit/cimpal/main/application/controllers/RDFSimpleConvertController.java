package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.converters.RDFConverter;
import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.BaseUriPresets;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.PathMemory;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.apache.jena.riot.RDFFormat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import static eu.griddigit.cimpal.main.core.RdfConvert.fileSaveDialog;

/** Pure one-file RDF conversion. RDFS union and transformation operations live in RDFS Union. */
public class RDFSimpleConvertController implements Initializable {
    private MainController mainController;
    private File sourceFile;
    @FXML private TextField fsourcePathTextField, frdfConvertXmlBase, fRDFconvertTab;
    @FXML private ChoiceBox<String> fsourceFormatChoiceBox, ftargetFormatChoiceBox, fcbBaseUri, fcbRelativeURIs, fcbRDFformat;
    @FXML private CheckBox fcbShowXMLDeclaration, fcbShowDoctypeDeclaration, fcbSortRDF, fcbStripPrefixes, fcbRDFconvertInstanceData;
    @FXML private ChoiceBox<String> fcbRDFsortOptions;
    @FXML private Label helpSource, helpSourceType, helpTargetFormat, helpXmlBase, helpSortingOptions;

    @Override public void initialize(URL location, ResourceBundle resources) {
        fsourceFormatChoiceBox.getItems().addAll("RDF XML (.rdf or .xml)", "RDF Turtle (.ttl)", "JSON-LD (.jsonld)");
        ftargetFormatChoiceBox.getItems().addAll("RDF XML (.rdf or .xml)", "RDF Turtle (.ttl)", "JSON-LD (.jsonld)");
        fcbRelativeURIs.getItems().addAll("same-document", "network", "absolute", "relative", "parent", "grandparent");
        fcbRDFformat.getItems().addAll("RDFXML_PLAIN", "RDFXML", "RDFXML_PRETTY", "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)", "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)", "RDFXML_ABBREV");
        fcbRDFsortOptions.getItems().addAll("Sorting by local name", "Sorting by prefix");
        BaseUriPresets.bind(fcbBaseUri, frdfConvertXmlBase, BaseUriPresets.OTHER);
        PathMemory.bind(fsourcePathTextField, "tab.rdfConvert.source", file -> sourceFile = file);
        ftargetFormatChoiceBox.getSelectionModel().selectedItemProperty().addListener((o, oldValue, value) -> updateTargetOptions());
        GUIhelper.installHelpTooltip(helpSource, "Select one RDF file to convert. Model union and RDFS transformations are available on the RDFS Union tab.");
        GUIhelper.installHelpTooltip(helpSourceType, "Choose this only for IEC 61970-552 / CGMES instance data.");
        GUIhelper.installHelpTooltip(helpTargetFormat, "Choose the syntax for the converted RDF output.");
        GUIhelper.installHelpTooltip(helpXmlBase, "Base URI written to RDF/XML output. Leave empty to omit xml:base.");
        GUIhelper.installHelpTooltip(helpSortingOptions, "Sorting controls are currently available for the custom RDF/XML writers.");
    }
    public void setMainController(MainController mainController) { this.mainController = mainController; }
    private void resetProgressBar() { if (mainController != null) mainController.resetProgressBar(); }
    @FXML private void actionBrowseRDFConvert() {
        List<File> files = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF file to convert", List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld"), "", "tab.rdfConvert.source");
        if (!files.isEmpty()) { sourceFile = files.getFirst(); fsourcePathTextField.setText(sourceFile.toString()); } else { sourceFile = null; fsourcePathTextField.clear(); }
    }
    @FXML private void actionBtnRunRDFConvert() throws IOException {
        if (sourceFile == null || fsourceFormatChoiceBox.getValue() == null || ftargetFormatChoiceBox.getValue() == null) throw new IllegalStateException("Select a source file, source format, and target format.");
        RDFConvertOptions.RDFFormats source = format(fsourceFormatChoiceBox.getValue()), target = format(ftargetFormatChoiceBox.getValue());
        RDFFormat rdfXml = rdfXmlFormat();
        String base = frdfConvertXmlBase.getText().isBlank() ? "" : frdfConvertXmlBase.getText();
        RDFConvertOptions options = RDFConvertOptions.builder().sourceFile(sourceFile).sourceFormat(source).targetFormat(target).xmlBase(base).rdfXmlFormat(rdfXml)
                .showXmlDeclaration(Boolean.toString(fcbShowXMLDeclaration.isSelected())).showDoctypeDeclaration(Boolean.toString(fcbShowDoctypeDeclaration.isSelected()))
                .tabCharacter(fRDFconvertTab.getText()).relativeURIs(fcbRelativeURIs.getValue() == null ? "" : fcbRelativeURIs.getValue())
                .sortRDF(Boolean.toString(fcbSortRDF.isSelected())).rdfSortOptions(Boolean.toString("Sorting by prefix".equals(fcbRDFsortOptions.getValue())))
                .stripPrefixes(fcbStripPrefixes.isSelected()).convertInstanceData(Boolean.toString(fcbRDFconvertInstanceData.isSelected())).build();
        RDFConverter converter = new RDFConverter(options); converter.convert();
        OutputStream out = switch (target) { case RDFXML -> fileSaveDialog("Save RDF XML", "RDF XML", "*.rdf"); case TURTLE -> fileSaveDialog("Save RDF Turtle", "RDF Turtle", "*.ttl"); case JSONLD -> fileSaveDialog("Save JSON-LD", "JSON-LD", "*.jsonld"); };
        converter.writeConvertedModel(out);
        if (mainController != null) mainController.setProgressBarValue(1);
    }
    @FXML private void actionBrtResetRDFConvert() { sourceFile = null; fsourcePathTextField.clear(); fsourceFormatChoiceBox.setValue(null); ftargetFormatChoiceBox.setValue(null); fcbBaseUri.setValue(BaseUriPresets.OTHER); frdfConvertXmlBase.clear(); fcbRDFconvertInstanceData.setSelected(false); resetProgressBar(); updateTargetOptions(); }
    private RDFConvertOptions.RDFFormats format(String value) { return value.startsWith("RDF Turtle") ? RDFConvertOptions.RDFFormats.TURTLE : value.startsWith("JSON-LD") ? RDFConvertOptions.RDFFormats.JSONLD : RDFConvertOptions.RDFFormats.RDFXML; }
    private RDFFormat rdfXmlFormat() { String value = fcbRDFformat.getValue(); if (value == null) return RDFFormat.RDFXML_PLAIN; return switch (value) { case "RDFXML" -> RDFFormat.RDFXML; case "RDFXML_ABBREV" -> RDFFormat.RDFXML_ABBREV; case "RDFXML_PRETTY" -> RDFFormat.RDFXML_PRETTY; case "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY; case "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN; default -> RDFFormat.RDFXML_PLAIN; }; }
    private void updateTargetOptions() { boolean xml = format(ftargetFormatChoiceBox.getValue() == null ? "" : ftargetFormatChoiceBox.getValue()) == RDFConvertOptions.RDFFormats.RDFXML && ftargetFormatChoiceBox.getValue() != null; fcbShowXMLDeclaration.setDisable(!xml); fcbShowDoctypeDeclaration.setDisable(!xml); fRDFconvertTab.setDisable(!xml); fcbRelativeURIs.setDisable(!xml); fcbRDFformat.setDisable(!xml); fcbSortRDF.setDisable(!xml); fcbRDFsortOptions.setDisable(!xml); fcbStripPrefixes.setDisable(!xml); if (xml) { fcbRelativeURIs.setValue("same-document"); fcbRDFformat.setValue("RDFXML_PLAIN"); fcbRDFsortOptions.setValue("Sorting by local name"); } }
}
