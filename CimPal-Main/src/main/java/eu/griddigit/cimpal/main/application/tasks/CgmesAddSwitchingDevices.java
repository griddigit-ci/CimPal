package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CgmesAddSwitchingDevices implements ITask {


    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private String newHeaderDescription;
    private boolean saveResult;

    public CgmesAddSwitchingDevices() {
        this.name = "Add Switching Devices (mapping-based modification)";
        this.pathToFXML = "/fxml/wizardPages/taskElements/cgmesAddSwitchingDevices.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }
    public enum DataExchangeStandard {
        CGMES_2_4, CGMES_3_0
    }

    private Path modelInput;     // IGM or CGM incl. boundary
    private Path mappingFile;    // optional in some flows

    private DataExchangeStandard standard = DataExchangeStandard.CGMES_2_4;

    private boolean applyLines = false;
    private boolean applyPowerTransformer = false;
    private boolean applySynchronousMachine = false;

    private boolean onlyForEquipmentInMappingFile = false;
    private boolean exportMappingFile = false;


    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return this.pathToFXML;
    }

    @Override
    public String getStatus() {
        return this.status;
    }

    @Override
    public String getInfo() {
        return this.info;
    }

    public Path getModelInput() {
        return modelInput;
    }

    public void setModelInput(Path modelInput) {
        this.modelInput = modelInput;
    }

    public Path getMappingFile() {
        return mappingFile;
    }

    public void setMappingFile(Path mappingFile) {
        this.mappingFile = mappingFile;
    }

    public DataExchangeStandard getStandard() {
        return standard;
    }

    public void setStandard(DataExchangeStandard standard) {
        this.standard = standard;
    }

    public boolean isApplyLines() {
        return applyLines;
    }

    public void setApplyLines(boolean applyLines) {
        this.applyLines = applyLines;
    }

    public boolean isApplyPowerTransformer() {
        return applyPowerTransformer;
    }

    public void setApplyPowerTransformer(boolean applyPowerTransformer) {
        this.applyPowerTransformer = applyPowerTransformer;
    }

    public boolean isApplySynchronousMachine() {
        return applySynchronousMachine;
    }

    public void setApplySynchronousMachine(boolean applySynchronousMachine) {
        this.applySynchronousMachine = applySynchronousMachine;
    }

    public boolean isOnlyForEquipmentInMappingFile() {
        return onlyForEquipmentInMappingFile;
    }

    public void setOnlyForEquipmentInMappingFile(boolean onlyForEquipmentInMappingFile) {
        this.onlyForEquipmentInMappingFile = onlyForEquipmentInMappingFile;
    }

    public boolean isExportMappingFile() {
        return exportMappingFile;
    }

    public void setExportMappingFile(boolean exportMappingFile) {
        this.exportMappingFile = exportMappingFile;
    }

    @Override
    public boolean getSaveResult() {
        return saveResult;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        if (modelInput == null) {
            return "Input model is missing.";
        }
        if (!Files.exists(modelInput)) {
            return "Input model does not exist: " + modelInput;
        }

        // at least one apply option should be selected
        if (!applyLines && !applyPowerTransformer && !applySynchronousMachine) {
            return "Select at least one 'Apply to' option (Lines / PowerTransformer / SynchronousMachine).";
        }

        // mapping file rules (adjust if your workflow allows empty mapping)
        if (mappingFile == null) {
            return "Mapping file is missing.";
        }
        if (!Files.exists(mappingFile)) {
            return "Mapping file does not exist: " + mappingFile;
        }

        return null;
    }

    @Override
    public void execute(eu.griddigit.cimpal.main.application.tasks.SelectedTask parent) throws IOException {
        // TODO: call Core modification service here
    }


}