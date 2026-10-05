/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.interfaces.ShaclRuleTesterCallback;
import eu.griddigit.cimpal.core.models.ShaclRuleTestOptions;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.RuleResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Verdict;
import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import eu.griddigit.cimpal.core.utils.ShaclRuleTester;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.BaseUriPresets;
import eu.griddigit.cimpal.main.gui.DatatypeMapPresets;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for the <em>Test SHACL rules against Conform / NonConform models</em> section of the
 * Constraints Operations tab.
 * <p>
 * The section tests the rules of a constraint set rather than a dataset: every rule folder of the
 * test suite holds models that must not trigger the rule and models that must, and
 * {@link ShaclRuleTester} reports which rules behaved as their models expect. It was the
 * <em>Validate by manual selection</em> workflow of the SHACL Validation tab, and before that the
 * <em>SHACL tester</em> tab.
 */
public class ShaclRuleTestController {

    private static final Logger LOG = LoggerFactory.getLogger(ShaclRuleTestController.class);

    /** How many failing rules the finish dialog names; the workbook lists all of them. */
    private static final int RULES_NAMED = 10;

    private MainController mainController;

    @FXML
    private TextField tfRuleTestShapeFiles;

    @FXML
    private TextField tfRuleTestSuite;

    @FXML
    private ChoiceBox<String> cbRuleTestDatatypeMap;

    /** Shown only when the datatype map choice is "Other". */
    @FXML
    private TextField tfRuleTestDatatypeMapFile;

    @FXML
    private Button btnBrowseRuleTestDatatypeMapFile;

    @FXML
    private ChoiceBox<String> cbRuleTestBaseUri;

    @FXML
    private TextField tfRuleTestBaseUri;

    @FXML
    private CheckBox cbRuleTestExcelReports;

    @FXML
    private CheckBox cbRuleTestTurtleReports;

    @FXML
    private Button btnRunRuleTest;

    @FXML
    private Label helpRuleTestShapeFiles;

    @FXML
    private Label helpRuleTestSuite;

    @FXML
    private Label helpRuleTestDatatypeMap;

    @FXML
    private Label helpRuleTestBaseUri;

    @FXML
    private Label helpRuleTestReports;

    private List<File> shapeFiles;
    private File suiteFolder;
    private File datatypeMapFile;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private void initialize() {
        DatatypeMapPresets.bind(cbRuleTestDatatypeMap, DatatypeMapPresets.DEFAULT_SELECTION,
                tfRuleTestDatatypeMapFile, btnBrowseRuleTestDatatypeMapFile);
        BaseUriPresets.bind(cbRuleTestBaseUri, tfRuleTestBaseUri, BaseUriPresets.DEFAULT_SELECTION);
        installHelpTooltips();
        restoreRemembered();
    }

    /**
     * Restores what the section was last used with. The constraint files field holds several
     * files, so it is not restored, but its chooser reopens where it was last used: under the
     * SHACL tester's key, so the folder chosen there before survives the move.
     */
    private void restoreRemembered() {
        PathMemory.bind(tfRuleTestSuite, "tab.ruleTest.suiteFolder", folder -> suiteFolder = folder);
        PathMemory.bind(tfRuleTestDatatypeMapFile, "tab.ruleTest.datatypeMap", file -> datatypeMapFile = file);

        restoreChoice(cbRuleTestDatatypeMap, "tab.ruleTest.datatypeMapChoice");
        restoreChoice(cbRuleTestBaseUri, "tab.ruleTest.baseUriChoice");
        String baseUriText = PathMemory.recallValue("tab.ruleTest.baseUriText", null);
        if (baseUriText != null && !baseUriText.isBlank() && BaseUriPresets.OTHER.equals(cbRuleTestBaseUri.getValue())) {
            tfRuleTestBaseUri.setText(baseUriText);
        }
        tfRuleTestBaseUri.textProperty().addListener((obs, oldValue, newValue) ->
                PathMemory.rememberValue("tab.ruleTest.baseUriText", newValue == null ? "" : newValue));
        restoreCheck(cbRuleTestExcelReports, "tab.ruleTest.excelReports");
        restoreCheck(cbRuleTestTurtleReports, "tab.ruleTest.turtleReports");
    }

