package eu.griddigit.cimpal.main.application.datagenerator;

import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.ProfileFamily;
import eu.griddigit.cimpal.main.application.datagenerator.resources.RDFSProfile;
import eu.griddigit.cimpal.main.application.datagenerator.resources.SupportedRDFSProfiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NcpProfileLoadingTest {

    @TempDir
    static Path dir;

    // Loading the 18 NCP vocabularies takes a few seconds, so one model serves every test; each
    // test points it at its own files and reloads just those.
    private static DataGeneratorModel ncp;

    @BeforeAll
    static void loadNcpVocabularies() throws IOException {
        RDFSProfile profile = new SupportedRDFSProfiles().getRDFSProfileFromName(SupportedRDFSProfiles.NCP_2_4);
        assertNotNull(profile, "the bundled NCP 2.4 profile set must be registered");
        assertEquals(ProfileFamily.NCP, profile.getFamily());
        ncp = new DataGeneratorModel();
        ncp.setRdfsProfileVersion(profile);
        ncp.loadRDFSProfileModel();
    }

    private static String dataset(String conformsTo, String... keywords) {
        StringBuilder keywordXml = new StringBuilder();
        for (String keyword : keywords) {
            keywordXml.append("    <dcat:keyword>").append(keyword).append("</dcat:keyword>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dcterms="http://purl.org/dc/terms/"
                    xmlns:cim="https://cim.ucaiug.io/ns#" xmlns:dcat="http://www.w3.org/ns/dcat#">
                  <dcat:Dataset rdf:about="urn:uuid:b2d1215a-a124-4b6e-9df5-85ecdce9793b">
                    <dcterms:conformsTo rdf:resource="%s"/>
                    <dcterms:conformsTo rdf:resource="https://cim4.eu/ns/nc/2.4#"/>
                %s  </dcat:Dataset>
                  <cim:GeneratingUnit rdf:about="#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0"/>
                  <cim:ContingencyEquipment rdf:ID="_19bfe9d5-5d04-4c3c-9919-ca1b2d1215ae"/>
                </rdf:RDF>
                """.formatted(conformsTo, keywordXml);
    }

    private static BaseInstanceModel load(DataGeneratorModel model, String fileName, String content) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content);
        model.setBaseInstanceModelPath(new String[]{file.toString()});
        model.loadInstanceModel();
        Map<String, BaseInstanceModel> loaded = model.getBaseInstanceModel();
        assertEquals(1, loaded.size());
        return loaded.get(fileName);
    }

    @Test
    void profileComesFromConformsToEvenWhenTheKeywordSaysOtherwise() throws IOException {
        // Named freely, and keyworded FAP, like a ReliCapGrid RemedialActionSchedule dataset.
        BaseInstanceModel loaded = load(ncp, "Svedala_FAP_RAS_simple.xml",
                dataset("https://ap.cim4.eu/RemedialActionSchedule/2.4", "FAP"));
        assertEquals("RAS", loaded.getProfile());
        assertFalse(loaded.hasCgmesFileName());
    }

    @Test
    void profileOutsideTheBundleFallsBackToItsKeyword() throws IOException {
        // Provenance is not an NCP 2.4 profile; this header carries two keywords, as the real one does.
        BaseInstanceModel loaded = load(ncp, "Svedala_FAP_PV.xml",
                dataset("https://ap.cim4.eu/Provenance/1.0", "Provenance", "PV"));
        assertEquals("PV", loaded.getProfile(), "with no loaded profile named, the shorter keyword is taken");
    }

    @Test
    void fileWithoutADatasetHeaderIsRefusedByName() {
        String cgmes = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#">
                  <md:FullModel rdf:about="urn:uuid:fe534bf5-0160-43eb-9b1a-da77b7818665"/>
                </rdf:RDF>
                """;
        IOException refused = assertThrows(IOException.class, () -> load(ncp, "Belgovia_SSH.xml", cgmes));
        assertTrue(refused.getMessage().contains("Belgovia_SSH.xml"), refused.getMessage());
        assertTrue(refused.getMessage().contains("dcat:Dataset"), refused.getMessage());
    }

    @Test
    void formTheFileWasWrittenInIsRecorded() throws IOException {
        BaseInstanceModel loaded = load(ncp, "Belgovia_CO.xml", dataset("https://ap.cim4.eu/Contingency/2.3", "CO"));
        assertEquals("CO", loaded.getProfile());
        assertEquals(2, loaded.getAboutSubjects().size(), "the header and the generating unit were written with rdf:about");
        assertEquals(1, loaded.getIdSubjects().size(), "the contingency equipment was written with rdf:ID");
    }

    @Test
    void ncpProfileWritesTheDatasetHeader() {
        assertEquals("http://www.w3.org/ns/dcat#Dataset", ncp.getSaveProperties().get("headerClassResource"),
                "putting the header on top and writing it with rdf:about both key on the header class");
    }

    @Test
    void cgmesFileNameWithoutItsFivePartsIsRefusedByName() throws IOException {
        DataGeneratorModel cgmes = new DataGeneratorModel();
        cgmes.setRdfsProfileVersion(new SupportedRDFSProfiles().getRDFSProfileFromName("IEC 61970-600-1&2 (CGMES 3.0.0)"));
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#">
                  <md:FullModel rdf:about="urn:uuid:fe534bf5-0160-43eb-9b1a-da77b7818665"/>
                </rdf:RDF>
                """;
        IOException refused = assertThrows(IOException.class, () -> load(cgmes, "Belgovia_SSH.xml", content));
        assertTrue(refused.getMessage().contains("<datetime>_<process>_<TSO>_<profile>_<version>"), refused.getMessage());
        assertEquals("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel", cgmes.getSaveProperties().get("headerClassResource"));
    }
}
