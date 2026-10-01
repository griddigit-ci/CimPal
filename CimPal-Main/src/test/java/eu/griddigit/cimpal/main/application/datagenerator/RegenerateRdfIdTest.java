package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegenerateRdfIdTest {
    private static final String CIM = "https://cim.ucaiug.io/ns#";
    private static final String NC = "https://cim4.eu/ns/nc#";
    private static final Property MRID = ResourceFactory.createProperty(CIM, "IdentifiedObject.mRID");
    private static final Property ELEMENT_OF = ResourceFactory.createProperty(NC, "ContingencyElement.Contingency");

    // The UUID of the dataset header starts with a digit on purpose: Jena's local name for such a
    // URN is not the UUID, which is what the header used to be looked up by.
    private static final String HEADER = "urn:uuid:3a3b27be-1111-2222-3333-444455556666";
    private static final String CONTINGENCY = CIM + "_c3b27bea-1111-2222-3333-444455556666";
    private static final String ELEMENT = CIM + "_5d5d5d5d-1111-2222-3333-444455556666";

    /** An NCP-shaped dataset: a URN header whose identifier repeats its UUID, and two objects. */
    private static Model dataset() {
        Model model = ModelFactory.createDefaultModel();
        model.createResource(HEADER)
                .addProperty(RDF.type, DCAT.Dataset)
                .addProperty(DCTerms.identifier, "3a3b27be-1111-2222-3333-444455556666");
        model.createResource(CONTINGENCY)
                .addProperty(RDF.type, model.createResource(NC + "OrdinaryContingency"))
                .addProperty(MRID, "c3b27bea-1111-2222-3333-444455556666");
        model.createResource(ELEMENT)
                .addProperty(RDF.type, model.createResource(CIM + "ContingencyEquipment"))
                .addProperty(MRID, "5d5d5d5d-1111-2222-3333-444455556666")
                .addProperty(ELEMENT_OF, model.createResource(CONTINGENCY));
        return model;
    }

    private static Resource only(Model model, Resource type) {
        List<Resource> subjects = model.listSubjectsWithProperty(RDF.type, type).toList();
        assertEquals(1, subjects.size(), "expected exactly one " + type);
        return subjects.getFirst();
    }

    private static String bare(Resource resource) {
        String uri = resource.getURI();
        return uri.startsWith("urn:uuid:") ? uri.substring("urn:uuid:".length()) : uri.substring(uri.lastIndexOf("#_") + 2);
    }

    @Test
    void urnHeaderGetsANewUrnAndItsIdentifierFollows() {
        Model model = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", dataset()), List.of(), new HashMap<>()).get("CO");

        Resource header = only(model, DCAT.Dataset);
        assertTrue(header.getURI().startsWith("urn:uuid:"), "a URN id stays a URN");
        assertNotEquals(HEADER, header.getURI(), "the header must get a new id");
        assertEquals(bare(header), model.getProperty(header, DCTerms.identifier).getString(),
                "dcterms:identifier repeats the dataset's UUID, so it has to follow the new id");
    }

    @Test
    void mridFollowsTheNewRdfIdAndStaysALiteral() {
        Model model = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", dataset()), List.of(), new HashMap<>()).get("CO");

        for (Resource subject : model.listSubjectsWithProperty(MRID).toList()) {
            assertFalse(Set.of(CONTINGENCY, ELEMENT).contains(subject.getURI()), "every object must get a new id");
            assertTrue(model.getProperty(subject, MRID).getObject().isLiteral(), "the mRID must stay a literal");
            assertEquals(bare(subject), model.getProperty(subject, MRID).getString(), "the mRID must match the new rdf:ID");
        }
        Resource element = only(model, model.createResource(CIM + "ContingencyEquipment"));
        Resource contingency = only(model, model.createResource(NC + "OrdinaryContingency"));
        assertEquals(contingency, model.getProperty(element, ELEMENT_OF).getResource(), "references follow the new ids");
    }

    @Test
    void identifierFollowsItsOwnSubjectWhenAnObjectSharesItsUuid() {
        // The ReliCapGrid Espheim contingency dataset and its contingency share one UUID.
        Model model = dataset();
        model.createResource(CIM + "_3a3b27be-1111-2222-3333-444455556666")
                .addProperty(RDF.type, model.createResource(NC + "OrdinaryContingency"))
                .addProperty(MRID, "3a3b27be-1111-2222-3333-444455556666");

        Model regenerated = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", model), List.of(), new HashMap<>()).get("CO");

        Resource header = only(regenerated, DCAT.Dataset);
        assertEquals(bare(header), regenerated.getProperty(header, DCTerms.identifier).getString(),
                "the dataset's identifier has to follow the dataset, not the object sharing its UUID");
        for (Resource subject : regenerated.listSubjectsWithProperty(MRID).toList()) {
            assertEquals(bare(subject), regenerated.getProperty(subject, MRID).getString());
        }
    }

    @Test
    void mridWrittenLikeTheRdfIdBecomesTheBareNewUuid() {
        Model model = dataset();
        Resource contingency = model.createResource(CONTINGENCY);
        model.removeAll(contingency, MRID, null);
        contingency.addProperty(MRID, "_c3b27bea-1111-2222-3333-444455556666");

        Model regenerated = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", model), List.of(), new HashMap<>()).get("CO");

        Resource newContingency = only(regenerated, regenerated.createResource(NC + "OrdinaryContingency"));
        assertEquals(bare(newContingency), regenerated.getProperty(newContingency, MRID).getString());
    }

    @Test
    void mridThatIsNotItsSubjectsIdIsLeftAlone() {
        Model model = dataset();
        Resource contingency = model.createResource(CONTINGENCY);
        model.removeAll(contingency, MRID, null);
        contingency.addProperty(MRID, "an-independent-mrid");

        Model regenerated = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", model), List.of(), new HashMap<>()).get("CO");

        Resource newContingency = only(regenerated, regenerated.createResource(NC + "OrdinaryContingency"));
        assertEquals("an-independent-mrid", regenerated.getProperty(newContingency, MRID).getString());
    }

    @Test
    void referencesBetweenFilesFollowTheSameNewIds() {
        Model other = ModelFactory.createDefaultModel();
        Resource dependent = other.createResource("urn:uuid:9f9f9f9f-1111-2222-3333-444455556666")
                .addProperty(RDF.type, DCAT.Dataset)
                .addProperty(DCTerms.requires, other.createResource(HEADER));

        Map<String, Model> files = new HashMap<>(Map.of("CO", dataset(), "RA", other));
        Map<String, Model> regenerated = ModelManipulationFactory.regenerateRDFIDmodule(files, List.of(), new HashMap<>());

        Resource newHeader = only(regenerated.get("CO"), DCAT.Dataset);
        Resource newDependent = only(regenerated.get("RA"), DCAT.Dataset);
        assertNotEquals(dependent.getURI(), newDependent.getURI());
        assertEquals(newHeader, regenerated.get("RA").getProperty(newDependent, DCTerms.requires).getResource(),
                "a dataset that requires another one in the set has to point at its new id");
    }

    @Test
    void pinnedIdKeepsItsIdAndItsMrid() {
        Map<String, String> idMap = new HashMap<>();
        idMap.put("_c3b27bea-1111-2222-3333-444455556666", "_c3b27bea-1111-2222-3333-444455556666");

        Model model = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", dataset()), List.of(), idMap).get("CO");

        Resource contingency = model.createResource(CONTINGENCY);
        assertTrue(model.contains(contingency, RDF.type), "an id mapped to itself must be kept");
        assertEquals("c3b27bea-1111-2222-3333-444455556666", model.getProperty(contingency, MRID).getString());
        Resource element = only(model, model.createResource(CIM + "ContingencyEquipment"));
        assertEquals(contingency, model.getProperty(element, ELEMENT_OF).getResource(),
                "references to a kept id still point at it");
    }

    @Test
    void skipListKeepsTheClassAndKeysTheMapByLocalName() {
        Map<String, String> idMap = new HashMap<>();
        Model model = ModelManipulationFactory.regenerateRDFIDmodule(Map.of("CO", dataset()), List.of("OrdinaryContingency"), idMap).get("CO");

        // Multiply and connect finds the copy of a node by its old local name.
        assertEquals("_c3b27bea-1111-2222-3333-444455556666", idMap.get("_c3b27bea-1111-2222-3333-444455556666"));
        assertTrue(model.contains(model.createResource(CONTINGENCY), RDF.type), "a class in the skip list keeps its id");
        String newElement = idMap.get("_5d5d5d5d-1111-2222-3333-444455556666");
        assertNotNull(newElement);
        assertTrue(model.contains(model.createResource(CIM + newElement), RDF.type));
    }

    @Test
    void idKeyTakesAUrnWholeAndAnythingElseByLocalName() {
        assertEquals(HEADER, ModelManipulationFactory.idKey(ResourceFactory.createResource(HEADER)));
        assertEquals("_c3b27bea-1111-2222-3333-444455556666", ModelManipulationFactory.idKey(ResourceFactory.createResource(CONTINGENCY)));
    }
}