    private static void restoreChoice(ChoiceBox<String> choice, String key) {
        String saved = PathMemory.recallValue(key, null);
        if (saved != null && choice.getItems().contains(saved)) {
            choice.getSelectionModel().select(saved);
        }
        choice.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
                PathMemory.rememberValue(key, newValue));
    }

    private static void restoreCheck(CheckBox check, String key) {
        String saved = PathMemory.recallValue(key, null);
        if (saved != null) {
            check.setSelected(Boolean.parseBoolean(saved));
        }
        check.selectedProperty().addListener((obs, oldValue, newValue) ->
                PathMemory.rememberValue(key, Boolean.toString(newValue)));
    }

    private void installHelpTooltips() {
        GUIhelper.installHelpTooltip(helpRuleTestShapeFiles,
                "The constraint set whose rules are tested: SHACL files (.ttl, .rdf), or ZIP archives of them. "
                        + "All the files are read as one shapes graph and their owl:imports are followed, so every "
                        + "model is validated against every rule.\n\n"
                        + "A rule is the shape, or shapes, with a given sh:name.");
        GUIhelper.installHelpTooltip(helpRuleTestSuite,
                "The test suite: a folder per rule, named exactly like the rule's sh:name, holding\n"
                        + "  Conform - models that must not trigger the rule\n"
                        + "  NonConform - models that must trigger it\n"
                        + "A model is a ZIP archive of the model's .xml files, for example\n"
                        + "  <suite>\\ACLineSegmentR\\Conform\\TC1_Conform.zip\n\n"
                        + "A rule passes only when it has models in both folders, none of its Conform models "
                        + "triggers it and every NonConform model does. Findings of other rules do not count. A "
                        + "model copied into several rule folders is validated once.\n\n"
                        + "Archives elsewhere in the suite are not tested: they are listed as notes, and a rule "
                        + "whose folder holds one cannot pass.\n\n"
                        + "The results workbook, rule_test_results_<date>.xlsx, is written into this folder.");
        GUIhelper.installHelpTooltip(helpRuleTestDatatypeMap,
                DatatypeMapPresets.HELP + "\n\nMatch it to the CGMES version of the test models: read untyped, a "
                        + "rule on a numeric range or a boolean can fire on a model that conforms to it.");
        GUIhelper.installHelpTooltip(helpRuleTestBaseUri,
                "Base URI used when loading the models' RDF/XML; relative URIs in the models are resolved against it.\n\n"
                        + "Pick the namespace the models are based on from the dropdown and the field is filled and "
                        + "locked. Select Other to type a base URI that is not in the list.");
        GUIhelper.installHelpTooltip(helpRuleTestReports,
                "Writes each model's validation report beside it as <model>_report.xlsx, every finding of every "
                        + "rule, so you can compare what a rule's Conform and NonConform models trigger. Clear it "
                        + "for a faster run; the results workbook is always written.\n\n"
                        + "Turtle (.ttl) reports hold the standard SHACL ValidationReport graph, which the AI Assistant "
                        + "can explain.");
    }

    // ---- browse --------------------------------------------------------------

    @FXML
    private void actionBrowseRuleTestShapeFiles() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false, "SHACL constraint files", List.of("*.ttl", "*.rdf", "*.zip"), "Select the constraint files",
                "tab.shaclTester.shaclFiles");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        shapeFiles = selected;
        tfRuleTestShapeFiles.setText(selected.stream().map(File::toString).collect(Collectors.joining(", ")));
    }

    @FXML
    private void actionBrowseRuleTestSuite() {
        File selected = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                "Select the test suite folder", "tab.ruleTest.suiteFolder");
        if (selected == null) {
            return;
        }
        suiteFolder = selected;
        tfRuleTestSuite.setText(selected.getAbsolutePath());
    }

    /** Selects a user-supplied datatype map. Only reachable while the choice is "Other". */
    @FXML
    private void actionBrowseRuleTestDatatypeMapFile() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true, "Datatype map", List.of("*.properties"), "Select a datatype map (.properties)",
                "tab.ruleTest.datatypeMap");
        if (selected == null || selected.isEmpty() || selected.getFirst() == null) {
            return;
        }
        datatypeMapFile = selected.getFirst();
        tfRuleTestDatatypeMapFile.setText(datatypeMapFile.getAbsolutePath());
    }

    // ---- run -----------------------------------------------------------------

    @FXML
    private void actionResetRuleTest() {
        shapeFiles = null;
        suiteFolder = null;
        datatypeMapFile = null;
        tfRuleTestShapeFiles.clear();
        tfRuleTestSuite.clear();
        tfRuleTestDatatypeMapFile.clear();
        cbRuleTestDatatypeMap.getSelectionModel().select(DatatypeMapPresets.DEFAULT_SELECTION);
        cbRuleTestBaseUri.getSelectionModel().select(BaseUriPresets.DEFAULT_SELECTION);
        cbRuleTestExcelReports.setSelected(true);
        cbRuleTestTurtleReports.setSelected(false);
        resetProgress();
    }

    @FXML
    private void actionRunRuleTest() {
        resetProgress();
        if (!validateInputs()) {
            return;
        }

        // Read on the FX thread, so the choices cannot change under a running test.
        ShaclRuleTestOptions.Builder options = ShaclRuleTestOptions.builder()
                .shapeFiles(shapeFiles.stream().map(File::toPath).toList())
                .suiteFolder(suiteFolder.toPath())
                .xmlBase(tfRuleTestBaseUri.getText().trim())
                .excelReports(cbRuleTestExcelReports.isSelected())
                .turtleReports(cbRuleTestTurtleReports.isSelected());
        DatatypeMapPreset preset = DatatypeMapPresets.presetFor(cbRuleTestDatatypeMap.getValue());
        if (preset == null) {
            options.datatypeMapFile(datatypeMapFile.toPath());
        } else {
            options.datatypeMap(preset);
        }

        btnRunRuleTest.setDisable(true);
        setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        ShaclRuleTesterCallback callback = new ShaclRuleTesterCallback() {
            @Override
            public void updateProgress(double progress) {
                setProgress(progress);
            }

            @Override
            public void appendOutput(String message) {
                if (mainController != null) {
                    mainController.appendText(message);
                }
            }
        };

        new Thread(() -> {
            try {
                ShaclRuleTestReport report = new ShaclRuleTester(options.build(), callback).run();
                String outcome = describe(report);
                boolean passed = report.passed();
                Platform.runLater(() -> {
                    setProgress(1);
                    btnRunRuleTest.setDisable(false);
                    if (passed) {
                        GUIhelper.showInfo("Rule test passed", outcome);
                    } else {
                        GUIhelper.showWarning("Rule test finished", outcome);
                    }
                });
            } catch (OutOfMemoryError ex) {
                LOG.error("SHACL rule test ran out of memory", ex);
                reportFailure("Not enough memory", "CimPal ran out of memory validating the test models. "
                        + "Start CimPal with a larger Java heap (-Xmx), or test fewer rule folders at a time.");
            } catch (Throwable ex) {
                // Errors as well as exceptions end here rather than in the application's handler,
                // which would report them but leave Run disabled.
                LOG.error("SHACL rule test failed", ex);
                reportFailure("Rule test failed", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            }
        }, "shacl-rule-test-runner").start();
    }

    /** The finish dialog's text: the totals, the first failing rules, and where the workbook is. */
    private static String describe(ShaclRuleTestReport report) {
        StringBuilder text = new StringBuilder();
        if (report.getRules().isEmpty()) {
            text.append("No rule folders were found: a rule folder holds a ").append(ShaclRuleTester.CONFORM)
                    .append(" or ").append(ShaclRuleTester.NON_CONFORM).append(" folder.");
        } else {
            text.append(report.count(Verdict.PASS)).append(" of ").append(report.getRules().size())
                    .append(" rule(s) passed, ").append(report.count(Verdict.FAIL)).append(" failed, ")
                    .append(report.count(Verdict.ERROR)).append(" could not be tested. ")
                    .append(report.getValidatedModels()).append(" distinct model(s) validated.");
        }
        List<RuleResult> notPassed = report.getRules().stream().filter(rule -> rule.verdict() != Verdict.PASS).toList();
        if (!notPassed.isEmpty()) {
            text.append("\n\n");
            notPassed.stream().limit(RULES_NAMED).forEach(rule -> text.append(rule.verdict().label()).append(": ")
                    .append(rule.rule()).append(" - ").append(rule.message()).append('\n'));
            if (notPassed.size() > RULES_NAMED) {
                text.append("... and ").append(notPassed.size() - RULES_NAMED).append(" more.\n");
            }
        }
        if (!report.getNotes().isEmpty()) {
            text.append("\n").append(report.getNotes().size())
                    .append(" note(s): models in the suite that were not tested, or reports that could not be"
                            + " written; see the Notes sheet.\n");
        }
        if (!report.getWarnings().isEmpty()) {
            text.append("\nWarnings:\n- ").append(String.join("\n- ", report.getWarnings())).append('\n');
        }
        Path workbook = report.getWorkbook();
        text.append(workbook == null
                ? "\nThe results workbook could not be written; the results are in the Output pane."
                : "\nResults saved to:\n" + workbook);
        return text.toString();
    }

    private boolean validateInputs() {
        if (shapeFiles == null || shapeFiles.isEmpty()) {
            GUIhelper.showWarning("Missing constraint files", "Please select the SHACL constraint files whose rules to test.");
            return false;
        }
        if (suiteFolder == null || !suiteFolder.isDirectory()) {
            GUIhelper.showWarning("Missing test suite folder",
                    "Please select the test suite folder: a folder per rule, each with a "
                            + ShaclRuleTester.CONFORM + " and a " + ShaclRuleTester.NON_CONFORM + " folder of models.");
            return false;
        }
        if (cbRuleTestDatatypeMap.getValue() == null) {
            GUIhelper.showWarning("Missing datatype map", "Please select a datatype map.");
            return false;
        }
        if (DatatypeMapPresets.OTHER.equals(cbRuleTestDatatypeMap.getValue())
                && (datatypeMapFile == null || !datatypeMapFile.isFile())) {
            GUIhelper.showWarning("Missing datatype map file",
                    "Datatype map is set to \"Other\". Please browse for a .properties datatype map.");
            return false;
        }
        if (tfRuleTestBaseUri.getText() == null || tfRuleTestBaseUri.getText().isBlank()) {
            GUIhelper.showWarning("Missing base URI", "Please select or enter a base URI.");
            return false;
        }
        return true;
    }

    private void reportFailure(String title, String message) {
        Platform.runLater(() -> {
            resetProgress();
            btnRunRuleTest.setDisable(false);
            GUIhelper.showError(title, message);
        });
    }

    /** Progress goes to the shared bar in the status line, as it does from every other tab. */
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
