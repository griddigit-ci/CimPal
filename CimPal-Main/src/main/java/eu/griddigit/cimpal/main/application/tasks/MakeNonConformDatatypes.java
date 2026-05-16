package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.InstanceDataFactory;
import eu.griddigit.cimpal.main.application.datagenerator.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.*;

public class MakeNonConformDatatypes implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String[] shaclFilesPath;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public MakeNonConformDatatypes() {
        this.name = "Make Non-Conform Datatypes";
        this.pathToFXML = "/fxml/wizardPages/taskElements/makeNon-ConformDatatypes.fxml";
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
        taskUpdater.updateState(parent, "Loading Shacl Data", "11%", wizardContext);

        wizardContext.getDataGeneratorModel().loadShaclModel(this.shaclFilesPath);

        taskUpdater.updateState(parent, "Loading Done, NonConforming Dataypes", "20%", wizardContext);
        Map<String, BaseInstanceModel> instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        Map<String, ArrayList<Object>> profileDataMap = wizardContext.getDataGeneratorModel().getProfileDataMap();
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();

        //loop in the model and decide if property needs to be modified
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            List<Statement> stmtToRemove=new ArrayList<>();
            List<Statement> stmtToAdd=new ArrayList<>();

            if (!entry.getKey().contains("unionModel") && !entry.getKey().contains("modelUnionWithoutHeader")){
                Model model = entry.getValue().getBaseInstanceModel();
                ArrayList<Object> profileData = profileDataMap.get(entry.getKey());

                //get info if it is association or attribure, enum
                Map<String,String> propertyMap = new HashMap<>(); //class&property URI, attribute, association
                for (int cl = 0; cl < ((ArrayList<?>) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class
                    String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(0)).get(0).toString();

                    for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).size(); atas++) {
                        // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                            if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                                String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                                propertyMap.put(classFullURI+"propertyFullURI"+propertyFullURI,"Association");
                            }
                        } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute

                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                            String datatypeType = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(8).toString();
                            String datatypeValueString;

                            switch (datatypeType) {
                                case "Primitive": {
                                    datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                    propertyMap.put(classFullURI+"propertyFullURI"+propertyFullURI,"Attribute#Primitive@"+datatypeValueString);
                                    break;
                                }
                                case "CIMDatatype": {
                                    datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                    propertyMap.put(classFullURI+"propertyFullURI"+propertyFullURI,"Attribute#CIMDatatype@"+datatypeValueString);
                                    break;
                                }
                                case "Compound": {
                                    datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                    propertyMap.put(classFullURI+"propertyFullURI"+propertyFullURI,"Attribute#Compound@"+datatypeValueString);
                                    break;
                                }
                                case "Enumeration":
                                    propertyMap.put(classFullURI+"propertyFullURI"+propertyFullURI,"Attribute#Enumeration@"+xmlBase+"#qocdcTest.data");
                                    break;
                            }
                        }
                    }
                }

                //create one dummy CIM class to ise for associations
                Resource fakeClassRes = ResourceFactory.createResource(xmlBase+"#_"+ UUID.randomUUID());
                RDFNode rdfType = ResourceFactory.createResource(xmlBase+"#"+"FakeCIMclass");
                Statement stmtFakeClass = ResourceFactory.createStatement(fakeClassRes, RDF.type,rdfType);
                stmtToAdd.add(stmtFakeClass);

                //look at the instance data
                for (StmtIterator stmt= model.listStatements(); stmt.hasNext();) {
                    Statement stmtItem= stmt.next();
                    Resource subject=stmtItem.getSubject();
                    Property predicate=stmtItem.getPredicate();

                    // check if it is attribute association or enum
                    String propertyKey=model.getRequiredProperty(subject,RDF.type).getObject().toString()+"propertyFullURI"+predicate.toString();
                    if(propertyMap.containsKey(propertyKey)) {
                        String info = propertyMap.get(propertyKey);
                        String datatypeType=null;
                        if (info.contains("Association")) { //it is association
                            stmtToRemove.add(stmtItem);
                            stmtToAdd.add(ResourceFactory.createStatement(subject, predicate,ResourceFactory.createProperty(fakeClassRes.toString())));
                        } else if (info.contains("Enumeration")) {//it is enumeration
                            Object datatypeValuesEnum = info.split("@",2)[1];
                            String datatypeValueString = null;
                            datatypeType = "Enumeration";
                            stmtToRemove.add(stmtItem);

                            Map<String,Object> shaclContraintResult=null;
                            Property fullClassPropertyKey = ResourceFactory.createProperty(model.getRequiredProperty(subject,RDF.type).getObject().toString()+"propertyFullURI"+predicate.toString());
                            if ( wizardContext.getDataGeneratorModel().getShaclConstraints().containsKey(fullClassPropertyKey)) { //the attribute has some constraints defined
                                shaclContraintResult = InstanceDataFactory.getShaclConstraint(true, fullClassPropertyKey);
                            }
                            Property property = predicate;
                            Resource classRes = subject;
                            Statement stmtAdd = ModelManipulationFactory.createBadValue(false, datatypeType, datatypeValueString, datatypeValuesEnum, null, shaclContraintResult, property, classRes);
                            stmtToAdd.add(stmtAdd);
                        } else if (info.contains("Primitive") || info.contains("CIMDatatype")) {//it is attribute
                            String datatypeValueString;
                            stmtToRemove.add(stmtItem);
                            Map<String,Object> shaclContraintResult=null;
                            Property fullClassPropertyKey = ResourceFactory.createProperty(model.getRequiredProperty(subject,RDF.type).getObject().toString()+"propertyFullURI"+predicate.toString());
                            if (wizardContext.getDataGeneratorModel().getShaclConstraints().containsKey(fullClassPropertyKey)) { //the attribute has some constraints defined
                                shaclContraintResult = InstanceDataFactory.getShaclConstraint(true, fullClassPropertyKey);
                            }
                            if (info.contains("Primitive")) {
                                datatypeType = "Primitive";
                            }else if (info.contains("CIMDatatype")){
                                datatypeType = "CIMDatatype";
                            }
                            datatypeValueString=info.split("@",2)[1];

                            Property property = predicate;
                            Resource classRes = subject;
                            Statement stmtAdd = ModelManipulationFactory.createBadValue(false, datatypeType,datatypeValueString,null,null,shaclContraintResult,property,classRes);
                            stmtToAdd.add(stmtAdd);
                        }
                    }
                }
                model.remove(stmtToRemove);
                model.add(stmtToAdd);
                entry.getValue().setBaseInstanceModel(model);
                //GuiHelper.appendTextToOutputWindow("Model part "+entry.getKey()+" has "+ stmtToRemove.size() +" properties removed and "+stmtToAdd.size()+" properties added.", true);
            }
        }

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);

        taskUpdater.updateState(parent, "Completed", "100%", wizardContext);
    }

    public void setShaclFilesPath(String[] shaclFilesPath) {
        this.shaclFilesPath = shaclFilesPath;
    }

    public String[] getShaclFilesPath() {
        return this.shaclFilesPath;
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
        if (shaclFilesPath == null || shaclFilesPath.length == 0) {
            return "No SHACL files selected for task: " + name + "\n";
        }

        return "";
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}
