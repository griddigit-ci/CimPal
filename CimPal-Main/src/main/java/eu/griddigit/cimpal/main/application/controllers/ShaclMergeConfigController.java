/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.shacl_tools.ShapeConfigurationMerger;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.GuiDialogs;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Controller for the <em>Merge a configuration into one constraint file</em> section of the
 * Constraints Operations tab: reads a configuration with {@link ShapeConfigurationMerger} and writes
 * its imported files into one file, without the shapes it deactivates.
 */
public class ShaclMergeConfigController {

    private static final Logger LOG = LoggerFactory.getLogger(ShaclMergeConfigController.class);

    private MainController mainController;

    @FXML
    private TextField tfMergeConfigFile;

    @FXML
    private TextField tfMergeOutputFile;

    @FXML
    private TextField tfMergeBase;

    @FXML
    private Button btnRunMerge;

    @FXML
    private Label helpMergeBase;

    @FXML
    private Label helpMergeConfigFile;

    @FXML
    private Label helpMergeOutputFile;

    private File configFile;
    private File outputFile;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private void initialize() {
        GUIhelper.installHelpTooltip(helpMergeConfigFile,
                "A configuration .ttl, such as one saved by \"Create a configuration that deactivates replaced "
                        + "constraints\": an owl:Ontology with owl:imports, and the shapes it switches off with "
                        + "sh:deactivated true.\n\n"
                        + "Its imports are read the way validation reads them, with their own imports: local files, "
                        + "and web URLs ending in an RDF file extension such as .ttl. An import that cannot be read "
                        + "stops the merge, so the merged file is never missing a file.");
        GUIhelper.installHelpTooltip(helpMergeOutputFile,
                "Where to write the merged file. Left empty, it goes next to the configuration as "
                        + "<name>-merged.ttl.\n\n"
                        + "The merged file holds the imported files without the deactivated shapes, their SPARQL "
                        + "constraints and the sh:property references to them. A deactivated shape that another "
                        + "shape uses in another way (sh:node, sh:or, ...) is kept with sh:deactivated true, and "
                        + "the output says so. The configuration's header is replaced by one for the merged file.");
        GUIhelper.installHelpTooltip(helpMergeBase,
                "Optional. An absolute IRI written as the merged file's @base line, for example "
                        + "http://example.com/ns/Equipment/Constraints. It only changes how the file is written "
                        + "(IRIs under it may be written relative to it), not what it says. Left empty, the file "
                        + "has no @base.");
        tfMergeBase.setText(PathMemory.recallValue("tab.merge.base", ""));
    }

    @FXML
    private void actionBrowseMergeConfigFile() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true, "Configuration", List.of("*.ttl"), "Select the configuration", "tab.merge.configFile");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        configFile = selected.getFirst();
        tfMergeConfigFile.setText(configFile.toString());
    }

    @FXML
    private void actionBrowseMergeOutputFile() {
        String suggested = configFile == null ? "Configuration-merged.ttl"
                : ShaclDeactivationDialog.mergedFileFor(configFile.toPath()).getFileName().toString();
        File selected = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom("Turtle", List.of("*.ttl"),
                "Save the merged file", suggested, "tab.merge.outputFile");
        if (selected == null) {
            return;
        }
        outputFile = selected;
        tfMergeOutputFile.setText(outputFile.toString());
    }

    @FXML
    private void actionResetMerge() {
        configFile = null;
        outputFile = null;
        tfMergeConfigFile.clear();
        tfMergeOutputFile.clear();
        tfMergeBase.clear();
        resetProgress();
    }

    @FXML
    private void actionRunMerge() {
        resetProgress();
        if (configFile == null) {
            GUIhelper.showWarning("Missing configuration", "Please select the configuration to merge.");
            return;
        }
        String base = tfMergeBase.getText() == null ? "" : tfMergeBase.getText().trim();
        String baseProblem = base.isEmpty() ? null : ShapeDeactivationConfig.importProblem(base);
        if (baseProblem != null) {
            GUIhelper.showWarning("Invalid @base", "The @base " + baseProblem + ".");
            return;
        }
        Path config = configFile.toPath();
        Path target = outputFile != null ? outputFile.toPath() : ShaclDeactivationDialog.mergedFileFor(config);
        // Compared as files, not as path text: another spelling, case or link of the configuration
        // is caught too. The files it imports are checked when the merged file is written.
        if (ShaclDeactivationDialog.isInputFile(target, List.of(config))) {
            GUIhelper.showWarning("Same file twice", "The merged file cannot replace the configuration.");
            return;
        }
        PathMemory.rememberValue("tab.merge.base", base);
        // A file picked with Browse was already confirmed by the save dialog.
        if (outputFile == null && Files.exists(target) && !confirm(target)) {
            return;
        }

        btnRunMerge.setDisable(true);
        setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        new Thread(() -> {
            try {
                ShapeConfigurationMerger.Result result = ShapeConfigurationMerger.mergeConfigurationWithBase(config, base);
                ShapeConfigurationMerger.write(result, target);
                appendOutput(ShaclDeactivationDialog.describeMerge(result, target));
                Platform.runLater(() -> {
                    setProgress(1);
                    btnRunMerge.setDisable(false);
                    GUIhelper.showInfo("Merged file saved", result.documents().size() + " file(s) merged, "
                            + result.removedShapes() + " deactivated shape(s) removed.\n\nSaved to:\n" + target
                            + ShaclDeactivationDialog.mergeWarnings(result));
                });
            } catch (Throwable ex) {
                LOG.error("Merging the configuration failed", ex);
                String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    resetProgress();
                    btnRunMerge.setDisable(false);
                    GUIhelper.showError("Merge failed", message);
                });
            }
        }, "shacl-configuration-merge").start();
    }

    private static boolean confirm(Path target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, target.getFileName()
                + " already exists next to the configuration. Replace it?", ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Replace the merged file?");
        alert.setHeaderText("Replace the merged file?");
        return GuiDialogs.confirm(alert);
    }

    private void appendOutput(String message) {
        if (mainController != null) {
            Platform.runLater(() -> mainController.appendText(message));
        }
    }

    private void setProgress(double progress) {
        if (mainController != null) {
            mainController.setProgressBarValue(progress);
        }
    }

    private void resetProgress() {
        if (mainController != null) {
            mainController.resetProgressBar();
        }
    }
}
