/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application;

import eu.griddigit.cimpal.core.kgcl.KgclChange;
import eu.griddigit.cimpal.core.kgcl.KgclCnlWriter;
import eu.griddigit.cimpal.core.kgcl.KgclConverter;
import eu.griddigit.cimpal.core.kgcl.KgclRdfWriter;
import eu.griddigit.cimpal.core.models.KgclOptions;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.RDFcomparisonResultModel;
import eu.griddigit.cimpal.main.gui.TextAreaEditTableCell;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import eu.griddigit.cimpal.main.util.ModelFactory;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

import static eu.griddigit.cimpal.main.gui.ExcelExportTableView.export;

public class rdfDiffResultController implements Initializable {
    public Button btnCancel;
    public Button btnExportResult;
    public Button btnExportKgcl;
    @FXML
    private TableView tableViewResults;
    @FXML
    private TableColumn cItem;
    @FXML
    private TableColumn cClass;
    @FXML
    private TableColumn cProperty;
    @FXML
    private TableColumn cValueModelA;
    @FXML
    private TableColumn cValueModelB;
    @FXML
    private Label labelModelA;
    @FXML
    private Label labelModelB;

    private Stage guiRdfDiffResultsStage;
    private RDFCompareResult rdfCompareResult;
    private List<String> compareFiles;

    public rdfDiffResultController() {

    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        tableViewResults.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY);
        tableViewResults.setPlaceholder(new Label("No comparison details available"));

        //add callback - the table is not editable, but it is necessary in order to get the wrap text and text area
        Callback<TableColumn, TableCell> cellFactory = p -> new TextAreaEditTableCell();

        cItem.setCellValueFactory( new PropertyValueFactory<RDFcomparisonResultModel, String>( "item" ) );
        cItem.setCellFactory(cellFactory);
        cClass.setCellValueFactory( new PropertyValueFactory<RDFcomparisonResultModel, String>( "rdfType" ) );
        cClass.setCellFactory(cellFactory);
        cProperty.setCellValueFactory( new PropertyValueFactory<RDFcomparisonResultModel, String>( "property" ) );
        cProperty.setCellFactory(cellFactory);
        cValueModelA.setCellValueFactory( new PropertyValueFactory<RDFcomparisonResultModel, String>( "valueModelA" ) );
        cValueModelA.setCellFactory(cellFactory);
        cValueModelB.setCellValueFactory( new PropertyValueFactory<RDFcomparisonResultModel, String>( "valueModelB" ) );
        cValueModelB.setCellFactory(cellFactory);
    }

    /**
     * Initialize the result view with given data. Call this after loading the FXML and before showing the stage.
     */
    public void initData(Stage stage, RDFCompareResult result, List<String> files) {
        this.guiRdfDiffResultsStage = stage;
        this.rdfCompareResult = result;
        this.compareFiles = files;

        // populate table now that we have data
        ObservableList<RDFcomparisonResultModel> tableResultsData= tableViewResults.getItems();

        if (rdfCompareResult != null) {
            List<RDFCompareResultEntry> rdfCompareResultEntries = rdfCompareResult.getEntries();
            for (RDFCompareResultEntry resultEntry : rdfCompareResultEntries) {
                tableResultsData.add(new RDFcomparisonResultModel(resultEntry.getItem(), resultEntry.getRdfType(), resultEntry.getProperty(),
                        resultEntry.getValueModelA(), resultEntry.getValueModelB()));
            }
        }

        if (compareFiles != null && compareFiles.size() >= 2) {
            labelModelA.setText(compareFiles.get(0));
            labelModelB.setText(compareFiles.get(1));
        }

        tableViewResults.setItems(tableResultsData);
        tableViewResults.getSelectionModel().selectFirst();
    }

    @FXML
    private void actionBtnExportResult(ActionEvent actionEvent) throws IOException {
        export(tableViewResults,"RDFS comparison results","RDFScomparisonResult","Save RDFS comparison result", compareFiles);
        //RDFDataMgr.write(System.out, AddShapesController.reportResource.getModel(), RDFFormat.TURTLE);

    }

    @FXML
    private void actionBtnExportKgcl(ActionEvent actionEvent) throws IOException {
        if (rdfCompareResult == null || rdfCompareResult.getEntries().isEmpty()) {
            return;
        }

        // Let the user pick the serialization; Model A is treated as the "before" state (A -> B).
        Map<String, KgclOptions.OutputFormat> formatChoices = new LinkedHashMap<>();
        formatChoices.put("KGCL (CNL text)", KgclOptions.OutputFormat.CNL);
        formatChoices.put("KGCL RDF (Turtle)", KgclOptions.OutputFormat.TURTLE);
        formatChoices.put("KGCL RDF (RDF/XML)", KgclOptions.OutputFormat.RDFXML);

        ChoiceDialog<String> dialog = new ChoiceDialog<>("KGCL (CNL text)", formatChoices.keySet());
        dialog.setTitle("Export KGCL");
        dialog.setHeaderText("Export the comparison as a KGCL changeset (Model A → Model B).");
        dialog.setContentText("Format:");
        Optional<String> choice = dialog.showAndWait();
        if (choice.isEmpty()) {
            return;
        }
        KgclOptions.OutputFormat format = formatChoices.get(choice.get());

        KgclOptions options = KgclOptions.builder()
                .outputFormat(format)
                .direction(KgclOptions.Direction.A_TO_B)
                .build();
        List<KgclChange> changes = new KgclConverter().convert(rdfCompareResult, options);

        String extension = switch (format) {
            case CNL -> "*.kgcl";
            case TURTLE -> "*.ttl";
            case RDFXML -> "*.rdf";
        };
        String initialFileName = "RDFScomparisonResult" + extension.substring(1);
        File saveFile = ModelFactory.fileSaveCustom("KGCL files", List.of(extension),
                "Save KGCL comparison result", initialFileName);
        if (saveFile == null) {
            return;
        }

        try (FileOutputStream outputStream = new FileOutputStream(saveFile)) {
            if (format == KgclOptions.OutputFormat.CNL) {
                new KgclCnlWriter().write(outputStream, changes);
            } else {
                new KgclRdfWriter().write(outputStream, changes, format);
            }
        }

        if (MainController.prefs != null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
        } else {
            Preferences.userNodeForPackage(rdfDiffResultController.class).put("LastWorkingFolder", saveFile.getParent());
        }
    }

    @FXML
    private void actionBtnCancel(ActionEvent actionEvent) {
        guiRdfDiffResultsStage.close();
    }

    // kept for compatibility (not used) - prefer using the instance initData(stage,result,files)
    public static void initData(Stage stage) {
        // no-op to avoid breaking existing callers; prefer loader.getController().initData(...)
    }
}
