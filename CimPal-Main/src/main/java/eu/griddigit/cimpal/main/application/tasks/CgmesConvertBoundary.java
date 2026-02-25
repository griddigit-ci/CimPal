package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CgmesConvertBoundary implements ITask {

    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private String newHeaderDescription;
    private boolean saveResult;

    public CgmesConvertBoundary() {
        this.name = "CGMES 2.4 → 3.0: Convert Boundary dataset";
        this.pathToFXML = "/fxml/wizardPages/taskElements/cgmesConvertBoundary.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }
    private Path boundaryDatasetFolder;

    private boolean convertToV3 = true;
    private boolean splitBoundaryAndReference = false;
    private boolean splitPerTsoBorder = false;


    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return "/fxml/wizardPages/taskElements/cgmesConvertBoundary.fxml";
    }

    public Path getBoundaryDatasetFolder() {
        return boundaryDatasetFolder;
    }

    public void setBoundaryDatasetFolder(Path boundaryDatasetFolder) {
        this.boundaryDatasetFolder = boundaryDatasetFolder;
    }

    public boolean isConvertToV3() {
        return convertToV3;
    }

    public void setConvertToV3(boolean convertToV3) {
        this.convertToV3 = convertToV3;
    }

    public boolean isSplitBoundaryAndReference() {
        return splitBoundaryAndReference;
    }

    public void setSplitBoundaryAndReference(boolean splitBoundaryAndReference) {
        this.splitBoundaryAndReference = splitBoundaryAndReference;
    }

    public boolean isSplitPerTsoBorder() {
        return splitPerTsoBorder;
    }

    public void setSplitPerTsoBorder(boolean splitPerTsoBorder) {
        this.splitPerTsoBorder = splitPerTsoBorder;
    }

    @Override
    public boolean getSaveResult() {
        return saveResult;
    }

    @Override
    public String getStatus() {
        return this.status;
    }

    @Override
    public String getInfo() {
        return this.info;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        if (boundaryDatasetFolder == null) {
            return "Boundary dataset folder is missing.";
        }
        if (!Files.isDirectory(boundaryDatasetFolder)) {
            return "Boundary dataset folder does not exist: " + boundaryDatasetFolder;
        }
        if (!convertToV3 && !splitBoundaryAndReference && !splitPerTsoBorder) {
            return "Nothing selected to do. Please enable at least one option.";
        }
        return null;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        // TODO: call Core boundary conversion service here
    }
}