package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.services.TaskInputElementsFactory;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

public class TaskInputController  implements Initializable, IController {

    public Button buttonSelectWorkingDirectory;

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

        // These defaults used to be read from CimPalWizardController.prefs, a leftover of the
        // standalone CimPal Wizard. That controller was bound to no FXML and constructed by no
        // code, so its initialize() - the only place that assigned the static - never ran and the
        // field was always null: opening this page threw. It has since been deleted.
        // MainController.prefs is the live handle on the same "CimPal" preferences node, so the
        // keys written by the old standalone wizard are still honoured. Nothing writes them any
        // more: the two directories are chosen on this page and remembered per dialog by
        // PathMemory, so on a fresh profile both reads fall through and validateInputs() asks for
        // an output directory. prefs stays null if reading the preferences store failed at
        // startup, hence the guard.
        Preferences prefs = MainController.prefs;
        if (prefs != null) {
            String outputDir = prefs.get("DefOutputDir", "");
            if (!outputDir.isEmpty()) {
                context.setOutputDirectory(new File(outputDir));
            }
            String workingDir = prefs.get("DefWorkingDir", "");
            if (!workingDir.isEmpty()) {
                context.setWorkingDirectory(new File(workingDir));
            }
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
        PathMemory.prepare(folderchooser, "dialog.wizard.workingDirectory");
        File selectFolder = folderchooser.showDialog(null);
        PathMemory.remember("dialog.wizard.workingDirectory", selectFolder);
        context.setWorkingDirectory(selectFolder);
    }

    public void selectOutputDirectory(ActionEvent actionEvent) {
        DirectoryChooser folderchooser = new DirectoryChooser();
        folderchooser.setTitle("Select output directory");
        PathMemory.prepare(folderchooser, "dialog.wizard.outputDirectory");
        File selectFolder = folderchooser.showDialog(null);
        PathMemory.remember("dialog.wizard.outputDirectory", selectFolder);
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
