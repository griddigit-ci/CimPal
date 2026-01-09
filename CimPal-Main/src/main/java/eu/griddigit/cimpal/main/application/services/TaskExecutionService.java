package eu.griddigit.cimpal.main.application.services;

import application.taskControllers.WizardContext;
import datagenerator.GuiHelper;
import javafx.concurrent.Task;

import java.io.FileNotFoundException;
import java.util.HashMap;

// Multithreading class to manage Tasks execution on a separate thread from the UI
public class TaskExecutionService {

    // Method that marks all non completed tasks with a Cancelled label on the status screen
    public void cancelExecutionOfSelectedTasks(WizardContext wizardContext) {
        wizardContext.getSelectedTasks().forEach((selectedTask) -> {
            if (selectedTask.getStatus() != "Completed") {
                selectedTask.updateStatus("Cancelled");
                selectedTask.updateInfo("0%");
            }
        });

        wizardContext.getTableForTaskStatus().setItems (wizardContext.getSelectedTasks());
        wizardContext.getTableForTaskStatus().refresh();
    }

    public Thread executeSelectedTasks(WizardContext wizardContext) {
        Task<Void> task = new Task<Void>() {
            @Override protected Void call() throws Exception {
                wizardContext.getSelectedTasks().forEach((selectedTask) -> {
                    if (isCancelled()) {
                        updateMessage("Cancelled");
                        return;
                    }
                    try {
                        selectedTask.getTask().execute(selectedTask);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        GuiHelper.appendTextToOutputWindow(wizardContext.getExecutionTextArea(), "Error at Task: " + selectedTask.getName(), true);
                        GuiHelper.appendTextToOutputWindow(wizardContext.getExecutionTextArea(),e.getMessage(), true);
                    } catch (Exception e) {
                        e.printStackTrace();
                        GuiHelper.appendTextToOutputWindow(wizardContext.getExecutionTextArea(), "Error at Task: " + selectedTask.getName(), true);
                        GuiHelper.appendTextToOutputWindow(wizardContext.getExecutionTextArea(),e.getMessage(), true);
                    }
                });
                wizardContext.saveInstanceModel((HashMap<String, Object>) wizardContext.getDataGeneratorModel().getSaveProperties(), null, false);
                return null;
            }
        };

        Thread taskThread = new Thread(task);
        taskThread.setDaemon(true);
        taskThread.start();
        return taskThread;
    }
}

