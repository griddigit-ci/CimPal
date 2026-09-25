/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.converters.RDFConverter;
import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.BaseUriPresets;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.rdf.model.*;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.*;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.LinkedHashMap;
import java.util.Map;

import static eu.griddigit.cimpal.main.core.RdfConvert.fileSaveDialog;

/** RDFS union and post-union transformations. Input RDF syntax is inferred per selected file. */
public class RDFSUnionController implements Initializable {
    private static final List<String> DATASET_FILTERS = List.of("*.rdf", "*.xml", "*.ttl", "*.jsonld");
    private final List<File> simpleDatasets = new ArrayList<>();
    private File mainDataset, deviationDataset, extendedDataset;
    private MainController mainController;
    private final Map<String, List<Node>> ontologyMetadata = new LinkedHashMap<>();
    @FXML private RadioButton simpleUnionRadio, detailedUnionRadio;
    @FXML private HBox simpleUnionInput, simpleUnionOptions;
    @FXML private VBox detailedUnionInput;
    @FXML private TextField simpleDatasetsTextField, mainDatasetTextField, deviationDatasetTextField, extendedDatasetTextField, resultPackageTextField, resultOntologyTextField, deletionStereotypeTextField, xmlBaseTextField, tabTextField;
    @FXML private CheckBox keepOntologyHeadersCheckBox, inheritanceOnlyCheckBox, inheritanceListCheckBox, concreteOnlyCheckBox, addOwlCheckBox, showXmlDeclarationCheckBox, showDoctypeCheckBox, sortCheckBox, stripPrefixesCheckBox;
    @FXML private ChoiceBox<String> targetFormatChoiceBox, baseUriChoiceBox, relativeUrisChoiceBox, rdfXmlFormatChoiceBox, sortOptionsChoiceBox;
    @FXML private Label helpUnionType, helpSimpleDatasets, helpMainDataset, helpDeviationDataset, helpExtendedDataset, helpResultPackage, helpResultOntology, helpDeletionStereotype, helpKeepOntologyHeaders, helpTransformations, helpTargetFormat, helpRdfXmlOptions, helpBaseUri, helpSorting;

