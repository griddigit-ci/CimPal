/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Plan;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.SourceModel;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for the <em>Create a configuration that deactivates replaced constraints</em> section
 * of the Constraints Operations tab.
 * <p>
 * Reads the base and replacing constraint files, lets {@link ShapeDeactivationPlanner} list the base
 * shapes the replacing set takes over, and opens {@link ShaclDeactivationDialog}, where the user
 * confirms the list, adds shapes by hand, sets what each {@code owl:imports} names and saves the
 * configuration.
 */
public class ShaclDeactivationConfigController {

    private static final Logger LOG = LoggerFactory.getLogger(ShaclDeactivationConfigController.class);

    private static final List<String> SHAPE_EXTENSIONS = List.of("*.ttl", "*.rdf", "*.xml");

    private MainController mainController;

    @FXML
    private TextField tfDeactivationBaseFiles;

    @FXML
    private TextField tfDeactivationOverrideFiles;

    @FXML
    private TextField tfDeactivationReferenceFiles;

    @FXML
    private Button btnRunDeactivation;

    @FXML
    private Label helpDeactivationBaseFiles;

    @FXML
    private Label helpDeactivationOverrideFiles;

    @FXML
    private Label helpDeactivationReferenceFiles;

    private List<File> baseFiles;
    private List<File> overrideFiles;
    private List<File> referenceFiles;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private void initialize() {
        installHelpTooltips();
    }

    private void installHelpTooltips() {
        GUIhelper.installHelpTooltip(helpDeactivationBaseFiles,
                "The constraint files the configuration imports and partly switches off, for example the CGMES v3 "
                        + "constraints of the profile (61970-301_Equipment-AP-Con-Complex-SHACL.ttl).\n\n"
                        + "Every shape of these files can be deactivated; the list shows the ones the replacing files "
                        + "appear to take over, and Add constraints offers the rest.");
        GUIhelper.installHelpTooltip(helpDeactivationOverrideFiles,
                "The constraint files that replace part of the base files, for example the header and profile "
                        + "constraints of a national or project extension. The configuration imports them too.\n\n"
                        + "They must use their own IRIs (their own namespaces): a shape that has the same IRI in both sets "
                        + "cannot be deactivated without switching off the replacing shape as well, and is flagged.\n\n"
                        + "A base shape is suggested when a replacing shape has the same rule name, the part of sh:name "
                        + "after the standard and profile (C:301:EQ:Equipment.aggregate:notUsed and "
                        + "C:EXT:EQ:Equipment.aggregate:notUsed), or constrains the same property of the same class "
                        + "with the same kind of parameter.");
        GUIhelper.installHelpTooltip(helpDeactivationReferenceFiles,
                "Optional. Trimmed copies of the base files, such as earlier hand-edited versions, one per base file "
                        + "and in the same namespace as the file it trims.\n\n"
                        + "Shapes a reference no longer has, or has changed, are suggested for deactivation too. Each "
                        + "reference only judges the base shapes in the namespaces it declares shapes in, so a base file "
                        + "without a reference is left alone (a warning names it). Node shapes that only dropped some of "
                        + "their sh:property shapes are listed but not ticked: the dropped property shapes are listed on "
                        + "their own.\n\n"
                        + "Reference files are only compared; the configuration does not import them.");
    }

    // ---- browse --------------------------------------------------------------

