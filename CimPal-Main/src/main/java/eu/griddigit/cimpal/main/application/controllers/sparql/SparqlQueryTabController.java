package eu.griddigit.cimpal.main.application.controllers.sparql;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.PathMemory;
import eu.griddigit.cimpal.main.gui.BaseUriPresets;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class SparqlQueryTabController implements Initializable {
    private MainController mainController;

    private static final String DEFAULT_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";

    @FXML
    private TextArea txtSparqlQuery;

    @FXML
    private ChoiceBox<String> cbBaseUri;
    @FXML
    private TextField tfBaseUri;
    @FXML
    private ChoiceBox<String> cbRepairOutputFormat;

    @FXML
    private Label helpSelectModels;
    @FXML
    private Label helpSparqlQuery;
    @FXML
    private Label lblRepairRepository;

    private final List<File> selectedModelFiles = new ArrayList<>();
    private File currentQueryFile;
    private File repairOutputRepository;
    private File lastRepairOutputFile;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeHelpTooltips();
        BaseUriPresets.bind(cbBaseUri, tfBaseUri, "CIM 16");
        cbRepairOutputFormat.getItems().setAll("CIM XML / RDF/XML (IEC 61970-552)", "RDF/XML", "Turtle", "N-Triples", "N-Quads", "TriG", "JSON-LD", "RDF/JSON");
        cbRepairOutputFormat.setValue("CIM XML / RDF/XML (IEC 61970-552)");
        String rememberedRepository = MainController.prefs.get("sparql.repair.outputRepository", "");
        if (!rememberedRepository.isBlank()) {
            File repository = new File(rememberedRepository);
            if (isGitRepository(repository)) repairOutputRepository = repository;
        }
        updateRepairRepositoryLabel();
        setCurrentQueryFile(null);
    }

    private void initializeHelpTooltips() {
        GUIhelper.installHelpTooltip(helpSelectModels, "Select one or more RDF/XML files or ZIP archives containing CGMES model data to query. The files are loaded as a combined RDF dataset.");
        GUIhelper.installHelpTooltip(helpSparqlQuery, "Write a SPARQL SELECT query or a SPARQL Update repair script.\n\nUse Load query to import .sparql, .rq, or .ru files. Updates run only after confirmation, use a temporary in-memory model, and may be saved only as a new RDF/XML output file.");
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        setStatus("Ready to edit or import a SPARQL query or repair script.");
    }

    /** Receives a review-approved query or repair script generated in the AI Assistant tab. */
    public void setGeneratedQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("The generated SPARQL query is empty.");
        }
        txtSparqlQuery.setText(query);
        setCurrentQueryFile(null);
        setStatus(isUpdate(query) ? "Generated SPARQL repair loaded. Review it, then run it against the selected model files."
                : "Generated SPARQL query loaded. Review it, then run it against the selected model files.");
    }

    private String selectedBaseUri() {
        String value = tfBaseUri == null ? "" : tfBaseUri.getText();
        return value == null || value.isBlank() ? DEFAULT_XML_BASE : value.trim();
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
                List.of("*.rdf", "*.xml", "*.ttl", "*.n3", "*.nt", "*.nq", "*.trig", "*.trix", "*.jsonld", "*.json", "*.rj", "*.zip"),
                "Select Jena RDF, CIM XML, or ZIP model files",
                "dialog.sparqlModelFiles"
        );

        if (modelFiles == null || modelFiles.isEmpty()) {
            setStatus("No RDF, CIM XML, or ZIP model files were selected.");
            return;
        }

        selectedModelFiles.clear();
        selectedModelFiles.addAll(modelFiles);
        MainController.IDModel1 = new ArrayList<>(modelFiles);
        completeProgressBar();
        setStatus("Selected " + selectedModelFiles.size() + " model file(s). Ready to run a SPARQL query.");
    }

    @FXML
    private void actionLoadQuery(ActionEvent actionEvent) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SPARQL query");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SPARQL files", "*.rq", "*.sparql", "*.ru"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        PathMemory.prepare(chooser, "dialog.sparqlQueryFile");

        File queryFile = chooser.showOpenDialog(txtSparqlQuery.getScene().getWindow());
        if (queryFile == null) {
            return;
        }
        PathMemory.remember("dialog.sparqlQueryFile", queryFile);

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
                chooser.setTitle("Save SPARQL script");
                chooser.setInitialFileName("query.rq");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("SPARQL files", "*.rq", "*.sparql", "*.ru"),
                        new FileChooser.ExtensionFilter("All files", "*.*")
                );
                PathMemory.prepare(chooser, "dialog.sparqlQueryFile");
                targetFile = chooser.showSaveDialog(txtSparqlQuery.getScene().getWindow());
                if (targetFile == null) {
                    return;
                }
                PathMemory.remember("dialog.sparqlQueryFile", targetFile);
            }

            String queryText = txtSparqlQuery.getText();
            if (queryText == null || queryText.isBlank()) {
                setStatus("Nothing to save.");
                return;
            }

            if (!hasSparqlExtension(targetFile)) {
                targetFile = new File(targetFile.getAbsolutePath() + (isUpdate(queryText) ? ".ru" : ".rq"));
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
            chooser.setTitle("Save SPARQL script as");
            chooser.setInitialFileName("query.rq");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("SPARQL files", "*.rq", "*.sparql", "*.ru"),
                    new FileChooser.ExtensionFilter("All files", "*.*")
            );
            PathMemory.prepare(chooser, "dialog.sparqlQueryFile");
            File targetFile = chooser.showSaveDialog(txtSparqlQuery.getScene().getWindow());
            if (targetFile == null) {
                return;
            }
            PathMemory.remember("dialog.sparqlQueryFile", targetFile);

            String queryText = txtSparqlQuery.getText();
            if (queryText == null || queryText.isBlank()) {
                setStatus("Nothing to save.");
                return;
            }

            if (!hasSparqlExtension(targetFile)) {
                targetFile = new File(targetFile.getAbsolutePath() + (isUpdate(queryText) ? ".ru" : ".rq"));
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
                setStatus("Please select RDF, CIM XML, or ZIP model files first.");
                return;
            }

            setProgressBar(javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS);
            setStatus("Loading RDF models...");
            Model combinedModel = eu.griddigit.cimpal.core.utils.ModelFactory.loadCombinedModelForSparql(selectedModelFiles, selectedBaseUri());
            if (combinedModel == null || combinedModel.isEmpty()) {
                throw new IllegalStateException("Failed to load RDF models from the selected files.");
            }

            if (isUpdate(queryText)) {
                runUpdate(queryText, combinedModel);
                completeProgressBar();
                return;
            }

            QueryFactory.create(queryText);
            setStatus("Running SPARQL query...");
            SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(queryText, combinedModel);

            setStatus("SPARQL query completed: " + results.rows.size() + " result(s).");
            completeProgressBar();

            // Open results window
            showResultsWindow(results);
        } catch (Exception e) {
            resetProgressBar();
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
    }

    private void runUpdate(String updateText, Model combinedModel) throws IOException {
        ensureSafeUpdate(updateText);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Run this SPARQL Update on a temporary in-memory copy of the selected model files?\n\n"
                        + "The original files will not be modified. You will be asked where to save a new repaired RDF/XML copy afterwards.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Run SPARQL repair");
        confirm.setHeaderText("Review-approved repair required");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            resetProgressBar();
            setStatus("SPARQL repair cancelled. No model data was changed.");
            return;
        }
        Model original = org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(combinedModel);
        UpdateAction.execute(UpdateFactory.create(updateText), combinedModel);
        long removed = original.difference(combinedModel).size();
        long added = combinedModel.difference(original).size();
        FileChooser chooser = new FileChooser();
        OutputFormat outputFormat = selectedOutputFormat();
        chooser.setTitle("Save repaired model copy");
        chooser.setInitialFileName("repaired-model" + outputFormat.extension);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(outputFormat.description, "*" + outputFormat.extension));
        PathMemory.prepare(chooser, "dialog.sparqlRepairOutput");
        if (repairOutputRepository != null && repairOutputRepository.isDirectory()) chooser.setInitialDirectory(repairOutputRepository);
        File target = chooser.showSaveDialog(txtSparqlQuery.getScene().getWindow());
        if (target == null) {
            setStatus("Repair ran only in memory; output was not saved. Original model files were not changed.");
            return;
        }
        if (!target.getName().toLowerCase().endsWith(outputFormat.extension)) target = new File(target.getAbsolutePath() + outputFormat.extension);
        try (FileOutputStream output = new FileOutputStream(target)) {
            RDFDataMgr.write(output, combinedModel, outputFormat.rdfFormat);
        }
        PathMemory.remember("dialog.sparqlRepairOutput", target);
        lastRepairOutputFile = target;
        setStatus("SPARQL repair saved as " + target.getName() + " (" + removed + " removed, " + added + " added triple(s)). Original files were not changed.");
    }

    @FXML
    private void actionChooseOutputRepository(ActionEvent event) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select local Git repository for repaired output");
        if (repairOutputRepository != null && repairOutputRepository.isDirectory()) chooser.setInitialDirectory(repairOutputRepository);
        File selected = chooser.showDialog(txtSparqlQuery.getScene().getWindow());
        if (selected == null) return;
        if (!isGitRepository(selected)) {
            GUIhelper.showUserFriendlyError("Not a Git repository", "Choose the root folder of a local cloned Git repository (a folder containing .git).", null);
            return;
        }
        repairOutputRepository = selected;
        MainController.prefs.put("sparql.repair.outputRepository", selected.getAbsolutePath());
        updateRepairRepositoryLabel();
        setStatus("Repair output repository selected: " + selected.getName() + ". Repaired files remain uncommitted until you choose Commit saved repair.");
    }

    @FXML
    private void actionCommitRepairOutput(ActionEvent event) {
        if (repairOutputRepository == null) {
            setStatus("Select a local output repository first.");
            return;
        }
        if (lastRepairOutputFile == null || !lastRepairOutputFile.isFile()) {
            setStatus("Save a repaired output file in this CimPal session before committing it.");
            return;
        }
        java.nio.file.Path repositoryPath = repairOutputRepository.toPath().toAbsolutePath().normalize();
        java.nio.file.Path outputPath = lastRepairOutputFile.toPath().toAbsolutePath().normalize();
        if (!outputPath.startsWith(repositoryPath)) {
            setStatus("The saved repair is outside the selected repository. Save it inside the repository before committing.");
            return;
        }
        String relativePath = repositoryPath.relativize(outputPath).toString();
        TextInputDialog messageDialog = new TextInputDialog("Repair " + lastRepairOutputFile.getName());
        messageDialog.setTitle("Commit repaired model");
        messageDialog.setHeaderText("Commit only " + relativePath + " to " + repairOutputRepository.getName());
        messageDialog.setContentText("Commit message:");
        Optional<String> entered = messageDialog.showAndWait();
        if (entered.isEmpty() || entered.get().trim().isBlank()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Stage and commit only this repaired output file?\n\nRepository: " + repairOutputRepository.getAbsolutePath()
                        + "\nFile: " + relativePath,
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Confirm Git commit");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            runGit(List.of("git", "-C", repositoryPath.toString(), "add", "--", relativePath));
            runGit(List.of("git", "-C", repositoryPath.toString(), "commit", "-m", entered.get().trim(), "--", relativePath));
            setStatus("Committed repaired output " + relativePath + " to " + repairOutputRepository.getName() + ".");
        } catch (IOException e) {
            GUIhelper.showUserFriendlyError("Git commit failed", "The repaired output was not committed. Check Git status and the repository configuration.", e);
        }
    }

    private static boolean isGitRepository(File folder) {
        return folder != null && folder.isDirectory() && new File(folder, ".git").exists();
    }

    private void updateRepairRepositoryLabel() {
        lblRepairRepository.setText(repairOutputRepository == null ? "No output repository selected" : "Output repository: " + repairOutputRepository.getName());
    }

    private static void runGit(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git command timed out.");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new IOException(output.isBlank() ? "Git command failed." : output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command was interrupted.", e);
        }
    }

    private static boolean isUpdate(String text) {
        try {
            UpdateFactory.create(text);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasSparqlExtension(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".rq") || name.endsWith(".sparql") || name.endsWith(".ru");
    }

    private static void ensureSafeUpdate(String update) {
        if (update.matches("(?is).*\\b(LOAD|SERVICE|CLEAR|DROP|CREATE|MOVE|COPY|ADD)\\b.*")) {
            throw new IllegalArgumentException("For safety, this tab does not run remote or graph-management SPARQL operations.");
        }
        if (!update.matches("(?is).*\\b(INSERT|DELETE)\\b.*")) {
            throw new IllegalArgumentException("The script does not contain an INSERT or DELETE operation.");
        }
    }

    private OutputFormat selectedOutputFormat() {
        return switch (cbRepairOutputFormat.getValue()) {
            case "Turtle" -> new OutputFormat(".ttl", "Turtle files", RDFFormat.TURTLE_PRETTY);
            case "N-Triples" -> new OutputFormat(".nt", "N-Triples files", RDFFormat.NTRIPLES_UTF8);
            case "N-Quads" -> new OutputFormat(".nq", "N-Quads files", RDFFormat.NQUADS_UTF8);
            case "TriG" -> new OutputFormat(".trig", "TriG files", RDFFormat.TRIG_PRETTY);
            case "JSON-LD" -> new OutputFormat(".jsonld", "JSON-LD files", RDFFormat.JSONLD_PRETTY);
            case "RDF/JSON" -> new OutputFormat(".rj", "RDF/JSON files", RDFFormat.RDFJSON);
            case "RDF/XML" -> new OutputFormat(".rdf", "RDF/XML files", RDFFormat.RDFXML_PRETTY);
            default -> new OutputFormat(".rdf", "CIM XML / RDF/XML files", RDFFormat.RDFXML_PRETTY);
        };
    }

    private record OutputFormat(String extension, String description, RDFFormat rdfFormat) { }

    private void setStatus(String message) {
        if (mainController != null) {
            mainController.setStatusMessage(message);
        }
    }

    private void completeProgressBar() {
        if (mainController != null) {
            mainController.completeProgressBar();
        }
    }
}


