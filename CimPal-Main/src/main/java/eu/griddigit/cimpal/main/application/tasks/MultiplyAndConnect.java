package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import datagenerator.ModelManipulationFactory;
import datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.*;

public class MultiplyAndConnect implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private Integer timesToMultiply;
    private String connectivityNodeId;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public MultiplyAndConnect() {
        this.name = "Multiply and connect";
        this.pathToFXML = "/fxml/wizardPages/taskElements/multiplyNTimesAndConnect.fxml";
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
        var instanceModelMap = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        var modelsMapForChangeIds = new HashMap<String, Model>();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModelMap.entrySet()) {
            modelsMapForChangeIds.put(entry.getKey(), entry.getValue().getBaseInstanceModel());
        }

        // keep model 1

        Map<String, Model> modifiedInstanceDataMultipliedMap = new HashMap<>();

        List<String> skipList = new ArrayList();
        skipList.add("FullModel");
        skipList.add("ControlArea");
        skipList.add("LoadArea");
        skipList.add("SubGeographicalRegion");
        skipList.add("GeographicalRegion");
        ModelManipulationFactory.cnNewUUIDconnectTemp = "1";
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();

        taskUpdater.updateState(parent, "Loading Original Base Model", "10%", wizardContext);

        //var baseInstanceModelMapOriginal = wizardContext.getDataGeneratorModel().loadOriginalBaseInstanceModel();
        var baseInstanceModelMapOriginal = wizardContext.getDataGeneratorModel().getBaseInstanceModel();

        taskUpdater.updateState(parent, "Multiplying..", "20%", wizardContext);
        for (int m = 0; m < timesToMultiply - 1; m++) {
            // regenerate IDs of model 1 = model x
            Map<String, Model> modifiedInstanceDataMap = ModelManipulationFactory.regenerateRDFIDmodule(modelsMapForChangeIds, skipList, timesToMultiply.toString());

            String ebID=String.valueOf(UUID.randomUUID());
            Resource ebRes = ResourceFactory.createResource(xmlBase+"#_"+ebID);
            String lineID=String.valueOf(UUID.randomUUID());
            Resource lineRes = ResourceFactory.createResource(xmlBase+"#_"+lineID);
            String term1ID=String.valueOf(UUID.randomUUID());
            Resource term1Res = ResourceFactory.createResource(xmlBase+"#_"+term1ID);
            String term2ID=String.valueOf(UUID.randomUUID());
            Resource term2Res = ResourceFactory.createResource(xmlBase+"#_"+term2ID);

            for (Map.Entry<String, Model> entry : modifiedInstanceDataMap.entrySet()) {
                if (!entry.getKey().contains("unionModel") && !entry.getKey().contains("modelUnionWithoutHeader")) {
                    if (m==0){
                        modifiedInstanceDataMultipliedMap.put(entry.getKey(), baseInstanceModelMapOriginal.get(entry.getKey()).getBaseInstanceModel());
                    }
                    Model tempUnionModel = modifiedInstanceDataMultipliedMap.get(entry.getKey());
                    tempUnionModel.add(entry.getValue());


                    // connect model x to model 1
                    // cnIDwhenMultiply is the ID of the ConnectivityNode in the base model
                    // cnNewUUIDconnect is the ID of the ConnectivityNode after the change ID
                    // EquivalentBranch needs to be connected between the two ConnectivityNode-s


                    if (entry.getKey().contains("SSH")){
                        //create statements
                        List<Statement> sshStmtList = new ArrayList<>();

                        sshStmtList.add(ResourceFactory.createStatement(term1Res, RDF.type,ResourceFactory.createProperty(xmlBase+"#Terminal")));
                        sshStmtList.add(ResourceFactory.createStatement(term1Res,ResourceFactory.createProperty(xmlBase+"#ACDCTerminal.connected"),ResourceFactory.createPlainLiteral("true")));

                        sshStmtList.add(ResourceFactory.createStatement(term2Res,RDF.type,ResourceFactory.createProperty(xmlBase+"#Terminal")));
                        sshStmtList.add(ResourceFactory.createStatement(term2Res,ResourceFactory.createProperty(xmlBase+"#ACDCTerminal.connected"),ResourceFactory.createPlainLiteral("true")));

                        tempUnionModel.add(sshStmtList);
                    } else if (entry.getKey().contains("EQ")){
                        //create statements
                        List<Statement> eqStmtList = new ArrayList<>();

                            /*eqStmtList.add(ResourceFactory.createStatement(ebRes,RDF.type,ResourceFactory.createProperty(xmlBase+"#EquivalentBranch")));
                            eqStmtList.add(ResourceFactory.createStatement(ebRes,ResourceFactory.createProperty(xmlBase+"#EquivalentBranch.r"),ResourceFactory.createPlainLiteral("0.011")));
                            eqStmtList.add(ResourceFactory.createStatement(ebRes,ResourceFactory.createProperty(xmlBase+"#EquivalentBranch.x"),ResourceFactory.createPlainLiteral("0.011")));
                            eqStmtList.add(ResourceFactory.createStatement(ebRes,ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.name"),ResourceFactory.createPlainLiteral("EquivalentBranch "+m)));
                            eqStmtList.add(ResourceFactory.createStatement(ebRes,ResourceFactory.createProperty(xmlBase+"#Equipment.EquipmentContainer"),ResourceFactory.createProperty(lineRes.toString())));*/

                        eqStmtList.add(ResourceFactory.createStatement(ebRes, RDF.type,ResourceFactory.createProperty(xmlBase+"#ACLineSegment")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#ACLineSegment.bch"),ResourceFactory.createPlainLiteral("0.0001875")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#ACLineSegment.gch"),ResourceFactory.createPlainLiteral("0")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#ACLineSegment.r"),ResourceFactory.createPlainLiteral("0.011")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#ACLineSegment.x"),ResourceFactory.createPlainLiteral("0.011")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#Conductor.length"),ResourceFactory.createPlainLiteral("0.01")));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.name"),ResourceFactory.createPlainLiteral("ACLineSegment "+m)));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#Equipment.EquipmentContainer"),ResourceFactory.createProperty(lineRes.toString())));
                        eqStmtList.add(ResourceFactory.createStatement(ebRes, ResourceFactory.createProperty(xmlBase+"#ConductingEquipment.BaseVoltage"),ResourceFactory.createProperty(xmlBase+"#_65dd04e792584b3b912374e35dec032e"))); // the 400 kV BaseVoltage


                        eqStmtList.add(ResourceFactory.createStatement(lineRes, RDF.type,ResourceFactory.createProperty(xmlBase+"#Line")));
                        eqStmtList.add(ResourceFactory.createStatement(lineRes, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.name"),ResourceFactory.createPlainLiteral("LineEB "+m)));
                        eqStmtList.add(ResourceFactory.createStatement(lineRes, ResourceFactory.createProperty(xmlBase+"#Line.Region"),tempUnionModel.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty(xmlBase+"#SubGeographicalRegion")).next()));

                        eqStmtList.add(ResourceFactory.createStatement(term1Res, RDF.type,ResourceFactory.createProperty(xmlBase+"#Terminal")));
                        eqStmtList.add(ResourceFactory.createStatement(term1Res, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.name"), ResourceFactory.createPlainLiteral("TerminalEB-1 "+m)));
                        eqStmtList.add(ResourceFactory.createStatement(term1Res, ResourceFactory.createProperty(xmlBase+"#ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral("1")));
                        eqStmtList.add(ResourceFactory.createStatement(term1Res, ResourceFactory.createProperty(xmlBase+"#Terminal.ConductingEquipment"), ResourceFactory.createProperty(ebRes.toString())));
                        eqStmtList.add(ResourceFactory.createStatement(term1Res, ResourceFactory.createProperty(xmlBase+"#Terminal.ConnectivityNode"), ResourceFactory.createProperty(xmlBase + "#" + connectivityNodeId)));

                        eqStmtList.add(ResourceFactory.createStatement(term2Res, RDF.type,ResourceFactory.createProperty(xmlBase+"#Terminal")));
                        eqStmtList.add(ResourceFactory.createStatement(term2Res, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.name"), ResourceFactory.createPlainLiteral("TerminalEB-2 "+m)));
                        eqStmtList.add(ResourceFactory.createStatement(term2Res, ResourceFactory.createProperty(xmlBase+"#ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral("2")));
                        eqStmtList.add(ResourceFactory.createStatement(term2Res, ResourceFactory.createProperty(xmlBase+"#Terminal.ConductingEquipment"), ResourceFactory.createProperty(ebRes.toString())));
                        eqStmtList.add(ResourceFactory.createStatement(term2Res, ResourceFactory.createProperty(xmlBase+"#Terminal.ConnectivityNode"), ResourceFactory.createProperty(xmlBase+"#_" + connectivityNodeId)));

                        tempUnionModel.add(eqStmtList);
                    }

                    var nameArray = entry.getKey().split("_", 5);
                    var newBaseInstanceModel = new BaseInstanceModel(nameArray[0], nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                    newBaseInstanceModel.setBaseInstanceModel(tempUnionModel);

                    modifiedInstanceDataMultipliedMap.put(entry.getKey(), tempUnionModel);

                    wizardContext.getDataGeneratorModel().getBaseInstanceModel().put(entry.getKey(), newBaseInstanceModel);

                    //}
                }

            }

            taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
            // saving the result
            wizardContext.saveModel(parent, saveResult);

            taskUpdater.updateState(parent, "Completed", "100%", wizardContext);

        }


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

    public void setConnectivityNodeId(String connectivityNodeId) {
        this.connectivityNodeId = connectivityNodeId;
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

        if (connectivityNodeId == null || connectivityNodeId.isEmpty() ) {
            message += "No Connectivity Node Id input for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}
