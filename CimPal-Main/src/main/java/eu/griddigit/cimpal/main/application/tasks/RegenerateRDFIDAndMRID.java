package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.Model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class RegenerateRDFIDAndMRID implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private boolean saveResult;
    private TaskStateUpdater taskUpdater;

    public RegenerateRDFIDAndMRID() {
        this.name = "Regenerate rdf:ID and mRID";
        this.pathToFXML = "/fxml/wizardPages/taskElements/regenRDFIDAndMRID.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent, "Executing..", "10%", wizardContext);

        var instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        var modelsMapForChangeIds = new HashMap<String, Model>();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            modelsMapForChangeIds.put(entry.getKey(), entry.getValue().getBaseInstanceModel());
        }
        var modifiedBaseInstanceModel = new HashMap<String, BaseInstanceModel>();
        // Change the RDFIDs
        Map<String, Model> modifiedInstanceDataMap = ModelManipulationFactory.regenerateRDFIDmodule(modelsMapForChangeIds, new ArrayList(), String.valueOf(1));

        // Update Instance model entries with mutated entries
        for (Map.Entry<String, Model> entry : modifiedInstanceDataMap.entrySet()) {
            var newBaseInstanceModel = new BaseInstanceModel(entry.getKey());
            newBaseInstanceModel.setBaseInstanceModel(modifiedInstanceDataMap.get(entry.getKey()));
            modifiedBaseInstanceModel.put(entry.getKey(), newBaseInstanceModel);
        }
        wizardContext.getDataGeneratorModel().setBaseInstanceModel(modifiedBaseInstanceModel);

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
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

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        return "";
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}

