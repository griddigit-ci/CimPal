package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.CgmesAddSwitchingDevices;
import eu.griddigit.cimpal.main.application.tasks.ITask;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CgmesAddSwitchingDevicesController {

    @FXML private TextField tfModelInput;
    @FXML private TextField tfMappingFile;

    @FXML private ChoiceBox<CgmesAddSwitchingDevices.DataExchangeStandard> cbStandard;

    @FXML private CheckBox cbApplyLines;
    @FXML private CheckBox cbApplyPowerTransformer;
    @FXML private CheckBox cbApplySyncMachine;
    @FXML private CheckBox cbOnlyForMappingEquipment;
    @FXML private CheckBox cbExportMappingFile;
    @FXML private CheckBox saveResult;

    private CgmesAddSwitchingDevices task;

    @FXML
    public void initialize() {
        WizardContext ctx = WizardContext.getInstance();
        if (ctx == null || ctx.getSelectedTasks() == null) return;

        task = findTask(ctx, CgmesAddSwitchingDevices.class);

        cbStandard.setItems(FXCollections.observableArrayList(CgmesAddSwitchingDevices.DataExchangeStandard.values()));
        cbStandard.setValue(task.getStandard());

        refreshModelInputSummary();
        refreshMappingFileSummary();

        cbApplyLines.setSelected(task.isApplyLines());
        cbApplyPowerTransformer.setSelected(task.isApplyPowerTransformer());
        cbApplySyncMachine.setSelected(task.isApplySynchronousMachine());
        cbOnlyForMappingEquipment.setSelected(task.isOnlyForEquipmentInMappingFile());
        cbExportMappingFile.setSelected(task.isExportMappingFile());
        saveResult.setSelected(task.getSaveResult());

        cbStandard.valueProperty().addListener((obs, o, n) -> {
            if (n != null) task.setStandard(n);
        });

        cbApplyLines.selectedProperty().addListener((obs, o, n) -> task.setApplyLines(n));
        cbApplyPowerTransformer.selectedProperty().addListener((obs, o, n) -> task.setApplyPowerTransformer(n));
        cbApplySyncMachine.selectedProperty().addListener((obs, o, n) -> task.setApplySynchronousMachine(n));
        cbOnlyForMappingEquipment.selectedProperty().addListener((obs, o, n) -> task.setOnlyForEquipmentInMappingFile(n));
        cbExportMappingFile.selectedProperty().addListener((obs, o, n) -> task.setExportMappingFile(n));
        saveResult.selectedProperty().addListener((obs, o, n) -> task.setSaveResult(n));
    }

    @FXML
    private void actionBrowseModelInput() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select input model (IGM/CGM including boundary)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Instance files", "*.xml", "*.rdf", "*.zip"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        Stage stage = (Stage) tfModelInput.getScene().getWindow();
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        List<Path> paths = files.stream().map(File::toPath).toList();
        task.setModelInputFiles(paths);
        refreshModelInputSummary();
    }

    @FXML
    private void actionBrowseMappingFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select mapping file");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Excel file", "*.xlsx"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        Stage stage = (Stage) tfMappingFile.getScene().getWindow();
        File f = fc.showOpenDialog(stage);
        if (f == null) return;

        task.setMappingFile(f.toPath());
        refreshMappingFileSummary();
    }

    private void refreshModelInputSummary() {
        List<Path> files = task.getModelInputFiles();
        if (files == null || files.isEmpty()) {
            tfModelInput.setText("");
            return;
        }
        if (files.size() <= 3) {
            tfModelInput.setText(files.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining("; ")));
        } else {
            tfModelInput.setText(files.size() + " files selected");
        }
    }

    private void refreshMappingFileSummary() {
        Path p = task.getMappingFile();
        tfMappingFile.setText(p == null ? "" : p.getFileName().toString());
    }

    private static <T extends ITask> T findTask(WizardContext ctx, Class<T> clazz) {
        return ctx.getSelectedTasks().stream()
                .map(SelectedTask::getTask)
                .filter(Objects::nonNull)
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Task not found in WizardContext: " + clazz.getSimpleName()));
    }
}