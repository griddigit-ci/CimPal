package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import application.tasks.ITask;
import application.tasks.SelectedTask;
import datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.Map;

public class ChangeModelHeaderDescription implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private String newHeaderDescription;
    private boolean saveResult;

    public ChangeModelHeaderDescription() {
        this.name = "Change Model Header Description";
        this.pathToFXML = "/fxml/wizardPages/taskElements/changeModelHeaderDescription.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent,"In Progress", "10%", wizardContext);

        var baseInstanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        for (Map.Entry<String, BaseInstanceModel> entry : baseInstanceModel.entrySet()) {

            var model = entry.getValue().getBaseInstanceModel();
            //check if there is description
            Statement fileIDhead1 = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
            if (model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null).hasNext()) {
                if (!newHeaderDescription.isBlank() && !newHeaderDescription.startsWith("fixChars|")) {
                    //use the description provided by user, i.e. replace all
                    Statement  descrStmt = model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null).next();
                    model.remove(descrStmt);
                    model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(newHeaderDescription)));
                }
                else if (!newHeaderDescription.isBlank() || newHeaderDescription.startsWith("fixChars|")){
                    String[] stringParts = newHeaderDescription.split("\\|",3);
                    Statement  descrStmt = model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null).next();
                    model.remove(descrStmt);
                    model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(descrStmt.getObject().toString().replaceAll(stringParts[1],stringParts[2]))));
                }
            }
            else {
                if (!newHeaderDescription.isBlank() && !newHeaderDescription.startsWith("fixChars|")) {
                    model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(newHeaderDescription)));
                }
            }

            entry.getValue().setBaseInstanceModel(model);
        }

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);
        taskUpdater.updateState(parent,"Completed", "100%", wizardContext);
    }

    @Override
    public String validateInputs() {
        if (newHeaderDescription == null || newHeaderDescription.isEmpty()) {
            return "No new header description set for task: " + name + "\n";
        }
        return "";
    }

    public void setNewHeaderDescription(String newHeaderDescription) {
        this.newHeaderDescription = newHeaderDescription;
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

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}
