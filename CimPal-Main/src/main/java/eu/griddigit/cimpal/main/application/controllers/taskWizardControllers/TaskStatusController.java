package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import application.services.TaskExecutionService;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

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

    public void openOutputDirectory(ActionEvent actionEvent) throws IOException {
        var command = "explorer /open, " + context.getOutputDirectory();
        Runtime.getRuntime().exec(command);
    }

    @Override
    public boolean validateInputs() {
        return true;
    }
}
