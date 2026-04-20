package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
import eu.griddigit.cimpal.main.application.tasks.DeleteRequiredProperties;
import eu.griddigit.cimpal.main.application.tasks.GenerateInstanceDataModel;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

public class GenerateInstanceDataModelController implements Initializable {

    @FXML
    private TextField loadedInputFile;
    @FXML
    public CheckBox saveResult;
    @FXML
    private ChoiceBox fcbGenDataFormat;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        fcbGenDataFormat.getItems().addAll(
                "61970-552 CIM XML (.xml)",
                "Custom RDF XML Plain (.xml)",
                "RDF Turtle (.ttl)"
        );
        fcbGenDataFormat.getSelectionModel().selectFirst();

        wizardContext = WizardContext.getInstance();

        //Once selected - save the saveResult value to task so it can be processed later
        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            GenerateInstanceDataModel task = (GenerateInstanceDataModel) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == GenerateInstanceDataModel.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });

        //Once selected - save the saveResult value to task so it can be processed later
        fcbGenDataFormat.getSelectionModel().selectedIndexProperty().addListener((observableValue, number, number2) -> {
            GenerateInstanceDataModel task = (GenerateInstanceDataModel) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == GenerateInstanceDataModel.class).findFirst().get().getTask();
            task.setFormatGeneratedModel((String) fcbGenDataFormat.getItems().get((Integer) number2));
        });
    }

    @FXML
    private void actionBrowseDataTemplate() {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Input template instance data XLS", "*.xlsx"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));

        File selectedFilePath = null;
        selectedFilePath = filechooser.showOpenDialog(null);


        if (selectedFilePath != null) {// the file is selected set the Task that will execute them to have the values
                loadedInputFile.setText(selectedFilePath.toString());
                GenerateInstanceDataModel task = (GenerateInstanceDataModel) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == DeleteRequiredProperties.class).findFirst().get().getTask();
                task.setTemplateDataFilePath(selectedFilePath.getPath());
                wizardContext.setLastOpenedDir(selectedFilePath.getParentFile());
        }
        else{
            loadedInputFile.clear();
        }

        // If more than one selected it is a unionModel
    }

}