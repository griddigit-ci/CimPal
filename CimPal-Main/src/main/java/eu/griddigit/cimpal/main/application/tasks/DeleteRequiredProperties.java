package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import datagenerator.GuiHelper;
import datagenerator.ShaclManipulationsService;
import datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeleteRequiredProperties implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String[] shaclFilesPath;
    private TaskStateUpdater taskUpdater;
    private ShaclManipulationsService shaclManipulationsService;
    private boolean saveResult;

    public DeleteRequiredProperties() {
        this.name = "Delete Required Properties";
        this.pathToFXML = "/fxml/wizardPages/taskElements/deleteRequiredProperties.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.shaclManipulationsService = new ShaclManipulationsService();
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent,"In Progress", "10%", wizardContext);

        taskUpdater.updateState(parent, "Loading Shacl Data", "11%", wizardContext);

        wizardContext.getDataGeneratorModel().loadShaclModel(this.shaclFilesPath);

        taskUpdater.updateState(parent, "Deleting Properties", "15%", wizardContext);

        var dataModel = wizardContext.getDataGeneratorModel();
        var instanceModel = dataModel.getBaseInstanceModel();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            List<Statement> stmtToRemove = new ArrayList<>();

            if (!entry.getKey().contains("unionModel") && !entry.getKey().contains("modelUnionWithoutHeader")){
                Model model = entry.getValue().getBaseInstanceModel();
                for (StmtIterator stmt = model.listStatements(); stmt.hasNext();) {
                    Statement stmtItem = stmt.next();
                    Resource subject = stmtItem.getSubject();
                    Property predicate = stmtItem.getPredicate();

                    Map<String,Object> shaclContraintResult;
                    Property fullClassPropertyKey = ResourceFactory.createProperty(model.getRequiredProperty(subject, RDF.type).getObject().toString()+"propertyFullURI"+predicate.toString());
                    if (dataModel.getShaclConstraints().containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                        shaclContraintResult = ShaclManipulationsService.getShaclConstraint(true, fullClassPropertyKey, dataModel.getShaclConstraints());

                        if (shaclContraintResult.containsKey("minCount")) {
                            if (((RDFNode) shaclContraintResult.get("minCount")).asLiteral().getInt() >= 1){
                                //it is required attribute =>  put it for deletion
                                stmtToRemove.add(stmtItem);
                            }
                        }
                    }
                }

                model.remove(stmtToRemove);
                entry.getValue().setBaseInstanceModel(model);
                GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"Model part "+entry.getKey()+" has "+ stmtToRemove.size() +" properties removed.", true);
            }
        }

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);
        taskUpdater.updateState(parent,"Completed", "100%", wizardContext);
        System.out.println("Executing task: " + this.name);
    }

    @Override
    public String validateInputs() {
        if (shaclFilesPath == null || shaclFilesPath.length == 0) {
            return "No SHACL files selected for task: " + name + "\n";
        }

        return "";
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
    public void setShaclFilesPath(String[] shaclFilesPath) {
        this.shaclFilesPath = shaclFilesPath;
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
}
