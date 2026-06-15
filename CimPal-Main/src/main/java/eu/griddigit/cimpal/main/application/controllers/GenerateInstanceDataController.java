package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.core.ModelManipulationFactory;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import static eu.griddigit.cimpal.main.core.ExportInstanceDataTemplate.CreateTemplateFromRDF;

public class GenerateInstanceDataController implements Initializable {
    private MainController mainController;

    private List<File> inputXLS;
    private List<File> genRDFSFiles;
    private List<File> genInstanceFiles;

    @FXML
    private ChoiceBox<String> fcbGenMethodOptions;
    @FXML
    private ListView<String> ls_geni_rdfs;
    @FXML
    private CheckBox hideEmptySheets;
    @FXML
    private ListView<String> ls_geni_instances;
    @FXML
    private Label label_geninfo;
    @FXML
    private TextField fsXlsTemplatePath;
    @FXML
    private CheckBox fcbSortRDFGen;
    @FXML
    private ChoiceBox<String> fcbRDFsortOptionsGen;
    @FXML
    private CheckBox fcbStripPrefixesGen;
    @FXML
    private CheckBox fcbExportExtensionsGen;
    @FXML
    private ChoiceBox<String> fcb_giVersion;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fcb_giVersion.getItems().addAll(
                "IEC 61970-600-1&2 (CGMES 3.0.0)",
                "IEC TS 61970-600-1&2 (CGMES 2.4.15)",
                "Other"
        );
        fcb_giVersion.getSelectionModel().selectFirst();

        fcbRDFsortOptionsGen.getItems().addAll(
                "Sorting by local name",
                "Sorting by prefix"
        );
        fcbRDFsortOptionsGen.getSelectionModel().selectFirst();

        fcbGenMethodOptions.getItems().addAll(
                "Old template (not maintained)",
                "Advanced template"
        );
        fcbGenMethodOptions.getSelectionModel().select(1);

