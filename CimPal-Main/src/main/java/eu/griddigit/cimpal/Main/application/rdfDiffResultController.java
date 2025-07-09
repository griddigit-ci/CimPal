/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.Main.application;

import eu.griddigit.cimpal.Main.gui.RDFcomparisonResultModel;
import eu.griddigit.cimpal.Main.gui.TextAreaEditTableCell;
import eu.griddigit.cimpal.Core.models.RDFCompareResultEntry;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import static eu.griddigit.cimpal.Main.gui.ExcelExportTableView.export;

public class rdfDiffResultController implements Initializable {
    public Button btnCancel;
    public Button btnExportResult;
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

    public static Stage guiRdfDiffResultsStage;

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

        ObservableList<RDFcomparisonResultModel> tableResultsData= tableViewResults.getItems();

        List<RDFCompareResultEntry> rdfCompareResultEntries = MainController.rdfCompareResult.GetEntries();

        for (RDFCompareResultEntry result : rdfCompareResultEntries) {
            tableResultsData.add(new RDFcomparisonResultModel(result.getItem(), result.getRdfType(), result.getProperty(),
                    result.getValueModelA(), result.getValueModelB()));
        }

        labelModelA.setText(MainController.rdfsCompareFiles.get(0));
        labelModelB.setText(MainController.rdfsCompareFiles.get(1));

        tableViewResults.setItems(tableResultsData);
        tableViewResults.getSelectionModel().selectFirst();
    }

    @FXML
    private void actionBtnExportResult(ActionEvent actionEvent) throws IOException {
        export(tableViewResults,"RDFS comparison results","RDFScomparisonResult","Save RDFS comparison result");
        //RDFDataMgr.write(System.out, AddShapesController.reportResource.getModel(), RDFFormat.TURTLE);

    }

    @FXML
    private void actionBtnCancel(ActionEvent actionEvent) {
        guiRdfDiffResultsStage.close();
    }

    //used for the cancel button on the add shapes GUI
    public static void initData(Stage stage) {
        guiRdfDiffResultsStage=stage;
    }
}
