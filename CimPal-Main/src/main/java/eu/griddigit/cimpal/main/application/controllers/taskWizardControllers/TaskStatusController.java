package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.services.TaskExecutionService;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskStatusController implements Initializable, IController {
    @FXML
    private TableView tableForTaskStatus;

    @FXML
    private TextArea executionOutput;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private TaskExecutionService taskExecutionService;
    private WizardContext context;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        // Set up and execute the individual selected tasks, passing in the context to execute them in order of selection
        taskExecutionService = new TaskExecutionService();
        context = WizardContext.getInstance();
        // Used for displaying errors and interim messages on the screen where needed
        context.setExecutionTextArea(executionOutput);
        tableForTaskStatus.setItems(context.getSelectedTasks());
        context.setTableForTaskStatus(tableForTaskStatus);
        Thread worker = taskExecutionService.executeSelectedTasks(context);
        running.set(true);
    }

    public void cancelTasksExecution(ActionEvent actionEvent) {
        running.set(false);
        taskExecutionService.cancelExecutionOfSelectedTasks(WizardContext.getInstance());
        System.out.println("task Canceled");
    }

    public void resetExecution(ActionEvent actionEvent) {
        this.cancelTasksExecution(null);
        context.backWizardPane();
        context.backWizardPane();
        context.clearContext();
    }

    public void openOutputDirectory(ActionEvent actionEvent) {
        File dir = context.getOutputDirectory();
        if (dir == null || !dir.isDirectory()) {
            GUIhelper.showUserFriendlyError("No output directory",
                    "The task has not produced an output directory yet.", null);
            return;
        }
        try {
            // Desktop.open hands the path to the shell as a single opaque argument. The
            // previous form built a command string and passed it to Runtime.exec(String),
            // which tokenises on whitespace - so a path could inject extra arguments, and
            // any path containing a space simply broke. It also named "explorer"
            // unqualified, which Windows resolves against the application and current
            // directories before System32, allowing a planted executable to run instead.
            Desktop.getDesktop().open(dir.getCanonicalFile());
        } catch (IOException | UnsupportedOperationException e) {
            GUIhelper.showUserFriendlyError("Could not open the folder",
                    "The output folder could not be opened: " + dir, e);
        }
    }

    @Override
    public boolean validateInputs() {
        return true;
    }
}
