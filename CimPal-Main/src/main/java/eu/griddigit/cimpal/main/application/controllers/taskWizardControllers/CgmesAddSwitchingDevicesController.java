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
        task = getTaskFromContext(CgmesAddSwitchingDevices.class);

        cbStandard.setItems(FXCollections.observableArrayList(CgmesAddSwitchingDevices.DataExchangeStandard.values()));
        cbStandard.setValue(task.getStandard());

        if (task.getModelInput() != null) tfModelInput.setText(task.getModelInput().toString());
        if (task.getMappingFile() != null) tfMappingFile.setText(task.getMappingFile().toString());

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
                new FileChooser.ExtensionFilter("RDF/XML", "*.xml", "*.rdf"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        Stage stage = (Stage) tfModelInput.getScene().getWindow();
        File f = fc.showOpenDialog(stage);
        if (f == null) return;

        Path p = f.toPath();
        tfModelInput.setText(p.toString());
        task.setModelInput(p);
    }

    @FXML
    private void actionBrowseMappingFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select mapping file");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Mapping files", "*.xls", "*.xlsx", "*.csv", "*.json", "*.ttl"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        Stage stage = (Stage) tfMappingFile.getScene().getWindow();
        File f = fc.showOpenDialog(stage);
        if (f == null) return;

        Path p = f.toPath();
        tfMappingFile.setText(p.toString());
        task.setMappingFile(p);
    }

    private <T extends ITask> T getTaskFromContext(Class<T> clazz) {
        // Adjust this method to your actual WizardContext API.
        WizardContext ctx = WizardContext.getInstance();
        List<SelectedTask> selected = ctx.getSelectedTasks();

        for (SelectedTask st : selected) {
            if (clazz.isInstance(st.getTask())) {
                return clazz.cast(st.getTask());
            }
        }
        throw new IllegalStateException("Task not found in context: " + clazz.getSimpleName());
    }
}