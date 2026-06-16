package eu.griddigit.cimpal.main.application.controllers.sparql;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

public class SparqlResultsWindowController implements Initializable {

    @FXML
    private TableView<Map<String, String>> tableResults;

    @FXML
    private Label lblResultCount;

    @FXML
    private Button btnExport;

    @FXML
    private Button btnClose;

    private SparqlTools.QueryResults queryResults;
    private Stage stage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeTable();
    }

    private void initializeTable() {
        tableResults.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
    }

    public void setResults(SparqlTools.QueryResults results, Stage stage) {
        this.queryResults = results;
        this.stage = stage;
        displayResults();
    }

    private void displayResults() {
        if (queryResults == null || queryResults.rows.isEmpty()) {
            lblResultCount.setText("No results found");
            return;
        }

        // Clear existing columns
        tableResults.getColumns().clear();

        // Create columns based on query results
        for (String columnName : queryResults.columns) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(columnName);
            column.setCellValueFactory(cellData -> {
                String value = cellData.getValue().get(columnName);
                return new javafx.beans.property.SimpleStringProperty(value != null ? value : "");
            });
            column.setMinWidth(100);
            tableResults.getColumns().add(column);
        }

        // Fill table with data
        for (Map<String, String> row : queryResults.rows) {
            tableResults.getItems().add(row);
        }

        lblResultCount.setText("Results: " + queryResults.rows.size() + " row(s), " + queryResults.columns.size() + " column(s)");
    }

    @FXML
    private void actionExport(ActionEvent actionEvent) {
        try {
            File outputFile = ModelFactory.fileSaveCustom(
                    "Excel file",
                    List.of("*.xlsx"),
                    "Save SPARQL results",
                    "sparql-results.xlsx"
            );

            if (outputFile == null) {
                return;
            }

            if (!outputFile.getName().toLowerCase().endsWith(".xlsx")) {
                outputFile = new File(outputFile.getAbsolutePath() + ".xlsx");
            }

            SparqlTools.exportResultsToExcel(queryResults, outputFile);

            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.setTitle("Export completed");
            ok.setHeaderText(null);
            ok.setContentText("SPARQL results exported to:\n" + outputFile.getAbsolutePath());
            ok.showAndWait();

        } catch (Exception e) {
            GUIhelper.showUserFriendlyError(
                    "Export failed",
                    "Could not export results to Excel.",
                    e
            );
        }
    }

    @FXML
    private void actionClose(ActionEvent actionEvent) {
        if (stage != null) {
            stage.close();
        }
    }
}



