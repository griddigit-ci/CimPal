package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.services.TaskInputElementsFactory;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

public class TaskInputController  implements Initializable, IController {

    public Button buttonSelectWorkingDirectory;
    @FXML
    private AnchorPane taskInputAnchorPane;

    @FXML
    private VBox vBoxForTaskInputs;

    @FXML
    private CheckBox saveToZip;
    private WizardContext context;
    private TaskInputElementsFactory taskInputElementsFactory;
    private static boolean saveInZip=false;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        context = WizardContext.getInstance();
        taskInputElementsFactory = new TaskInputElementsFactory();
        taskInputElementsFactory.constructTaskInputElements(vBoxForTaskInputs, context);

        if (!CimPalWizardController.prefs.get("DefOutputDir", "").equals("")){
            File outputDir = new File(CimPalWizardController.prefs.get("DefOutputDir",""));
            context.setOutputDirectory(outputDir);
        }
        if (!CimPalWizardController.prefs.get("DefWorkingDir", "").equals("")){
            File workingDir = new File(CimPalWizardController.prefs.get("DefWorkingDir",""));
            context.setWorkingDirectory(workingDir);
        }

        saveInZip = saveToZip.isSelected();
        saveToZip.setOnAction(e -> {
            saveInZip = saveToZip.isSelected();
        });

        // Pass reference to context to this controller in a safe way
        Platform.runLater(() ->  context.setCurrentController(this));
    }

    public void selectWorkingDirectory(ActionEvent actionEvent) {
        DirectoryChooser folderchooser = new DirectoryChooser();
        folderchooser.setTitle("Select working directory");
        folderchooser.setInitialDirectory(new File(context.getLastOpenedDir().toString()));
        File selectFolder = folderchooser.showDialog(null);
        context.setWorkingDirectory(selectFolder);
    }

    public void selectOutputDirectory(ActionEvent actionEvent) {
        DirectoryChooser folderchooser = new DirectoryChooser();
        folderchooser.setTitle("Select output directory");
        folderchooser.setInitialDirectory(new File(context.getLastOpenedDir().toString()));
        File selectFolder = folderchooser.showDialog(null);
        context.setOutputDirectory(selectFolder);
    }

    public boolean validateInputs() {
        var valid = true;
        var message = "";
        var hasSaveInterimTaskResult = false;

        for (SelectedTask selectedTask : context.getSelectedTasks()) {
                message = message + selectedTask.getTask().validateInputs();
                hasSaveInterimTaskResult = hasSaveInterimTaskResult  ||  selectedTask.getTask().getSaveResult();
        }

        if ( hasSaveInterimTaskResult && context.getWorkingDirectory() == null) {
            message = message + "Please Select Working Directory!\n";
        }

        if (context.getOutputDirectory() == null) {
            message = message + "Please Select Output Directory!\n";
        }

        if (!message.isEmpty()) {
            valid = false;
            Alert alert = new Alert(Alert.AlertType.ERROR);
            TextArea area = new TextArea(message);
            area.setWrapText(true);
            area.setEditable(false);
            alert.setContentText("Please input the described below input fields!");
            alert.getDialogPane().setExpandableContent(area);
            alert.setResizable(true);
            alert.setHeaderText(null);
            alert.setTitle("Error - violation of input parameters.");
            alert.showAndWait();
        }
        return valid;
    }

    public static boolean getSaveInZip(){
        return saveInZip;
    }
}
