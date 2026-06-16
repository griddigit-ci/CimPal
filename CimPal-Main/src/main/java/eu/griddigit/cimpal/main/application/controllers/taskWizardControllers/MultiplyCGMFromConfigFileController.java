package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.MultiplyCGMFromConfigXLS;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import eu.griddigit.cimpal.main.application.datagenerator.InstanceDataFactory;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ResourceBundle;

public class MultiplyCGMFromConfigFileController  implements Initializable {

    @FXML
    public CheckBox saveResult;
    @FXML
    public TextField loadedXMLFile;

    @FXML
    public TextField loadedMasFile;

    @FXML
    public CheckBox persistentEQflag;

    @FXML
    public CheckBox useGeneratedMasFile;

    @FXML
    public Button browseMasFile;

    @FXML
    public TextField tpbdId;

    private File tpbdFile;


    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();

        for(SelectedTask task : wizardContext.getSelectedTasks()){
            if (task.getName().equals("Multiply CGM from Config xls")) {
                browseMasFile.setDisable(true);
                break;
            }
            if (task.getName().equals("Multiply IGM from Config xls")){
                browseMasFile.setDisable(false);
                break;
            }
        }

        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });

        persistentEQflag.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setPersistentEQflag(newValue);
        });

        useGeneratedMasFile.selectedProperty().addListener((observable, oldValue, newValue) -> {
            browseMasFile.setDisable(newValue);

            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setUsePreviousMasFile(newValue);
        });

        tpbdId.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setTpbdId(newValue);
        });
    }

    @FXML
    private void actionBrowseMasFiles() {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Input MAS data XLS", "*.xlsx"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        File file = filechooser.showOpenDialog(null);


        if (file != null) {// the file is selected
            loadedMasFile.setText(file.toString());
            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setInputMASCGMFilePath(file.toString());
            wizardContext.setLastOpenedDir(file.getParentFile());
        }

        else{
            loadedMasFile.clear();
        }
    }

    @FXML
    //action button Browse for input XLS file - related to the checkbox Multiply CGM
    private void actionBrowseMultiplyCGMFiles() {

        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Input data XLS", "*.xlsx"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        File file = filechooser.showOpenDialog(null);


        if (file != null) {// the file is selected
            loadedXMLFile.setText(file.toString());
            MultiplyCGMFromConfigXLS task = (MultiplyCGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyCGMFromConfigXLS.class).findFirst().get().getTask();
            task.setInputCGMConfigXmlFilePath(file.toString());
            wizardContext.setLastOpenedDir(file.getParentFile());
        }

        else{
            loadedXMLFile.clear();
        }
    }
    public void browsTPBDFile(ActionEvent actionEvent) {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("TPBD File", "*.zip"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        tpbdFile = filechooser.showOpenDialog(null);
        //load and get Id
        InputStream inputStream = InstanceDataFactory.unzip(tpbdFile);
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, inputStream, xmlBase, Lang.RDFXML);
        wizardContext.getDataGeneratorModel().setTpbdModel(model);
        //get the ID of the TP
        String readTpdbId = model.listStatements(null, RDF.type, (RDFNode) ResourceFactory.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next().getSubject().toString();
        if (!readTpdbId.isEmpty()) {
            tpbdId.setText(readTpdbId);
            tpbdId.setDisable(true);
            tpbdId.setEditable(false);
            wizardContext.setLastOpenedDir(tpbdFile.getParentFile());
        }
        else {
            tpbdId.clear();
            tpbdId.setDisable(false);
            tpbdId.setEditable(true);
        }
    }

}