package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.shacl_tools.ShaclFromXls;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.core.ShaclTools;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.ExcelTools;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RIOT;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class ExcelToSHACLController implements Initializable {
    private MainController mainController;

    @FXML
    private TextField fPathRdffileForExcel;
    @FXML
    private ChoiceBox<String> fcbRDFSformatForExcel;
    @FXML
    private TextField fbaseURIShapeExcel;
    @FXML
    private TextField fPrefixExcelShape;
    @FXML
    private TextField fNSexcelShape;
    @FXML
    private TextField fPathXLSfileForShape;
    @FXML
    private TextField fPathXLSChangesExcelToTtl;
    @FXML
    private TextField fPathTTLChangesExcelToTtl;
    @FXML
    private Button btnRunExcelToTtl;
    @FXML
    private Button btnResetExcelToTtl;
    @FXML
    private Button btnRunExcelShape;
    @FXML
    private Button btnResetExcelShape;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fcbRDFSformatForExcel.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();
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
    //action button RDF file Browse for Excel to SHACL
    private void actionBrowseRDFfileForExcel() {
        resetProgressBar();
        //select file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf"), "");
        if (!file.isEmpty()) {// the file is selected
            fPathRdffileForExcel.setText(file.getFirst().toString());
            MainController.rdfModelExcelShacl = file.getFirst();

        } else {
            fPathRdffileForExcel.clear();
        }
    }

    @FXML
    //action button XLS file Browse for Excel to SHACL
    private void actionBrowseExcelfileForShape() {
        resetProgressBar();
        //select file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Excel files", List.of("*.xlsx"), "");

        if (!file.isEmpty()) {// the file is selected
            fPathXLSfileForShape.setText(file.getFirst().toString());
            MainController.xlsFileExcelShacl = file.getFirst();

        } else {
            fPathXLSfileForShape.clear();
        }
    }

    @FXML
    //action button XLS file Browse for Excel to SHACL
    private void actionBrowseXlsChangesExcelToTtl() {
        resetProgressBar();
        //select file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Excel files", List.of("*.xlsx"), "");

        if (!file.isEmpty()) {// the file is selected
            fPathXLSChangesExcelToTtl.setText(file.getFirst().toString());
            MainController.XlsChangesExcelToTtl = file.getFirst();

        } else {
            fPathXLSChangesExcelToTtl.clear();
        }
    }

    @FXML
    //action button RDF file Browse for Excel to SHACL
    private void actionBrowseTtlChangesExcelToTtl() {
        resetProgressBar();
        //select file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "TTL files", List.of("*.ttl"), "");
        if (file.getFirst() != null) {// the file is selected
            fPathTTLChangesExcelToTtl.setText(file.getFirst().toString());
            MainController.TtlChangesExcelToTtl = file.getFirst();

        } else {
            fPathTTLChangesExcelToTtl.clear();
        }
    }

    @FXML
    public void actionBtnRunExcelToTtl(ActionEvent actionEvent) {
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        try {
            if (fPathXLSChangesExcelToTtl.getText().isBlank() || fPathTTLChangesExcelToTtl.getText().isBlank()) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText("Please complete all fields.");
                alert.setHeaderText(null);
                alert.setTitle("Error - not all fields are filled in");
                alert.showAndWait();
                resetProgressBar();
                return;
            }

            // 1) Read Excel
            ArrayList<Object> dataExcel = ExcelTools.importXLSX(String.valueOf(MainController.XlsChangesExcelToTtl), 0);

            // Simple header detection (exact match, case-sensitive like the applier):
            int startRow = 0;
            if (!dataExcel.isEmpty() && dataExcel.getFirst() instanceof java.util.List<?> hdr) {
                String c0 = !hdr.isEmpty() ? String.valueOf(hdr.get(0)) : "";
                String c1 = hdr.size() > 1 ? String.valueOf(hdr.get(1)) : "";
                if (("Name".equals(c0) || "sh:name".equals(c0)) && ("Property".equals(c1) || "Property Type".equals(c1))) {
                    startRow = 1;
                }
            }

            // 2) Read TTL shapes model (single file)
            java.util.List<File> modelFiles1 = new java.util.LinkedList<>();
            modelFiles1.add(MainController.TtlChangesExcelToTtl);
            Model model1 = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(modelFiles1, null, Lang.TURTLE, true, false).get("shacl");

            System.out.println("Loaded triples: " + model1.size());

            // 3) Apply updates (mutates model1 in place; 'updated' == model1)
            Model updated = eu.griddigit.cimpal.main.core.ShaclExcelApplier.applyPropsFromExcelSimple(model1, dataExcel, startRow);

            // Ensure common prefixes (especially sh:) before saving
            model1.setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
            model1.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
            model1.setNsPrefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#");
            model1.setNsPrefix("xsd", "http://www.w3.org/2001/XMLSchema#");

            // --- Optional debug roundtrip ---
            if (false) {
                File outRaw = new File("roundtrip_no_change.ttl");
                try (FileOutputStream fos = new FileOutputStream(outRaw)) {
                    RDFDataMgr.write(fos, updated, org.apache.jena.riot.Lang.TURTLE);
                }
                Model reread = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                try (java.io.FileInputStream in = new java.io.FileInputStream(outRaw)) {
                    RDFDataMgr.read(reread, in, null, org.apache.jena.riot.Lang.TURTLE);
                }
                System.out.println("Isomorphic (loaded vs roundtrip)? " + model1.isIsomorphicWith(reread));
            }
            // --- end optional debug ---


            // 4) Save-as
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Turtle (*.ttl)", "*.ttl"));
            File out = fc.showSaveDialog(btnRunExcelToTtl.getScene().getWindow());
            if (out != null) {
                String saveBaseURI = null; // keep as given

                try (OutputStream os = new FileOutputStream(out)) {
                    RDFWriter.create()
                            .base(saveBaseURI)                        // leave as null per your request
                            .set(RIOT.symTurtleOmitBase, false)
                            .set(RIOT.symTurtleIndentStyle, "wide")
                            .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                            .set(RIOT.symTurtleMultilineLiterals, true)
                            .lang(Lang.TURTLE)
                            .source(updated)
                            .output(os);
                    System.out.println("Model saved successfully to " + out.getAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Error saving model to file: " + e.getMessage());
                }
            }

            System.out.println("Apply finished.");
            setProgressBar(1.0);

        } catch (Exception e) {
            GUIhelper.showUserFriendlyError("Apply changes error", "The changes could not be applied. Please review details.", e);
            resetProgressBar();
        }
    }

    @FXML
    public void actionBtnResetExcelToTtl(ActionEvent actionEvent) {
        fPathTTLChangesExcelToTtl.clear();
        fPathXLSChangesExcelToTtl.clear();
    }

    @FXML
    //action menu "Excel to SHACL"
    private void actionBtnRunExcelShape(ActionEvent actionEvent) throws IOException {

        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        if (fPathRdffileForExcel.getText().isBlank() || fPathXLSfileForShape.getText().isBlank() || fcbRDFSformatForExcel.getSelectionModel().getSelectedItem() == null
                || fbaseURIShapeExcel.getText().isBlank() || fPrefixExcelShape.getText().isBlank() || fNSexcelShape.getText().isBlank()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please complete all fields.");
            alert.setHeaderText(null);
            alert.setTitle("Error - not all fields are filled in");
            alert.showAndWait();
            resetProgressBar();
            return;
        }


        //open the rdfs for the profile
        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        try {
            RDFDataMgr.read(model, new FileInputStream(MainController.rdfModelExcelShacl), Lang.RDFXML);
        } catch (FileNotFoundException e) {
            GUIhelper.showUserFriendlyError("RDFS loading error", "The RDFS file for shape construction could not be opened.", e);
        }
        MainController.setShaclNodataMap(1); // as no mapping is to be used for this task
        String cimsNs = MainController.prefs.get("cimsNamespace", "");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        MainController.shapesOnAbstractOption = 0;
        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);


        //select the xlsx file and read it
        ArrayList<Object> dataExcel = null;
        ArrayList<Object> configSheet = null;

        //if (file != null) {// the file is selected

        dataExcel = ExcelTools.importXLSX(String.valueOf(MainController.xlsFileExcelShacl), 0);
        configSheet = ExcelTools.importXLSX(String.valueOf(MainController.xlsFileExcelShacl), "Config");

        String baseURI = fbaseURIShapeExcel.getText();
        String nsURIprofilePrefix = fPrefixExcelShape.getText();
        String nsURIprofile = fNSexcelShape.getText();

        //generate the shapes
        Model shapeModel = ShaclFromXls.generateShaclFromXls(MainController.prefs, dataExcel, configSheet, shapeData, nsURIprofilePrefix, nsURIprofile);

        //open the ChoiceDialog for the save file and save the file in different formats
        String titleSaveAs = "Save as for shape model: ";
        File savedFile = eu.griddigit.cimpal.main.core.ShaclTools.saveShapesFile(shapeModel, baseURI, 0, titleSaveAs);

        setProgressBar(1);
    }

    @FXML
    //Action for button "Reset" related to the Excel to Shacl
    private void actionBtnResetExcelShape() {
        fPathRdffileForExcel.clear();
        fPathXLSfileForShape.clear();
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();
        fbaseURIShapeExcel.clear();
        fPrefixExcelShape.clear();
        fNSexcelShape.clear();
        resetProgressBar();

    }

}
