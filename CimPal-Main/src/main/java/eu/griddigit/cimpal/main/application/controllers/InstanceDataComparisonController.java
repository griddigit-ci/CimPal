package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.application.rdfDiffResultController;
import eu.griddigit.cimpal.main.core.ComparisonInstanceData;
import eu.griddigit.cimpal.main.core.DataTypeMaping;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.util.CompareFactory;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

public class InstanceDataComparisonController implements Initializable {
    private MainController mainController;

    static RDFCompareResult rdfCompareResult;

    @FXML
    private TextField fPathIDfile1;
    @FXML
    private TextField fPathIDfile2;
    @FXML
    private ChoiceBox<String> fcbIDformat;
    @FXML
    private TextField fPathIDmapXmlBase;
    @FXML
    private ChoiceBox<String> fcbIDmap;
    @FXML
    private TextField fPathIDmap;
    @FXML
    private Button fBTbrowseIDmap;
    @FXML
    private CheckBox fcbIDcompIgnoreTP;
    @FXML
    private CheckBox fcbIDcompIgnoreSV;
    @FXML
    private CheckBox fcbIDcompIgnoreDL;
    @FXML
    private CheckBox fcbIDcompSVonly;
    @FXML
    private CheckBox fcbIDcompSVonlyCN;
    @FXML
    private CheckBox fcbIDcompCount;
    @FXML
    private CheckBox fcbIDcompSolutionOverview;
    @FXML
    private CheckBox fcbIDcompShowDetails;
    @FXML
    private CheckBox fcbIDcompDiff;
    @FXML
    private CheckBox fcbIDcompPerFileAndAll;
    @FXML
    private Button btnRunIDcompare;
    @FXML
    private Button btnResetIDComp;


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fcbIDformat.getItems().addAll(
                "IEC 61970-600-1&2 (CGMES 3.0.0)",
                "IEC 61970-45x (CIM17)",
                "IEC TS 61970-600-1&2 (CGMES 2.4.15)",
                "IEC 61970-45x (CIM16)",
                "Other CIM version"

        );

        fcbIDmap.getItems().addAll(
                "No datatypes mapping",
                "Generate from RDFS",
                "Use saved map"
        );

