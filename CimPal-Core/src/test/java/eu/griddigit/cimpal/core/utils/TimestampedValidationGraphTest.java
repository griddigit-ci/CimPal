package eu.griddigit.cimpal.core.utils;

import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TimestampedValidationGraphTest {
    @TempDir Path tempDir;

    private static Model ttl(String body) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString("@prefix ex: <urn:test:> .\n" + body, Lang.TTL).parse(model);
        return model;
    }

    @Test
    void rdfXmlHeaderReaderPrefersScenarioTimeFromFullModel() throws Exception {
        String xml = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#">
                  <md:FullModel rdf:about="urn:header">
                    <md:Model.startDate>2026-08-01T00:00:00Z</md:Model.startDate>
                    <md:Model.scenarioTime>2026-08-01T12:00:00Z</md:Model.scenarioTime>
                  </md:FullModel>
                  <md:SomeLargeData>not parsed as a data graph</md:SomeLargeData>
                </rdf:RDF>
                """;

        assertEquals("2026-08-01T12:00:00Z",
                ValidationTools.readTimestampFromRdfXmlHeader(
                        new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void rdfXmlHeaderReaderRecognizesTypedDatasetStartDate() throws Exception {
        String xml = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:dcat="http://www.w3.org/ns/dcat#">
                  <rdf:Description rdf:about="urn:header">
                    <rdf:type rdf:resource="http://www.w3.org/ns/dcat#Dataset"/>
                    <dcat:startDate>2026-08-01T00:00:00Z</dcat:startDate>
                  </rdf:Description>
                </rdf:RDF>
                """;

        assertEquals("2026-08-01T00:00:00Z",
                ValidationTools.readTimestampFromRdfXmlHeader(
                        new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void zipXmlEntriesAreReadDirectlyWithoutCreatingExtractedFiles() throws Exception {
        Path zip = tempDir.resolve("input.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("nested/IGM_TEST_EQ_20260101T0000Z.xml"));
            output.write("<root>from zip</root>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }

        var entries = ValidationTools.scanZipXmlEntries(zip);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertFalse(Files.exists(entry.virtualPath));

        try (var input = entry.openStream()) {
            assertEquals("<root>from zip</root>",
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        assertFalse(Files.exists(entry.virtualPath));
    }

    @Test
    void mappingShapeSetUsesTheExactCombinedRootsAndCachesItIndependently() throws Exception {
        Path firstRoot = tempDir.resolve("first.ttl");
        Path secondRoot = tempDir.resolve("second.ttl");
        Files.writeString(firstRoot, """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:first> a sh:NodeShape; sh:targetNode <urn:test:a>;
                    sh:property [ sh:path <urn:test:p>; sh:minCount 1 ] .
                """);
        Files.writeString(secondRoot, """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:second> a sh:NodeShape; sh:targetNode <urn:test:a>;
                    sh:property [ sh:path <urn:test:q>; sh:minCount 1 ] .
                """);

        Map<String, ValidationTools.CachedShapes> cache = new ConcurrentHashMap<>();
        var combined = ValidationTools.loadParsedShapesWithImports(
                List.of(firstRoot, secondRoot), tempDir, cache);
        var sameCombinationReordered = ValidationTools.loadParsedShapesWithImports(
                List.of(secondRoot, firstRoot), tempDir, cache);

        assertSame(combined, sameCombinationReordered);
        assertFalse(ShaclValidator.get().validate(combined.shapes(), ttl("ex:a ex:p ex:b .").getGraph()).conforms());
        assertEquals(1, cache.size());
    }

    @Test
    void selectedUnionMatchesPhysicalMergeIncludingSparqlAndDuplicates() {
        Model first = ttl("ex:a a ex:Thing; ex:p ex:b .");
        Model second = ttl("ex:a a ex:Thing; ex:p ex:b . ex:b ex:q ex:c .");
        Model excluded = ttl("ex:a ex:p ex:extra .");
        Path one = tempDir.resolve("one.xml").toAbsolutePath();
        Path two = tempDir.resolve("two.xml").toAbsolutePath();
        Path other = tempDir.resolve("other.xml").toAbsolutePath();
        Map<Path, Model> cache = new ConcurrentHashMap<>(Map.of(one, first, two, second, other, excluded));
        Graph union = ValidationTools.loadRdfXmlGraphFromFilesWithCache(
                List.of(one, two, one), new ConcurrentHashMap<>(cache), cache, Map.of(), Map.of(), "urn:test:", 1);
        Model merged = ModelFactory.createDefaultModel().add(first).add(second);
        assertTrue(merged.isIsomorphicWith(ModelFactory.createModelForGraph(union)));
        assertEquals(3, union.size());
        Model shapesModel = ttl("""
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                ex:shape a sh:NodeShape; sh:targetClass ex:Thing;
                    sh:property [ sh:path ex:p; sh:maxCount 1 ];
                    sh:property [ sh:path (ex:p ex:q); sh:minCount 1 ];
                    sh:sparql [ sh:message "cross-file match"; sh:select "SELECT $this WHERE { $this <urn:test:p> ?b . ?b <urn:test:q> <urn:test:c> }" ] .
                """);
        Shapes shapes = Shapes.parse(shapesModel.getGraph());
        var expected = ShaclValidator.get().validate(shapes, merged.getGraph());
        var actual = ShaclValidator.get().validate(shapes, union);
        assertFalse(actual.conforms());
        assertEquals(1, actual.getEntries().size());
        assertTrue(expected.getModel().isIsomorphicWith(actual.getModel()));
        assertEquals(2, first.size());
        assertEquals(3, second.size());
        assertFalse(first.isClosed());
        assertFalse(second.isClosed());
    }

    @Test
    void parsedImportsAreSharedAcrossConcurrentRowsAndCanValidateConcurrently() throws Exception {
        Path root = tempDir.resolve("root.ttl");
        Path imported = tempDir.resolve("imported.ttl");
        Files.writeString(imported, """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:shape> a sh:NodeShape; sh:targetNode <urn:test:a>;
                    sh:property [ sh:path <urn:test:p>; sh:minCount 1 ] .
                """);
        Files.writeString(root, "<urn:test:root> <http://www.w3.org/2002/07/owl#imports> <" + imported.toUri() + "> .");
        Map<String, ValidationTools.CachedShapes> cache = new ConcurrentHashMap<>();
        var source = new ValidationTools.LocalShapeSource(root);
        Model data = ttl("ex:a ex:p ex:b .");
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<ValidationTools.CachedShapes>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> {
                    var entry = ValidationTools.loadParsedShapesWithImports(source, tempDir, cache);
                    assertTrue(ShaclValidator.get().validate(entry.shapes(), data.getGraph()).conforms());
                    return entry;
                });
            }
            var results = executor.invokeAll(tasks);
            var first = results.getFirst().get();
            for (var result : results) assertSame(first, result.get());
            assertFalse(ShaclValidator.get().validate(first.shapes(), ModelFactory.createDefaultModel().getGraph()).conforms());
        }
        assertEquals(1, cache.size());
    }

    @Test
    void failedShapeLoadIsNotCachedAndCanBeRetried() throws Exception {
        Path root = tempDir.resolve("missing.ttl");
        var source = new ValidationTools.LocalShapeSource(root);
        Map<String, ValidationTools.CachedShapes> cache = new ConcurrentHashMap<>();
        assertThrows(java.io.IOException.class,
                () -> ValidationTools.loadParsedShapesWithImports(source, tempDir, cache));
        assertTrue(cache.isEmpty());
        Files.writeString(root, "<urn:test:s> <urn:test:p> <urn:test:o> .");
        assertNotNull(ValidationTools.loadParsedShapesWithImports(source, tempDir, cache));
        assertEquals(1, cache.size());
    }
}
