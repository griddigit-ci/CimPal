package eu.griddigit.cimpal.core.converters;

/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2022, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */


//import application.MainController;
import common.customWriter.CustomRDFFormat;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.commons.math3.ml.neuralnet.MapUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import eu.griddigit.cimpal.core.*;

import java.io.*;
import java.net.URISyntaxException;
import java.util.*;

public class ModelManipulationFactory {

    public static Map<String, String> nameMap;
    public static Model mapModel;

    // Maps with the profile names for both CGMES versions
    private static final Map<String, String> profileURLMap_v2415 = new HashMap<>();
    private static final Map<String, String> profileURLMap_v3 = new HashMap<>();

    static {
        profileURLMap_v2415.put("EQ", "http://entsoe.eu/CIM/EquipmentCore/3/1#");
        profileURLMap_v2415.put("SSH", "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1#");
        profileURLMap_v2415.put("TP", "http://entsoe.eu/CIM/Topology/4/1#");
        profileURLMap_v2415.put("SV", "http://entsoe.eu/CIM/StateVariables/4/1#");

        profileURLMap_v3.put("EQ", "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0#");
        profileURLMap_v3.put("SSH", "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0#");
        profileURLMap_v3.put("TP", "http://iec.ch/TC57/ns/CIM/Topology-EU/3.0#");
        profileURLMap_v3.put("SV", "http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0#");
    }

    // Getting the URL of the profile
    public static String getProfileUrl(String profile, String cgmesVersion){
        if (cgmesVersion.equals("CGMESv3.0")) return profileURLMap_v3.get(profile);
        else if (cgmesVersion.equals("CGMESv2.4")) return profileURLMap_v2415.get(profile);
        return "";
    }
    public static String getProfileName(String profileURL, String cgmesVersion){
        Map<String, String> map = cgmesVersion.equals("CGMESv2.4") ? profileURLMap_v2415 : profileURLMap_v3;
        for (String i : map.keySet()){
            if (map.get(i).equals(profileURL))
                return i;
        }
        return "";
    }

