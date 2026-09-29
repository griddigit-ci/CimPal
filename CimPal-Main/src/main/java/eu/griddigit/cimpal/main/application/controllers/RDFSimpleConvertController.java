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
import eu.griddigit.cimpal.main.gui.RdfLoadingPerformance;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.application.Platform;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFWriterRegistry;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

import static eu.griddigit.cimpal.main.core.RdfConvert.fileSaveDialog;

/** Pure RDF conversion. RDFS union and transformation operations live in RDFS Union. */
public class RDFSimpleConvertController implements Initializable {
    private static final List<String> SOURCE_FILTERS = List.of("*.rdf", "*.xml", "*.owl", "*.ttl", "*.nt", "*.n3", "*.trig", "*.trix", "*.jsonld", "*.json");
    private MainController mainController;
    private final List<File> sourceFiles = new ArrayList<>();
    private final Map<String, TargetFormat> cimXmlTargets = new LinkedHashMap<>();
    private final Map<String, List<RDFFormat>> jenaTargetFamilies = new LinkedHashMap<>();
    private final Map<String, RDFFormat> targetVariants = new LinkedHashMap<>();
    private String selectedVariantFamily;
    @FXML private TextField fsourcePathTextField, frdfConvertXmlBase, fRDFconvertTab;
    @FXML private ChoiceBox<String> ftargetFormatChoiceBox, targetVariantChoiceBox, fcbBaseUri, fcbRelativeURIs;
    @FXML private CheckBox fcbShowXMLDeclaration, fcbShowDoctypeDeclaration, fcbSortRDF, fcbStripPrefixes, fcbRDFconvertInstanceData;
    @FXML private ChoiceBox<String> fcbRDFsortOptions;
    @FXML private TitledPane jenaOptionsPane, jsonLdOptionsPane;
    @FXML private TextField jsonLdContextTextField;
    @FXML private CheckBox jsonLdNativeTypesCheckBox, jsonLdRdfTypeCheckBox, jsonLdCompactArraysCheckBox, jsonLdOrderedCheckBox;
    @FXML private Label helpSource, helpSourceType, helpTargetFormat, helpXmlBase, helpSortingOptions;

    @Override public void initialize(URL location, ResourceBundle resources) {
        loadTargetFormats();
        fcbRelativeURIs.getItems().addAll("same-document", "network", "absolute", "relative", "parent", "grandparent");
        fcbRDFsortOptions.getItems().addAll("Sorting by local name", "Sorting by prefix");
        BaseUriPresets.bind(fcbBaseUri, frdfConvertXmlBase, BaseUriPresets.OTHER);
        ftargetFormatChoiceBox.getSelectionModel().selectedItemProperty().addListener((o, oldValue, value) -> updateTargetOptions());
        targetVariantChoiceBox.getSelectionModel().selectedItemProperty().addListener((o, oldValue, value) -> updateTargetOptions());
        GUIhelper.installHelpTooltip(helpSource, "Select one or more RDF files. Jena detects each source syntax from its content and extension.");
        GUIhelper.installHelpTooltip(helpSourceType, "Use this only with CimPal's IEC 61970-552 / CGMES CIMXML writer. It applies CIM instance-data identifier and ordering rules.");
        GUIhelper.installHelpTooltip(helpTargetFormat, "Select a format family. Jena writer styles, such as pretty, plain and flat JSON-LD, are selected in the options below.");
        GUIhelper.installHelpTooltip(helpXmlBase, "Base URI for writers that support it. Leave empty to omit it.");
        GUIhelper.installHelpTooltip(helpSortingOptions, "Sorting is currently implemented by CimPal's custom CIMXML writers. Other Jena writers retain their own serialization order.");
        updateTargetOptions();
    }

