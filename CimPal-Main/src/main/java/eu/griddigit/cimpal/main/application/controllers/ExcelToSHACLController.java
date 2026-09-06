package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.shacl_tools.ShaclFromXls;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.core.ShaclTools;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.PathMemory;
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

/**
 * Controller for the <em>SHACL Constraints Operations</em> tab.
 * <p>
 * The tab hosts three independent routines as accordion sections: generating constraints
 * from an Excel template, modifying an existing constraints {@code .ttl} from Excel input,
 * and splitting a constraints {@code .ttl} per an Excel template. The third was previously
 * the separate <em>SHACL Organizer</em> tab; an FXML file has a single controller, so its
 * members were folded in here when the tabs were merged. Each routine's state is kept
 * distinct - nothing is shared between the three sections.
 */
public class ExcelToSHACLController implements Initializable {
    private MainController mainController;

    // ---- section 3 (split constraints) state, formerly SHACLOrganizerController ----
    private List<File> shaclFilesToSplit;
    private List<File> splitTemplateXls;

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
    @FXML
    private Label helpRdfFile;
    @FXML
    private Label helpRdfsFormat;
    @FXML
    private Label helpExcelFile;
    @FXML
    private Label helpBaseUri;
    @FXML
    private Label helpPrefixNs;
    @FXML
    private Label helpChangesExcel;
    @FXML
    private Label helpTargetTtl;

    // ---- section 3 (split constraints) controls ----
    @FXML
    private TextField fPathShaclFilesToOrganize;
    @FXML
    private TextField fPathXLSfileForShacl;
    @FXML
    private TextField fbaseURIShacl;
    @FXML
    private TextField fPrefixShaclOrganizer;
    @FXML
    private TextField fNSShaclOrganizer;
    @FXML
    private Button btnRunShaclOrganizer;
    @FXML
    private Button btnResetShaclOrganizer;
    @FXML
    private Label helpShaclFilesToSplit;
    @FXML
    private Label helpExcelTemplate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeHelpTooltips();
        fcbRDFSformatForExcel.getItems().addAll(
                "RDFS (augmented) by CimSyntaxGen"

        );
        fcbRDFSformatForExcel.getSelectionModel().selectFirst();

        //The first routine opens by default; that is declared on the TitledPane in the FXML,
        //because an Accordion discards an expandedPane set from initialize().

        //restore the paths this tab was last used with; the consumers repeat what the
        //Browse handlers do besides filling the field
        PathMemory.bind(fPathRdffileForExcel, "tab.excelToShacl.rdfsFile",
                file -> MainController.rdfModelExcelShacl = file);
        PathMemory.bind(fPathXLSfileForShape, "tab.excelToShacl.excelFile",
                file -> MainController.xlsFileExcelShacl = file);
        PathMemory.bind(fPathXLSChangesExcelToTtl, "tab.excelToShacl.changesExcel",
                file -> MainController.XlsChangesExcelToTtl = file);
        PathMemory.bind(fPathTTLChangesExcelToTtl, "tab.excelToShacl.targetTtl",
                file -> MainController.TtlChangesExcelToTtl = file);