    //Convert CGMES v2.4 to CGMES v3.0
    public static void ConvertCGMESv2v3(Map<String, Map> loadDataMap, int keepExtensions, int eqOnly, int fixRegCont) throws IOException {


        //Map<String, Map> loadDataMap= new HashMap<>();
        String xmlBase = "http://iec.ch/TC57/CIM100";
        //String xmlBase = "";

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;


        //TODO to be improved what file names should be assigned. Now it takes the same names
        nameMap = new HashMap<>();
//        for (File item : MainController.IDModel) {
//            if (item.toString().contains("_EQ") && !item.toString().contains("_EQBD") && !item.toString().contains("_EQ_BD")) {
//                nameMap.put("EQ", item.getName());
//            } else if (item.toString().contains("_SSH")) {
//                nameMap.put("SSH", item.getName());
//            } else if (item.toString().contains("_SV")) {
//                nameMap.put("SV", item.getName());
//            } else if (item.toString().contains("_TP")) {
//                nameMap.put("TP", item.getName());
//            }
//        }

        Map<String, Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(MainController.IDModel, xmlBase, null);

        Model modelEQ = baseInstanceModelMap.get("EQ");
        Model modelSSH = baseInstanceModelMap.get("SSH");
        Model modelSV = baseInstanceModelMap.get("SV");
        Model modelTP = baseInstanceModelMap.get("TP");
        Model modelTPBD = baseInstanceModelMap.get("TPBD");

        Map<String, Model> convertedModelMap = new HashMap<>();

        //create the new models
        Model convEQModel = createModel();
        Model convSSHModel = createModel();
        Model convSVModel = createModel();
        Model convTPModel = createModel();

        //check for extensions

        if (keepExtensions == 1) {
            Map<String, String> oldPrefix = modelEQ.getNsPrefixMap();
            for (Map.Entry<String, String> entry : oldPrefix.entrySet()) {
                if (!entry.getKey().equals("cim") && !entry.getKey().equals("eu") && !entry.getKey().equals("entsoe") && !entry.getKey().equals("md") && !entry.getKey().equals("rfd")) {
                    convEQModel.setNsPrefix(entry.getKey(), entry.getValue());
                }
            }
            if (eqOnly == 0) {
                oldPrefix = modelSSH.getNsPrefixMap();
                for (Map.Entry<String, String> entry : oldPrefix.entrySet()) {
                    if (!entry.getKey().equals("cim") && !entry.getKey().equals("eu") && !entry.getKey().equals("entsoe") && !entry.getKey().equals("md") && !entry.getKey().equals("rfd")) {
                        convSSHModel.setNsPrefix(entry.getKey(), entry.getValue());
                    }
                }
                oldPrefix = modelSV.getNsPrefixMap();
                for (Map.Entry<String, String> entry : oldPrefix.entrySet()) {
                    if (!entry.getKey().equals("cim") && !entry.getKey().equals("eu") && !entry.getKey().equals("entsoe") && !entry.getKey().equals("md") && !entry.getKey().equals("rfd")) {
                        convSVModel.setNsPrefix(entry.getKey(), entry.getValue());
                    }
                }
                oldPrefix = modelTP.getNsPrefixMap();
                for (Map.Entry<String, String> entry : oldPrefix.entrySet()) {
                    if (!entry.getKey().equals("cim") && !entry.getKey().equals("eu") && !entry.getKey().equals("entsoe") && !entry.getKey().equals("md") && !entry.getKey().equals("rfd")) {
                        convTPModel.setNsPrefix(entry.getKey(), entry.getValue());
                    }
                }
            }
        }


        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");

        //add header for EQ
        Resource headerRes = modelEQ.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "FullModel")).nextResource();
        for (StmtIterator n = modelEQ.listStatements(headerRes, null, (RDFNode) null); n.hasNext(); ) {
            Statement stmtH = n.next();
            if (stmtH.getPredicate().getLocalName().equals("Model.profile")) {
                if (stmtH.getObject().asLiteral().getString().equals("http://entsoe.eu/CIM/EquipmentCore/3/1")) {
                    convEQModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")));
                } else if (stmtH.getObject().asLiteral().getString().equals("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1")) {
                    convEQModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0")));
                } else if (stmtH.getObject().asLiteral().getString().equals("http://entsoe.eu/CIM/EquipmentOperation/3/1")) {
                    convEQModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/Operation-EU/3.0")));
                }
            } else if (stmtH.getPredicate().getLocalName().equals("Model.created")) {
                if (!stmtH.getObject().toString().endsWith("Z")) {
                    convEQModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                } else {
                    convEQModel.add(stmtH);
                }

            } else if (stmtH.getPredicate().getLocalName().equals("Model.scenarioTime")) {
                if (!stmtH.getObject().toString().endsWith("Z")) {
                    convEQModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                } else {
                    convEQModel.add(stmtH);
                }
            } else {
                convEQModel.add(stmtH);
            }
        }

        if (eqOnly == 0) {
            //add header for SSH
            RDFNode sshMAS = null;
            headerRes = modelSSH.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "FullModel")).nextResource();
            for (StmtIterator n = modelSSH.listStatements(headerRes, null, (RDFNode) null); n.hasNext(); ) {
                Statement stmtH = n.next();
                if (stmtH.getPredicate().getLocalName().equals("Model.profile")) {
                    convSSHModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0")));
                } else if (stmtH.getPredicate().getLocalName().equals("Model.created")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convSSHModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convSSHModel.add(stmtH);
                    }

                } else if (stmtH.getPredicate().getLocalName().equals("Model.scenarioTime")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convSSHModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convSSHModel.add(stmtH);
                    }
                } else {
                    convSSHModel.add(stmtH);
                }
                if (stmtH.getPredicate().getLocalName().equals("Model.modelingAuthoritySet")) {
                    sshMAS = stmtH.getObject();
                }
            }

            //add header for SV
            headerRes = modelSV.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "FullModel")).nextResource();
            for (StmtIterator n = modelSV.listStatements(headerRes, null, (RDFNode) null); n.hasNext(); ) {
                Statement stmtH = n.next();
                if (stmtH.getPredicate().getLocalName().equals("Model.profile")) {
                    convSVModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0")));
                } else if (stmtH.getPredicate().getLocalName().equals("Model.created")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convSVModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convSVModel.add(stmtH);
                    }

                } else if (stmtH.getPredicate().getLocalName().equals("Model.scenarioTime")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convSVModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convSVModel.add(stmtH);
                    }
                } else {
                    convSVModel.add(stmtH);
                }
                if (stmtH.getPredicate().getLocalName().equals("Model.modelingAuthoritySet")) {
                    convSVModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), sshMAS));
                }
            }
            if (!convSVModel.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "Model.modelingAuthoritySet"), (RDFNode) null).hasNext()) {
                convSVModel.add(ResourceFactory.createStatement(headerRes, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "Model.modelingAuthoritySet"), sshMAS));
            }

            //add header for TP
            headerRes = modelTP.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#", "FullModel")).nextResource();
            for (StmtIterator n = modelTP.listStatements(headerRes, null, (RDFNode) null); n.hasNext(); ) {
                Statement stmtH = n.next();
                if (stmtH.getPredicate().getLocalName().equals("Model.profile")) {
                    convTPModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/Topology-EU/3.0")));
                } else if (stmtH.getPredicate().getLocalName().equals("Model.created")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convTPModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convTPModel.add(stmtH);
                    }

                } else if (stmtH.getPredicate().getLocalName().equals("Model.scenarioTime")) {
                    if (!stmtH.getObject().toString().endsWith("Z")) {
                        convTPModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral(stmtH.getObject().toString() + "Z")));
                    } else {
                        convTPModel.add(stmtH);
                    }
                } else {
                    convTPModel.add(stmtH);
                }
            }
        }


        //conversion process
        String cim17NS = "http://iec.ch/TC57/CIM100#";
        String cim16NS = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
        String euNS = "http://iec.ch/TC57/CIM100-European#";
        List<String> skipList = new LinkedList<>();
        skipList.add("ConnectivityNode.boundaryPoint");
        List<String> getMRID = new LinkedList<>();
        getMRID.add("ConnectivityNode");
        getMRID.add("Line");
        getMRID.add("EnergySchedulingType");
        getMRID.add("GeographicalRegion");
        getMRID.add("SubGeographicalRegion");
        getMRID.add("Terminal");
        getMRID.add("Substation");
        getMRID.add("BaseVoltage");
        getMRID.add("VoltageLevel");
        getMRID.add("Bay");
        getMRID.add("Junction");


        List<String> getMRIDSV = new LinkedList<>();
        getMRIDSV.add("DCTopologicalIsland");
        getMRIDSV.add("TopologicalIsland");

        List<String> getMRIDTP = new LinkedList<>();
        getMRIDTP.add("DCTopologicalNode");
        getMRIDTP.add("TopologicalNode");

        List<String> getMRIDSSH = new LinkedList<>();
        getMRIDSSH.add("Equipment");

        List<String> getMRIDEQ = new LinkedList<>();
        getMRIDEQ.add("ActivePowerLimit");
        getMRIDEQ.add("BusNameMarker");
        getMRIDEQ.add("Ground");
        getMRIDEQ.add("PhotoVoltaicUnit");
        getMRIDEQ.add("LoadBreakSwitch");
        getMRIDEQ.add("StationSupply");
        getMRIDEQ.add("CurrentTransformer");
        getMRIDEQ.add("ReportingGroup");
        getMRIDEQ.add("WaveTrap");
        getMRIDEQ.add("ReactiveCapabilityCurve");
        getMRIDEQ.add("PowerTransformerEnd");
        getMRIDEQ.add("Junction");
        getMRIDEQ.add("TapSchedule");
        getMRIDEQ.add("EnergySchedulingType");
        getMRIDEQ.add("PhaseTapChangerSymmetrical");
        getMRIDEQ.add("NonConformLoad");
        getMRIDEQ.add("NonConformLoadSchedule");
        getMRIDEQ.add("PhaseTapChangerTable");
        getMRIDEQ.add("ExternalNetworkInjection");
        getMRIDEQ.add("ControlAreaGeneratingUnit");
        getMRIDEQ.add("PhaseTapChangerTabular");
        getMRIDEQ.add("GrossToNetActivePowerCurve");
        getMRIDEQ.add("AsynchronousMachine");
        getMRIDEQ.add("Substation");
        getMRIDEQ.add("HydroGeneratingUnit");
        getMRIDEQ.add("DCConverterUnit");
        getMRIDEQ.add("Bay");
        getMRIDEQ.add("OperationalLimitSet");
        getMRIDEQ.add("VsCapabilityCurve");
        getMRIDEQ.add("ConformLoadSchedule");
        getMRIDEQ.add("GeneratingUnit");
        getMRIDEQ.add("CombinedCyclePlant");
        getMRIDEQ.add("SwitchSchedule");
        getMRIDEQ.add("SubLoadArea");
        getMRIDEQ.add("Line");
        getMRIDEQ.add("Jumper");
        getMRIDEQ.add("PetersenCoil");
        getMRIDEQ.add("GeographicalRegion");
        getMRIDEQ.add("PostLineSensor");
        getMRIDEQ.add("RatioTapChangerTable");
        getMRIDEQ.add("EquivalentShunt");
        getMRIDEQ.add("ConformLoad");
        getMRIDEQ.add("HydroPump");
        getMRIDEQ.add("WindPowerPlant");
        getMRIDEQ.add("NonConformLoadGroup");
        getMRIDEQ.add("Breaker");
        getMRIDEQ.add("DCTerminal");
        getMRIDEQ.add("DCLine");
        getMRIDEQ.add("DCChopper");
        getMRIDEQ.add("Fuse");
        getMRIDEQ.add("DCGround");
        getMRIDEQ.add("SubGeographicalRegion");
        getMRIDEQ.add("LoadResponseCharacteristic");
        getMRIDEQ.add("PotentialTransformer");
        getMRIDEQ.add("DayType");
        getMRIDEQ.add("FossilFuel");
        getMRIDEQ.add("ConnectivityNode");
        getMRIDEQ.add("CurrentLimit");
        getMRIDEQ.add("SolarGeneratingUnit");
        getMRIDEQ.add("WindGeneratingUnit");
        getMRIDEQ.add("StaticVarCompensator");
        getMRIDEQ.add("DCDisconnector");
        getMRIDEQ.add("SeriesCompensator");
        getMRIDEQ.add("RegulatingControl");
        getMRIDEQ.add("BatteryUnit");
        getMRIDEQ.add("LinearShuntCompensator");
        getMRIDEQ.add("LoadArea");
        getMRIDEQ.add("FaultIndicator");
        getMRIDEQ.add("TapChangerControl");
        getMRIDEQ.add("EnergySource");
        getMRIDEQ.add("BusbarSection");
        getMRIDEQ.add("DCShunt");
        getMRIDEQ.add("RegulationSchedule");
        getMRIDEQ.add("OperationalLimitType");
        getMRIDEQ.add("DCBreaker");
        getMRIDEQ.add("Terminal");
        getMRIDEQ.add("PhaseTapChangerLinear");
        getMRIDEQ.add("VoltageLimit");
        getMRIDEQ.add("NuclearGeneratingUnit");
        getMRIDEQ.add("DCLineSegment");
        getMRIDEQ.add("DCNode");
        getMRIDEQ.add("ACDCConverterDCTerminal");
        getMRIDEQ.add("Switch");
        getMRIDEQ.add("Clamp");
        getMRIDEQ.add("Season");
        getMRIDEQ.add("SynchronousMachine");
        getMRIDEQ.add("EquivalentBranch");
        getMRIDEQ.add("ConformLoadGroup");
        getMRIDEQ.add("BaseVoltage");
        getMRIDEQ.add("Disconnector");
        getMRIDEQ.add("SolarPowerPlant");
        getMRIDEQ.add("GroundingImpedance");
        getMRIDEQ.add("Cut");
        getMRIDEQ.add("ThermalGeneratingUnit");
        getMRIDEQ.add("GroundDisconnector");
        getMRIDEQ.add("ApparentPowerLimit");
        getMRIDEQ.add("RatioTapChanger");
        getMRIDEQ.add("PowerTransformer");
        getMRIDEQ.add("SurgeArrester");
        getMRIDEQ.add("EnergyConsumer");
        getMRIDEQ.add("BoundaryPoint");
        getMRIDEQ.add("CsConverter");
        getMRIDEQ.add("DCSeriesDevice");
        getMRIDEQ.add("ACLineSegment");
        getMRIDEQ.add("PhaseTapChangerAsymmetrical");
        getMRIDEQ.add("VsConverter");
        getMRIDEQ.add("CAESPlant");
        getMRIDEQ.add("NonlinearShuntCompensator");
        getMRIDEQ.add("HydroPowerPlant");
        getMRIDEQ.add("PowerElectronicsWindUnit");
        getMRIDEQ.add("PowerElectronicsConnection");
        getMRIDEQ.add("VoltageLevel");
        getMRIDEQ.add("DCSwitch");
        getMRIDEQ.add("EquivalentNetwork");
        getMRIDEQ.add("ControlArea");
        getMRIDEQ.add("TieFlow");
        getMRIDEQ.add("CogenerationPlant");
        getMRIDEQ.add("EquivalentInjection");
        getMRIDEQ.add("DisconnectingCircuitBreaker");
        getMRIDEQ.add("DCBusbar");


        List<String> getEqInService = new LinkedList<>();
        getEqInService.add("ConformLoad");
        getEqInService.add("PotentialTransformer");
        getEqInService.add("Ground");
        getEqInService.add("HydroPump");
        getEqInService.add("WaveTrap");
        getEqInService.add("EnergyConsumer");
        getEqInService.add("DCChopper");
        getEqInService.add("PowerElectronicsWindUnit");
        getEqInService.add("PhotoVoltaicUnit");
        getEqInService.add("ExternalNetworkInjection");
        getEqInService.add("AsynchronousMachine");
        getEqInService.add("Jumper");
        getEqInService.add("CurrentTransformer");
        getEqInService.add("BatteryUnit");
        getEqInService.add("WindGeneratingUnit");
        getEqInService.add("EquivalentShunt");
        getEqInService.add("DCShunt");
        getEqInService.add("SynchronousMachine");
        getEqInService.add("EquivalentBranch");
        getEqInService.add("NonlinearShuntCompensator");
        getEqInService.add("ThermalGeneratingUnit");
        getEqInService.add("PostLineSensor");
        getEqInService.add("DCDisconnector");
        getEqInService.add("Switch");
        getEqInService.add("DCBusbar");
        getEqInService.add("CsConverter");
        getEqInService.add("Cut");
        getEqInService.add("Breaker");
        getEqInService.add("DCSwitch");
        getEqInService.add("SeriesCompensator");
        getEqInService.add("SurgeArrester");
        getEqInService.add("Fuse");
        getEqInService.add("SolarGeneratingUnit");
        getEqInService.add("StaticVarCompensator");
        getEqInService.add("DCGround");
        getEqInService.add("NonConformLoad");
        getEqInService.add("ACLineSegment");
        getEqInService.add("HydroGeneratingUnit");
        getEqInService.add("FaultIndicator");
        getEqInService.add("DCBreaker");
        getEqInService.add("VsConverter");
        getEqInService.add("LoadBreakSwitch");
        getEqInService.add("DCLineSegment");
        getEqInService.add("BusbarSection");
        getEqInService.add("Disconnector");
        getEqInService.add("GeneratingUnit");
        getEqInService.add("PowerTransformer");
        getEqInService.add("LinearShuntCompensator");
        getEqInService.add("DisconnectingCircuitBreaker");
        getEqInService.add("GroundDisconnector");
        getEqInService.add("PetersenCoil");
        getEqInService.add("Junction");
        getEqInService.add("PowerElectronicsConnection");
        getEqInService.add("EnergySource");
        getEqInService.add("NuclearGeneratingUnit");
        getEqInService.add("GroundingImpedance");
        getEqInService.add("StationSupply");
        getEqInService.add("Clamp");
        getEqInService.add("EquivalentInjection");

        List<String> getSvStInService = new LinkedList<>();
        getSvStInService.add("NonConformLoad");
        getSvStInService.add("Jumper");
        getSvStInService.add("EquivalentShunt");
        getSvStInService.add("Fuse");
        getSvStInService.add("Cut");
        getSvStInService.add("Junction");
        getSvStInService.add("PowerElectronicsConnection");
        getSvStInService.add("CsConverter");
        getSvStInService.add("EnergyConsumer");
        getSvStInService.add("AsynchronousMachine");
        getSvStInService.add("SynchronousMachine");
        getSvStInService.add("EquivalentBranch");
        getSvStInService.add("Clamp");
        getSvStInService.add("DisconnectingCircuitBreaker");
        getSvStInService.add("EquivalentInjection");
        getSvStInService.add("StationSupply");
        getSvStInService.add("BusbarSection");
        getSvStInService.add("ACLineSegment");
        getSvStInService.add("StaticVarCompensator");
        getSvStInService.add("Disconnector");
        getSvStInService.add("GroundingImpedance");
        getSvStInService.add("LoadBreakSwitch");
        getSvStInService.add("PowerTransformer");
        getSvStInService.add("PetersenCoil");
        getSvStInService.add("Switch");
        getSvStInService.add("LinearShuntCompensator");
        getSvStInService.add("Ground");
        getSvStInService.add("SeriesCompensator");
        getSvStInService.add("GroundDisconnector");
        getSvStInService.add("VsConverter");
        getSvStInService.add("Breaker");
        getSvStInService.add("EnergySource");
        getSvStInService.add("NonlinearShuntCompensator");
        getSvStInService.add("ExternalNetworkInjection");
        getSvStInService.add("ConformLoad");

        List<String> getDCratedUdc = new LinkedList<>();
        getDCratedUdc.add("DCDisconnector");
        getDCratedUdc.add("DCBreaker");
        getDCratedUdc.add("DCGround");
        getDCratedUdc.add("DCBusbar");
        getDCratedUdc.add("DCShunt");
        getDCratedUdc.add("DCLineSegment");
        getDCratedUdc.add("DCSeriesDevice");


        Resource newSub = null;
        Property newPre;
        RDFNode newObj;

        List<String> excludeTPBDattributes = new LinkedList<>();
        excludeTPBDattributes.add("TopologicalNode.fromEndName");
        excludeTPBDattributes.add("TopologicalNode.fromEndNameTso");
        excludeTPBDattributes.add("TopologicalNode.fromEndIsoCode");
        excludeTPBDattributes.add("TopologicalNode.toEndName");
        excludeTPBDattributes.add("TopologicalNode.toEndNameTso");
        excludeTPBDattributes.add("TopologicalNode.toEndIsoCode");
        excludeTPBDattributes.add("TopologicalNode.boundaryPoint");

        if (eqOnly == 0) {
            //convert SV
            for (StmtIterator c = modelSV.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                String className = stmtC.getObject().asResource().getLocalName();
                if (!className.equals("FullModel")) {

                    int hasMRid = 0;

                    for (StmtIterator a = modelSV.listStatements(stmtC.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                        Statement stmtA = a.next();

                        Statement stmtArebase = rebaseStatement(stmtA, cim17NS, euNS);
                        newSub = stmtArebase.getSubject();
                        newPre = stmtArebase.getPredicate();
                        newObj = stmtArebase.getObject();

                        if (newPre.getLocalName().equals("VsConverter.uf")) {
                            newPre = ResourceFactory.createProperty(cim17NS, "VsConverter.uv");
                        }
                        convSVModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));

                        if (newPre.getLocalName().equals("IdentifiedObject.mRID")) {
                            hasMRid = 1;
                        }
                    }
                    //add mrid if not there
                    if (hasMRid == 0 && getMRIDSV.contains(stmtC.getObject().asResource().getLocalName())) {
                        convSVModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), mrid, ResourceFactory.createPlainLiteral(stmtC.getSubject().getLocalName().substring(1))));
                    }
                }
            }
            // add SvSwitch
            //check if in the EQ there is Switch or subclass in CGMES v2.4
            for (StmtIterator c = modelEQ.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                String className = stmtC.getObject().asResource().getLocalName();
                if (className.equals("Switch") || className.equals("GroundDisconnector") || className.equals("Disconnector") || className.equals("LoadBreakSwitch") || className.equals("Breaker")) {
                    //get status in SSH
                    Statement swopen = modelSSH.listStatements(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, "Switch.open"), (RDFNode) null).nextStatement();
                    //add .locked in SSH
                    convSSHModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "Switch.locked"), ResourceFactory.createPlainLiteral("false")));
                    //create the SvSwitch
                    //Resource clRes=rebaseResource(stmtC.getSubject(), cim17NS);
                    String uuidSvSwitch = String.valueOf(UUID.randomUUID());
                    Resource clRes = ResourceFactory.createResource(euNS + "_" + uuidSvSwitch);
                    convSVModel.add(ResourceFactory.createStatement(clRes, RDF.type, ResourceFactory.createProperty(cim17NS, "SvSwitch")));
                    convSVModel.add(ResourceFactory.createStatement(clRes, ResourceFactory.createProperty(cim17NS, "SvSwitch.open"), swopen.getObject()));
                    convSVModel.add(ResourceFactory.createStatement(clRes, ResourceFactory.createProperty(cim17NS, "SvSwitch.Switch"), ResourceFactory.createProperty(cim17NS, swopen.getSubject().getLocalName())));
                }
            }

            //clean up SvVoltage=0
            List<Statement> stmtDelete = new LinkedList<>();
            for (StmtIterator c = convSVModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cim17NS, "SvVoltage")); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                float voltage = convSVModel.getRequiredProperty(stmtC.getSubject(), ResourceFactory.createProperty(cim17NS, "SvVoltage.v")).getFloat();
                if (voltage == 0) {
                    stmtDelete.addAll(convSVModel.listStatements(stmtC.getSubject(), null, (RDFNode) null).toList());
                    Statement tnref = convSVModel.getRequiredProperty(stmtC.getSubject(), ResourceFactory.createProperty(cim17NS, "SvVoltage.TopologicalNode"));
                    if (convSVModel.contains(ResourceFactory.createStatement(stmtC.getSubject(), ResourceFactory.createProperty(cim17NS, "TopologicalIsland.TopologicalNodes"), tnref.getObject()))) {
                        stmtDelete.add(ResourceFactory.createStatement(stmtC.getSubject(), ResourceFactory.createProperty(cim17NS, "TopologicalIsland.TopologicalNodes"), tnref.getObject()));
                    }
                }
            }
            convSVModel.remove(stmtDelete);

            //TODO see if something should be done for SvPowerFlow = 0

            //convert TP

            for (StmtIterator c = modelTP.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                String className = stmtC.getObject().asResource().getLocalName();
                if (className.equals("Terminal")) {
                    RDFNode tnProp = modelTP.getRequiredProperty(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, "Terminal.TopologicalNode")).getObject();
                    Resource tnRes = tnProp.asResource();
                    if (modelTPBD.listStatements(tnRes, null, (RDFNode) null).hasNext()) {
                        Statement stmtArebase = null;
                        for (StmtIterator a = modelTPBD.listStatements(tnRes, null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                            Statement stmtA = a.next();
                            stmtArebase = rebaseStatement(stmtA, cim17NS, euNS);
                            if (!excludeTPBDattributes.contains(stmtA.getPredicate().getLocalName())) {
                                if (stmtA.getPredicate().getLocalName().equals(RDF.type.toString())) {
                                    convTPModel.add(stmtArebase.getSubject(), RDF.type, ResourceFactory.createProperty(cim17NS, "TopologicalNode"));
                                } else {
                                    convTPModel.add(stmtArebase.getSubject(), stmtArebase.getPredicate(), stmtArebase.getObject());
                                }
                                convTPModel.add(stmtArebase.getSubject(), mrid, ResourceFactory.createPlainLiteral(stmtArebase.getSubject().getLocalName().split("_", 2)[1]));

                            }
                        }
                        Resource cnRes = modelTPBD.listStatements(null, ResourceFactory.createProperty(cim16NS, "ConnectivityNode.TopologicalNode"), tnProp).next().getSubject();
                        convTPModel.add(ResourceFactory.createResource(cim17NS + cnRes.getLocalName()), RDF.type, ResourceFactory.createProperty(cim17NS, "ConnectivityNode"));
                        convEQModel.add(stmtC.getSubject(), ResourceFactory.createProperty(cim17NS, "Terminal.ConnectivityNode"), ResourceFactory.createProperty(cim17NS, cnRes.getLocalName()));
                        assert stmtArebase != null;
                        convTPModel.add(ResourceFactory.createResource(cim17NS + cnRes.getLocalName()), ResourceFactory.createProperty(cim17NS, "ConnectivityNode.TopologicalNode"), stmtArebase.getSubject());
                    }
                }
            }
        }
        //check if EQ has ConnectivityNodes
        int hasCN = 0;
        if (modelEQ.listStatements(null, RDF.type, ResourceFactory.createProperty(cim16NS, "ConnectivityNode")).hasNext()) {
            hasCN = 1;
        }
        //check if EQ has DCNodes
        int hasDCN = 0;
        if (modelEQ.listStatements(null, RDF.type, ResourceFactory.createProperty(cim16NS, "DCNode")).hasNext()) {
            hasDCN = 1;
        }
        if (eqOnly == 0) {
            // convert TN of TP
            for (StmtIterator c = modelTP.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                String className = stmtC.getObject().asResource().getLocalName();
                if (!className.equals("FullModel")) {

                    int hasMRid = 0;
                    Resource newCNres = null;
                    Resource newDCNres = null;

                    if (hasCN == 0 && className.equals("TopologicalNode")) { // need to create CN and add it to EQ and link here in TP
                        String uuidCN = String.valueOf(UUID.randomUUID());
                        newCNres = ResourceFactory.createResource(cim17NS + "_" + uuidCN);
                        convEQModel.add(ResourceFactory.createStatement(newCNres, RDF.type, ResourceFactory.createProperty(cim17NS, "ConnectivityNode")));
                        convEQModel.add(ResourceFactory.createStatement(newCNres, mrid, ResourceFactory.createPlainLiteral(uuidCN)));
                        convTPModel.add(ResourceFactory.createStatement(newCNres, RDF.type, ResourceFactory.createProperty(cim17NS, "ConnectivityNode")));
                        convTPModel.add(ResourceFactory.createStatement(newCNres, ResourceFactory.createProperty(cim17NS, "ConnectivityNode.TopologicalNode"), ResourceFactory.createProperty(stmtC.getSubject().toString())));
                    }

                    if (hasDCN == 0 && className.equals("DCTopologicalNode")) { // need to create DCNobe and add it to EQ and link here in TP
                        String uuidDCN = String.valueOf(UUID.randomUUID());
                        newDCNres = ResourceFactory.createResource(cim17NS + "_" + uuidDCN);
                        convEQModel.add(ResourceFactory.createStatement(newDCNres, RDF.type, ResourceFactory.createProperty(cim17NS, "DCNode")));
                        convEQModel.add(ResourceFactory.createStatement(newDCNres, mrid, ResourceFactory.createPlainLiteral(uuidDCN)));
                        convTPModel.add(ResourceFactory.createStatement(newDCNres, RDF.type, ResourceFactory.createProperty(cim17NS, "DCNode")));
                        convTPModel.add(ResourceFactory.createStatement(newDCNres, ResourceFactory.createProperty(cim17NS, "DCNode.DCTopologicalNode"), ResourceFactory.createProperty(stmtC.getSubject().toString())));
                    }

                    for (StmtIterator a = modelTP.listStatements(stmtC.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                        Statement stmtA = a.next();

                        Statement stmtArebase = rebaseStatement(stmtA, cim17NS, euNS);
                        newSub = stmtArebase.getSubject();
                        newPre = stmtArebase.getPredicate();
                        newObj = stmtArebase.getObject();

                        convTPModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));

                        if (newPre.getLocalName().equals("IdentifiedObject.mRID")) {
                            hasMRid = 1;
                        }
                        if (hasCN == 0 && className.equals("TopologicalNode")) {
                            if (newPre.getLocalName().equals("IdentifiedObject.name")) {
                                convEQModel.add(ResourceFactory.createStatement(newCNres, newPre, newObj));
                            }
                            if (newPre.getLocalName().equals("TopologicalNode.ConnectivityNodeContainer")) {
                                convEQModel.add(ResourceFactory.createStatement(newCNres, ResourceFactory.createProperty(cim17NS, "ConnectivityNode.ConnectivityNodeContainer"), newObj));
                            }

                            for (StmtIterator t = modelTP.listStatements(null, ResourceFactory.createProperty(cim16NS, "Terminal.TopologicalNode"), stmtC.getSubject()); t.hasNext(); ) { // loop on all classes
                                Statement stmtT = t.next();
                                convEQModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cim17NS, "Terminal.ConnectivityNode"), ResourceFactory.createProperty(newCNres.toString())));
                            }
                        }


                        if (hasDCN == 0 && className.equals("DCTopologicalNode")) { //manage the container of the DCNode and the link with the Terminal
                            if (newPre.getLocalName().equals("IdentifiedObject.name")) {
                                convEQModel.add(ResourceFactory.createStatement(newDCNres, newPre, newObj));
                            }
                            if (newPre.getLocalName().equals("DCTopologicalNode.DCEquipmentContainer")) {
                                convEQModel.add(ResourceFactory.createStatement(newDCNres, ResourceFactory.createProperty(cim17NS, "DCNode.DCEquipmentContainer"), newObj));
                            }

                            for (StmtIterator t = modelTP.listStatements(null, ResourceFactory.createProperty(cim16NS, "DCTerminal.DCTopologicalNode"), stmtC.getSubject()); t.hasNext(); ) { // loop on all classes
                                Statement stmtT = t.next();
                                convEQModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cim17NS, "DCTerminal.DCNode"), ResourceFactory.createProperty(newDCNres.toString())));
                            }
                            for (StmtIterator t = modelTP.listStatements(null, ResourceFactory.createProperty(cim16NS, "ACDCConverterDCTerminal.DCTopologicalNode"), stmtC.getSubject()); t.hasNext(); ) { // loop on all classes
                                Statement stmtT = t.next();
                                convEQModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cim17NS, "ACDCConverterDCTerminal.DCNode"), ResourceFactory.createProperty(newDCNres.toString())));
                            }
                        }

                    }
                    //add mrid if not there
                    if (hasMRid == 0 && getMRIDTP.contains(stmtC.getObject().asResource().getLocalName())) {
                        convTPModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), mrid, ResourceFactory.createPlainLiteral(stmtC.getSubject().getLocalName().substring(1))));
                    }
                }
            }

            //convert SSH
            for (StmtIterator c = modelSSH.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();
                String className = stmtC.getObject().asResource().getLocalName();
                if (!className.equals("FullModel")) {
                    int hasMRid = 0;

                    for (StmtIterator a = modelSSH.listStatements(stmtC.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                        Statement stmtA = a.next();

                        Statement stmtArebase = rebaseStatement(stmtA, cim17NS, euNS);
                        newSub = stmtArebase.getSubject();
                        newPre = stmtArebase.getPredicate();
                        newObj = stmtArebase.getObject();

                        if (className.equals("EquivalentInjection") && (newPre.getLocalName().equals("EquivalentInjection.regulationStatus") || newPre.getLocalName().equals("EquivalentInjection.regulationTarget"))) {
                            if (modelEQ.listStatements(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, "EquivalentInjection.regulationCapability"), (RDFNode) null).hasNext()) {
                                String regulationCapability = modelEQ.listStatements(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, "EquivalentInjection.regulationCapability"), (RDFNode) null).next().getObject().toString();
                                if (regulationCapability.equals("true")) {
                                    convSSHModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                }
                            }
                        } else {
                            convSSHModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                        }

                        if (newPre.getLocalName().equals("IdentifiedObject.mRID")) {
                            hasMRid = 1;
                        }
                    }
                    //add mrid if not there
                    if (hasMRid == 0 && getMRIDSSH.contains(stmtC.getObject().asResource().getLocalName())) {
                        convSSHModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), mrid, ResourceFactory.createPlainLiteral(stmtC.getSubject().getLocalName().substring(1))));
                    }
                }
            }
            //add limits to SSH
            for (StmtIterator c = modelEQ.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
                Statement stmtC = c.next();

                String className = stmtC.getObject().asResource().getLocalName();
                if (className.equals("VoltageLimit") || className.equals("CurrentLimit") || className.equals("ApparentPowerLimit") || className.equals("ActivePowerLimit")) {
                    convSSHModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), RDF.type, rebaseRDFNode(stmtC.getObject(), cim17NS)));
                    RDFNode limvalue = modelEQ.getRequiredProperty(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, className + ".value")).getObject();
                    convSSHModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, className + ".value"), limvalue));
                }
            }
        }


        //convert EQ
        for (StmtIterator c = modelEQ.listStatements(null, RDF.type, (RDFNode) null); c.hasNext(); ) { // loop on all classes
            Statement stmtC = c.next();
            String className = stmtC.getObject().asResource().getLocalName();
            if (!className.equals("FullModel")) {
                int hasMRid = 0;
                int hasDir = 0;
                int hasTerSeqNum = 0;
                int hasContainment = 0;

                //check if there is ratedUdc attribute
                boolean hasratedUdc = false;
                boolean addDefaultratedUdc = false;
                if (getDCratedUdc.contains(className)) {
                    for (StmtIterator adc = modelEQ.listStatements(stmtC.getSubject(), null, (RDFNode) null); adc.hasNext(); ) { // loop on all attributes
                        Statement stmtAdc = adc.next();
                        if (stmtAdc.getPredicate().getLocalName().contains(".ratedUdc")) {
                            hasratedUdc = true;
                            break;
                        }
                    }
                    if (!hasratedUdc){// the original data does not have ratedUdc, add default
                        addDefaultratedUdc=true;
                    }
                }

                for (StmtIterator a = modelEQ.listStatements(stmtC.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                    Statement stmtA = a.next();

                    Statement stmtArebase = rebaseStatement(stmtA, cim17NS, euNS);
                    newSub = stmtArebase.getSubject();
                    newPre = stmtArebase.getPredicate();
                    newObj = stmtArebase.getObject();

                    //fix operational limits
                    if (className.equals("VoltageLimit") || className.equals("CurrentLimit") || className.equals("ApparentPowerLimit") || className.equals("ActivePowerLimit")) {
                        if (newPre.getLocalName().contains(".value")) {
                            newPre = ResourceFactory.createProperty(cim17NS, className + ".normalValue");
                        }
                    }

                    //fix operational limit type
                    if (className.equals("OperationalLimitType")) {
                        if (newPre.getLocalName().equals("OperationalLimitType.limitType")) {
                            newPre = ResourceFactory.createProperty(euNS, "OperationalLimitType.kind");
                            String kindType = null;
                            if (newObj.toString().contains(".patl")) {
                                kindType = ".patl";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("true")));
                            } else if (newObj.toString().contains(".patlt")) {
                                kindType = ".patlt";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("false")));
                            } else if (newObj.toString().contains(".tatl")) {
                                kindType = ".tatl";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("false")));
                            } else if (newObj.toString().contains(".tc")) {
                                kindType = ".tc";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("false")));
                            } else if (newObj.toString().contains(".tct")) {
                                kindType = ".tct";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("false")));
                            } else if (newObj.toString().contains(".highVoltage")) {
                                kindType = ".highVoltage";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("true")));
                            } else if (newObj.toString().contains(".lowVoltage")) {
                                kindType = ".lowVoltage";
                                convEQModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitType.isInfiniteDuration"), ResourceFactory.createPlainLiteral("true")));
                            }
                            newObj = ResourceFactory.createProperty(euNS, "LimitKind" + kindType);
                        }
                    }

                    //fix DC classes ratedUdc; move the value to DCConductingEquipment
                    if (getDCratedUdc.contains(className)) {
                        if (newPre.getLocalName().contains(".ratedUdc")) {
                            convEQModel.add(newSub, ResourceFactory.createProperty(cim17NS, "DCConductingEquipment.ratedUdc"), newObj);
                        }
                    }

                    //fix operational limit set
                    if (className.equals("OperationalLimitSet")) {
                        if (newPre.getLocalName().equals("OperationalLimitSet.Equipment")) {
                            if (!modelEQ.listStatements(stmtA.getSubject(), ResourceFactory.createProperty(cim16NS, "OperationalLimitSet.Terminal"), (RDFNode) null).hasNext()) {
                                Resource termRes = modelEQ.listStatements(null, ResourceFactory.createProperty(cim16NS, "Terminal.ConductingEquipment"), stmtA.getObject()).next().getSubject();
                                convEQModel.add(newSub, ResourceFactory.createProperty(cim17NS, "OperationalLimitSet.Terminal"), ResourceFactory.createProperty(cim17NS, termRes.getLocalName()));
                            }

                        }

                    }

                    if (newPre.getLocalName().equals("Equipment.aggregate")) {
                        if (!className.equals("EquivalentBranch") && !className.equals("EquivalentShunt") && !className.equals("EquivalentInjection")) {
                            convEQModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                        }
                    } else if (getDCratedUdc.contains(className)){
                        if (!newPre.getLocalName().contains(".ratedUdc")) {
                            convEQModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                        }
                    } else {
                        convEQModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                    }

                    if (newPre.getLocalName().equals("IdentifiedObject.mRID")) {
                        hasMRid = 1;
                    }
                    if (newPre.getLocalName().equals("OperationalLimitType.direction")) {
                        hasDir = 1;
                    }
                    if (newPre.getLocalName().equals("ACDCTerminal.sequenceNumber")) {
                        hasTerSeqNum = 1;
                    }
                    if (newPre.getLocalName().equals("Equipment.EquipmentContainer")) {
                        hasContainment = 1;
                    }

                }

                //add default the ratedUdc to DCConductingEquipment
                //TODO may need to request user input
                if (addDefaultratedUdc){
                    convEQModel.add(newSub, ResourceFactory.createProperty(cim17NS, "DCConductingEquipment.ratedUdc"), ResourceFactory.createPlainLiteral("320"));
                    System.out.print("WARNING: Default value for ratedUdc was added to the class: "+className+ " with ID: "+ newSub.getLocalName()+".\n");
                }

                assert newSub != null;
                //add Equipment.inservice to SSH
                if (eqOnly == 0) {
                    if (getEqInService.contains(className)) {
                        //if contains SvStatus add Equipment.inService with the same status
                        if (modelSV.contains(ResourceFactory.createResource(cim16NS + newSub.getLocalName()), ResourceFactory.createProperty(cim16NS, "SvStatus.inService"))) {
                            Statement oldObj = modelSV.getRequiredProperty(ResourceFactory.createResource(cim16NS + newSub.getLocalName()), ResourceFactory.createProperty(cim16NS, "SvStatus.inService"));
                            if (!modelSSH.listStatements(null, RDF.type, ResourceFactory.createProperty(cim16NS, className)).hasNext()) {
                                convSSHModel.add(ResourceFactory.createStatement(newSub, RDF.type, ResourceFactory.createProperty(cim17NS, "Equipment")));
                            }
                            convSSHModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "Equipment.inService"), oldObj.getObject()));
                        } else {
                            //if it does not contain SvStatus, check ACDCTerminal.connected in SSH. If 1 or 2 Terminal device => connected false means inservice false; if 3 terminals if 2 terminals are true then inservice is true
                            //if Terminal.connected is true check is there is Switch and check the switch status. But the switch has to be related to the equipment
                            if (hasCN == 1) { // there are ConnectivityNodes in EQ
                                List<Statement> stmtList = modelEQ.listStatements(null, ResourceFactory.createProperty(cim16NS, "Terminal.ConductingEquipment"), ResourceFactory.createProperty(cim16NS + newSub.getLocalName())).toList();
                                int countTerm = stmtList.size();
                                if (countTerm == 1) {
                                    Resource termRes = stmtList.get(0).getSubject();
                                } else if (countTerm == 2) {

                                } else if (countTerm > 2) {

                                }
                            } else {

                            }
                        }


                        if (!modelSSH.listStatements(null, RDF.type, ResourceFactory.createProperty(cim16NS, className)).hasNext()) {
                            convSSHModel.add(ResourceFactory.createStatement(newSub, RDF.type, ResourceFactory.createProperty(cim17NS, "Equipment")));
                        }
                        convSSHModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(cim17NS, "Equipment.inService"), ResourceFactory.createPlainLiteral("true")));
                    }
                }

                //add SvStatus.inservice to SV
                if (eqOnly == 0) {
                    if (getSvStInService.contains(className)) {
                        //if (!modelSV.contains(ResourceFactory.createResource(cim16NS+newSub.getLocalName()),ResourceFactory.createProperty(cim16NS, "SvStatus.inService"))) {
                        if (!modelSV.listStatements(null, ResourceFactory.createProperty(cim16NS, "SvStatus.ConductingEquipment"), ResourceFactory.createProperty(newSub.toString())).hasNext()) {
                            //Statement oldObj = modelSV.getRequiredProperty(ResourceFactory.createResource(cim16NS+newSub.getLocalName()),ResourceFactory.createProperty(cim16NS, "SvStatus.inService"));
                            //Resource newSvStatusres = ResourceFactory.createResource(cim17NS + newSub.getLocalName());
                            //convSVModel.add(ResourceFactory.createStatement(newSvStatusres, RDF.type, ResourceFactory.createProperty(cim17NS, "SvStatus")));
                            //convSVModel.add(ResourceFactory.createStatement(newSvStatusres, ResourceFactory.createProperty(cim17NS, "SvStatus.inService"), oldObj.getObject()));
                            //convSVModel.add(ResourceFactory.createStatement(newSvStatusres, ResourceFactory.createProperty(cim17NS, "SvStatus.ConductingEquipment"), ResourceFactory.createProperty(newSub.toString())));
                            // }else {
                            String uuidSvStatus = String.valueOf(UUID.randomUUID());
                            Resource newSvStatusres = ResourceFactory.createResource(cim17NS + "_" + uuidSvStatus);
                            convSVModel.add(ResourceFactory.createStatement(newSvStatusres, RDF.type, ResourceFactory.createProperty(cim17NS, "SvStatus")));
                            convSVModel.add(ResourceFactory.createStatement(newSvStatusres, ResourceFactory.createProperty(cim17NS, "SvStatus.inService"), ResourceFactory.createPlainLiteral("true")));
                            convSVModel.add(ResourceFactory.createStatement(newSvStatusres, ResourceFactory.createProperty(cim17NS, "SvStatus.ConductingEquipment"), ResourceFactory.createProperty(newSub.toString())));
                        }
                    }
                }
                //add mrid if not there
                if (hasMRid == 0 && getMRIDEQ.contains(stmtC.getObject().asResource().getLocalName())) {
                    convEQModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), mrid, ResourceFactory.createPlainLiteral(stmtC.getSubject().getLocalName().substring(1))));
                }
                //add OperationalLimitType.direction if not there
                if (hasDir == 0 && className.equals("OperationalLimitType")) {
                    convEQModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "OperationalLimitType.direction"), ResourceFactory.createProperty(cim17NS, "OperationalLimitDirectionKind.absoluteValue")));
                }

                //add ACDCTerminal.sequenceNumber if not there
                if (className.equals("Terminal")) {
                    if (hasTerSeqNum == 0) {
                        Statement condEQ = modelEQ.getRequiredProperty(stmtC.getSubject(), ResourceFactory.createProperty(cim16NS, "Terminal.ConductingEquipment"));
                        List<Statement> terminals = modelEQ.listStatements(null, ResourceFactory.createProperty(cim16NS, "Terminal.ConductingEquipment"), condEQ.getObject()).toList();
                        if (terminals.size() == 1) {
                            convEQModel.add(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral("1"));

                        } else {
                            Statement condEQnameStmt = modelEQ.getRequiredProperty(condEQ.getObject().asResource(), RDF.type);
                            String condEQname = condEQnameStmt.getObject().asResource().getLocalName();
                            if (condEQname.equals("PowerTransformer")) {
                                for (StmtIterator pt = modelEQ.listStatements(null, ResourceFactory.createProperty(cim16NS, "PowerTransformerEnd.PowerTransformer"), condEQ.getObject()); pt.hasNext(); ) { // loop on all classes
                                    Statement stmtPT = pt.next();
                                    RDFNode endTerminal = modelEQ.getRequiredProperty(stmtPT.getSubject(), ResourceFactory.createProperty(cim16NS, "TransformerEnd.Terminal")).getObject();
                                    if (endTerminal.asResource().getLocalName().equals(stmtC.getSubject().getLocalName())) {
                                        String endnumber = modelEQ.getRequiredProperty(stmtPT.getSubject(), ResourceFactory.createProperty(cim16NS, "TransformerEnd.endNumber")).getObject().toString();
                                        convEQModel.add(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral(endnumber));
                                    }
                                }
                            } else {
                                int count = 1;
                                for (Statement st : terminals) {
                                    if (!convEQModel.listStatements(st.getSubject(), ResourceFactory.createProperty(cim17NS, "ACDCTerminal.sequenceNumber"), (RDFNode) null).hasNext()) {
                                        convEQModel.add(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral(Integer.toString(count)));
                                    }
                                    count = count + 1;
                                }
                            }
                        }
                    }
                }

                //Add line is missing' also assumes that there are no lines in the model
                if (className.equals("ACLineSegment")) {
                    if (hasContainment == 0) {

                        String uuidLine = String.valueOf(UUID.randomUUID());
                        Resource newLineres = ResourceFactory.createResource(cim17NS + "_" + uuidLine);
                        convEQModel.add(ResourceFactory.createStatement(newLineres, RDF.type, ResourceFactory.createProperty(cim17NS, "Line")));
                        convEQModel.add(ResourceFactory.createStatement(newLineres, ResourceFactory.createProperty(cim17NS, "IdentifiedObject.name"), ResourceFactory.createPlainLiteral("new line")));
                        convEQModel.add(ResourceFactory.createStatement(newLineres, mrid, ResourceFactory.createPlainLiteral(uuidLine)));

                        convEQModel.add(ResourceFactory.createStatement(rebaseResource(stmtC.getSubject(), cim17NS), ResourceFactory.createProperty(cim17NS, "Equipment.EquipmentContainer"), ResourceFactory.createProperty(newLineres.toString())));

                    }
                }
            }
        }

        //fix RegulatingControl targers - voltage
        if (fixRegCont == 1) {
            List<Resource> processedRC = new LinkedList<>();
            for (StmtIterator rc = convEQModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cim17NS,"RegulatingControl")); rc.hasNext(); ) { // loop on RegulatingControl classes
                Statement stmtRC = rc.next();
                if (processedRC.isEmpty() || !processedRC.contains(stmtRC.getSubject())) {
                    List<Resource> relatedRegCont = new LinkedList<>();
                    List<Float> controltarget = new LinkedList<>();
                    String mode = convEQModel.getRequiredProperty(stmtRC.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.mode")).getObject().asResource().getLocalName();
                    if (mode.equals("RegulatingControlModeKind.voltage")) {
                        //check if the control is enabled
                        if (convSSHModel.getRequiredProperty(stmtRC.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.enabled")).getObject().asLiteral().getString().equals("true")) {
                            relatedRegCont.add(stmtRC.getSubject());
                            processedRC.add(stmtRC.getSubject());
                            float voltagetarget = convSSHModel.getRequiredProperty(stmtRC.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.targetValue")).getObject().asLiteral().getFloat();
                            controltarget.add(voltagetarget);

                            // check for other Regulating controls related to this TN
                            List<Resource> relatedRegContTemp = null;
                            List<Float> controltargetTemp;
                            for (StmtIterator tntp = convTPModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cim17NS,"TopologicalNode")); tntp.hasNext(); ) { // loop on TopologicalNode classes
                                Statement stmtTN = tntp.next();
                                relatedRegContTemp = new LinkedList<>();
                                controltargetTemp = new LinkedList<>();
                                //loop on all CN that connect to this TN
                                for (StmtIterator cn = convTPModel.listStatements(null, ResourceFactory.createProperty(cim17NS,"ConnectivityNode.TopologicalNode"), stmtTN.getSubject()); cn.hasNext(); ) { // loop on CN
                                    Statement stmtCN = cn.next();
                                    //loop on all terminals that connect to this TN
                                    for (StmtIterator term = convEQModel.listStatements(null, ResourceFactory.createProperty(cim17NS,"Terminal.ConnectivityNode"), stmtCN.getSubject()); term.hasNext(); ) { // loop on Terminals
                                        Statement stmterm = term.next();
                                        //for each of Terminals that relate to the TN
                                        //then get the enabled Reg controls that are with mode voltage,
                                        for (StmtIterator rc1 = convEQModel.listStatements(null, ResourceFactory.createProperty(cim17NS,"RegulatingControl.Terminal"), stmterm.getSubject()); rc1.hasNext(); ) { // loop on RC
                                            Statement stmtRC1 = rc1.next();
                                            mode = convEQModel.getRequiredProperty(stmtRC1.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.mode")).getObject().asResource().getLocalName();
                                            if (mode.equals("RegulatingControlModeKind.voltage")) {
                                                //check if the control is enabled
                                                if (convSSHModel.getRequiredProperty(stmtRC1.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.enabled")).getObject().asLiteral().getString().equals("true")) {
                                                    relatedRegContTemp.add(stmtRC1.getSubject());
                                                    float voltagetarget1 = convSSHModel.getRequiredProperty(stmtRC1.getSubject(), ResourceFactory.createProperty(cim17NS, "RegulatingControl.targetValue")).getObject().asLiteral().getFloat();
                                                    controltargetTemp.add(voltagetarget1);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // if one control: For the TN: - do nothing as the single control was added to the list already
                            // if more controls compare target values
                            if (relatedRegContTemp.size()>1){
                                //TODO compare targets, save in the right lists
                            }
                        }
                    }
                }
            }

        }

        //add the model to the map
        convertedModelMap.put("EQ", convEQModel);
        if (eqOnly == 0) {
            convertedModelMap.put("SSH", convSSHModel);
            convertedModelMap.put("SV", convSVModel);
            convertedModelMap.put("TP", convTPModel);
        }

        //save
        saveInstanceModelData(convertedModelMap, saveProperties, "CGMESv3.0");

    }

    //Split boundary per TSO border
    public static void SplitBoundaryPerBorder() throws IOException {


        Map<String, Map> loadDataMap = new HashMap<>();
        String xmlBase = "http://iec.ch/TC57/CIM100";
        //String xmlBase = "";

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;


        Map<String, ArrayList<Object>> profileDataMap = new HashMap<>();
        Map<String, Model> profileDataMapAsModel = new HashMap<>();

        // load all profile models
        Map<String, Model> profileModelMap = null;


        loadDataMap.put("profileDataMap", profileDataMap);
        loadDataMap.put("profileDataMapAsModel", profileDataMapAsModel);
        loadDataMap.put("profileModelMap", profileModelMap);

        // load base instance models
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Boundary equipment", "*.xml"));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
        File file;

        try {
            file = filechooser.showOpenDialog(null);
        } catch (Exception k) {
            filechooser.setInitialDirectory(new File("C:\\\\"));
            file = filechooser.showOpenDialog(null);
        }
        List<File> baseInstanceModelFiles = new LinkedList<>();
        if (file != null) {// the file is selected

            baseInstanceModelFiles.add(file);

            Map<String, Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(baseInstanceModelFiles, xmlBase, null);
            loadDataMap.put("baseInstanceModelMap", baseInstanceModelMap);

        }
        Map<String, Model> instanceModelMap = loadDataMap.get("baseInstanceModelMap");
        Model instanceModelBD = null;
        //Model instanceModelBD = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (Map.Entry<String, Model> entry : instanceModelMap.entrySet()) {
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")) {
                if (entry.getKey().equals("EQBD")) {
                    instanceModelBD = entry.getValue();
                }
            }

        }

        Map<String, Model> newBDModelMap = new HashMap<>();

        assert instanceModelBD != null;
        for (ResIterator i = instanceModelBD.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint")); i.hasNext(); ) {
            Resource resItem = i.next();
            String fromTSOname = instanceModelBD.getRequiredProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint.fromEndNameTso")).getObject().toString();
            String toTSOname = instanceModelBD.getRequiredProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/CIM100-European#BoundaryPoint.toEndNameTso")).getObject().toString();

            if (newBDModelMap.containsKey("Border-" + fromTSOname + "-" + toTSOname + ".xml")) {
                Model borderModel = newBDModelMap.get("Border-" + fromTSOname + "-" + toTSOname + ".xml");
                //add the BoundaryPoint
                borderModel = addBP(instanceModelBD, borderModel, resItem);
                newBDModelMap.put("Border-" + fromTSOname + "-" + toTSOname + ".xml", borderModel);

            } else if (newBDModelMap.containsKey("Border-" + toTSOname + "-" + fromTSOname + ".xml")) {
                Model borderModel = newBDModelMap.get("Border-" + toTSOname + "-" + fromTSOname + ".xml");
                //add the BoundaryPoint
                borderModel = addBP(instanceModelBD, borderModel, resItem);
                newBDModelMap.put("Border-" + toTSOname + "-" + fromTSOname + ".xml", borderModel);

            } else {
                Model newBoderModel = ModelFactory.createDefaultModel();
                newBoderModel.setNsPrefixes(instanceModelBD.getNsPrefixMap());
                //add the header statements
                Resource headerRes = ResourceFactory.createResource("urn:uuid:" + UUID.randomUUID());
                newBoderModel.add(ResourceFactory.createStatement(headerRes, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")));
                for (StmtIterator n = instanceModelBD.listStatements(instanceModelBD.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).nextResource(), null, (RDFNode) null); n.hasNext(); ) {
                    Statement stmt = n.next();
                    newBoderModel.add(ResourceFactory.createStatement(headerRes, stmt.getPredicate(), stmt.getObject()));
                }
                //add the BoundaryPoint
                newBoderModel = addBP(instanceModelBD, newBoderModel, resItem);
                //add the model to the map
                newBDModelMap.put("Border-" + fromTSOname + "-" + toTSOname + ".xml", newBoderModel);
            }
        }


        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, "CGMESv3.0");

    }

    //Split Boundary and Reference data (CGMES v3.0)
    public static void SplitBoundaryAndRefData() throws IOException {


        Map<String, Map> loadDataMap = new HashMap<>();
        String xmlBase = "http://iec.ch/TC57/CIM100";
        //String xmlBase = "";

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;


        Map<String, ArrayList<Object>> profileDataMap = new HashMap<>();
        Map<String, Model> profileDataMapAsModel = new HashMap<>();

        // load all profile models
        Map<String, Model> profileModelMap = null;


        loadDataMap.put("profileDataMap", profileDataMap);
        loadDataMap.put("profileDataMapAsModel", profileDataMapAsModel);
        loadDataMap.put("profileModelMap", profileModelMap);

        // load base instance models
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Boundary equipment", "*.xml"));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
        File file;

        try {
            file = filechooser.showOpenDialog(null);
        } catch (Exception k) {
            filechooser.setInitialDirectory(new File("C:\\\\"));
            file = filechooser.showOpenDialog(null);
        }
        List<File> baseInstanceModelFiles = new LinkedList<>();
        if (file != null) {// the file is selected

            baseInstanceModelFiles.add(file);

            Map<String, Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(baseInstanceModelFiles, xmlBase, null);
            loadDataMap.put("baseInstanceModelMap", baseInstanceModelMap);

        }
        Map<String, Model> instanceModelMap = loadDataMap.get("baseInstanceModelMap");
        Model instanceModelBD = null;
        //Model instanceModelBD = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (Map.Entry<String, Model> entry : instanceModelMap.entrySet()) {
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")) {
                if (entry.getKey().equals("EQBD")) {
                    instanceModelBD = entry.getValue();
                }
            }

        }

        List<String> keepInBD = new LinkedList<>();
        keepInBD.add("ConnectivityNode");
        keepInBD.add("BoundaryPoint");
        keepInBD.add("Line");
        keepInBD.add("Substation");
        keepInBD.add("VoltageLevel");
        keepInBD.add("Bay");
        keepInBD.add("Terminal");
        keepInBD.add("Junction");
        keepInBD.add("FullModel");

        Map<String, Model> newBDModelMap = new HashMap<>();

        //create the new model for BP
        Model newBoderModel = ModelFactory.createDefaultModel();
        newBoderModel.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
        newBoderModel.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
        newBoderModel.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newBoderModel.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        //create the new model for ref data
        Model newRefModel = ModelFactory.createDefaultModel();
        newRefModel.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
        newRefModel.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
        newRefModel.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newRefModel.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        assert instanceModelBD != null;
        Map<String, String> oldPrefix = instanceModelBD.getNsPrefixMap();
        int keepExtensions = 1; // keep extensions
        if (oldPrefix.containsKey("cgmbp")) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setContentText("The Boundary Set includes non CIM (cgmbp) extensions. Do you want to keep them in the split Boundary Set?");
            alert.setHeaderText(null);
            alert.setTitle("Question - cgmbp extensions are present");
            ButtonType btnYes = new ButtonType("Yes");
            ButtonType btnNo = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(btnYes, btnNo);
            Optional<ButtonType> result = alert.showAndWait();

            if (result.get() == btnNo) {
                keepExtensions = 0;
            } else {
                if (oldPrefix.containsKey("cgmbp")) {
                    newBoderModel.setNsPrefix("cgmbp", "http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                    newRefModel.setNsPrefix("cgmbp", "http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                }
            }
        }


        for (StmtIterator i = instanceModelBD.listStatements(null, RDF.type, (RDFNode) null); i.hasNext(); ) {
            Statement stmt = i.next();
            if (keepExtensions == 1) {
                if (keepInBD.contains(stmt.getObject().asResource().getLocalName())) {
                    for (StmtIterator k = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                        Statement stmtKeep = k.next();
                        newBoderModel.add(stmtKeep);
                        if (stmt.getObject().asResource().getLocalName().equals("FullModel")) {
                            newRefModel.add(stmtKeep);
                        }
                    }
                } else {
                    for (StmtIterator r = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); r.hasNext(); ) {
                        Statement stmtRef = r.next();
                        newRefModel.add(stmtRef);
                    }
                }
            } else {
                if (!stmt.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                    if (keepInBD.contains(stmt.getObject().asResource().getLocalName())) {
                        for (StmtIterator k = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                            Statement stmtKeep = k.next();
                            if (!stmtKeep.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                                newBoderModel.add(stmtKeep);
                                if (stmt.getObject().asResource().getLocalName().equals("FullModel")) {
                                    newRefModel.add(stmtKeep);
                                }
                            }
                        }
                    } else {
                        for (StmtIterator r = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); r.hasNext(); ) {
                            Statement stmtRef = r.next();
                            if (!stmtRef.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                                newRefModel.add(stmtRef);
                            }
                        }
                    }
                }
            }
        }


        newBDModelMap.put("BoundaryData.xml", newBoderModel);
        newBDModelMap.put("ReferenceData.xml", newRefModel);
        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, "CGMESv3.0");

    }

    // Convert CGMES v2.4 Boundary Set to CGMES v3.0
    public static void ConvertBoundarySetCGMESv2v3() throws IOException {


        Map<String, Map> loadDataMap = new HashMap<>();
        String xmlBase = "http://iec.ch/TC57/CIM100";
        //String xmlBase = "";

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");

        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;


        Map<String, ArrayList<Object>> profileDataMap = new HashMap<>();
        Map<String, Model> profileDataMapAsModel = new HashMap<>();
        //Map<String,Model> conversionInstruction=new HashMap<>();

        // load all profile models
        Map<String, Model> profileModelMap = null;


        loadDataMap.put("profileDataMap", profileDataMap);
        loadDataMap.put("profileDataMapAsModel", profileDataMapAsModel);
        loadDataMap.put("profileModelMap", profileModelMap);
        loadDataMap.put("conversionInstruction", profileModelMap);


        //xmlBase = "http://iec.ch/TC57/2013/CIM-schema-cim16";
        // load base instance models
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Boundary equipment", "*.xml"));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
        File file;

        try {
            file = filechooser.showOpenDialog(null);
        } catch (Exception k) {
            filechooser.setInitialDirectory(new File("C:\\\\"));
            file = filechooser.showOpenDialog(null);
        }
        List<File> baseInstanceModelFiles = new LinkedList<>();
        if (file != null) {// the file is selected

            baseInstanceModelFiles.add(file);

            Map<String, Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(baseInstanceModelFiles, xmlBase, null);
            loadDataMap.put("baseInstanceModelMap", baseInstanceModelMap);

        }
        Map<String, Model> instanceModelMap = loadDataMap.get("baseInstanceModelMap");
        Model instanceModelBD = null;
        //Model instanceModelBD = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (Map.Entry<String, Model> entry : instanceModelMap.entrySet()) {
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")) {
                if (entry.getKey().equals("EQBD")) {
                    instanceModelBD = entry.getValue();
                }
            }

        }

        Map<String, Model> newBDModelMap = new HashMap<>();

        //create the new model
        Model newBoderModel = ModelFactory.createDefaultModel();
        newBoderModel.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
        newBoderModel.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
        newBoderModel.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        newBoderModel.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        //check for extensions

        assert instanceModelBD != null;
        Map<String, String> oldPrefix = instanceModelBD.getNsPrefixMap();
        int keepExtensions = 1; // keep extensions
        if (oldPrefix.containsKey("cgmbp")) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setContentText("The Boundary Set includes non CIM (cgmbp) extensions. Do you want to keep them in the converted Boundary Set?");
            alert.setHeaderText(null);
            alert.setTitle("Question - cgmbp extensions are present");
            ButtonType btnYes = new ButtonType("Yes");
            ButtonType btnNo = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(btnYes, btnNo);
            Optional<ButtonType> result = alert.showAndWait();

            if (result.get() == btnNo) {
                keepExtensions = 0;
            } else {
                if (oldPrefix.containsKey("cgmbp")) {
                    newBoderModel.setNsPrefix("cgmbp", "http://entsoe.eu/CIM/Extensions/CGM-BP/2020#");
                }
            }
        }

        //conversion process
        String cim17NS = "http://iec.ch/TC57/CIM100#";
        String cim16NS = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
        String euNS = "http://iec.ch/TC57/CIM100-European#";
        List<String> skipList = new LinkedList<>();
        skipList.add("ConnectivityNode.boundaryPoint");
        List<String> getMRID = new LinkedList<>();
        getMRID.add("ConnectivityNode");
        getMRID.add("Line");
        getMRID.add("EnergySchedulingType");
        getMRID.add("GeographicalRegion");
        getMRID.add("SubGeographicalRegion");
        getMRID.add("Terminal");
        getMRID.add("Substation");
        getMRID.add("BaseVoltage");
        getMRID.add("VoltageLevel");
        getMRID.add("Bay");
        getMRID.add("Junction");

        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");


        for (StmtIterator i = instanceModelBD.listStatements(null, RDF.type, (RDFNode) null); i.hasNext(); ) { // loop on all classes
            Statement stmt = i.next();

            //add the header statements
            if (stmt.getObject().asResource().getLocalName().equals("FullModel")) {
                for (StmtIterator n = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); n.hasNext(); ) {
                    Statement stmtH = n.next();
                    if (stmtH.getPredicate().getLocalName().equals("Model.profile")) {
                        newBoderModel.add(ResourceFactory.createStatement(stmtH.getSubject(), stmtH.getPredicate(), ResourceFactory.createPlainLiteral("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0")));
                    } else {
                        newBoderModel.add(stmtH);
                    }
                }
            } else {
                Resource newSub;
                Property newPre;
                RDFNode newObj;
                Resource newBPres = null;

                if (stmt.getObject().asResource().getLocalName().equals("ConnectivityNode")) {
                    //Create BoundaryPoint
                    String uuidBP = String.valueOf(UUID.randomUUID());
                    newBPres = ResourceFactory.createResource(euNS + "_" + uuidBP);
                    RDFNode euBP = ResourceFactory.createProperty(euNS, "BoundaryPoint");
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, RDF.type, euBP));
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, mrid, ResourceFactory.createPlainLiteral(uuidBP)));
                    newBoderModel.add(ResourceFactory.createStatement(newBPres, ResourceFactory.createProperty(euNS, "BoundaryPoint.ConnectivityNode"), ResourceFactory.createProperty(stmt.getSubject().toString())));
                }

                if (!stmt.getObject().asResource().getLocalName().equals("Junction") && !stmt.getObject().asResource().getLocalName().equals("Terminal")) {// this is to filter Junction and Terminal. TODO create option in GUT to make this more flexible
                    //Add Terminal.ConnectivityNode
                    if (stmt.getObject().asResource().getLocalName().equals("Junction")) {
                        Statement EqCont = instanceModelBD.listStatements(stmt.getSubject(), ResourceFactory.createProperty(cim16NS, "Equipment.EquipmentContainer"), (RDFNode) null).next();
                        Statement conNode = instanceModelBD.listStatements(null, ResourceFactory.createProperty(cim16NS, "ConnectivityNode.ConnectivityNodeContainer"), EqCont.getObject()).next();
                        Statement terminalJunction = instanceModelBD.listStatements(null, ResourceFactory.createProperty(cim16NS, "Terminal.ConductingEquipment"), stmt.getSubject()).next();
                        if (!instanceModelBD.listStatements(terminalJunction.getSubject(), ResourceFactory.createProperty(cim16NS, "Terminal.ConnectivityNode"), conNode.getSubject()).hasNext()) {
                            newBoderModel.add(terminalJunction.getSubject(), ResourceFactory.createProperty(cim17NS, "Terminal.ConnectivityNode"), conNode.getSubject());
                        }
                        if (!instanceModelBD.listStatements(terminalJunction.getSubject(), ResourceFactory.createProperty(cim16NS, "ACDCTerminal.sequenceNumber"), (RDFNode) null).hasNext()) {
                            newBoderModel.add(terminalJunction.getSubject(), ResourceFactory.createProperty(cim17NS, "ACDCTerminal.sequenceNumber"), ResourceFactory.createPlainLiteral("1"));
                        }
                    }
                    int addmrid = 1;

                    for (StmtIterator a = instanceModelBD.listStatements(stmt.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                        Statement stmtA = a.next();
                        if (!skipList.contains(stmtA.getPredicate().getLocalName())) {
                            if (stmtA.getSubject().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                                newSub = rebaseResource(stmtA.getSubject(), cim17NS);
                            } else if (stmtA.getSubject().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                                newSub = rebaseResource(stmtA.getSubject(), euNS);
                            } else {
                                newSub = stmtA.getSubject();
                            }
                            if (stmtA.getPredicate().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                                newPre = rebaseProperty(stmtA.getPredicate(), cim17NS);
                            } else if (stmtA.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                                newPre = rebaseProperty(stmtA.getPredicate(), euNS);
                            } else {
                                newPre = stmtA.getPredicate();
                            }
                            if (stmtA.getObject().isResource()) {
                                if (stmtA.getObject().asResource().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                                    newObj = rebaseRDFNode(stmtA.getObject(), cim17NS);
                                } else if (stmtA.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                                    newObj = rebaseRDFNode(stmtA.getObject(), euNS);
                                } else {
                                    newObj = stmtA.getObject();
                                }
                            } else {
                                newObj = stmtA.getObject();
                            }
                            if (stmt.getObject().asResource().getLocalName().equals("ConnectivityNode")) {
                                String stmtAPredicate = stmtA.getPredicate().getLocalName();
                                switch (stmtAPredicate) {
                                    case "ConnectivityNode.toEndName":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndName");
                                        newSub = newBPres;
                                        break;
                                    case "ConnectivityNode.fromEndName":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndName");
                                        newSub = newBPres;
                                        break;
                                    case "ConnectivityNode.toEndNameTso":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndNameTso");
                                        newSub = newBPres;
                                        break;
                                    case "ConnectivityNode.toEndIsoCode":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.toEndIsoCode");
                                        newSub = newBPres;
                                        break;
                                    case "ConnectivityNode.fromEndIsoCode":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndIsoCode");
                                        newSub = newBPres;
                                        break;
                                    case "ConnectivityNode.fromEndNameTso":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "BoundaryPoint.fromEndNameTso");
                                        newSub = newBPres;
                                        break;
                                    case "IdentifiedObject.name":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.name");
                                        newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                        newSub = newBPres;
                                        break;
                                    case "IdentifiedObject.description":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.description");
                                        newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                        newSub = newBPres;
                                        Resource lineSub = instanceModelBD.getRequiredProperty(stmtA.getSubject(), ResourceFactory.createProperty(cim16NS, "ConnectivityNode.ConnectivityNodeContainer")).getObject().asResource();
                                        String lineDesc = instanceModelBD.getRequiredProperty(lineSub, ResourceFactory.createProperty(cim16NS, "IdentifiedObject.description")).getObject().toString();
                                        if (lineDesc.contains("HVDC")) {
                                            newBoderModel.add(ResourceFactory.createStatement(newSub, ResourceFactory.createProperty(euNS, "BoundaryPoint.isDirectCurrent"), ResourceFactory.createPlainLiteral("true")));
                                        }
                                        break;
                                    case "IdentifiedObject.shortName":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.shortName");
                                        newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                        newSub = newBPres;
                                        break;
                                    case "IdentifiedObject.energyIdentCodeEic":
                                        newPre = ResourceFactory.createProperty(newPre.getNameSpace(), "IdentifiedObject.energyIdentCodeEic");
                                        newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                                        newSub = newBPres;
                                        break;
                                }
                            }


                            newBoderModel.add(ResourceFactory.createStatement(newSub, newPre, newObj));
                        }
                        if (getMRID.contains(stmt.getObject().asResource().getLocalName()) && addmrid == 1) {
                            newBoderModel.add(ResourceFactory.createStatement(stmt.getSubject(), mrid, ResourceFactory.createPlainLiteral(stmt.getSubject().getLocalName().substring(1))));
                            addmrid = 0;
                        }
                    }
                }

            }

        }

        //filter extensions
        List<Statement> StmtDeleteList = new LinkedList<>();
        int deleteClass;
        if (keepExtensions == 0) {
            for (StmtIterator i = newBoderModel.listStatements(null, RDF.type, (RDFNode) null); i.hasNext(); ) { // loop on all classes
                Statement stmt = i.next();
                deleteClass = 0;
                if (stmt.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                    StmtDeleteList.add(stmt);
                    deleteClass = 1;
                }
                for (StmtIterator a = newBoderModel.listStatements(stmt.getSubject(), null, (RDFNode) null); a.hasNext(); ) { // loop on all attributes
                    Statement stmtA = a.next();
                    if (deleteClass == 1) {
                        StmtDeleteList.add(stmtA);
                    } else {
                        if (stmtA.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/Extensions/CGM-BP/2020#")) {
                            StmtDeleteList.add(stmtA);
                        }
                    }
                }
            }
            newBoderModel.remove(StmtDeleteList);
        }


        //add the model to the map
        newBDModelMap.put("ConvertedBoundaryCGMESv3" + ".xml", newBoderModel);


        //save the borders
        saveInstanceModelData(newBDModelMap, saveProperties, "CGMESv3.0");

    }


    //add BP
    private static Model addBP(Model modelSource, Model newModel, Resource resItem) {
        for (StmtIterator bp = modelSource.listStatements(resItem, null, (RDFNode) null); bp.hasNext(); ) {
            Statement stmt = bp.next();
            newModel.add(stmt);
            // Add ConnectivityNode
            if (stmt.getPredicate().asResource().getLocalName().equals("BoundaryPoint.ConnectivityNode")) {
                for (StmtIterator cn = modelSource.listStatements(stmt.getObject().asResource(), null, (RDFNode) null); cn.hasNext(); ) {
                    Statement stmtcn = cn.next();
                    newModel.add(stmtcn);
                    //Add Line container
                    if (stmtcn.getPredicate().asResource().getLocalName().equals("ConnectivityNode.ConnectivityNodeContainer")) {
                        for (StmtIterator con = modelSource.listStatements(stmtcn.getObject().asResource(), null, (RDFNode) null); con.hasNext(); ) {
                            Statement stmtcon = con.next();
                            newModel.add(stmtcon);
                        }
                    }
                }
            }
        }
        return newModel;
    }


    //Create model
    private static Model createModel() {

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
        model.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
        model.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        model.setNsPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");

        return model;
    }

    private static Model createSimilarModel(Model origModel) {

        Model model = ModelFactory.createDefaultModel();
        Map<String,String> origPrefMap = origModel.getNsPrefixMap();
        model.setNsPrefixes(origPrefMap);
        return model;
    }

    //Replace namespace
    private static Property rebaseProperty(Property prop, String newBase) {

        return ResourceFactory.createProperty(newBase + prop.getLocalName());
    }

    //Replace namespace
    private static RDFNode rebaseRDFNode(RDFNode prop, String newBase) {

        return ResourceFactory.createProperty(newBase + prop.asResource().getLocalName());
    }

    //Replace namespace
    private static Resource rebaseResource(Resource res, String newBase) {

        return ResourceFactory.createResource(newBase + res.getLocalName());
    }

    //Replace namespace
    private static Statement rebaseStatement(Statement stmtA, String cim17NS, String euNS) {

        Resource newSub;
        Property newPre;
        RDFNode newObj;

        if (stmtA.getSubject().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
            newSub = rebaseResource(stmtA.getSubject(), cim17NS);
        } else if (stmtA.getSubject().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
            newSub = rebaseResource(stmtA.getSubject(), euNS);
        } else {
            newSub = stmtA.getSubject();
        }
        if (stmtA.getPredicate().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
            newPre = rebaseProperty(stmtA.getPredicate(), cim17NS);
        } else if (stmtA.getPredicate().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
            newPre = rebaseProperty(stmtA.getPredicate(), euNS);
        } else {
            newPre = stmtA.getPredicate();
        }
        if (stmtA.getObject().isResource()) {
            if (stmtA.getObject().asResource().getNameSpace().equals("http://iec.ch/TC57/2013/CIM-schema-cim16#")) {
                newObj = rebaseRDFNode(stmtA.getObject(), cim17NS);
            } else if (stmtA.getObject().asResource().getNameSpace().equals("http://entsoe.eu/CIM/SchemaExtension/3/1#")) {
                newObj = rebaseRDFNode(stmtA.getObject(), euNS);
            } else {
                newObj = stmtA.getObject();
            }
        } else {
            newObj = stmtA.getObject();
        }

        return ResourceFactory.createStatement(newSub, newPre, newObj);
    }

    public static HashMap<String, Set<Resource>> LoadRDFAbout(String cgmesVersion) throws FileNotFoundException {
        HashMap<String, Set<Resource>> rdfAboutMap = new HashMap<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (cgmesVersion.equals("CGMESv3.0")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v300_enum_id_about.ttl");
        } else if (cgmesVersion.equals("CGMESv2.4")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v2415_enum_id_about.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream,"", Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }
        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            String[] sub_URI = stmt.getSubject().getURI().split("#");
            if (sub_URI[1].equals("RdfAbout")){
                String profile = getProfileName(sub_URI[0], cgmesVersion);
                if (!profile.isEmpty()){
                    Set<Resource> rdfAboutList = new HashSet<>();
                    for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                        RDFNode o_i = iter.next();
                        rdfAboutList.add(o_i.asResource());
                    }
                    rdfAboutMap.put(profile, rdfAboutList);
                }
            }
        }
        return rdfAboutMap;
    }

    public static HashMap<String, Set<Resource>> LoadRDFEnum(String cgmesVersion) throws FileNotFoundException {
        HashMap<String, Set<Resource>> rdfEnumMap = new HashMap<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (cgmesVersion.equals("CGMESv3.0")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v300_enum_id_about.ttl");
        } else if (cgmesVersion.equals("CGMESv2.4")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v2415_enum_id_about.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, "", Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }

        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            String[] sub_URI = stmt.getSubject().getURI().split("#");
            if (sub_URI[1].equals("RdfEnum")){
                String profile = getProfileName(sub_URI[0], cgmesVersion);
                if (!profile.isEmpty()){
                    Set<Resource> RdfEnumList = new HashSet<>();
                    for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                        RDFNode o_i = iter.next();
                        RdfEnumList.add(o_i.asResource());
                    }
                    rdfEnumMap.put(profile, RdfEnumList);
                }
            }
        }
        return rdfEnumMap;
    }

    //Save data
    public static void saveInstanceModelData(Map<String, Model> instanceDataModelMap, Map<String, Object> saveProperties, String cgmesVersion) throws IOException {

        boolean useFileDialog = (boolean) saveProperties.get("useFileDialog");
        if (!useFileDialog) {
            DirectoryChooser folderchooser = new DirectoryChooser();
            folderchooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
            File file;

            try {
                file = folderchooser.showDialog(null);
            } catch (Exception k) {
                folderchooser.setInitialDirectory(new File("C:\\\\"));
                file = folderchooser.showDialog(null);
            }
            saveProperties.replace("fileFolder", file);
        }

//        HashMap<String, Set<Resource>> rdfAboutMap = LoadRDFAbout("CGMESv2.4");
//        HashMap<String, Set<Resource>> rdfEnumMap = LoadRDFEnum("CGMESv2.4");

        for (Map.Entry<String, Model> entry : instanceDataModelMap.entrySet()) {

            //TODO fix this to be more universal
            String profileURI = Objects.equals(cgmesVersion, "CGMESv2.4") ?
                    switch (entry.getKey()) {
                        case "EQ" -> "http://entsoe.eu/CIM/EquipmentCore/3/1#";
                        case "SSH" -> "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1#";
                        case "TP" -> "http://entsoe.eu/CIM/Topology/4/1#";
                        case "SV" -> "http://entsoe.eu/CIM/StateVariables/4/1#";
                        default -> null;
                    }
                    :
                    switch (entry.getKey()) {
                        case "EQ" -> "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0#";
                        case "SSH" -> "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0#";
                        case "TP" -> "http://iec.ch/TC57/ns/CIM/Topology-EU/3.0#";
                        case "SV" -> "http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0#";
                        default -> null;
                    };


            Set<Resource> rdfAboutList = LoadRDFAbout(profileURI, cgmesVersion);
            Set<Resource> rdfEnumList = LoadRDFEnum(profileURI, cgmesVersion);

//            Set<Resource> rdfAboutList = rdfAboutMap.get(entry.getKey());
//            Set<Resource> rdfEnumList = rdfEnumMap.get(entry.getKey());
            rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));

            //this is related to the save of the data
