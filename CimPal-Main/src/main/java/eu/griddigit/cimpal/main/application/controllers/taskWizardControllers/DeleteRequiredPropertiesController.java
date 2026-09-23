package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
import eu.griddigit.cimpal.main.application.tasks.DeleteRequiredProperties;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class DeleteRequiredPropertiesController implements Initializable {
    public static List<File> shaclFileList;

    @FXML
    private TextField loadedShaclFiles;
    @FXML
    public CheckBox saveResult;
    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            DeleteRequiredProperties task = (DeleteRequiredProperties) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == DeleteRequiredProperties.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }

    @FXML
    private void actionBrowseShaclFiles() {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("SHACL constraints", "*.rdf", "*.ttl", "*.xml"));
        PathMemory.prepare(filechooser, "dialog.wizard.deleteRequiredProps.shaclFiles");

        List<File> selectedFilesPaths = null;
        selectedFilesPaths = filechooser.showOpenMultipleDialog(null);


        if (selectedFilesPaths != null) {// the file is selected set the Task that will execute them to have the values
                //MainController.prefs.put("LastWorkingFolder", selectedFilesPaths.get(0).getParent());
                loadedShaclFiles.setText(selectedFilesPaths.toString());
                DeleteRequiredProperties task = (DeleteRequiredProperties) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == DeleteRequiredProperties.class).findFirst().get().getTask();
                task.setShaclFilesPath(selectedFilesPaths.stream().map(x -> x.getPath()).toArray(String[]::new));
                wizardContext.setLastOpenedDir(selectedFilesPaths.get(0).getParentFile());
                PathMemory.remember("dialog.wizard.deleteRequiredProps.shaclFiles", selectedFilesPaths.get(0).getParentFile());
        }
        else{
            loadedShaclFiles.clear();
        }

        // If more than one selected it is a unionModel
    }

}