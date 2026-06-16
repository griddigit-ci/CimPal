package eu.griddigit.cimpal.main.application.controllers.sparql;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.apache.jena.rdf.model.Model;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class SparqlQueryTabController implements Initializable {
    private MainController mainController;

    private static final String DEFAULT_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";

    @FXML
    private TextArea txtSparqlQuery;

    @FXML
    private Label lblQueryFile;

    @FXML
    private Label lblStatus;

    @FXML
    private Label lblModelFiles;

    private final List<File> selectedModelFiles = new ArrayList<>();
    private File currentQueryFile;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setStatus("Ready to edit or import a SPARQL SELECT query.");
        setCurrentQueryFile(null);
        updateModelFilesLabel();
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
    private void actionSelectModelFiles(ActionEvent actionEvent) {
        List<File> modelFiles = ModelFactory.fileChooserCustom(
                false,
                "Instance files",
                List.of("*.xml", "*.zip"),
                "Select RDF/XML or ZIP model files"
        );

        if (modelFiles == null || modelFiles.isEmpty()) {
            setStatus("No RDF/XML or ZIP model files were selected.");
            return;
        }

        selectedModelFiles.clear();
        selectedModelFiles.addAll(modelFiles);
        MainController.IDModel1 = new ArrayList<>(modelFiles);
        updateModelFilesLabel();
        setStatus("Selected " + selectedModelFiles.size() + " model file(s).");
    }

    @FXML
    private void actionLoadQuery(ActionEvent actionEvent) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SPARQL query");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SPARQL query files", "*.rq", "*.sparql"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File queryFile = chooser.showOpenDialog(txtSparqlQuery.getScene().getWindow());
        if (queryFile == null) {
            return;
        }

        try {
            String queryText = Files.readString(queryFile.toPath(), StandardCharsets.UTF_8);
            txtSparqlQuery.setText(queryText);
            setCurrentQueryFile(queryFile);
            setStatus("Loaded query from " + queryFile.getName() + ". You can edit it now.");
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError(
                    "Query loading failed",
                    "The SPARQL query file could not be opened.",
                    e
            );
        }
    }

    @FXML
    private void actionSaveQuery(ActionEvent actionEvent) {
        try {
            File targetFile = currentQueryFile;
            if (targetFile == null) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save SPARQL query");
                chooser.setInitialFileName("query.rq");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("SPARQL query files", "*.rq", "*.sparql"),
                        new FileChooser.ExtensionFilter("All files", "*.*")
                );
                targetFile = chooser.showSaveDialog(txtSparqlQuery.getScene().getWindow());
                if (targetFile == null) {
                    return;
                }
            }

            String queryText = txtSparqlQuery.getText();
            if (queryText == null || queryText.isBlank()) {
                setStatus("Nothing to save.");
                return;
            }

            if (!targetFile.getName().toLowerCase().endsWith(".rq") && !targetFile.getName().toLowerCase().endsWith(".sparql")) {
                targetFile = new File(targetFile.getAbsolutePath() + ".rq");
            }

            Files.writeString(targetFile.toPath(), queryText, StandardCharsets.UTF_8);
            setCurrentQueryFile(targetFile);
            setStatus("Saved query to " + targetFile.getName() + ".");
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError(
                    "Query save failed",
                    "The SPARQL query could not be saved.",
                    e
            );
        }
    }

    @FXML
    private void actionSaveAsQuery(ActionEvent actionEvent) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save SPARQL query as");
            chooser.setInitialFileName("query.rq");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("SPARQL query files", "*.rq", "*.sparql"),
                    new FileChooser.ExtensionFilter("All files", "*.*")
            );
            File targetFile = chooser.showSaveDialog(txtSparqlQuery.getScene().getWindow());
            if (targetFile == null) {
                return;
            }

            String queryText = txtSparqlQuery.getText();
            if (queryText == null || queryText.isBlank()) {
                setStatus("Nothing to save.");
                return;
            }

            if (!targetFile.getName().toLowerCase().endsWith(".rq") && !targetFile.getName().toLowerCase().endsWith(".sparql")) {
                targetFile = new File(targetFile.getAbsolutePath() + ".rq");
            }

            Files.writeString(targetFile.toPath(), queryText, StandardCharsets.UTF_8);
            setCurrentQueryFile(targetFile);
            setStatus("Saved query as " + targetFile.getName() + ".");
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError(
                    "Save as failed",
                    "The SPARQL query could not be saved.",
                    e
            );
        }
    }

    @FXML
    private void actionClearQuery(ActionEvent actionEvent) {
        txtSparqlQuery.clear();
        setCurrentQueryFile(null);
        setStatus("Query editor cleared.");
    }

    @FXML
    private void actionRunQuery(ActionEvent actionEvent) {
        String queryText = txtSparqlQuery.getText();
        if (queryText == null || queryText.isBlank()) {
            setStatus("Please enter or load a SPARQL query first.");
            return;
        }

        try {
            if (selectedModelFiles.isEmpty()) {
                setStatus("Please select RDF/XML or ZIP model files first.");
                return;
            }

            setStatus("Loading RDF models...");
            Model combinedModel = eu.griddigit.cimpal.core.utils.ModelFactory.loadCombinedModelForSparql(selectedModelFiles, DEFAULT_XML_BASE);
            if (combinedModel == null || combinedModel.isEmpty()) {
                throw new IllegalStateException("Failed to load RDF models from the selected files.");
            }

            setStatus("Running SPARQL query...");
            SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(queryText, combinedModel);

            setStatus("SPARQL query completed: " + results.rows.size() + " result(s).");

            // Open results window
            showResultsWindow(results);
        } catch (Exception e) {
            setStatus("SPARQL query failed.");
            GUIhelper.showUserFriendlyError(
                    "SPARQL query failed",
                    "The SPARQL query could not be run. Review the details and send them to support if needed.",
                    e
            );
        }
    }

    private void showResultsWindow(SparqlTools.QueryResults results) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SparqlResultsWindow.fxml"));
            Parent root = loader.load();

            SparqlResultsWindowController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("SPARQL Query Results");
            stage.setScene(new Scene(root));
            stage.setWidth(1200);
            stage.setHeight(600);

            controller.setResults(results, stage);
            stage.show();
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError(
                    "Results window failed",
                    "Could not open the results display window.",
                    e
            );
        }
    }

    private void setCurrentQueryFile(File file) {
        currentQueryFile = file;
        if (lblQueryFile != null) {
            lblQueryFile.setText(file == null ? "No query loaded" : file.getAbsolutePath());
        }
    }

    private void setStatus(String message) {
        if (lblStatus != null) {
            lblStatus.setText(message);
        }
    }

    private void updateModelFilesLabel() {
        if (lblModelFiles != null) {
            if (selectedModelFiles.isEmpty()) {
                lblModelFiles.setText("No model files selected");
            } else {
                lblModelFiles.setText(selectedModelFiles.size() + " model file(s) selected");
            }
        }
    }
}


