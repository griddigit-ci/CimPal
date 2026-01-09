package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.tasks.MultiplyIGMFromConfigXLS;
import eu.griddigit.cimpal.main.application.datagenerator.InstanceDataFactory;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ResourceBundle;

public class MultiplyIGMFromConfigFileController implements Initializable {
    @FXML
    public TextArea loadedXMLFile;

    @FXML
    public TextField eqbdId;

    @FXML
    public TextField tpbdId;

    @FXML
    public CheckBox persistentEQ;
    @FXML
    public CheckBox saveResult;

    private File eqbdFile;

    private File tpbdFile;

    private WizardContext wizardContext;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        wizardContext = WizardContext.getInstance();
        persistentEQ.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyIGMFromConfigXLS task = (MultiplyIGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyIGMFromConfigXLS.class).findFirst().get().getTask();
            task.setPersistentEQ(newValue);
        });

        eqbdId.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyIGMFromConfigXLS task = (MultiplyIGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyIGMFromConfigXLS.class).findFirst().get().getTask();
            task.setEqbdId(newValue);
        });

        tpbdId.textProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyIGMFromConfigXLS task = (MultiplyIGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyIGMFromConfigXLS.class).findFirst().get().getTask();
            task.setTpbdId(newValue);
        });


        saveResult.selectedProperty().addListener((observable, oldValue, newValue) -> {
            MultiplyIGMFromConfigXLS task = (MultiplyIGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyIGMFromConfigXLS.class).findFirst().get().getTask();
            task.setSaveResult(newValue);
        });
    }

    @FXML
    //action button Browse for input XLS file - related to the checkbox Multiply CGM
    private void actionBrowseMultiplyIGMFiles() {

        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Input data XLS", "*.xlsx"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        File file = filechooser.showOpenDialog(null);

        if (file != null ) {// the file is selected
            loadedXMLFile.setText(file.toString());
            MultiplyIGMFromConfigXLS task = (MultiplyIGMFromConfigXLS) wizardContext.getSelectedTasks().stream().filter(x -> x.getTask().getClass() == MultiplyIGMFromConfigXLS.class).findFirst().get().getTask();
            task.setMultiplyXMLFile(file.toString());
            wizardContext.setLastOpenedDir(file.getParentFile());
            //MainController.inputXLSCGM = file;
        }

        else{
            loadedXMLFile.clear();
        }
    }

    public void browsEQBDFile(ActionEvent actionEvent) {
        //select file
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("EQBD File", "*.zip"));
        filechooser.setInitialDirectory(new File(wizardContext.getLastOpenedDir().toString()));
        eqbdFile = filechooser.showOpenDialog(null);
        //load and get Id

        InputStream inputStream = InstanceDataFactory.unzip(eqbdFile);
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, inputStream, xmlBase, Lang.RDFXML);
        wizardContext.getDataGeneratorModel().setEqbdModel(model);
        //get the ID of the EQ
        String readEqdbId = model.listStatements(null, RDF.type, (RDFNode) ResourceFactory.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next().getSubject().toString();
        if (!readEqdbId.isEmpty()) {
            eqbdId.setText(readEqdbId);
            eqbdId.setDisable(true);
            eqbdId.setEditable(false);
            wizardContext.setLastOpenedDir(eqbdFile.getParentFile());
        }
        else {
            eqbdId.clear();
            eqbdId.setDisable(false);
            eqbdId.setEditable(true);
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
        Model model = ModelFactory.createDefaultModel();
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