    @Override public void initialize(URL location, ResourceBundle resources) {
        ToggleGroup unionType = new ToggleGroup(); simpleUnionRadio.setToggleGroup(unionType); detailedUnionRadio.setToggleGroup(unionType); simpleUnionRadio.setSelected(true);
        simpleUnionInput.visibleProperty().bind(simpleUnionRadio.selectedProperty()); simpleUnionInput.managedProperty().bind(simpleUnionRadio.selectedProperty());
        simpleUnionOptions.visibleProperty().bind(simpleUnionRadio.selectedProperty()); simpleUnionOptions.managedProperty().bind(simpleUnionRadio.selectedProperty());
        detailedUnionInput.visibleProperty().bind(detailedUnionRadio.selectedProperty()); detailedUnionInput.managedProperty().bind(detailedUnionRadio.selectedProperty());
        targetFormatChoiceBox.getItems().addAll("RDF XML (.rdf or .xml)", "RDF Turtle (.ttl)", "JSON-LD (.jsonld)");
        relativeUrisChoiceBox.getItems().addAll("same-document", "network", "absolute", "relative", "parent", "grandparent");
        rdfXmlFormatChoiceBox.getItems().addAll("RDFXML_PLAIN", "RDFXML", "RDFXML_PRETTY", "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)", "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)", "RDFXML_ABBREV");
        sortOptionsChoiceBox.getItems().addAll("Sorting by local name", "Sorting by prefix");
        BaseUriPresets.bind(baseUriChoiceBox, xmlBaseTextField, BaseUriPresets.OTHER);
        targetFormatChoiceBox.valueProperty().addListener((o, oldValue, value) -> updateOutputOptions());
        initializeHelpTooltips();
    }
    public void setMainController(MainController controller) { mainController = controller; }
    @FXML private void browseSimpleDatasets() { List<File> files = choose(false, "Select RDFS datasets", "tab.rdfsUnion.simple"); if (!files.isEmpty()) { simpleDatasets.clear(); simpleDatasets.addAll(files); simpleDatasetsTextField.setText(files.toString()); } }
    @FXML private void browseMainDataset() { mainDataset = chooseOne("Main RDFS dataset", "tab.rdfsUnion.main"); mainDatasetTextField.setText(path(mainDataset)); }
    @FXML private void browseDeviationDataset() { deviationDataset = chooseOne("Deviation RDFS dataset", "tab.rdfsUnion.deviation"); deviationDatasetTextField.setText(path(deviationDataset)); }
    @FXML private void browseExtendedDataset() { extendedDataset = chooseOne("Extended RDFS dataset", "tab.rdfsUnion.extended"); extendedDatasetTextField.setText(path(extendedDataset)); }
    private List<File> choose(boolean single, String title, String key) { return eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(single, title, DATASET_FILTERS, "", key); }
    private File chooseOne(String title, String key) { List<File> files = choose(true, title, key); return files.isEmpty() ? null : files.getFirst(); }
    private String path(File file) { return file == null ? "" : file.toString(); }
    @FXML private void updateInheritanceOptions() { boolean inheritance = inheritanceOnlyCheckBox.isSelected(); inheritanceListCheckBox.setDisable(!inheritance); if (!inheritance) { inheritanceListCheckBox.setSelected(false); concreteOnlyCheckBox.setSelected(false); concreteOnlyCheckBox.setDisable(true); } else concreteOnlyCheckBox.setDisable(!inheritanceListCheckBox.isSelected()); }
    private void updateOutputOptions() { boolean xml = "RDF XML (.rdf or .xml)".equals(targetFormatChoiceBox.getValue()); for (Control control : List.of(showXmlDeclarationCheckBox, showDoctypeCheckBox, tabTextField, relativeUrisChoiceBox, rdfXmlFormatChoiceBox, sortCheckBox, sortOptionsChoiceBox, stripPrefixesCheckBox)) control.setDisable(!xml); if (xml) { relativeUrisChoiceBox.setValue("same-document"); rdfXmlFormatChoiceBox.setValue("RDFXML_PLAIN"); sortOptionsChoiceBox.setValue("Sorting by local name"); } }
    @FXML private void saveUnionResult() throws IOException {
        if (targetFormatChoiceBox.getValue() == null) throw new IllegalStateException("Select a target format.");
        boolean detailed = detailedUnionRadio.isSelected();
        if (detailed && mainDataset == null) throw new IllegalStateException("Select the required Main RDFS dataset.");
        if (!detailed && simpleDatasets.isEmpty()) throw new IllegalStateException("Select one or more RDFS datasets.");
        List<File> detailedFiles = new ArrayList<>(); if (mainDataset != null) detailedFiles.add(mainDataset); if (deviationDataset != null) detailedFiles.add(deviationDataset); if (extendedDataset != null) detailedFiles.add(extendedDataset);
        RDFConvertOptions.RDFFormats target = targetFormatChoiceBox.getValue().startsWith("RDF Turtle") ? RDFConvertOptions.RDFFormats.TURTLE : targetFormatChoiceBox.getValue().startsWith("JSON-LD") ? RDFConvertOptions.RDFFormats.JSONLD : RDFConvertOptions.RDFFormats.RDFXML;
        RDFConvertOptions options = RDFConvertOptions.builder().sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(target).xmlBase(xmlBaseTextField.getText().isBlank() ? "" : xmlBaseTextField.getText()).rdfXmlFormat(rdfXmlFormat()).showXmlDeclaration(Boolean.toString(showXmlDeclarationCheckBox.isSelected())).showDoctypeDeclaration(Boolean.toString(showDoctypeCheckBox.isSelected())).tabCharacter(tabTextField.getText()).relativeURIs(relativeUrisChoiceBox.getValue() == null ? "" : relativeUrisChoiceBox.getValue()).modelUnionFlag(!detailed).modelUnionFiles(simpleDatasets).modelUnionFlagDetailed(detailed).modelUnionDetailedFiles(detailedFiles).keepOntologyHeaders(keepOntologyHeadersCheckBox.isSelected()).inheritanceOnly(inheritanceOnlyCheckBox.isSelected()).inheritanceList(inheritanceListCheckBox.isSelected()).inheritanceListConcrete(concreteOnlyCheckBox.isSelected()).addOwl(addOwlCheckBox.isSelected()).detailedUnionPackageUri(resultPackageTextField.getText()).detailedUnionOntologyUri(resultOntologyTextField.getText()).detailedUnionDeletionStereotype(deletionStereotypeTextField.getText()).detailedUnionOntologyMetadata(ontologyMetadata).sortRDF(Boolean.toString(sortCheckBox.isSelected())).rdfSortOptions(Boolean.toString("Sorting by prefix".equals(sortOptionsChoiceBox.getValue()))).stripPrefixes(stripPrefixesCheckBox.isSelected()).convertInstanceData("false").build();
        RDFConverter converter = new RDFConverter(options); converter.convert(); OutputStream output = switch (target) { case RDFXML -> fileSaveDialog("Save RDFS union", "RDF XML", "*.rdf"); case TURTLE -> fileSaveDialog("Save RDFS union", "RDF Turtle", "*.ttl"); case JSONLD -> fileSaveDialog("Save RDFS union", "JSON-LD", "*.jsonld"); }; converter.writeConvertedModel(output);
        if (options.isInheritanceList()) converter.writeInheritanceModel(fileSaveDialog("Save inheritance list", "RDF Turtle", "*.ttl"));
        if (mainController != null) mainController.setProgressBarValue(1);
    }
    private RDFFormat rdfXmlFormat() { String value = rdfXmlFormatChoiceBox.getValue(); if (value == null) return RDFFormat.RDFXML_PLAIN; return switch (value) { case "RDFXML" -> RDFFormat.RDFXML; case "RDFXML_PRETTY" -> RDFFormat.RDFXML_PRETTY; case "RDFXML_ABBREV" -> RDFFormat.RDFXML_ABBREV; case "CIMXML 61970-552 (RDFXML_CUSTOM_PLAIN_PRETTY)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY; case "RDFS CIMXML 61970-501 (RDFXML_CUSTOM_PLAIN)" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN; default -> RDFFormat.RDFXML_PLAIN; }; }
    private void initializeHelpTooltips() {
        GUIhelper.installHelpTooltip(helpUnionType, "Simple union merges selected datasets as-is. Detailed RDFS union applies profile cleanup and lets you set the resulting package and ontology identities.");
        GUIhelper.installHelpTooltip(helpSimpleDatasets, "Select one or more RDF/RDFS datasets. CimPal infers each input syntax from its file extension.");
        GUIhelper.installHelpTooltip(helpMainDataset, "Required reference RDFS dataset for Detailed Union. Its statements take precedence when duplicate multiplicities are cleaned.");
        GUIhelper.installHelpTooltip(helpDeviationDataset, "Optional deviation RDFS dataset to merge with the main dataset.");
        GUIhelper.installHelpTooltip(helpExtendedDataset, "Optional extension RDFS dataset to merge with the main and deviation datasets.");
        GUIhelper.installHelpTooltip(helpResultPackage, "Optional exact package resource for the result. Enter a full URI or a prefix-qualified identifier such as gb:Package_EQProfile. Existing package assignments are replaced only when this is supplied.");
        GUIhelper.installHelpTooltip(helpResultOntology, "Optional exact ontology resource for the result. Enter a full URI or a prefix-qualified identifier such as gb:Ontology. CimPal preserves the retained ontology metadata while changing its URI.");
        GUIhelper.installHelpTooltip(helpDeletionStereotype, "Classes and properties marked with this stereotype are removed during Detailed Union. Leave blank to detect the first cims:stereotype containing notDefined.");
        GUIhelper.installHelpTooltip(helpKeepOntologyHeaders, "Keep ontology header resources from every selected dataset in a Simple Union.");
        GUIhelper.installHelpTooltip(helpTransformations, "Process inheritance properties only keeps the class hierarchy. Generate inheritance list writes that hierarchy to a separate Turtle file; Concrete classes only narrows that list. Add OWL types adds OWL class/property declarations.");
        GUIhelper.installHelpTooltip(helpTargetFormat, "Choose the syntax used for the saved union result.");
        GUIhelper.installHelpTooltip(helpRdfXmlOptions, "These serialization settings apply when RDF/XML is selected. The RDFS CIMXML 61970-501 writer is available from RDF format.");
        GUIhelper.installHelpTooltip(helpBaseUri, "Sets xml:base for RDF/XML output. Leave the text field empty to omit xml:base.");
        GUIhelper.installHelpTooltip(helpSorting, "Controls deterministic RDF/XML ordering and removal of unused namespace prefixes. These options are available with RDF/XML output.");
    }
    @FXML private void configureOntology() {
        if (!detailedUnionRadio.isSelected() || mainDataset == null) throw new IllegalStateException("Select the Main RDFS dataset before configuring the ontology.");
        Map<String, List<MetadataOption>> suggestions = readOntologySuggestions();
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Configure resulting ontology"); dialog.setHeaderText("Select exactly which properties and values from the loaded ontology headers belong in the result.");
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        grid.addRow(0, new Label("Include"), new Label("Property"), new Label("Available values"));
        Map<String, CheckBox> propertySelectors = new LinkedHashMap<>(); Map<String, ListView<MetadataOption>> valueSelectors = new LinkedHashMap<>(); int row = 1;
        for (Map.Entry<String, List<MetadataOption>> entry : suggestions.entrySet()) { CheckBox include = new CheckBox(); include.setSelected(entry.getValue().stream().anyMatch(option -> "Main".equals(option.source))); ListView<MetadataOption> values = new ListView<>(); values.getItems().addAll(entry.getValue()); values.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); values.setPrefHeight(96); entry.getValue().stream().filter(option -> "Main".equals(option.source)).forEach(option -> values.getSelectionModel().select(option)); values.disableProperty().bind(include.selectedProperty().not()); Button add = new Button("Add value…"); add.disableProperty().bind(include.selectedProperty().not()); add.setOnAction(event -> promptForCustomValue(entry.getKey()).ifPresent(option -> { values.getItems().add(option); values.getSelectionModel().select(option); })); grid.addRow(row++, include, new Label(localName(entry.getKey()) + ":"), values, add); propertySelectors.put(entry.getKey(), include); valueSelectors.put(entry.getKey(), values); }
        ScrollPane pane = new ScrollPane(grid); pane.setFitToWidth(true); pane.setPrefViewportHeight(620); dialog.getDialogPane().setPrefWidth(1100); dialog.getDialogPane().setPrefHeight(760); dialog.getDialogPane().setContent(pane); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) { ontologyMetadata.clear(); propertySelectors.forEach((property, include) -> ontologyMetadata.put(property, include.isSelected() ? valueSelectors.get(property).getSelectionModel().getSelectedItems().stream().map(option -> option.node).toList() : List.of())); }
    }
    private java.util.Optional<MetadataOption> promptForCustomValue(String property) { TextInputDialog input = new TextInputDialog(); input.setTitle("Add ontology value"); input.setHeaderText(localName(property)); input.setContentText("Value:"); return input.showAndWait().filter(value -> !value.isBlank()).map(value -> new MetadataOption("Other", NodeFactory.createLiteralString(value))); }
    private Map<String, List<MetadataOption>> readOntologySuggestions() { Map<String, LinkedHashMap<String, MetadataOption>> unique = new LinkedHashMap<>(); List<File> files = new ArrayList<>(); List<String> roles = new ArrayList<>(); if (mainDataset != null) { files.add(mainDataset); roles.add("Main"); } if (deviationDataset != null) { files.add(deviationDataset); roles.add("Deviation"); } if (extendedDataset != null) { files.add(extendedDataset); roles.add("Extended"); } for (int index = 0; index < files.size(); index++) { Model source = ModelFactory.createDefaultModel(); RDFDataMgr.read(source, files.get(index).toURI().toString()); List<Resource> ontologies = source.listSubjectsWithProperty(RDF.type, OWL2.Ontology).toList(); if (ontologies.isEmpty()) ontologies = source.listSubjectsWithProperty(DCTerms.title).toList(); for (Resource ontology : ontologies) for (StmtIterator statements = source.listStatements(ontology, null, (RDFNode) null); statements.hasNext();) { Statement statement = statements.next(); if (!statement.getPredicate().equals(RDF.type)) { String property = canonicalPropertyUri(statement.getPredicate().getURI()); Node value = statement.getObject().asNode(); unique.computeIfAbsent(property, key -> new LinkedHashMap<>()).putIfAbsent(value.toString(), new MetadataOption(roles.get(index), value)); } } } Map<String, List<MetadataOption>> result = new LinkedHashMap<>(); unique.forEach((property, values) -> result.put(property, new ArrayList<>(values.values()))); return result; }
    private static String canonicalPropertyUri(String uri) { return uri.replace("http://purl.org/dc/terms/#", DCTerms.NS).replace("http://purl.org/dc/terms#", DCTerms.NS); }
    private static String localName(String uri) { return uri.substring(Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('#')) + 1); }
    private record MetadataOption(String source, Node node) { @Override public String toString() { return source + ": " + node; } }
    @FXML private void reset() { ontologyMetadata.clear(); simpleDatasets.clear(); mainDataset = deviationDataset = extendedDataset = null; simpleDatasetsTextField.clear(); mainDatasetTextField.clear(); deviationDatasetTextField.clear(); extendedDatasetTextField.clear(); resultPackageTextField.clear(); resultOntologyTextField.clear(); deletionStereotypeTextField.clear(); simpleUnionRadio.setSelected(true); targetFormatChoiceBox.setValue(null); baseUriChoiceBox.setValue(BaseUriPresets.OTHER); xmlBaseTextField.clear(); inheritanceOnlyCheckBox.setSelected(false); updateInheritanceOptions(); addOwlCheckBox.setSelected(false); if (mainController != null) mainController.resetProgressBar(); }
}
