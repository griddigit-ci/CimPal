package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.CGMESConverter.ModelManipulationFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    private List<Path> modelInputFiles;     // IGM or CGM incl. boundary
    private List<Path> mappingFiles;    // optional in some flows

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

    public List<Path> getModelInput() {
        return modelInputFiles;
    }

    public void setModelInput(List<Path> modelInput) {
        this.modelInputFiles = modelInputFiles;
    }

    public List<Path> getMappingFiles() {
        return mappingFiles;
    }

    public void setMappingFiles(List<Path> mappingFiles) {
        this.mappingFiles = mappingFiles;
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
        if (modelInputFiles == null) {
            return "Input model is missing.";
        }
        if (!Files.exists(modelInputFiles.getFirst())) {
            return "Input model does not exist: " + modelInputFiles;
        }

        // at least one apply option should be selected
        if (!applyLines && !applyPowerTransformer && !applySynchronousMachine) {
            return "Select at least one 'Apply to' option (Lines / PowerTransformer / SynchronousMachine).";
        }

        // mapping file rules (adjust if your workflow allows empty mapping)
        if (mappingFiles == null) {
            return "Mapping file is missing.";
        }
        if (!Files.exists(mappingFiles.getFirst())) {
            return "Mapping file does not exist: " + mappingFiles;
        }

        return null;
    }

    @Override
    public void execute(SelectedTask parent) throws Exception {
        String validationError = validateInputs();
        if (validationError != null) {
            throw new IllegalStateException(validationError);
        }

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent, "Running Add Switching Devices", "1%", wizardContext);

        ModelManipulationFactory.ModifyIGM(
                modelInputFiles,
                mappingFiles,
                standard,
                applyLines,
                applyPowerTransformer,
                applySynchronousMachine,
                onlyForEquipmentInMappingFile,
                exportMappingFile
        );

        this.status = "Finished";
        this.info = "100%";
        taskUpdater.updateState(parent, this.status, this.info, wizardContext);
    }


}