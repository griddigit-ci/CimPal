package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.Model;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiplyBaseModelNTimes implements ITask {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.from(ZoneOffset.UTC));

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

        // Snapshot of the models to copy from. regenerateRDFIDmodule() rewrites the model it is
        // handed in place and returns that same object, so every round has to start from its own
        // clone: handing it the base models directly gave all the copies - and the base model
        // itself - one shared model carrying the last round's IDs.
        var originalModels = new HashMap<String, Model>();
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            originalModels.put(entry.getKey(), entry.getValue().getBaseInstanceModel());
        }

        // One timestamp per round, taken from a single instant and stepped a second at a time.
        // The file name pattern is only accurate to the second, so reading the clock inside the
        // loop gave several rounds the same name and they overwrote each other in the map.
        var firstTimestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // timesToMultiply is how many models the result holds, the base model included, which is
        // why one fewer copy is made than the number asked for. Multiply and connect reads the
        // field the same way.
        int copiesToMake = timesToMultiply - 1;

        for (int i = 0; i < copiesToMake; i++) {
            var timestamp = TIMESTAMP_FORMAT.format(firstTimestamp.plusSeconds(i));

            // Copy first, regenerate second, so this round's new IDs land on the copy and the
            // base model stays as it was loaded.
            var modelsMapForMultiply = new HashMap<String, Model>();
            for (Map.Entry<String, Model> original : originalModels.entrySet()) {
                Model copy = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                copy.setNsPrefixes(original.getValue().getNsPrefixMap());
                copy.add(original.getValue());
                modelsMapForMultiply.put(original.getKey(), copy);
            }

            // Change the RDFIDs of this round's copy
            Map<String, Model> modifiedInstanceDataMap = ModelManipulationFactory.regenerateRDFIDmodule(modelsMapForMultiply, List.of(), new HashMap<>());

            // Create new Base instance Model entries for the multiplied entries
            for (Map.Entry<String, Model> entry : modifiedInstanceDataMap.entrySet()) {
                var nameArray = entry.getKey().split("_", 5);
                var newBaseInstanceModel = new BaseInstanceModel(timestamp, nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                newBaseInstanceModel.setBaseInstanceModel(entry.getValue());
                instanceModel.put(newBaseInstanceModel.getFileName(), newBaseInstanceModel);
            }

            int progress = 10 + (int) ((double) (i + 1) / copiesToMake * 80);
            taskUpdater.updateState(parent, "Copy " + (i + 1) + " of " + copiesToMake, progress + "%", wizardContext);
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
        // The count is the total, and the base model is one of them, so below two there is nothing
        // to copy.
        if (timesToMultiply == null || timesToMultiply < 2) {
            message += "Number of models has to be 2 or more (the base model counts as one) for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}