package eu.griddigit.cimpal.main.workspace;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRdfStoreTest {
    private static Model model(String subject) {
        Model model = ModelFactory.createDefaultModel();
        model.createResource("urn:test:" + subject).addProperty(model.createProperty("urn:test:property"), subject);
        return model;
    }

    @Test
    void publishedGraphCanBeCopiedWithoutMutatingTheSource() {
        String key = "workspace-test-isolation";
        WorkspaceRdfStore.publishModel(key, WorkspaceArtifactRegistry.Type.VISUALISATION_GRAPH,
                "Visual graph", "test", "loaded", model("one"), List.of("source-one.ttl"));

        Model copy = WorkspaceRdfStore.copy(key);
        copy.removeAll();

        assertTrue(WorkspaceRdfStore.isLoaded(key));
        assertEquals(1, WorkspaceRdfStore.copy(key).size(), "The named workspace graph must remain unchanged by a working copy.");
    }

    @Test
    void multipleGraphsAreCombinedIntoAnIsolatedWorkingModel() {
        String first = "workspace-test-first";
        String second = "workspace-test-second";
        WorkspaceRdfStore.publishModel(first, WorkspaceArtifactRegistry.Type.VISUALISATION_GRAPH,
                "First", "test", "loaded", model("one"), List.of());
        WorkspaceRdfStore.publishModel(second, WorkspaceArtifactRegistry.Type.VISUALISATION_GRAPH,
                "Second", "test", "loaded", model("two"), List.of());

        Model combined = WorkspaceRdfStore.copyAll(List.of(first, second));

        assertEquals(2, combined.size());
        combined.removeAll();
        assertEquals(1, WorkspaceRdfStore.copy(first).size());
        assertEquals(1, WorkspaceRdfStore.copy(second).size());
    }

    @Test
    void releasedGraphIsNoLongerAvailableAndRegistryReflectsIt() {
        String key = "workspace-test-release";
        WorkspaceRdfStore.publishModel(key, WorkspaceArtifactRegistry.Type.INSTANCE_DATA,
                "Release test", "test", "loaded", model("one"), List.of());

        WorkspaceRdfStore.release(key);

        assertFalse(WorkspaceRdfStore.isLoaded(key));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceRdfStore.copy(key));
        assertFalse(WorkspaceArtifactRegistry.find(key).inMemory());
    }
}
