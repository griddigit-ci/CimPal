package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import datagenerator.ModelManipulationFactory;
import datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.Model;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MultiplyBaseModelNTimes implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private Integer timesToMultiply;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public MultiplyBaseModelNTimes() {
        this.name = "Multiply base model N times";
        this.pathToFXML = "/fxml/wizardPages/taskElements/multiplyNTimes.fxml";
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
        taskUpdater.updateState(parent,"Loading Completed", "10%", wizardContext);

        var instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        var modelsMapForMultiply = new HashMap<String, Model>();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            modelsMapForMultiply.put( entry.getKey(), entry.getValue().getBaseInstanceModel());
        }

        for (int i = 0; i < timesToMultiply; i++) {
            // Timestamp each new multiply with the date of generating the new RDFIDs for the multiplied models
            var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.from(ZoneOffset.UTC)).format(Instant.now());

            // Change the RDFIDs over and over again N times
            Map<String, Model> modifiedInstanceDataMap = ModelManipulationFactory.regenerateRDFIDmodule(modelsMapForMultiply, new ArrayList(), String.valueOf(1));

            // Create new Base instance Model entries for the multiplied entries
            for (Map.Entry<String, Model> entry : modifiedInstanceDataMap.entrySet()) {
                var nameArray = entry.getKey().split("_", 5);
                var newBaseInstanceModel = new BaseInstanceModel(timestamp, nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                newBaseInstanceModel.setBaseInstanceModel(entry.getValue());
                instanceModel.put(newBaseInstanceModel.getFileName(), newBaseInstanceModel);
            }

        }

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);

        taskUpdater.updateState(parent,"Completed", "100%", wizardContext);
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

    public void setTimesToMultiply(Integer timesToMultiply) {
        this.timesToMultiply = timesToMultiply;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        var message = "";
        if (timesToMultiply == null || timesToMultiply == 0) {
            message += "No valid value found for number of times to multiply for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}