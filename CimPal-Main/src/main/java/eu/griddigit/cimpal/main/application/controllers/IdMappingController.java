/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.matching.IdMappingService;
import eu.griddigit.cimpal.core.matching.model.MatchingConfig;
import eu.griddigit.cimpal.main.util.MatchingConfigLoader;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the "ID Mapping / Model Matching" tab. Collects the PF EQ files
 * (EirGrid + SONI, unioned), the IGMS EQ file(s), an optional boundary set, an
 * optional external query folder and JSON config, and an output workbook path;
 * then runs {@link IdMappingService} on a background thread and reports progress.
 *
 * <p>Self-contained (own progress bar + status label), mirroring the
 * ValidationByMapping tab. All matching logic lives in CimPal-Core; this class
 * only wires the UI.</p>
 */
public class IdMappingController {

    @FXML private TextField tfPfFiles;
    @FXML private TextField tfIgmsFiles;
    @FXML private TextField tfBoundaryFiles;
    @FXML private TextField tfQueryFolder;
    @FXML private TextField tfConfigFile;
    @FXML private TextField tfOutputFile;
    @FXML private ChoiceBox<String> cbSecondName;
    @FXML private ProgressBar pbIdMapping;
    @FXML private Label lblStatus;
    @FXML private Button btnRunIdMapping;

    private final List<File> pfFiles = new ArrayList<>();
    private final List<File> igmsFiles = new ArrayList<>();
    private final List<File> boundaryFiles = new ArrayList<>();
    private File queryFolder;
    private File configFile;
    private File outputFile;

    @FXML
    private void initialize() {
        cbSecondName.getItems().setAll("name", "aliasName", "description");
        cbSecondName.getSelectionModel().select("name");
        pbIdMapping.setProgress(0);
        setStatus("Idle.");
    }

    // ---- browse handlers ----

    @FXML
    private void actionBrowsePf() {
        List<File> sel = ModelFactory.fileChooserCustom(false, "PF EQ files (EirGrid + SONI)",
                List.of("*.xml", "*.zip", "*.rdf"), "Select PF EQ files");
        if (sel == null || sel.isEmpty()) return;
        pfFiles.clear();
        pfFiles.addAll(sel);
        tfPfFiles.setText(joinPaths(pfFiles));
    }

    @FXML
    private void actionBrowseIgms() {
        List<File> sel = ModelFactory.fileChooserCustom(false, "IGMS EQ file(s)",
                List.of("*.xml", "*.zip", "*.rdf"), "Select IGMS EQ file(s)");
        if (sel == null || sel.isEmpty()) return;
        igmsFiles.clear();
        igmsFiles.addAll(sel);
        tfIgmsFiles.setText(joinPaths(igmsFiles));
    }

    @FXML
    private void actionBrowseBoundary() {
        List<File> sel = ModelFactory.fileChooserCustom(false, "Boundary set (optional)",
                List.of("*.xml", "*.zip", "*.rdf"), "Select boundary EQ/TP files");
        if (sel == null || sel.isEmpty()) return;
        boundaryFiles.clear();
        boundaryFiles.addAll(sel);
        tfBoundaryFiles.setText(joinPaths(boundaryFiles));
    }

    @FXML
    private void actionBrowseQueryFolder() {
        File f = ModelFactory.folderChooserCustom("Select SPARQL query library folder (optional)");
        if (f == null) return;
        queryFolder = f;
        tfQueryFolder.setText(f.getAbsolutePath());
    }

    @FXML
    private void actionBrowseConfig() {
        List<File> sel = ModelFactory.fileChooserCustom(true, "Matching config (optional)",
                List.of("*.json"), "Select matching config JSON");
        if (sel == null || sel.isEmpty() || sel.get(0) == null) return;
        configFile = sel.get(0);
        tfConfigFile.setText(configFile.getAbsolutePath());
    }