    @FXML
    private void actionBrowseDeactivationBaseFiles() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false, "SHACL constraint files", SHAPE_EXTENSIONS, "Select the base constraint files",
                "tab.deactivation.baseFiles");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        baseFiles = new ArrayList<>(selected);
        tfDeactivationBaseFiles.setText(join(baseFiles));
    }

    @FXML
    private void actionBrowseDeactivationOverrideFiles() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false, "SHACL constraint files", SHAPE_EXTENSIONS, "Select the replacing constraint files",
                "tab.deactivation.overrideFiles");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        overrideFiles = new ArrayList<>(selected);
        tfDeactivationOverrideFiles.setText(join(overrideFiles));
    }

    @FXML
    private void actionBrowseDeactivationReferenceFiles() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false, "SHACL constraint files", SHAPE_EXTENSIONS, "Select the reference files (optional)",
                "tab.deactivation.referenceFile");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        referenceFiles = new ArrayList<>(selected);
        tfDeactivationReferenceFiles.setText(join(referenceFiles));
    }

    private static String join(List<File> files) {
        return files.stream().map(File::toString).collect(Collectors.joining(", "));
    }

    // ---- run -----------------------------------------------------------------

    @FXML
    private void actionResetDeactivation() {
        baseFiles = null;
        overrideFiles = null;
        referenceFiles = null;
        tfDeactivationBaseFiles.clear();
        tfDeactivationOverrideFiles.clear();
        tfDeactivationReferenceFiles.clear();
        resetProgress();
    }

    @FXML
    private void actionRunDeactivation() {
        resetProgress();
        if (baseFiles == null || baseFiles.isEmpty()) {
            GUIhelper.showWarning("Missing base constraint files", "Please select the base constraint files, for example the CGMES v3 constraints of the profile.");
            return;
        }
        if (overrideFiles == null || overrideFiles.isEmpty()) {
            GUIhelper.showWarning("Missing replacing constraint files", "Please select the constraint files that replace part of the base files.");
            return;
        }
        for (File file : baseFiles) {
            if (overrideFiles.contains(file)) {
                GUIhelper.showWarning("Same file twice", file.getName() + " is selected both as a base and as a replacing file.");
                return;
            }
        }
        List<File> references = referenceFiles == null ? List.of() : List.copyOf(referenceFiles);
        for (File file : references) {
            if (baseFiles.contains(file) || overrideFiles.contains(file)) {
                GUIhelper.showWarning("Same file twice", file.getName()
                        + " is selected as a reference file and as a base or replacing file.");
                return;
            }
        }

        List<File> base = List.copyOf(baseFiles);
        List<File> overrides = List.copyOf(overrideFiles);
        btnRunDeactivation.setDisable(true);
        setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        new Thread(() -> {
            try {
                List<SourceModel> baseModels = load(base);
                List<SourceModel> overrideModels = load(overrides);
                List<SourceModel> referenceModels = load(references);
                Plan plan = ShapeDeactivationPlanner.plan(baseModels, overrideModels, referenceModels);
                appendOutput("Deactivation analysis: " + plan.baseShapes().size() + " base shape(s), "
                        + plan.candidates().size() + " listed, "
                        + plan.candidates().stream().filter(ShapeDeactivationPlanner.Candidate::suggested).count()
                        + " suggested.\n");
                Platform.runLater(() -> {
                    setProgress(1);
                    btnRunDeactivation.setDisable(false);
                    new ShaclDeactivationDialog(plan, base, overrides, baseModels, this::appendOutput).showAndWait();
                });
            } catch (Throwable ex) {
                LOG.error("Deactivation analysis failed", ex);
                String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    resetProgress();
                    btnRunDeactivation.setDisable(false);
                    GUIhelper.showError("Analysis failed", message);
                });
            }
        }, "shacl-deactivation-analysis").start();
    }

    /** Reads each file; it is named by its file name, or by its full path when two files share a name. */
    private static List<SourceModel> load(List<File> files) {
        List<SourceModel> models = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> repeated = new HashSet<>();
        files.forEach(file -> {
            if (!names.add(file.getName())) {
                repeated.add(file.getName());
            }
        });
        for (File file : files) {
            Model model;
            try {
                model = RDFDataMgr.loadModel(file.getAbsolutePath());
            } catch (RuntimeException ex) {
                throw new IllegalStateException(file.getName() + " could not be read: " + ex.getMessage(), ex);
            }
            models.add(new SourceModel(repeated.contains(file.getName()) ? file.getAbsolutePath() : file.getName(), model));
        }
        return models;
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
