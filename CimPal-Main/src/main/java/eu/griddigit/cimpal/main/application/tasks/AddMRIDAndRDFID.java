package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.DataGeneratorModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.IOException;
import java.util.*;

public class AddMRIDAndRDFID implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public AddMRIDAndRDFID() {
        this.name = "Replaces mRID with rdf:ID";
        this.pathToFXML = "/fxml/wizardPages/taskElements/replaceMRIDWithRDFID.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent, "Executing..", "11%", wizardContext);
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();


        DataGeneratorModel dataModel = wizardContext.getDataGeneratorModel();
        Map<String, BaseInstanceModel> instanceModel = dataModel.getBaseInstanceModel();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {

            Model model = entry.getValue().getBaseInstanceModel();
            // replace mRID with rdf:ID
            List<Statement> removeStmtList = new LinkedList<>();
            List<Statement> addStmtList = new LinkedList<>();
            for (StmtIterator stmt = model.listStatements(null, ResourceFactory.createProperty(xmlBase + "#IdentifiedObject.mRID"), (RDFNode) null); stmt.hasNext(); ) {
                Statement stmtItem = stmt.next();
                Resource subject = stmtItem.getSubject();
                Property predicate = stmtItem.getPredicate();
                removeStmtList.add(stmtItem);
                String newMRID = subject.getLocalName().substring(1);
                addStmtList.add(ResourceFactory.createStatement(subject, predicate, ResourceFactory.createPlainLiteral(newMRID)));
            }

            Map<String,Model> profileModelMap = dataModel.getProfileModelMap();
            Map<String,Model> profileDataMapAsModel = dataModel.getProfileDataMapAsModel();
            //this is related to the save of the data
            Set<Resource> rdfAboutList = new HashSet<>();


            /*if (profileModelMap.get(originalNameInParts[3]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
                rdfAboutList = profileModelMap.get(originalNameInParts[3]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
            }*/
            //rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));


            for (StmtIterator stmt = model.listStatements(null, RDF.type, (RDFNode) null); stmt.hasNext();) {
                Statement stmtItem = stmt.next();
                Resource subject = stmtItem.getSubject();
                Property predicate = stmtItem.getPredicate();
                RDFNode object = stmtItem.getObject();

                //check if the object is in the rdfAboutList, if yes look at EQ profile and see if it has mrid if yes then add it to instance data
                if (rdfAboutList.contains(object.asResource())) {
                    if (profileDataMapAsModel.get("EQ").contains(ResourceFactory.createResource(xmlBase+"#IdentifiedObject.mRID"), RDFS.domain,object)){
                        if (!model.contains(subject,ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.mRID"))){
                            String newMRID = subject.getLocalName().substring(1);
                            addStmtList.add(ResourceFactory.createStatement(subject, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.mRID"), ResourceFactory.createPlainLiteral(newMRID)));
                        }
                    }
                } else {
                    //if the object is not in rdfAboutList, check the current profile and see if has mrid, if yes add it
                    if (profileDataMapAsModel.containsKey(entry.getKey()) && profileDataMapAsModel.get(entry.getKey()).contains(ResourceFactory.createResource(xmlBase+"#IdentifiedObject.mRID"), RDFS.domain,object)){
                        if (!model.contains(subject,ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.mRID"))){
                            String newMRID = subject.getLocalName().substring(1);
                            addStmtList.add(ResourceFactory.createStatement(subject, ResourceFactory.createProperty(xmlBase+"#IdentifiedObject.mRID"), ResourceFactory.createPlainLiteral(newMRID)));
                        }
                    }
                }

                //from the profileDataMapAsModel
                //[http://iec.ch/TC57/CIM100#IdentifiedObject.mRID, http://www.w3.org/2000/01/rdf-schema#domain, http://iec.ch/TC57/CIM100#DCTopologicalNode]

            }

            model.remove(removeStmtList);
            model.add(addStmtList);
            entry.getValue().setBaseInstanceModel(model);
        }


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

