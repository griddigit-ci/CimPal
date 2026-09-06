package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.shacl_tools.ShaclAutoTester;
import eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.apache.jena.datatypes.RDFDatatype;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the <em>Dataset SHACL Validation</em> tab.
 * <p>
 * The tab offers three workflows, selected by the {@code Validation workflow} choice box:
 * two mapping-driven ones that resolve constraint files from a mapping CSV, and
 * {@code Validate by manual selection}, which validates every model found under the models
 * root folder against constraint files chosen by hand. The last was previously the separate
 * <em>SHACL tester</em> tab; it is a third workflow here rather than a tab of its own because
 * it shares the models root folder with the mapping workflows.
 */
public class ValidationByMappingController {

    private static final Logger LOG = LoggerFactory.getLogger(ValidationByMappingController.class);

    /** Choice-box label for the manual workflow, formerly the SHACL tester tab. */
    private static final String WORKFLOW_MAPPING = "Validate by mapping file";
    private static final String WORKFLOW_TIMESTAMPED = "Validate by timestamped mapping";
    private static final String WORKFLOW_MANUAL = "Validate by manual selection";

    /** Depth used when discovering model archives under the models root folder. */
    private static final int MODEL_SCAN_DEPTH = 3;

    private MainController mainController;

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

    // Previous-run comparison CSV (header: region,dataset,total).
    // Only used by the timestamped workflow; disabled otherwise.
    @FXML
    private TextField tfPreviousComparisonCsv;

    @FXML
    private Button btnBrowsePreviousComparisonCsv;

    @FXML
    private Button btnBrowseMappingCsv;

    @FXML
    private Button btnBrowseConstraintsRootFolder;

    @FXML
    private Button btnBrowseOutputFolder;

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

    @FXML
    private Label helpPreviousComparisonCsv;

    // ---- manual-selection workflow controls, formerly the SHACL tester tab ----
    @FXML
    private HBox rowShaclConstraintFilesLabel;

    @FXML
    private TextField tfShaclConstraintFiles;

    @FXML
    private Button btnBrowseShaclConstraintFiles;

    @FXML
    private Label helpShaclConstraintFiles;

    @FXML
    private HBox rowExportModelReports;

    @FXML
    private CheckBox cbExportReports;

    @FXML
    private Label helpExportReports;

    @FXML
    private TreeView<String> treeViewShaclFiles;

