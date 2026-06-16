package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.shacl_tools.ShaclAutoTester;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ResourceBundle;

public class SHACLTesterController implements Initializable {
    private MainController mainController;

    private List<File> selectedFile;
    private File selectedFolder;

    @FXML
    private TextField fPathShaclFilesValidator;
    @FXML
    private TextField fPathModelsForShaclValidator;
    @FXML
    private TreeView<String> treeViewShaclFiles;
    @FXML
    private CheckBox cbExportReports;

    @Override
    public void initialize(URL location, ResourceBundle resources) {

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
    private void actionBrowseShaclFilesTester(ActionEvent actionEvent) {
        selectedFile = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "SHACL Shape file", List.of("*.rdf", "*.ttl"), "");
        if (selectedFile != null) {
            StringBuilder paths = new StringBuilder();
            for (File file : selectedFile) {
                paths.append(", ").append(file.toString());
            }
            fPathShaclFilesValidator.setText(paths.toString());
        }
    }

    @FXML
    private void actionBrowseFolderPathForShaclTester(ActionEvent actionEvent) {
        selectedFolder = eu.griddigit.cimpal.main.util.ModelFactory.folderChooserCustom();
        if (selectedFolder != null) {
            fPathModelsForShaclValidator.setText(selectedFolder.toString());

            GUIhelper.buildFileTree(selectedFolder, treeViewShaclFiles);
        }
    }

    @FXML
    private void actionBtnRunShaclValidator() throws IOException {
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        List<File> fileL = new ArrayList<>();
        try {
            Files.walkFileTree(selectedFolder.toPath(), EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".zip")) {
                        fileL.add(file.toFile()); // Changed from new File(file.getFileName().toString())
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Error while searching for files", "An error occurred while searching for files in the selected folder.", e);
        }

        if (!fileL.isEmpty()) {
            ShaclAutoTester shaclAutoTester = new ShaclAutoTester(new ShaclAutoTesterCallback() {
                @Override
                public void updateProgress(double progress) {
                    Platform.runLater(() -> setProgressBar(progress));
                }

                @Override
                public void appendOutput(String message) {
                    mainController.appendText(message);
                }
            });
            shaclAutoTester.runTests(selectedFile, selectedFolder, fileL, cbExportReports.isSelected());

            setProgressBar(1);
        } else {
            resetProgressBar();
        }
    }

    public void actionBtnResetShaclValidator(ActionEvent actionEvent) {
        fPathShaclFilesValidator.clear();
        fPathModelsForShaclValidator.clear();
        selectedFile = null;
    }
}
