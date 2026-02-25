package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.CgmesConvertBoundary;
import eu.griddigit.cimpal.main.application.tasks.ITask;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class CgmesConvertBoundaryController {

    @FXML private TextField tfBoundaryFolder;
    @FXML private CheckBox cbConvertToV3;
    @FXML private CheckBox cbSplitBoundaryRef;
    @FXML private CheckBox cbSplitPerTsoBorder;
    @FXML private CheckBox saveResult;

    private CgmesConvertBoundary task;

    @FXML
    public void initialize() {
        task = getTaskFromContext(CgmesConvertBoundary.class);

        // init UI from task
        if (task.getBoundaryDatasetFolder() != null) {
            tfBoundaryFolder.setText(task.getBoundaryDatasetFolder().toString());
        }
        cbConvertToV3.setSelected(task.isConvertToV3());
        cbSplitBoundaryRef.setSelected(task.isSplitBoundaryAndReference());
        cbSplitPerTsoBorder.setSelected(task.isSplitPerTsoBorder());
        saveResult.setSelected(task.getSaveResult());

        // update task when user changes UI
        cbConvertToV3.selectedProperty().addListener((obs, o, n) -> task.setConvertToV3(n));
        cbSplitBoundaryRef.selectedProperty().addListener((obs, o, n) -> task.setSplitBoundaryAndReference(n));
        cbSplitPerTsoBorder.selectedProperty().addListener((obs, o, n) -> task.setSplitPerTsoBorder(n));
        saveResult.selectedProperty().addListener((obs, o, n) -> task.setSaveResult(n));
    }

    @FXML
    private void actionBrowseBoundaryFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Boundary dataset folder");

        Stage stage = (Stage) tfBoundaryFolder.getScene().getWindow();
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        Path p = dir.toPath();
        tfBoundaryFolder.setText(p.toString());
        task.setBoundaryDatasetFolder(p);
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