    private void loadTargetFormats() {
        addCimXmlTarget(new TargetFormat("CIMXML / IEC 61970-552 (RDF/XML)", ".rdf", null, CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY));
        addCimXmlTarget(new TargetFormat("RDFS CIMXML / IEC 61970-501 (RDF/XML)", ".rdf", null, CustomRDFFormat.RDFXML_CUSTOM_PLAIN));
        RDFWriterRegistry.registeredGraphFormats().stream()
                .filter(format -> format != RDFFormat.RDFNULL && format != RDFFormat.RDFRAW)
                .collect(java.util.stream.Collectors.groupingBy(format -> "Jena: " + format.getLang().getLabel(), TreeMap::new, java.util.stream.Collectors.toList()))
                .forEach((family, formats) -> { jenaTargetFamilies.put(family, formats); ftargetFormatChoiceBox.getItems().add(family); });
    }
    private void addCimXmlTarget(TargetFormat target) { cimXmlTargets.put(target.label(), target); ftargetFormatChoiceBox.getItems().add(target.label()); }
    private String extension(RDFFormat format) { List<String> extensions = format.getLang().getFileExtensions(); return extensions.isEmpty() ? ".rdf" : "." + extensions.getFirst(); }
    private TargetFormat selectedTarget() {
        TargetFormat cimXml = cimXmlTargets.get(ftargetFormatChoiceBox.getValue());
        if (cimXml != null) return cimXml;
        RDFFormat jena = targetVariants.get(targetVariantChoiceBox.getValue());
        return jena == null ? null : new TargetFormat(ftargetFormatChoiceBox.getValue(), extension(jena), jena, null);
    }
    private void populateVariantOptions() {
        selectedVariantFamily = ftargetFormatChoiceBox.getValue();
        targetVariants.clear(); targetVariantChoiceBox.getItems().clear(); targetVariantChoiceBox.setValue(null);
        List<RDFFormat> formats = jenaTargetFamilies.get(ftargetFormatChoiceBox.getValue());
        if (formats == null) return;
        formats.stream().sorted(Comparator.comparing(this::variantLabel)).forEach(format -> {
            String label = variantLabel(format); targetVariants.put(label, format); targetVariantChoiceBox.getItems().add(label);
        });
        RDFFormat preferred = formats.stream().filter(format -> RDFFormat.PRETTY.equals(format.getVariant())).findFirst().orElse(formats.getFirst());
        targetVariantChoiceBox.setValue(variantLabel(preferred));
    }
    private String variantLabel(RDFFormat format) { return format.getVariant() == null ? "Default" : format.getVariant().toString(); }

    public void setMainController(MainController mainController) { this.mainController = mainController; }
    private void resetProgressBar() { if (mainController != null) mainController.resetProgressBar(); }

    @FXML private void actionBrowseRDFConvert() {
        List<File> files = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF files to convert", SOURCE_FILTERS, "", "tab.rdfConvert.source");
        sourceFiles.clear(); if (files != null) sourceFiles.addAll(files);
        fsourcePathTextField.setText(sourceFiles.isEmpty() ? "" : sourceFiles.size() == 1 ? sourceFiles.getFirst().toString() : sourceFiles.size() + " files selected");
        fsourcePathTextField.setTooltip(sourceFiles.size() > 1 ? new Tooltip(sourceFiles.stream().map(File::getAbsolutePath).reduce((a, b) -> a + "\n" + b).orElse("")) : null);
    }

    @FXML private void actionBtnRunRDFConvert() throws IOException {
        TargetFormat target = selectedTarget();
        if (sourceFiles.isEmpty() || target == null) throw new IllegalStateException("Select at least one source file and a target format.");
        if (sourceFiles.size() == 1) {
            OutputStream output = fileSaveDialog("Save converted RDF", target.label(), "*" + target.extension());
            if (output != null) convert(sourceFiles.getFirst(), target, output);
        } else {
            File folder = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom("Select output folder", "tab.rdfConvert.output");
            if (folder == null) return;
            Set<String> usedNames = new HashSet<>();
            List<ConversionJob> jobs = new ArrayList<>();
            for (File source : sourceFiles) jobs.add(new ConversionJob(source, uniqueOutput(folder, baseName(source.getName()) + target.extension(), usedNames)));
            convertBatch(jobs, target, settings());
            return;
        }
        if (mainController != null) mainController.setProgressBarValue(1);
    }

    private void convert(File source, TargetFormat target, OutputStream output) throws IOException {
        convert(source, target, output, settings());
    }

