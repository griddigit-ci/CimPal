package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.VBox;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskInputElementsFactory {

    private static final Logger LOG = LoggerFactory.getLogger(TaskInputElementsFactory.class);


    public VBox constructTaskInputElements(VBox vBoxForTaskInputs, WizardContext wizardContext) {
        var selectedTasks = wizardContext.getSelectedTasks();
        var taskElementsMap = wizardContext.getTaskElements();
        selectedTasks.forEach(selectedTask -> {
            try {
                 vBoxForTaskInputs.getChildren().add(FXMLLoader.load(getClass().getResource(selectedTask.getTask().getPathToFXMLComponent())));
            } catch (IOException e) {
                LOG.error("Unhandled exception", e);
            }
        });
        return vBoxForTaskInputs;
    }
}
