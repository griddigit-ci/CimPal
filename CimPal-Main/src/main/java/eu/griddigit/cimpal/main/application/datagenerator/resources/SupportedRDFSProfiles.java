package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

public class SupportedRDFSProfiles {

    // Hashmap to hold the mapping between Supported RDFS Profiles and file location
    private HashMap<String, RDFSProfile> supportedRDFSProfiles;

    public SupportedRDFSProfiles() throws IOException {

        try {
            supportedRDFSProfiles = new HashMap<>() {{
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
                put("Other CIM version", new RDFSProfile("Other CIM version", new String[]{}, new InputStream[]{}, ""));
            }};
        }
        catch (NullPointerException e){
            e.printStackTrace();
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