    private void convert(File source, TargetFormat target, OutputStream output, ConversionSettings settings) throws IOException {
        RDFConvertOptions.Builder builder = RDFConvertOptions.builder().sourceFile(source).sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.RDFXML).xmlBase(settings.base())
                .showXmlDeclaration(Boolean.toString(settings.showXmlDeclaration())).showDoctypeDeclaration(Boolean.toString(settings.showDoctypeDeclaration())).tabCharacter(settings.tab())
                .relativeURIs(settings.relativeUris()).sortRDF(Boolean.toString(settings.sort())).rdfSortOptions(Boolean.toString(settings.sortByPrefix()))
                .stripPrefixes(settings.stripPrefixes()).convertInstanceData(Boolean.toString(settings.instanceData()))
                .jsonLdContext(settings.jsonLdContext()).jsonLdUseNativeTypes(settings.jsonLdNativeTypes()).jsonLdUseRdfType(settings.jsonLdRdfType())
                .jsonLdCompactArrays(settings.jsonLdCompactArrays()).jsonLdOrdered(settings.jsonLdOrdered());
        if (target.jenaFormat() != null) builder.jenaTargetFormat(target.jenaFormat()).rdfXmlFormat(RDFFormat.RDFXML_PLAIN); else builder.rdfXmlFormat(target.cimXmlFormat());
        RDFConverter converter = new RDFConverter(builder.build()); converter.convert(); converter.writeConvertedModel(output);
    }

    private ConversionSettings settings() {
        return new ConversionSettings(frdfConvertXmlBase.getText().isBlank() ? "" : frdfConvertXmlBase.getText(), fcbShowXMLDeclaration.isSelected(), fcbShowDoctypeDeclaration.isSelected(), fRDFconvertTab.getText(), fcbRelativeURIs.getValue() == null ? "" : fcbRelativeURIs.getValue(), fcbSortRDF.isSelected(), "Sorting by prefix".equals(fcbRDFsortOptions.getValue()), fcbStripPrefixes.isSelected(), fcbRDFconvertInstanceData.isSelected(), jsonLdContextTextField.getText(), jsonLdNativeTypesCheckBox.isSelected(), jsonLdRdfTypeCheckBox.isSelected(), jsonLdCompactArraysCheckBox.isSelected(), jsonLdOrderedCheckBox.isSelected());
    }

    private void convertBatch(List<ConversionJob> jobs, TargetFormat target, ConversionSettings settings) {
        int workers = RdfLoadingPerformance.workerCount(jobs.size());
        Thread batchThread = new Thread(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (ConversionJob job : jobs) futures.add(executor.submit(() -> { try (OutputStream output = new FileOutputStream(job.output())) { convert(job.source(), target, output, settings); } return null; }));
                for (Future<?> future : futures) future.get();
                Platform.runLater(() -> { if (mainController != null) mainController.setProgressBarValue(1); });
            } catch (Exception e) {
                Platform.runLater(() -> GUIhelper.showUserFriendlyError("Batch conversion failed", "One or more RDF files could not be converted. Review the technical details.", e));
            } finally {
                executor.shutdown();
            }
        }, "rdf-convert-batch");
        batchThread.setDaemon(true);
        batchThread.start();
    }

    private File uniqueOutput(File folder, String requestedName, Set<String> usedNames) {
        String stem = baseName(requestedName), extension = requestedName.substring(stem.length()), candidate = requestedName; int index = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT)) || new File(folder, candidate).exists()) candidate = stem + "-" + index++ + extension;
        return new File(folder, candidate);
    }
    private String baseName(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    @FXML private void actionBrtResetRDFConvert() { sourceFiles.clear(); fsourcePathTextField.clear(); fsourcePathTextField.setTooltip(null); ftargetFormatChoiceBox.setValue(null); fcbBaseUri.setValue(BaseUriPresets.OTHER); frdfConvertXmlBase.clear(); fcbRDFconvertInstanceData.setSelected(false); jsonLdContextTextField.clear(); jsonLdNativeTypesCheckBox.setSelected(false); jsonLdRdfTypeCheckBox.setSelected(false); jsonLdCompactArraysCheckBox.setSelected(true); jsonLdOrderedCheckBox.setSelected(false); resetProgressBar(); updateTargetOptions(); }
    private void updateTargetOptions() {
        if (!Objects.equals(selectedVariantFamily, ftargetFormatChoiceBox.getValue())) populateVariantOptions();
        TargetFormat target = selectedTarget(); boolean cimXml = target != null && target.cimXmlFormat() != null;
        boolean jsonLd = target != null && (target.jenaFormat() != null && (target.jenaFormat().getLang().equals(org.apache.jena.riot.Lang.JSONLD) || target.jenaFormat().getLang().equals(org.apache.jena.riot.Lang.JSONLD11)));
        boolean jena = target != null && target.jenaFormat() != null;
        jenaOptionsPane.setManaged(jena); jenaOptionsPane.setVisible(jena);
        jsonLdOptionsPane.setManaged(jsonLd); jsonLdOptionsPane.setVisible(jsonLd);
        fcbShowXMLDeclaration.setDisable(!cimXml); fcbShowDoctypeDeclaration.setDisable(!cimXml); fRDFconvertTab.setDisable(!cimXml); fcbRelativeURIs.setDisable(!cimXml); fcbSortRDF.setDisable(!cimXml); fcbRDFsortOptions.setDisable(!cimXml); fcbStripPrefixes.setDisable(!cimXml); fcbRDFconvertInstanceData.setDisable(!cimXml);
        if (cimXml) { fcbRelativeURIs.setValue("same-document"); fcbRDFsortOptions.setValue("Sorting by local name"); }
    }
    private record TargetFormat(String label, String extension, RDFFormat jenaFormat, RDFFormat cimXmlFormat) { }
    private record ConversionJob(File source, File output) { }
    private record ConversionSettings(String base, boolean showXmlDeclaration, boolean showDoctypeDeclaration, String tab, String relativeUris, boolean sort, boolean sortByPrefix, boolean stripPrefixes, boolean instanceData, String jsonLdContext, boolean jsonLdNativeTypes, boolean jsonLdRdfType, boolean jsonLdCompactArrays, boolean jsonLdOrdered) { }
}
