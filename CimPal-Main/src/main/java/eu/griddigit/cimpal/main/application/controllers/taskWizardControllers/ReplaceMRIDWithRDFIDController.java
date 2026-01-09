package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
import application.tasks.AddMRIDAndRDFID;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;

import java.net.URL;
import java.util.ResourceBundle;

public class ReplaceMRIDWithRDFIDController implements Initializable {
    private WizardContext wizardContext;
    @FXML
    public CheckBox saveResult;
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            AddMRIDAndRDFID task = (AddMRIDAndRDFID) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == AddMRIDAndRDFID.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });

    }
}