//            Set<Resource> rdfAboutList = new HashSet<>();
//            Set<Resource> rdfEnumList = new HashSet<>();
//            if ((boolean) saveProperties.get("useAboutRules")) {
//                if (profileModelMap != null) {
//                    if (profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
//                        rdfAboutList = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
//                    }
//                }
//                rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
//            }
//
//            if ((boolean) saveProperties.get("useEnumRules")) {
//                if (profileModelMap != null) {
//                    for (ResIterator i = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
//                            ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); i.hasNext(); ) {
//                        Resource resItem = i.next();
//                        for (ResIterator j = profileModelMap.get(entry.getKey()).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
//                            Resource resItemProp = j.next();
//                            rdfEnumList.add(resItemProp);
//                        }
//                    }
//                }
//            }
//
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

            if (MainController.ibBDconversion) {
                saveProperties.replace("filename", entry.getKey());
            } else {
                if (nameMap.size() != 0) {
                    saveProperties.replace("filename", nameMap.get(entry.getKey()));
                } else {
                    saveProperties.replace("filename", entry.getKey());
                }
            }


            InstanceDataFactory.saveInstanceData(entry.getValue(), saveProperties);
        }
    }


    // load profiles
    public static List<File> LoadRDFS(String cgmesVersion) throws URISyntaxException {

        List<File> modelFiles = new LinkedList<>();
        if (cgmesVersion.equals("CGMESv2.4")) {
            // load all profile models
            File EQ;
            File FH;
            File TPBD;
            File SSH;
            File TP;
            File SV;
            File EQBD;

            String pathclass = ModelManipulationFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(pathclass);
            String jarDir = jarFile.getAbsolutePath();

            EQ = new File(jarDir + "/RDFSCGMESv2.4/EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf");
            FH = new File(jarDir + "/RDFSCGMESv2.4/FileHeader.rdf");
            EQBD = new File(jarDir + "/RDFSCGMESv2.4/EquipmentBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
            TPBD = new File(jarDir + "/RDFSCGMESv2.4/TopologyBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
            SSH = new File(jarDir + "/RDFSCGMESv2.4/SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
            SV = new File(jarDir + "/RDFSCGMESv2.4/StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
            TP = new File(jarDir + "/RDFSCGMESv2.4/TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");

            if (!EQ.exists() && !FH.exists() && !EQBD.exists() && !TPBD.exists() && !SSH.exists() && !SV.exists() && !TP.exists()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setContentText("RFS folder does not exist. Please select the folder with Select or cancel conversion.");
                alert.setHeaderText(null);
                alert.setTitle("Warning - RDFS folder does not exist");
                ButtonType btnYes = new ButtonType("Select RDFS folder");
                ButtonType btnNo = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(btnYes, btnNo);
                Optional<ButtonType> result = alert.showAndWait();
                if (result.get() == btnNo) {
                    return modelFiles;
                } else if (result.get() == btnYes) {
                    DirectoryChooser directoryChooser = new DirectoryChooser();
                    directoryChooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
                    File folder = directoryChooser.showDialog(null);
                    String rdfsdir = folder.getAbsolutePath();
                    EQ = new File(rdfsdir + "/EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf");
                    FH = new File(rdfsdir + "/FileHeader.rdf");
                    EQBD = new File(rdfsdir + "/EquipmentBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
                    TPBD = new File(rdfsdir + "/TopologyBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
                    SSH = new File(rdfsdir + "/SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
                    SV = new File(rdfsdir + "/StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
                    TP = new File(rdfsdir + "/TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf");
                }
            }


            modelFiles.add(EQ);
            modelFiles.add(FH);
            modelFiles.add(EQBD);
            modelFiles.add(TPBD);
            modelFiles.add(SSH);
            modelFiles.add(SV);
            modelFiles.add(TP);
        } else if (cgmesVersion.equals("CGMESv3.0")) {
            // load all profile models
            File EQ;
            File FH;
            File SC;
            File OP;
            File SSH;
            File TP;
            File SV;
            File EQBD;

            String pathclass = ModelManipulationFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(pathclass);
            String jarDir = jarFile.getAbsolutePath();

            EQ = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf");
            FH = new File(jarDir + "/RDFSCGMESv3/FileHeader_RDFS2019.rdf");
            EQBD = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf");
            OP = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf");
            SC = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf");
            SSH = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf");
            SV = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf");
            TP = new File(jarDir + "/RDFSCGMESv3/IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf");
/*
            if (!EQ.exists() && !FH.exists() && !EQBD.exists() && !OP.exists() && !SC.exists() && !SSH.exists() && !SV.exists() && !TP.exists()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setContentText("RFS folder does not exist. Please select the folder with Select or cancel conversion.");
                alert.setHeaderText(null);
                alert.setTitle("Warning - RDFS folder does not exist");
                ButtonType btnYes = new ButtonType("Select RDFS folder");
                ButtonType btnNo = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(btnYes, btnNo);
                Optional<ButtonType> result = alert.showAndWait();
                if (result.get() == btnNo) {
                    return modelFiles;
                } else if (result.get() == btnYes) {
                    DirectoryChooser directoryChooser = new DirectoryChooser();
                    directoryChooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
                    File folder = directoryChooser.showDialog(null);
                    String rdfsdir = folder.getAbsolutePath();
                    EQ = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf");
                    FH = new File(rdfsdir + "/FileHeader_RDFS2019.rdf");
                    EQBD = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf");
                    OP = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf");
                    SC = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf");
                    SSH = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf");
                    SV = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf");
                    TP = new File(rdfsdir + "/IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf");
                }
            }
 */
            modelFiles.add(EQ);
            modelFiles.add(FH);
            modelFiles.add(EQBD);
            modelFiles.add(OP);
            modelFiles.add(SC);
            modelFiles.add(SSH);
            modelFiles.add(SV);
            modelFiles.add(TP);
        }
        return modelFiles;
    }


    public static Set<Resource> LoadRDFAbout(String profileURI, String cgmesVersion) throws FileNotFoundException {
        Set<Resource> rdfAboutList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (cgmesVersion.equals("CGMESv3.0")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v300_enum_id_about.ttl");
        } else if (cgmesVersion.equals("CGMESv2.4")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v2415_enum_id_about.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream,"", Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }
        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject().equals(ResourceFactory.createResource(profileURI+"RdfAbout"))){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    rdfAboutList.add(o_i.asResource());

                }
            }
        }
        return rdfAboutList;
    }
    public static Set<Resource> LoadRDFEnum(String profileURI, String cgmesVersion) throws FileNotFoundException {
        Set<Resource> RdfEnumList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (cgmesVersion.equals("CGMESv3.0")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v300_enum_id_about.ttl");
        } else if (cgmesVersion.equals("CGMESv2.4")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/Serialization_cgmes_v2415_enum_id_about.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, "", Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }

        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject().equals(ResourceFactory.createResource(profileURI+"RdfEnum"))){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    RdfEnumList.add(o_i.asResource());
                }
            }
        }
        return RdfEnumList;
    }


    //Modify IGM
    public static void ModifyIGM(Map<String, Map> loadDataMap,String cgmesVersion,boolean impMap,boolean applyAllCondEq,boolean applyLine,boolean applyTrafo,boolean applySynMach,boolean expMap, boolean applyEQmap) throws IOException {
        String xmlBase ="";
        String cimns = "";
        if (cgmesVersion.equals("CGMESv3.0")) {
            xmlBase = "http://iec.ch/TC57/CIM100";
            cimns = "http://iec.ch/TC57/CIM100#";
        }else if(cgmesVersion.equals("CGMESv2.4")){
            xmlBase = "http://iec.ch/TC57/2013/CIM-schema-cim16";
            cimns = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
        }

        //put the xls map to graph


        if (impMap){
            //create empty graph
            mapModel = ModelFactory.createDefaultModel();

            List<String> prop_names = Arrays.asList(
                    "connectivityNode1ID",
                    "connectivityNode2ID",
                    "connectivityNode3ID",
                    "connectivityNode4ID",
                    "connectivityNode5ID",
                    "connectivityNode6ID",
                    "topologicalNode1ID",
                    "topologicalNode2ID",
                    "topologicalNode3ID",
                    "topologicalNode4ID",
                    "topologicalNode5ID",
                    "topologicalNode6ID",
                    "breaker1ID",
                    "breaker1IDTerminal1ID",
                    "breaker1IDTerminal2ID",
                    "breaker2ID",
                    "breaker2IDTerminal1ID",
                    "breaker2IDTerminal2ID",
                    "breaker3ID",
                    "breaker3IDTerminal1ID",
                    "breaker3IDTerminal2ID",
                    "topologicalNodeMRID",
                    "connectivityNodeMRID"
            );
            for (File file : MainController.MappingMapFile) {
                // load MainController.MappingMapFile - this is the xls mapping file that user selects
                try (FileInputStream fis = new FileInputStream(file);
                     Workbook workbook = WorkbookFactory.create(fis)) {
                    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                        //Sheet sheet = workbook.getSheetAt(sheetIndex);
                        String sheetName = workbook.getSheetName(sheetIndex);
                        if (sheetName.equals("Mapping")) {
                            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file, sheetIndex);
                            //loop on the file and store the data in the graph mapModel

                            for (int i = 1; i < inputXLSdata.size(); i++) {
                                LinkedList<?> row = (LinkedList<?>) inputXLSdata.get(i);
                                Resource subject = ResourceFactory.createResource(cimns + row.get(1).toString());
                                RDFNode object = ResourceFactory.createResource(cimns + row.getFirst().toString());
                                Statement stmt = ResourceFactory.createStatement(subject, RDF.type, object);
                                mapModel.add(stmt);
                                int k = 0;
                                for (int j = 2; j < row.size(); j++) {
                                    object = ResourceFactory.createPlainLiteral(row.get(j).toString());
                                    Property predicate = ResourceFactory.createProperty(cimns + prop_names.get(k));
                                    stmt = ResourceFactory.createStatement(subject, predicate, object);
                                    mapModel.add(stmt);
                                    k++;
                                }
                            }
                        }else if (sheetName.equals("TN-CN Mapping")) {
                            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(file, sheetIndex);
                            //loop on the file and store the data in the graph mapModel

                            for (int i = 1; i < inputXLSdata.size(); i++) {
                                LinkedList<?> row = (LinkedList<?>) inputXLSdata.get(i);
                                Resource subject = ResourceFactory.createResource(cimns + row.getFirst().toString());
                                RDFNode object = ResourceFactory.createResource(cimns + "TopologicalNode");
                                Statement stmt = ResourceFactory.createStatement(subject, RDF.type, object);
                                mapModel.add(stmt);
                                Property predicate = ResourceFactory.createProperty(cimns + "connectivityNodeMRID");
                                object = ResourceFactory.createPlainLiteral(row.get(1).toString());
                                Statement stmt1 = ResourceFactory.createStatement(subject, predicate, object);
                                mapModel.add(stmt1);
                            }
                        }
                    }
                }catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        Map<String,List> expMapToXls = new HashMap<>();
        if (expMap) {
            List Element_type = new ArrayList<>();
            List Element_ID = new ArrayList<>();
            List ConnectivityNode_1_ID = new ArrayList<>();
            List ConnectivityNode_2_ID = new ArrayList<>();
            List ConnectivityNode_3_ID = new ArrayList<>();
            List ConnectivityNode_4_ID = new ArrayList<>();
            List ConnectivityNode_5_ID = new ArrayList<>();
            List ConnectivityNode_6_ID = new ArrayList<>();
            List TopologicalNode_1_ID = new ArrayList<>();
            List TopologicalNode_2_ID = new ArrayList<>();
            List TopologicalNode_3_ID = new ArrayList<>();
            List TopologicalNode_4_ID = new ArrayList<>();
            List TopologicalNode_5_ID = new ArrayList<>();
            List TopologicalNode_6_ID = new ArrayList<>();
            List Breaker_1_ID = new ArrayList<>();
            List Breaker_1_Terminal_1_ID = new ArrayList<>();
            List Breaker_1_Terminal_2_ID = new ArrayList<>();
            List Breaker_2_ID = new ArrayList<>();
            List Breaker_2_ID_Terminal_1_ID = new ArrayList<>();
            List Breaker_2_ID_Terminal_2_ID = new ArrayList<>();
            List Breaker_3_ID = new ArrayList<>();
            List Breaker_3_ID_Terminal_1_ID = new ArrayList<>();
            List Breaker_3_ID_Terminal_2_ID = new ArrayList<>();
            List TopologicalNode_main_ID = new ArrayList<>();
            List ConnectivityNode_main_ID = new ArrayList<>();
            expMapToXls.put("Element_type",Element_type);
            expMapToXls.put("Element_ID",Element_ID);
            expMapToXls.put("ConnectivityNode_1_ID",ConnectivityNode_1_ID);
            expMapToXls.put("ConnectivityNode_2_ID",ConnectivityNode_2_ID);
            expMapToXls.put("ConnectivityNode_3_ID",ConnectivityNode_3_ID);
            expMapToXls.put("ConnectivityNode_4_ID",ConnectivityNode_4_ID);
            expMapToXls.put("ConnectivityNode_5_ID",ConnectivityNode_5_ID);
            expMapToXls.put("ConnectivityNode_6_ID",ConnectivityNode_6_ID);
            expMapToXls.put("TopologicalNode_1_ID",TopologicalNode_1_ID);
            expMapToXls.put("TopologicalNode_2_ID",TopologicalNode_2_ID);
            expMapToXls.put("TopologicalNode_3_ID",TopologicalNode_3_ID);
            expMapToXls.put("TopologicalNode_4_ID",TopologicalNode_4_ID);
            expMapToXls.put("TopologicalNode_5_ID",TopologicalNode_5_ID);
            expMapToXls.put("TopologicalNode_6_ID",TopologicalNode_6_ID);
            expMapToXls.put("Breaker_1_ID",Breaker_1_ID);
            expMapToXls.put("Breaker_1_Terminal_1_ID",Breaker_1_Terminal_1_ID);
            expMapToXls.put("Breaker_1_Terminal_2_ID",Breaker_1_Terminal_2_ID);
            expMapToXls.put("Breaker_2_ID",Breaker_2_ID);
            expMapToXls.put("Breaker_2_ID_Terminal_1_ID",Breaker_2_ID_Terminal_1_ID);
            expMapToXls.put("Breaker_2_ID_Terminal_2_ID",Breaker_2_ID_Terminal_2_ID);
            expMapToXls.put("Breaker_3_ID",Breaker_3_ID);
            expMapToXls.put("Breaker_3_ID_Terminal_1_ID",Breaker_3_ID_Terminal_1_ID);
            expMapToXls.put("Breaker_3_ID_Terminal_2_ID",Breaker_3_ID_Terminal_2_ID);
            expMapToXls.put("TopologicalNode_main_ID",TopologicalNode_main_ID);
            expMapToXls.put("ConnectivityNode_main_ID",ConnectivityNode_main_ID);
        }

        //set properties for the export

        Map<String, Object> saveProperties = new HashMap<>();

        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        //saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        saveProperties.put("useFileDialog", false);
        saveProperties.put("fileFolder", "C:");
        saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");
        Set<Resource> rdfAboutList = LoadRDFAbout(xmlBase, cgmesVersion);
        Set<Resource> rdfEnumList = LoadRDFEnum(xmlBase, cgmesVersion);
        saveProperties.put("rdfAboutList", rdfAboutList);
        saveProperties.put("rdfEnumList", rdfEnumList);
        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
        saveProperties.put("fileDialogTitle", "Save RDF XML for");
        saveProperties.put("sortRDF", "true");
        saveProperties.put("sortRDFprefix", "false"); // if true the sorting is on the prefix, if false on the localName
        //RDFFormat rdfFormat=RDFFormat.RDFXML;
        //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
        //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
        //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;


        //TODO to be improved what file names should be assigned. Now it takes same names
        nameMap = new HashMap<>();
        for (File item : MainController.IDModel) {
            if (item.toString().contains("_EQ")) {
                nameMap.put("EQ", item.getName());
            } else if (item.toString().contains("_SSH")) {
                nameMap.put("SSH", item.getName());
            } else if (item.toString().contains("_SV")) {
                nameMap.put("SV", item.getName());
            } else if (item.toString().contains("_TP")) {
                nameMap.put("TP", item.getName());
            }
        }

        Map<String, Model> baseInstanceModelMap = InstanceDataFactory.modelLoad(MainController.IDModel, xmlBase, null);

        Model modelEQ = baseInstanceModelMap.get("EQ");
        Model modelSSH = baseInstanceModelMap.get("SSH");
        Model modelSV = baseInstanceModelMap.get("SV");
        Model modelTP = baseInstanceModelMap.get("TP");
        Model modelTPBD = baseInstanceModelMap.get("TPBD");
        Model modelEQBD = baseInstanceModelMap.get("EQBD");

        Map<String, Model> modifiedModelMap = new HashMap<>();

        //create the new models
        Model modEQModel = createSimilarModel(modelEQ);
        Model modSSHModel = createSimilarModel(modelSSH);
        Model modSVModel = createSimilarModel(modelSV);
        Model modTPModel = createSimilarModel(modelTP);
        //do the modification
        modEQModel.add(modelEQ);
        modSSHModel.add(modelSSH);
        modSVModel.add(modelSV);
        modTPModel.add(modelTP);

        //update header to refer to Operation profile - only for CGMES v2.4
        if (!cgmesVersion.equals("CGMESv3.0") ) {// because for CGMES v3 there is no need to add 0 voltage
            Resource headerRes = modEQModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).next();
            modEQModel.add(ResourceFactory.createStatement(headerRes, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile"), ResourceFactory.createPlainLiteral("http://entsoe.eu/CIM/EquipmentOperation/3/1")));
        }

        //add ConnectivityNode for each TopologicalNode in case there is no and link the Terminal
        Map<String,String> mapIDs = null;
        RDFNode TopologicalNode = ResourceFactory.createProperty(cimns,"TopologicalNode");
        RDFNode ConnectivityNode = ResourceFactory.createProperty(cimns,"ConnectivityNode");
        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");
        Property ioname = ResourceFactory.createProperty(cimns,"IdentifiedObject.name");
        Property termToCN = ResourceFactory.createProperty(cimns,"Terminal.ConnectivityNode");
        Property termToTN = ResourceFactory.createProperty(cimns,"Terminal.TopologicalNode");
        Property cnToTN = ResourceFactory.createProperty(cimns,"ConnectivityNode.TopologicalNode");
        Property cncncontainer = ResourceFactory.createProperty(cimns, "ConnectivityNode.ConnectivityNodeContainer");
        Property tncncontainer = ResourceFactory.createProperty(cimns, "TopologicalNode.ConnectivityNodeContainer");
        List TopologicalNode_main_ID = List.of();
        List ConnectivityNode_main_ID = List.of();
        if (expMap) {
            TopologicalNode_main_ID = expMapToXls.get("TopologicalNode_main_ID");
            ConnectivityNode_main_ID = expMapToXls.get("ConnectivityNode_main_ID");
        }
        for (StmtIterator s = modTPModel.listStatements(null,RDF.type,TopologicalNode); s.hasNext();) {
            Statement stmt = s.next();
            if (!modTPModel.listStatements(null, cnToTN, stmt.getSubject()).hasNext()) {
                //List<String> ids = GenerateUUID();

                List<String> ids = new LinkedList<>();
                if (impMap) {
                    mapIDs = GetMapIDs(stmt);
                    try {
                        String id = mapIDs.get("connectivityNodeMRID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }catch (Exception e){
                        ids = GenerateUUID();
                    }
                }else{
                    ids = GenerateUUID();
                }

                if (expMap) {
                    TopologicalNode_main_ID.add(stmt.getSubject().getLocalName());
                    ConnectivityNode_main_ID.add(ids.get(1));
                }

                Resource cnRes = ResourceFactory.createResource(cimns + ids.get(1));
                modEQModel.add(ResourceFactory.createStatement(cnRes, RDF.type, ConnectivityNode));
                modEQModel.add(ResourceFactory.createStatement(cnRes, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
                if (cgmesVersion.equals("CGMESv3.0")) {
                    modEQModel.add(ResourceFactory.createStatement(cnRes, mrid, ResourceFactory.createPlainLiteral(ids.getFirst())));
                }
                modEQModel.add(ResourceFactory.createStatement(cnRes, ioname, ResourceFactory.createPlainLiteral("new node"))); // add a default name
                modTPModel.add(ResourceFactory.createStatement(cnRes, RDF.type, ConnectivityNode));
                modTPModel.add(ResourceFactory.createStatement(cnRes, cnToTN, stmt.getSubject()));
                RDFNode tnContainer;
                if (modTPModel.listStatements(stmt.getSubject(), tncncontainer, (RDFNode) null).hasNext()) {
                    tnContainer = modTPModel.listStatements(stmt.getSubject(), tncncontainer, (RDFNode) null).next().getObject();
                } else if (modelTPBD.listStatements(stmt.getSubject(), tncncontainer, (RDFNode) null).hasNext()) {
                    tnContainer = modelTPBD.listStatements(stmt.getSubject(), tncncontainer, (RDFNode) null).next().getObject();
                } else {
                    tnContainer = ResourceFactory.createProperty(cimns, "_NoContainer");
                    //TODO issue warning
                }
                modEQModel.add(ResourceFactory.createStatement(cnRes, cncncontainer, tnContainer));

//                if (stmt.getSubject().getLocalName().equals("_f34cd840-dac6-5f69-a445-345313b7bd6f")){
//                    int k=1;
//                }
                //get all terminals of topologicalNode
                List<Statement> TerminalList = modTPModel.listStatements(null, termToTN, stmt.getSubject()).toList();
                for (Statement term : TerminalList){
                    modEQModel.add(ResourceFactory.createStatement(term.getSubject().asResource(), termToCN, ResourceFactory.createProperty(cnRes.toString())));
                }
                if (expMap) {
                    expMapToXls.replace("TopologicalNode_main_ID",TopologicalNode_main_ID);
                    expMapToXls.replace("ConnectivityNode_main_ID",ConnectivityNode_main_ID);
                }
            }
        }
        //solve the problems with terminals connecting to the boundary nodes
        for (StmtIterator s = modelTPBD.listStatements(null,RDF.type,TopologicalNode); s.hasNext();) {
            Statement stmt = s.next();
            //get all terminals of topologicalNode
            List<Statement> TerminalList = modTPModel.listStatements(null, termToTN, stmt.getSubject()).toList();
            // get boundary CN
            Resource boundaryCN = modelTPBD.listStatements(null, cnToTN, stmt.getSubject()).next().getSubject();
            for (Statement term : TerminalList){
                modEQModel.add(ResourceFactory.createStatement(term.getSubject().asResource(), termToCN, boundaryCN));
            }
        }

        //check for association ControlArea.EnergyArea for CGMES v2.4
        if (!cgmesVersion.equals("CGMESv3.0") ) {//
            Resource controlAreaRes = modEQModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty(cimns,"ControlArea")).next();
            //find EnergyArea
            Resource loadAreaRes = null;
            if (modEQModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns,"LoadArea")).hasNext()){
                List<Statement> loadAreaList = modEQModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns,"LoadArea")).toList();
                loadAreaRes = loadAreaList.getFirst().getSubject();
            }else{
                //TODO do warning that energy area is missing or create it...
            }
            if (!modEQModel.listStatements(controlAreaRes, ResourceFactory.createProperty(cimns,"ControlArea.EnergyArea"), (RDFNode) null).hasNext()){
                modEQModel.add(ResourceFactory.createStatement(controlAreaRes, ResourceFactory.createProperty(cimns,"ControlArea.EnergyArea"), loadAreaRes));
            }
        }


        if (applyLine){
            //find all ACLineSegments
            //for each of the line segments, add 2 breakers
            RDFNode aclinesegment = ResourceFactory.createProperty(cimns,"ACLineSegment");
            for (StmtIterator s = modEQModel.listStatements(null,RDF.type,aclinesegment); s.hasNext();){
                Statement stmt = s.next();
                if (applyEQmap) {//only look at the xls map
                    if (!mapModel.listStatements(stmt.getSubject(),RDF.type,aclinesegment).hasNext()) {// not in the map then skip the rest
                        continue;
                    }
                }
                //check if there is Line container. If yes, do a check if there are Lines with more than 1 segment and issue a warning
                // if not process the line segments

                // get the ID of the Line if there is a line
                List<Statement> LineList;
                if (modEQModel.listStatements(stmt.getSubject(), ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), (RDFNode) null).hasNext()) {
                    Statement LineStmt = modEQModel.listStatements(stmt.getSubject(), ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), (RDFNode) null).next();
                    LineList = modEQModel.listStatements(null, ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), LineStmt.getSubject()).toList();
                    if (LineList.size() > 1) {
                        //TODO not supported case, todo the warning
                        //more than one segment in a Line - print warning
                        continue;
                    }
                } else {
                    //this is the case where there is no Line container for a given ACLineSegment
                    LineList = new LinkedList<>();
                    LineList.add(stmt);
                }
                Map<String, Object> breakerLineMap = AddBreakerLine(LineList, modelEQ, modelTP, modelSV, modelSSH, modEQModel, modSSHModel, modSVModel, modTPModel, cimns, cgmesVersion, modelTPBD, expMapToXls, expMap, impMap);
                modEQModel = (Model) breakerLineMap.get("modEQModel");
                modTPModel = (Model) breakerLineMap.get("modTPModel");
                modSVModel = (Model) breakerLineMap.get("modSVModel");
                modSSHModel = (Model) breakerLineMap.get("modSSHModel");
            }
        }

        if (applyTrafo){
            //find all PowerTransformers - only 2 winding power transformers here
            //for each of the PowerTransformer segments add 2 breakers
            RDFNode powerTransformer = ResourceFactory.createProperty(cimns,"PowerTransformer");
            for (StmtIterator s = modEQModel.listStatements(null,RDF.type,powerTransformer); s.hasNext();){
                Statement stmt = s.next();
                if (applyEQmap) {//only look at the xls map
                    if (!mapModel.listStatements(stmt.getSubject(),RDF.type,powerTransformer).hasNext()) {// not in the map then skip the rest
                        continue;
                    }
                }
//                //check if there is Line container. If yes, do a check if there are Lines with more than 1 segment and issue a warning
//                // if not process the line segments
//
//                // get the ID of the Line if there is a line
//                List<Statement> LineList;
//                if (modEQModel.listStatements(stmt.getSubject(), ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), (RDFNode) null).hasNext()) {
//                    Statement LineStmt = modEQModel.listStatements(stmt.getSubject(), ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), (RDFNode) null).next();
//                    LineList = modEQModel.listStatements(null, ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer"), LineStmt.getSubject()).toList();
//                    if (LineList.size() > 1) {
//                        //TODO not supported case, todo the warning
//                        //more than one segment in a Line - print warning
//                        continue;
//                    }
//                } else {
//                    //this is the case where there is no Line container for a given ACLineSegment
//                    LineList = new LinkedList<>();
//                    LineList.add(stmt);
//                }
                List<Statement> trafoList = new LinkedList<>();
                trafoList.add(stmt);
                Map<String, Object> breakerLineMap = AddBreakerTrafo(trafoList, modelEQ, modelTP, modelSV, modelSSH, modEQModel, modSSHModel, modSVModel, modTPModel, cimns, cgmesVersion, modelTPBD, expMapToXls, expMap, impMap);
                modEQModel = (Model) breakerLineMap.get("modEQModel");
                modTPModel = (Model) breakerLineMap.get("modTPModel");
                modSVModel = (Model) breakerLineMap.get("modSVModel");
                modSSHModel = (Model) breakerLineMap.get("modSSHModel");
            }
        }

        if (applySynMach){
            // find all SynchronousMachines
            //for each of the machine add 1 breaker
            RDFNode syncmachine = ResourceFactory.createProperty(cimns,"SynchronousMachine");
            for (StmtIterator s = modEQModel.listStatements(null,RDF.type,syncmachine); s.hasNext();){
                Statement stmt = s.next();
                if (applyEQmap) {//only look at the xls map
                    if (!mapModel.listStatements(stmt.getSubject(),RDF.type,syncmachine).hasNext()) {// not in the map then skip the rest
                        continue;
                    }
                }

                // do the routine for cases when there is one ACLineSegment in a Line
                Map<String,Object> breakerLineMap = AddBreakerSynchronousMachine(stmt,modelEQ,modelTP,modelSV,modelSSH,modEQModel,modSSHModel, modSVModel,modTPModel,cimns,cgmesVersion, modelTPBD,expMapToXls,expMap,impMap);
                modEQModel = (Model) breakerLineMap.get("modEQModel");
                modTPModel = (Model) breakerLineMap.get("modTPModel");
                modSVModel = (Model) breakerLineMap.get("modSVModel");
                modSSHModel = (Model) breakerLineMap.get("modSSHModel");

            }
        }

        // check for missing associations Terminal.TopologicalNode
        for (StmtIterator t = modEQModel.listStatements(null, ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode"), (RDFNode) null); t.hasNext(); ) { // loop on Terminal classes
            Statement stmtT = t.next();
            modTPModel.add(ResourceFactory.createStatement(stmtT.getSubject(), RDF.type, ResourceFactory.createProperty(cimns, "Terminal")));
            Resource tnRes = null;
            if (modTPModel.listStatements(stmtT.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"),(RDFNode) null).hasNext()) {
                tnRes = modTPModel.getRequiredProperty(stmtT.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode")).getResource();
            }else if (modelTPBD.listStatements(stmtT.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"),(RDFNode) null).hasNext()){
                tnRes = modelTPBD.getRequiredProperty(stmtT.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode")).getResource();
            }

            modTPModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), tnRes));
        }

        // check for missing associations Terminal.TopologicalNode by looking at the original
        for (StmtIterator t = modTPModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns, "Terminal")); t.hasNext(); ) { // loop on Terminal classes
            Statement stmtT = t.next();
            if(!modTPModel.listStatements(stmtT.getSubject(),ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"),(RDFNode) null).hasNext()) {
                //get from the original
                if (modelTP.listStatements(stmtT.getSubject(),ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"),(RDFNode) null).hasNext()){
                    Resource tnRes = modelTP.listStatements(stmtT.getSubject(),ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"),(RDFNode) null).next().getObject().asResource();
                    modTPModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), tnRes));
                }
            }
        }

