/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.services.TaskDependenciesEnforcer;
import eu.griddigit.cimpal.main.application.tasks.GenerateInstanceDataModel;
import eu.griddigit.cimpal.main.application.tasks.ITask;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import eu.griddigit.cimpal.main.application.datagenerator.DataGeneratorModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.SupportedRDFSProfiles;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskSelectionController implements Initializable, IController {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSelectionController.class);


    public TableColumn removeColumn;
    @FXML
    private ChoiceBox fcbProfileVersionIDG;

    @FXML
    private ChoiceBox<String> rdfsFormatsDropdown;

    @FXML
    private Button rdfsProfileBrowseButton;

    @FXML
    public TextArea fieldTextProfileIDG;

    @FXML
    private ListView<String> tasksForSelection;

    @FXML
    private TableView<SelectedTask> tableForSelectedTasks;

    @FXML
    private TextArea baseInstanceModelFilesPaths;

    @FXML
    private TextArea rdfsProfileBaseNamespaceTextField;

    private ObservableList<String> availableTasks;
    private WizardContext context;
    private TaskDependenciesEnforcer taskSelectionController;
    private List<File> selectedBaseInstanceModelFilePaths;
    private List<File> selectedProfileIDG;
    private SupportedRDFSProfiles supportedRDFSProfiles;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        try {
            taskSelectionController = new TaskDependenciesEnforcer();
        } catch (IOException | URISyntaxException e) {
            LOG.error("Unhandled exception", e);
        }
        try {
            supportedRDFSProfiles = new SupportedRDFSProfiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        context = WizardContext.getInstance();
        context.setDataGeneratorModel(new DataGeneratorModel());
        availableTasks = FXCollections.observableArrayList();

        // Add all available tasks in the list from the context
        for (String key : context.getTaskElements().keySet()) {
            availableTasks.add(context.getTaskElements().get(key).getName());
        }

        tableForSelectedTasks.setItems(context.getSelectedTasks());
        tasksForSelection.setItems(availableTasks);
        tasksForSelection.setCellFactory(list -> new TaskNameCell());

        // Bind clicking as a selection and add it to the Table of Selected Tasks
        tasksForSelection.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                // Clearing the selection - which the unavailable branch below does - fires this
                // listener with a null name, and null is not a task to add.
                if (newValue == null) {
                    return;
                }

                ITask task = context.getTaskElements().get(newValue);

                // Unavailable tasks are greyed out in the list, but keyboard navigation still
                // reaches them, so refuse them here too rather than letting a run start and fail
                // part way through, after earlier tasks have already written files.
                if (task == null || !task.isAvailable()) {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Task not available");
                    a.setHeaderText(newValue);
                    a.setContentText(task == null
                            ? "This task is not registered."
                            : task.getUnavailableReason());
                    a.show();
                    Platform.runLater(() -> tasksForSelection.getSelectionModel().clearSelection());
                    return;
                }

                var allowed = taskSelectionController.checkIfAddingTaskIsAllowed(newValue);
                if (allowed.isEmpty()) {
                    var nextTaskIndex = context.getSelectedTasks().size() + 1;
                    var selectedTask = new SelectedTask(task, nextTaskIndex);
                    context.addSelectedTasks(selectedTask);
                } else {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Action Disallowed");
                    a.setContentText("Task " + newValue + " cannot be selected because you selected: " + allowed);
                    a.show();
                }
            }
        });

        // Set double-click action for removing a selected task
        tableForSelectedTasks.setOnMousePressed(event -> {
            if (event.isPrimaryButtonDown() && event.getClickCount() == 2 && tableForSelectedTasks.getSelectionModel().getSelectedCells().getFirst().getColumn() == 2) {
                context.removeSelectedTask(tableForSelectedTasks.getSelectionModel().getFocusedIndex());
                SelectedTask selectedItem = tableForSelectedTasks.getSelectionModel().getSelectedItem();
                tableForSelectedTasks.getItems().remove(selectedItem);
            }
        });

        // Set selection action on RDFS Profile Version selection
        fcbProfileVersionIDG.getSelectionModel().selectedIndexProperty().addListener((observableValue, number, number2) ->
        {
            var selectedRDFSProfileVersion = (String) fcbProfileVersionIDG.getItems().get((Integer) number2);
            rdfsProfileBaseNamespaceTextField.setText(supportedRDFSProfiles.getSupportedRDFSProfileBaseNamespaceFromName(selectedRDFSProfileVersion));
            fieldTextProfileIDG.setText(Arrays.toString(supportedRDFSProfiles.getSupportedRDFSProfileFilesFromName(selectedRDFSProfileVersion)));
            if (supportedRDFSProfiles.getSupportedRDFSProfileBaseNamespaceFromName(selectedRDFSProfileVersion).isEmpty()) {
                rdfsProfileBrowseButton.setDisable(false);
                rdfsProfileBaseNamespaceTextField.setDisable(false);
            } else{
                rdfsProfileBrowseButton.setDisable(true);
                rdfsProfileBaseNamespaceTextField.setDisable(true);
            }
            context.getDataGeneratorModel().setRdfsProfileVersion(supportedRDFSProfiles.getRDFSProfileFromName(selectedRDFSProfileVersion));
        });

        // Set change listener for Base namespace for the Other usage
        rdfsProfileBaseNamespaceTextField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (context.getDataGeneratorModel().getRdfsProfileVersion() != null && context.getDataGeneratorModel().getRdfsProfileVersion().getName().equals("Other")) {
                context.getDataGeneratorModel().getRdfsProfileVersion().setBaseNamespace(newValue);
            }
        });

        fcbProfileVersionIDG.getItems().addAll(List.of(supportedRDFSProfiles.getSupportedRDFSProfileNames()));

        //Adding action to the choice box
        fcbProfileVersionIDG.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBInstanceDataProfileformat());
        rdfsFormatsDropdown.getItems().addAll(
                "RDFS (augmented) v2019 and v2020 by CimSyntaxGen"
        );
        rdfsFormatsDropdown.setValue("RDFS (augmented) v2019 and v2020 by CimSyntaxGen");

        // Pass reference to context to this controller in a safe way
        Platform.runLater(() -> context.setCurrentController(this));
    }

    @FXML
    public void browseBaseInstance(ActionEvent actionEvent) {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select Base Instance Model Files", "*.zip", "*.xml", "*.ttl"));
        PathMemory.prepare(filechooser, "dialog.wizard.baseInstanceModels");

        selectedBaseInstanceModelFilePaths = filechooser.showOpenMultipleDialog(null);

        if (selectedBaseInstanceModelFilePaths != null) {
            baseInstanceModelFilesPaths.setText(selectedBaseInstanceModelFilePaths.toString());

            String[] filePaths = new String[selectedBaseInstanceModelFilePaths.size()];
            for (int i = 0; i < selectedBaseInstanceModelFilePaths.size(); i++) {
                filePaths[i] = selectedBaseInstanceModelFilePaths.get(i).toString();
            }
            context.getDataGeneratorModel().setBaseInstanceModelPath(filePaths);
            context.setLastOpenedDir(selectedBaseInstanceModelFilePaths.getFirst().getParentFile());
            PathMemory.remember("dialog.wizard.baseInstanceModels", selectedBaseInstanceModelFilePaths.getFirst().getParentFile());
        } else {
            baseInstanceModelFilesPaths.clear();
        }

        // If more than one selected it is a unionModel
    }

    public void actionBrowseProfileIDG(ActionEvent actionEvent) {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF profile file", "*.rdf", "*.ttl"));
        PathMemory.prepare(filechooser, "dialog.wizard.profileRdfs");
        selectedProfileIDG = filechooser.showOpenMultipleDialog(null);

        if (selectedProfileIDG != null) {// the file is selected
            fieldTextProfileIDG.setText(selectedProfileIDG.toString());
            String[] filePaths = new String[selectedProfileIDG.size()];
            for (int i = 0; i < selectedProfileIDG.size(); i++) {
                filePaths[i] = selectedProfileIDG.get(i).toString();
            }
            context.getDataGeneratorModel().getRdfsProfileVersion().setPathToRDFSFiles(filePaths);
            context.setLastOpenedDir(selectedProfileIDG.getFirst().getParentFile());
            PathMemory.remember("dialog.wizard.profileRdfs", selectedProfileIDG.getFirst().getParentFile());
        } else {
            fieldTextProfileIDG.clear();
        }
    }

    //Action for choice box "Profile version" related to Instance data comparison
    private void actionCBInstanceDataProfileformat() {
        // Nothing to do: the profile version is read straight from the choice box when the task
        // runs. Kept because the FXML binds it.
    }

    public boolean validateInputs() {
        var validInputs = true;
        var message = "";

        if (fieldTextProfileIDG.getText().isEmpty()) {
            message = message + "RDF Profile files not selected! \n";
        }

        // Generate Instance Data Model is the one task that makes its own models rather than
        // reading them, so it is the one case where base instance files are not needed. It is
        // currently not selectable - see GenerateInstanceDataModel.isAvailable() - which is why
        // the message no longer offers it as an alternative; the check is kept for when it is.
        if (baseInstanceModelFilesPaths.getText().isEmpty() && (!context.getSelectedTasks().isEmpty() && context.getSelectedTasks().getFirst().getTask().getClass() != GenerateInstanceDataModel.class) ) {
            message = message + "Base Instance Model files not selected! \n";
        }

        if (context.getSelectedTasks().isEmpty()) {
            message = message + "Please select at least one task to execute! \n";
        }

        if (!message.isEmpty()) {
            validInputs = false;
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText(message);
            alert.setHeaderText(null);
            alert.setTitle("Error - violation of input parameters.");
            alert.showAndWait();
        }

        return validInputs;
    }

    /**
     * Renders one task name, greying out the tasks that are registered but not offered.
     * <p>
     * An unfinished task stays in the list rather than disappearing from it, so it is clear the
     * feature exists and is coming; the tooltip carries the reason it cannot be picked yet.
     */
    private final class TaskNameCell extends ListCell<String> {
        @Override
        protected void updateItem(String taskName, boolean empty) {
            super.updateItem(taskName, empty);

            if (empty || taskName == null) {
                setText(null);
                setTooltip(null);
                setDisable(false);
                return;
            }

            ITask task = context.getTaskElements().get(taskName);
            boolean available = task == null || task.isAvailable();

            setText(available ? taskName : taskName + "  (not available)");
            setDisable(!available);
            setTooltip(available ? null : new Tooltip(task.getUnavailableReason()));
        }
    }
}