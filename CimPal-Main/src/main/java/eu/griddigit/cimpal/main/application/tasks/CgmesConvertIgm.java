package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.core.InstanceDataFactory;
import javafx.scene.control.ProgressIndicator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import eu.griddigit.cimpal.core.converters.ModelManipulationFactory;

import static eu.griddigit.cimpal.core.converters.ModelManipulationFactory.LoadRDFS;

public class CgmesConvertIgm implements ITask {

        private String name;
        private String pathToFXML;
        private String status;
        private String info;
        private TaskStateUpdater taskUpdater;
        private String newHeaderDescription;
        private boolean saveResult;

    public CgmesConvertIgm() {
        this.name = "CGMES 2.4 → 3.0: Convert IGM (multi-file selection";
        this.pathToFXML = "/fxml/wizardPages/taskElements/cgmesConvertIgm.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }
    private final List<Path> inputFiles = new ArrayList<>();

    private boolean keepExtensions = false;
    private boolean convertOnlyEq = false;
    private boolean alignRegulatingControlTargets = false;

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return "/fxml/wizardPages/taskElements/cgmesConvertIgm.fxml";
    }

    public List<Path> getInputFiles() {
        return inputFiles;
    }

    public void setInputFiles(List<Path> files) {
        inputFiles.clear();
        if (files != null) {
            inputFiles.addAll(files);
        }
    }

    public boolean isKeepExtensions() {
        return keepExtensions;
    }

    public void setKeepExtensions(boolean keepExtensions) {
        this.keepExtensions = keepExtensions;
    }

    public boolean isConvertOnlyEq() {
        return convertOnlyEq;
    }

    public void setConvertOnlyEq(boolean convertOnlyEq) {
        this.convertOnlyEq = convertOnlyEq;
    }

    public boolean isAlignRegulatingControlTargets() {
        return alignRegulatingControlTargets;
    }

    public void setAlignRegulatingControlTargets(boolean alignRegulatingControlTargets) {
        this.alignRegulatingControlTargets = alignRegulatingControlTargets;
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
        if (inputFiles.isEmpty()) {
            return "No IGM files selected.";
        }

        boolean hasEq  = hasProfileToken("EQ");
        boolean hasSsh = hasProfileToken("SSH");
        boolean hasTp  = hasProfileToken("TP");
        boolean hasSv  = hasProfileToken("SV");

        if (!hasEq) {
            return "EQ profile file is missing.";
        }

        if (!convertOnlyEq) {
            if (!hasSsh || !hasTp || !hasSv) {
                return "SSH/TP/SV profiles are required unless 'Convert only EQ' is selected.";
            }
        }

        return null;
    }

    private boolean hasProfileToken(String token) {
        String t = token.toUpperCase(Locale.ROOT);
        for (Path p : inputFiles) {
            if (p == null) continue;
            String name = p.getFileName().toString().toUpperCase(Locale.ROOT);
            // common CGMES naming uses EQ/SSH/TP/SV in file name
            if (name.contains(t)) return true;
        }
        return false;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);


        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);



        ModelManipulationFactory.ConvertBoundarySetCGMESv2v3();

        System.out.print("Conversion finished.\n");
        progressBar.setProgress(1);
    }
}