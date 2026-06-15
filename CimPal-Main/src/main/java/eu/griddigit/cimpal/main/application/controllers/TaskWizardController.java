package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;

public class TaskWizardController implements Initializable {
    private MainController mainController;

    private WizardContext wizardContext;

    @FXML
    private Button forwardWizardButton;
    @FXML
    private Button wizardTaskSelectionButton;
    @FXML
    private Button wizardTaskInputButton;
    @FXML
    private Button wizardTaskStatusButton;
    @FXML
    private BorderPane wizardBorderPane;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        wizardContext = WizardContext.getInstance();
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    private void setProgressBar(double progress) {
        if (mainController != null) {
            mainController.setProgressBarValue(progress);
        }
    }

    private void resetProgressBar() {
        if (mainController != null) {
            mainController.resetProgressBar();
        }
    }

    @FXML
    public void forwardWizardPane(ActionEvent actionEvent) throws IOException {
        //FXMLLoader currentController = new FXMLLoader(getClass().getResource(wizardContext.getCurrentWizardPane()));
        //currentController.load();
        //var controller = currentController.getController();
        //var validInputs = wizardPaneController.validateInputs();

        /*
        switch (wizardContext.getCurrentWizardPaneIndex()) {
            case 0:
                validInputs = wizardPaneController.validateInputs();
                break;
            case 1:
                TaskInputController controller2 = (TaskInputController) controller;
                validInputs = controller2.validateInputs();
                break;
            default:
                validInputs = true;
        }
        */
        if (wizardContext.getCurrentController().validateInputs()) {
            wizardContext.forwardWizardPane();

            wizardBorderPane.setCenter(FXMLLoader.load(Objects.requireNonNull(getClass().getResource(wizardContext.getCurrentWizardPane()))));

            this.updateWizardProgressButtons();
        }

    }

    @FXML
    public void backWizardPane(ActionEvent actionEvent) throws IOException {
        wizardContext.backWizardPane();
        wizardBorderPane.setCenter(FXMLLoader.load(Objects.requireNonNull(getClass().getResource(wizardContext.getCurrentWizardPane()))));
        this.updateWizardProgressButtons();
    }

    private void updateWizardProgressButtons() {
        switch (wizardContext.getCurrentWizardPaneIndex()) {
            case 0:
                wizardTaskSelectionButton.setDefaultButton(true);
                wizardTaskSelectionButton.setDisable(false);
                wizardTaskInputButton.setDefaultButton(false);
                wizardTaskInputButton.setDisable(true);
                wizardTaskStatusButton.setDefaultButton(false);
                wizardTaskStatusButton.setDisable(true);
                forwardWizardButton.setText("Next");
                forwardWizardButton.setDisable(false);
                break;
            case 1:
                wizardTaskSelectionButton.setDefaultButton(false);
                wizardTaskSelectionButton.setDisable(true);
                wizardTaskInputButton.setDefaultButton(true);
                wizardTaskInputButton.setDisable(false);
                wizardTaskStatusButton.setDefaultButton(false);
                wizardTaskStatusButton.setDisable(true);
                forwardWizardButton.setText("Execute");
                forwardWizardButton.setDisable(false);
                break;
            case 2:
                wizardTaskSelectionButton.setDefaultButton(false);
                wizardTaskSelectionButton.setDisable(true);
                wizardTaskInputButton.setDefaultButton(false);
                wizardTaskInputButton.setDisable(true);
                wizardTaskStatusButton.setDefaultButton(true);
                wizardTaskStatusButton.setDisable(false);
                forwardWizardButton.setDisable(true);
                break;
        }
    }
}
