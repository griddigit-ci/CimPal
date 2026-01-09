package eu.griddigit.cimpal.main.application.datagenerator;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class DataGeneratorFactory {

    public static int countOfRequiredAttributes;
    public static int countOfOptionalAttributes;
    public static int countOfRequiredAssociations;
    public static int countOfOptionalAssociations;
    public static int countOfClasses;
    public static Resource resDummyClass;
    public static Resource classResource;
    public static Resource classResourceTerminal;

    public static List<RDFNode> supportedProperties;
    public static Map<Property, Map> shaclConstraints;

    private static Map<String, Integer> terminalMap;
    private static Map<String, Integer> terminalDCMap;
    private static Map<String,Integer> skipClassMap;
    private static Map<String,List<String>> headerDependencyMap;
    private static ArrayList<Object> skippedClassPropertyProcessed;
    private static ArrayList<Object> addedClassPropertyProcessed;

    public static Map<Integer,Resource> connectivityNodeMap;
    public static Map<Integer,Resource> topologicalNodeMap;
    public static List<String> shuntCompensatorList;
    public static List<String> tapChangerList;
    public static List<String> conductingEquipmentList;




    public static void instanceDataGenerator(String xmlBase, String profileFormat,
                                             Boolean profileModelUnionFlag, Boolean instanceModelUnionFlag, Boolean shaclModelUnionFlag, Integer cardinalityFlag, Boolean conform, Map<String, Object> saveProperties,
                                             Map<String, Boolean> inputData) throws IOException {

        WizardContext wizardContext = WizardContext.getInstance();

        countOfRequiredAttributes = 0;
        countOfOptionalAttributes = 0;
        countOfRequiredAssociations = 0;
        countOfOptionalAssociations = 0;
        countOfClasses = 0;

        supportedProperties = ConstraintsFactory.supportedPropertiesInit();

        Lang rdfProfileFormat = null;

        ArrayList<Object> profileData = null;
        Model shaclModel = null;
        BaseInstanceModel baseInstanceModel = null;

        Map<String, ArrayList<Object>> profileDataMap = new HashMap<>();
        Map<String, Model> profileDataMapAsModel = new HashMap<>();

        // load all profile models
        Map<String, Model> profileModelMap = new HashMap<>();
        if (inputData.get("rdfs")) {
            profileModelMap = wizardContext.getDataGeneratorModel().getProfileModelMap();


            String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
            String rdfNs = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();

            //make the profile data per profile

            for (Map.Entry<String, Model> entry : profileModelMap.entrySet()) {
                profileData = ProfileDataFactory.constructShapeData(entry.getValue(), rdfNs, concreteNs);
                profileDataMap.put(entry.getKey(), profileData);
                profileDataMapAsModel.put(entry.getKey(), ProfileDataFactory.profileDataMapAsModelTemp);
            }
                /*if (!entry.getKey().equals("FH")) {
                    Model profileModelTemp = entry.getValue();
                    if (profileModelMap.containsKey("FH")){
                        Model profileModelFHTemp=profileModelMap.get("FH");
                        Map prefixMapFH = profileModelFHTemp.getNsPrefixMap();
                        profileModelTemp.add(profileModelFHTemp);
                        profileModelTemp.setNsPrefixes(prefixMapFH);
                    }
                    profileData = ProfileDataFactory.constructShapeData(profileModelTemp, rdfNs, concreteNs);
                    profileDataMap.put(entry.getKey(),profileData);
                }*/

        }

        // load all shacl models
//        if (inputData.get("shacl") && profileData != null) {
//            Map<String, Model> shaclModelMap = wizardContext.getDataGeneratorModel().loadShaclModel();
//            shaclModel = shaclModelMap.get("shacl");
//            Map prefixMapShacl = shaclModel.getNsPrefixMap();
//
//            //look at the shacl files and prepare a structure that contains all relevant constraints
//            //at the moment the implementation is that when shacl files are selected also RDFS files need to be selected, but this can be split in the future
//            //to make it more flexible
//
//            shaclConstraints = ConstraintsFactory.getConstraints(shaclModel, profileDataMap.get("unionModel"));
//        }


        Map<String, BaseInstanceModel> baseInstanceModelMap = new HashMap<>();
        // load all base instance models
        if (inputData.get("baseModel")) {
            baseInstanceModelMap = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
            baseInstanceModel = baseInstanceModelMap.get("unionModel");
            Map<String, String> prefixMapBaseInstanceModels = baseInstanceModel.getBaseInstanceModel().getNsPrefixMap();
        }

        //create the terminal map
        createTerminalMap();
        //create skip class map
        createSkipClassMap();
        //create the map of classes or properties that are skipped
        createSkippedClassPropertyProcessedMap();

        //create the map of classes or properties that are added
        createAddedClassPropertyProcessedMap();
        //header dependency map
        createHeaderDependencyMap(profileFormat);

        //create the interitance map for the SV
        createInheritanceClassLists();

        Map<String, Model> instanceDataModelMap = new HashMap<>();

        if (profileData != null) {
           /* //add a dummy class that is used in case of non-conform for the associations

            //order the map
            List<String> orderedList= new ArrayList<>();
            orderedList.add("unionModel");
            orderedList.add("EQBD");
            orderedList.add("TPBD");
            orderedList.add("EQ");
            orderedList.add("OP");
            orderedList.add("SC");
            orderedList.add("SSH");
            orderedList.add("TP");
            orderedList.add("SV");
            orderedList.add("GL");
            orderedList.add("DL");
            orderedList.add("DY");
            Map<String,ArrayList<Object>> orderedProfileDataMap= orderMap(profileDataMap, orderedList);*/

            for (Map.Entry<String, ArrayList<Object>> entry : profileDataMap.entrySet()) {
                if (entry.getKey().equals("modelUnionWithoutHeader")) {
                    if (inputData.get("baseModel")) {
                        if (baseInstanceModel != null) {
                            instanceDataModelMap.put(baseInstanceModel.getFileName(),baseInstanceModel.getBaseInstanceModel());
                        }
                    }
                    createInstanceData(instanceDataModelMap, profileModelMap, entry, conform, xmlBase, shaclModel, inputData, cardinalityFlag);
                    //add additional SV classes
                    addSVClasses(instanceDataModelMap, profileModelMap, entry, conform, xmlBase, shaclModel, inputData, cardinalityFlag);
                }
            }
        }

        //Print statistics
//        GuiHelper.appendTextToOutputWindow("[Info] Required attributes added: " + countOfRequiredAttributes, true);
//        GuiHelper.appendTextToOutputWindow("[Info] Optional attributes added: " + countOfOptionalAttributes, true);
//        GuiHelper.appendTextToOutputWindow("[Info] Required associations added: " + countOfRequiredAssociations, true);
//        GuiHelper.appendTextToOutputWindow("[Info] Optional associations added: " + countOfOptionalAssociations, true);
//        GuiHelper.appendTextToOutputWindow("[Info] Classes added: " + countOfClasses, true);


        //split the model and prepare for serialisation
        instanceDataModelMap = modelSplitPerProfile(instanceDataModelMap.get("modelUnionWithoutHeader"), profileDataMapAsModel);

        //add header for each profile/instance file
        addHeader(instanceDataModelMap, profileDataMap, shaclModel, xmlBase, cardinalityFlag, conform, null, inputData);

        //fix header dependencies
        Boolean deleteSupercede = true;
        fixHeaderDependency(instanceDataModelMap, profileDataMap, shaclModel, xmlBase, cardinalityFlag, conform, null, inputData, deleteSupercede);


        for (Map.Entry<String, Model> entry : instanceDataModelMap.entrySet()) {

            //this is related to the save of the data
            Set<Resource> rdfAboutList = null;
            Set<Resource> rdfEnumList = new HashSet<>();
            if ((boolean) saveProperties.get("useAboutRules")) {
                //load RDF model
            /*FileChooser filechooser = new FileChooser();
            filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("RDF file to convert", "*.rdf", "*.ttl"));
            filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
            File file = filechooser.showOpenDialog(null);
            profileModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file.toString());
            RDFDataMgr.read(profileModel, inputStream, xmlBase, Lang.RDFXML);*/

                rdfAboutList = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
                rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));

            }

            if ((boolean) saveProperties.get("useEnumRules")) {
                for (ResIterator i = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
                        ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); i.hasNext(); ) {
                    Resource resItem = i.next();
                    for (ResIterator j = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
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

            saveProperties.replace("filename", entry.getKey());
            // saveInstanceData(entry.getValue(), saveProperties); Todo saving in the new way
        }


        //ExportFactory.exportSkippedClassProperty(skippedClassPropertyProcessed, "Skipped items", "ExcludedItems", "Save excel with excluded (skipped) items...");
        //ExportFactory.exportSkippedClassProperty(addedClassPropertyProcessed, "Added items", "AddedItems", "Save excel with added items...");
    }

    private static void createTerminalMap() {


        terminalMap = new HashMap<>();
        terminalDCMap = new HashMap<>();
        //AC
        terminalMap.put("ACLineSegment", 2);
        terminalMap.put("Switch", 2);
        terminalMap.put("Disconnector", 2);
        terminalMap.put("Fuse", 2);
        terminalMap.put("GroundDisconnector", 2);
        terminalMap.put("Jumper", 2);
        terminalMap.put("Breaker", 2);
        terminalMap.put("LoadBreakSwitch", 2);
        terminalMap.put("DisconnectingCircuitBreaker", 2);
        terminalMap.put("Cut", 2);
        terminalMap.put("SeriesCompensator", 2);
        terminalMap.put("EquivalentBranch", 2);
        terminalMap.put("PowerTransformer", 2);

        terminalMap.put("Ground", 1);
        terminalMap.put("EnergySource", 1);
        terminalMap.put("EnergyConsumer", 1);
        terminalMap.put("ConformLoad", 1);
        terminalMap.put("NonConformLoad", 1);
        terminalMap.put("StationSupply", 1);
        terminalMap.put("PowerElectronicsConnection", 1);
        terminalMap.put("ExternalNetworkInjection", 1);
        terminalMap.put("LinearShuntCompensator", 1);
        terminalMap.put("NonlinearShuntCompensator", 1);
        terminalMap.put("StaticVarCompensator", 1);
        terminalMap.put("SynchronousMachine", 1);
        terminalMap.put("AsynchronousMachine", 1);
        terminalMap.put("EquivalentInjection", 1);
        terminalMap.put("EquivalentShunt", 1);
        terminalMap.put("Junction", 1);
        terminalMap.put("Clamp", 1);
        terminalMap.put("GroundingImpedance", 1);
        terminalMap.put("PetersenCoil", 1);
        terminalMap.put("VsConverter", 1);
        terminalMap.put("CsConverter", 1);
        terminalMap.put("BusbarSection", 1);

        //DC

        terminalDCMap.put("DCLineSegment", 2);
        terminalDCMap.put("DCSeriesDevice", 2);
        terminalDCMap.put("DCSwitch", 2);
        terminalDCMap.put("DCDisconnector", 2);
        terminalDCMap.put("DCBreaker", 2);
        terminalDCMap.put("DCChopper", 2);

        terminalDCMap.put("DCGround", 1);
        terminalDCMap.put("DCBusbar", 1);
        terminalDCMap.put("DCShunt", 1);


        //2 Terminal equipment
        //cim:ACLineSegment, cim:DCLineSegment , cim:DCSeriesDevice , cim:DCSwitch , cim:DCDisconnector , cim:DCBreaker , cim:DCChopper ,
        // cim:Switch, cim:Disconnector, cim:Fuse, cim:GroundDisconnector, cim:Jumper, cim:Breaker, cim:LoadBreakSwitch,
        // cim:DisconnectingCircuitBreaker, cim:Cut, cim:SeriesCompensator,cim:EquivalentBranch

        //2 DC terminals
        //cim:VsConverter, cim:CsConverter

        //2 or 3 Terminals
        //cim:PowerTransformer

        //1 Terminal equipment
        //cim:DCGround , cim:DCBusbar , cim:DCShunt , cim:Ground , cim:EnergySource , cim:EnergyConsumer,
        // cim:ConformLoad, cim:NonConformLoad, cim:StationSupply, cim:PowerElectronicsConnection , cim:ExternalNetworkInjection,
        // cim:LinearShuntCompensator, cim:NonlinearShuntCompensator , cim:StaticVarCompensator,cim:SynchronousMachine,
        // cim:AsynchronousMachine,cim:EquivalentInjection, cim:EquivalentShunt , cim:Junction
        //cim:Clamp
        //cim:GroundingImpedance, cim:PetersenCoil
    }

    private static void createSkipClassMap() {


        skipClassMap = new HashMap<>();
        skipClassMap.put("Terminal", 1);
        skipClassMap.put("DifferenceModel", 1);


    }

    private static void createSkippedClassPropertyProcessedMap() {

        skippedClassPropertyProcessed = new ArrayList<>();
        LinkedList<String> classObject = new LinkedList<>();
        LinkedList<String> property = new LinkedList<>();
        LinkedList<String> type = new LinkedList<>();
        skippedClassPropertyProcessed.add(classObject);
        skippedClassPropertyProcessed.add(property);
        skippedClassPropertyProcessed.add(type);
    }

    //create skip classes map
    private static void createAddedClassPropertyProcessedMap() {

        addedClassPropertyProcessed = new ArrayList<>();
        LinkedList<String> classObject = new LinkedList<>();
        LinkedList<String> property = new LinkedList<>();
        LinkedList<String> type = new LinkedList<>();
        addedClassPropertyProcessed.add(classObject);
        addedClassPropertyProcessed.add(property);
        addedClassPropertyProcessed.add(type);
    }

    //populate header dependency map
    private static void createHeaderDependencyMap(String profileFormat) {
        headerDependencyMap = new HashMap<>();
        if (profileFormat.equals("IEC TS 61970-600-1&2 (CGMES 2.4.15)") || profileFormat.equals("IEC 61970-45x (CIM16)")) {

            headerDependencyMap.put("EQBD", Collections.singletonList(""));
            headerDependencyMap.put("TPBD", Collections.singletonList("EQBD"));
            headerDependencyMap.put("EQ", Collections.singletonList("EQBD"));
            headerDependencyMap.put("TP", Collections.singletonList("EQ"));
            headerDependencyMap.put("SSH", Collections.singletonList("EQ"));
            headerDependencyMap.put("SV", Arrays.asList("TPBD", "SSH", "TP"));
        } else { //TODO fix this for CGMES v3
            headerDependencyMap.put("EQBD", Collections.singletonList(""));
            headerDependencyMap.put("EQ", Collections.singletonList("EQBD"));
            headerDependencyMap.put("TP", Collections.singletonList("EQ"));
            headerDependencyMap.put("SSH", Collections.singletonList("EQ"));
            headerDependencyMap.put("SV", Arrays.asList("TPBD", "SSH", "TP"));
            headerDependencyMap.put("DY", Collections.singletonList("EQ"));
            headerDependencyMap.put("DL", Arrays.asList("EQBD", "EQ"));
            headerDependencyMap.put("GL", Arrays.asList("EQBD", "EQ"));

        }


    }

    //populate inheritanceMap
    private static void createInheritanceClassLists() {

        shuntCompensatorList = new ArrayList<>();
        tapChangerList = new ArrayList<>();
        conductingEquipmentList = new ArrayList<>();

        shuntCompensatorList.add("NonlinearShuntCompensator");
        shuntCompensatorList.add("LinearShuntCompensator");

        tapChangerList.add("PhaseTapChangerLinear");
        tapChangerList.add("PhaseTapChangerSymmetrical");
        tapChangerList.add("PhaseTapChangerAsymmetrical");
        tapChangerList.add("PhaseTapChangerTabular");
        tapChangerList.add("RatioTapChanger");

        conductingEquipmentList.add("StationSupply");
        conductingEquipmentList.add("Junction");
        conductingEquipmentList.add("EquivalentBranch");
        conductingEquipmentList.add("StaticVarCompensator");
        conductingEquipmentList.add("PowerTransformer");
        conductingEquipmentList.add("Switch");
        conductingEquipmentList.add("ExternalNetworkInjection");
        conductingEquipmentList.add("VsConverter");
        conductingEquipmentList.add("CsConverter");
        conductingEquipmentList.add("AsynchronousMachine");
        conductingEquipmentList.add("BusbarSection");
        conductingEquipmentList.add("EquivalentShunt");
        conductingEquipmentList.add("Ground");
        conductingEquipmentList.add("ACLineSegment");
        conductingEquipmentList.add("LoadBreakSwitch");
        conductingEquipmentList.add("GroundingImpedance");
        conductingEquipmentList.add("Breaker");
        conductingEquipmentList.add("SeriesCompensator");
        conductingEquipmentList.add("PetersenCoil");
        conductingEquipmentList.add("LinearShuntCompensator");
        conductingEquipmentList.add("Disconnector");
        conductingEquipmentList.add("NonlinearShuntCompensator");
        conductingEquipmentList.add("NonConformLoad");
        conductingEquipmentList.add("GroundDisconnector");
        conductingEquipmentList.add("ConformLoad");
        conductingEquipmentList.add("EquivalentInjection");
        conductingEquipmentList.add("EnergyConsumer");
        conductingEquipmentList.add("SynchronousMachine");

    }

    //instance data create per profile
    private static Map<String, Model> createInstanceData(Map<String, Model> instanceDataModelMap, Map<String, Model> profileModelMap,
                                                         Map.Entry<String, ArrayList<Object>> entry, Boolean conform, String xmlBase, Model shaclModel,
                                                         Map<String, Boolean> inputData, Integer cardinalityFlag) {

        connectivityNodeMap = new HashMap<>();
        topologicalNodeMap = new HashMap<>();
        connectivityNodeMap.put(1, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));
        connectivityNodeMap.put(2, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));
        connectivityNodeMap.put(3, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));

        topologicalNodeMap.put(1, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));
        topologicalNodeMap.put(2, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));
        topologicalNodeMap.put(3, ResourceFactory.createResource(xmlBase + "#" + UUID.randomUUID()));
        Model profileModel = profileModelMap.get(entry.getKey());
        Map<String, String> prefixMap = profileModel.getNsPrefixMap();
        //instantiate the instance data model
        Model instanceDataModel = ModelFactory.createDefaultModel();
        instanceDataModel.setNsPrefixes(prefixMap);
        if (!conform) {
            addDummyClass(entry.getValue(), instanceDataModel, xmlBase, xmlBase + "#DummyClass", cardinalityFlag, inputData);
        }

        //add 3 ConnectivityNode-s and TopologicalNode-s
        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "ConnectivityNode", inputData);
        connectivityNodeMap.replace(1, classResource);
        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "TopologicalNode", inputData);
        topologicalNodeMap.replace(1, classResource);

        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "ConnectivityNode", inputData);
        connectivityNodeMap.replace(2, classResource);
        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "TopologicalNode", inputData);
        topologicalNodeMap.replace(2, classResource);

        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "ConnectivityNode", inputData);
        connectivityNodeMap.replace(3, classResource);
        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "TopologicalNode", inputData);
        topologicalNodeMap.replace(3, classResource);

        instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, null, inputData);

        //fix the association Terminal.ConnectivityNode i.e connect the Terminals to the ConnectivityNode-s
        List<Statement> TerminalCNList = instanceDataModel.listStatements(null, ResourceFactory.createProperty(instanceDataModel.getNsPrefixURI("cim"), "Terminal.ConnectivityNode"), (RDFNode) null).toList();
        List<Statement> stmtDel = new ArrayList<>();
        List<Statement> stmtAdd = new ArrayList<>();
        for (Statement stmt : TerminalCNList) {
            int seqnum = instanceDataModel.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(instanceDataModel.getNsPrefixURI("cim"), "ACDCTerminal.sequenceNumber")).getInt();
            if (seqnum == 1) {
                stmtDel.add(stmt);
                stmtAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), connectivityNodeMap.get(1)));
            } else if (seqnum == 2) {
                stmtDel.add(stmt);
                stmtAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), connectivityNodeMap.get(2)));
            } else if (seqnum == 3) {
                stmtDel.add(stmt);
                stmtAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), connectivityNodeMap.get(3)));
            }
        }

        //fix the association Terminal.TopologicalNode i.e connect the Terminals to the TopologicalNode-s
        List<Statement> TerminalTNList = instanceDataModel.listStatements(null, ResourceFactory.createProperty(instanceDataModel.getNsPrefixURI("cim"), "Terminal.TopologicalNode"), (RDFNode) null).toList();
        List<Statement> stmtTNDel = new ArrayList<>();
        List<Statement> stmtTNAdd = new ArrayList<>();
        for (Statement stmt : TerminalTNList) {
            int seqnum = instanceDataModel.getRequiredProperty(stmt.getSubject(), ResourceFactory.createProperty(instanceDataModel.getNsPrefixURI("cim"), "ACDCTerminal.sequenceNumber")).getInt();
            if (seqnum == 1) {
                stmtTNDel.add(stmt);
                stmtTNAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), topologicalNodeMap.get(1)));
            } else if (seqnum == 2) {
                stmtTNDel.add(stmt);
                stmtTNAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), topologicalNodeMap.get(2)));
            } else if (seqnum == 3) {
                stmtTNDel.add(stmt);
                stmtTNAdd.add(ResourceFactory.createStatement(stmt.getSubject(), stmt.getPredicate(), topologicalNodeMap.get(3)));
            }
        }
        instanceDataModel.remove(stmtDel);
        instanceDataModel.remove(stmtTNDel);
        instanceDataModel.add(stmtAdd);
        instanceDataModel.add(stmtTNAdd);


        instanceDataModelMap.put(entry.getKey(), instanceDataModel);

        return instanceDataModelMap;
    }

    private static Map<String, Model> addSVClasses(Map<String, Model> instanceDataModelMap, Map<String, Model> profileModelMap,
                                                   Map.Entry<String, ArrayList<Object>> entry, Boolean conform, String xmlBase, Model shaclModel,
                                                   Map<String, Boolean> inputData, Integer cardinalityFlag) {

        Model instanceDataModel = instanceDataModelMap.get(entry.getKey());

        //loop on the instanceDataModel
        List<Resource> listToChange = new ArrayList<>();
        for (StmtIterator stmt = instanceDataModel.listStatements(null, RDF.type, ResourceFactory.createProperty(xmlBase + "#", "Terminal")); stmt.hasNext(); ) {
            Statement stmtItem = stmt.next();
            //check for Terminal; check if it has SvPowerFlow; if not add
            if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                listToChange.add(stmtItem.getSubject());
            }
        }
        for (Resource res : listToChange) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvPowerFlow", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal"), ResourceFactory.createProperty(res.toString()));
        }
        //check for TopologicalNode; check if has SvVoltage if not add
        //check for TopologicalNode; check if has SvInjection if not add
        listToChange = new ArrayList<>();
        List<Resource> listToChange2 = new ArrayList<>();
        for (StmtIterator stmt = instanceDataModel.listStatements(null, RDF.type, ResourceFactory.createProperty(xmlBase + "#", "TopologicalNode")); stmt.hasNext(); ) {
            Statement stmtItem = stmt.next();
            if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                listToChange.add(stmtItem.getSubject());
            }
            if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                listToChange2.add(stmtItem.getSubject());
            }
        }
        for (Resource res : listToChange) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvVoltage", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode"), ResourceFactory.createProperty(res.toString()));
        }
        for (Resource res : listToChange2) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvInjection", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode"), ResourceFactory.createProperty(res.toString()));
        }


        listToChange = new ArrayList<>();
        listToChange2 = new ArrayList<>();
        List<Resource> listToChange3 = new ArrayList<>();
        for (StmtIterator stmt = instanceDataModel.listStatements(null, RDF.type, (RDFNode) null); stmt.hasNext(); ) {
            Statement stmtItem = stmt.next();
            //check for ShuntCompensator; check if has SvShuntCompensatorSections if not add
            if (shuntCompensatorList.contains(stmtItem.getObject().asResource().getLocalName())) {
                if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                    listToChange.add(stmtItem.getSubject());
                }
            }
            //check for TapChanger; check if has SvTapStep if not add
            if (tapChangerList.contains(stmtItem.getObject().asResource().getLocalName())) {
                if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                    listToChange2.add(stmtItem.getSubject());
                }
            }
            //check for ConductingEquipment; check if has SvStatus if not add
            if (conductingEquipmentList.contains(stmtItem.getObject().asResource().getLocalName())) {
                if (!instanceDataModel.listStatements(null, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment"), ResourceFactory.createProperty(stmtItem.getSubject().toString())).hasNext()) {
                    listToChange3.add(stmtItem.getSubject());
                }
            }
        }

        for (Resource res : listToChange) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvShuntCompensatorSections", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator"), ResourceFactory.createProperty(res.toString()));
        }
        for (Resource res : listToChange2) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvTapStep", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger"), ResourceFactory.createProperty(res.toString()));
        }
        for (Resource res : listToChange3) {
            instanceDataModel = processAndAddSingle(entry.getValue(), instanceDataModel, shaclModel, xmlBase, cardinalityFlag, conform, "SvStatus", inputData);
            if (instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment"), (RDFNode) null).hasNext()) {
                instanceDataModel.remove(instanceDataModel.listStatements(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment"), (RDFNode) null).next());
            }
            instanceDataModel.add(classResource, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment"), ResourceFactory.createProperty(res.toString()));
        }


        instanceDataModelMap.replace(entry.getKey(), instanceDataModel);

        return instanceDataModelMap;
    }

    //splits the model per profiles
    private static Map<String,Model> modelSplitPerProfile(Model instanceDataModelFull, Map<String,Model> profileDataMapAsModel) {

        Map<String, Model> splitInstanceModelMap = new HashMap<>();
        Map<String, String> prefixMap = instanceDataModelFull.getNsPrefixMap();
        for (Map.Entry<String, Model> entry : profileDataMapAsModel.entrySet()){
            if (!entry.getKey().equals("unionModel")  && !entry.getKey().equals("FH")) {//&& !entry.getKey().equals("modelUnionWithoutHeader")
                Model model = ModelFactory.createDefaultModel();
                model.setNsPrefixes(prefixMap);
                Model profileModel = entry.getValue();
                //for (ResIterator res = profileModel.listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")); res.hasNext(); ) {
                for (ResIterator res = profileModel.listSubjectsWithProperty(RDF.type, RDFS.Class); res.hasNext(); ) {
                    Resource resItem = res.next();
                    List<Statement> profileProperties;
                    //if(resItem.getLocalName().equals("FullModel") || resItem.getLocalName().equals("DifferenceModel")){
                    //    profileProperties=profileModel.listStatements(resItem,(Property) null, (RDFNode) null).toList();
                    // }else {
                    profileProperties = profileModel.listStatements((Resource) null, RDFS.domain, resItem).toList();//the subject of the attributes of the class
                    // }
                    List<Statement> classes = instanceDataModelFull.listStatements(null, RDF.type, resItem).toList();
                    model.add(classes);
                    for (Statement stmt : classes) {
                        for (Statement resProperty : profileProperties) {
                            List<Statement> properties = instanceDataModelFull.listStatements(stmt.getSubject(), ResourceFactory.createProperty(resProperty.getSubject().toString()), (RDFNode) null).toList();
                            model.add(properties);
                        }
                    }
                }
                splitInstanceModelMap.put(entry.getKey(), model);
            }
        }

        return splitInstanceModelMap;
    }

    //add header in the profile
    private static Map<String,Model> addHeader(Map<String, Model> splitInstanceModelMap, Map<String, ArrayList<Object>>  profileDataMap,
                                               Model shaclModel, String xmlBase, Integer cardinalityFlag, Boolean conform, String desiredClass,
                                               Map<String, Boolean> inputData) {

        Model instanceDataModel = null;
        for (Map.Entry<String, Model> entry : splitInstanceModelMap.entrySet()) {
            //instanceDataModel=entry.getValue();

            if (!entry.getKey().equals("FH")) {
                ArrayList<Object> profileDataHeader = profileDataMap.get("FH");
                instanceDataModel = processAndAddSingle(profileDataHeader, entry.getValue(), shaclModel, xmlBase, cardinalityFlag, conform, desiredClass,inputData);
            }
            else
                continue;

            //fix MAS and profile for QoCDC 3.2
            Resource headerID = instanceDataModel.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
            if(instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"), (RDFNode)  null).hasNext()) {
                Statement stmtDelete=instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"), (RDFNode)  null).next();
                instanceDataModel.remove(stmtDelete);
            }
            if(instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime"), (RDFNode)  null).hasNext()) {
                Statement stmtScenarioTime=instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.scenarioTime"), (RDFNode)  null).next();
                instanceDataModel.remove(stmtScenarioTime);
                instanceDataModel.add(stmtScenarioTime);
            }
            if(instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)  null).hasNext()) {
                Statement stmtDescription=instanceDataModel.listStatements(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode)  null).next();
                instanceDataModel.remove(stmtDescription);
                instanceDataModel.add(headerID, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"),
                        ResourceFactory.createTypedLiteral("&lt;qodc:MDE&gt;&lt;qodc:BP&gt;1D&lt;/qodc:BP&gt;&lt;qodc:TOOL&gt;PowerFactory 2021&lt;/qodc:TOOL&gt;&lt;qodc:RSC&gt;N/A&lt;/qodc:RSC&gt;&lt;qodc:TXT&gt;  QoCDC v3.2 test configuration&lt;/qodc:TXT&gt;&lt;/qodc:MDE&gt;"));
            }

            if (entry.getKey().equals("EQ")){
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/EquipmentCore/3/1"));
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/EquipmentOperation/3/1"));
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1"));
            }else if (entry.getKey().equals("TP")){
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/Topology/4/1"));
            }else if(entry.getKey().equals("SSH")){
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1"));
            }else if(entry.getKey().equals("SV")){
                instanceDataModel.add(headerID,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"),ResourceFactory.createTypedLiteral("http://entsoe.eu/CIM/StateVariables/4/1"));
            }

            splitInstanceModelMap.replace(entry.getKey(),instanceDataModel);
        }

        return splitInstanceModelMap;
    }

    //add header in the profile
    private static Map<String,Model> fixHeaderDependency(Map<String, Model> splitInstanceModelMap, Map<String, ArrayList<Object>>  profileDataMap,
                                                         Model shaclModel, String xmlBase, Integer cardinalityFlag, Boolean conform, String desiredClass,
                                                         Map<String, Boolean> inputData, Boolean deleteSupersede) {
        Model instanceDataModel=null;
        for (Map.Entry<String, Model> entry : splitInstanceModelMap.entrySet()) {
            if (!entry.getKey().equals("modelUnionWithoutHeader")) {
                instanceDataModel=entry.getValue();
                if (deleteSupersede){
                    List<Statement> supersedeStmts=instanceDataModel.listStatements(null,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.Supersedes"),(RDFNode) null).toList();
                    instanceDataModel.remove(supersedeStmts);
                }
                List<String> dependency = headerDependencyMap.get(entry.getKey());
                List<Statement> dependOnStmts=instanceDataModel.listStatements(null,ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"),(RDFNode) null).toList();
                Resource headerRes = dependOnStmts.getFirst().getSubject();
                //delete the dependency tripples
                instanceDataModel.remove(dependOnStmts);

                List<Statement> newDependOnStmts;
                for (String dep : dependency){
                    if (!dep.isEmpty()){
                        //fix the dependency
                        Model dependentModel = splitInstanceModelMap.get(dep);
                        if (dependentModel!=null) {
                            Statement headerStmt = dependentModel.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
                            //add good dependentOn statement
                            instanceDataModel.add(headerRes, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.DependentOn"), headerStmt.getSubject());
                        }
                    }
                }
                splitInstanceModelMap.replace(entry.getKey(),instanceDataModel);
            }
        }

        return splitInstanceModelMap;
    }

    //Add dummy class to instance data model
    private static Model addDummyClass(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, String classFullURI,Integer cardinalityFlag,Map<String,Boolean> inputData) {

        classResource = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());
        resDummyClass=classResource;
        RDFNode rdfType = ResourceFactory.createResource(classFullURI);
        Statement stmt = ResourceFactory.createStatement(classResource, RDF.type,rdfType);
        instanceDataModel.add(stmt);

        return instanceDataModel;
    }

    //process profile data and all elements/equipment to single node
    private static Model processAndAddSingle(ArrayList<Object> profileData, Model instanceDataModel, Model shaclModel, String xmlBase, Integer cardinalityFlag, Boolean conform, String desiredClass, Map<String, Boolean> inputData) {


        for (int cl = 0; cl < ((ArrayList<?>) profileData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class


            //add a class
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).getFirst()).getFirst().toString();
            String classLocalName = classFullURI.split("#",2)[1];
            /*if (classLocalName.equals("Command")){
                int k=1;
            }*/
            if ((!classLocalName.equals("Terminal") && !classLocalName.equals("DCTerminal") && !classLocalName.equals("ACDCConverterDCTerminal") && !classLocalName.equals("ConnectivityNode") && !classLocalName.equals("TopologicalNode") && !skipClassMap.containsKey(classLocalName) && desiredClass==null) || (classLocalName.equals(desiredClass))) {
                if (desiredClass==null) {
                    //need to check if the class is already in the model; if not then it is added; if yes associations and attributes are added to it
                    boolean classInModel = instanceDataModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createResource(classFullURI)).hasNext();
                    if (!classInModel) {
                        addClass(profileData, instanceDataModel, xmlBase, classFullURI, cardinalityFlag, inputData);
                    } else {
                        classResource = instanceDataModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createResource(classFullURI)).next();
                    }
                }else{//if desired class is defined then the class is always added.
                    addClass(profileData, instanceDataModel, xmlBase, classFullURI, cardinalityFlag, inputData);
                }



                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations


                    ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                    /*
                     * propertyNodeFeatures structure
                     * 0 - type of check: cardinality, datatype, associationValueType
                     * 1 - message
                     * 2 - name
                     * 3 - description
                     * 4 - severity
                     * 5 - cardinality
                     * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                     * in case of enumeration 6 is set to Enumeration
                     * in case of compound 6 is set to Compound
                     * 7 - is a list of uri of the enumeration attributes
                     * 8 - order
                     * 9 - group
                     * 10 - the list of concrete classes for association - the value type at the used end
                     * 11 - classFullURI for the targetClass of the NodeShape
                     * 12 - the uri of the compound class to be used in sh:class
                     * 13 - path for the attributes of the compound
                     */
                   /* for (int i = 0; i < 14; i++) {
                        propertyNodeFeatures.add("");
                    }*/

                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);


                            //Association info for target class - only for concrete classes in the profile
                            if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                //propertyNodeFeatures.set(0, "associationValueType");
                                //String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                                //localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                                //propertyNodeFeatures.set(5, cardinality);
                                //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");
                                //propertyNodeFeatures.set(1, "Not correct target class.");
                                //propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                //propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                                //propertyNodeFeatures.set(4, "Violation");
                                //propertyNodeFeatures.set(8, atas - 1); // this is the order
                                //propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                                List<Resource> concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);
                                //propertyNodeFeatures.set(10, concreteClasses);
                                //String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
                                //propertyNodeFeatures.set(11, classFullURI);

                                //shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                instanceDataModel=selectAssociationReferenceAndAddAssociation(instanceDataModel,concreteClasses,inputData,classFullURI,property,conform,profileData,xmlBase,cardinalityFlag,cardinality, classLocalName);

                            }




                    /*    //Cardinality check
                        propertyNodeFeatures.set(0, "cardinality");
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                        String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                        propertyNodeFeatures.set(5, cardinality);
                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        propertyNodeFeatures.set(1, "Missing required association.");
                        propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                        propertyNodeFeatures.set(4, "Violation");
                        propertyNodeFeatures.set(8, atas - 1); // this is the order
                        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(2).toString();

                        shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                        //Association check for target class - only for concrete classes in the profile
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                            propertyNodeFeatures.set(0, "associationValueType");
                            //String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            //localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            //propertyNodeFeatures.set(5, cardinality);
                            //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");
                            propertyNodeFeatures.set(1, "Not correct target class.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                            propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                            List<Resource> concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10);
                            propertyNodeFeatures.set(10, concreteClasses);
                            //String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            propertyNodeFeatures.set(11, classFullURI);

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        }*/
                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        //String localNameAttr = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                       /* if (property.getLocalName().equals("Model.profile")){
                            int k=1;
                        }*/

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, datatypePrimitive);
                                //propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, datatypePrimitive);
                                //propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                                break;
                            }
                            case "Compound": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, "Compound");
                                //propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                                //propertyNodeFeatures.set(12, datatypeCompound);
                                //break;
                            }
                            case "Enumeration":
                                //propertyNodeFeatures.set(6, "Enumeration");
                                //propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                                //this adds the structure which is a list of possible enumerated values

                                datatypeValuesEnum = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);
                                //propertyNodeFeatures.set(7, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10));
                                break;
                        }

                        addAttribute(instanceDataModel, property, conform, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, null, inputData, classFullURI);
                        //saveInstanceData(instanceDataModel, xmlBase);



                    /*shapeModel= addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource,localNameAttr,propertyFullURI);
                    //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                    // for each attribute of the compound
                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(8).toString().equals("Compound")) {
                        ArrayList shapeDataCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10));
                        shapeModel= addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile, nodeShapeResource, localNameAttr,propertyFullURI);
                    }*/
                    }
                }
            }
        }


        return instanceDataModel;
    }

    //Add class to instance data model
    private static Model addClass(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, String classFullURI,Integer cardinalityFlag,Map<String,Boolean> inputData) {

        RDFNode rdfType = ResourceFactory.createResource(classFullURI);
        //an exception for FullModel and Difference model classes
        String uuidPrefix=xmlBase+"#_";
        if (rdfType.asResource().getLocalName().equals("FullModel") || rdfType.asResource().getLocalName().equals("DifferenceModel")){
            uuidPrefix="urn:uuid:";
        }
        classResource = ResourceFactory.createResource(uuidPrefix+UUID.randomUUID());
        RDFNode resource = classResource;

        Statement stmt = ResourceFactory.createStatement(classResource, RDF.type,rdfType);
        instanceDataModel.add(stmt);
        countOfClasses=countOfClasses+1;

        //add necessary number of terminals
        if (!rdfType.asResource().getLocalName().equals("Terminal") || !rdfType.asResource().getLocalName().equals("DCTerminal") || !rdfType.asResource().getLocalName().equals("ACDCConverterDCTerminal")) {
            Map<String,Integer> terminals = identifyClassType(rdfType.asResource().getLocalName());
            if(terminals.containsKey("AC")) {
                if (terminals.get("AC") == 1) {
                    addTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                } else if (terminals.get("AC") == 2) {
                    addTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                    addTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
                }
            }else if(terminals.containsKey("DC")) {
                if (terminals.get("DC") == 1) {
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                } else if (terminals.get("DC") == 2) {
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
                }
            }
        }
        //need to add 2 ACDCConverter Terminal-s
        if (rdfType.asResource().getLocalName().equals("VsConverter") || rdfType.asResource().getLocalName().equals("CsConverter")) {
            addACDCConverterDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
            addACDCConverterDCTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
        }


        return instanceDataModel;
    }

    //Add attribute to instance data model
    private static Model addAttribute(Model instanceDataModel, Property property, Boolean conform, String datatypeType,
                                      String datatypeValueString, Object datatypeValuesEnum,
                                      String cardinality, Integer cardinalityFlag, String classLocalName, Object concreteValue,Map<String,Boolean> inputData, String classFullURI) {


        Map<String,Object> shaclContraintResult = null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
            }
        }

        Resource classRes;
        if (classLocalName.equals("Terminal") || classLocalName.equals("DCTerminal") || classLocalName.equals("ACDCConverterDCTerminal")){
            classRes = classResourceTerminal;
        }else {
            classRes = classResource;
        }

        if(property.getLocalName().equals("Conductor.length")){
            int k=1;
        }

        Statement stmt = null;
        String attributeValue=null;

        Map<String,Integer> cardinalityCheckResult= cardinalityCheck(cardinality, shaclContraintResult,property);
        int lowerBound = cardinalityCheckResult.get("lowerBound");
        int upperBound =cardinalityCheckResult.get("upperBound");

        List<RDFNode> inValues = new ArrayList<>();

        //cardinalityFlag 1 = required only; 0=optional only ; 3=all - optional and required
        if ((cardinalityFlag==1 && lowerBound>=1 && upperBound>=1) ||
                (cardinalityFlag==0 && lowerBound==0 && upperBound>=1) ||
                cardinalityFlag==3) {

            if (lowerBound>=1 && upperBound>=1){//required
                countOfRequiredAttributes=countOfRequiredAttributes+1;
            }else if(lowerBound==0 && upperBound>=1) {//optional
                countOfOptionalAttributes=countOfOptionalAttributes+1;
            }

            //the attribute is added and need to record this
            ((LinkedList<String>) addedClassPropertyProcessed.getFirst()).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList<String>) addedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList<String>) addedClassPropertyProcessed.get(2)).add("Attribute "+lowerBound+".."+upperBound);

            switch (datatypeType) {
                case "Primitive":
                case "CIMDatatype": {
                    switch (datatypeValueString) {
                        case "Integer":
                            if (concreteValue==null) {
                                Map<String,Integer> valuesInt = new HashMap<>();
                                if (shaclContraintResult!=null) {
                                    if (shaclContraintResult.containsKey("minExclusive")) {
                                        valuesInt.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("minInclusive")) {
                                        valuesInt.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxExclusive")) {
                                        valuesInt.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxInclusive")) {
                                        valuesInt.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getInt());
                                    }
                                    if (!shaclContraintResult.containsKey("minExclusive") && !shaclContraintResult.containsKey("minInclusive")){
                                        valuesInt.put("minInclusive",0);
                                    }
                                    if (!shaclContraintResult.containsKey("maxExclusive") && !shaclContraintResult.containsKey("maxInclusive")){
                                        valuesInt.put("maxInclusive",999);
                                    }
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                    }
                                }else{
                                    valuesInt.put("minInclusive",0);
                                    valuesInt.put("maxInclusive",999);
                                }

                                attributeValue = generateAttributeValueInteger(conform, valuesInt, inValues);

                            }else{
                                attributeValue=concreteValue.toString();
                            }
                            break;
                        case "Float":
                            Map<String,Float> valuesFL = new HashMap<>();
                            if (shaclContraintResult!=null) {
                                if (shaclContraintResult.containsKey("minExclusive")) {
                                    valuesFL.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("minInclusive")) {
                                    valuesFL.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("maxExclusive")) {
                                    valuesFL.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("maxInclusive")) {
                                    valuesFL.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("in")) {
                                    inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                }
                            }else{
                                valuesFL.put("minInclusive", (float) 0.1);
                                valuesFL.put("maxInclusive", (float) 999.1);
                            }
                            attributeValue = generateAttributeValueFloat(conform,valuesFL);
                            break;

                        case "String":
                        case "StringFixedLanguage":
                            Map<String,Integer> valuesST = new HashMap<>();
                            if (property.getLocalName().equals("IdentifiedObject.mRID")){
                                attributeValue=classRes.getLocalName().split("_",2)[1];
                            }else {
                                if (shaclContraintResult != null) {
                                    if (shaclContraintResult.containsKey("minLength")) {
                                        valuesST.put("minLength", ((RDFNode) shaclContraintResult.get("minLength")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxLength")) {
                                        valuesST.put("maxLength", ((RDFNode) shaclContraintResult.get("maxLength")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                    }
                                } else {
                                    valuesST.put("minLength", 1);
                                    valuesST.put("maxLength", 10);
                                }
                                attributeValue = generateAttributeValueString(conform,valuesST,inValues);
                            }
                            break;

                        case "Boolean":
                            if (property.getLocalName().equals("ACDCTerminal.connected")){
                                attributeValue="true";
                            }else {
                                attributeValue = generateAttributeValueBoolean(conform);
                            }
                            if(!conform){
                                attributeValue="truee";
                            }
                            break;

                        case "Date":
                            long aDay = TimeUnit.DAYS.toMillis(1);
                            long now = new Date().getTime();
                            Date startInclusive = new Date(now + aDay * 2 ); // starts 2 days from now
                            Date endExclusive = new Date(now + aDay * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueDate(conform, startInclusive, endExclusive);
                            break;

                        case "DateTime":
                            long aDay1 = TimeUnit.DAYS.toMillis(1);
                            long now1 = new Date().getTime();
                            Date startInclusive1 = new Date(now1 + aDay1 * 2 ); // starts 2 days from now
                            Date endExclusive1 = new Date(now1 + aDay1 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueDateTime(conform, startInclusive1, endExclusive1);
                            break;

                        case "Decimal":
                            attributeValue = generateAttributeValueDecimal(conform);
                            break;

                        case "Duration":
                            if (conform) {
                                attributeValue="P5Y2M10DT15H";
                            }else{
                                attributeValue= RandomStringUtils.randomAlphabetic(10);
                            }
                            break;

                        case "MonthDay":
                            long aDay2 = TimeUnit.DAYS.toMillis(1);
                            long now2 = new Date().getTime();
                            Date startInclusive2 = new Date(now2 + aDay2 * 2 ); // starts 2 days from now
                            Date endExclusive2 = new Date(now2 + aDay2 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueMonthDay(conform, startInclusive2, endExclusive2);
                            break;

                        case "Time":
                            long aDay3 = TimeUnit.DAYS.toMillis(1);
                            long now3 = new Date().getTime();
                            Date startInclusive3 = new Date(now3 + aDay3 * 2 ); // starts 2 days from now
                            Date endExclusive3 = new Date(now3 + aDay3 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueTime(conform, startInclusive3, endExclusive3);
                            break;

                        case "URI":
                        case "URL":
                        case "IRI":
                        case "StringIRI":
                            if (conform) {
                                if (shaclContraintResult != null) {
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                        attributeValue=inValues.getFirst().toString();
                                    }else{
                                        attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                                    }

                                }else {
                                    attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                                }
                            }else{
                                attributeValue=RandomStringUtils.randomAlphabetic(10);
                            }
                            break;
                    }
                    break;
                }
                case "Compound": {
                    //TODO compound at later stage. This is to do a good and bad compound when attributes are generated.
                }
                case "Enumeration": {
                    Map<String, Integer> values = new HashMap<>();
                    values.put("minInclusive", 0);
                    if (shaclContraintResult != null) {
                        if (shaclContraintResult.containsKey("in")) {
                            List<RDFNode> objectTempIn = (List<RDFNode>) shaclContraintResult.get("in");
                            if (conform) {
                                values.put("maxInclusive", objectTempIn.size() - 1);
                                int randomInt = Integer.parseInt(generateAttributeValueInteger(true, values, inValues));
                                attributeValue = objectTempIn.get(randomInt).toString();
                            } else {
                                if (datatypeValuesEnum != null) {
                                    boolean noValue = true;
                                    for (Object item : (ArrayList<?>) datatypeValuesEnum) {
                                        if (!objectTempIn.contains(ResourceFactory.createResource(item.toString()))) {
                                            attributeValue = item.toString();
                                            noValue = false;
                                        }
                                    }
                                    if (noValue) {
                                        attributeValue = objectTempIn.getFirst().toString() + "nonConform";
                                    }
                                }
                            }
                        }
                    } else {
                        if (datatypeValuesEnum != null) {
                            values.put("maxInclusive", ((ArrayList<?>) datatypeValuesEnum).size() - 1);
                            int randomInt = Integer.parseInt(generateAttributeValueInteger(true, values, new ArrayList<>()));
                            if (conform) {
                                attributeValue = ((ArrayList<?>) datatypeValuesEnum).get(randomInt).toString();
                            } else {
                                //TODO see if the non conform can be clever e.g. to select from other list;
                                attributeValue = ((ArrayList<?>) datatypeValuesEnum).get(randomInt).toString() + "nonConform";
                            }
                        }
                    }
                    break;
                }
            }

            if (attributeValue!=null && datatypeValuesEnum==null) {
                stmt = addGenerategAttributePlainLiteralToStatement(attributeValue, classRes, property);
                instanceDataModel.add(stmt);
            }else if (datatypeValuesEnum!=null){
                stmt = addEnumerationToStatement(attributeValue, classRes, property);
                instanceDataModel.add(stmt);
            }else {
                //GuiHelper.appendTextToOutputWindow("[Error] The class.attribute: "+property.getLocalName()+" did not get value.",true);
            }
        }else{
            //the attribute is skipped and need to record this
            ((LinkedList<String>) skippedClassPropertyProcessed.getFirst()).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList<String>) skippedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList<String>) skippedClassPropertyProcessed.get(2)).add("Attribute");
        }
        return instanceDataModel;
    }

    //identify equipment classes and their number of terminals
    private static Map<String,Integer> identifyClassType(String classURILocalName) {

        Map<String,Integer> terminals=new HashMap<>();

        if (terminalMap.containsKey(classURILocalName)) {
            terminals.put("AC", terminalMap.get(classURILocalName));
        }else if(terminalDCMap.containsKey(classURILocalName)){
            terminals.put("DC", terminalDCMap.get(classURILocalName));
        }else{
            //GuiHelper.appendTextToOutputWindow("[Warning] The class: "+classURILocalName+" will not have Terminal.",true);
        }
        return terminals;
    }

    //Add terminal to instance data model
    private static Model addTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                     Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList<?>) profileData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).getFirst()).getFirst().toString();

            if (classFullURI.split("#",2)[1].equals("Terminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="Terminal";


                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("Terminal.ConductingEquipment")) {
                                addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource, classLocalName, inputData, classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    selectAssociationReferenceAndAddAssociation(instanceDataModel, concreteClasses, inputData, classFullURI, property, conform, profileData, xmlBase, cardinalityFlag, cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber, inputData, classFullURI);
                        }else{
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, null, inputData, classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }

    //Add terminal to instance data model
    private static Model addDCTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                       Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList<?>) profileData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).getFirst()).getFirst().toString();

            if (classFullURI.split("#",2)[1].equals("DCTerminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="DCTerminal";


                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("DCTerminal.DCConductingEquipment")) {
                                addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource, classLocalName, inputData, classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    selectAssociationReferenceAndAddAssociation(instanceDataModel, concreteClasses, inputData, classFullURI, property, conform, profileData, xmlBase, cardinalityFlag, cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber, inputData, classFullURI);
                        }else{
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, null, inputData, classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }

    //Add terminal to instance data model
    private static Model addACDCConverterDCTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                                    Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList<?>) profileData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).getFirst()).getFirst().toString();

            if (classFullURI.split("#",2)[1].equals("ACDCConverterDCTerminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="ACDCConverterDCTerminal";


                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("DCTerminal.DCConductingEquipment")) {
                                addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource, classLocalName, inputData, classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    selectAssociationReferenceAndAddAssociation(instanceDataModel, concreteClasses, inputData, classFullURI, property, conform, profileData, xmlBase, cardinalityFlag, cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) profileData.getFirst()).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber, inputData, classFullURI);
                        }else{
                            addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, null, inputData, classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }

    //create skip classes map
    public static Map<String,Object> getShaclConstraint(Boolean conform,Property property) {
        Map<String,Object> shaclContraintResult = new HashMap<>();

        //shacl properties to considered and could be found in the Maps
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in
        //sh:equals, sh:disjoint

        //gets the 1st level structure
        Map mainMap=shaclConstraints.get(property);
        // gets the path content - is it direct, inverse or sequence
        // shaclConstraints.get(property).get("pathContent");
        //gets the content of the property shape where the keys are the shacl properties and the value is the object
        // shaclConstraints.get(property).get("propertyShapeContent");
        //gets the value
        // ((Map) shaclConstraints.get(property).get("propertyShapeContent")).get(SH.nodeKind);
        Map propertyShapeContent = (Map) mainMap.get("propertyShapeContent");

        if (conform){
            shaclContraintResult.put("conform",true);

        }else{
            shaclContraintResult.put("conform",false);
        }
        if (propertyShapeContent.containsKey(SH.datatype)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.datatype)).getFirst();
            shaclContraintResult.put("datatype",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.nodeKind)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.nodeKind)).getFirst();
            shaclContraintResult.put("nodeKind",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minCount)).getFirst();
            shaclContraintResult.put("minCount",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxCount)).getFirst();
            shaclContraintResult.put("maxCount",objectTemp);
        }


        if (propertyShapeContent.containsKey(SH.minExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minExclusive)).getFirst();
            shaclContraintResult.put("minExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minInclusive)).getFirst();
            shaclContraintResult.put("minInclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxExclusive)).getFirst();
            shaclContraintResult.put("maxExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxInclusive)).getFirst();
            shaclContraintResult.put("maxInclusive",objectTemp);
        }



        if (propertyShapeContent.containsKey(SH.minLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minLength)).getFirst();
            shaclContraintResult.put("minLength",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxLength)).getFirst();
            shaclContraintResult.put("maxLength",objectTemp);
        }






        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThan)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThan));
            shaclContraintResult.put("lessThan",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThanOrEquals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThanOrEquals));
            shaclContraintResult.put("lessThanOrEquals",objectTemp);
        }

        //can be a list
        if (propertyShapeContent.containsKey(SH.equals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.equals));
            shaclContraintResult.put("equals",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.disjoint)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.disjoint));
            shaclContraintResult.put("disjoint",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.in)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.in));
            shaclContraintResult.put("in",objectTemp);
        }



        return shaclContraintResult;
    }

    //extracts cardinality either form RDFS or SHACL data
    private static Map<String,Integer> cardinalityCheck(String cardinality,Map<String,Object> shaclContraintResult,Property property) {

        Map<String,Integer> cardinalityMap = new HashMap<>();


        String multiplicity ="";
        int lowerBoundRDF = 0;
        int upperBoundRDF =0;
        int lowerBound = 0;
        int upperBound =0;

        if (cardinality.length() == 1) {
            //need to have sh:minCount 1 ; and sh:maxCount 1 ;
            multiplicity = "required";
            lowerBoundRDF = 1;
            upperBoundRDF = 1;


        } else if (cardinality.length() == 4) {
            multiplicity = "seeBounds";

            if (Character.isDigit(cardinality.charAt(0))) {
                lowerBoundRDF = Character.getNumericValue(cardinality.charAt(0));
            }
            if (Character.isDigit(cardinality.charAt(3))) {
                upperBoundRDF = Character.getNumericValue(cardinality.charAt(3));

            } else {
                upperBoundRDF = 999; // means that no upper bound is defined when we have upper bound "to many"
            }

              /*  if (lowerBound != 0 && upperBound != 999) { // covers 1..1 x..y excludes 0..n
                    if (lowerBound != 1 && upperBound != 1) {//is they are the same 1..1 "Missing required association" is used
                    }

                } else if (lowerBound == 0 && upperBound != 999) {//need to cover 0..x

                } else if (lowerBound != 0 && upperBound == 999) {//need to cover x..n

                }*/

        }

        if (shaclContraintResult!=null ) {
            int lowerBoundShacl = 0;
            int upperBoundShacl =0;
            //with this SHACL becomes the priority; for instance if RDFS says 0..1 SHACL could be 1..1. In a way it could also override
            if(shaclContraintResult.containsKey("minCount") ){
                lowerBoundShacl = ((RDFNode) shaclContraintResult.get("minCount")).asLiteral().getInt();
                if (lowerBoundRDF>lowerBoundShacl){
                    lowerBound=lowerBoundShacl;
                    //GuiHelper.appendTextToOutputWindow("[Attention] The class.attribute: "+property.getLocalName()+" has relaxing lower bound cardinality in SHACL. Note that SHACL constraint overrides.",true);
                }else{
                    lowerBound=lowerBoundShacl;
                }
            }else{
                lowerBound = lowerBoundRDF;
            }
            if(shaclContraintResult.containsKey("maxCount")){
                upperBoundShacl = ((RDFNode) shaclContraintResult.get("maxCount")).asLiteral().getInt();
                if (upperBoundRDF<upperBoundShacl){
                    upperBound=upperBoundShacl;
                    //GuiHelper.appendTextToOutputWindow("[Attention] The class.attribute: "+property.getLocalName()+" has relaxing upper bound cardinality in SHACL. Note that SHACL constraint overrides.",true);
                }else{
                    upperBound=upperBoundShacl;
                }
            }else{
                upperBound = upperBoundRDF;
            }

        }else{
            lowerBound=lowerBoundRDF;
            upperBound=upperBoundRDF;
        }
        cardinalityMap.put("lowerBound",lowerBound);
        cardinalityMap.put("upperBound",upperBound);
        return cardinalityMap;
    }

    //Generate integer value as string for attribute
    public static String generateAttributeValueInteger(Boolean conform, Map<String,Integer> values, List<RDFNode> inValues) {

        SecureRandom random = new SecureRandom();

        String attributeValue = null;
        int value;

      /*  if (inValues.size()!=0) {
            if (inValues.getFirst().toString().contains("ThreePhasePower")) {
                int k = 1;
            }
        }*/

        if (values.isEmpty() && inValues.isEmpty()){//no limits defined
            if (conform) {
                value= random.nextInt((10)+1);
                attributeValue=String.valueOf(value);
            }else{
                attributeValue="1.1";
            }
        }

        if (values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,maxExclusive (1,1)
            int minExclusive=values.get("minExclusive");
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((maxExclusive - minExclusive));
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxExclusive);
                tempIntList.add(maxExclusive+1);
                value=random.nextInt(3); //with 3 gives 0,1 or 2
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minExclusive,maxInclusive (1,1]
            int minExclusive=values.get("minExclusive");
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((maxInclusive - minExclusive)+1);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxInclusive+1);
                value=random.nextInt(2); //with 2 gives 0,1
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minInclusive, maxInclusive [1,1]
            int minInclusive=values.get("minInclusive");
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=minInclusive + random.nextInt((maxInclusive - minInclusive)+1);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add(minInclusive - 1);
                    tempIntList.add(maxInclusive+1);
                    value=random.nextInt(2); //with 3 gives 0,1
                }else{
                    tempIntList.add(maxInclusive+1);
                    value=0;
                }
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,maxExclusive [1,1)
            int minInclusive=values.get("minInclusive");
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minInclusive + random.nextInt((maxExclusive - minInclusive));
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add(minInclusive - 1);
                    tempIntList.add(maxExclusive);
                    tempIntList.add(maxExclusive+1);
                    value=random.nextInt(3); //with 3 gives 0,1,2
                }else{
                    tempIntList.add(maxExclusive);
                    value=0;
                }
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,NA [1,...
            int minInclusive=values.get("minInclusive");
            if (conform) {
                value=minInclusive + random.nextInt((999 - minInclusive)+1);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                attributeValue=String.valueOf(minInclusive - 1);
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,NA (1,...
            int minExclusive=values.get("minExclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((999 - minExclusive)+1);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                attributeValue=String.valueOf(minExclusive);
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//NA,maxInclusive ...,1]
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=random.nextInt(maxInclusive+1);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                attributeValue=String.valueOf(maxInclusive+1);
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//NA,maxExclusive ...,1)
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=random.nextInt(maxExclusive);
                if(inValues.isEmpty()) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.getFirst().toString();
                }
            }else{
                attributeValue=String.valueOf(maxExclusive);
            }
        }

        return attributeValue;
    }

    //Generate string value for attribute
    public static String generateAttributeValueString(Boolean conform,Map<String,Integer> values, List<RDFNode> inValues) {

        Map<String,Integer> valuesTemp = new HashMap<>();
        int randomInt=0;
        if(!inValues.isEmpty()) {
            valuesTemp.put("minInclusive", 0);
            valuesTemp.put("maxInclusive", inValues.size() - 1);

            if (inValues.getFirst().toString().contains("ThreePhasePower")) {
                int k = 1;
            }
            randomInt = Integer.parseInt(generateAttributeValueInteger(true, valuesTemp, new ArrayList<>()));
        }

        String attributeValue = "";
        SecureRandom random = new SecureRandom();
        int len=0;
        if (values.containsKey("minLength") && values.containsKey("maxLength")){ //there is min and max
            int minLength=values.get("minLength");
            int maxLength=values.get("maxLength");
            len=minLength + random.nextInt((maxLength - minLength) + 1);
            if (conform) {
                if(inValues.isEmpty()) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(maxLength+1);
            }
        }
        if (values.containsKey("minLength") && !values.containsKey("maxLength")) { //only min
            int minLength=values.get("minLength");
            len=minLength + random.nextInt((999 - minLength) + 1);
            if (conform) {
                if(inValues.isEmpty()) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                if (minLength>=1) {
                    attributeValue = RandomStringUtils.randomAlphabetic(minLength - 1);
                }else{
                    attributeValue = RandomStringUtils.randomAlphabetic(minLength);
                    // GuiHelper.appendTextToOutputWindow("[Error] Please check: non-conform value is not possible with this value of minLength = "+minLength,true);
                }
            }
        }
        if (!values.containsKey("minLength") && values.containsKey("maxLength")) { //only max
            int maxLength=values.get("maxLength");
            len=1 + random.nextInt((maxLength - 1) + 1);
            if (conform) {
                if(inValues.isEmpty()) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(maxLength+1);
            }
        }
        if (!values.containsKey("minLength") && !values.containsKey("maxLength")){ //there is no min and no max
            len=10;
            if (conform) {
                if(inValues.isEmpty()) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(len+300);
                //GuiHelper.appendTextToOutputWindow("[Error] Please check: There is no min and max length for the string.",true);
            }
        }

        return attributeValue;
    }

    //Generate float value as string for attribute
    public static String generateAttributeValueFloat(Boolean conform, Map<String,Float> values) {

        SecureRandom random = new SecureRandom();

        String attributeValue = null;
        float value;
        int index;

        if (values.isEmpty()){//no limits defined
            if (conform) {
                value= (float) (0 + random.nextFloat()*(1)+0.000001);
                attributeValue=String.valueOf(value);
            }else{
                attributeValue="1,1";
            }
        }

        if (values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,maxExclusive (1,1)
            float minExclusive=values.get("minExclusive");
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextInt()*(maxExclusive - minExclusive));
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxExclusive);
                tempIntList.add((float) (maxExclusive+0.000001));
                index=random.nextInt(3); //with 3 gives 0,1 or 2
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minExclusive,maxInclusive (1,1]
            float minExclusive=values.get("minExclusive");
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextFloat()*((maxInclusive - minExclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add((float) (maxInclusive+0.000001));
                index=random.nextInt(2); //with 2 gives 0,1
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minInclusive, maxInclusive [1,1]
            float minInclusive=values.get("minInclusive");
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (minInclusive + random.nextFloat()*(maxInclusive - minInclusive)+0.000001);
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add((float) (minInclusive - 0.000001));
                    tempIntList.add((float) (maxInclusive+0.000001));
                    index=random.nextInt(2); //with 3 gives 0,1
                }else{
                    tempIntList.add(maxInclusive+1);
                    index=0;
                }
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,maxExclusive [1,1)
            float minInclusive=values.get("minInclusive");
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minInclusive + random.nextFloat()*(maxExclusive - minInclusive);
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add((float) (minInclusive - 0.000001));
                    tempIntList.add(maxExclusive);
                    tempIntList.add((float) (maxExclusive+0.000001));
                    index=random.nextInt(3); //with 3 gives 0,1,2
                }else{
                    tempIntList.add(maxExclusive);
                    index=0;
                }
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,NA [1,...
            float minInclusive=values.get("minInclusive");
            if (conform) {
                value= (float) (minInclusive + random.nextFloat()*((999 - minInclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(minInclusive - 0.000001);
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,NA (1,...
            float minExclusive=values.get("minExclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextFloat()*((999 - minExclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(minExclusive);
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//NA,maxInclusive ...,1]
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (random.nextFloat()*(maxInclusive+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(maxInclusive+0.000001);
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//NA,maxExclusive ...,1)
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                //GuiHelper.appendTextToOutputWindow("[maxExclusive]: "+maxExclusive,true);
                value= (float) -0.1;
                if (maxExclusive!=0){
                    value = random.nextInt((int) maxExclusive);
                }
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(maxExclusive);
            }
        }

        return attributeValue;
    }

    //Generate date value as string for attribute
    public static String generateAttributeValueDate(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }else{
            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";

        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);


        return attributeValue;
    }
    //Generate time value as string for attribute
    public static String generateAttributeValueTime(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "HH:mm:ss'Z'";

        }else{
            //isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);


        return attributeValue;
    }

    //Generate date time value as string for attribute
    public static String generateAttributeValueDateTime(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {


            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
            //String isoDatePattern = "--MM-dd";

        }else{
            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);

        return attributeValue;
    }

    //Generate date time value as string for attribute
    public static String generateAttributeValueMonthDay(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****

            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
            isoDatePattern = "--MM-dd";
        }else{
            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);

        return attributeValue;
    }

    //Generate boolean value as string for attribute
    public static String generateAttributeValueBoolean(Boolean conform) {

        SecureRandom random = new SecureRandom();

        String attributeValue;
        boolean value;
        if (conform) {
            value=random.nextBoolean();
            attributeValue=String.valueOf(value);
        }else{
            attributeValue="truee";
        }


        return attributeValue;
    }

    //Generate float value as string for attribute
    public static String generateAttributeValueDecimal(Boolean conform) {

        SecureRandom random = new SecureRandom();

        String attributeValue;

        double value;
        if (conform) {
            value=random.nextDouble();
            attributeValue=String.valueOf(BigDecimal.valueOf(value));
        }else{
            attributeValue="1,1";
        }


        return attributeValue;
    }

    //Add the generated attribute to the model
    public static Statement addGenerategAttributePlainLiteralToStatement(String attributeValue, Resource classRes, Property property) {

        Literal attributeValueLiteral = ResourceFactory.createPlainLiteral(attributeValue);

        return ResourceFactory.createStatement(classRes, property, attributeValueLiteral);
    }

    //Add the generated attribute to the model
    public static Statement addEnumerationToStatement(String attributeValue, Resource classRes, Property property) {

        RDFNode attributeEnumeration = ResourceFactory.createResource(attributeValue);


        return ResourceFactory.createStatement(classRes, property, attributeEnumeration);
    }

    //Add association to instance data model
    private static Model addAssociation(Model instanceDataModel, Property property, Boolean conform,
                                        String cardinality, Integer cardinalityFlag, RDFNode resource, String classLocalName,Map<String,Boolean> inputData, String classFullURI) {

        Map<String,Object> shaclContraintResult = null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
            }
        }

        Resource classRes;
        if (classLocalName.equals("Terminal") || classLocalName.equals("DCTerminal") || classLocalName.equals("ACDCConverterDCTerminal")){
            classRes=classResourceTerminal;
        }else{
            classRes=classResource;
        }


        Statement stmt = null;
        String attributeValue=null;

        Map<String,Integer> cardinalityCheckResult= cardinalityCheck(cardinality, shaclContraintResult,property);
        int lowerBound = cardinalityCheckResult.get("lowerBound");
        int upperBound =cardinalityCheckResult.get("upperBound");

        //cardinalityFlag 1 = required only; 0=optional only ; 3=all - optional and required
        if ((cardinalityFlag==1 && lowerBound==1 ) ||
                (cardinalityFlag==0 && lowerBound==0 ) ||
                cardinalityFlag==3) {

            if (lowerBound>=1 && upperBound>=1){//required
                countOfRequiredAssociations=countOfRequiredAssociations+1;
            }else if(lowerBound==0 && upperBound>=1) {//optional
                countOfOptionalAssociations=countOfOptionalAssociations+1;
            }

            if (conform){
                stmt = ResourceFactory.createStatement(classRes, property, resource);
            }else{
                //this association will point to the dummy class
                stmt = ResourceFactory.createStatement(classRes, property, resDummyClass);
            }
            instanceDataModel.add(stmt);

        }else{
            //the association is skipped and need to record this
            ((LinkedList<String>) skippedClassPropertyProcessed.getFirst()).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList<String>) skippedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList<String>) skippedClassPropertyProcessed.get(2)).add("Association");
        }

        return instanceDataModel;
    }

    //select the right class for association.
    private static Model selectAssociationReferenceAndAddAssociation(Model instanceDataModel, List<Resource> concreteClasses,
                                                                     Map<String, Boolean> inputData, String classFullURI, Property property, Boolean conform,
                                                                     ArrayList<Object> profileData, String xmlBase, Integer cardinalityFlag,
                                                                     String cardinality, String classLocalName) {

        //check if there are shacl restrictions
        Map<String,Object> shaclContraintResult;
        List<Resource> inValues = null;
        Resource resOfconcreteClass=null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        boolean noSHACLinfo=false;
        Map<Boolean,RDFNode> hasClassMapWithoutShacl=hasClass(instanceDataModel,concreteClasses);
        Map<Boolean,RDFNode> hasClassMapWithShacl=null;
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
                if (shaclContraintResult.containsKey("in")) {
                    inValues = (List<Resource>) shaclContraintResult.get("in");

                    //check if the class is in the model
                    if (inValues.contains(ResourceFactory.createProperty(xmlBase+"#"+"VoltageLevel"))){//to force the containment to VoltageLevel
                        List<Resource> inValuesVoltageLevel =new ArrayList<>();
                        inValuesVoltageLevel.add(ResourceFactory.createProperty(xmlBase+"#"+"VoltageLevel"));
                        hasClassMapWithShacl = hasClass(instanceDataModel, inValuesVoltageLevel);
                    }else {
                        hasClassMapWithShacl = hasClass(instanceDataModel, inValues);
                    }
                }else{
                    noSHACLinfo=true;
                }
            }else{
                noSHACLinfo=true;
            }
        }else {
            noSHACLinfo=true;
        }


        // see which resource needs to be added - multiple situations
        if (noSHACLinfo && hasClassMapWithoutShacl.containsKey(true)){ // the case when there is no shacl info and the class exists in the model
            resOfconcreteClass = instanceDataModel.listSubjectsWithProperty(RDF.type, hasClassMapWithoutShacl.get(true)).next();
        } else if (noSHACLinfo && !hasClassMapWithoutShacl.containsKey(true)){ // the case when there is no shacl info and the class does not exist in the model
            Resource originalClassRes = classResource;
            addClass(profileData, instanceDataModel, xmlBase, concreteClasses.getFirst().toString(), cardinalityFlag, inputData);
            resOfconcreteClass=classResource;
            classResource=originalClassRes;
        } else if (!noSHACLinfo && hasClassMapWithShacl.containsKey(true)){// the case when there is shacl info and the class exists in the model
            resOfconcreteClass = instanceDataModel.listSubjectsWithProperty(RDF.type, hasClassMapWithShacl.get(true)).next();
        } else if (!noSHACLinfo && !hasClassMapWithShacl.containsKey(true)) {// the case when there is shacl info and the class does not exists in the model
            Resource originalClassRes = classResource;
            addClass(profileData, instanceDataModel, xmlBase, inValues.getFirst().toString(), cardinalityFlag, inputData);
            resOfconcreteClass=classResource;
            classResource=originalClassRes;
        }

        //create the association
        addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resOfconcreteClass, classLocalName, inputData, classFullURI);


        return instanceDataModel;
    }

    //check if the class exists in a model. Returns the 1st one.
    private static Map<Boolean,RDFNode> hasClass(Model instanceDataModel, List<Resource> concreteClasses) {

        Map<Boolean,RDFNode> hasClassMap = new HashMap<>();
        RDFNode resource = null;
        hasClassMap.put(false,resource);

        Set<RDFNode> resInInstanceDataModel= instanceDataModel.listObjectsOfProperty(RDF.type).toSet();
        for (RDFNode concreteClass : concreteClasses) {
            if (resInInstanceDataModel.contains(concreteClass)) {
                hasClassMap.put(true,concreteClass);
                break;
            }
        }

        return hasClassMap;
    }
}
