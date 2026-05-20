package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.CGMESConverter.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.CGMESConverter.requests.AddSwitchingDevicesRequest;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CgmesAddSwitchingDevices implements ITask {

    private final String name;
    private final String pathToFXML;
    private String status;
    private String info;
    private final TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public enum DataExchangeStandard {
        CGMES_2_4, CGMES_3_0
    }

    private final List<Path> modelInputFiles = new ArrayList<>();
    private Path mappingFile;

    private DataExchangeStandard standard = DataExchangeStandard.CGMES_2_4;

    private boolean applyLines = false;
    private boolean applyPowerTransformer = false;
    private boolean applySynchronousMachine = false;

    private boolean onlyForEquipmentInMappingFile = false;
    private boolean exportMappingFile = false;

    public CgmesAddSwitchingDevices() {
        this.name = "Add Switching Devices";
        this.pathToFXML = "/fxml/wizardPages/taskElements/cgmesAddSwitchingDevices.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return pathToFXML;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public String getInfo() {
        return info;
    }

    @Override
    public boolean getSaveResult() {
        return saveResult;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    public List<Path> getModelInputFiles() {
        return modelInputFiles;
    }

    public void setModelInputFiles(List<Path> files) {
        modelInputFiles.clear();
        if (files != null) {
            modelInputFiles.addAll(files);
        }
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
    public String validateInputs() {
        if (modelInputFiles.isEmpty()) {
            return "Input model files are missing.";
        }
        for (Path p : modelInputFiles) {
            if (p == null || !Files.exists(p)) {
                return "Input model file does not exist: " + p;
            }
        }

        if (!applyLines && !applyPowerTransformer && !applySynchronousMachine) {
            return "Select at least one 'Apply to' option.";
        }

        if ((onlyForEquipmentInMappingFile || exportMappingFile) && mappingFile == null) {
            return "Mapping file is required for the selected options.";
        }

        if (mappingFile != null && !Files.exists(mappingFile)) {
            return "Mapping file does not exist: " + mappingFile;
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
        this.status = "Running";
        this.info = "1%";
        taskUpdater.updateState(parent, this.status, this.info, wizardContext);

        AddSwitchingDevicesRequest request = new AddSwitchingDevicesRequest(
                List.copyOf(modelInputFiles),
                mappingFile,
                standard,
                mappingFile != null,
                applyLines,
                applyPowerTransformer,
                applySynchronousMachine,
                exportMappingFile,
                onlyForEquipmentInMappingFile,
                saveResult
        );

        ModelManipulationFactory.modifyIGM(request, parent, wizardContext);

        this.status = "Finished";
        this.info = "100%";
        taskUpdater.updateState(parent, this.status, this.info, wizardContext);
    }
}