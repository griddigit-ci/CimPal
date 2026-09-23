package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.MultiplyAndConnect;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

public class MultiplyNTimesAndConnectController implements Initializable {
    @FXML
    public TextField timesToMultiply;
    @FXML
    public CheckBox saveResult;

    @FXML
    public TextField connectivityNodeId;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        wizardContext = WizardContext.getInstance();
        // Add change listener to text filed to update the Task information with the new times to multiply
        timesToMultiply.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyAndConnect task = (MultiplyAndConnect) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyAndConnect.class).findFirst().get().getTask();
            // Clearing the field, or typing anything that is not a number, used to throw out of the
            // listener and leave the page in a broken state. validateInputs() reports the empty
            // value when the user moves on, which is where it belongs.
            task.setTimesToMultiply(parseCount(newValue));
        });

        // Add change listener to text filed to update the Task information with the new connectivity node
        connectivityNodeId.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyAndConnect task = (MultiplyAndConnect) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyAndConnect.class).findFirst().get().getTask();
            task.setConnectivityNodeId(newValue);
        });

        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyAndConnect task = (MultiplyAndConnect) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyAndConnect.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }

    private static Integer parseCount(String text) {
        try {
            return text == null || text.isBlank() ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
