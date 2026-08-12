package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ShapeSource abstraction and loadShapesWithImports traversal.
 */
class ShapeSourceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRemoteCache() {
        ValidationTools.REMOTE_IMPORTS_CACHE.clear();
    }

    // ---- 1. Classification: resolveImport ----

    @Test
    void classify_https_ttl_returnsRemote() {
        var src = ValidationTools.resolveImport(
                "https://raw.githubusercontent.com/example/repo/main/shapes.ttl",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir);
        assertInstanceOf(ValidationTools.RemoteShapeSource.class, src);
    }

    @Test
    void classify_http_ttl_returnsRemote() {
        var src = ValidationTools.resolveImport(
                "http://example.org/shapes.ttl",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir);
        assertInstanceOf(ValidationTools.RemoteShapeSource.class, src);
    }

    @Test
    void classify_namespaceUri_returnsNull() {
        // http://www.w3.org/ns/shacl# is a vocabulary namespace, not a downloadable file.
        // The old code skipped all HTTP/HTTPS; the new code must also skip namespace URIs.
        assertNull(ValidationTools.resolveImport(
                "http://www.w3.org/ns/shacl#",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir));
    }

    @Test
    void classify_namespaceUriNoExtension_returnsNull() {
        // Generic namespace URI without RDF file extension must be skipped.
        assertNull(ValidationTools.resolveImport(
                "http://www.w3.org/2002/07/owl#",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir));
    }

    @Test
    void classify_fileUri_returnsLocal() throws Exception {
        Path file = tempDir.resolve("dep.ttl");
        Files.writeString(file, "# placeholder");
        var src = ValidationTools.resolveImport(
                file.toUri().toString(),
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir);
        assertInstanceOf(ValidationTools.LocalShapeSource.class, src);
        assertEquals(file.toAbsolutePath().normalize(),
                ((ValidationTools.LocalShapeSource) src).path().toAbsolutePath().normalize());
    }

    @Test
    void classify_urn_returnsNull() {
        assertNull(ValidationTools.resolveImport(
                "urn:example:shapes",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir));
    }

    @Test
    void classify_blank_returnsNull() {
        assertNull(ValidationTools.resolveImport(
                "   ",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir));
    }

    @Test
    void classify_relativeFromLocal_resolvesToSiblingFile() throws Exception {
        Path dep = tempDir.resolve("dep.ttl");
        Files.writeString(dep, "# placeholder");
        var src = ValidationTools.resolveImport(
                "dep.ttl",
                new ValidationTools.LocalShapeSource(tempDir.resolve("root.ttl")),
                tempDir);
        assertInstanceOf(ValidationTools.LocalShapeSource.class, src);
    }

    @Test
    void classify_relativeFromRemote_resolvesAgainstParentUri() {
        // A relative ".ttl" reference from a remote parent resolves to a remote .ttl URL.
        var parent = new ValidationTools.RemoteShapeSource(
                URI.create("https://raw.githubusercontent.com/example/repo/main/root.ttl"));
        var src = ValidationTools.resolveImport("dep.ttl", parent, tempDir);
        assertInstanceOf(ValidationTools.RemoteShapeSource.class, src);
        assertEquals("https://raw.githubusercontent.com/example/repo/main/dep.ttl",
                ((ValidationTools.RemoteShapeSource) src).uri().toString());
    }

    // ---- 2. Mixed import graph with pre-seeded remote cache ----

    @Test
    void mixedGraph_localImportingRemoteImportingRemote_loadsAll() throws Exception {
        // Use .ttl URLs so they pass the RDF-extension filter.
        // remoteB: leaf, no imports
        String remoteBUrl = "https://raw.githubusercontent.com/example/repo/main/b.ttl";
        Model remoteB = ModelFactory.createDefaultModel();
        remoteB.createResource("urn:b").addProperty(RDF.type, OWL.Ontology);

        // remoteA: imports remoteB
        String remoteAUrl = "https://raw.githubusercontent.com/example/repo/main/a.ttl";
        Model remoteA = ModelFactory.createDefaultModel();
        Resource aOnt = remoteA.createResource("urn:a");
        aOnt.addProperty(RDF.type, OWL.Ontology);
        aOnt.addProperty(OWL.imports, remoteA.createResource(remoteBUrl));

        // Pre-seed in-run cache using the normalized keys
        String keyA = new ValidationTools.RemoteShapeSource(URI.create(remoteAUrl)).key();
        String keyB = new ValidationTools.RemoteShapeSource(URI.create(remoteBUrl)).key();
        ValidationTools.REMOTE_IMPORTS_CACHE.put(keyA, remoteA);
        ValidationTools.REMOTE_IMPORTS_CACHE.put(keyB, remoteB);

        // root.ttl: imports remoteA
        Path root = tempDir.resolve("root.ttl");
        writeOntologyWithImport(root, "urn:root", remoteAUrl);

        Map<String, Model> cache = new HashMap<>();
        var result = ValidationTools.loadShapesWithImports(
                new ValidationTools.LocalShapeSource(root), tempDir, cache);

        // All three sources were traversed → root + a + b = 3 loaded
        assertEquals(3, result.loadedFiles());
        // Total triple count = root triples + a triples + b triples
        long expected = countTriplesInFile(root) + remoteA.size() + remoteB.size();
        assertEquals(expected, result.model().size());
    }

    // ---- 3. Diamond and cycle ----

    @Test
    void diamondImport_eachFileLoadedOnce() throws Exception {
        //   root → A, B
        //   A    → C
        //   B    → C   (C is the shared leaf)
        Path c = tempDir.resolve("c.ttl");
        writeMinimalOntology(c, "urn:c");

        Path a = tempDir.resolve("a.ttl");
        writeOntologyWithImport(a, "urn:a", c.toUri().toString());

        Path b = tempDir.resolve("b.ttl");
        writeOntologyWithImport(b, "urn:b", c.toUri().toString());

        Path root = tempDir.resolve("root.ttl");
        writeTwoImports(root, "urn:root", a.toUri().toString(), b.toUri().toString());

        Map<String, Model> cache = new HashMap<>();
        var result = ValidationTools.loadShapesWithImports(
                new ValidationTools.LocalShapeSource(root), tempDir, cache);

        // root + A + B + C — C must NOT be loaded twice
        assertEquals(4, result.loadedFiles(), "C loaded twice would give 5");
    }

    @Test
    void cycle_terminates_eachFileLoadedOnce() throws Exception {
        // A imports B, B imports A
        Path a = tempDir.resolve("a.ttl");
        Path b = tempDir.resolve("b.ttl");
        writeOntologyWithImport(a, "urn:a", b.toUri().toString());
        writeOntologyWithImport(b, "urn:b", a.toUri().toString());

        Map<String, Model> cache = new HashMap<>();
        var result = ValidationTools.loadShapesWithImports(
                new ValidationTools.LocalShapeSource(a), tempDir, cache);

        assertEquals(2, result.loadedFiles(), "Cycle must not cause infinite loop or double-load");
    }

    // ---- 4. Failing remote import must throw, not silently pass ----

    @Test
    void failingRemoteImport_throwsIOException() throws Exception {
        // Points at a port with nothing listening
        Path root = tempDir.resolve("root.ttl");
        writeOntologyWithImport(root, "urn:root", "http://localhost:19999/nonexistent.ttl");

        Map<String, Model> cache = new HashMap<>();
        assertThrows(IOException.class,
                () -> ValidationTools.loadShapesWithImports(
                        new ValidationTools.LocalShapeSource(root), tempDir, cache),
                "A failing remote import must throw IOException, never produce a silent pass");
    }

    // ---- 5. All-local regression: triple count unchanged ----

    @Test
    void allLocalImports_tripleCountUnchanged() throws Exception {
        Path dep = tempDir.resolve("dep.ttl");
        writeMinimalOntology(dep, "urn:dep");

        Path root = tempDir.resolve("root.ttl");
        writeOntologyWithImport(root, "urn:root", dep.toUri().toString());

        // Manual merge (simulates old behaviour)
        Model manual = ModelFactory.createDefaultModel();
        manual.read(root.toUri().toString(), "TURTLE");
        manual.read(dep.toUri().toString(), "TURTLE");

        Map<String, Model> cache = new HashMap<>();
        var result = ValidationTools.loadShapesWithImports(
                new ValidationTools.LocalShapeSource(root), tempDir, cache);

        assertEquals(manual.size(), result.model().size(),
                "loadShapesWithImports must produce the same triple count as a manual merge");
    }

    // ---- Helpers ----

    private void writeMinimalOntology(Path file, String ontUri) throws IOException {
        Files.writeString(file,
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ontUri + "> a owl:Ontology .\n",
                StandardCharsets.UTF_8);
    }

    private void writeOntologyWithImport(Path file, String ontUri, String importUri) throws IOException {
        Files.writeString(file,
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ontUri + "> a owl:Ontology ;\n"
                + "    owl:imports <" + importUri + "> .\n",
                StandardCharsets.UTF_8);
    }

    private void writeTwoImports(Path file, String ontUri, String imp1, String imp2) throws IOException {
        Files.writeString(file,
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ontUri + "> a owl:Ontology ;\n"
                + "    owl:imports <" + imp1 + "> ;\n"
                + "    owl:imports <" + imp2 + "> .\n",
                StandardCharsets.UTF_8);
    }

    private long countTriplesInFile(Path file) {
        Model m = ModelFactory.createDefaultModel();
        m.read(file.toUri().toString(), "TURTLE");
        return m.size();
    }
}
