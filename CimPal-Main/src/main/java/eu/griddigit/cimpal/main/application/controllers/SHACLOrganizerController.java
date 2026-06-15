package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.core.ShaclTools;
import eu.griddigit.cimpal.main.util.ExcelTools;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class SHACLOrganizerController implements Initializable {
    private MainController mainController;

    private List<File> selectedFile;
    private List<File> inputXLS;

    @FXML
    private TextField fbaseURIShacl;
    @FXML
    private Button btnRunShaclOrganizer;
    @FXML
    private Button btnResetShaclOrganizer;
    @FXML
    private TextField fPrefixShaclOrganizer;
    @FXML
    private TextField fNSShaclOrganizer;
    @FXML
    private TextField fPathShaclFilesToOrganize;
    @FXML
    private TextField fPathXLSfileForShacl;

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

    public void actionBrowseShaclFilesToOrganize(ActionEvent actionEvent) {
        selectedFile = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "SHACL Shape file", List.of("*.rdf", "*.ttl"), "");
        if (selectedFile != null) {
            StringBuilder paths = new StringBuilder();
            for (int m = 0; m < selectedFile.size(); m++) {
                paths.append(", ").append(selectedFile.get(m).toString());
            }
            fPathShaclFilesToOrganize.setText(paths.toString());
        }
    }

    public void actionBrowseExcelfileForShacl(ActionEvent actionEvent) {
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Input template instance data XLS", List.of("*.xlsx"), "");

        if (!file.isEmpty()) {
            inputXLS = file;
            fPathXLSfileForShacl.setText(file.getFirst().toString());
        }
    }

    public void actionBtnRunShaclOrganizer(ActionEvent actionEvent) throws IOException {
        resetProgressBar();
        if (selectedFile != null) {
            for (int m = 0; m < selectedFile.size(); m++) {
                eu.griddigit.cimpal.main.util.ModelFactory.shapeModelLoad(m, selectedFile);
            }
        }
        ArrayList<Object> inputXLSdata;
        inputXLSdata = ExcelTools.importXLSX(inputXLS.getFirst().toString(), 0);

        ShaclTools.splitShaclPerXlsInput(inputXLSdata);

        setProgressBar(1);
    }

    public void actionBtnResetShaclOrganizer(ActionEvent actionEvent) {
        fPathShaclFilesToOrganize.clear();
        fPathXLSfileForShacl.clear();
        inputXLS = null;
        selectedFile = null;
    }
}
