package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.CGMESConverter.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    private boolean keepExtensions = true;


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

    public boolean isKeepExtensions() {
        return keepExtensions;
    }

    public void setKeepExtensions(boolean keepExtensions) {
        this.keepExtensions = keepExtensions;
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
        String validationError = validateInputs();
        if (validationError != null) {
            throw new IllegalStateException(validationError);
        }

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent, "Loading boundary data", "1%", wizardContext);

        List<Path> boundaryFiles;
        try (var stream = Files.list(boundaryDatasetFolder)) {
            boundaryFiles = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .sorted()
                    .toList();
        }

        if (boundaryFiles.isEmpty()) {
            throw new IllegalStateException("No XML files found in folder: " + boundaryDatasetFolder);
        }

        File outputDirectory = wizardContext.getOutputDirectory();

        if (convertToV3) {
            taskUpdater.updateState(parent, "Converting boundary to CGMESv3.0", "20%", wizardContext);
            ModelManipulationFactory.ConvertBoundarySetCGMESv2v3(boundaryFiles, outputDirectory, keepExtensions);
        }
        if (splitBoundaryAndReference) {
            taskUpdater.updateState(parent, "Splitting boundary and reference data", "60%", wizardContext);
            ModelManipulationFactory.SplitBoundaryAndRefData(boundaryFiles, outputDirectory, keepExtensions);
        }
        if (splitPerTsoBorder) {
            taskUpdater.updateState(parent, "Splitting boundary per TSO border", "80%", wizardContext);
            ModelManipulationFactory.SplitBoundaryPerBorder(boundaryFiles, outputDirectory);
        }

        this.status = "Conversion finished";
        this.info = "100%";
        taskUpdater.updateState(parent, this.status, this.info, wizardContext);
    }
}