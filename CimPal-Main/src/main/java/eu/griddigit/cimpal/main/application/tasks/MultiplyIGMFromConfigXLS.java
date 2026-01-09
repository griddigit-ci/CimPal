package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import util.ExcelTools;

import java.io.IOException;
import java.util.*;

public class MultiplyIGMFromConfigXLS implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String multiplyXMLFile;
    private String eqbdId;
    private String tpbdId;
    private boolean persistentEQ;
    private boolean saveResult;
    private TaskStateUpdater taskUpdater;

    public MultiplyIGMFromConfigXLS() {
        this.name = "Multiply IGM from Config xls";
        this.pathToFXML = "/fxml/wizardPages/taskElements/multiplyIGMFromConfigFile.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.persistentEQ = false;
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent, "Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent, "Executing", "11%", wizardContext);

        // new instance model to replace the old one
        Map<String, BaseInstanceModel> modifiedInstanceDataMultipliedMap = new HashMap<>();
        //this is to load profile data - this is needed for the export
        // Map<String, Map> loadDataMap = ModelManipulationFactory.loadDataForIGMMulDateTime(xmlBase, profileModelUnionFlag, instanceModelUnionFlag, inputData, shaclModelUnionFlag);

        // load input data xls
        ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(multiplyXMLFile, 0);

        Map<String, String> headerIdMap = new HashMap<>();
        Map<String, BaseInstanceModel> baseModelMap = wizardContext.getDataGeneratorModel().getBaseInstanceModel();

        //the case when the EQ is persistent so it is not generated. It is only read for the depend on
        Map<String, String> eqIDmap = new HashMap<>();
        if (persistentEQ) {

            if (baseModelMap.keySet().stream().anyMatch(x -> "EQ".equals(baseModelMap.get(x).getProcess()))) {
                //get the ID of the EQ
                String svModelName = baseModelMap.keySet().stream().filter(x -> "SV".equals(baseModelMap.get(x).getProcess())).findFirst().orElse("");
                Model model = baseModelMap.get(svModelName).getBaseInstanceModel();
                Statement eqfileIDhead = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
                Statement eqfileMAS = model.listStatements(eqfileIDhead.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null).next();
                //save map mas vs id of eq
                eqIDmap.putIfAbsent(eqfileMAS.getObject().toString(), eqfileIDhead.getSubject().toString());
            }
        }

        //for the xls export
        List<String> fileNamexls = new LinkedList<>();
        List<String> fileProfilexls = new LinkedList<>();
        List<String> fileMASxls = new LinkedList<>();
        List<String> fileIDxls = new LinkedList<>();

        var dataModel = wizardContext.getDataGeneratorModel();
        var instanceModel = dataModel.getBaseInstanceModel();

        int iCount = 0;
        int totalSize = instanceModel.size() * inputXLSdata.size();

        // TODO Should we exclude union models?
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            Model originalModel = entry.getValue().getBaseInstanceModel();

            String eqfileID = null;
            //get the ID of the file and MAS
            Statement fileIDhead1 = originalModel.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
            Statement fileMAS = originalModel.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null).next();

            //get the ID of the EQ

            if (persistentEQ && entry.getKey().contains("EQ")) {
                //get the MAS from the header//get the ID from the map
                eqfileID = eqIDmap.get(fileMAS.getObject().toString());
            }

            for (int i = 1; i < inputXLSdata.size(); i++) {
                try {
                    Model model = ModelFactory.createDefaultModel();
                    model.add(originalModel);

                    iCount++;
                    int progress = (int) ((double) iCount / totalSize * 100);
                    taskUpdater.updateState(parent, "Processing: " + entry.getKey(), progress + "%", wizardContext);

                    String dateTimeNew = ((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString();
                    String businessProcessNew = ((LinkedList<?>) inputXLSdata.get(i)).get(1).toString();
                    String fileNameNewEQ;
                    if (persistentEQ) {
                        fileNameNewEQ = entry.getValue().getDatetime() + "_" + entry.getValue().getProcess() + "_" + entry.getValue().getTso() + "_" + "EQ" + "_" + entry.getValue().getVersion();
                        headerIdMap.putIfAbsent(fileNameNewEQ, eqfileID);
                    } else {
                        fileNameNewEQ = dateTimeNew + "_" + businessProcessNew + "_" + entry.getValue().getTso() + "_" + "EQ" + "_" + entry.getValue().getVersion();
                        headerIdMap.putIfAbsent(fileNameNewEQ, "urn:uuid:" + UUID.randomUUID());
                    }

                    String fileNameNewSSH = dateTimeNew + "_" + businessProcessNew + "_" + entry.getValue().getTso() + "_" + "SSH" + "_" + entry.getValue().getVersion();
                    String fileNameNewTP = dateTimeNew + "_" + businessProcessNew + "_" + entry.getValue().getTso() + "_" + "TP" + "_" + entry.getValue().getVersion();

                    String fileNameNewSV = dateTimeNew + "_" + businessProcessNew + "_" + entry.getValue().getTso() + "_" + "SV" + "_" + entry.getValue().getVersion();

                    headerIdMap.putIfAbsent(fileNameNewSSH, "urn:uuid:" + UUID.randomUUID());
                    headerIdMap.putIfAbsent(fileNameNewTP, "urn:uuid:" + UUID.randomUUID());
                    headerIdMap.putIfAbsent(fileNameNewSV, "urn:uuid:" + UUID.randomUUID());

                    // material for xls export
                    //info that need to export to xls for the new generated MAS
                    //-file name
                    //-profile keyword
                    //-header MAS
                    //-file - header ID

                    fileMASxls.add(fileMAS.getObject().toString());
                    switch (entry.getValue().getProfile()) {
                        case "EQ" -> {
                            //for the xls export
                            fileNamexls.add(fileNameNewEQ);
                            fileProfilexls.add("EQ");
                            fileIDxls.add(headerIdMap.get(fileNameNewEQ));
                        }
                        case "SSH" -> {
                            //for the xls export
                            fileNamexls.add(fileNameNewSSH);
                            fileProfilexls.add("SSH");
                            fileIDxls.add(headerIdMap.get(fileNameNewSSH));
                        }
                        case "TP" -> {
                            //for the xls export
                            fileNamexls.add(fileNameNewTP);
                            fileProfilexls.add("TP");
                            fileIDxls.add(headerIdMap.get(fileNameNewTP));
                        }
                        case "SV" -> {
                            //for the xls export
                            fileNamexls.add(fileNameNewSV);
                            fileProfilexls.add("SV");
                            fileIDxls.add(headerIdMap.get(fileNameNewSV));
                        }
                    }


                    if (!persistentEQ || !entry.getValue().getProfile().equals("EQ")) {
                        //modify header
                        //get old header
                        Statement headerStmtIDOld = model.listStatements(null, RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();

                        List<Statement> headerStmtOld = model.listStatements(headerStmtIDOld.getSubject().asResource(), null, (RDFNode) null).toList();


                        // decide on new ID
                        String headerIDNew = null;
                        String fileName = null;
                        switch (entry.getValue().getProfile()) {
                            case "EQ" -> {
                                headerIDNew = headerIdMap.get(fileNameNewEQ);
                                fileName = fileNameNewEQ;
                            }
                            case "SSH" -> {
                                headerIDNew = headerIdMap.get(fileNameNewSSH);
                                fileName = fileNameNewSSH;
                            }
                            case "TP" -> {
                                headerIDNew = headerIdMap.get(fileNameNewTP);
                                fileName = fileNameNewTP;
                            }
                            case "SV" -> {
                                headerIDNew = headerIdMap.get(fileNameNewSV);
                                fileName = fileNameNewSV;
                            }
                            default ->
                                    throw new NoSuchElementException("Unsupported profile in the input model: " + entry.getValue().getProfile());
                        }

                        //add new header
                        List<Statement> headerStmtNew = new ArrayList<>();

                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                        for (Statement stmt : headerStmtOld) {
                            if (!stmt.getPredicate().toString().equals(RDF.type.toString())) {
                                //dependencies: EQ with EQBD; TP with EQ, SSH with EQ, SV with TP, SSH and TPBD
                                if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn")) {
                                    if (entry.getValue().getProfile().equals("EQ")) {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(eqbdId)));
                                    } else if (entry.getValue().getProfile().equals("SSH")) {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewEQ))));
                                    } else if (entry.getValue().getProfile().equals("TP")) {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewEQ))));
                                    } else if (entry.getValue().getProfile().equals("SV")) {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewTP))));
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewSSH))));
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(tpbdId)));
                                    }

                                } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime")) {
                                    //target date dateTimeNew
                                    //year dateTimeNewParts[0].substring(0,4)
                                    //month dateTimeNewParts[0].substring(4,6)
                                    //day dateTimeNewParts[0].substring(6,8)
                                    //time hours dateTimeNewParts[1].substring(0,2)
                                    //time min dateTimeNewParts[1].substring(2,4)

                                    String[] dateTimeNewParts = dateTimeNew.split("T", 2);
                                    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime"), (RDFNode)
                                            ResourceFactory.createPlainLiteral(dateTimeNewParts[0].substring(0, 4) + "-" +
                                                    dateTimeNewParts[0].substring(4, 6) + "-" +
                                                    dateTimeNewParts[0].substring(6, 8) + "T" +
                                                    dateTimeNewParts[1].substring(0, 2) + ":" +
                                                    dateTimeNewParts[1].substring(2, 4) + ":00Z")));

                                } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description")) {
                                    // Use '&lt;' for '<' and '&gt;' for '>'
                                    if (stmt.getObject().toString().contains("/BP")) {
                                        String[] description = stmt.getObject().toString().split("/BP", 2);
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                ResourceFactory.createPlainLiteral("<MDE><BP>" + businessProcessNew + "</BP" + description[1])));

                                        String newDescription = "&lt;MDE&gt;&lt;BP&gt;" + businessProcessNew + "&lt;/BP" + description[1];
                                        newDescription = newDescription.replace("<", "&lt;").replace(">", "&gt;");
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                ResourceFactory.createPlainLiteral(newDescription)));
                                    } else {

                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                ResourceFactory.createPlainLiteral(stmt.getObject().toString())));
                                    }
                                } else {
                                    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew),
                                            stmt.getPredicate(), stmt.getObject()));
                                }

                            }


                        }

                        //delete old header
                        model.remove(headerStmtOld);
                        //add new header
                        model.add(headerStmtNew);

                        var nameArray = fileName.split("_", 5);
                        var newBaseInstanceModel = new BaseInstanceModel(nameArray[0], nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                        newBaseInstanceModel.setBaseInstanceModel(model);

                        modifiedInstanceDataMultipliedMap.put(newBaseInstanceModel.getFileName(), newBaseInstanceModel);

                    }
                } catch (NoSuchElementException | NullPointerException ex) {
                    break;
                }
            }
        }

        // Create an XLS for MAS for CGM multiply
        ArrayList<Object> items = new ArrayList<>();
        items.add(fileNamexls);
        items.add(fileProfilexls);
        items.add(fileMASxls);
        items.add(fileIDxls);
        wizardContext.setMasForCGM(items);
        wizardContext.getDataGeneratorModel().setBaseInstanceModel(modifiedInstanceDataMultipliedMap);
        // saving the result
        wizardContext.saveModel(parent, saveResult);
        if (saveResult) {
            wizardContext.exportFileNameIDsToDefaultDir(items, "FileData", "FileMASData.xlsx", "Save file MAS data", this.name);
        }

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

    public void setMultiplyXMLFile(String multiplyXMLFilePath) {
        this.multiplyXMLFile = multiplyXMLFilePath;
    }

    public Boolean getPersistentEQ() {
        return this.persistentEQ;
    }

    public void setPersistentEQ(Boolean persistentEQ) {
        this.persistentEQ = persistentEQ;
    }

    public String getEqbdId() {
        return eqbdId;
    }

    public void setEqbdId(String eqbdId) {
        this.eqbdId = eqbdId;
    }

    public String getTpbdId() {
        return tpbdId;
    }

    public void setTpbdId(String tpbdId) {
        this.tpbdId = tpbdId;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        var message = "";
        if (eqbdId == null || eqbdId.isEmpty()) {
            message += "No valid value found for EQBD ID for task: " + name + "\n";
        }

        if (tpbdId == null || tpbdId.isEmpty()) {
            message += "No valid value found for TPBD ID for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}

