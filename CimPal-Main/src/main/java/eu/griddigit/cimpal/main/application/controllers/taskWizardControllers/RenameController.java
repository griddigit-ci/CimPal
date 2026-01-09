package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import application.taskControllers.WizardContext;
import application.tasks.Rename;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

public class RenameController implements Initializable {
    @FXML
    public TextField loadedXMLFile;
    @FXML
    public CheckBox saveResult;
    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            Rename task = (Rename) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == Rename.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });

    }

    @FXML
    public void browseRenameMultiplyConfigXML(ActionEvent actionEvent) {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Input data XLS", "*.xlsx"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        File file = filechooser.showOpenDialog(null);

        if (file != null ) {// the file is selected
            //MainController.prefs.put("LastWorkingFolder", file.getParent());
            loadedXMLFile.setText(file.toString());
            //MainController.inputXLSCGM = file;
            Rename task = (Rename) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == Rename.class).findFirst().get().getTask();
            task.setIRenameConfigXmlFilePath(file.toString());
            wizardContext.setLastOpenedDir(file.getParentFile());
        }

        else {
            loadedXMLFile.clear();
        }
    }
}