//        // check for missing associations Terminal.ConnectivityNode
//        for (StmtIterator t = modTPModel.listStatements(null, ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), (RDFNode) null); t.hasNext(); ) { // loop on Terminal classes
//            Statement stmtT = t.next();
//            if(!modEQModel.listStatements(stmtT.getSubject(),ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode"),(RDFNode) null).hasNext()) {
//                modEQModel.add(ResourceFactory.createStatement(stmtT.getSubject(), RDF.type, ResourceFactory.createProperty(cimns, "Terminal")));
//                Resource cnRes = null;
//                if (modTPModel.listStatements(null, ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), stmtT.getObject().asResource()).hasNext()) {
//                    cnRes = modTPModel.listStatements(null, ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), stmtT.getObject().asResource()).next().getSubject().asResource();
//                } else if (modelTPBD.listStatements(null, ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), stmtT.getObject().asResource()).hasNext()) {
//                    cnRes = modelTPBD.listStatements(null, ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), stmtT.getObject().asResource()).next().getSubject().asResource();
//                }
//
//                modEQModel.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode"), cnRes));
//            }
//        }



        //Delete the custom extension
        List<Statement> stmpToDelete = new LinkedList<>();
        for (StmtIterator s = modEQModel.listStatements(null,ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")); s.hasNext();) {
            Statement stmt = s.next();
            stmpToDelete.add(stmt);
        }
        modEQModel.remove(stmpToDelete);

        //modTPModel.listStatements(ResourceFactory.createProperty("http://res.eu#NA"),RDF.type,ResourceFactory.createProperty("http://iec.ch/TC57/2013/CIM-schema-cim16#ConnectivityNode")).toList()
        //add the model to the map
        modifiedModelMap.put("EQ", modEQModel);
        modifiedModelMap.put("SSH", modSSHModel);
        modifiedModelMap.put("SV", modSVModel);
        modifiedModelMap.put("TP", modTPModel);

        //save
        saveInstanceModelData(modifiedModelMap, saveProperties, cgmesVersion);
        if (expMap){
            exportMapping(expMapToXls);
        }



    }

    //Modify CGM
    public static void ModifyCGM(Map<String, Map> loadDataMap,String cgmesVersion,boolean impMap,boolean applyAllCondEq,boolean applyLine,boolean applyTrafo,boolean applySynMach,boolean expMap) {


    }

    //Get element terminals
    public static List<Statement> GetElementTerminals(Model model, Statement elementStmt,String cimns) {
        return model.listStatements(null,ResourceFactory.createProperty(cimns,"Terminal.ConductingEquipment"),elementStmt.getSubject()).toList();
    }

    //Get element ConnectivityNodes (CN are the objects of the statements)
    public static List<Statement> GetElementConnectivityNodes(Model model, List<Statement> terminalsStmt,String cimns) {
        List<Statement> cnList = new LinkedList<>();

        for (Statement term : terminalsStmt){
            if (model.listStatements(term.getSubject(),ResourceFactory.createProperty(cimns,"Terminal.ConnectivityNode"),(RDFNode) null).hasNext()) {
                Statement cnStmt = model.listStatements(term.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode"), (RDFNode) null).next();
                cnList.add(cnStmt);
            }
        }

        return cnList;
    }


    //Get element TopologicalNodes (TN are the objects of the statements)
    public static List<Statement> GetElementTopologicalNodes(Model model, List<Statement> terminalsStmt, List<Statement> cnList, String cimns) {
        List<Statement> tnList = new LinkedList<>();

        if (cnList.size() != 2) {
            for (Statement term : terminalsStmt) {
                if (model.listStatements(term.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), (RDFNode) null).hasNext()){
                    Statement tnStmt = model.listStatements(term.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), (RDFNode) null).next();
                    tnList.add(tnStmt);
                }
            }
        }else{
            for (Statement cn : cnList) {
                if (model.listStatements(cn.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), (RDFNode) null).hasNext()){
                    Statement tnStmt = model.listStatements(cn.getObject().asResource(), ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode"), (RDFNode) null).next();
                    tnList.add(tnStmt);
                }
            }
        }

        return tnList;
    }

    public static List<Statement> GetTerminalsTopologicalNodeMinus1(Model modelTP, Statement tnode, String cimns, Statement removeTerm) {
        List<Statement> termList = new LinkedList<>();

        for (StmtIterator t = modelTP.listStatements(null, ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), tnode.getObject()); t.hasNext(); ) { // loop on TN classes
            Statement stmtT = t.next();
            if (!stmtT.getSubject().equals(removeTerm.getSubject())){
                termList.add(stmtT);
            }
        }
        return termList;
    }

    //Logic breaker status
    public static boolean breakerStatusLogic(Model modelSSH, Statement terminalsStmt, String cimns) {
        boolean breakerStatus = true;
        //commented lines are to be able to consider the open ended branches
        //for (Statement term : terminalsStmt) {
        Statement tnStmt = modelSSH.listStatements(terminalsStmt.getSubject(), ResourceFactory.createProperty(cimns, "ACDCTerminal.connected"), (RDFNode) null).next();
        if (!tnStmt.getObject().asLiteral().getBoolean()) {
            breakerStatus = false;
            //break;
        }
        //}
        return breakerStatus;
    }

    //Add Breaker
    public static Map<String,Object> AddBreaker(Model modelEQ, Model modelSSH, Model modelSV, String cimns, boolean status, String cgmesVersion,boolean impMap,Map<String,String> mapIDs,int breakerNumber) {

        List<String> ids = new LinkedList<>();
        if (impMap) {
            try {
                if (breakerNumber == 1) {
                    String id = mapIDs.get("breaker1ID");
                    ids.add(id.split("_", 2)[1]);
                    ids.add(id);

                } else if (breakerNumber == 2) {
                    String id = mapIDs.get("breaker2ID");
                    ids.add(id.split("_", 2)[1]);
                    ids.add(id);
                } else {
                    String id = mapIDs.get("breaker3ID");
                    ids.add(id.split("_", 2)[1]);
                    ids.add(id);
                }
            }catch (Exception e){
                ids = GenerateUUID();
            }
        }else{
            ids = GenerateUUID();
        }
        Resource breakerRes = ResourceFactory.createResource(cimns + ids.get(1));
        Property ioname = ResourceFactory.createProperty(cimns,"IdentifiedObject.name");
        Property mrid = ResourceFactory.createProperty(cimns,"IdentifiedObject.mRID");
        Property retained = ResourceFactory.createProperty(cimns,"Switch.retained");
        Property normalopen = ResourceFactory.createProperty(cimns, "Switch.normalOpen");
        Property open = ResourceFactory.createProperty(cimns, "Switch.open");
        Property termPhases = ResourceFactory.createProperty(cimns, "Terminal.phases");
        modelEQ.add(ResourceFactory.createStatement(breakerRes, RDF.type,ResourceFactory.createProperty(cimns,"Breaker")));
        modelSSH.add(ResourceFactory.createStatement(breakerRes, RDF.type,ResourceFactory.createProperty(cimns,"Breaker")));
        modelEQ.add(ResourceFactory.createStatement(breakerRes, ioname,ResourceFactory.createPlainLiteral("added breaker")));
        modelEQ.add(ResourceFactory.createStatement(breakerRes, mrid,ResourceFactory.createPlainLiteral(ids.getFirst())));
        modelEQ.add(ResourceFactory.createStatement(breakerRes, retained,ResourceFactory.createPlainLiteral("false")));
        if (status) {
            modelEQ.add(ResourceFactory.createStatement(breakerRes, normalopen, ResourceFactory.createPlainLiteral("false")));
            modelSSH.add(ResourceFactory.createStatement(breakerRes, open, ResourceFactory.createPlainLiteral("false")));
        }else{
            modelEQ.add(ResourceFactory.createStatement(breakerRes, normalopen, ResourceFactory.createPlainLiteral("true")));
            modelSSH.add(ResourceFactory.createStatement(breakerRes, open, ResourceFactory.createPlainLiteral("true")));
        }

        //create 2 terminals
        Map<String,Object> term1Map = AddTerminal(modelEQ, modelSSH,cimns,"1",cgmesVersion,breakerRes,impMap,mapIDs,breakerNumber);
        Resource term1Res = (Resource) term1Map.get("resource");
        modelEQ = (Model) term1Map.get("modelEQ");
        modelSSH = (Model) term1Map.get("modelSSH");

        Map<String,Object> term2Map = AddTerminal(modelEQ, modelSSH,cimns,"2",cgmesVersion,breakerRes,impMap,mapIDs,breakerNumber);
        Resource term2Res = (Resource) term2Map.get("resource");
        modelEQ = (Model) term2Map.get("modelEQ");
        modelSSH = (Model) term2Map.get("modelSSH");

        //TODO add here if CIM17 then add the SVSwitch

        Map<String,Object> breakerMap = new HashMap<>();
        breakerMap.put("resourceTerm1",term1Res);
        breakerMap.put("resourceTerm2",term2Res);
        breakerMap.put("breakerRes",breakerRes);
        breakerMap.put("modelEQ",modelEQ);
        breakerMap.put("modelSSH",modelSSH);
        breakerMap.put("modelSV",modelSV);
        return breakerMap;
    }


    //Add Breaker on a SynchronousMachine
    public static Map<String,Object> AddBreakerSynchronousMachine(Statement SMstatement,Model modelEQ, Model modelTP, Model modelSV,Model modelSSH,Model modEQModel,Model modSSHModel, Model modSVModel, Model modTPModel,String cimns, String cgmesVersion, Model modelTPBD,Map<String,List> expMapToXls, boolean expMap,boolean impMap) {

        Property cnToTn = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        RDFNode termObj = ResourceFactory.createProperty(cimns,"Terminal"); //TODO check if needed
        Map<String,String> mapIDs = null;
        if (impMap){
            mapIDs = GetMapIDs(SMstatement);
        }
        // add breaker
        List<Statement> smTerminals = GetElementTerminals(modelEQ,SMstatement,cimns);
        List<Statement> smCN = GetElementConnectivityNodes(modelEQ, smTerminals, cimns);
        List<Statement> smTN= GetElementTopologicalNodes(modelTP, smTerminals, smCN, cimns);
        Statement island;
        if (modelSV.listStatements(null,ResourceFactory.createProperty(cimns,"TopologicalIsland.TopologicalNodes"),smTN.getFirst().getObject()).hasNext()) {
            island = modelSV.listStatements(null, ResourceFactory.createProperty(cimns, "TopologicalIsland.TopologicalNodes"), smTN.getFirst().getObject()).next();
        }else{
            island = modelSV.listStatements(null, RDF.type,ResourceFactory.createProperty(cimns, "TopologicalIsland")).next();
        }
        //logic for the breaker status - if true the synchronous machine is in operation, if false it is disconnected
        // get breaker status
        boolean breakerStatus = breakerStatusLogic(modelSSH, smTerminals.getFirst(),cimns);

        // originTN is one of the list lineTN for which breaker and CN/TN are added it is used to sort out the containment

        //add breaker 1
        int breakerNumber = 1;
        Map<String,Object> breaker1Map = AddBreaker(modEQModel, modSSHModel, modSVModel, cimns, breakerStatus, cgmesVersion,impMap,mapIDs,breakerNumber);
        Resource resBreakerTerm1 = (Resource) breaker1Map.get("resourceTerm1");
        Resource resBreakerTerm2 = (Resource) breaker1Map.get("resourceTerm2");
        Resource resBreaker1 = (Resource) breaker1Map.get("breakerRes");
        modEQModel = (Model) breaker1Map.get("modelEQ");
        modSSHModel = (Model) breaker1Map.get("modelSSH");
        modSVModel = (Model) breaker1Map.get("modelSV");

        //add container for the breaker
        RDFNode tnContainer;
        Property tnToCNcontainer = ResourceFactory.createProperty(cimns,"TopologicalNode.ConnectivityNodeContainer");
        Property eqToEQcontainer = ResourceFactory.createProperty(cimns,"Equipment.EquipmentContainer");
        if (modelTP.listStatements(smTN.getFirst().getObject().asResource(),tnToCNcontainer,(RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(smTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        }else if (modelTPBD.listStatements(smTN.getFirst().getObject().asResource(),tnToCNcontainer,(RDFNode) null).hasNext()){
            tnContainer = modelTPBD.listStatements(smTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        }else{
            tnContainer=ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        modEQModel.add(ResourceFactory.createStatement(resBreaker1, eqToEQcontainer, tnContainer));

        //manage inner connections
        Map<String, Object> innerConMap = SMInnerConnections(modEQModel,modTPModel,modSVModel,smTN,cimns, smTerminals,resBreakerTerm2,cgmesVersion,island,modelTPBD,breakerStatus,impMap,mapIDs);
        modEQModel = (Model) innerConMap.get("modEQModel");
        modTPModel = (Model) innerConMap.get("modTPModel");
        modSVModel = (Model) innerConMap.get("modSVModel");
        Resource cn1resBreaker = (Resource) innerConMap.get("cn1res");
        Resource tn1resBreaker = (Resource) innerConMap.get("tn1res");
//        Resource cn2resBreaker = (Resource) innerConMap.get("cn2res");
//        Resource tn2resBreaker = (Resource) innerConMap.get("tn2res");

        //start 24 May 2024
        modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
        modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, cnToTn, ResourceFactory.createProperty(tn1resBreaker.toString())));
        //end 24 May 2024

        //check Connectivity nodes and Topological nodes at the two ends and connect
        int side = 0;
        boolean innerSide = false;
        Resource cn1res = ResourceFactory.createResource("http://res.eu#NAsm");
        Property termToCn = ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode");
        Property termToConEQ = ResourceFactory.createProperty(cimns, "Terminal.ConductingEquipment");

        List<Statement> termList = GetTerminalsTopologicalNodeMinus1(modelTP,smTN.get(side),cimns,smTerminals.get(side));
        //add the term1 of the breaker so that it is connected to the CN that is created
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        termList.add(ResourceFactory.createStatement(resBreakerTerm1,termToTN,smTN.get(side).getObject()));
        //remove the old connection to the TN
        //modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(),termToTN,lineTN.get(side).getObject()));
        //add the term 1 to the TN
        modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
        modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1,termToTN,smTN.get(side).getObject()));

//        if (smTerminals.get(side).getSubject().getLocalName().equals("_d4815b07-ce3e-cf4f-6201-4f60fd420c2d")){
//            boolean test = true;
//        }

        if (modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).hasNext()) {//there is a connectivity node at side 1

            boolean hasMainCN = false;
            for (StmtIterator s = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()); s.hasNext();) {
                Statement stmtCN = s.next();
                // Statement stmtCN = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).nextStatement();

                if (modEQModel.listStatements(stmtCN.getSubject(), ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")).hasNext()) {
                    cn1res = stmtCN.getSubject();
                    //need to remove Terminal.ConnectivityNode
                    if (modEQModel.listStatements(smTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
                        modEQModel.remove(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
                    }
                    modEQModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
                    hasMainCN = true;
                }
            }

            if (!hasMainCN){
                List<Resource> terminalsCN = new LinkedList<>();
                for (Statement t : termList ){
                    //terminalsCN.add(t.getSubject());
                    //get cond equipment
                    Resource condEq = modEQModel.getRequiredProperty(t.getSubject(),termToConEQ).getObject().asResource();
                    String resType = modEQModel.getRequiredProperty(condEq,RDF.type).getObject().asResource().getLocalName();
                    if (!resType.equals("ACLineSegment")) {
                        terminalsCN.add(t.getSubject());
                    }
                }
                terminalsCN.add(resBreakerTerm1);
                Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, smTN.get(side), terminalsCN, modelTPBD,impMap,mapIDs,side,innerSide);
                modEQModel = (Model) cn1Side1Map.get("modelEQ");
                modTPModel = (Model) cn1Side1Map.get("modelTP");
                modSVModel = (Model) cn1Side1Map.get("modelSV");
                cn1res = (Resource) cn1Side1Map.get("resource");
                modEQModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
                modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));

                modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
                modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(smTN.get(side).getObject().toString())));
            }
        }else{//there is no CN at side 1, add CN


            List<Resource> terminalsCN = new LinkedList<>();
            for (Statement t : termList ){
                //terminalsCN.add(t.getSubject());
                //get cond equipment
                Resource condEq = modEQModel.getRequiredProperty(t.getSubject(),termToConEQ).getObject().asResource();
                String resType = modEQModel.getRequiredProperty(condEq,RDF.type).getObject().asResource().getLocalName();
                if (!resType.equals("ACLineSegment")) {
                    terminalsCN.add(t.getSubject());
                }
            }
            terminalsCN.add(resBreakerTerm1);
            Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, smTN.get(side), terminalsCN, modelTPBD,impMap,mapIDs,side,innerSide);
            modEQModel = (Model) cn1Side1Map.get("modelEQ");
            modTPModel = (Model) cn1Side1Map.get("modelTP");
            modSVModel = (Model) cn1Side1Map.get("modelSV");
            cn1res = (Resource) cn1Side1Map.get("resource");
            modEQModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
            modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));

            modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
            modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(smTN.get(side).getObject().toString())));
        }




        if (expMap) {
            List Element_type = expMapToXls.get("Element_type");
            List Element_ID = expMapToXls.get("Element_ID");
            List ConnectivityNode_1_ID = expMapToXls.get("ConnectivityNode_1_ID");
            List ConnectivityNode_2_ID = expMapToXls.get("ConnectivityNode_2_ID");
            List ConnectivityNode_3_ID = expMapToXls.get("ConnectivityNode_3_ID");
            List ConnectivityNode_4_ID = expMapToXls.get("ConnectivityNode_4_ID");
            List ConnectivityNode_5_ID = expMapToXls.get("ConnectivityNode_5_ID");
            List ConnectivityNode_6_ID = expMapToXls.get("ConnectivityNode_6_ID");
            List TopologicalNode_1_ID = expMapToXls.get("TopologicalNode_1_ID");
            List TopologicalNode_2_ID = expMapToXls.get("TopologicalNode_2_ID");
            List TopologicalNode_3_ID = expMapToXls.get("TopologicalNode_3_ID");
            List TopologicalNode_4_ID = expMapToXls.get("TopologicalNode_4_ID");
            List TopologicalNode_5_ID = expMapToXls.get("TopologicalNode_5_ID");
            List TopologicalNode_6_ID = expMapToXls.get("TopologicalNode_6_ID");
            List Breaker_1_ID = expMapToXls.get("Breaker_1_ID");
            List Breaker_1_Terminal_1_ID = expMapToXls.get("Breaker_1_Terminal_1_ID");
            List Breaker_1_Terminal_2_ID = expMapToXls.get("Breaker_1_Terminal_2_ID");
            List Breaker_2_ID = expMapToXls.get("Breaker_2_ID");
            List Breaker_2_ID_Terminal_1_ID = expMapToXls.get("Breaker_2_ID_Terminal_1_ID");
            List Breaker_2_ID_Terminal_2_ID = expMapToXls.get("Breaker_2_ID_Terminal_2_ID");
            List Breaker_3_ID = expMapToXls.get("Breaker_3_ID");
            List Breaker_3_ID_Terminal_1_ID = expMapToXls.get("Breaker_3_ID_Terminal_1_ID");
            List Breaker_3_ID_Terminal_2_ID = expMapToXls.get("Breaker_3_ID_Terminal_2_ID");


            Element_type.add(SMstatement.getObject().asResource().getLocalName());
            Element_ID.add(SMstatement.getSubject().getLocalName());
            ConnectivityNode_1_ID.add(cn1res.getLocalName());
            ConnectivityNode_2_ID.add(cn1resBreaker.getLocalName());
            ConnectivityNode_3_ID.add("NA");
            ConnectivityNode_4_ID.add("NA");
            ConnectivityNode_5_ID.add("NA");
            ConnectivityNode_6_ID.add("NA");
            TopologicalNode_1_ID.add(smTN.getFirst().getObject().asResource().getLocalName());
            TopologicalNode_2_ID.add(tn1resBreaker.getLocalName());
            TopologicalNode_3_ID.add("NA");
            TopologicalNode_4_ID.add("NA");
            TopologicalNode_5_ID.add("NA");
            TopologicalNode_6_ID.add("NA");
            Breaker_1_ID.add(resBreaker1.getLocalName());
            Breaker_1_Terminal_1_ID.add(resBreakerTerm1.getLocalName());
            Breaker_1_Terminal_2_ID.add(resBreakerTerm2.getLocalName());
            Breaker_2_ID.add("NA");
            Breaker_2_ID_Terminal_1_ID.add("NA");
            Breaker_2_ID_Terminal_2_ID.add("NA");
            Breaker_3_ID.add("NA");
            Breaker_3_ID_Terminal_1_ID.add("NA");
            Breaker_3_ID_Terminal_2_ID.add("NA");


            expMapToXls.replace("Element_type",Element_type);
            expMapToXls.replace("Element_ID",Element_ID);
            expMapToXls.replace("ConnectivityNode_1_ID",ConnectivityNode_1_ID);
            expMapToXls.replace("ConnectivityNode_2_ID",ConnectivityNode_2_ID);
            expMapToXls.replace("ConnectivityNode_3_ID",ConnectivityNode_3_ID);
            expMapToXls.replace("ConnectivityNode_4_ID",ConnectivityNode_4_ID);
            expMapToXls.replace("ConnectivityNode_5_ID",ConnectivityNode_5_ID);
            expMapToXls.replace("ConnectivityNode_6_ID",ConnectivityNode_6_ID);
            expMapToXls.replace("TopologicalNode_1_ID",TopologicalNode_1_ID);
            expMapToXls.replace("TopologicalNode_2_ID",TopologicalNode_2_ID);
            expMapToXls.replace("TopologicalNode_3_ID",TopologicalNode_3_ID);
            expMapToXls.replace("TopologicalNode_4_ID",TopologicalNode_4_ID);
            expMapToXls.replace("TopologicalNode_5_ID",TopologicalNode_5_ID);
            expMapToXls.replace("TopologicalNode_6_ID",TopologicalNode_6_ID);
            expMapToXls.replace("Breaker_1_ID",Breaker_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_1_ID",Breaker_1_Terminal_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_2_ID",Breaker_1_Terminal_2_ID);
            expMapToXls.replace("Breaker_2_ID",Breaker_2_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_1_ID",Breaker_2_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_2_ID",Breaker_2_ID_Terminal_2_ID);
            expMapToXls.replace("Breaker_3_ID",Breaker_3_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_1_ID",Breaker_3_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_2_ID",Breaker_3_ID_Terminal_2_ID);
        }

        Map<String,Object> breakerSMMap = new HashMap<>();

        breakerSMMap.put("modEQModel",modEQModel);
        breakerSMMap.put("modTPModel",modTPModel);
        breakerSMMap.put("modSVModel",modSVModel);
        breakerSMMap.put("modSSHModel",modSSHModel);
        breakerSMMap.put("expMapToXls",expMapToXls);
        return breakerSMMap;
    }

    //Add Breaker on a line
    public static Map<String,Object> AddBreakerLine(List<Statement> lineList,Model modelEQ, Model modelTP, Model modelSV,Model modelSSH,Model modEQModel,Model modSSHModel, Model modSVModel, Model modTPModel,String cimns, String cgmesVersion, Model modelTPBD,Map<String,List> expMapToXls, boolean expMap, boolean impMap) {

        Property cnToTn = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        RDFNode termObj = ResourceFactory.createProperty(cimns, "Terminal"); //TODO check if needed
        Map<String, String> mapIDs = null;
        if (impMap) {
            mapIDs = GetMapIDs(lineList.getFirst());
        }

        // check original connections
        Statement objStmt = lineList.getFirst();
        List<Statement> lineTerminals = GetElementTerminals(modelEQ, lineList.getFirst(), cimns);
        List<Statement> lineCN = GetElementConnectivityNodes(modelEQ, lineTerminals, cimns);
        List<Statement> lineTN = GetElementTopologicalNodes(modelTP, lineTerminals, lineCN, cimns);

        List<Statement> tieFlowList = modEQModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns, "TieFlow")).toList();
        Property tieToTerminal = ResourceFactory.createProperty(cimns, "TieFlow.Terminal");


        Statement island;
        if (modelSV.listStatements(null, ResourceFactory.createProperty(cimns, "TopologicalIsland.TopologicalNodes"), lineTN.getFirst().getObject()).hasNext()) {
            island = modelSV.listStatements(null, ResourceFactory.createProperty(cimns, "TopologicalIsland.TopologicalNodes"), lineTN.getFirst().getObject()).next();
        } else {
            island = modelSV.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns, "TopologicalIsland")).next();
        }

        // check if TNs are boundary
        //check for side 1
        boolean side1TNboundaryDoBreaker = !modelTPBD.listStatements(lineTN.getFirst().getObject().asResource(), RDF.type, ResourceFactory.createProperty(cimns, "TopologicalNode")).hasNext();

        //check for side 2
        boolean side2TNboundaryDoBreaker = !modelTPBD.listStatements(lineTN.get(1).getObject().asResource(), RDF.type, ResourceFactory.createProperty(cimns, "TopologicalNode")).hasNext();

        //logic for the breaker status - if true the line is in operation, if false it is disconnected
        // if it is connected - no need to create TN, just CN; if not connected TN is needed
        boolean breaker1Status = breakerStatusLogic(modelSSH, lineTerminals.getFirst(), cimns);

        //create breakers
        //create breaker 1
        int breakerNumber = 1;
        Resource resBreaker1Term1 = ResourceFactory.createResource("http://res.eu#NAb1t1");
        Resource resBreaker1Term2 = ResourceFactory.createResource("http://res.eu#NAb1t2");
        Resource resBreaker1 = ResourceFactory.createResource("http://res.eu#NAbreaker1");
        if (side1TNboundaryDoBreaker) {
            Map<String, Object> breaker1Map = AddBreaker(modEQModel, modSSHModel, modSVModel, cimns, breaker1Status, cgmesVersion, impMap, mapIDs, breakerNumber);
            resBreaker1Term1 = (Resource) breaker1Map.get("resourceTerm1");
            resBreaker1Term2 = (Resource) breaker1Map.get("resourceTerm2");
            resBreaker1 = (Resource) breaker1Map.get("breakerRes");
            modEQModel = (Model) breaker1Map.get("modelEQ");
            modSSHModel = (Model) breaker1Map.get("modelSSH");
            modSVModel = (Model) breaker1Map.get("modelSV");
        }
        //create breaker 2
        breakerNumber = 2;
        boolean breaker2Status = breakerStatusLogic(modelSSH, lineTerminals.get(1), cimns);
        Resource resBreaker2Term1 = ResourceFactory.createResource("http://res.eu#NAb2t1");
        Resource resBreaker2Term2 = ResourceFactory.createResource("http://res.eu#NAb2t2");
        Resource resBreaker2 = ResourceFactory.createResource("http://res.eu#NAbreaker2");
        if (side2TNboundaryDoBreaker) {
            Map<String, Object> breaker2Map = AddBreaker(modEQModel, modSSHModel, modSVModel, cimns, breaker2Status, cgmesVersion, impMap, mapIDs, breakerNumber);
            resBreaker2Term1 = (Resource) breaker2Map.get("resourceTerm1");
            resBreaker2Term2 = (Resource) breaker2Map.get("resourceTerm2");
            resBreaker2 = (Resource) breaker2Map.get("breakerRes");
            modEQModel = (Model) breaker2Map.get("modelEQ");
            modSSHModel = (Model) breaker2Map.get("modelSSH");
            modSVModel = (Model) breaker2Map.get("modelSV");
        }

        //add container for the breaker
        RDFNode tnContainer;
        Property tnToCNcontainer = ResourceFactory.createProperty(cimns, "TopologicalNode.ConnectivityNodeContainer");
        Property eqToEQcontainer = ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer");
        if (modelTP.listStatements(lineTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(lineTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else if (modelTPBD.listStatements(lineTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTPBD.listStatements(lineTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else {
            tnContainer = ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        if (side1TNboundaryDoBreaker) {
            modEQModel.add(ResourceFactory.createStatement(resBreaker1, eqToEQcontainer, tnContainer));
        }

        if (modelTP.listStatements(lineTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(lineTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else if (modelTPBD.listStatements(lineTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTPBD.listStatements(lineTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else {
            tnContainer = ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        if (side2TNboundaryDoBreaker) {
            modEQModel.add(ResourceFactory.createStatement(resBreaker2, eqToEQcontainer, tnContainer));
        }

        //manage inner connections
        Map<String, Object> innerConMap = LineInnerConnections(modEQModel, modTPModel, modSVModel, lineTN, cimns, lineTerminals, resBreaker1Term2, resBreaker2Term2, cgmesVersion, island, modelTPBD, breaker1Status, breaker2Status, impMap, mapIDs, side1TNboundaryDoBreaker, side2TNboundaryDoBreaker);
        modEQModel = (Model) innerConMap.get("modEQModel");
        modTPModel = (Model) innerConMap.get("modTPModel");
        modSVModel = (Model) innerConMap.get("modSVModel");
        Resource cn1resBreaker = (Resource) innerConMap.get("cn1res");
        Resource tn1resBreaker = (Resource) innerConMap.get("tn1res");
        Resource cn2resBreaker = (Resource) innerConMap.get("cn2res");
        Resource tn2resBreaker = (Resource) innerConMap.get("tn2res");

        //start 24 May 2024
        if (!cn1resBreaker.getLocalName().equals("NA")) {
            modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
            modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, cnToTn, ResourceFactory.createProperty(tn1resBreaker.toString())));
        }
        if (!cn2resBreaker.getLocalName().equals("NA")) {
            modTPModel.add(ResourceFactory.createStatement(cn2resBreaker, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
            modTPModel.add(ResourceFactory.createStatement(cn2resBreaker, cnToTn, ResourceFactory.createProperty(tn2resBreaker.toString())));
        }
        //end 24 May 2024


        //check Connectivity nodes and Topological nodes at the two ends and connect
        int side = 0;
        boolean innerSide = false;
        Resource cn1res = ResourceFactory.createResource("http://res.eu#NAl1");
        Property termToCn = ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode");
        Property termToConEQ = ResourceFactory.createProperty(cimns, "Terminal.ConductingEquipment");

        List<Statement> termList = GetTerminalsTopologicalNodeMinus1(modelTP,lineTN.get(side),cimns,lineTerminals.get(side));
        //add the term1 of the breaker so that it is connected to the CN that is created
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        if (side1TNboundaryDoBreaker) {
            termList.add(ResourceFactory.createStatement(resBreaker1Term1, termToTN, lineTN.get(side).getObject()));
            //remove the old connection to the TN
            //modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(),termToTN,lineTN.get(side).getObject()));
            //add the term 1 to the TN
            modTPModel.add(ResourceFactory.createStatement(resBreaker1Term1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToTN, lineTN.get(side).getObject()));

            if (modTPModel.listStatements(null, cnToTn, lineTN.getFirst().getObject()).hasNext()) {//there is a connectivity node at side 1

                boolean hasMainCN = false;
                for (StmtIterator s = modTPModel.listStatements(null, cnToTn, lineTN.getFirst().getObject()); s.hasNext();) {
                    Statement stmtCN = s.next();
                    // Statement stmtCN = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).nextStatement();

                    if (modEQModel.listStatements(stmtCN.getSubject(), ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")).hasNext()) {
                        cn1res = stmtCN.getSubject();
                        //need to remove Terminal.ConnectivityNode
                        if (modEQModel.listStatements(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
                            modEQModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
                        }
                        modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));

                        modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
                        modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
                        hasMainCN = true;
                    }
                }

//                if (!hasMainCN){
//                    List<Resource> terminalsCN = new LinkedList<>();
//                    for (Statement t : termList) {
//                        //terminalsCN.add(t.getSubject());
//                        //get cond equipment
//                        Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                        String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                        if (!resType.equals("ACLineSegment")) {
//                            terminalsCN.add(t.getSubject());
//                        }
//                    }
//                    terminalsCN.add(resBreaker1Term1);
//                    Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                    modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                    modTPModel = (Model) cn1Side1Map.get("modelTP");
//                    modSVModel = (Model) cn1Side1Map.get("modelSV");
//                    cn1res = (Resource) cn1Side1Map.get("resource");
//                    modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                    modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                    modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                    modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
//                }



//                Statement stmtCN = modTPModel.listStatements(null, cnToTn, lineTN.getFirst().getObject()).nextStatement();
//                cn1res = stmtCN.getSubject();
//                //need to remove Terminal.ConnectivityNode
//                if (modEQModel.listStatements(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
//                    modEQModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                }
//                modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
            } else {//there is no CN at side 1, add CN

//
//                List<Resource> terminalsCN = new LinkedList<>();
//                for (Statement t : termList) {
//                    //terminalsCN.add(t.getSubject());
//                    //get cond equipment
//                    Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                    String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                    if (!resType.equals("ACLineSegment")) {
//                        terminalsCN.add(t.getSubject());
//                    }
//                }
//                terminalsCN.add(resBreaker1Term1);
//                Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                modTPModel = (Model) cn1Side1Map.get("modelTP");
//                modSVModel = (Model) cn1Side1Map.get("modelSV");
//                cn1res = (Resource) cn1Side1Map.get("resource");
//                modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
            }



            //check if one of the terminals if also a terminal referenced by a TieFlow and change the reference to be the terminal of the breaker 1

            for (Statement tie : tieFlowList) {
                if (modEQModel.getRequiredProperty(tie.getSubject(), tieToTerminal).getObject().asResource().equals(lineTerminals.get(side).getSubject())) {
                    modEQModel.remove(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, lineTerminals.get(side).getSubject()));
                    modEQModel.add(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, resBreaker1Term1));
                }
            }
        }

        side = 1;
        Resource cn2res = ResourceFactory.createResource("http://res.eu#NAl2");

        termList = GetTerminalsTopologicalNodeMinus1(modelTP,lineTN.get(side),cimns,lineTerminals.get(side));
        //add the term1 of the breaker so that it is connected to the CN that is created
        if (side2TNboundaryDoBreaker) {
            termList.add(ResourceFactory.createStatement(resBreaker2Term1, termToTN, lineTN.get(side).getObject()));
            //remove the old connection to the TN
            //modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(),termToTN,lineTN.get(side).getObject()));
            //add the term 1 to the TN
            modTPModel.add(ResourceFactory.createStatement(resBreaker2Term1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToTN, lineTN.get(side).getObject()));

            if (modTPModel.listStatements(null, cnToTn, lineTN.get(side).getObject()).hasNext()) {//there is a connectivity node at side 2

                boolean hasMainCN = false;
                for (StmtIterator s = modTPModel.listStatements(null, cnToTn, lineTN.get(side).getObject()); s.hasNext();) {
                    Statement stmtCN = s.next();
                    // Statement stmtCN = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).nextStatement();

                    if (modEQModel.listStatements(stmtCN.getSubject(), ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")).hasNext()) {
                        cn2res = stmtCN.getSubject();
                        //need to remove Terminal.ConnectivityNode
                        if (modEQModel.listStatements(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn2res.toString())).hasNext()) {
                            modEQModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn2res.toString())));
                        }
                        modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));

                        modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
                        modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
                        hasMainCN = true;
                    }
                }
//                if (!hasMainCN){
//                    List<Resource> terminalsCN = new LinkedList<>();
//                    for (Statement t : termList) {
//                        //get cond equipment
//                        Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                        String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                        if (!resType.equals("ACLineSegment")) {
//                            terminalsCN.add(t.getSubject());
//                        }
//                    }
//                    terminalsCN.add(resBreaker2Term1);
//                    Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                    modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                    modTPModel = (Model) cn1Side1Map.get("modelTP");
//                    modSVModel = (Model) cn1Side1Map.get("modelSV");
//                    cn2res = (Resource) cn1Side1Map.get("resource");
//                    modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
//                    modEQModel.add(ResourceFactory.createStatement(cn2res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                    modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                    modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
//                }



//                Statement stmtCN = modTPModel.listStatements(null, cnToTn, lineTN.get(side).getObject()).nextStatement();
//                cn2res = stmtCN.getSubject();
//                //need to remove Terminal.ConnectivityNode
//                if (modEQModel.listStatements(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
//                    modEQModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                }
//                modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
            } else {//there is no CN at side 2, add CN


//                List<Resource> terminalsCN = new LinkedList<>();
//                for (Statement t : termList) {
//                    //get cond equipment
//                    Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                    String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                    if (!resType.equals("ACLineSegment")) {
//                        terminalsCN.add(t.getSubject());
//                    }
//                }
//                terminalsCN.add(resBreaker2Term1);
//                Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                modTPModel = (Model) cn1Side1Map.get("modelTP");
//                modSVModel = (Model) cn1Side1Map.get("modelSV");
//                cn2res = (Resource) cn1Side1Map.get("resource");
//                modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
//                modEQModel.add(ResourceFactory.createStatement(cn2res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(lineTN.get(side).getObject().toString())));
            }



            //check if one of the terminals if also a terminal referenced by a TieFlow and change the reference to be the terminal of the breaker 1
            for (Statement tie : tieFlowList) {
                if (modEQModel.getRequiredProperty(tie.getSubject(), tieToTerminal).getObject().asResource().equals(lineTerminals.get(side).getSubject())) {
                    modEQModel.remove(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, lineTerminals.get(side).getSubject()));
                    modEQModel.add(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, resBreaker2Term1));
                }
            }
        }

        if (expMap) {
            List Element_type = expMapToXls.get("Element_type");
            List Element_ID = expMapToXls.get("Element_ID");
            List ConnectivityNode_1_ID = expMapToXls.get("ConnectivityNode_1_ID");
            List ConnectivityNode_2_ID = expMapToXls.get("ConnectivityNode_2_ID");
            List ConnectivityNode_3_ID = expMapToXls.get("ConnectivityNode_3_ID");
            List ConnectivityNode_4_ID = expMapToXls.get("ConnectivityNode_4_ID");
            List ConnectivityNode_5_ID = expMapToXls.get("ConnectivityNode_5_ID");
            List ConnectivityNode_6_ID = expMapToXls.get("ConnectivityNode_6_ID");
            List TopologicalNode_1_ID = expMapToXls.get("TopologicalNode_1_ID");
            List TopologicalNode_2_ID = expMapToXls.get("TopologicalNode_2_ID");
            List TopologicalNode_3_ID = expMapToXls.get("TopologicalNode_3_ID");
            List TopologicalNode_4_ID = expMapToXls.get("TopologicalNode_4_ID");
            List TopologicalNode_5_ID = expMapToXls.get("TopologicalNode_5_ID");
            List TopologicalNode_6_ID = expMapToXls.get("TopologicalNode_6_ID");
            List Breaker_1_ID = expMapToXls.get("Breaker_1_ID");
            List Breaker_1_Terminal_1_ID = expMapToXls.get("Breaker_1_Terminal_1_ID");
            List Breaker_1_Terminal_2_ID = expMapToXls.get("Breaker_1_Terminal_2_ID");
            List Breaker_2_ID = expMapToXls.get("Breaker_2_ID");
            List Breaker_2_ID_Terminal_1_ID = expMapToXls.get("Breaker_2_ID_Terminal_1_ID");
            List Breaker_2_ID_Terminal_2_ID = expMapToXls.get("Breaker_2_ID_Terminal_2_ID");
            List Breaker_3_ID = expMapToXls.get("Breaker_3_ID");
            List Breaker_3_ID_Terminal_1_ID = expMapToXls.get("Breaker_3_ID_Terminal_1_ID");
            List Breaker_3_ID_Terminal_2_ID = expMapToXls.get("Breaker_3_ID_Terminal_2_ID");


            Element_type.add(lineList.getFirst().getObject().asResource().getLocalName());
            Element_ID.add(lineList.getFirst().getSubject().getLocalName());
            ConnectivityNode_1_ID.add(cn1res.getLocalName());
            ConnectivityNode_2_ID.add(cn1resBreaker.getLocalName());
            ConnectivityNode_3_ID.add(cn2resBreaker.getLocalName());
            ConnectivityNode_4_ID.add(cn2res.getLocalName());
            ConnectivityNode_5_ID.add("NA");
            ConnectivityNode_6_ID.add("NA");
            TopologicalNode_1_ID.add(lineTN.getFirst().getObject().asResource().getLocalName());
            TopologicalNode_2_ID.add(tn1resBreaker.getLocalName());
            TopologicalNode_3_ID.add(tn2resBreaker.getLocalName());
            TopologicalNode_4_ID.add(lineTN.get(1).getObject().asResource().getLocalName());
            TopologicalNode_5_ID.add("NA");
            TopologicalNode_6_ID.add("NA");
            Breaker_1_ID.add(resBreaker1.getLocalName());
            Breaker_1_Terminal_1_ID.add(resBreaker1Term1.getLocalName());
            Breaker_1_Terminal_2_ID.add(resBreaker1Term2.getLocalName());
            Breaker_2_ID.add(resBreaker2.getLocalName());
            Breaker_2_ID_Terminal_1_ID.add(resBreaker2Term1.getLocalName());
            Breaker_2_ID_Terminal_2_ID.add(resBreaker2Term2.getLocalName());
            Breaker_3_ID.add("NA");
            Breaker_3_ID_Terminal_1_ID.add("NA");
            Breaker_3_ID_Terminal_2_ID.add("NA");


            expMapToXls.replace("Element_type",Element_type);
            expMapToXls.replace("Element_ID",Element_ID);
            expMapToXls.replace("ConnectivityNode_1_ID",ConnectivityNode_1_ID);
            expMapToXls.replace("ConnectivityNode_2_ID",ConnectivityNode_2_ID);
            expMapToXls.replace("ConnectivityNode_3_ID",ConnectivityNode_3_ID);
            expMapToXls.replace("ConnectivityNode_4_ID",ConnectivityNode_4_ID);
            expMapToXls.replace("ConnectivityNode_5_ID",ConnectivityNode_5_ID);
            expMapToXls.replace("ConnectivityNode_6_ID",ConnectivityNode_6_ID);
            expMapToXls.replace("TopologicalNode_1_ID",TopologicalNode_1_ID);
            expMapToXls.replace("TopologicalNode_2_ID",TopologicalNode_2_ID);
            expMapToXls.replace("TopologicalNode_3_ID",TopologicalNode_3_ID);
            expMapToXls.replace("TopologicalNode_4_ID",TopologicalNode_4_ID);
            expMapToXls.replace("TopologicalNode_5_ID",TopologicalNode_5_ID);
            expMapToXls.replace("TopologicalNode_6_ID",TopologicalNode_6_ID);
            expMapToXls.replace("Breaker_1_ID",Breaker_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_1_ID",Breaker_1_Terminal_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_2_ID",Breaker_1_Terminal_2_ID);
            expMapToXls.replace("Breaker_2_ID",Breaker_2_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_1_ID",Breaker_2_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_2_ID",Breaker_2_ID_Terminal_2_ID);
            expMapToXls.replace("Breaker_3_ID",Breaker_3_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_1_ID",Breaker_3_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_2_ID",Breaker_3_ID_Terminal_2_ID);
        }

        Map<String,Object> breakerLineMap = new HashMap<>();

        breakerLineMap.put("modEQModel",modEQModel);
        breakerLineMap.put("modTPModel",modTPModel);
        breakerLineMap.put("modSVModel",modSVModel);
        breakerLineMap.put("modSSHModel",modSSHModel);
        breakerLineMap.put("expMapToXls",expMapToXls);
        return breakerLineMap;
    }


    //Add Breaker on a line
    public static Map<String,Object> AddBreakerTrafo(List<Statement> trafoList,Model modelEQ, Model modelTP, Model modelSV,Model modelSSH,Model modEQModel,Model modSSHModel, Model modSVModel, Model modTPModel,String cimns, String cgmesVersion, Model modelTPBD,Map<String,List> expMapToXls, boolean expMap, boolean impMap) {

        Property cnToTn = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        Property tiToTn = ResourceFactory.createProperty(cimns, "TopologicalIsland.TopologicalNodes");
        Property tn = ResourceFactory.createProperty(cimns, "TopologicalNode");
        RDFNode termObj = ResourceFactory.createProperty(cimns, "Terminal"); //TODO check if needed
        Map<String, String> mapIDs = null;
        if (impMap) {
            mapIDs = GetMapIDs(trafoList.getFirst());
        }

        // check original connections
        Statement objStmt = trafoList.getFirst();
        List<Statement> trafoTerminals = GetElementTerminals(modelEQ, objStmt, cimns);
        List<Statement> trafoCN = GetElementConnectivityNodes(modelEQ, trafoTerminals, cimns);
        List<Statement> trafoTN = GetElementTopologicalNodes(modelTP, trafoTerminals, trafoCN, cimns);

//        List<Statement> tieFlowList = modEQModel.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns, "TieFlow")).toList();
//        Property tieToTerminal = ResourceFactory.createProperty(cimns, "TieFlow.Terminal");


        Statement island;
        if (modelSV.listStatements(null, tiToTn, trafoTN.getFirst().getObject()).hasNext()) {
            island = modelSV.listStatements(null, tiToTn, trafoTN.getFirst().getObject()).next();
        } else {
            island = modelSV.listStatements(null, RDF.type, ResourceFactory.createProperty(cimns, "TopologicalIsland")).next();
        }

        // check if TNs are boundary
        //check for side 1
        boolean side1TNboundaryDoBreaker = !modelTPBD.listStatements(trafoTN.getFirst().getObject().asResource(), RDF.type, tn).hasNext();

        //check for side 2
        boolean side2TNboundaryDoBreaker = !modelTPBD.listStatements(trafoTN.get(1).getObject().asResource(), RDF.type, tn).hasNext();

        //logic for the breaker status - if true the trafo is in operation, if false it is disconnected
        // if it is connected - no need to create TN, just CN; if not connected TN is needed
        boolean breaker1Status = breakerStatusLogic(modelSSH, trafoTerminals.getFirst(), cimns);

        //create breakers
        //create breaker 1
        int breakerNumber = 1;
        Resource resBreaker1Term1 = ResourceFactory.createResource("http://res.eu#NAb1t1");
        Resource resBreaker1Term2 = ResourceFactory.createResource("http://res.eu#NAb1t2");
        Resource resBreaker1 = ResourceFactory.createResource("http://res.eu#NAbreaker1");
        if (side1TNboundaryDoBreaker) {
            Map<String, Object> breaker1Map = AddBreaker(modEQModel, modSSHModel, modSVModel, cimns, breaker1Status, cgmesVersion, impMap, mapIDs, breakerNumber);
            resBreaker1Term1 = (Resource) breaker1Map.get("resourceTerm1");
            resBreaker1Term2 = (Resource) breaker1Map.get("resourceTerm2");
            resBreaker1 = (Resource) breaker1Map.get("breakerRes");
            modEQModel = (Model) breaker1Map.get("modelEQ");
            modSSHModel = (Model) breaker1Map.get("modelSSH");
            modSVModel = (Model) breaker1Map.get("modelSV");
        }
        //create breaker 2
        breakerNumber = 2;
        boolean breaker2Status = breakerStatusLogic(modelSSH, trafoTerminals.get(1), cimns);
        Resource resBreaker2Term1 = ResourceFactory.createResource("http://res.eu#NAb2t1");
        Resource resBreaker2Term2 = ResourceFactory.createResource("http://res.eu#NAb2t2");
        Resource resBreaker2 = ResourceFactory.createResource("http://res.eu#NAbreaker2");
        if (side2TNboundaryDoBreaker) {
            Map<String, Object> breaker2Map = AddBreaker(modEQModel, modSSHModel, modSVModel, cimns, breaker2Status, cgmesVersion, impMap, mapIDs, breakerNumber);
            resBreaker2Term1 = (Resource) breaker2Map.get("resourceTerm1");
            resBreaker2Term2 = (Resource) breaker2Map.get("resourceTerm2");
            resBreaker2 = (Resource) breaker2Map.get("breakerRes");
            modEQModel = (Model) breaker2Map.get("modelEQ");
            modSSHModel = (Model) breaker2Map.get("modelSSH");
            modSVModel = (Model) breaker2Map.get("modelSV");
        }

        //add container for the breaker
        RDFNode tnContainer;
        Property tnToCNcontainer = ResourceFactory.createProperty(cimns, "TopologicalNode.ConnectivityNodeContainer");
        Property eqToEQcontainer = ResourceFactory.createProperty(cimns, "Equipment.EquipmentContainer");
        if (modelTP.listStatements(trafoTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(trafoTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else if (modelTPBD.listStatements(trafoTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTPBD.listStatements(trafoTN.getFirst().getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else {
            tnContainer = ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        if (side1TNboundaryDoBreaker) {
            modEQModel.add(ResourceFactory.createStatement(resBreaker1, eqToEQcontainer, tnContainer));
        }

        if (modelTP.listStatements(trafoTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(trafoTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else if (modelTPBD.listStatements(trafoTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).hasNext()) {
            tnContainer = modelTPBD.listStatements(trafoTN.get(1).getObject().asResource(), tnToCNcontainer, (RDFNode) null).next().getObject();
        } else {
            tnContainer = ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        if (side2TNboundaryDoBreaker) {
            modEQModel.add(ResourceFactory.createStatement(resBreaker2, eqToEQcontainer, tnContainer));
        }

        //manage inner connections
        Map<String, Object> innerConMap = TrafoInnerConnections(modEQModel, modTPModel, modSVModel, trafoTN, cimns, trafoTerminals, resBreaker1Term2, resBreaker2Term2, cgmesVersion, island, modelTPBD, breaker1Status, breaker2Status, impMap, mapIDs, side1TNboundaryDoBreaker, side2TNboundaryDoBreaker);
        modEQModel = (Model) innerConMap.get("modEQModel");
        modTPModel = (Model) innerConMap.get("modTPModel");
        modSVModel = (Model) innerConMap.get("modSVModel");
        Resource cn1resBreaker = (Resource) innerConMap.get("cn1res");
        Resource tn1resBreaker = (Resource) innerConMap.get("tn1res");
        Resource cn2resBreaker = (Resource) innerConMap.get("cn2res");
        Resource tn2resBreaker = (Resource) innerConMap.get("tn2res");

        //start 24 May 2024
        if (!cn1resBreaker.getLocalName().equals("NA")) {
            modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
            modTPModel.add(ResourceFactory.createStatement(cn1resBreaker, cnToTn, ResourceFactory.createProperty(tn1resBreaker.toString())));
        }
        if (!cn2resBreaker.getLocalName().equals("NA")) {
            modTPModel.add(ResourceFactory.createStatement(cn2resBreaker, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
            modTPModel.add(ResourceFactory.createStatement(cn2resBreaker, cnToTn, ResourceFactory.createProperty(tn2resBreaker.toString())));
        }
        //end 24 May 2024


        //check Connectivity nodes and Topological nodes at the two ends and connect
        int side = 0;
        boolean innerSide = false;
        Resource cn1res = ResourceFactory.createResource("http://res.eu#NAl1");
        Property termToCn = ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode");
        Property termToConEQ = ResourceFactory.createProperty(cimns, "Terminal.ConductingEquipment");

        List<Statement> termList = GetTerminalsTopologicalNodeMinus1(modelTP,trafoTN.get(side),cimns,trafoTerminals.get(side));
        //add the term1 of the breaker so that it is connected to the CN that is created
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        if (side1TNboundaryDoBreaker) {
            termList.add(ResourceFactory.createStatement(resBreaker1Term1, termToTN, trafoTN.get(side).getObject()));
            //remove the old connection to the TN
            //modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(),termToTN,lineTN.get(side).getObject()));
            //add the term 1 to the TN
            modTPModel.add(ResourceFactory.createStatement(resBreaker1Term1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToTN, trafoTN.get(side).getObject()));

            if (modTPModel.listStatements(null, cnToTn, trafoTN.getFirst().getObject()).hasNext()) {//there is a connectivity node at side 1
                boolean hasMainCN = false;
                for (StmtIterator s = modTPModel.listStatements(null, cnToTn, trafoTN.getFirst().getObject()); s.hasNext();) {
                    Statement stmtCN = s.next();
                    // Statement stmtCN = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).nextStatement();

                    if (modEQModel.listStatements(stmtCN.getSubject(), ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")).hasNext()) {
                        cn1res = stmtCN.getSubject();
                        //need to remove Terminal.ConnectivityNode
                        if (modEQModel.listStatements(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
                            modEQModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
                        }
                        modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));

                        modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
                        modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
                    }
                }

//                if (!hasMainCN){
//                    List<Resource> terminalsCN = new LinkedList<>();
//                    for (Statement t : termList) {
//                        //terminalsCN.add(t.getSubject());
//                        //get cond equipment
//                        Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                        String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                        if (!resType.equals("ACLineSegment")) {
//                            terminalsCN.add(t.getSubject());
//                        }
//                    }
//                    terminalsCN.add(resBreaker1Term1);
//                    Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                    modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                    modTPModel = (Model) cn1Side1Map.get("modelTP");
//                    modSVModel = (Model) cn1Side1Map.get("modelSV");
//                    cn1res = (Resource) cn1Side1Map.get("resource");
//                    modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                    modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                    modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                    modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
//                }


//                Statement stmtCN = modTPModel.listStatements(null, cnToTn, trafoTN.getFirst().getObject()).nextStatement();
//                cn1res = stmtCN.getSubject();
//                //need to remove Terminal.ConnectivityNode
//                if (modEQModel.listStatements(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
//                    modEQModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                }
//                modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
            } else {//there is no CN at side 1, add CN


//                List<Resource> terminalsCN = new LinkedList<>();
//                for (Statement t : termList) {
//                    //terminalsCN.add(t.getSubject());
//                    //get cond equipment
//                    Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                    String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                    if (!resType.equals("ACLineSegment")) {
//                        terminalsCN.add(t.getSubject());
//                    }
//                }
//                terminalsCN.add(resBreaker1Term1);
//                Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                modTPModel = (Model) cn1Side1Map.get("modelTP");
//                modSVModel = (Model) cn1Side1Map.get("modelSV");
//                cn1res = (Resource) cn1Side1Map.get("resource");
//                modEQModel.add(ResourceFactory.createStatement(resBreaker1Term1, termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                modEQModel.add(ResourceFactory.createStatement(cn1res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                modTPModel.add(ResourceFactory.createStatement(cn1res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
            }



//            //check if one of the terminals if also a terminal referenced by a TieFlow and change the reference to be the terminal of the breaker 1
//
//            for (Statement tie : tieFlowList) {
//                if (modEQModel.getRequiredProperty(tie.getSubject(), tieToTerminal).getObject().asResource().equals(lineTerminals.get(side).getSubject())) {
//                    modEQModel.remove(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, lineTerminals.get(side).getSubject()));
//                    modEQModel.add(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, resBreaker1Term1));
//                }
//            }
        }

        side = 1;
        Resource cn2res = ResourceFactory.createResource("http://res.eu#NAl2");

        termList = GetTerminalsTopologicalNodeMinus1(modelTP,trafoTN.get(side),cimns,trafoTerminals.get(side));
        //add the term1 of the breaker so that it is connected to the CN that is created
        if (side2TNboundaryDoBreaker) {
            termList.add(ResourceFactory.createStatement(resBreaker2Term1, termToTN, trafoTN.get(side).getObject()));
            //remove the old connection to the TN
            //modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(),termToTN,lineTN.get(side).getObject()));
            //add the term 1 to the TN
            modTPModel.add(ResourceFactory.createStatement(resBreaker2Term1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToTN, trafoTN.get(side).getObject()));

            if (modTPModel.listStatements(null, cnToTn, trafoTN.get(side).getObject()).hasNext()) {//there is a connectivity node at side 2

                boolean hasMainCN = false;
                for (StmtIterator s = modTPModel.listStatements(null, cnToTn, trafoTN.get(side).getObject()); s.hasNext();) {
                    Statement stmtCN = s.next();
                    // Statement stmtCN = modTPModel.listStatements(null, cnToTn, smTN.getFirst().getObject()).nextStatement();

                    if (modEQModel.listStatements(stmtCN.getSubject(), ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")).hasNext()) {
                        cn2res = stmtCN.getSubject();
                        //need to remove Terminal.ConnectivityNode
                        if (modEQModel.listStatements(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn2res.toString())).hasNext()) {
                            modEQModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn2res.toString())));
                        }
                        modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));

                        modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
                        modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
                    }
                }

//                if (!hasMainCN){
//                    List<Resource> terminalsCN = new LinkedList<>();
//                    for (Statement t : termList) {
//                        //get cond equipment
//                        Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                        String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                        if (!resType.equals("ACLineSegment")) {
//                            terminalsCN.add(t.getSubject());
//                        }
//                    }
//                    terminalsCN.add(resBreaker2Term1);
//                    Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                    modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                    modTPModel = (Model) cn1Side1Map.get("modelTP");
//                    modSVModel = (Model) cn1Side1Map.get("modelSV");
//                    cn2res = (Resource) cn1Side1Map.get("resource");
//                    modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
//                    modEQModel.add(ResourceFactory.createStatement(cn2res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                    modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                    modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
//                }



//                Statement stmtCN = modTPModel.listStatements(null, cnToTn, trafoTN.get(side).getObject()).nextStatement();
//                cn2res = stmtCN.getSubject();
//                //need to remove Terminal.ConnectivityNode
//                if (modEQModel.listStatements(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())).hasNext()) {
//                    modEQModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToCn, ResourceFactory.createProperty(cn1res.toString())));
//                }
//                modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
            } else {//there is no CN at side 2, add CN


//                List<Resource> terminalsCN = new LinkedList<>();
//                for (Statement t : termList) {
//                    //get cond equipment
//                    Resource condEq = modEQModel.getRequiredProperty(t.getSubject(), termToConEQ).getObject().asResource();
//                    String resType = modEQModel.getRequiredProperty(condEq, RDF.type).getObject().asResource().getLocalName();
//                    if (!resType.equals("ACLineSegment")) {
//                        terminalsCN.add(t.getSubject());
//                    }
//                }
//                terminalsCN.add(resBreaker2Term1);
//                Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
//                modEQModel = (Model) cn1Side1Map.get("modelEQ");
//                modTPModel = (Model) cn1Side1Map.get("modelTP");
//                modSVModel = (Model) cn1Side1Map.get("modelSV");
//                cn2res = (Resource) cn1Side1Map.get("resource");
//                modEQModel.add(ResourceFactory.createStatement(resBreaker2Term1, termToCn, ResourceFactory.createProperty(cn2res.toString())));
//                modEQModel.add(ResourceFactory.createStatement(cn2res, ResourceFactory.createProperty("http://griddigit.eu/ext#","ConnectivityNode.isMain"), ResourceFactory.createPlainLiteral("true")));
//
//                modTPModel.add(ResourceFactory.createStatement(cn2res, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
//                modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTn, ResourceFactory.createProperty(trafoTN.get(side).getObject().toString())));
            }



//            //check if one of the terminals if also a terminal referenced by a TieFlow and change the reference to be the terminal of the breaker 1
//            for (Statement tie : tieFlowList) {
//                if (modEQModel.getRequiredProperty(tie.getSubject(), tieToTerminal).getObject().asResource().equals(lineTerminals.get(side).getSubject())) {
//                    modEQModel.remove(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, lineTerminals.get(side).getSubject()));
//                    modEQModel.add(ResourceFactory.createStatement(tie.getSubject(), tieToTerminal, resBreaker2Term1));
//                }
//            }
        }

        if (expMap) {
            List Element_type = expMapToXls.get("Element_type");
            List Element_ID = expMapToXls.get("Element_ID");
            List ConnectivityNode_1_ID = expMapToXls.get("ConnectivityNode_1_ID");
            List ConnectivityNode_2_ID = expMapToXls.get("ConnectivityNode_2_ID");
            List ConnectivityNode_3_ID = expMapToXls.get("ConnectivityNode_3_ID");
            List ConnectivityNode_4_ID = expMapToXls.get("ConnectivityNode_4_ID");
            List ConnectivityNode_5_ID = expMapToXls.get("ConnectivityNode_5_ID");
            List ConnectivityNode_6_ID = expMapToXls.get("ConnectivityNode_6_ID");
            List TopologicalNode_1_ID = expMapToXls.get("TopologicalNode_1_ID");
            List TopologicalNode_2_ID = expMapToXls.get("TopologicalNode_2_ID");
            List TopologicalNode_3_ID = expMapToXls.get("TopologicalNode_3_ID");
            List TopologicalNode_4_ID = expMapToXls.get("TopologicalNode_4_ID");
            List TopologicalNode_5_ID = expMapToXls.get("TopologicalNode_5_ID");
            List TopologicalNode_6_ID = expMapToXls.get("TopologicalNode_6_ID");
            List Breaker_1_ID = expMapToXls.get("Breaker_1_ID");
            List Breaker_1_Terminal_1_ID = expMapToXls.get("Breaker_1_Terminal_1_ID");
            List Breaker_1_Terminal_2_ID = expMapToXls.get("Breaker_1_Terminal_2_ID");
            List Breaker_2_ID = expMapToXls.get("Breaker_2_ID");
            List Breaker_2_ID_Terminal_1_ID = expMapToXls.get("Breaker_2_ID_Terminal_1_ID");
            List Breaker_2_ID_Terminal_2_ID = expMapToXls.get("Breaker_2_ID_Terminal_2_ID");
            List Breaker_3_ID = expMapToXls.get("Breaker_3_ID");
            List Breaker_3_ID_Terminal_1_ID = expMapToXls.get("Breaker_3_ID_Terminal_1_ID");
            List Breaker_3_ID_Terminal_2_ID = expMapToXls.get("Breaker_3_ID_Terminal_2_ID");


            Element_type.add(trafoList.getFirst().getObject().asResource().getLocalName());
            Element_ID.add(trafoList.getFirst().getSubject().getLocalName());
            ConnectivityNode_1_ID.add(cn1res.getLocalName());
            ConnectivityNode_2_ID.add(cn1resBreaker.getLocalName());
            ConnectivityNode_3_ID.add(cn2resBreaker.getLocalName());
            ConnectivityNode_4_ID.add(cn2res.getLocalName());
            ConnectivityNode_5_ID.add("NA");
            ConnectivityNode_6_ID.add("NA");
            TopologicalNode_1_ID.add(trafoTN.getFirst().getObject().asResource().getLocalName());
            TopologicalNode_2_ID.add(tn1resBreaker.getLocalName());
            TopologicalNode_3_ID.add(tn2resBreaker.getLocalName());
            TopologicalNode_4_ID.add(trafoTN.get(1).getObject().asResource().getLocalName());
            TopologicalNode_5_ID.add("NA");
            TopologicalNode_6_ID.add("NA");
            Breaker_1_ID.add(resBreaker1.getLocalName());
            Breaker_1_Terminal_1_ID.add(resBreaker1Term1.getLocalName());
            Breaker_1_Terminal_2_ID.add(resBreaker1Term2.getLocalName());
            Breaker_2_ID.add(resBreaker2.getLocalName());
            Breaker_2_ID_Terminal_1_ID.add(resBreaker2Term1.getLocalName());
            Breaker_2_ID_Terminal_2_ID.add(resBreaker2Term2.getLocalName());
            Breaker_3_ID.add("NA");
            Breaker_3_ID_Terminal_1_ID.add("NA");
            Breaker_3_ID_Terminal_2_ID.add("NA");


            expMapToXls.replace("Element_type",Element_type);
            expMapToXls.replace("Element_ID",Element_ID);
            expMapToXls.replace("ConnectivityNode_1_ID",ConnectivityNode_1_ID);
            expMapToXls.replace("ConnectivityNode_2_ID",ConnectivityNode_2_ID);
            expMapToXls.replace("ConnectivityNode_3_ID",ConnectivityNode_3_ID);
            expMapToXls.replace("ConnectivityNode_4_ID",ConnectivityNode_4_ID);
            expMapToXls.replace("ConnectivityNode_5_ID",ConnectivityNode_5_ID);
            expMapToXls.replace("ConnectivityNode_6_ID",ConnectivityNode_6_ID);
            expMapToXls.replace("TopologicalNode_1_ID",TopologicalNode_1_ID);
            expMapToXls.replace("TopologicalNode_2_ID",TopologicalNode_2_ID);
            expMapToXls.replace("TopologicalNode_3_ID",TopologicalNode_3_ID);
            expMapToXls.replace("TopologicalNode_4_ID",TopologicalNode_4_ID);
            expMapToXls.replace("TopologicalNode_5_ID",TopologicalNode_5_ID);
            expMapToXls.replace("TopologicalNode_6_ID",TopologicalNode_6_ID);
            expMapToXls.replace("Breaker_1_ID",Breaker_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_1_ID",Breaker_1_Terminal_1_ID);
            expMapToXls.replace("Breaker_1_Terminal_2_ID",Breaker_1_Terminal_2_ID);
            expMapToXls.replace("Breaker_2_ID",Breaker_2_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_1_ID",Breaker_2_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_2_ID_Terminal_2_ID",Breaker_2_ID_Terminal_2_ID);
            expMapToXls.replace("Breaker_3_ID",Breaker_3_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_1_ID",Breaker_3_ID_Terminal_1_ID);
            expMapToXls.replace("Breaker_3_ID_Terminal_2_ID",Breaker_3_ID_Terminal_2_ID);
        }

        Map<String,Object> breakerTrafoMap = new HashMap<>();

        breakerTrafoMap.put("modEQModel",modEQModel);
        breakerTrafoMap.put("modTPModel",modTPModel);
        breakerTrafoMap.put("modSVModel",modSVModel);
        breakerTrafoMap.put("modSSHModel",modSSHModel);
        breakerTrafoMap.put("expMapToXls",expMapToXls);
        return breakerTrafoMap;
    }

    //Add ConnectivityNode
    public static Map<String,Object> AddConnectivityNode(Model modelEQ, Model modelTP, Model modelSV, String cimns, String cgmesVersion, Statement originTN, List<Resource> terminalsCN, Model modelTPBD,boolean impMap, Map<String,String> mapIDs,int side,boolean innerSide) {


        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");
        Property ioname = ResourceFactory.createProperty(cimns,"IdentifiedObject.name");
        Property termToCN = ResourceFactory.createProperty(cimns,"Terminal.ConnectivityNode");
        Property cncncontainer = ResourceFactory.createProperty(cimns, "ConnectivityNode.ConnectivityNodeContainer");

        List<String> ids = new LinkedList<>();
        if (impMap) {
            try {
                if (innerSide) {
                    if (side == 0) {
                        String id = mapIDs.get("connectivityNode2ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);

                    } else if (side == 1) {
                        String id = mapIDs.get("connectivityNode3ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                } else if (!innerSide) {
                    if (side == 0) {
                        String id = mapIDs.get("connectivityNode1ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);

                    } else if (side == 1) {
                        String id = mapIDs.get("connectivityNode4ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                }
            }catch (Exception e) {
                ids = GenerateUUID();
            }
        }else{
            ids = GenerateUUID();
        }
//        if (ids.get(1).equals("_404ad71b-c24e-48e4-a21a-6c43a1c52db8")){
//            String stop = "stop";
//        }

        Resource cnRes = ResourceFactory.createResource(cimns + ids.get(1));
        modelEQ.add(ResourceFactory.createStatement(cnRes, RDF.type, ResourceFactory.createProperty(cimns, "ConnectivityNode")));
        if (cgmesVersion.equals("CGMESv3.0")) {
            modelEQ.add(ResourceFactory.createStatement(cnRes, mrid, ResourceFactory.createPlainLiteral(ids.getFirst())));
        }
        modelEQ.add(ResourceFactory.createStatement(cnRes, ioname, ResourceFactory.createPlainLiteral("new node"))); // add a default name
        //System.out.print(originTN.getObject().asResource().toString());
        RDFNode tnContainer;
        if (modelTP.listStatements(originTN.getObject().asResource(),ResourceFactory.createProperty(cimns,"TopologicalNode.ConnectivityNodeContainer"),(RDFNode) null).hasNext()) {
            tnContainer = modelTP.listStatements(originTN.getObject().asResource(), ResourceFactory.createProperty(cimns, "TopologicalNode.ConnectivityNodeContainer"), (RDFNode) null).next().getObject();
        }else if (modelTPBD.listStatements(originTN.getObject().asResource(),ResourceFactory.createProperty(cimns,"TopologicalNode.ConnectivityNodeContainer"),(RDFNode) null).hasNext()){
            tnContainer = modelTPBD.listStatements(originTN.getObject().asResource(), ResourceFactory.createProperty(cimns, "TopologicalNode.ConnectivityNodeContainer"), (RDFNode) null).next().getObject();
        }else{
            tnContainer=ResourceFactory.createProperty(cimns, "_NoContainer");
            //TODO issue warning
        }
        modelEQ.add(ResourceFactory.createStatement(cnRes, cncncontainer, tnContainer));

        //add the terminals that need to connect to the CN
        for (Resource term : terminalsCN){
//            if (modelEQ.listStatements(term,termToCN,(RDFNode) null).hasNext()){
//                System.out.print("This terminal already has association with ConnectivityNode. Terminal: " + term.getLocalName() + "\n");
//            }
//            if (term.getLocalName().equals("_de8844e2-68b0-5ac3-f173-f08aa248a98f")){
//                int k=1;
//            }
            modelEQ.add(ResourceFactory.createStatement(term, termToCN, ResourceFactory.createProperty(cnRes.toString())));
        }

        Map<String,Object> cnMap = new HashMap<>();
        cnMap.put("resource",cnRes);
        cnMap.put("modelEQ",modelEQ);
        cnMap.put("modelTP",modelTP);
        cnMap.put("modelSV",modelSV);

        return cnMap;
    }


    //Add TopologicalNode
    public static Map<String,Object> AddTopologicalNode(Model modelEQ, Model modelTP, Model modelSV, String cimns, Statement island, Resource cnRes,String cgmesVersion,boolean impMap, Map<String,String> mapIDs,int side,boolean innerSide) {
        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");
        Property ioname = ResourceFactory.createProperty(cimns,"IdentifiedObject.name");
        Property tncnContainer = ResourceFactory.createProperty(cimns,"TopologicalNode.ConnectivityNodeContainer");
        Property cnContainer = ResourceFactory.createProperty(cimns,"ConnectivityNode.ConnectivityNodeContainer");
        // add in TP,
        List<String> ids = new LinkedList<>();
        if (impMap) {
            try {
                if (innerSide) {
                    if (side == 0) {
                        String id = mapIDs.get("topologicalNode2ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);

                    } else if (side == 1) {
                        String id = mapIDs.get("topologicalNode3ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                } else if (!innerSide) {
                    if (side == 0) {
                        String id = mapIDs.get("topologicalNode1ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);

                    } else if (side == 1) {
                        String id = mapIDs.get("topologicalNode4ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                }
            }catch (Exception e) {
                ids = GenerateUUID();
            }
        }else{
            ids = GenerateUUID();
        }
        Resource tnRes = ResourceFactory.createResource(cimns + ids.get(1));
        modelTP.add(ResourceFactory.createStatement(tnRes, RDF.type, ResourceFactory.createProperty(cimns, "TopologicalNode")));
        if (cgmesVersion.equals("CGMESv3.0")) {
            modelTP.add(ResourceFactory.createStatement(tnRes, mrid, ResourceFactory.createPlainLiteral(ids.getFirst())));
        }

        if (!modelTP.listStatements(tnRes,ioname,(RDFNode) null).hasNext()){
            modelTP.add(ResourceFactory.createStatement(tnRes, ioname, ResourceFactory.createPlainLiteral(modelEQ.getRequiredProperty(cnRes,ioname).getObject().toString())));
        }

        modelTP.add(ResourceFactory.createStatement(tnRes, tncnContainer, ResourceFactory.createProperty(modelEQ.getRequiredProperty(cnRes,cnContainer).getObject().toString())));

        for (StmtIterator t = modelEQ.listStatements(null, ResourceFactory.createProperty(cimns, "Terminal.ConnectivityNode"), cnRes); t.hasNext(); ) { // loop on Terminal classes
            Statement stmtT = t.next();
            modelTP.add(ResourceFactory.createStatement(stmtT.getSubject(), RDF.type, ResourceFactory.createProperty(cimns, "Terminal")));
            modelTP.add(ResourceFactory.createStatement(stmtT.getSubject(), ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode"), tnRes));
        }

        //add in SV ref to the island
        modelSV.add(ResourceFactory.createStatement(island.getSubject(),ResourceFactory.createProperty(cimns,"TopologicalIsland.TopologicalNodes"),tnRes));
        //add SvVoltage
        if (!cgmesVersion.equals("CGMESv3.0") && !modelSV.listStatements(null, ResourceFactory.createProperty(cimns,"SvVoltage.TopologicalNode"), ResourceFactory.createResource(tnRes.toString())).hasNext()) {// because for CGMES v3 there is no need to add 0 voltage
            List<String> idssvvoltage = GenerateUUID();
            Resource svvoltageRes = ResourceFactory.createResource(cimns + idssvvoltage.get(1));
            modelSV.add(ResourceFactory.createStatement(svvoltageRes, RDF.type, ResourceFactory.createProperty(cimns, "SvVoltage")));
            modelSV.add(ResourceFactory.createStatement(svvoltageRes, ResourceFactory.createProperty(cimns,"SvVoltage.v"), ResourceFactory.createPlainLiteral("0")));
            modelSV.add(ResourceFactory.createStatement(svvoltageRes, ResourceFactory.createProperty(cimns,"SvVoltage.angle"), ResourceFactory.createPlainLiteral("0")));
            modelSV.add(ResourceFactory.createStatement(svvoltageRes, ResourceFactory.createProperty(cimns,"SvVoltage.TopologicalNode"), ResourceFactory.createResource(tnRes.toString())));
        }

        //TODO see if something needs to be done with SvPowerFlow on Terminals

        Map<String,Object> tnMap = new HashMap<>();
        tnMap.put("resource",tnRes);
        tnMap.put("modelSV",modelSV);
        tnMap.put("modelTP",modelTP);

        return tnMap;
    }

    //Add Terminal
    public static Map<String,Object> AddTerminal(Model modelEQ, Model modelSSH, String cimns, String seqnumber, String cgmesVersion,Resource breakerRes,boolean impMap,Map<String,String> mapIDs,int breakerNumber) {
        List<String> ids = new LinkedList<>();
        if (impMap) {
            try{
                if (breakerNumber == 1) {
                    if (seqnumber.equals("1")) {
                        String id = mapIDs.get("breaker1IDTerminal1ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    } else if (seqnumber.equals("2")) {
                        String id = mapIDs.get("breaker1IDTerminal2ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                }else if (breakerNumber == 2) {
                    if (seqnumber.equals("1")) {
                        String id = mapIDs.get("breaker2IDTerminal1ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    } else if (seqnumber.equals("2")) {
                        String id = mapIDs.get("breaker2IDTerminal2ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                }else if (breakerNumber == 3) {
                    if (seqnumber.equals("1")) {
                        String id = mapIDs.get("breaker3IDTerminal1ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    } else if (seqnumber.equals("2")) {
                        String id = mapIDs.get("breaker3IDTerminal2ID");
                        ids.add(id.split("_", 2)[1]);
                        ids.add(id);
                    }
                }
            }catch (Exception e){
                ids = GenerateUUID();
            }
        }else{
            ids = GenerateUUID();
        }
        Resource termRes = ResourceFactory.createResource(cimns + ids.get(1));
        Property mrid = ResourceFactory.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID");
        Property ioname = ResourceFactory.createProperty(cimns,"IdentifiedObject.name");
        Property connected = ResourceFactory.createProperty(cimns,"ACDCTerminal.connected");
        Property snumber = ResourceFactory.createProperty(cimns,"ACDCTerminal.sequenceNumber");
        Property termConEq = ResourceFactory.createProperty(cimns,"Terminal.ConductingEquipment");
        Property termPhases = ResourceFactory.createProperty(cimns, "Terminal.phases");
        modelEQ.add(ResourceFactory.createStatement(termRes, RDF.type, ResourceFactory.createProperty(cimns, "Terminal")));
        modelEQ.add(ResourceFactory.createStatement(termRes, ioname, ResourceFactory.createPlainLiteral("new terminal"))); // add a default name
        if (cgmesVersion.equals("CGMESv3.0")) {
            modelEQ.add(ResourceFactory.createStatement(termRes, mrid, ResourceFactory.createPlainLiteral(ids.getFirst())));
        }
        modelEQ.add(ResourceFactory.createStatement(termRes, snumber, ResourceFactory.createPlainLiteral(seqnumber)));
        modelEQ.add(ResourceFactory.createStatement(termRes, termConEq, ResourceFactory.createProperty(breakerRes.toString())));
        modelEQ.add(ResourceFactory.createStatement(termRes, termPhases, ResourceFactory.createProperty(cimns,"PhaseCode.ABC")));
        modelSSH.add(ResourceFactory.createStatement(termRes, RDF.type, ResourceFactory.createProperty(cimns, "Terminal")));
        modelSSH.add(ResourceFactory.createStatement(termRes, connected, ResourceFactory.createPlainLiteral("true")));

        Map<String,Object> termMap = new HashMap<>();
        termMap.put("resource",termRes);
        termMap.put("modelEQ",modelEQ);
        termMap.put("modelSSH",modelSSH);

        return termMap;
    }

    //Generate UUID
    public static List<String> GenerateUUID() {
        List<String> uuidList = new LinkedList<>();
        String uuid = String.valueOf(UUID.randomUUID());
        String uuid_ = "_"+uuid;
        uuidList.add(uuid);
        uuidList.add(uuid_);

        return uuidList;
    }

    //Generate UUID
    public static Map<String,String> GetMapIDs(Statement objStmt) {
        Map<String,String> mapIDs = new HashMap<>();
        for (StmtIterator s = mapModel.listStatements(objStmt.getSubject(),null,(RDFNode) null); s.hasNext();) {
            Statement stmt = s.next();
            if (!stmt.getPredicate().equals(RDF.type)){
                mapIDs.put(stmt.getPredicate().getLocalName(),stmt.getObject().toString());
            }
        }

        return mapIDs;
    }

    public static Map<String,Object> LineInnerConnections(Model modEQModel, Model modTPModel, Model modSVModel, List<Statement> lineTN, String cimns, List<Statement> lineTerminals, Resource resBreakerTerm1, Resource resBreakerTerm2, String cgmesVersion, Statement island, Model modelTPBD,boolean breaker1Status, boolean breaker2Status, boolean impMap, Map<String,String> mapIDs,boolean side1TNboundaryDoBreaker, boolean side2TNboundaryDoBreaker) {

        Property cnToTN = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        Property tnToBV = ResourceFactory.createProperty(cimns, "TopologicalNode.BaseVoltage");
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        RDFNode termObj = ResourceFactory.createProperty(cimns, "Terminal"); //TODO check if needed
        //add 2 CNs
        int side = 0;
        boolean innerSide = true;
        List<Resource> terminalsCN = new LinkedList<>();
        Resource cn1res = ResourceFactory.createResource("http://res.eu#NA");
        Resource tn1res = ResourceFactory.createResource("http://res.eu#NA");
        if (side1TNboundaryDoBreaker) {
            terminalsCN.add(lineTerminals.get(side).getSubject());
            terminalsCN.add(resBreakerTerm1);
            Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
            modEQModel = (Model) cn1Side1Map.get("modelEQ");
            modTPModel = (Model) cn1Side1Map.get("modelTP");
            modSVModel = (Model) cn1Side1Map.get("modelSV");
            cn1res = (Resource) cn1Side1Map.get("resource");
            //tn1res = ResourceFactory.createResource(cimns + "NA");


            if (!breaker1Status) { // breaker is open, add also TN
                Map<String, Object> tnMap = AddTopologicalNode(modEQModel, modTPModel, modSVModel, cimns, island, cn1res, cgmesVersion, impMap, mapIDs, side, innerSide);
                tn1res = (Resource) tnMap.get("resource");
                modTPModel = (Model) tnMap.get("modelTP");
                modSVModel = (Model) tnMap.get("modelSV");
                modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTN, ResourceFactory.createProperty(tn1res.toString())));
                //get base voltage of the topologicalnode and assign it to the new one
                RDFNode tnBV = null;
                if (modTPModel.listStatements(lineTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modTPModel.getRequiredProperty(lineTN.get(side).getObject().asResource(), tnToBV).getObject();
                } else if (modelTPBD.listStatements(lineTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modelTPBD.getRequiredProperty(lineTN.get(side).getObject().asResource(), tnToBV).getObject();
                }
                if (tnBV != null) {
                    modTPModel.add(ResourceFactory.createStatement(tn1res, tnToBV, tnBV));
                }
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, ResourceFactory.createProperty(tn1res.toString())));
                modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, lineTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToTN, ResourceFactory.createProperty(tn1res.toString())));
            } else {
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, lineTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToTN, lineTN.get(side).getObject()));
                tn1res = lineTN.get(side).getObject().asResource();
            }
        }


        side = 1;
        terminalsCN = new LinkedList<>();
        Resource cn2res = ResourceFactory.createResource("http://res.eu#NA");
        Resource tn2res = ResourceFactory.createResource("http://res.eu#NA");
        if (side2TNboundaryDoBreaker) {
            terminalsCN.add(lineTerminals.get(side).getSubject());
            terminalsCN.add(resBreakerTerm2);
            Map<String, Object> cn2Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, lineTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
            modEQModel = (Model) cn2Side1Map.get("modelEQ");
            modTPModel = (Model) cn2Side1Map.get("modelTP");
            modSVModel = (Model) cn2Side1Map.get("modelSV");
            cn2res = (Resource) cn2Side1Map.get("resource");
            //tn2res = ResourceFactory.createResource(cimns + "NA");


            if (!breaker2Status) { // breaker is open, add also TN
                Map<String, Object> tnMap = AddTopologicalNode(modEQModel, modTPModel, modSVModel, cimns, island, cn2res, cgmesVersion, impMap, mapIDs, side, innerSide);
                tn2res = (Resource) tnMap.get("resource");
                modTPModel = (Model) tnMap.get("modelTP");
                modSVModel = (Model) tnMap.get("modelSV");
                modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTN, ResourceFactory.createProperty(tn2res.toString())));
                //get base voltage of the topologicalnode and assign it to the new one
                RDFNode tnBV = null;
                if (modTPModel.listStatements(lineTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modTPModel.getRequiredProperty(lineTN.get(side).getObject().asResource(), tnToBV).getObject();
                } else if (modelTPBD.listStatements(lineTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modelTPBD.getRequiredProperty(lineTN.get(side).getObject().asResource(), tnToBV).getObject();
                }
                if (tnBV != null) {
                    modTPModel.add(ResourceFactory.createStatement(tn2res, tnToBV, tnBV));
                }
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, ResourceFactory.createProperty(tn2res.toString())));
                modTPModel.remove(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, lineTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, ResourceFactory.createProperty(tn2res.toString())));
            } else {
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(lineTerminals.get(side).getSubject(), termToTN, lineTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, lineTN.get(side).getObject()));
                tn2res = lineTN.get(side).getObject().asResource();
            }
        }

        Map<String,Object> breakerConMap = new HashMap<>();
        breakerConMap.put("modEQModel",modEQModel);
        breakerConMap.put("modTPModel",modTPModel);
        breakerConMap.put("modSVModel",modSVModel);
        breakerConMap.put("cn1res",cn1res);
        breakerConMap.put("tn1res",tn1res);
        breakerConMap.put("cn2res",cn2res);
        breakerConMap.put("tn2res",tn2res);

        return breakerConMap;
    }


    public static Map<String,Object> TrafoInnerConnections(Model modEQModel, Model modTPModel, Model modSVModel, List<Statement> trafoTN, String cimns, List<Statement> trafoTerminals, Resource resBreakerTerm1, Resource resBreakerTerm2, String cgmesVersion, Statement island, Model modelTPBD,boolean breaker1Status, boolean breaker2Status, boolean impMap, Map<String,String> mapIDs,boolean side1TNboundaryDoBreaker, boolean side2TNboundaryDoBreaker) {

        Property cnToTN = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        Property tnToBV = ResourceFactory.createProperty(cimns, "TopologicalNode.BaseVoltage");
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        RDFNode termObj = ResourceFactory.createProperty(cimns, "Terminal"); //TODO check if needed
        //add 2 CNs
        int side = 0;
        boolean innerSide = true;
        List<Resource> terminalsCN = new LinkedList<>();
        Resource cn1res = ResourceFactory.createResource("http://res.eu#NA");
        Resource tn1res = ResourceFactory.createResource("http://res.eu#NA");
        if (side1TNboundaryDoBreaker) {
            terminalsCN.add(trafoTerminals.get(side).getSubject());
            terminalsCN.add(resBreakerTerm1);
            Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
            modEQModel = (Model) cn1Side1Map.get("modelEQ");
            modTPModel = (Model) cn1Side1Map.get("modelTP");
            modSVModel = (Model) cn1Side1Map.get("modelSV");
            cn1res = (Resource) cn1Side1Map.get("resource");
            //tn1res = ResourceFactory.createResource(cimns + "NA");


            if (!breaker1Status) { // breaker is open, add also TN
                Map<String, Object> tnMap = AddTopologicalNode(modEQModel, modTPModel, modSVModel, cimns, island, cn1res, cgmesVersion, impMap, mapIDs, side, innerSide);
                tn1res = (Resource) tnMap.get("resource");
                modTPModel = (Model) tnMap.get("modelTP");
                modSVModel = (Model) tnMap.get("modelSV");
                modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTN, ResourceFactory.createProperty(tn1res.toString())));
                //get base voltage of the topologicalnode and assign it to the new one
                RDFNode tnBV = null;
                if (modTPModel.listStatements(trafoTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modTPModel.getRequiredProperty(trafoTN.get(side).getObject().asResource(), tnToBV).getObject();
                } else if (modelTPBD.listStatements(trafoTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modelTPBD.getRequiredProperty(trafoTN.get(side).getObject().asResource(), tnToBV).getObject();
                }
                if (tnBV != null) {
                    modTPModel.add(ResourceFactory.createStatement(tn1res, tnToBV, tnBV));
                }
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, ResourceFactory.createProperty(tn1res.toString())));
                modTPModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, trafoTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToTN, ResourceFactory.createProperty(tn1res.toString())));
            } else {
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, trafoTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm1, termToTN, trafoTN.get(side).getObject()));
                tn1res = trafoTN.get(side).getObject().asResource();
            }
        }


        side = 1;
        terminalsCN = new LinkedList<>();
        Resource cn2res = ResourceFactory.createResource("http://res.eu#NA");
        Resource tn2res = ResourceFactory.createResource("http://res.eu#NA");
        if (side2TNboundaryDoBreaker) {
            terminalsCN.add(trafoTerminals.get(side).getSubject());
            terminalsCN.add(resBreakerTerm2);
            Map<String, Object> cn2Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, trafoTN.get(side), terminalsCN, modelTPBD, impMap, mapIDs, side, innerSide);
            modEQModel = (Model) cn2Side1Map.get("modelEQ");
            modTPModel = (Model) cn2Side1Map.get("modelTP");
            modSVModel = (Model) cn2Side1Map.get("modelSV");
            cn2res = (Resource) cn2Side1Map.get("resource");
            //tn2res = ResourceFactory.createResource(cimns + "NA");


            if (!breaker2Status) { // breaker is open, add also TN
                Map<String, Object> tnMap = AddTopologicalNode(modEQModel, modTPModel, modSVModel, cimns, island, cn2res, cgmesVersion, impMap, mapIDs, side, innerSide);
                tn2res = (Resource) tnMap.get("resource");
                modTPModel = (Model) tnMap.get("modelTP");
                modSVModel = (Model) tnMap.get("modelSV");
                modTPModel.add(ResourceFactory.createStatement(cn2res, cnToTN, ResourceFactory.createProperty(tn2res.toString())));
                //get base voltage of the topologicalnode and assign it to the new one
                RDFNode tnBV = null;
                if (modTPModel.listStatements(trafoTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modTPModel.getRequiredProperty(trafoTN.get(side).getObject().asResource(), tnToBV).getObject();
                } else if (modelTPBD.listStatements(trafoTN.get(side).getObject().asResource(), tnToBV, (RDFNode) null).hasNext()) {
                    tnBV = modelTPBD.getRequiredProperty(trafoTN.get(side).getObject().asResource(), tnToBV).getObject();
                }
                if (tnBV != null) {
                    modTPModel.add(ResourceFactory.createStatement(tn2res, tnToBV, tnBV));
                }
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, ResourceFactory.createProperty(tn2res.toString())));
                modTPModel.remove(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, trafoTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, ResourceFactory.createProperty(tn2res.toString())));
            } else {
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(trafoTerminals.get(side).getSubject(), termToTN, trafoTN.get(side).getObject()));
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
                modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, trafoTN.get(side).getObject()));
                tn2res = trafoTN.get(side).getObject().asResource();
            }
        }

        Map<String,Object> breakerConMap = new HashMap<>();
        breakerConMap.put("modEQModel",modEQModel);
        breakerConMap.put("modTPModel",modTPModel);
        breakerConMap.put("modSVModel",modSVModel);
        breakerConMap.put("cn1res",cn1res);
        breakerConMap.put("tn1res",tn1res);
        breakerConMap.put("cn2res",cn2res);
        breakerConMap.put("tn2res",tn2res);

        return breakerConMap;
    }

    public static Map<String,Object> SMInnerConnections(Model modEQModel, Model modTPModel, Model modSVModel, List<Statement> smTN, String cimns, List<Statement> smTerminals, Resource resBreakerTerm2, String cgmesVersion, Statement island, Model modelTPBD,boolean breakerStatus,boolean impMap, Map<String,String> mapIDs) {

        Property cnToTN = ResourceFactory.createProperty(cimns, "ConnectivityNode.TopologicalNode");
        Property tnToBV = ResourceFactory.createProperty(cimns, "TopologicalNode.BaseVoltage");
        Property termToTN = ResourceFactory.createProperty(cimns, "Terminal.TopologicalNode");
        //add 2 CNs
        int side = 0;
        boolean innerSide = true;
        List<Resource> terminalsCN = new LinkedList<>();
        terminalsCN.add(smTerminals.get(side).getSubject());
        terminalsCN.add(resBreakerTerm2);
        Map<String, Object> cn1Side1Map = AddConnectivityNode(modEQModel, modTPModel, modSVModel, cimns, cgmesVersion, smTN.get(side), terminalsCN, modelTPBD,impMap,mapIDs,side,innerSide);
        modEQModel = (Model) cn1Side1Map.get("modelEQ");
        modTPModel = (Model) cn1Side1Map.get("modelTP");
        modSVModel = (Model) cn1Side1Map.get("modelSV");
        Resource cn1res = (Resource) cn1Side1Map.get("resource");
        Resource tn1res;// = ResourceFactory.createResource(cimns+ "NA");
        RDFNode termObj = ResourceFactory.createProperty(cimns,"Terminal"); //TODO check if needed

        if (!breakerStatus) { // breaker is open, add also TN
            Map<String,Object> tnMap = AddTopologicalNode(modEQModel, modTPModel, modSVModel,cimns,island,cn1res,cgmesVersion,impMap,mapIDs,side,innerSide);
            tn1res = (Resource) tnMap.get("resource");
            modTPModel = (Model) tnMap.get("modelTP");
            modSVModel = (Model) tnMap.get("modelSV");
            modTPModel.add(ResourceFactory.createStatement(cn1res, cnToTN, ResourceFactory.createProperty(tn1res.toString())));
            //get base voltage of the topologicalnode and assign it to the new one
            RDFNode tnBV = null;
            if (modTPModel.listStatements(smTN.get(side).getObject().asResource(),tnToBV, (RDFNode) null).hasNext()) {
                tnBV = modTPModel.getRequiredProperty(smTN.get(side).getObject().asResource(), tnToBV).getObject();
            }else if (modelTPBD.listStatements(smTN.get(side).getObject().asResource(),tnToBV, (RDFNode) null).hasNext()) {
                tnBV = modelTPBD.getRequiredProperty(smTN.get(side).getObject().asResource(), tnToBV).getObject();
            }
            if (tnBV != null) {
                modTPModel.add(ResourceFactory.createStatement(tn1res, tnToBV, tnBV));
            }
            modTPModel.add(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), termToTN, ResourceFactory.createProperty(tn1res.toString())));
            modTPModel.remove(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), termToTN, smTN.get(side).getObject()));
            modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, ResourceFactory.createProperty(tn1res.toString())));
        }else{
            modTPModel.add(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(smTerminals.get(side).getSubject(), termToTN, smTN.get(side).getObject()));
            modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, RDF.type, termObj)); //TODO check this if needed added 27 Mar 2024
            modTPModel.add(ResourceFactory.createStatement(resBreakerTerm2, termToTN, smTN.get(side).getObject()));
            tn1res = smTN.get(side).getObject().asResource();
        }

        Map<String,Object> breakerConMap = new HashMap<>();
        breakerConMap.put("modEQModel",modEQModel);
        breakerConMap.put("modTPModel",modTPModel);
        breakerConMap.put("modSVModel",modSVModel);
        breakerConMap.put("cn1res",cn1res);
        breakerConMap.put("tn1res",tn1res);

        return breakerConMap;
    }

    private static void exportMapping(Map<String,List> expMapToXls) throws FileNotFoundException {

        String sheetname = "Mapping";
        String title ="Save file";
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Element type");
        firstRow.createCell(1).setCellValue("Element ID");
        firstRow.createCell(2).setCellValue("ConnectivityNode 1 ID");
        firstRow.createCell(3).setCellValue("ConnectivityNode 2 ID");
        firstRow.createCell(4).setCellValue("ConnectivityNode 3 ID");
        firstRow.createCell(5).setCellValue("ConnectivityNode 4 ID");
        firstRow.createCell(6).setCellValue("ConnectivityNode 5 ID");
        firstRow.createCell(7).setCellValue("ConnectivityNode 6 ID");
        firstRow.createCell(8).setCellValue("TopologicalNode 1 ID");
        firstRow.createCell(9).setCellValue("TopologicalNode 2 ID");
        firstRow.createCell(10).setCellValue("TopologicalNode 3 ID");
        firstRow.createCell(11).setCellValue("TopologicalNode 4 ID");
        firstRow.createCell(12).setCellValue("TopologicalNode 5 ID");
        firstRow.createCell(13).setCellValue("TopologicalNode 6 ID");
        firstRow.createCell(14).setCellValue("Breaker 1 ID");
        firstRow.createCell(15).setCellValue("Breaker 1 Terminal 1 ID");
        firstRow.createCell(16).setCellValue("Breaker 1 Terminal 2 ID");
        firstRow.createCell(17).setCellValue("Breaker 2 ID");
        firstRow.createCell(18).setCellValue("Breaker 2 ID Terminal 1 ID");
        firstRow.createCell(19).setCellValue("Breaker 2 ID Terminal 2 ID");
        firstRow.createCell(20).setCellValue("Breaker 3 ID");
        firstRow.createCell(21).setCellValue("Breaker 3 ID Terminal 1 ID");
        firstRow.createCell(22).setCellValue("Breaker 3 ID Terminal 2 ID");

        List column1 = expMapToXls.get("Element_type");
        List column2 = expMapToXls.get("Element_ID");
        List column3 = expMapToXls.get("ConnectivityNode_1_ID");
        List column4 = expMapToXls.get("ConnectivityNode_2_ID");
        List column5 = expMapToXls.get("ConnectivityNode_3_ID");
        List column6 = expMapToXls.get("ConnectivityNode_4_ID");
        List column7 = expMapToXls.get("ConnectivityNode_5_ID");
        List column8 = expMapToXls.get("ConnectivityNode_6_ID");
        List column9 = expMapToXls.get("TopologicalNode_1_ID");
        List column10 = expMapToXls.get("TopologicalNode_2_ID");
        List column11 = expMapToXls.get("TopologicalNode_3_ID");
        List column12 = expMapToXls.get("TopologicalNode_4_ID");
        List column13 = expMapToXls.get("TopologicalNode_5_ID");
        List column14 = expMapToXls.get("TopologicalNode_6_ID");
        List column15 = expMapToXls.get("Breaker_1_ID");
        List column16 = expMapToXls.get("Breaker_1_Terminal_1_ID");
        List column17 = expMapToXls.get("Breaker_1_Terminal_2_ID");
        List column18 = expMapToXls.get("Breaker_2_ID");
        List column19 = expMapToXls.get("Breaker_2_ID_Terminal_1_ID");
        List column20 = expMapToXls.get("Breaker_2_ID_Terminal_2_ID");
        List column21 = expMapToXls.get("Breaker_3_ID");
        List column22 = expMapToXls.get("Breaker_3_ID_Terminal_1_ID");
        List column23 = expMapToXls.get("Breaker_3_ID_Terminal_2_ID");

        for (int row=0; row<column1.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = column1.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = column2.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = column3.get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = column4.get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }

            Object celValue4 = column5.get(row);
            try {
                if (celValue4 != null && Double.parseDouble(celValue4.toString()) != 0.0) {
                    xssfRow.createCell(4).setCellValue(Double.parseDouble(celValue4.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(4).setCellValue(celValue4.toString());
            }

            Object celValue5 = column6.get(row);
            try {
                if (celValue5 != null && Double.parseDouble(celValue5.toString()) != 0.0) {
                    xssfRow.createCell(5).setCellValue(Double.parseDouble(celValue5.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(5).setCellValue(celValue5.toString());
            }

            Object celValue6 = column7.get(row);
            try {
                if (celValue6 != null && Double.parseDouble(celValue6.toString()) != 0.0) {
                    xssfRow.createCell(6).setCellValue(Double.parseDouble(celValue6.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(6).setCellValue(celValue6.toString());
            }

            Object celValue7 = column8.get(row);
            try {
                if (celValue7 != null && Double.parseDouble(celValue7.toString()) != 0.0) {
                    xssfRow.createCell(7).setCellValue(Double.parseDouble(celValue7.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(7).setCellValue(celValue7.toString());
            }

            Object celValue8 = column9.get(row);
            try {
                if (celValue8 != null && Double.parseDouble(celValue8.toString()) != 0.0) {
                    xssfRow.createCell(8).setCellValue(Double.parseDouble(celValue8.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(8).setCellValue(celValue8.toString());
            }

            Object celValue9 = column10.get(row);
            try {
                if (celValue9 != null && Double.parseDouble(celValue9.toString()) != 0.0) {
                    xssfRow.createCell(9).setCellValue(Double.parseDouble(celValue9.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(9).setCellValue(celValue9.toString());
            }

            Object celValue10 = column11.get(row);
            try {
                if (celValue10 != null && Double.parseDouble(celValue10.toString()) != 0.0) {
                    xssfRow.createCell(10).setCellValue(Double.parseDouble(celValue10.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(10).setCellValue(celValue10.toString());
            }

            Object celValue11 = column12.get(row);
            try {
                if (celValue11 != null && Double.parseDouble(celValue11.toString()) != 0.0) {
                    xssfRow.createCell(11).setCellValue(Double.parseDouble(celValue11.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(11).setCellValue(celValue11.toString());
            }

            Object celValue12 = column13.get(row);
            try {
                if (celValue12 != null && Double.parseDouble(celValue12.toString()) != 0.0) {
                    xssfRow.createCell(12).setCellValue(Double.parseDouble(celValue12.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(12).setCellValue(celValue12.toString());
            }

            Object celValue13 = column14.get(row);
            try {
                if (celValue13 != null && Double.parseDouble(celValue13.toString()) != 0.0) {
                    xssfRow.createCell(13).setCellValue(Double.parseDouble(celValue13.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(13).setCellValue(celValue13.toString());
            }

            Object celValue14 = column15.get(row);
            try {
                if (celValue14 != null && Double.parseDouble(celValue14.toString()) != 0.0) {
                    xssfRow.createCell(14).setCellValue(Double.parseDouble(celValue14.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(14).setCellValue(celValue14.toString());
            }

            Object celValue15 = column16.get(row);
            try {
                if (celValue15 != null && Double.parseDouble(celValue15.toString()) != 0.0) {
                    xssfRow.createCell(15).setCellValue(Double.parseDouble(celValue15.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(15).setCellValue(celValue15.toString());
            }

            Object celValue16 = column17.get(row);
            try {
                if (celValue16 != null && Double.parseDouble(celValue16.toString()) != 0.0) {
                    xssfRow.createCell(16).setCellValue(Double.parseDouble(celValue16.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(16).setCellValue(celValue16.toString());
            }

            Object celValue17 = column18.get(row);
            try {
                if (celValue17 != null && Double.parseDouble(celValue17.toString()) != 0.0) {
                    xssfRow.createCell(17).setCellValue(Double.parseDouble(celValue17.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(17).setCellValue(celValue17.toString());
            }

            Object celValue18 = column19.get(row);
            try {
                if (celValue18 != null && Double.parseDouble(celValue18.toString()) != 0.0) {
                    xssfRow.createCell(18).setCellValue(Double.parseDouble(celValue18.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(18).setCellValue(celValue18.toString());
            }

            Object celValue19 = column20.get(row);
            try {
                if (celValue19 != null && Double.parseDouble(celValue19.toString()) != 0.0) {
                    xssfRow.createCell(19).setCellValue(Double.parseDouble(celValue19.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(19).setCellValue(celValue19.toString());
            }

            Object celValue20 = column21.get(row);
            try {
                if (celValue20 != null && Double.parseDouble(celValue20.toString()) != 0.0) {
                    xssfRow.createCell(20).setCellValue(Double.parseDouble(celValue20.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(20).setCellValue(celValue20.toString());
            }

            Object celValue21 = column22.get(row);
            try {
                if (celValue21 != null && Double.parseDouble(celValue21.toString()) != 0.0) {
                    xssfRow.createCell(21).setCellValue(Double.parseDouble(celValue21.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(21).setCellValue(celValue21.toString());
            }

            Object celValue22 = column23.get(row);
            try {
                if (celValue22 != null && Double.parseDouble(celValue22.toString()) != 0.0) {
                    xssfRow.createCell(22).setCellValue(Double.parseDouble(celValue22.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(22).setCellValue(celValue22.toString());
            }

        }

        sheetname = "TN-CN Mapping";
        sheet = workbook.createSheet(sheetname);
        firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("TopologicalNode MRID");
        firstRow.createCell(1).setCellValue("ConnectivityNode MRID");

        column1 = expMapToXls.get("TopologicalNode_main_ID");
        column2 = expMapToXls.get("ConnectivityNode_main_ID");

        for (int row=0; row<column1.size();row++) {
            XSSFRow xssfRow = sheet.createRow(row + 1);

            Object celValue = column1.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e) {
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = column2.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }
        }


        File saveFile = InstanceDataFactory.filesavecustom("Excel files", List.of("*.xlsx"),title,"");
        if (saveFile != null) {
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}

