package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.ChangeModelHeaderDescription;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

public class ChangeModelHeaderDescriptionController implements Initializable {
    @FXML
    public TextField newModelHeaderDescription;

    @FXML
    public CheckBox saveResult;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();

        // Add change listener to text filed to update the Task information with the new description
        newModelHeaderDescription.textProperty().addListener((observable, oldValue, newValue) -> {
            ChangeModelHeaderDescription task = (ChangeModelHeaderDescription) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == ChangeModelHeaderDescription.class).findFirst().get().getTask();
            task.setNewHeaderDescription(newValue);
        });

        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            ChangeModelHeaderDescription task = (ChangeModelHeaderDescription) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == ChangeModelHeaderDescription.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }
}