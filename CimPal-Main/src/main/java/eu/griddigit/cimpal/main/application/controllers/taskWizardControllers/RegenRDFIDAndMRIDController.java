package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
import application.tasks.RegenerateRDFIDAndMRID;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;

import java.net.URL;
import java.util.ResourceBundle;

public class RegenRDFIDAndMRIDController implements Initializable {
    @FXML
    public CheckBox saveResult;
    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            RegenerateRDFIDAndMRID task = (RegenerateRDFIDAndMRID) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == RegenerateRDFIDAndMRID.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });

    }
}