    private File mappingCsvFile;
    private File modelsInputFolder;
    private File constraintsRootFolder;
    private File outputFolder;
    private File previousComparisonCsvFile;
    private List<File> shaclConstraintFiles;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @FXML
    private void initialize() {
        cbValidationWorkflow.getItems().setAll(
                WORKFLOW_MAPPING,
                WORKFLOW_TIMESTAMPED,
                WORKFLOW_MANUAL
        );
        cbValidationWorkflow.getSelectionModel().select(WORKFLOW_MAPPING);

        cbDatatypeMap.getItems().setAll(
                "CGMES 3.0 / NC 2.5",
                "CGMES 3.0 / NC 2.4",
                "CGMES 2.4 / NC 2.2"
        );
        cbDatatypeMap.getSelectionModel().select("CGMES 3.0 / NC 2.5");

        tfXmlBaseUri.setText("http://iec.ch/TC57/CIM100");

        pbValidationByMapping.setProgress(0);

        // Each workflow uses a different subset of the form; re-evaluate on every change.
        cbValidationWorkflow.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldVal, newVal) -> updateWorkflowControls());
        updateWorkflowControls();

        initializeHelpTooltips();
    }

    /**
     * Enables the fields the selected workflow actually reads, and disables the rest.
     * <p>
     * The two mapping workflows resolve their constraint files from the mapping CSV, so the
     * manual constraint-files row does not apply to them; the manual workflow reads neither
     * the mapping CSV nor the constraints root, output folder, datatype map or XML base, so
     * those are disabled in turn. Without this, {@link #validateInputs()} would demand a
     * mapping CSV for a run that never looks at one.
     */
    private void updateWorkflowControls() {
        boolean timestamped = isTimestampedWorkflow();
        boolean manual = isManualWorkflow();

        // Previous-run comparison CSV: timestamped workflow only.
        setDisabled(!timestamped, tfPreviousComparisonCsv, btnBrowsePreviousComparisonCsv,
                helpPreviousComparisonCsv);

        // Manual constraint file selection: manual workflow only.
        setDisabled(!manual, rowShaclConstraintFilesLabel, tfShaclConstraintFiles,
                btnBrowseShaclConstraintFiles);

        // Mapping-driven inputs: not read by the manual workflow.
        setDisabled(manual, tfMappingCsvFile, btnBrowseMappingCsv,
                tfConstraintsRootFolder, btnBrowseConstraintsRootFolder,
                tfOutputFolder, btnBrowseOutputFolder,
                cbDatatypeMap, tfXmlBaseUri);

        // The export option and the discovered-model tree only mean anything for the manual
        // workflow, so they are hidden outright rather than shown disabled.
        setShown(manual, rowExportModelReports, treeViewShaclFiles);
    }

    private static void setDisabled(boolean disabled, Node... nodes) {
        for (Node node : nodes) {
            if (node != null) {
                node.setDisable(disabled);
            }
        }
    }

    /** Hides and unmanages, so a hidden node gives its space back instead of leaving a gap. */
    private static void setShown(boolean shown, Node... nodes) {
        for (Node node : nodes) {
            if (node != null) {
                node.setVisible(shown);
                node.setManaged(shown);
            }
        }
    }

    private void initializeHelpTooltips() {
        installHelpTooltip(
                helpValidationWorkflow,
                "Select which validation process should be executed.\n\n" +
                        "Validate by mapping file: validates the selected input model structure according to the mapping CSV and creates a validation report and ZIP files.\n\n" +
                        "Validate by timestamped mapping: discovers timestamped input files and creates timestamp-based validation reports.\n\n" +
                        "Validate by manual selection: validates every model archive found under the models root folder against the SHACL constraint files you select by hand, with no mapping file. Results are written to the Output pane."
        );

        installHelpTooltip(
                helpShaclConstraintFiles,
                "SHACL constraint files (.ttl) used to validate the models. Several files can be selected and are combined into one shapes graph.\n\n" +
                        "Only used by the \"Validate by manual selection\" workflow; the mapping workflows resolve their constraint files from the mapping CSV instead."
        );

        installHelpTooltip(
                helpMappingCsvFile,
                "CSV mapping file containing the XML input definitions and the SHACL constraint files.\n\n" +
                        "Only .csv files are accepted.\n\n" +
                        "Not used by the \"Validate by manual selection\" workflow."
        );

        installHelpTooltip(
                helpModelsInputFolder,
                "For normal mapping, select the models root folder.\n\n" +
                        "For timestamped mapping, select the root folder containing timestamped XML or ZIP files.\n\n" +
                        "For manual selection, this is the parent folder that is searched for model archives to validate."
        );

        installHelpTooltip(
                helpExportReports,
                "When checked, saves a SHACL validation report file alongside each validated model in the models root folder.\n\n" +
                        "Only used by the \"Validate by manual selection\" workflow."
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
                        "CGMES 3.0 / NC 2.5 uses the CIM17 / CGMES 3 / NC 2.5 datatype map.\n\n" +
                        "CGMES 3.0 / NC 2.4 uses the CIM17 / CGMES 3 / NC 2.4 datatype map.\n\n" +
                        "CGMES 2.4 / NC 2.2 uses the CIM16 / CGMES 2.4 / NC 2.2 datatype map."
        );

        installHelpTooltip(
                helpXmlBaseUri,
                "Base URI used when loading RDF/XML files.\n\n" +
                        "The default value is normally correct for CIM100 based profiles."
        );

        installHelpTooltip(
                helpPreviousComparisonCsv,
                "Optional CSV holding the previous run's totals, used to build the comparison workbook.\n\n" +
                        "Only available for the timestamped workflow.\n\n" +
                        "Expected header: region,dataset,total  (total = warnings + infos + violations).\n\n" +
                        "This is the same shape the comparison workbook emits, so each run's output can feed the next.\n\n" +
                        "If left empty, the comparison is produced with an empty \"previous\" column."
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

        //restore the paths this tab was last used with; the consumers repeat what the
        //Browse handlers do besides filling the field
        PathMemory.bind(tfMappingCsvFile, "tab.validationByMapping.mappingCsv",
                file -> mappingCsvFile = file);
        PathMemory.bind(tfModelsInputFolder, "tab.validationByMapping.modelsInput",
                folder -> {
                    modelsInputFolder = folder;
                    //the manual workflow shows the discovered models, so rebuild the tree
                    //whenever the folder is restored, exactly as the Browse handler does
                    GUIhelper.buildFileTree(folder, treeViewShaclFiles);
                });
        PathMemory.bind(tfConstraintsRootFolder, "tab.validationByMapping.constraintsRoot",
                folder -> constraintsRootFolder = folder);
        PathMemory.bind(tfOutputFolder, "tab.validationByMapping.outputFolder",
                folder -> outputFolder = folder);
        PathMemory.bind(tfPreviousComparisonCsv, "tab.validationByMapping.previousComparisonCsv",
                file -> previousComparisonCsvFile = file);
    }

    @FXML
    private void actionBrowseMappingCsv() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true,
                "Validation mapping file",
                List.of("*.csv"),
                "Mapping file",
                "tab.validationByMapping.mappingCsv"
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
                        : "Select the models root folder",
                "tab.validationByMapping.modelsInput"
        );

        if (selected == null) {
            return;
        }

        modelsInputFolder = selected;
        tfModelsInputFolder.setText(modelsInputFolder.getAbsolutePath());

        //the manual workflow lists what was found under this folder
        GUIhelper.buildFileTree(modelsInputFolder, treeViewShaclFiles);
    }

    /**
     * Selects the constraint files for the manual workflow. Multi-select: the field shows the
     * joined list and is not restored on restart, but the chooser reopens where it was last
     * used. Keeps the SHACL tester's path-memory key so that folder survives the tab merge.
     */
    @FXML
    private void actionBrowseShaclConstraintFiles() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false,
                "SHACL Constraints file",
                List.of("*.ttl", "*.rdf"),
                "",
                "tab.shaclTester.shaclFiles"
        );

        if (selected == null || selected.isEmpty()) {
            return;
        }

        shaclConstraintFiles = selected;

        StringBuilder paths = new StringBuilder();
        for (File file : selected) {
            if (!paths.isEmpty()) {
                paths.append(", ");
            }
            paths.append(file.toString());
        }
        tfShaclConstraintFiles.setText(paths.toString());
    }

    @FXML
    private void actionBrowseConstraintsRootFolder() {
        File selected = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom(
                "Select constraint's root folder",
                "tab.validationByMapping.constraintsRoot"
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
                "Select the output folder of reports and zips",
                "tab.validationByMapping.outputFolder"
        );

        if (selected == null) {
            return;
        }

        outputFolder = selected;
        tfOutputFolder.setText(outputFolder.getAbsolutePath());
    }

    @FXML
    private void actionBrowsePreviousComparisonCsv() {
        List<File> selected = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true,
                "Previous comparison CSV",
                List.of("*.csv"),
                "Previous comparison CSV",
                "tab.validationByMapping.previousComparisonCsv"
        );

        if (selected == null || selected.isEmpty() || selected.get(0) == null) {
            return;
        }

        File selectedFile = selected.get(0);

        if (!selectedFile.getName().toLowerCase().endsWith(".csv")) {
            showWarning(
                    "Invalid comparison file",
                    "Please select a CSV file (header: region,dataset,total)."
            );
            return;
        }

        previousComparisonCsvFile = selectedFile;
        if (tfPreviousComparisonCsv != null) {
            tfPreviousComparisonCsv.setText(previousComparisonCsvFile.getAbsolutePath());
        }
    }

    @FXML
    private void actionResetValidationByMapping() {
        mappingCsvFile = null;
        modelsInputFolder = null;
        constraintsRootFolder = null;
        outputFolder = null;
        previousComparisonCsvFile = null;
        shaclConstraintFiles = null;

        tfMappingCsvFile.clear();
        tfModelsInputFolder.clear();
        tfConstraintsRootFolder.clear();
        tfOutputFolder.clear();

        if (tfShaclConstraintFiles != null) {
            tfShaclConstraintFiles.clear();
        }

        if (treeViewShaclFiles != null) {
            treeViewShaclFiles.setRoot(null);
        }

        if (cbExportReports != null) {
            cbExportReports.setSelected(true);
        }

        if (tfPreviousComparisonCsv != null) {
            tfPreviousComparisonCsv.clear();
        }

        cbValidationWorkflow.getSelectionModel().select(WORKFLOW_MAPPING);
        cbDatatypeMap.getSelectionModel().select("CGMES 3.0 / NC 2.5");

        tfXmlBaseUri.setText("http://iec.ch/TC57/CIM100");

        updateWorkflowControls();

        pbValidationByMapping.setProgress(0);
    }

    @FXML
    private void actionRunValidationByMapping() {
        pbValidationByMapping.setProgress(0);

        if (!validateInputs()) {
            return;
        }

        if (isManualWorkflow()) {
            runManualValidation();
            return;
        }

        boolean runTimestampedWorkflow = isTimestampedWorkflow();
        String datatypeMapResource = getDatatypeMapResource();
        String xmlBase = tfXmlBaseUri.getText() == null || tfXmlBaseUri.getText().isBlank()
                ? "http://iec.ch/TC57/CIM100"
                : tfXmlBaseUri.getText().trim();

        int threadCount = getThreadCount(runTimestampedWorkflow);

        // Only meaningful for the timestamped workflow; null when none selected.
        Path previousComparisonCsv = runTimestampedWorkflow ? getPreviousComparisonCsvPath() : null;

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
                    ValidationTools.ValidationTimestampedRunSummary tsResult =
                            ValidationTools.validateByTimestampedMapping(
                            selectedMappingFile.toPath(),
                            selectedModelsInputFolder.toPath(),
                            selectedConstraintsRootFolder.toPath(),
                            selectedOutputFolder.toPath(),
                            threadCount,
                            dataTypeMap,
                            xmlBase,
                            previousComparisonCsv
                    );
                    List<Path> reports = tsResult.reports();

                    System.out.println("Created report count: " + reports.size());
                    for (Path report : reports) {
                        System.out.println("Report saved to: " + report);
                    }

                } else {
                    ValidationTools.ValidationRunSummary result = ValidationTools.validateByMapping(
                            selectedMappingFile.toPath(),
                            selectedModelsInputFolder.toPath(),
                            selectedConstraintsRootFolder.toPath(),
                            selectedOutputFolder.toPath(),
                            threadCount,
                            dataTypeMap,
                            xmlBase
                    );
                    Path report = result.reportPath();

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
                LOG.error("Unhandled exception", ex);

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(0);
                    btnRunValidationByMapping.setDisable(false);
                    showError("Validation failed", ex.getMessage());
                });

            } catch (Exception ex) {
                LOG.error("Unhandled exception", ex);

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(0);
                    btnRunValidationByMapping.setDisable(false);
                    showError("Validation failed", ex.getMessage());
                });
            }
        }, "validation-by-mapping-runner").start();
    }

    /**
     * Runs the manual workflow: discovers model archives under the models root folder and
     * validates them against the hand-picked constraint files.
     * <p>
     * Carried over from the SHACL tester tab, with the models root folder standing in for
     * that tab's separate "Parent folder for test models" field. Progress and per-model
     * output go to the shared progress bar and the Output pane, as they did before.
     */
    private void runManualValidation() {
        File selectedModelsFolder = modelsInputFolder;
        List<File> selectedConstraintFiles = shaclConstraintFiles;
        boolean exportReports = cbExportReports != null && cbExportReports.isSelected();

        List<File> archives = new ArrayList<>();
        try {
            Files.walkFileTree(selectedModelsFolder.toPath(), EnumSet.noneOf(FileVisitOption.class),
                    MODEL_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.toString().endsWith(".zip")) {
                                archives.add(file.toFile());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Error while searching for files",
                    "An error occurred while searching for models in the selected folder.", e);
            pbValidationByMapping.setProgress(0);
            return;
        }

        if (archives.isEmpty()) {
            showWarning("No models found",
                    "No model archives (.zip) were found under the selected models root folder.");
            pbValidationByMapping.setProgress(0);
            return;
        }

        btnRunValidationByMapping.setDisable(true);
        pbValidationByMapping.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        new Thread(() -> {
            try {
                ShaclAutoTester tester = new ShaclAutoTester(new ShaclAutoTesterCallback() {
                    @Override
                    public void updateProgress(double progress) {
                        Platform.runLater(() -> pbValidationByMapping.setProgress(progress));
                    }

                    @Override
                    public void appendOutput(String message) {
                        if (mainController != null) {
                            mainController.appendText(message);
                        }
                    }
                });

                tester.runTests(selectedConstraintFiles, selectedModelsFolder, archives, exportReports);

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(1);
                    btnRunValidationByMapping.setDisable(false);
                    showInfo("Validation finished",
                            "Validated " + archives.size() + " model(s). See the Output pane for details.");
                });

            } catch (Exception ex) {
                LOG.error("Manual SHACL validation failed", ex);

                Platform.runLater(() -> {
                    pbValidationByMapping.setProgress(0);
                    btnRunValidationByMapping.setDisable(false);
                    showError("Validation failed", ex.getMessage());
                });
            }
        }, "manual-shacl-validation-runner").start();
    }

    private boolean validateInputs() {
        if (cbValidationWorkflow.getSelectionModel().getSelectedItem() == null) {
            showWarning("Missing workflow", "Please select a validation workflow.");
            return false;
        }

        // The manual workflow reads only the constraint files and the models root folder.
        if (isManualWorkflow()) {
            if (shaclConstraintFiles == null || shaclConstraintFiles.isEmpty()) {
                showWarning("Missing constraint files",
                        "Please select one or more SHACL constraint files (.ttl).");
                return false;
            }

            if (modelsInputFolder == null) {
                showWarning("Missing models folder",
                        "Please select the models root folder holding the models to validate.");
                return false;
            }

            return true;
        }

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

        if (cbDatatypeMap.getSelectionModel().getSelectedItem() == null) {
            showWarning("Missing datatype map", "Please select a datatype map.");
            return false;
        }

        return true;
    }

    private boolean isTimestampedWorkflow() {
        return WORKFLOW_TIMESTAMPED.equals(
                cbValidationWorkflow.getSelectionModel().getSelectedItem()
        );
    }

    private boolean isManualWorkflow() {
        return WORKFLOW_MANUAL.equals(
                cbValidationWorkflow.getSelectionModel().getSelectedItem()
        );
    }

    private String getDatatypeMapResource() {
        String selected = cbDatatypeMap.getSelectionModel().getSelectedItem();

        return switch (selected) {
            case "CGMES 2.4 / NC 2.2" -> "/CompleteDatatypeMap_CIM16_CGMES24_NC22.properties";
            case "CGMES 3.0 / NC 2.4" -> "/CompleteDatatypeMap_CIM17_CGMES3_NC24.properties";
            case "CGMES 3.0 / NC 2.5" -> "/CompleteDatatypeMap_CIM17_CGMES3_NC25.properties";
            default -> throw new IllegalStateException("Unknown datatype map: " + selected);
        };
    }

    /** Previous-run comparison CSV path, or null if none selected. */
    private Path getPreviousComparisonCsvPath() {
        if (previousComparisonCsvFile != null) {
            return previousComparisonCsvFile.toPath();
        }
        if (tfPreviousComparisonCsv != null) {
            String text = tfPreviousComparisonCsv.getText();
            if (text != null && !text.isBlank()) {
                return new File(text.trim()).toPath();
            }
        }
        return null;
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