/* Licensed under the EUPL-1.2-or-later. Copyright (c) 2026, gridDigIt Kft. */
package eu.griddigit.cimpal.main.workspace;

import eu.griddigit.cimpal.main.application.MainController;
import org.apache.jena.rdf.model.Model;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of visible workspace artifacts. It records ownership and provenance but deliberately
 * does not expose mutable Jena models as a global shared store.
 */
public final class WorkspaceArtifactRegistry {
    public enum Type { INSTANCE_DATA, SCHEMA_PROFILE, SHACL_SHAPES, VALIDATION_REPORT, DERIVED_MODEL, VISUALISATION_GRAPH }
    public record Artifact(String key, Type type, String name, String owner, String state, long triples,
                           List<String> sources, String fingerprint, boolean inMemory, Instant updated) { }

    private static final Map<String, Artifact> ARTIFACTS = new LinkedHashMap<>();
    private WorkspaceArtifactRegistry() { }

    public static synchronized void registerFiles(String key, Type type, String name, String owner, String state, List<File> files) {
        List<String> sources = files == null ? List.of() : files.stream().map(File::getAbsolutePath).sorted().toList();
        ARTIFACTS.put(key, new Artifact(key, type, name, owner, state, -1, sources, "", false, Instant.now()));
    }

    public static synchronized void registerModel(String key, Type type, String name, String owner, String state,
                                                  Model model, List<String> sources) {
        removePersistentAlias(key);
        ARTIFACTS.put(key, new Artifact(key, type, name, owner, state, model == null ? -1 : model.size(),
                sources == null ? List.of() : List.copyOf(sources), "", true, Instant.now()));
    }

    public static synchronized void registerModel(String key, Type type, String name, String owner, String state,
                                                  Model model, List<File> files, String fingerprint, boolean inMemory) {
        removePersistentAlias(key);
        List<String> sources = files == null ? List.of() : files.stream().map(File::getAbsolutePath).sorted().toList();
        ARTIFACTS.put(key, new Artifact(key, type, name, owner, state, model == null ? -1 : model.size(),
                sources, fingerprint, inMemory, Instant.now()));
    }

    public static synchronized Artifact find(String key) { return ARTIFACTS.get(key); }

    public static synchronized void markReleased(String key) {
        Artifact artifact = ARTIFACTS.get(key);
        if (artifact != null) ARTIFACTS.put(key, new Artifact(artifact.key(), artifact.type(), artifact.name(), artifact.owner(),
                "released from shared memory", artifact.triples(), artifact.sources(), artifact.fingerprint(), false, Instant.now()));
    }
    public static synchronized void remove(String key) { ARTIFACTS.remove(key); }
    public static synchronized void clear() { ARTIFACTS.clear(); }

    /** Records existing legacy shared fields while modules are progressively migrated to the registry. */
    public static synchronized void synchronizeLegacyInventory() {
        List<Artifact> knownArtifacts = List.copyOf(ARTIFACTS.values());
        ARTIFACTS.entrySet().removeIf(entry -> entry.getValue().owner().equals("Persisted workspace store")
                && knownArtifacts.stream().anyMatch(other -> !other.owner().equals("Persisted workspace store")
                && WorkspaceRdfStore.sameGraph(other.key(), entry.getKey())));
        for (String key : WorkspaceRdfStore.storedKeys()) {
            boolean alreadyRepresented = ARTIFACTS.values().stream()
                    .anyMatch(artifact -> WorkspaceRdfStore.sameGraph(artifact.key(), key));
            if (!alreadyRepresented) {
                ARTIFACTS.put(key, new Artifact(key, Type.DERIVED_MODEL, key, "Persisted workspace store",
                        "stored in local TDB2 store (not loaded)", -1, List.of(), "", false, Instant.now()));
            }
        }
        if (MainController.IDModel1 != null && !MainController.IDModel1.isEmpty() && find("sparql-selected-input") == null) {
            registerFiles("sparql-selected-input", Type.INSTANCE_DATA, "SPARQL / AI selected input", "SPARQL Query", "source files selected", MainController.IDModel1);
        }
        if (MainController.RDFSmodels != null) {
            for (int index = 0; index < MainController.RDFSmodels.size(); index++) {
                Model model = MainController.RDFSmodels.get(index);
                registerModel("legacy-rdfs-" + index, Type.SCHEMA_PROFILE, "Loaded RDFS profile " + (index + 1), "Legacy RDFS workflow", "in memory", model, List.of());
            }
        }
        if (MainController.shapeModels != null) {
            for (int index = 0; index < MainController.shapeModels.size(); index++) {
                Object candidate = MainController.shapeModels.get(index);
                if (candidate instanceof Model model) {
                    registerModel("legacy-shapes-" + index, Type.SHACL_SHAPES, "Loaded SHACL shapes " + (index + 1), "Legacy SHACL workflow", "in memory", model, List.of());
                }
            }
        }
    }

    /** Replaces the generic stored-only alias once a workflow supplies richer model metadata. */
    private static void removePersistentAlias(String key) {
        ARTIFACTS.entrySet().removeIf(entry -> entry.getValue().owner().equals("Persisted workspace store")
                && WorkspaceRdfStore.sameGraph(entry.getKey(), key));
    }

    public static synchronized List<Artifact> snapshot() {
        return ARTIFACTS.values().stream().sorted(Comparator.comparing(Artifact::updated).reversed()).toList();
    }

    public static String describeWorkspace() {
        synchronizeLegacyInventory();
        List<Artifact> artifacts = snapshot();
        if (artifacts.isEmpty()) return "No workspace artifacts are currently registered.\n\nLoad source files in a workflow to begin.";
        StringBuilder text = new StringBuilder("Workspace artifacts\n\n");
        for (Artifact artifact : artifacts) {
            text.append(artifact.type()).append(" — ").append(artifact.name()).append('\n')
                    .append("  Owner: ").append(artifact.owner()).append(" | State: ").append(artifact.state()).append('\n')
                    .append("  In memory: ").append(artifact.inMemory() ? artifact.triples() + " triples" : "no (source reference only)").append('\n')
                    .append("  Sources: ").append(artifact.sources().isEmpty() ? "not recorded" : String.join(", ", artifact.sources())).append('\n')
                    .append("  Updated: ").append(artifact.updated()).append("\n\n");
        }
        return text.toString();
    }
}
