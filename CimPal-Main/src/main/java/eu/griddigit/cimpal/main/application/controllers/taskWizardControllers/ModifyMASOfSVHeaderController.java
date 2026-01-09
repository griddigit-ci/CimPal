package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import application.tasks.ModifyMASOfSVHeader;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

public class ModifyMASOfSVHeaderController implements Initializable {

    @FXML
    public CheckBox saveResult;
    @FXML
    public TextField newMASOfSVHeader;
    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();

        // Add change listener to text filed to update the Task information with the new MAS of SV header
        newMASOfSVHeader.textProperty().addListener((observable, oldValue, newValue) -> {
            ModifyMASOfSVHeader task = (ModifyMASOfSVHeader) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == ModifyMASOfSVHeader.class).findFirst().get().getTask();
            task.setNewMASOfSVHeader(newValue);
        });

        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            ModifyMASOfSVHeader task = (ModifyMASOfSVHeader) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == ModifyMASOfSVHeader.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }
}
