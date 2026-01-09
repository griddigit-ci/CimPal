package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.tasks.MultiplyBaseModelNTimes;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

public class MultiplyNTimesController implements Initializable {
    @FXML
    public TextField timesToMultiply;
    @FXML
    public CheckBox saveResult;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        // Add change listener to text filed to update the Task information with the new times to multiply
        timesToMultiply.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyBaseModelNTimes task = (MultiplyBaseModelNTimes) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyBaseModelNTimes.class).findFirst().get().getTask();
            task.setTimesToMultiply(Integer.parseInt(newValue));
        });

        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyBaseModelNTimes task = (MultiplyBaseModelNTimes) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyBaseModelNTimes.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }
}
