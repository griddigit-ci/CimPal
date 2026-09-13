/* Licensed under the EUPL-1.2-or-later. Copyright (c) 2026, gridDigIt Kft. */
package eu.griddigit.cimpal.main.workspace;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.tdb2.TDB2Factory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * Opt-in on-disk cache for workspace named graphs. It is deliberately separate from the
 * application's normal file loaders: a persistence failure must never prevent a model from
 * being loaded by Visualisation, SPARQL, validation, or conversion.
 */
public final class WorkspaceTdbStore {
    private static final String GRAPH_PREFIX = "urn:cimpal:workspace:";
    private static final Path STORE_PATH = Path.of(System.getProperty("user.home"), ".cimpal", "workspace-tdb2");
    private static final Path METADATA_PATH = STORE_PATH.resolve("workspace-artifacts.properties");

    public record StoredArtifact(String key, WorkspaceArtifactRegistry.Type type, String name, String owner, List<String> sources) { }

    private WorkspaceTdbStore() { }

    public static Path storePath() { return STORE_PATH; }

    /** Lists persisted artifact keys without restoring their RDF into workspace memory. */
    public static synchronized List<String> storedKeys() {
        if (!Files.isDirectory(STORE_PATH)) return List.of();
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.READ);
        try {
            List<String> keys = new ArrayList<>(storedArtifacts().stream().map(StoredArtifact::key).toList());
            disk.listNames().forEachRemaining(name -> {
                if (name.startsWith(GRAPH_PREFIX) && keys.stream().noneMatch(key -> graphName(key).equals(name))) {
                    keys.add(name.substring(GRAPH_PREFIX.length()));
                }
            });
            return List.copyOf(keys);
        } finally {
            disk.end();
            disk.close();
        }
    }

    public static synchronized List<StoredArtifact> storedArtifacts() {
        Properties properties = readMetadata();
        List<StoredArtifact> artifacts = new ArrayList<>();
        for (String property : properties.stringPropertyNames()) {
            if (!property.endsWith(".key")) continue;
            String token = property.substring(0, property.length() - 4);
            String key = properties.getProperty(property);
            try {
                WorkspaceArtifactRegistry.Type type = WorkspaceArtifactRegistry.Type.valueOf(properties.getProperty(token + ".type", "DERIVED_MODEL"));
                artifacts.add(new StoredArtifact(key, type, properties.getProperty(token + ".name", key),
                        properties.getProperty(token + ".owner", "Persistent workspace store"),
                        decodeSources(properties.getProperty(token + ".sources", ""))));
            } catch (IllegalArgumentException ignored) { }
        }
        return List.copyOf(artifacts);
    }

    public static synchronized boolean isStored(String key) {
        if (key == null || !Files.isDirectory(STORE_PATH)) return false;
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.READ);
        try {
            return disk.containsNamedModel(graphName(key));
        } finally {
            disk.end();
            disk.close();
        }
    }

    /** Persists isolated copies of the chosen currently-loaded workspace artifacts. */
    public static synchronized int persist(Collection<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) return 0;
        List<String> available = keys.stream().filter(WorkspaceRdfStore::isLoaded).distinct().toList();
        if (available.isEmpty()) return 0;
        Files.createDirectories(STORE_PATH);
        Properties metadata = readMetadata();
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.WRITE);
        try {
            for (String key : available) {
                String graphName = graphName(key);
                Model target = disk.containsNamedModel(graphName) ? disk.getNamedModel(graphName) : null;
                if (target == null) {
                    disk.addNamedModel(graphName, org.apache.jena.rdf.model.ModelFactory.createDefaultModel());
                    target = disk.getNamedModel(graphName);
                }
                target.removeAll();
                target.add(WorkspaceRdfStore.copy(key));
                WorkspaceArtifactRegistry.Artifact artifact = WorkspaceArtifactRegistry.find(key);
                writeMetadata(metadata, key, artifact);
            }
            disk.commit();
            saveMetadata(metadata);
            return available.size();
        } finally {
            disk.end();
            disk.close();
        }
    }

    /** Restores all persisted graphs into the in-memory workspace and returns their artifact keys. */
    public static synchronized List<String> restoreAll() {
        if (!Files.isDirectory(STORE_PATH)) return List.of();
        List<String> keys = storedKeys();
        List<StoredArtifact> metadata = storedArtifacts();
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.READ);
        try {
            List<String> restored = new ArrayList<>();
            for (String key : keys) {
                String graphName = graphName(key);
                if (!disk.containsNamedModel(graphName)) continue;
                Model copy = org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(disk.getNamedModel(graphName));
                WorkspaceArtifactRegistry.Artifact existing = WorkspaceArtifactRegistry.find(key);
                StoredArtifact persisted = metadata.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
                WorkspaceRdfStore.publishModel(key,
                        existing != null ? existing.type() : persisted != null ? persisted.type() : WorkspaceArtifactRegistry.Type.DERIVED_MODEL,
                        existing != null ? existing.name() : persisted != null ? persisted.name() : key,
                        existing != null ? existing.owner() : persisted != null ? persisted.owner() : "Persistent workspace store",
                        "restored from local TDB2 store", copy,
                        existing != null ? existing.sources() : persisted != null ? persisted.sources() : List.of());
                restored.add(key);
            }
            return List.copyOf(restored);
        } finally {
            disk.end();
            disk.close();
        }
    }

    /** Removes selected persisted copies only; the in-memory graphs and source files are untouched. */
    public static synchronized int remove(Collection<String> keys) throws Exception {
        if (keys == null || keys.isEmpty() || !Files.isDirectory(STORE_PATH)) return 0;
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.WRITE);
        try {
            int removed = 0;
            for (String key : keys.stream().distinct().toList()) {
                String graphName = graphName(key);
                if (disk.containsNamedModel(graphName)) {
                    disk.removeNamedModel(graphName);
                    removed++;
                }
            }
            disk.commit();
            Properties metadata = readMetadata();
            keys.forEach(key -> removeMetadata(metadata, key));
            saveMetadata(metadata);
            return removed;
        } finally {
            disk.end();
            disk.close();
        }
    }

    /** Clears every CimPal-managed graph in the persistent TDB2 cache; source files are untouched. */
    public static synchronized int clear() throws Exception {
        if (!Files.isDirectory(STORE_PATH)) return 0;
        Dataset disk = TDB2Factory.connectDataset(STORE_PATH.toString());
        disk.begin(ReadWrite.WRITE);
        try {
            List<String> names = new ArrayList<>();
            disk.listNames().forEachRemaining(name -> { if (name.startsWith(GRAPH_PREFIX)) names.add(name); });
            names.forEach(disk::removeNamedModel);
            disk.commit();
            Files.deleteIfExists(METADATA_PATH);
            return names.size();
        } finally {
            disk.end();
            disk.close();
        }
    }

    private static String graphName(String key) {
        return GRAPH_PREFIX + key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Properties readMetadata() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(METADATA_PATH)) return properties;
        try (InputStream input = Files.newInputStream(METADATA_PATH)) { properties.load(input); }
        catch (Exception ignored) { }
        return properties;
    }

    private static void saveMetadata(Properties properties) throws Exception {
        try (OutputStream output = Files.newOutputStream(METADATA_PATH)) { properties.store(output, "CimPal workspace artifact metadata"); }
    }

    private static void writeMetadata(Properties properties, String key, WorkspaceArtifactRegistry.Artifact artifact) {
        String token = token(key);
        properties.setProperty(token + ".key", key);
        properties.setProperty(token + ".type", artifact == null ? WorkspaceArtifactRegistry.Type.DERIVED_MODEL.name() : artifact.type().name());
        properties.setProperty(token + ".name", artifact == null ? key : artifact.name());
        properties.setProperty(token + ".owner", artifact == null ? "Persistent workspace store" : artifact.owner());
        properties.setProperty(token + ".sources", encodeSources(artifact == null ? List.of() : artifact.sources()));
    }

    private static void removeMetadata(Properties properties, String key) {
        String token = token(key);
        properties.keySet().removeIf(property -> property.toString().startsWith(token + "."));
    }

    private static String token(String key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String encodeSources(List<String> sources) { return Base64.getUrlEncoder().encodeToString(String.join("\n", sources).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static List<String> decodeSources(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.of(new String(Base64.getUrlDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8).split("\\n")); }
        catch (IllegalArgumentException ignored) { return List.of(); }
    }
}
