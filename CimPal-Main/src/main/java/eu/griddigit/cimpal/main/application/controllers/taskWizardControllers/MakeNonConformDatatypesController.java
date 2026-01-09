package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import application.tasks.MakeNonConformDatatypes;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class MakeNonConformDatatypesController implements Initializable {

    @FXML
    public CheckBox saveResult;
    @FXML
    private TextField loadedShaclFiles;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {

        wizardContext = WizardContext.getInstance();
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MakeNonConformDatatypes task = (MakeNonConformDatatypes) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MakeNonConformDatatypes.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }

    @FXML
    private void actionBrowseShaclFiles() {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("SHACL constraints", "*.rdf", "*.ttl", "*.xml"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));

        List<File> selectedFilesPaths = null;
        selectedFilesPaths = filechooser.showOpenMultipleDialog(null);


        if (selectedFilesPaths != null) {// the file is selected
            //MainController.prefs.put("LastWorkingFolder", selectedFilesPaths.get(0).getParent());
            loadedShaclFiles.setText(selectedFilesPaths.toString());
            MakeNonConformDatatypes task = (MakeNonConformDatatypes) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MakeNonConformDatatypes.class).findFirst().get().getTask();
            task.setShaclFilesPath(selectedFilesPaths.stream().map(x -> x.getPath()).toArray(String[]::new));
            wizardContext.setLastOpenedDir(selectedFilesPaths.get(0).getParentFile());
        }
        else{
            loadedShaclFiles.clear();
        }

        // If more than one selected it is a unionModel
    }

}
