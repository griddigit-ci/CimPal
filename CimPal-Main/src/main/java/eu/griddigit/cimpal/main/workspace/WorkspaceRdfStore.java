/* Licensed under the EUPL-1.2-or-later. Copyright (c) 2026, gridDigIt Kft. */
package eu.griddigit.cimpal.main.workspace;

import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;

import java.io.File;
import java.util.List;
import java.util.Collection;

/**
 * Shared in-memory named-graph store. Source graphs are cached by file fingerprint; callers get
 * a copy for any workflow that may mutate data, keeping source artifacts stable and reusable.
 */
public final class WorkspaceRdfStore {
    private static final Dataset DATASET = DatasetFactory.createTxnMem();
    private WorkspaceRdfStore() { }

    public static synchronized Model loadOrGetCombinedCopy(String key, WorkspaceArtifactRegistry.Type type,
                                                            String name, String owner, List<File> files,
                                                            String xmlBase) throws Exception {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Select model files first.");
        String fingerprint = fingerprint(files, xmlBase);
        String graphName = graphName(key);
        WorkspaceArtifactRegistry.Artifact existing = WorkspaceArtifactRegistry.find(key);
        if (existing == null || !fingerprint.equals(existing.fingerprint()) || !DATASET.containsNamedModel(graphName)) {
            Model loaded = ModelFactory.loadCombinedModelForSparql(files, xmlBase);
            if (loaded == null || loaded.isEmpty()) throw new IllegalArgumentException("The selected files did not produce any RDF statements.");
            Model stored;
            if (DATASET.containsNamedModel(graphName)) {
                stored = DATASET.getNamedModel(graphName);
            } else {
                DATASET.addNamedModel(graphName, org.apache.jena.rdf.model.ModelFactory.createDefaultModel());
                stored = DATASET.getNamedModel(graphName);
            }
            stored.removeAll();
            stored.add(loaded);
            WorkspaceArtifactRegistry.registerModel(key, type, name, owner, "loaded in shared memory", stored,
                    files, fingerprint, true);
        }
        // Do not give a source graph to an arbitrary tab: an update/conversion must be isolated.
        return org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(DATASET.getNamedModel(graphName));
    }

    public static synchronized void release(String key) {
        String graphName = graphName(key);
        if (DATASET.containsNamedModel(graphName)) DATASET.removeNamedModel(graphName);
        WorkspaceArtifactRegistry.markReleased(key);
    }

    /** Permanently removes one CimPal-managed named graph; original source files are untouched. */
    public static synchronized void removeStoredArtifact(String key) {
        release(key);
        WorkspaceArtifactRegistry.remove(key);
    }

    /** Releases only CimPal's managed in-memory named graphs; source files and catalog entries remain. */
    public static synchronized void clearMemoryArtifacts() {
        List<String> releasedKeys = WorkspaceArtifactRegistry.snapshot().stream()
                .map(WorkspaceArtifactRegistry.Artifact::key)
                .filter(WorkspaceRdfStore::isLoaded)
                .toList();
        DATASET.begin(org.apache.jena.query.ReadWrite.WRITE);
        try {
            releasedKeys.forEach(graphKey -> DATASET.removeNamedModel(graphName(graphKey)));
            DATASET.commit();
        } finally {
            DATASET.end();
        }
        releasedKeys.forEach(WorkspaceArtifactRegistry::markReleased);
    }

    /** @deprecated Use {@link #clearMemoryArtifacts()}; this store is intentionally in-memory. */
    @Deprecated
    public static synchronized void clearStoredArtifacts() {
        clearMemoryArtifacts();
    }

    /** Publishes an already parsed graph (for example from Visualisation) to a named workspace graph. */
    public static synchronized void publishModel(String key, WorkspaceArtifactRegistry.Type type, String name, String owner,
                                                 String state, Model model, List<String> sources) {
        if (model == null) throw new IllegalArgumentException("A model is required.");
        String graphName = graphName(key);
        Model stored;
        if (DATASET.containsNamedModel(graphName)) {
            stored = DATASET.getNamedModel(graphName);
            stored.removeAll();
        } else {
            DATASET.addNamedModel(graphName, org.apache.jena.rdf.model.ModelFactory.createDefaultModel());
            stored = DATASET.getNamedModel(graphName);
        }
        stored.add(model);
        WorkspaceArtifactRegistry.registerModel(key, type, name, owner, state, stored, sources);
    }

    /** Returns an isolated copy of a published named graph. */
    public static synchronized Model copy(String key) {
        String graphName = graphName(key);
        if (!DATASET.containsNamedModel(graphName)) throw new IllegalArgumentException("Workspace artifact '" + key + "' is no longer loaded in memory.");
        return org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(DATASET.getNamedModel(graphName));
    }

    /** The Dataset, rather than catalog metadata, is authoritative for availability. */
    public static synchronized boolean isLoaded(String key) {
        return key != null && DATASET.containsNamedModel(graphName(key));
    }

    /** True when two artifact keys address the same normalized workspace named graph. */
    public static boolean sameGraph(String firstKey, String secondKey) {
        return firstKey != null && secondKey != null && graphName(firstKey).equals(graphName(secondKey));
    }

    /** Lists persisted workspace keys without loading them into the active in-memory Dataset. */
    public static synchronized List<String> storedKeys() {
        return WorkspaceTdbStore.storedKeys();
    }

    /** Combines selected named graphs into one isolated working model for a multi-profile operation. */
    public static synchronized Model copyAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("Choose one or more workspace artifacts.");
        Model combined = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        for (String key : keys) combined.add(copy(key));
        return combined;
    }

    private static String graphName(String key) { return "urn:cimpal:workspace:" + key.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static String fingerprint(List<File> files, String xmlBase) {
        return (xmlBase == null ? "" : xmlBase) + "|" + files.stream()
                .map(file -> file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified())
                .sorted().reduce((left, right) -> left + "|" + right).orElse("");
    }
}
