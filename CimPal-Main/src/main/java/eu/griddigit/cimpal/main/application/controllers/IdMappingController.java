/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.idmapping.IdMapper;
import eu.griddigit.cimpal.core.idmapping.IdMappingOptions;
import eu.griddigit.cimpal.core.idmapping.IdMappingResult;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The Dataset ▸ ID Mapping tab: maps the IDs of one model onto another with {@link IdMapper} and
 * writes the mapping workbook. The work runs on a background thread; progress messages go to the
 * output pane.
 */
public class IdMappingController implements Initializable {

    private static final Logger LOG = LoggerFactory.getLogger(IdMappingController.class);
    private static final List<String> MODEL_EXTENSIONS = List.of("*.xml", "*.rdf", "*.zip");

    private MainController mainController;
    private List<File> model1Files;
    private List<File> model2Files;
    private List<File> commonFiles;
    private File aliasFile;
    private File outputFile;

    @FXML private TextField tfModel1Files;
    @FXML private TextField tfModel2Files;
    @FXML private TextField tfCommonFiles;
    @FXML private TextField tfAliases;
    @FXML private TextField tfOutput;
    @FXML private CheckBox cbUseNames;
    @FXML private CheckBox cbSameIds;
    @FXML private CheckBox cbJunctions;
    @FXML private CheckBox cbGroupStations;
    @FXML private CheckBox cbTerminals;
    @FXML private CheckBox cbNodes;
    @FXML private CheckBox cbDependents;
    @FXML private CheckBox cbOthers;
    @FXML private TextField tfParameterTolerance;
    @FXML private TextField tfVoltageTolerance;
    @FXML private ChoiceBox<String> cbSecondName;
    @FXML private Button btnRun;
    @FXML private Label helpModel1Files;
    @FXML private Label helpModel2Files;
    @FXML private Label helpCommonFiles;
    @FXML private Label helpAliases;
    @FXML private Label helpOutput;
    @FXML private Label helpMatching;
    @FXML private Label helpReported;
    @FXML private Label helpTolerances;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        cbSecondName.getItems().addAll("name", "shortName", "description");
        cbSecondName.getSelectionModel().select(0);
        GUIhelper.installHelpTooltip(helpModel1Files, "The model whose IDs are mapped; its IDs fill the Model1_ID column. "
                + "Select one or more files (e.g. two EQ files); they are read as one model. "
                + "RDF/XML files or ZIP archives of them.");
        GUIhelper.installHelpTooltip(helpModel2Files, "The model the IDs are looked up in; its IDs fill Model2_ID. "
                + "One or more files, read as one model.");
        GUIhelper.installHelpTooltip(helpCommonFiles, "Files both models depend on and that are not mapped: the "
                + "boundary set (boundary nodes) and the common data or EQ boundary with the BaseVoltages. They are "
                + "loaded into both sides; boundary nodes then anchor the tie lines, and the voltages become known.");
        GUIhelper.installHelpTooltip(helpAliases, "Optional CSV for substations named differently in the two "
                + "models, e.g. a station code in one and the full name in the other. One substation per line, its "
                + "names separated by ; or , (a header line does no harm).");
        GUIhelper.installHelpTooltip(helpOutput, "The workbook to write. Matched: Model1_ID, Element_type, Model2_ID, "
                + "Model2_2nd_name, then Confidence, Model1_name, Model2_type, Method and Note. Unmatched: what is left for a person, "
                + "with the reason and candidates. Summary: counts per class and method.");
        GUIhelper.installHelpTooltip(helpMatching, "How matching starts:\n\n"
                + "• Same name: substations whose names are unique and equal on both sides seed the matching.\n"
                + "• Same ID: objects with the same ID and class are matched first.\n"
                + "• Line junctions: a substation that only joins line sections (line ends, switches and at most "
                + "one busbar section, e.g. a PowerFactory Term_ point or a cable sealing end) is treated as part "
                + "of the line, so a line split there still matches.\n"
                + "• One station: substations joined by a transformer or switch count as one station, for a model "
                + "that keeps each voltage level of a station as a substation of its own.");
        GUIhelper.installHelpTooltip(helpReported, "Which objects are mapped and reported besides substations, "
                + "lines and equipment. Leave them on to map every ID the topology determines.");
        GUIhelper.installHelpTooltip(helpTolerances, "Line parameters (r, x, bch, length) agree within this "
                + "relative tolerance; voltages within the kV tolerance. They tell parallel lines apart and confirm "
                + "matches; a difference is reported in the Note column, not a reason to reject a line that the "
                + "topology identifies.\n\nModel2_2nd_name: which name of the model 2 object to report.");
    }

    // ---- browse --------------------------------------------------------------------------------

    @FXML
    private void actionBrowseModel1() {
        List<File> selected = ModelFactory.fileChooserCustom(false, "Model files", MODEL_EXTENSIONS,
                "Select model 1 files", "tab.idMapping.model1");
        if (selected != null && !selected.isEmpty()) {
            model1Files = selected;
            tfModel1Files.setText(names(selected));
        }
    }

    @FXML
    private void actionBrowseModel2() {
        List<File> selected = ModelFactory.fileChooserCustom(false, "Model files", MODEL_EXTENSIONS,
                "Select model 2 files", "tab.idMapping.model2");
        if (selected != null && !selected.isEmpty()) {
            model2Files = selected;
            tfModel2Files.setText(names(selected));
        }
    }

    @FXML
    private void actionBrowseCommon() {
        List<File> selected = ModelFactory.fileChooserCustom(false, "Model files", MODEL_EXTENSIONS,
                "Select the boundary and common data files", "tab.idMapping.common");
        if (selected != null && !selected.isEmpty()) {
            commonFiles = selected;
            tfCommonFiles.setText(names(selected));
        }
    }

    @FXML
    private void actionBrowseAliases() {
        List<File> selected = ModelFactory.fileChooserCustom(true, "Name aliases", List.of("*.csv", "*.txt"),
                "Select the substation name aliases", "tab.idMapping.aliases");
        if (selected != null && !selected.isEmpty() && selected.getFirst() != null) {
            aliasFile = selected.getFirst();
            tfAliases.setText(aliasFile.getAbsolutePath());
        }
    }

    @FXML
    private void actionBrowseOutput() {
        File selected = ModelFactory.fileSaveCustom("Excel workbook", List.of("*.xlsx", "*.xls"),
                "Save the ID mapping as", "id_mapping.xlsx", "tab.idMapping.output");
        if (selected != null) {
            outputFile = selected;
            tfOutput.setText(selected.getAbsolutePath());
        }
    }

    // ---- run -----------------------------------------------------------------------------------

    @FXML
    private void actionReset() {
        model1Files = null;
        model2Files = null;
        commonFiles = null;
        aliasFile = null;
        outputFile = null;
        for (TextField field : List.of(tfModel1Files, tfModel2Files, tfCommonFiles, tfAliases, tfOutput)) {
            field.clear();
        }
        for (CheckBox box : List.of(cbUseNames, cbSameIds, cbJunctions, cbGroupStations, cbTerminals, cbNodes, cbDependents, cbOthers)) {
            box.setSelected(true);
        }
        tfParameterTolerance.setText("5");
        tfVoltageTolerance.setText("1");
        cbSecondName.getSelectionModel().select(0);
        if (mainController != null) {
            mainController.resetProgressBar();
        }
    }

    @FXML
    private void actionRun() {
        IdMappingOptions options = readOptions();
        if (options == null) {
            return;
        }
        File output = outputFile;
        btnRun.setDisable(true);
        setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendText("ID mapping started.\n");
        new Thread(() -> {
            try {
                IdMappingResult result = new IdMapper(options, message -> appendText(message + "\n")).run();
                result.writeExcel(output.toPath());
                String summary = describe(result, output);
                Platform.runLater(() -> {
                    btnRun.setDisable(false);
                    if (mainController != null) {
                        mainController.completeProgressBar();
                    }
                    appendText(summary + "\n");
                    GUIhelper.showInfo("ID mapping finished", summary);
                });
            } catch (OutOfMemoryError ex) {
                LOG.error("ID mapping ran out of memory", ex);
                reportFailure("Not enough memory", "CimPal ran out of memory loading the models. "
                        + "Start CimPal with a larger Java heap (-Xmx).");
            } catch (Throwable ex) {
                // Errors as well as exceptions end here, so the Run button is enabled again.
                LOG.error("ID mapping failed", ex);
                reportFailure("ID mapping failed", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            }
        }, "id-mapping-runner").start();
    }

    /** The options from the form, or null after telling the user what is missing. */
    private IdMappingOptions readOptions() {
        if (model1Files == null || model1Files.isEmpty()) {
            GUIhelper.showWarning("Missing model 1", "Please select the files of model 1.");
            return null;
        }
        if (model2Files == null || model2Files.isEmpty()) {
            GUIhelper.showWarning("Missing model 2", "Please select the files of model 2.");
            return null;
        }
        if (outputFile == null) {
            GUIhelper.showWarning("Missing output", "Please choose where to save the mapping workbook.");
            return null;
        }
        Double parameterTolerance = number(tfParameterTolerance.getText());
        Double voltageTolerance = number(tfVoltageTolerance.getText());
        if (parameterTolerance == null || parameterTolerance < 0 || voltageTolerance == null || voltageTolerance < 0) {
            GUIhelper.showWarning("Invalid tolerance", "The tolerances must be zero or positive numbers.");
            return null;
        }
        IdMappingOptions.Builder b = IdMappingOptions.builder()
                .model1Files(paths(model1Files))
                .model2Files(paths(model2Files))
                .commonFiles(commonFiles == null ? List.of() : paths(commonFiles))
                .useNames(cbUseNames.isSelected())
                .matchSameIds(cbSameIds.isSelected())
                .collapsePassThroughSubstations(cbJunctions.isSelected())
                .groupStations(cbGroupStations.isSelected())
                .includeTerminals(cbTerminals.isSelected())
                .includeNodes(cbNodes.isSelected())
                .includeDependents(cbDependents.isSelected())
                .includeOthers(cbOthers.isSelected())
                .parameterTolerance(parameterTolerance / 100.0)
                .voltageTolerance(voltageTolerance)
                .secondName(switch (cbSecondName.getValue()) {
                    case "shortName" -> IdMappingOptions.SecondName.SHORT_NAME;
                    case "description" -> IdMappingOptions.SecondName.DESCRIPTION;
                    default -> IdMappingOptions.SecondName.NAME;
                });
        if (aliasFile != null) {
            b.nameAliasFile(aliasFile.toPath());
        }
        return b.build();
    }

    private static String describe(IdMappingResult result, File output) {
        StringBuilder text = new StringBuilder("Classes in both models:\n");
        text.append(overview("Model 1", result.getModel1Overview()));
        text.append(overview("Model 2", result.getModel2Overview()));
        text.append("Probable matches: check them on the Matched sheet (Confidence column, see the Note).\n");
        Map<String, Integer> methods = new TreeMap<>(result.getMethodCounts());
        text.append("By method: ").append(methods.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append('\n');
        text.append("Workbook: ").append(output.getAbsolutePath());
        return text.toString();
    }

    private static String overview(String label, IdMappingResult.Overview o) {
        return String.format(Locale.ROOT, "%s: %d objects, certain %.1f%%, probable %.1f%%, not matched %.1f%%%n",
                label, o.objects(), o.percent(o.certain()), o.percent(o.probable()), o.percent(o.unmatched()));
    }

    private void reportFailure(String title, String message) {
        Platform.runLater(() -> {
            btnRun.setDisable(false);
            if (mainController != null) {
                mainController.resetProgressBar();
            }
            GUIhelper.showError(title, message);
        });
    }

    private void setProgress(double progress) {
        if (mainController != null) {
            mainController.setProgressBarValue(progress);
        }
    }

    private void appendText(String text) {
        if (mainController != null) {
            mainController.appendText(text);
        }
    }

    private static Double number(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }

    private static List<Path> paths(List<File> files) {
        return files.stream().map(File::toPath).toList();
    }

    private static String names(List<File> files) {
        return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "));
    }
}
