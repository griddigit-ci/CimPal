package eu.griddigit.cimpal.main.application.datagenerator.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseInstanceModelTest {

    @Test
    void cgmesFileNameIsSplitIntoItsParts() {
        // The ReliCapGrid EQ leaves the process part empty, which is still five parts.
        BaseInstanceModel model = new BaseInstanceModel("20220615T2230Z__Belgovia_EQ_1.xml");
        assertTrue(model.hasCgmesFileName());
        assertEquals("20220615T2230Z", model.getDatetime());
        assertEquals("", model.getProcess());
        assertEquals("Belgovia", model.getTso());
        assertEquals("EQ", model.getProfile());
        assertEquals("1.xml", model.getVersion());
    }

    @Test
    void freelyNamedFileIsKeptWithoutParts() {
        // Network Code Profile datasets are named freely; splitting such a name used to throw.
        BaseInstanceModel model = new BaseInstanceModel("Belgovia_CO.xml");
        assertFalse(model.hasCgmesFileName());
        assertEquals("Belgovia_CO.xml", model.getFileName());
        assertNull(model.getProfile(), "the profile of such a file comes from its content");
    }

    @Test
    void copyKeepsTheMetadataUnderTheNewName() {
        BaseInstanceModel original = new BaseInstanceModel("Belgovia_CO.xml");
        original.setProfile("CO");
        BaseInstanceModel copy = new BaseInstanceModel(original, "Belgovia_CO_20260928T120000Z.xml", null);
        assertEquals("CO", copy.getProfile());
        assertEquals("Belgovia_CO_20260928T120000Z.xml", copy.getFileName());
    }
}
