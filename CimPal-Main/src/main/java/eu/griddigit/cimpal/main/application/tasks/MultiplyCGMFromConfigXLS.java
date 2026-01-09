package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import eu.griddigit.cimpal.main.util.ExcelTools;

import java.io.IOException;
import java.util.*;

public class MultiplyCGMFromConfigXLS implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private String inputCGMConfigXmlFilePath;
    private String inputMASCGMFilePath;
    private String tpbdID;
    private ArrayList<Object> inputXLSdataCGM;
    private TaskStateUpdater taskUpdater;
    private boolean persistentEQflag;
    private boolean usePreviousMasFile;
    private boolean saveResult;

    public MultiplyCGMFromConfigXLS() {
        this.name = "Multiply CGM from Config xls";
        this.pathToFXML = "/fxml/wizardPages/taskElements/multiplyCGMFromConfigFile.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.usePreviousMasFile = false;
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        // new instance model to replace the old one
        Map<String, BaseInstanceModel> modifiedInstanceDataMultipliedMap = new HashMap<>();
        // load input data xls - for the new dates, times
        ArrayList<Object> inputXLSdata =ExcelTools.importXLSX(inputCGMConfigXmlFilePath,0);


        taskUpdater.updateState(parent,"Executing..", "10%", wizardContext);

        // load MAS input data xls - for the IDs of the EQ, TP,...
        Map<String,String> headerInfoIGM = new HashMap<>();
        if (usePreviousMasFile) {
            inputXLSdataCGM = wizardContext.getMasForCGM();
            LinkedList<?> filenames = (LinkedList<?>)inputXLSdataCGM.getFirst();
            for (int m = 0; m < filenames.size(); m++) {
                String name = FilenameUtils.getName(((LinkedList<?>) inputXLSdataCGM.get(0)).get(m).toString());
                String[] nameParts=name.split("_",5); //indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
                if (persistentEQflag && nameParts[3].equals("EQ")){
                    headerInfoIGM.putIfAbsent("|" + nameParts[3] + "|" + ((LinkedList<?>) inputXLSdataCGM.get(2)).get(m).toString(), ((LinkedList<?>) inputXLSdataCGM.get(3)).get(m).toString());
                }
                else {
                    headerInfoIGM.putIfAbsent(nameParts[0] + "|" + nameParts[1] + "|" + nameParts[3] + "|" + ((LinkedList<?>) inputXLSdataCGM.get(2)).get(m).toString(), ((LinkedList<?>) inputXLSdataCGM.get(3)).get(m).toString());
                }
            }
        }
        else {
            inputXLSdataCGM = ExcelTools.importXLSX(inputMASCGMFilePath,0);
            for (int m = 1; m < inputXLSdataCGM.size(); m++) {

                String name = FilenameUtils.getName(((LinkedList<?>) inputXLSdataCGM.get(m)).getFirst().toString());
                String[] nameParts=name.split("_",5); //indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
                if (persistentEQflag && nameParts[3].equals("EQ")){
                    headerInfoIGM.putIfAbsent("|" + nameParts[3] + "|" + ((LinkedList<?>) inputXLSdataCGM.get(m)).get(2).toString(), ((LinkedList<?>) inputXLSdataCGM.get(m)).get(3).toString());
                }
                else {
                    headerInfoIGM.putIfAbsent(nameParts[0] + "|" + nameParts[1] + "|" + nameParts[3] + "|" + ((LinkedList<?>) inputXLSdataCGM.get(m)).get(2).toString(), ((LinkedList<?>) inputXLSdataCGM.get(m)).get(3).toString());
                }
            }
        }


        Map<String,String> headerIdMap = new HashMap<>();
        Map<String,String> headerInfoCGMSSH = new HashMap<>();
        var saveProperties = wizardContext.getDataGeneratorModel().getSaveProperties();


        var dataModel = wizardContext.getDataGeneratorModel();
        var instanceModel = dataModel.getBaseInstanceModel();

        String[] rscfilename = ((LinkedList<?>) inputXLSdata.getFirst()).get(2).toString().split("\\|");

        int totalSize = instanceModel.size() * inputXLSdata.size() * rscfilename.length;
        int iCount = 0;

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) { // Run through SSH first

            var model = entry.getValue().getBaseInstanceModel();

            //indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
            if (entry.getValue().getProfile().equals("SSH")) {
                // first do all SSH
                //get the ID of the file and MAS
                Statement fileIDhead1 = model.listStatements(null, RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
                Statement fileMAS = model.listStatements(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null).next();


//                String[] rscmas = ((LinkedList<?>) inputXLSdata.getFirst()).get(3).toString().split("\\|");

                for (String rscfilenam : rscfilename) {
                    for (int i = 1; i < inputXLSdata.size(); i++) {

                        int progress = (int) ((double) iCount / totalSize * 100);
                        iCount++;
                        taskUpdater.updateState(parent, "Processing: " + entry.getKey(), progress + "%", wizardContext);

                        String dateTimeNew = ((LinkedList<?>) inputXLSdata.get(i)).get(0).toString();
                        String businessProcessNew = ((LinkedList<?>) inputXLSdata.get(i)).get(1).toString();
                        String tso = entry.getValue().getTso();
                        String fileNameNewSSH;
                        if (tso.split("-").length >= 3){
                            String[] tsoA = tso.split("-");
                            fileNameNewSSH = dateTimeNew + "_" + businessProcessNew + "_" + rscfilenam + "-" + tsoA[1] + "-" + tsoA[2] + "_" + "SSH" + "_" + entry.getValue().getVersion();
                        }
                        else
                            fileNameNewSSH = dateTimeNew + "_" + businessProcessNew + "_" + rscfilenam + "-" + tso + "_" + "SSH" + "_" + entry.getValue().getVersion();

                        headerIdMap.putIfAbsent(fileNameNewSSH, "urn:uuid:" + UUID.randomUUID());
                        String[] tsoA = tso.split("-");
                        headerInfoCGMSSH.putIfAbsent(dateTimeNew + "|" + businessProcessNew + "_" + rscfilenam + "-" + tsoA[1] + "|SSH|CGM|" + fileMAS.getObject().toString(), headerIdMap.get(fileNameNewSSH));


                        //modify header
                        //get old header
                        Statement headerStmtIDOld = model.listStatements(null, RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();

                        List<Statement> headerStmtOld = model.listStatements(headerStmtIDOld.getSubject().asResource(), null, (RDFNode) null).toList();


                        // decide on new ID
                        String headerIDNew = headerIdMap.get(fileNameNewSSH);
                        String fileName = fileNameNewSSH;


                        //for SSH need to get the EQ reference
                        //in the xls there should be EQ with same time, mas as the SSH
                        String edIDref;
                        if (persistentEQflag) {
                            edIDref = headerInfoIGM.get("|EQ|" + fileMAS.getObject().toString());
                        } else {
                            edIDref = headerInfoIGM.get(dateTimeNew + "|" + businessProcessNew + "|EQ|" + fileMAS.getObject().toString());
                        }
                        //
                        String sshIDref = headerInfoIGM.get(dateTimeNew + "|" + businessProcessNew + "|SSH|" + fileMAS.getObject().toString());
                        //String edIDref = "";
                    /*LinkedList<String> tpIDref = new LinkedList<>();
                    for (int k = 1; k < inputXLSdataCGM.size(); k++) {
                        //0-file name; 1-profile; 2-MAS URI, 3-ID
                        String[] nameFromXLS = ((LinkedList) inputXLSdataCGM.get(k)).get(0).toString().split("_", 5); //indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
                        if (nameFromXLS[0].equals(dateTimeNew)) {
                            if (((LinkedList) inputXLSdataCGM.get(k)).get(2).toString().equals(fileMAS.getObject().toString())) {
                                if (((LinkedList) inputXLSdataCGM.get(k)).get(1).toString().equals("EQ")) {
                                    edIDref = ((LinkedList) inputXLSdataCGM.get(k)).get(3).toString();
                                } else if (((LinkedList) inputXLSdataCGM.get(k)).get(1).toString().equals("TP")) {
                                    tpIDref.add(((LinkedList) inputXLSdataCGM.get(k)).get(3).toString());
                                }


                            }
                        }

                    }*/
                        //String fileNameNewEQ = ""; //this is fake
                        //String fileNameNewTP = ""; //this is fake
                        //for SV need to get all TP, all SSH and the TPBD references
                        //in the xls there should be TP with same time, mas as the SSH. The SSH ned to be done first as these are the new SSH, the TPBD is a constant


                        //add new header
                        List<Statement> headerStmtNew = new ArrayList<>();

                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                        for (Statement stmt : headerStmtOld) {
                            if (!stmt.getPredicate().toString().equals(RDF.type.toString())) {
                                //dependencies: EQ with EQBD; TP with EQ, SSH with EQ, SV with TP, SSH and TPBD
                                if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn")) {
                                    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(edIDref)));
                                    //if (originalNameInParts[3].equals("SSH")) {
                                    //    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(edIDref)));
                                    //} else if (originalNameInParts[3].equals("SV")) {
                                    //     headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewTP))));
                                    //    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewSSH))));
                                    //     headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(tpbdID)));
                                    // }

                                } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.Supersedes")) {
                                    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.Supersedes"), (RDFNode) ResourceFactory.createProperty(sshIDref)));

                                } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime")) {
                                    //target date dateTimeNew
                                    //year dateTimeNewParts[0].substring(0,4)
                                    //month dateTimeNewParts[0].substring(4,6)
                                    //day dateTimeNewParts[0].substring(6,8)
                                    //time hours dateTimeNewParts[1].substring(0,2)
                                    //time min dateTimeNewParts[1].substring(2,4)

                                    String[] dateTimeNewParts = dateTimeNew.split("T", 2);
                                    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime"), (RDFNode)
                                            ResourceFactory.createPlainLiteral(dateTimeNewParts[0].substring(0, 4) + "-" + dateTimeNewParts[0].substring(4, 6) + "-" + dateTimeNewParts[0].substring(6, 8) + "T" +
                                                    dateTimeNewParts[1].substring(0, 2) + ":" + dateTimeNewParts[1].substring(2, 4) + ":00Z")));

                                } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description")) {
                                    try {
                                        String[] description = stmt.getObject().toString().split("/BP", 2);
                                        String descriptionnew = "<MDE><BP>" + businessProcessNew + "</BP" + description[1];
                                        String[] description2 = descriptionnew.split("/RSC", 2);
                                        String[] description3 = descriptionnew.split("/TOOL", 2);
                                        String descriptionnew1 = description3[0] + "/TOOL><RSC>" + rscfilenam + "</RSC" + description2[1];
                                        descriptionnew1 = descriptionnew1.replace("<", "&lt;").replace(">", "&gt;");
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                ResourceFactory.createPlainLiteral(descriptionnew1)));
                                    }
                                    catch (ArrayIndexOutOfBoundsException e)
                                    {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                ResourceFactory.createPlainLiteral("")));
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
                        //Update new base instance model:
                        var nameArray = fileName.split("_", 5);
                        var newBaseInstanceModel = new BaseInstanceModel(nameArray[0], nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                        newBaseInstanceModel.setBaseInstanceModel(model);
                        modifiedInstanceDataMultipliedMap.put(newBaseInstanceModel.getFileName(), newBaseInstanceModel);

                        //save xml

                        Map<String, Model> profileModelMap =  dataModel.getProfileModelMap();
                        //this is related to the save of the data
                        Set<Resource> rdfAboutList = new HashSet<>();
                        Set<Resource> rdfEnumList = new HashSet<>();
                        if ((boolean) saveProperties.get("useAboutRules")) {
                            if (profileModelMap.get(entry.getValue().getProfile()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
                                rdfAboutList = profileModelMap.get(entry.getValue().getProfile()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
                            }
                            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                        }

                        if ((boolean) saveProperties.get("useEnumRules")) {
                            for (ResIterator ii = profileModelMap.get(entry.getValue().getProfile()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
                                    ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
                                Resource resItem = ii.next();
                                for (ResIterator j = profileModelMap.get(entry.getValue().getProfile()).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
                                    Resource resItemProp = j.next();
                                    rdfEnumList.add(resItemProp);
                                }
                            }
                        }

                        if (saveProperties.containsKey("rdfAboutList")) {
                            saveProperties.replace("rdfAboutList", rdfAboutList);
                        } else {
                            saveProperties.put("rdfAboutList", rdfAboutList);
                        }
                        if (saveProperties.containsKey("rdfEnumList")) {
                            saveProperties.replace("rdfEnumList", rdfEnumList);
                        } else {
                            saveProperties.put("rdfEnumList", rdfEnumList);
                        }
                        saveProperties.replace("filename", fileName);

                        // Call individual model save to default dir
                        wizardContext.saveModel(saveProperties, model, fileName, this.name, saveResult);


                    }
                }
            }

        }

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()){ // Run for SV only
            var model = entry.getValue().getBaseInstanceModel();

            if (entry.getValue().getProfile().equals("SV")) {

                //get the ID of the file and MAS
                //Statement fileIDhead1 = model.listStatements(new SimpleSelector(null, RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"))).next();
                //Statement fileMAS = model.listStatements(new SimpleSelector(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet"), (RDFNode) null)).next();

                String[] rscmas = ((LinkedList<?>) inputXLSdata.getFirst()).get(3).toString().split("\\|");

                int m=0;
                for (String rscfilenam : rscfilename) {
                    for (int i=1; i<inputXLSdata.size(); i++) {
                        try {
                            int progress = (int) ((double) iCount / totalSize * 100);
                            iCount++;
                            taskUpdater.updateState(parent, "Processing: " + entry.getKey(), progress + "%", wizardContext);

                            String dateTimeNew = ((LinkedList<?>) inputXLSdata.get(i)).getFirst().toString();
                            String businessProcessNew = ((LinkedList<?>) inputXLSdata.get(i)).get(1).toString();

                            String fileNameNewSV;
                            String tso = entry.getValue().getTso();
                            String cgmRegion = null;
                            if (tso.split("-").length > 1)
                                cgmRegion = tso.split("-")[1];

                            if (cgmRegion != null)
                                fileNameNewSV = dateTimeNew + "_" + businessProcessNew + "_" + rscfilenam + "-" + cgmRegion + "_" + "SV" + "_" + entry.getValue().getVersion();
                            else
                                throw new Exception("Missing cgmRegion.");

                            headerIdMap.putIfAbsent(fileNameNewSV, "urn:uuid:" + UUID.randomUUID());
                            //headerInfoCGMSSH.putIfAbsent(dateTimeNew+"|"+businessProcessNew+"|SSH|CGM|"+fileMAS,headerIdMap.get(fileNameNewSSH));


                            //modify header
                            //get old header
                            Statement headerStmtIDOld = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();

                            List<Statement> headerStmtOld = model.listStatements(headerStmtIDOld.getSubject().asResource(), null, (RDFNode) null).toList();


                            // decide on new ID
                            String headerIDNew = headerIdMap.get(fileNameNewSV);
                            String fileName = fileNameNewSV;

                            //for SSH need to get the EQ reference
                            //in the xls there should be EQ with same time, mas as the SSH

                            //String tpIDref= headerInfoIGM.get(dateTimeNew+"|"+businessProcessNew+"|TP|"+fileMAS);

                            //String edIDref = "";
                            LinkedList<String> tpIDref = new LinkedList<>();

                            for (Map.Entry<String, String> entry1 : headerInfoIGM.entrySet()) {
                                if (entry1.getKey().contains(dateTimeNew + "|" + businessProcessNew + "|TP|")) {
                                    tpIDref.add(entry1.getValue());
                                }
                            }

                            LinkedList<String> sshIDref = new LinkedList<>();
                            for (Map.Entry<String, String> entry2 : headerInfoCGMSSH.entrySet()) {
                                String[] tsoA = tso.split("-");
                                if (entry2.getKey().contains(dateTimeNew + "|" + businessProcessNew + "_" + rscfilenam + "-" + tsoA[1] + "|SSH|CGM|")) {
                                    sshIDref.add(entry2.getValue());
                                }
                            }

                            //String fileNameNewEQ = ""; //this is fake
                            //String fileNameNewTP = ""; //this is fake
                            //for SV need to get all TP, all SSH and the TPBD references
                            //in the xls there should be TP with same time, mas as the SSH. The SSH need to be done first as these are the new SSH, the TPBD is a constant


                            //add new header
                            List<Statement> headerStmtNew = new ArrayList<>();

                            headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                            for (String string : tpIDref) {
                                headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(string)));
                            }
                            for (String s : sshIDref) {
                                headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(s)));
                            }

                            headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(tpbdID)));


                            for (Statement stmt : headerStmtOld) {
                                if (!stmt.getPredicate().toString().equals(RDF.type.toString())) {
                                    //dependencies: EQ with EQBD; TP with EQ, SSH with EQ, SV with TP, SSH and TPBD
                                    if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn")) {
                                        //  headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(tpIDref.get(k))));
                                        //   headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(sshIDref.get(k))));
                                        //if (originalNameInParts[3].equals("SSH")) {
                                        //    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(edIDref)));
                                        //} else if (originalNameInParts[3].equals("SV")) {
                                        //     headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewTP))));
                                        //    headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(headerIdMap.get(fileNameNewSSH))));
                                        //     headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), (RDFNode) ResourceFactory.createProperty(tpbdID)));
                                        // }

                                        //} else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.Supersedes")) {
                                        //   headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.Supersedes"), (RDFNode) ResourceFactory.createProperty(sshIDref)));

                                    } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime")) {
                                        //target date dateTimeNew
                                        //year dateTimeNewParts[0].substring(0,4)
                                        //month dateTimeNewParts[0].substring(4,6)
                                        //day dateTimeNewParts[0].substring(6,8)
                                        //time hours dateTimeNewParts[1].substring(0,2)
                                        //time min dateTimeNewParts[1].substring(2,4)

                                        String[] dateTimeNewParts = dateTimeNew.split("T", 2);
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime"), (RDFNode)
                                                ResourceFactory.createPlainLiteral(dateTimeNewParts[0].substring(0, 4) + "-" + dateTimeNewParts[0].substring(4, 6) + "-" + dateTimeNewParts[0].substring(6, 8) + "T" +
                                                        dateTimeNewParts[1].substring(0, 2) + ":" + dateTimeNewParts[1].substring(2, 4) + ":00Z")));

                                    } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description")) {
                                        try {
                                            String[] description = stmt.getObject().toString().split("/BP", 2);
                                            String descriptionnew = "<MDE><BP>" + businessProcessNew + "</BP" + description[1];
                                            String[] description2 = descriptionnew.split("/RSC", 2);
                                            String[] description3 = descriptionnew.split("/TOOL", 2);
                                            String descriptionnew1 = description3[0] + "/TOOL><RSC>" + rscfilenam + "</RSC" + description2[1];
                                            descriptionnew1 = descriptionnew1.replace("<", "&lt;").replace(">", "&gt;");
                                            headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                    ResourceFactory.createPlainLiteral(descriptionnew1)));
                                        } catch (Exception ex) {
                                            headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)
                                                    ResourceFactory.createPlainLiteral(stmt.getObject().toString())));
                                        }

                                    } else if (stmt.getPredicate().toString().equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.modelingAuthoritySet")) {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew),
                                                stmt.getPredicate(), ResourceFactory.createPlainLiteral(rscmas[m])));

                                    } else {
                                        headerStmtNew.add(ResourceFactory.createStatement(ResourceFactory.createResource(headerIDNew),
                                                stmt.getPredicate(), stmt.getObject()));
                                    }


                                    //k=k+1;
                                }


                            }

                            //delete old header
                            model.remove(headerStmtOld);
                            //add new header
                            model.add(headerStmtNew);

                            //Update new base instance model:
                            var nameArray = fileName.split("_", 5);
                            var newBaseInstanceModel = new BaseInstanceModel(nameArray[0], nameArray[1], nameArray[2], nameArray[3], nameArray[4]);
                            newBaseInstanceModel.setBaseInstanceModel(model);
                            modifiedInstanceDataMultipliedMap.put(newBaseInstanceModel.getFileName(), newBaseInstanceModel);

                        }
                        catch (Exception e){
                            break;
                        }
                    }

                    m = m+1;
                }
            }
        }

        // Replace existing model with multiplied  - TODO: since we modify subsections of model, should we mutate or replace?
        wizardContext.getDataGeneratorModel().setBaseInstanceModel(modifiedInstanceDataMultipliedMap);
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

    public void setInputCGMConfigXmlFilePath(String inputCGMConfigXmlFilePath) {
        this.inputCGMConfigXmlFilePath = inputCGMConfigXmlFilePath;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    public void setPersistentEQflag(boolean persistentEQflag) {
        this.persistentEQflag = persistentEQflag;
    }

    @Override
    public String validateInputs() {
        var message = "";
        if (!usePreviousMasFile && inputMASCGMFilePath == null) {
            message += "No valid value found for Input MAS CGM File - Either browse it or have it picked up from multiple IGM: " + name + "\n";
        }

        if (inputCGMConfigXmlFilePath == null || inputCGMConfigXmlFilePath.isEmpty() ) {
            message += "No valid value found for input CGM Config Xml File Path for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }

    public void setUsePreviousMasFile(boolean usePreviousMasFile) {
        this.usePreviousMasFile = usePreviousMasFile;
    }

    public void setInputMASCGMFilePath(String inputMASCGMFilePath) {
        this.inputMASCGMFilePath = inputMASCGMFilePath;
    }

    public void setTpbdId(String tpbdId) {
        this.tpbdID = tpbdId;
    }
}

