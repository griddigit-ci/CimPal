package eu.griddigit.cimpal.main.application.services;

import application.taskControllers.WizardContext;
import application.tasks.SelectedTask;
import javafx.application.Platform;

public class TaskStateUpdater {
    public void updateState(SelectedTask currentTask, String status, String percentComplete, WizardContext wizardContext) {
        Platform.runLater(() ->  {
            currentTask.updateStatus(status);
            currentTask.updateInfo(percentComplete);
            wizardContext.getTableForTaskStatus().setItems (wizardContext.getSelectedTasks());
            wizardContext.getTableForTaskStatus().refresh();
        });
    }
}