        //Adding action to the choice box
        fcbIDformat.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBIDformat());

        //Adding action to the choice box
        fcbIDmap.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> actionCBIDmap());
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

    //Action for choice box "Profile version" related to Instance data comparison
    private void actionCBIDformat() {

        resetProgressBar();
        if (!fcbIDformat.getSelectionModel().isSelected(-1)) {
            if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("Other CIM version")) {
                fPathIDmapXmlBase.setEditable(true);
                fPathIDmapXmlBase.clear();
            } else if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-600-1&2 (CGMES 3.0.0)") ||
                    fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-45x (CIM17)")) {
                fPathIDmapXmlBase.setEditable(false);
                fPathIDmapXmlBase.setText("http://iec.ch/TC57/CIM100");
            } else if (fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC TS 61970-600-1&2 (CGMES 2.4.15)") ||
                    fcbIDformat.getSelectionModel().getSelectedItem().toString().equals("IEC 61970-45x (CIM16)")) {
                fPathIDmapXmlBase.setEditable(false);
                fPathIDmapXmlBase.setText("http://iec.ch/TC57/2013/CIM-schema-cim16");
            }
        } else {
            fPathIDmapXmlBase.setEditable(false);
        }

    }

    //Action for choice box "Datatypes map" related to Instance data comparison
    private void actionCBIDmap() {

        resetProgressBar();
        if (!fcbIDmap.getSelectionModel().isSelected(-1)) {
            fPathIDmap.clear();
            fBTbrowseIDmap.setDisable(fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping"));
        } else {
            fBTbrowseIDmap.setDisable(false);
            fPathIDmap.clear();
        }

    }

    @FXML
    //action button Browse for Instance data comparison - file 1
    private void actionBrowseIDfile1() {
        resetProgressBar();

        //select file 1
        List<File> fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDfile1.setText(fileL.toString());
            MainController.IDModel1 = fileL;
        } else {
            fPathIDfile1.clear();
        }
    }

    @FXML
    //action button Browse for Instance data comparison - file 2
    private void actionBrowseIDfile2() {
        resetProgressBar();
        //select file 1
        List<File> fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Instance files", List.of("*.xml", "*.zip"), "");

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDfile2.setText(fileL.toString());
            MainController.IDModel2 = fileL;
        } else {
            fPathIDfile2.clear();
        }
    }

    @FXML
    //action button Browse for Instance data comparison - mapping
    private void actionBrowseIDmap() {
        resetProgressBar();
        MainController.IDmapSelect = 0;
        List<File> fileL = null;
        //FileChooser filechooser = new FileChooser();

        if (fcbIDmap.getSelectionModel().getSelectedItem().equals("Generate from RDFS")) {
            MainController.IDmapSelect = 1;
            //select file
            fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "RDF files", List.of("*.rdf"), "");
        } else if (fcbIDmap.getSelectionModel().getSelectedItem().equals("Use saved map")) {
            MainController.IDmapSelect = 2;
            //select file
            fileL = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(false, "Map file", List.of("*.properties"), "");
        }

        if (fileL != null) {// the file is selected

            //MainController.prefs.put("LastWorkingFolder", fileL.get(0).getParent());
            fPathIDmap.setText(fileL.toString());
            MainController.IDmapList = fileL;

            if (!fPathIDfile1.getText().isEmpty() && !fPathIDfile2.getText().isEmpty()) {
                btnRunIDcompare.setDisable(false);
            }

        } else {
            fPathIDmap.clear();
            btnRunIDcompare.setDisable(true);
        }
    }

    @FXML
    //action button Run in instance data comparison
    private void actionBtnRunIDcompare() throws IOException {
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);

        String xmlBase = fPathIDmapXmlBase.getText();
        Map<String, RDFDatatype> dataTypeMap = new HashMap<>();
        ArrayList<Object> compareResults = new ArrayList<>();

        //if the datatypes map is from RDFS - make union of RDFS and generate map
        if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("Generate from RDFS") && MainController.IDmapSelect == 1) {


        } else if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("Use saved map") && MainController.IDmapSelect == 2) {
            //if the datatypes map is previously saved - load it
            if (MainController.IDmapList != null) {// the file is selected
                for (File item : MainController.IDmapList) {
                    Properties properties = new Properties();
                    properties.load(new FileInputStream(item.toString()));
                    for (Object key : properties.keySet()) {
                        String value = properties.get(key).toString();
                        RDFDatatype valueRDFdatatype = DataTypeMaping.mapFromMapDefaultFile(value);
                        dataTypeMap.put(key.toString(), valueRDFdatatype);
                    }
                }
            }
        }
        // if model 1 is more than 1 zip or xml - merge

        Model model1single = null;
        Model model2single = null;
        Map<String, Model> model1Structure = new HashMap<>();
        Map<String, Model> model2Structure = new HashMap<>();
        Map<String, String> model1IDname = new HashMap<>();
        Map<String, String> model2IDname = new HashMap<>();
        Resource mdFullModelRes = ResourceFactory.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        Resource mdDiffernceModelRes = ResourceFactory.createResource("http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel");
        Model model1 = ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = model1.getNsPrefixMap();

        for (File item : MainController.IDModel1) {
            if (item.getName().toLowerCase().endsWith(".zip")) {
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model1single = eu.griddigit.cimpal.main.util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 3);
                } else {
                    model1single = eu.griddigit.cimpal.main.util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
                }
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model1single = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model1single, inputStream, xmlBase, Lang.RDFXML);
                } else {
                    model1single = eu.griddigit.cimpal.main.util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                }
            }
            prefixMap.putAll(model1single.getNsPrefixMap());
            model1.add(model1single);
            String model1ID = "";
            if (model1single.listStatements(null, RDF.type, mdFullModelRes).hasNext()) {
                model1ID = model1single.listStatements(null, RDF.type, mdFullModelRes).nextStatement().getSubject().getLocalName();
            } else if (model1single.listStatements(null, RDF.type, mdDiffernceModelRes).hasNext()) {
                model1ID = model1single.listStatements(null, RDF.type, mdDiffernceModelRes).nextStatement().getSubject().getLocalName();
            }
            model1Structure.put(model1ID, model1single);
            model1IDname.put(model1ID, item.toString().toLowerCase());
        }
        model1.setNsPrefixes(prefixMap);


        // if model 2 is more than 1 zip or xml - merge
        Model model2 = ModelFactory.createDefaultModel();
        prefixMap = model2.getNsPrefixMap();

        for (File item : MainController.IDModel2) {
            if (item.getName().toLowerCase().endsWith(".zip")) {
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model2single = eu.griddigit.cimpal.main.util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 3);
                } else {
                    model2single = eu.griddigit.cimpal.main.util.ModelFactory.unzip(item, dataTypeMap, xmlBase, 2);
                }
            } else if (item.getName().toLowerCase().endsWith(".xml")) {
                InputStream inputStream = new FileInputStream(item);
                if (fcbIDmap.getSelectionModel().getSelectedItem().toString().equals("No datatypes mapping")) {
                    model2single = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model2single, inputStream, xmlBase, Lang.RDFXML);
                } else {
                    model2single = eu.griddigit.cimpal.main.util.ModelFactory.modelLoadXMLmapping(inputStream, dataTypeMap, xmlBase);
                }

            }
            prefixMap.putAll(model2single.getNsPrefixMap());
            model2.add(model2single);
            String model2ID = "";
            if (model2single.listStatements(null, RDF.type, mdFullModelRes).hasNext()) {
                model2ID = model2single.listStatements(null, RDF.type, mdFullModelRes).nextStatement().getSubject().getLocalName();
            } else if (model2single.listStatements(null, RDF.type, mdDiffernceModelRes).hasNext()) {
                model2ID = model2single.listStatements(null, RDF.type, mdDiffernceModelRes).nextStatement().getSubject().getLocalName();
            }
            model2Structure.put(model2ID, model2single);
            model2IDname.put(model2ID, item.toString().toLowerCase());
        }
        model2.setNsPrefixes(prefixMap);
        ComparisonSHACLshapes.modelsABPrefMap = prefixMap;


        Model compareIDmodel1;
        Model compareIDmodel2;
        List<String> IDCompareFiles;
        if (fcbIDcompPerFileAndAll.isSelected()) {


            for (Map.Entry<String, Model> entry : model1Structure.entrySet()) {
                String key = entry.getKey();
                Model valueModel1 = entry.getValue();
                IDCompareFiles = new LinkedList<>();
                IDCompareFiles.add(model1IDname.get(key));
                IDCompareFiles.add(model2IDname.get(key));
                compareIDmodel1 = valueModel1;
                Model valueModel2 = model2Structure.get(key);
                compareIDmodel2 = valueModel2;

                if (valueModel2 == null) {
                    System.out.printf("WARNING: The dataset ID is not part of the 2nd set: %s . Checking if there is matching file name.....\n", key);
                    //check the file name
                    boolean foundMatch = false;
                    for (File item : MainController.IDModel1) {
                        if (model1IDname.get(key).equals(item.toString())) {
                            for (File item1 : MainController.IDModel2) {
                                if (item1.getName().equals(item.getName())) {
                                    //get the ID and find it in the structure
                                    for (Map.Entry<String, String> entry1 : model2IDname.entrySet()) {
                                        String key1 = entry1.getKey();
                                        String value1 = entry1.getValue();
                                        if (value1.contains(item1.toString())) {
                                            valueModel2 = model2Structure.get(key1);
                                            compareIDmodel2 = valueModel2;
                                            foundMatch = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (foundMatch) {
                        continue;
                    } else {
                        System.out.printf("WARNING: The dataset filename is not part of the 2nd set: %s . No comparison is done for this file. \n", model1IDname.get(key));
                        break;
                    }
                }

                LinkedList<Integer> options = new LinkedList<>();
                options.add(0); //1 is ignore sv classes
                options.add(0); // 1 is to ignore DL classes
                options.add(0); //1 is to do the count
                options.add(0); // 1 is to ignore TP
                options.add(0); // 1 is to do SV only
                options.add(0);
                options.add(0);
                options.add(0);
                if (fcbIDcompIgnoreSV.isSelected()) {
                    options.set(0, 1);
                }
                if (fcbIDcompIgnoreDL.isSelected()) {
                    options.set(1, 1);
                }
                if (fcbIDcompCount.isSelected()) {
                    options.set(3, 1);
                    compareResults = CompareFactory.compareCountClasses(compareResults, valueModel1, valueModel2);
                }
                if (fcbIDcompIgnoreTP.isSelected()) {
                    options.set(4, 1);
                }
                if (fcbIDcompSVonlyCN.isSelected()) {
                    options.set(5, 1);
                }
                if (fcbIDcompDiff.isSelected()) {
                    options.set(6, 1);
                }
                if (fcbIDcompPerFileAndAll.isSelected()) {
                    options.set(7, 1);
                }
                if (fcbIDcompSVonly.isSelected()) {
                    options.set(2, 1);
                    compareResults = ComparisonInstanceData.compareSolution(compareResults, valueModel1, valueModel2, xmlBase, options);
                }
                if (!fcbIDcompCount.isSelected() && !fcbIDcompSVonly.isSelected()) {
                    compareResults = ComparisonInstanceData.compareInstanceData(compareResults, valueModel1, valueModel2, options);
                }

                if (fcbIDcompSolutionOverview.isSelected()) {
                    compareResults = ComparisonInstanceData.compareSolution(compareResults, valueModel1, valueModel2, xmlBase, options);
                    List<String> solutionOverviewResult = ComparisonInstanceData.solutionOverview(mainController.getGuiHelper());
                }


                if (!compareResults.isEmpty()) {

                    if (fcbIDcompShowDetails.isSelected()) {
                        RDFCompareResult result = new RDFCompareResult(); // Todo make a new class for the instance data comparison results
                        for (Object diffItem : compareResults) {
                            List<String> item = (List<String>) diffItem;
                            result.addEntry(new RDFCompareResultEntry(item.get(0), item.get(1), item.get(2), item.get(3), item.get(4)));
                        }
                        rdfCompareResult = result;
                        try {
                            Stage guiRdfDiffResultsStage = new Stage();
                            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getResource("/fxml/rdfDiffResult.fxml")));
                            Parent rootRDFdiff = loader.load();
                            rdfDiffResultController controller = loader.getController();
                            Scene rdfDiffscene = new Scene(rootRDFdiff);
                            guiRdfDiffResultsStage.setScene(rdfDiffscene);
                            guiRdfDiffResultsStage.setTitle("Comparison Instance data");
                            guiRdfDiffResultsStage.initModality(Modality.APPLICATION_MODAL);
                            controller.initData(guiRdfDiffResultsStage, result, IDCompareFiles);
                            guiRdfDiffResultsStage.showAndWait();

                        } catch (IOException e) {
                            GUIhelper.showUserFriendlyError("Comparison view error", "The comparison result window could not be opened.", e);
                        }
                    }
                } else {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setContentText("The two models are identical.");
                    alert.setHeaderText(null);
                    alert.setTitle("Information");
                    alert.showAndWait();
                }
            }


        } else {

            //proceed with the comparison

            IDCompareFiles = new LinkedList<>();
            if (MainController.IDModel1.size() == 1) {
                IDCompareFiles.add(MainController.IDModel1.getFirst().getName());
            } else {
                IDCompareFiles.add("Model 1");
            }
            if (MainController.IDModel2.size() == 1) {
                IDCompareFiles.add(MainController.IDModel2.getFirst().getName());
            } else {
                IDCompareFiles.add("Model 2");
            }

            compareIDmodel1 = model1;
            compareIDmodel2 = model2;

            LinkedList<Integer> options = new LinkedList<>();
            options.add(0); //1 is ignore sv classes
            options.add(0); // 1 is to ignore DL classes
            options.add(0); //1 is to do the count
            options.add(0); // 1 is to ignore TP
            options.add(0); // 1 is to do SV only
            options.add(0);
            options.add(0);
            if (fcbIDcompIgnoreSV.isSelected()) {
                options.set(0, 1);
            }
            if (fcbIDcompIgnoreDL.isSelected()) {
                options.set(1, 1);
            }
            if (fcbIDcompCount.isSelected()) {
                options.set(3, 1);
                compareResults = CompareFactory.compareCountClasses(compareResults, model1, model2);
            }
            if (fcbIDcompIgnoreTP.isSelected()) {
                options.set(4, 1);
            }
            if (fcbIDcompSVonlyCN.isSelected()) {
                options.set(5, 1);
            }
            if (fcbIDcompDiff.isSelected()) {
                options.set(6, 1);
            }
            if (fcbIDcompPerFileAndAll.isSelected()) {
                options.set(7, 1);
            }
            if (fcbIDcompSVonly.isSelected()) {
                options.set(2, 1);
                compareResults = ComparisonInstanceData.compareSolution(compareResults, model1, model2, xmlBase, options);
            }
            if (!fcbIDcompCount.isSelected() && !fcbIDcompSVonly.isSelected()) {
                compareResults = ComparisonInstanceData.compareInstanceData(compareResults, model1, model2, options);
            }

            if (fcbIDcompSolutionOverview.isSelected()) {
                compareResults = ComparisonInstanceData.compareSolution(compareResults, model1, model2, xmlBase, options);
                List<String> solutionOverviewResult = ComparisonInstanceData.solutionOverview(mainController.getGuiHelper());
            }


            if (!compareResults.isEmpty()) {

                if (fcbIDcompShowDetails.isSelected()) {
                    RDFCompareResult result = new RDFCompareResult(); // Todo make a new class for the instance data comparison results
                    for (Object diffItem : compareResults) {
                        List<String> item = (List<String>) diffItem;
                        result.addEntry(new RDFCompareResultEntry(item.get(0), item.get(1), item.get(2), item.get(3), item.get(4)));
                    }
                    rdfCompareResult = result;
                    try {
                        Stage guiRdfDiffResultsStage = new Stage();
                        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getResource("/fxml/rdfDiffResult.fxml")));
                        Parent rootRDFdiff = loader.load();
                        rdfDiffResultController controller = loader.getController();
                        Scene rdfDiffscene = new Scene(rootRDFdiff);
                        guiRdfDiffResultsStage.setScene(rdfDiffscene);
                        guiRdfDiffResultsStage.setTitle("Comparison Instance data");
                        guiRdfDiffResultsStage.initModality(Modality.APPLICATION_MODAL);
                        controller.initData(guiRdfDiffResultsStage, result, IDCompareFiles);
                        guiRdfDiffResultsStage.showAndWait();

                    } catch (IOException e) {
                        GUIhelper.showUserFriendlyError("Comparison view error", "The comparison result window could not be opened.", e);
                    }
                }
            } else {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setContentText("The two models are identical.");
                alert.setHeaderText(null);
                alert.setTitle("Information");
                alert.showAndWait();
            }
        }
        setProgressBar(1);

    }

    @FXML
    //Action for button "Reset" related to the Instance data Comparison
    private void actionBtnResetIDComp() {
        fPathIDfile1.clear();
        fPathIDfile2.clear();
        fPathIDmap.clear();
        fPathIDmapXmlBase.clear();
        fcbIDformat.getSelectionModel().clearSelection();
        fcbIDmap.getSelectionModel().clearSelection();
        btnRunIDcompare.setDisable(true);
        fcbIDcompIgnoreSV.setSelected(false);
        fcbIDcompIgnoreDL.setSelected(false);
        fcbIDcompIgnoreTP.setSelected(false);
        fcbIDcompSVonly.setSelected(false);
        fcbIDcompCount.setSelected(false);
        fcbIDcompSVonlyCN.setSelected(false);
        fcbIDcompSolutionOverview.setSelected(false);
        fcbIDcompShowDetails.setSelected(false);
        resetProgressBar();

    }
}
