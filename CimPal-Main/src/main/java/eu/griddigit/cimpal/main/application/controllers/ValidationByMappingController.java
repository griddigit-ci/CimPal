package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.Duration;
import org.apache.jena.datatypes.RDFDatatype;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ValidationByMappingController {

    @FXML
    private ChoiceBox<String> cbValidationWorkflow;

    @FXML
    private TextField tfMappingCsvFile;

    @FXML
    private TextField tfModelsInputFolder;

    @FXML
    private TextField tfConstraintsRootFolder;

    @FXML
    private TextField tfOutputFolder;

    @FXML
    private ChoiceBox<String> cbDatatypeMap;

    @FXML
    private TextField tfXmlBaseUri;

    @FXML
    private ProgressBar pbValidationByMapping;

    @FXML
    private Button btnRunValidationByMapping;

    @FXML
    private Label helpValidationWorkflow;

    @FXML
    private Label helpMappingCsvFile;

    @FXML
    private Label helpModelsInputFolder;

    @FXML
    private Label helpConstraintsRootFolder;

    @FXML
    private Label helpOutputFolder;

    @FXML
    private Label helpDatatypeMap;

    @FXML
    private Label helpXmlBaseUri;

    private File mappingCsvFile;
    private File modelsInputFolder;
    private File constraintsRootFolder;
    private File outputFolder;

    @FXML
    private void initialize() {
        cbValidationWorkflow.getItems().setAll(
                "Validate by mapping file",
                "Validate by timestamped mapping"
        );
        cbValidationWorkflow.getSelectionModel().select("Validate by mapping file");

        cbDatatypeMap.getItems().setAll(
                "CGMES 3.0 / NC 2.4",
                "CGMES 2.4 / NC 2.2"
        );
        cbDatatypeMap.getSelectionModel().select("CGMES 3.0 / NC 2.4");

        tfXmlBaseUri.setText("http://iec.ch/TC57/CIM100");

        pbValidationByMapping.setProgress(0);

        initializeHelpTooltips();
    }

    private void initializeHelpTooltips() {
        installHelpTooltip(
                helpValidationWorkflow,
                "Select which validation process should be executed.\n\n" +
                        "Validate by mapping file: validates the selected input model structure according to the mapping CSV and creates a validation report and ZIP files.\n\n" +
                        "Validate by timestamped mapping: discovers timestamped input files and creates timestamp-based validation reports."
        );

        installHelpTooltip(
                helpMappingCsvFile,
                "CSV mapping file containing the XML input definitions and the SHACL constraint files.\n\n" +
                        "Only .csv files are accepted."
        );

        installHelpTooltip(
                helpModelsInputFolder,
                "For normal mapping, select the models root folder.\n\n" +
                        "For timestamped mapping, select the root folder containing timestamped XML or ZIP files."
        );

        installHelpTooltip(
                helpConstraintsRootFolder,
                "Root folder where the SHACL constraint files are located.\n\n" +
                        "The mapping file constraint paths are resolved relative to this folder."
        );

        installHelpTooltip(
                helpOutputFolder,
                "Folder where validation reports, timestamped summaries and generated ZIP files will be written."
        );

        installHelpTooltip(
                helpDatatypeMap,
                "Select the CGMES and NC version combination used to load the datatype mapping for validation.\n\n" +
                        "CGMES 3.0 / NC 2.4 uses the CIM17 / CGMES 3 / NC 2.4 datatype map.\n\n" +
                        "CGMES 2.4 / NC 2.2 uses the CIM16 / CGMES 2.4 / NC 2.2 datatype map."
        );

        installHelpTooltip(
                helpXmlBaseUri,
                "Base URI used when loading RDF/XML files.\n\n" +
                        "The default value is normally correct for CIM100 based profiles."
        );
    }

    private void installHelpTooltip(Label helpIcon, String text) {
        if (helpIcon == null) {
            return;
        }

        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(450);

        tooltip.setShowDelay(Duration.millis(300));
        tooltip.setHideDelay(Duration.millis(200));
        tooltip.setShowDuration(Duration.INDEFINITE);

        Tooltip.install(helpIcon, tooltip);
    }

    @FXML
    private void actionBrowseMappingCsv() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true,
                "Validation mapping file",
                List.of("*.csv"),
                "Mapping file"
        );

        if (selected == null || selected.isEmpty() || selected.get(0) == null) {
            return;
        }

        File selectedFile = selected.get(0);

        if (!selectedFile.getName().toLowerCase().endsWith(".csv")) {
            showWarning(
                    "Invalid mapping file",
                    "Please select a CSV mapping file."
            );
            return;
        }

        mappingCsvFile = selectedFile;
        tfMappingCsvFile.setText(mappingCsvFile.getAbsolutePath());
    }

    @FXML
    private void actionBrowseModelsInputFolder() {
        boolean timestamped = isTimestampedWorkflow();

        File selected = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                timestamped
                        ? "Select the timestamped input root folder"
                        : "Select the models root folder"
        );

        if (selected == null) {
            return;
        }

        modelsInputFolder = selected;
        tfModelsInputFolder.setText(modelsInputFolder.getAbsolutePath());
    }

    @FXML
    private void actionBrowseConstraintsRootFolder() {
        File selected = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                "Select constraint's root folder"
        );

        if (selected == null) {
            return;
        }

        constraintsRootFolder = selected;
        tfConstraintsRootFolder.setText(constraintsRootFolder.getAbsolutePath());
    }

    @FXML
    private void actionBrowseOutputFolder() {
        File selected = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                "Select the output folder of reports and zips"
        );

        if (selected == null) {
            return;
        }

        outputFolder = selected;
        tfOutputFolder.setText(outputFolder.getAbsolutePath());
    }

    @FXML
    private void actionResetValidationByMapping() {
        mappingCsvFile = null;
        modelsInputFolder = null;
        constraintsRootFolder = null;
        outputFolder = null;

        tfMappingCsvFile.clear();
        tfModelsInputFolder.clear();
        tfConstraintsRootFolder.clear();
        tfOutputFolder.clear();

        cbValidationWorkflow.getSelectionModel().select("Validate by mapping file");
        cbDatatypeMap.getSelectionModel().select("CGMES 3.0 / NC 2.4");

        tfXmlBaseUri.setText("http://iec.ch/TC57/CIM100");

        pbValidationByMapping.setProgress(0);
    }

    @FXML
    private void actionRunValidationByMapping() {
        pbValidationByMapping.setProgress(0);

        if (!validateInputs()) {
            return;
        }

        boolean runTimestampedWorkflow = isTimestampedWorkflow();
        String datatypeMapResource = getDatatypeMapResource();
        String xmlBase = tfXmlBaseUri.getText() == null || tfXmlBaseUri.getText().isBlank()
                ? "http://iec.ch/TC57/CIM100"
                : tfXmlBaseUri.getText().trim();

        int threadCount = getThreadCount(runTimestampedWorkflow);

        btnRunValidationByMapping.setDisable(true);
        pbValidationByMapping.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        File selectedMappingFile = mappingCsvFile;
        File selectedModelsInputFolder = modelsInputFolder;
        File selectedOutputFolder = outputFolder;
        File selectedConstraintsRootFolder = constraintsRootFolder;

        new Thread(() -> {
            try {
                Map<String, RDFDatatype> dataTypeMap =
                        CompleteDatatypeMapLoader.loadFromResource(datatypeMapResource);

                if (runTimestampedWorkflow) {
                    List<Path> reports = ValidationTools.validateByTimestampedMapping(
                            selectedMappingFile.toPath(),
                            selectedModelsInputFolder.toPath(),
                            selectedConstraintsRootFolder.toPath(),
                            selectedOutputFolder.toPath(),
                            threadCount,
                            dataTypeMap,
                            xmlBase
                    );

                    System.out.println("Created report count: " + reports.size());
                    for (Path report : reports) {
                        System.out.println("Report saved to: " + report);
                    }

                } else {
                    Path report = ValidationTools.validateByMapping(
                            selectedMappingFile.toPath(),
                            selectedModelsInputFolder.toPath(),
                            selectedConstraintsRootFolder.toPath(),
                            selectedOutputFolder.toPath(),
                            threadCount,
                            dataTypeMap,
                            xmlBase
                    );

                    System.out.println("Report saved to: " + report);

                    List<Path> createdZips = ValidationTools.zipByMapping(
                            selectedMappingFile.toPath(),
                            selectedModelsInputFolder.toPath(),
                            selectedOutputFolder.toPath()
                    );

                    System.out.println("Created zip count: " + createdZips.size());
                    for (Path zip : createdZips) {
                        System.out.println("ZIP: " + zip);
                    }
                }

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(1);
                    btnRunValidationByMapping.setDisable(false);
                    showInfo("Validation finished", "Validation report generation finished.");
                });

            } catch (IOException ex) {
                ex.printStackTrace();

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(0);
                    btnRunValidationByMapping.setDisable(false);
                    showError("Validation failed", ex.getMessage());
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(0);
                    btnRunValidationByMapping.setDisable(false);
                    showError("Validation failed", ex.getMessage());
                });
            }
        }, "validation-by-mapping-runner").start();
    }

    private boolean validateInputs() {
        if (mappingCsvFile == null) {
            showWarning("Missing mapping file", "Please select a CSV mapping file.");
            return false;
        }

        if (!mappingCsvFile.getName().toLowerCase().endsWith(".csv")) {
            showWarning("Invalid mapping file", "The mapping file must be a CSV file.");
            return false;
        }

        if (modelsInputFolder == null) {
            showWarning("Missing input folder", "Please select the models or timestamped input root folder.");
            return false;
        }

        if (constraintsRootFolder == null) {
            showWarning("Missing constraints folder", "Please select the constraints root folder.");
            return false;
        }

        if (outputFolder == null) {
            showWarning("Missing output folder", "Please select the output folder.");
            return false;
        }

        if (cbValidationWorkflow.getSelectionModel().getSelectedItem() == null) {
            showWarning("Missing workflow", "Please select a validation workflow.");
            return false;
        }

        if (cbDatatypeMap.getSelectionModel().getSelectedItem() == null) {
            showWarning("Missing datatype map", "Please select a datatype map.");
            return false;
        }

        return true;
    }

    private boolean isTimestampedWorkflow() {
        return "Validate by timestamped mapping".equals(
                cbValidationWorkflow.getSelectionModel().getSelectedItem()
        );
    }

    private String getDatatypeMapResource() {
        String selected = cbDatatypeMap.getSelectionModel().getSelectedItem();

        return switch (selected) {
            case "CGMES 2.4 / NC 2.2" -> "/CompleteDatatypeMap_CIM16_CGMES24_NC22.properties";
            case "CGMES 3.0 / NC 2.4" -> "/CompleteDatatypeMap_CIM17_CGMES3_NC24.properties";
            default -> throw new IllegalStateException("Unknown datatype map: " + selected);
        };
    }

    private int getThreadCount(boolean timestampedWorkflow) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();

        if (timestampedWorkflow) {
            return Math.min(Math.max(1, availableProcessors - 1), 2);
        }

        return Math.min(Math.max(1, availableProcessors - 1), 4);
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }

    private void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message == null ? "" : message);
        alert.showAndWait();
    }
}