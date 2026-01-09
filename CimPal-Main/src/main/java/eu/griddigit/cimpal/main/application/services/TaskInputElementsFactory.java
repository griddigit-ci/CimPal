package eu.griddigit.cimpal.main.application.services;

import application.taskControllers.WizardContext;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;

import java.io.IOException;

public class TaskInputElementsFactory {

    public VBox constructTaskInputElements(VBox vBoxForTaskInputs, WizardContext wizardContext) {
        var selectedTasks = wizardContext.getSelectedTasks();
        var taskElementsMap = wizardContext.getTaskElements();
        selectedTasks.forEach(selectedTask -> {
            try {
                 vBoxForTaskInputs.getChildren().add(FXMLLoader.load(getClass().getResource(selectedTask.getTask().getPathToFXMLComponent())));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        return vBoxForTaskInputs;
    }
}
