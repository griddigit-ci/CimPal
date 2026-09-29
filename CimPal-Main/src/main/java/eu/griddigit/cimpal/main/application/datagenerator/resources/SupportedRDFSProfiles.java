/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SupportedRDFSProfiles {

    private static final Logger LOG = LoggerFactory.getLogger(SupportedRDFSProfiles.class);


    /** Name of the bundled ENTSO-E Network Code Profiles 2.4 entry. */
    public static final String NCP_2_4 = "Network Codes profiles, release 2.4";
    /** Name of the entry that takes RDFS files and a base namespace from the user. */
    public static final String OTHER_CIM_VERSION = "Other CIM version";

    // Mapping between the supported RDFS profiles and their files. Insertion ordered, because it is
    // also the order of the profile drop-down: a HashMap reshuffled the list whenever an entry was added.
    private LinkedHashMap<String, RDFSProfile> supportedRDFSProfiles;

    public SupportedRDFSProfiles() throws IOException {

        try {
            supportedRDFSProfiles = new LinkedHashMap<>() {{
                put("IEC 61970-600-1&2 (CGMES 3.0.0)", new RDFSProfile("IEC 61970-600-1&2 (CGMES 3.0.0)", new String[]{
                        "FileHeader_RDFS2019.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_DL.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_DY.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_GL.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf",
                }, new InputStream[]{
                        getClass().getResourceAsStream("/RDFS/CGMES300/FileHeader_RDFS2019.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_DL.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_DY.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_GL.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES300/IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf"),
                }, "http://iec.ch/TC57/CIM100"));
                put("IEC 61970-45x (CIM17)", new RDFSProfile("IEC 61970-45x (CIM17)", new String[]{
                        "FileHeader_RDFS2019.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_DL.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_DY.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_GL.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf",
                        "IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf",
                }, new InputStream[]{
                        getClass().getResourceAsStream("/RDFS/cim17/FileHeader_RDFS2019.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_DL.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_DY.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQ.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_EQBD.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_GL.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_OP.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SC.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SSH.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_SV.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim17/IEC61970-600-2_CGMES_3_0_0_RDFS2020_TP.rdf"),
                }, "http://iec.ch/TC57/CIM100"));
                put("IEC TS 61970-600-1&2 (CGMES 2.4.15)", new RDFSProfile("IEC TS 61970-600-1&2 (CGMES 2.4.15)", new String[]{
                        "DiagramLayoutProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "DynamicsProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "EquipmentBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "EquipmentProfileCoreOperationRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "EquipmentProfileCoreRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "EquipmentProfileCoreShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "FileHeader.rdf",
                        "GeographicalLocationProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "TopologyBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"
                }, new InputStream[]{
                        getClass().getResourceAsStream("/RDFS/CGMES2415/DiagramLayoutProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/DynamicsProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/EquipmentBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/EquipmentProfileCoreOperationRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/EquipmentProfileCoreRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/EquipmentProfileCoreShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/FileHeader.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/GeographicalLocationProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/TopologyBoundaryProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/CGMES2415/TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                }, "http://iec.ch/TC57/2013/CIM-schema-cim16"));
                put("IEC 61970-45x (CIM16)", new RDFSProfile("IEC 61970-45x (CIM16)", new String[]{
                        "EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "FileHeader.rdf",
                        "StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                        "TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf",
                }, new InputStream[]{
                        getClass().getResourceAsStream("/RDFS/cim16/EquipmentProfileCoreOperationShortCircuitRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim16/FileHeader.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim16/StateVariableProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim16/SteadyStateHypothesisProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/cim16/TopologyProfileRDFSAugmented-v2_4_15-4Sep2020.rdf"),
                }, "http://iec.ch/TC57/2013/CIM-schema-cim16"));
                // ENTSO-E Network Code Profiles 2.4 - the vocabulary (RDFS) of each application profile, taken
                // unchanged from the Apache-2.0 ENTSO-E application profiles library (LICENSE.txt and NOTICE.txt
                // sit next to them). The PROF descriptors and SHACL constraints of that library are not schemas
                // and are not loaded here; SHACL is picked per task, as for CGMES.
                put(NCP_2_4, new RDFSProfile(NCP_2_4, new String[]{
                        "AssessedElement-AP-Voc-RDFS2020.rdf",
                        "AvailabilitySchedule-AP-Voc-RDFS2020.rdf",
                        "Contingency-AP-Voc-RDFS2020.rdf",
                        "DatasetMetadata-AP-Voc-RDFS2020.rdf",
                        "EquipmentReliability-AP-Voc-RDFS2020.rdf",
                        "GridDisturbance-AP-Voc-RDFS2020.rdf",
                        "ImpactAssessmentMatrix-AP-Voc-RDFS2020.rdf",
                        "MonitoringArea-AP-Voc-RDFS2020.rdf",
                        "ObjectRegistry-AP-Voc-RDFS2020.rdf",
                        "PowerSchedule-AP-Voc-RDFS2020.rdf",
                        "PowerSystemProject-AP-Voc-RDFS2020.rdf",
                        "RemedialAction-AP-Voc-RDFS2020.rdf",
                        "RemedialActionSchedule-AP-Voc-RDFS2020.rdf",
                        "SecurityAnalysisResult-AP-Voc-RDFS2020.rdf",
                        "SensitivityMatrix-AP-Voc-RDFS2020.rdf",
                        "StateInstructionSchedule-AP-Voc-RDFS2020.rdf",
                        "SteadyStateHypothesisSchedule-AP-Voc-RDFS2020.rdf",
                        "SteadyStateInstruction-AP-Voc-RDFS2020.rdf",
                }, new InputStream[]{
                        getClass().getResourceAsStream("/RDFS/NCP24/AssessedElement-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/AvailabilitySchedule-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/Contingency-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/DatasetMetadata-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/EquipmentReliability-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/GridDisturbance-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/ImpactAssessmentMatrix-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/MonitoringArea-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/ObjectRegistry-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/PowerSchedule-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/PowerSystemProject-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/RemedialAction-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/RemedialActionSchedule-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/SecurityAnalysisResult-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/SensitivityMatrix-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/StateInstructionSchedule-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/SteadyStateHypothesisSchedule-AP-Voc-RDFS2020.rdf"),
                        getClass().getResourceAsStream("/RDFS/NCP24/SteadyStateInstruction-AP-Voc-RDFS2020.rdf"),
                }, "https://cim.ucaiug.io/ns", ProfileFamily.NCP));
                put(OTHER_CIM_VERSION, new RDFSProfile(OTHER_CIM_VERSION, new String[]{}, new InputStream[]{}, ""));
            }};
        }
        catch (NullPointerException e){
            LOG.error("Unhandled exception", e);
        }
    }

    public String[] getSupportedRDFSProfileNames() {
        return supportedRDFSProfiles.keySet().toArray(new String[0]);
    }

    public String[] getSupportedRDFSProfileFilesFromName(String rdfsProfileName) {
        return supportedRDFSProfiles.get(rdfsProfileName).getPathToRDFSFiles();
    }

    public RDFSProfile getRDFSProfileFromName(String rdfsProfileName) {
        return supportedRDFSProfiles.get(rdfsProfileName);
    }


    public String getSupportedRDFSProfileBaseNamespaceFromName(String rdfsProfileName) {
        return supportedRDFSProfiles.get(rdfsProfileName).getBaseNamespace();
    }
}