        //the SHACL files field holds a comma-joined list of several files, so it is not
        //restored; its chooser still reopens in the folder it was last used in
        PathMemory.bind(fPathXLSfileForShacl, "tab.shaclOrganizer.excelTemplate",
                file -> splitTemplateXls = List.of(file));
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
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf"), "", "tab.excelToShacl.rdfsFile");
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
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Excel files", List.of("*.xlsx"), "", "tab.excelToShacl.excelFile");

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
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(true, "Excel files", List.of("*.xlsx"), "", "tab.excelToShacl.changesExcel");

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
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "TTL files", List.of("*.ttl"), "", "tab.excelToShacl.targetTtl");
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
            PathMemory.prepare(fc, "dialog.excelToShacl.saveTtl");
            File out = fc.showSaveDialog(btnRunExcelToTtl.getScene().getWindow());
            PathMemory.remember("dialog.excelToShacl.saveTtl", out);
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

    // ================= section 3: split constraints per Excel template =================
    // Formerly the SHACL Organizer tab. Behaviour is unchanged; only the field labels and
    // the enclosing container differ.

    @FXML
    public void actionBrowseShaclFilesToOrganize(ActionEvent actionEvent) {
        shaclFilesToSplit = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false, "SHACL Constraints file", List.of("*.rdf", "*.ttl"), "",
                "tab.shaclOrganizer.shaclFiles");
        if (shaclFilesToSplit != null) {
            StringBuilder paths = new StringBuilder();
            for (File file : shaclFilesToSplit) {
                paths.append(", ").append(file.toString());
            }
            fPathShaclFilesToOrganize.setText(paths.toString());
        }
    }

    @FXML
    public void actionBrowseExcelfileForShacl(ActionEvent actionEvent) {
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                true, "Input template instance data XLS", List.of("*.xlsx"), "",
                "tab.shaclOrganizer.excelTemplate");

        if (!file.isEmpty()) {
            splitTemplateXls = file;
            fPathXLSfileForShacl.setText(file.getFirst().toString());
        }
    }

    @FXML
    public void actionBtnRunShaclOrganizer(ActionEvent actionEvent) throws IOException {
        resetProgressBar();

        if (splitTemplateXls == null || splitTemplateXls.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setContentText("Please select the Excel template file.");
            alert.setHeaderText(null);
            alert.setTitle("Error - not all fields are filled in");
            alert.showAndWait();
            return;
        }

        if (shaclFilesToSplit != null) {
            for (int m = 0; m < shaclFilesToSplit.size(); m++) {
                eu.griddigit.cimpal.main.util.ModelFactory.shapeModelLoad(m, shaclFilesToSplit);
            }
        }

        ArrayList<Object> inputXLSdata =
                ExcelTools.importXLSX(splitTemplateXls.getFirst().toString(), 0);

        ShaclTools.splitShaclPerXlsInput(inputXLSdata);

        setProgressBar(1);
    }

    @FXML
    public void actionBtnResetShaclOrganizer(ActionEvent actionEvent) {
        fPathShaclFilesToOrganize.clear();
        fPathXLSfileForShacl.clear();
        splitTemplateXls = null;
        shaclFilesToSplit = null;
        resetProgressBar();
    }

    private void initializeHelpTooltips() {
        GUIhelper.installHelpTooltip(helpRdfFile,
                "The RDFS ontology file (.xml, .rdf) containing CIM class and property definitions. Used as the base for generating SHACL shapes from the Excel constraints.");
        GUIhelper.installHelpTooltip(helpRdfsFormat,
                "The format/version of the RDFS profile (e.g. augmented CimSyntaxGen format).");
        GUIhelper.installHelpTooltip(helpExcelFile,
                "Excel file (.xls/.xlsx) containing SHACL constraint definitions. Each row defines a constraint derived from the RDFS model.");
        GUIhelper.installHelpTooltip(helpBaseUri,
                "The base URI (IRI) for the generated SHACL shapes graph. This URI is used as the graph name in the output.");
        GUIhelper.installHelpTooltip(helpPrefixNs,
                "The namespace prefix and URI for the generated shapes graph. The prefix is a short alias used in the Turtle output (e.g. \"sh\"), and the URI is the full namespace it expands to.");
        GUIhelper.installHelpTooltip(helpChangesExcel,
                "Excel file (.xlsx) containing the constraint modifications to apply. Each row specifies one change to the constraints file selected below.");
        GUIhelper.installHelpTooltip(helpTargetTtl,
                "The existing SHACL constraints file (.ttl) that the changes from the Excel input above will be applied to. The result is written to a new file you choose when the routine finishes; this file is not overwritten in place.");
        GUIhelper.installHelpTooltip(helpShaclFilesToSplit,
                "SHACL constraints files (.ttl) to be split into separate files according to the Excel template. Multiple files can be selected and are combined before splitting.");
        GUIhelper.installHelpTooltip(helpExcelTemplate,
                "Excel template (.xlsx) that defines how the constraints are grouped into the resulting files. Produced by the SHACL constraints information export, or created manually.");
    }

}