    @FXML
    private void actionBrowseOutput() {
        File f = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"),
                "Save mapping workbook as", "id_mapping.xlsx");
        if (f == null) return;
        outputFile = f;
        tfOutputFile.setText(f.getAbsolutePath());
    }

    @FXML
    private void actionReset() {
        pfFiles.clear();
        igmsFiles.clear();
        boundaryFiles.clear();
        queryFolder = null;
        configFile = null;
        outputFile = null;
        tfPfFiles.clear();
        tfIgmsFiles.clear();
        tfBoundaryFiles.clear();
        tfQueryFolder.clear();
        tfConfigFile.clear();
        tfOutputFile.clear();
        cbSecondName.getSelectionModel().select("name");
        pbIdMapping.setProgress(0);
        setStatus("Idle.");
    }

    // ---- run ----

    @FXML
    private void actionRun() {
        if (!validateInputs()) return;

        btnRunIdMapping.setDisable(true);
        pbIdMapping.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        final List<File> pf = new ArrayList<>(pfFiles);
        final List<File> igms = new ArrayList<>(igmsFiles);
        final List<File> boundary = new ArrayList<>(boundaryFiles);
        final File config = configFile;
        final File query = queryFolder;
        final String secondName = cbSecondName.getValue();
        final Path output = outputFile.toPath();

        new Thread(() -> {
            try {
                MatchingConfig cfg;
                if (config != null) {
                    cfg = MatchingConfigLoader.load(config);
                } else {
                    MatchingConfig.Builder b = MatchingConfig.builder()
                            .secondNameSource(parseSecondName(secondName));
                    if (query != null) b.queryFolder(query.getAbsolutePath());
                    cfg = b.build();
                }

                IdMappingService service = new IdMappingService(cfg);
                IdMappingService.RunSummary summary = service.run(pf, igms,
                        boundary.isEmpty() ? null : boundary, output,
                        (fraction, message) -> Platform.runLater(() -> {
                            pbIdMapping.setProgress(fraction);
                            setStatus(message);
                        }));

                Platform.runLater(() -> {
                    pbIdMapping.setProgress(1);
                    btnRunIdMapping.setDisable(false);
                    setStatus("Done: " + summary.matched() + " matched rows, "
                            + summary.unmatched() + " unmatched (for review).");
                    showInfo("ID Mapping finished",
                            "Workbook written to:\n" + summary.output()
                                    + "\n\nMatched rows: " + summary.matched()
                                    + "\nUnmatched (for review): " + summary.unmatched());
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.runLater(() -> {
                    pbIdMapping.setProgress(0);
                    btnRunIdMapping.setDisable(false);
                    setStatus("Failed: " + ex.getMessage());
                    showError("ID Mapping failed", String.valueOf(ex.getMessage()));
                });
            }
        }, "id-mapping-runner").start();
    }

    private boolean validateInputs() {
        if (pfFiles.isEmpty()) {
            showWarning("Missing PF files", "Select at least one PF EQ file.");
            return false;
        }
        if (igmsFiles.isEmpty()) {
            showWarning("Missing IGMS files", "Select at least one IGMS EQ file.");
            return false;
        }
        if (outputFile == null) {
            showWarning("Missing output", "Choose an output .xlsx path.");
            return false;
        }
        return true;
    }

    private static MatchingConfig.SecondNameSource parseSecondName(String s) {
        if (s == null) return MatchingConfig.SecondNameSource.NAME;
        return switch (s) {
            case "aliasName" -> MatchingConfig.SecondNameSource.ALIAS_NAME;
            case "description" -> MatchingConfig.SecondNameSource.DESCRIPTION;
            default -> MatchingConfig.SecondNameSource.NAME;
        };
    }

    private static String joinPaths(List<File> files) {
        List<String> paths = new ArrayList<>();
        for (File f : files) paths.add(f.getAbsolutePath());
        return String.join("; ", paths);
    }

    private void setStatus(String s) {
        if (lblStatus != null) lblStatus.setText(s);
    }

    private void showInfo(String title, String msg) {
        alert(Alert.AlertType.INFORMATION, title, msg);
    }

    private void showWarning(String title, String msg) {
        alert(Alert.AlertType.WARNING, title, msg);
    }

    private void showError(String title, String msg) {
        alert(Alert.AlertType.ERROR, title, msg);
    }

    private void alert(Alert.AlertType type, String title, String msg) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }
}
