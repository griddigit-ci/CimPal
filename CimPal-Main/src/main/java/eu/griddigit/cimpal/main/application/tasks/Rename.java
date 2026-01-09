package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import eu.griddigit.cimpal.main.util.ExcelTools;

import java.io.IOException;
import java.util.*;

public class Rename implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String configXmlFilePath;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public Rename() {
        this.name = "Rename";
        this.pathToFXML = "/fxml/wizardPages/taskElements/rename.fxml";
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

        taskUpdater.updateState(parent,"Processing..", "11%", wizardContext);

        // load input data xls
        ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(configXmlFilePath, 0);
        var dataModel = wizardContext.getDataGeneratorModel();

        var instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        var xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();
        var modifiedBaseInstanceModel = new HashMap<String, BaseInstanceModel>();

        int iCount = 0;
        int totalSize = instanceModel.size() * inputXLSdata.size();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            for (int i = 1; i < inputXLSdata.size(); i++) {

                iCount++;
                int progress = (int) ((double) iCount / totalSize * 100);
                taskUpdater.updateState(parent, "Processing: " + entry.getKey(), progress + "%", wizardContext);

                String fileNameNew = null;
                Model newModel = null;
//                if (((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString().equalsIgnoreCase(entry.getValue().getTso()) ||
//                        (entry.getValue().getTso().split("-",3).length == 3 &&
//                        ((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString().equalsIgnoreCase(entry.getValue().getTso().split("-",3)[2]))) {

                Model model = entry.getValue().getBaseInstanceModel();


                // Name of GeographicalRegion / Name of ControlArea
                Property ioName = ResourceFactory.createProperty(xmlBase,"#IdentifiedObject.name");
                Property eicProp = ResourceFactory.createProperty("http://entsoe.eu/CIM/SchemaExtension/3/1","#IdentifiedObject.energyIdentCodeEic");


                List<Statement> stmpDelete = new ArrayList<>();
                List<Statement> stmpAdd = new ArrayList<>();

                if (entry.getValue().getTso().split("-",3).length==3){
                    fileNameNew = entry.getValue().getDatetime() + "_" + entry.getValue().getProcess() + "_" +
                            entry.getValue().getTso().toUpperCase().replace(((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString().toUpperCase(),((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString().toUpperCase()) + "_" +
                            entry.getValue().getProfile() + "_" + entry.getValue().getVersion();
                } else {
                    fileNameNew = entry.getValue().getDatetime() + "_" +  entry.getValue().getProcess() + "_" +
                            ((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString() + "_" +
                            entry.getValue().getProfile() + "_" + entry.getValue().getVersion();
                }
                String masname = ((LinkedList<?>) inputXLSdata.get(i)).get(1).toString();
                String geoname = ((LinkedList<?>) inputXLSdata.get(i)).get(2).toString();
                String caname = ((LinkedList<?>) inputXLSdata.get(i)).get(3).toString();
                String eiccode = ((LinkedList<?>) inputXLSdata.get(i)).get(4).toString();

                newModel = model;

                Property masprop = ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet");
                Statement stmtFullModel = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
                if (model.contains(stmtFullModel.getSubject(), masprop)) {
                    Statement fmMAS = model.listStatements(stmtFullModel.getSubject(), masprop, (RDFNode) null).next();
                    stmpDelete.add(fmMAS);
                    stmpAdd.add(ResourceFactory.createStatement(stmtFullModel.getSubject(), masprop, ResourceFactory.createPlainLiteral(masname)));
                }
                else {
                    stmpAdd.add(ResourceFactory.createStatement(stmtFullModel.getSubject(), masprop, ResourceFactory.createPlainLiteral(masname)));
                }

                if (entry.getValue().getProfile().equals("EQ")) {
                    List<Statement> geoRegStmt = model.listStatements(null, RDF.type, ResourceFactory.createProperty(xmlBase, "#GeographicalRegion")).toList();
                    for (Statement gStmt : geoRegStmt) {
                        if (model.contains(gStmt.getSubject(), ioName)) {
                            Statement geoIoName = model.listStatements(gStmt.getSubject(), ioName, (RDFNode) null).next();
                            stmpDelete.add(geoIoName);
                            stmpAdd.add(ResourceFactory.createStatement(gStmt.getSubject(), ioName, ResourceFactory.createPlainLiteral(geoname)));
                        } else {
                            stmpAdd.add(ResourceFactory.createStatement(gStmt.getSubject(), ioName, ResourceFactory.createPlainLiteral(geoname)));
                        }
                    }

                    List<Statement> caStmt = model.listStatements(null, RDF.type, ResourceFactory.createProperty(xmlBase, "#ControlArea")).toList();
                    for (Statement cStmt : caStmt) {
                        if (model.listStatements(cStmt.getSubject(), ResourceFactory.createProperty(xmlBase, "#ControlArea.type"), ResourceFactory.createProperty(xmlBase, "#ControlAreaTypeKind.Interchange")).hasNext()) {

                            if (model.contains(cStmt.getSubject(), ioName)) {
                                Statement caIoName = model.listStatements(cStmt.getSubject(), ioName, (RDFNode) null).next();
                                stmpDelete.add(caIoName);
                                stmpAdd.add(ResourceFactory.createStatement(cStmt.getSubject(), ioName, ResourceFactory.createPlainLiteral(caname)));
                            } else {
                                stmpAdd.add(ResourceFactory.createStatement(cStmt.getSubject(), ioName, ResourceFactory.createPlainLiteral(caname)));
                            }
                            if (model.contains(cStmt.getSubject(), eicProp)) {
                                Statement caeic = model.listStatements(cStmt.getSubject(), eicProp, (RDFNode) null).next();
                                stmpDelete.add(caeic);
                                stmpAdd.add(ResourceFactory.createStatement(cStmt.getSubject(), eicProp, ResourceFactory.createPlainLiteral(eiccode)));
                            } else {
                                stmpAdd.add(ResourceFactory.createStatement(cStmt.getSubject(), eicProp, ResourceFactory.createPlainLiteral(eiccode)));
                            }
                        }
                    }
                }
                newModel.remove(stmpDelete);
                newModel.add(stmpAdd);


                // Update existing Base instance Model with this data and modified name
                var newBaseInstanceModel = new BaseInstanceModel(fileNameNew);
                newBaseInstanceModel.setBaseInstanceModel(newModel);
                modifiedBaseInstanceModel.put(fileNameNew, newBaseInstanceModel);

            }
//                }
            }

        wizardContext.getDataGeneratorModel().setBaseInstanceModel(modifiedBaseInstanceModel);
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

    public void setIRenameConfigXmlFilePath(String configXmlFilePath) {
        this.configXmlFilePath = configXmlFilePath;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        var message = "";
        if (configXmlFilePath == null || configXmlFilePath.isEmpty()) {
            message += "No valid value found for input XML ID for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}

