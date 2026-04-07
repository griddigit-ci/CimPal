package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.CgmesConvertIgm;
import eu.griddigit.cimpal.main.application.tasks.ITask;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class CgmesConvertIgmController {

    @FXML private TextField tfIgmFiles;
    @FXML private CheckBox cbKeepExtensions;
    @FXML private CheckBox cbConvertOnlyEq;
    @FXML private CheckBox cbAlignRegControlTargets;
    @FXML private CheckBox saveResult;

    private CgmesConvertIgm task;

    @FXML
    public void initialize() {

        WizardContext ctx = WizardContext.getInstance();
        if (ctx == null || ctx.getSelectedTasks() == null) return;
        task = findTask(ctx, CgmesConvertIgm.class);


        cbKeepExtensions.setSelected(task.isKeepExtensions());
        cbConvertOnlyEq.setSelected(task.isConvertOnlyEq());
        cbAlignRegControlTargets.setSelected(task.isAlignRegulatingControlTargets());
        saveResult.setSelected(task.getSaveResult()); // <-- fixed
        refreshFileSummary();

        cbKeepExtensions.selectedProperty().addListener((obs, o, n) -> task.setKeepExtensions(n));
        cbConvertOnlyEq.selectedProperty().addListener((obs, o, n) -> task.setConvertOnlyEq(n));
        cbAlignRegControlTargets.selectedProperty().addListener((obs, o, n) -> task.setAlignRegulatingControlTargets(n));
        saveResult.selectedProperty().addListener((obs, o, n) -> task.setSaveResult(n));
    }

    @FXML
    private void actionBrowseIgmFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select IGM profile files (EQ/SSH/TP/SV)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("RDF/XML", "*.xml", "*.rdf"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        Stage stage = (Stage) tfIgmFiles.getScene().getWindow();
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        List<Path> paths = files.stream().map(File::toPath).toList();
        task.setInputFiles(paths);

        refreshFileSummary();
    }

    private void refreshFileSummary() {
        List<Path> files = task.getInputFiles();
        if (files == null || files.isEmpty()) {
            tfIgmFiles.setText("");
            return;
        }
        if (files.size() <= 3) {
            tfIgmFiles.setText(files.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining("; ")));
        } else {
            tfIgmFiles.setText(files.size() + " files selected");
        }
    }

    private static <T extends ITask> T findTask(WizardContext ctx, Class<T> clazz) {
        return ctx.getSelectedTasks().stream()
                .map(SelectedTask::getTask)
                .filter(Objects::nonNull)
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Task not found in WizardContext: " + clazz.getSimpleName()));
    }
}