        fcbGenMethodOptions.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionHandleOptionsForGen(newV.toString()));

        ls_geni_rdfs.getItems().addListener((ListChangeListener<String>) c -> {
            double maxWidth = 0;
            Text helper = new Text();
            helper.setFont(Font.font("System", 12));

            for (String item : ls_geni_rdfs.getItems()) {
                helper.setText(item);
                double width = helper.getLayoutBounds().getWidth();
                if (width > maxWidth) {
                    maxWidth = width;
                }
            }

            ls_geni_rdfs.setPrefWidth(Math.min(maxWidth + 40, 400));
        });

        ls_geni_instances.getItems().addListener((ListChangeListener<String>) c -> {
            double maxWidth = 0;
            Text helper = new Text();
            helper.setFont(Font.font("System", 12));

            for (String item : ls_geni_instances.getItems()) {
                helper.setText(item);
                double width = helper.getLayoutBounds().getWidth();
                if (width > maxWidth) {
                    maxWidth = width;
                }
            }

            ls_geni_instances.setPrefWidth(Math.min(maxWidth + 40, 400));
        });

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
    //action button Browse for Xls template for instance data generation
    private void actionBrowseXlsTemplate() {
        resetProgressBar();
        //select xls file
        List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Xls template", List.of("*.xlsx"), "Browse for xls template");

        if (file != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fsXlsTemplatePath.setText(file.getFirst().toString());
            inputXLS = file;
        }
    }

    @FXML
    private void actionBtnRunGenerateInstance() throws Exception {
        resetProgressBar();

        String xmlBase = "";
        switch (fcb_giVersion.getSelectionModel().getSelectedItem().toString()) {
            case "IEC 61970-600-1&2 (CGMES 3.0.0)":
                xmlBase = "http://iec.ch/TC57/CIM100";
                break;
            case "IEC TS 61970-600-1&2 (CGMES 2.4.15)":
                xmlBase = "http://iec.ch/TC57/2013/CIM-schema-cim16";
                break;
            case "Other":
                xmlBase = "https://cim.ucaiug.io/ns";
                break;
            default:
                break;
        }
        Map<String, Object> saveProperties = new HashMap<>();
        boolean sortRDF = fcbSortRDFGen.isSelected();
        boolean sortPrefix = fcbRDFsortOptionsGen.getSelectionModel().getSelectedItem().toString().equals("Sorting by prefix");
        boolean stripPrefixes = fcbStripPrefixesGen.isSelected();
        boolean exportExtensions = fcbExportExtensionsGen.isSelected();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", true);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "false"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");
        saveProperties.put("sortRDF", sortRDF);
        saveProperties.put("sortRDFprefix", sortPrefix); // if true the sorting is on the prefix, if false on the localNam

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");

        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        String selectedMethod = fcbGenMethodOptions.getSelectionModel().getSelectedItem().toString();
        switch (selectedMethod) {
            case "Old template (not maintained)":
                ModelManipulationFactory.generateDataFromXls(xmlBase, saveProperties, mainController.getGuiHelper());
                break;
            case "Advanced template":
                for (File file : inputXLS) {
                    ModelManipulationFactory.generateDataFromXlsV2(xmlBase, file, saveProperties, stripPrefixes, exportExtensions);
                }
                break;
        }

        setProgressBar(1);
    }

    @FXML
    private void actionBtnResetGenerateInstance() {
        resetProgressBar();
        inputXLS = null;
        fsXlsTemplatePath.clear();
        fcb_giVersion.getSelectionModel().selectFirst();
        fcbSortRDFGen.setSelected(true);
        fcbRDFsortOptionsGen.getSelectionModel().selectFirst();
        fcbGenMethodOptions.getSelectionModel().selectFirst();
    }


    private void checkInstanceData() {
        if (!ls_geni_instances.getItems().isEmpty()) {
            hideEmptySheets.setDisable(false);
        } else {
            hideEmptySheets.setDisable(true);
            hideEmptySheets.setSelected(false);
        }
    }


    @FXML
    private void actionCreateXlsTemplate() throws FileNotFoundException {
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        String selectedMethod = fcbGenMethodOptions.getSelectionModel().getSelectedItem().toString();
        boolean hide = hideEmptySheets.isSelected();

        if (genRDFSFiles != null || genInstanceFiles != null) {// the file is selected
            MainController.setShaclNodataMap(1); // as no mapping is to be used for this task

            CreateTemplateFromRDF(genRDFSFiles, genInstanceFiles, selectedMethod, hide);
            setProgressBar(1);
        } else {
            resetProgressBar();
        }
    }

    private void updateGenInfoLabel() {
        String infoText = "Load RDFS and/or Instance data to generate template.";
        if (genRDFSFiles != null && !genRDFSFiles.isEmpty()) {
            if (genInstanceFiles != null && !genInstanceFiles.isEmpty()) {
                infoText = "Template will be created based on RDFS files and populated with instance data.";
            } else {
                infoText = "Empty template will be created based on RDFS files.";
            }
        } else {
            if (genInstanceFiles != null && !genInstanceFiles.isEmpty()) {
                infoText = "Template will be created using only instance data information. Select RDFS files to create a more complete template!";
            }
        }
        label_geninfo.setText(infoText);
    }

    @FXML
    private void actionLoadRDFSGen() {
        try {
            List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDFS files", List.of("*.rdf"), "Select RDF file(s) for template.");
            ls_geni_rdfs.getItems().clear();
            genRDFSFiles = file;
            if (file != null) {// the file is selected
                ObservableList<String> filenames = FXCollections.observableArrayList();
                file.forEach(f -> filenames.add(f.getName()));
                ls_geni_rdfs.setItems(filenames);
            }
        } catch (Exception e) {
            GUIhelper.showUserFriendlyError("RDFS selection error", "The selected RDFS file list could not be loaded.", e);
        } finally {
            updateGenInfoLabel();
        }
    }

    @FXML
    private void actionLoadInstanceDataGen() {
        try {
            List<File> file = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Instance files", List.of("*.xml"), "Select instance file(s) for template.");
            ls_geni_instances.getItems().clear();
            genInstanceFiles = file;
            if (!file.isEmpty()) {// the file is selected
                ObservableList<String> filenames = FXCollections.observableArrayList();
                file.forEach(f -> filenames.add(f.getName()));
                ls_geni_instances.setItems(filenames);
            }
        } catch (Exception e) {
            GUIhelper.showUserFriendlyError("Instance selection error", "The selected instance file list could not be loaded.", e);
        } finally {
            updateGenInfoLabel();
            checkInstanceData();
        }
    }

    private void actionHandleOptionsForGen(String selected) {
        switch (selected) {
            case "Old template (not maintained)":
                fcbSortRDFGen.setDisable(true);
                fcbRDFsortOptionsGen.setDisable(true);
                fcbStripPrefixesGen.setDisable(true);
                fcbExportExtensionsGen.setDisable(true);
                break;
            case "Advanced template":
                fcbSortRDFGen.setDisable(false);
                fcbRDFsortOptionsGen.setDisable(false);
                fcbStripPrefixesGen.setDisable(false);
                fcbExportExtensionsGen.setDisable(false);
                break;
        }
    }
}
