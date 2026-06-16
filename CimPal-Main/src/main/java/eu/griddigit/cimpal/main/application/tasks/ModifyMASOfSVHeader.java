package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.Map;

public class ModifyMASOfSVHeader implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String masOfSVHeader;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public ModifyMASOfSVHeader() {
        this.name = "Modify MAS of SV Header";
        this.pathToFXML = "/fxml/wizardPages/taskElements/modifyMASOfSVHeader.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();

        taskUpdater.updateState(parent, "Executing", "11%", wizardContext);

        var instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {

            if (entry.getKey().contains("SV")) {
                Model model = entry.getValue().getBaseInstanceModel();

                Statement fileIDhead1 = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
                if (!masOfSVHeader.isBlank()) {
                    if (model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null).hasNext()) {
                        Statement descrStmt = model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null).next();
                        model.remove(descrStmt);
                        model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), ResourceFactory.createPlainLiteral(masOfSVHeader)));
                    } else {
                        model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), ResourceFactory.createPlainLiteral(masOfSVHeader)));
                    }
                }
                entry.getValue().setBaseInstanceModel(model);

                //save xml

            }
        }
        // saving the result
        wizardContext.saveModel(parent, saveResult);

        taskUpdater.updateState(parent, "Completed", "100%", wizardContext);
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return this.pathToFXML;
    }

    @Override
    public String getStatus() {
        return this.status;
    }

    @Override
    public String getInfo() {
        return this.info;
    }

    public void setNewMASOfSVHeader(String newMASOfSVHeader) {
        masOfSVHeader = newMASOfSVHeader;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        if (masOfSVHeader == null || masOfSVHeader.isEmpty()) {
            return "No input for MAS of SV Header for task: " + name + "\n";
        }

        return "";
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}

