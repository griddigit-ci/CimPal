/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.shacl_tools.ShapeConfigurationMerger.Header;
import eu.griddigit.cimpal.core.shacl_tools.ShapeConfigurationMerger.Result;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig.Entry;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig.Options;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import eu.griddigit.cimpal.core.utils.SHACLValidator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ShapeConfigurationMergerTest {

    private static final String SH_PROPERTY_SHAPE = "http://www.w3.org/ns/shacl#PropertyShape";
    private static final String SH_NODE_SHAPE = "http://www.w3.org/ns/shacl#NodeShape";
    private static final String CGMES = "http://example.com/cgmes#";
    private static final Header HEADER = new Header("http://example.com/config-merged", "Merged", null, "1",
            Instant.parse("2026-11-16T10:00:00Z"), "urn:uuid:00000000-0000-0000-0000-000000000002", null);

    @TempDir
    Path tempDir;

    /**
     * The base file has a deactivated core shape, a deactivated SPARQL-based shape and an active
     * SPARQL-based shape whose query needs the prefixes its ontology declares. The merged file must
     * validate like the configuration and keep the prefix declarations and the vocabulary import.
     */
    @Test
    void mergedFileValidatesLikeTheConfiguration() throws Exception {
        Path base = writeBase();
        Path override = writeOverride();
        Path data = writeData();
        Path config = writeConfig(List.of(base, override), List.of(
                new Entry(CGMES + "Thing.a-required", List.of(SH_PROPERTY_SHAPE), "test"),
                new Entry(CGMES + "Thing.d-sparql", List.of(SH_PROPERTY_SHAPE), "test")));

        Result result = ShapeConfigurationMerger.mergeConfiguration(config, HEADER);
        Path merged = tempDir.resolve("merged.ttl");
        ShapeConfigurationMerger.write(result, merged);

        Set<String> expected = Set.of("C:301:EQ:Thing.b:required", "C:301:EQ:Thing.f:sparql", "C:EXT:EQ:Thing.c:required");
        assertEquals(expected, violatedRules(config, data), "the configuration");
        assertEquals(expected, violatedRules(merged, data), "the merged file validates the same");

        Model model = RDFDataMgr.loadModel(merged.toUri().toString());
        assertFalse(model.contains(model.createResource(CGMES + "Thing.a-required"), null, (RDFNode) null));
        assertFalse(model.contains(model.createResource(CGMES + "Thing.d-sparql"), null, (RDFNode) null));
        assertFalse(model.contains(model.createResource(CGMES + "Thing.d-sparqlQuery"), null, (RDFNode) null),
                "the SPARQL constraint only the removed shape used goes too");
        assertEquals(Set.of(CGMES + "Thing.b-required", CGMES + "Thing.f-sparql"),
                model.listObjectsOfProperty(model.createResource(CGMES + "Thing"), SH.property).toList().stream()
                        .map(node -> node.asResource().getURI()).collect(Collectors.toSet()));
        assertEquals(1, model.listObjectsOfProperty(model.createResource("http://example.com/cgmes"), SH.declare)
                        .toList().size(),
                "the ontology declaring the SPARQL prefixes stays, with the declaration both files repeat once");

        assertEquals(Set.of("http://www.w3.org/ns/shacl#"),
                model.listObjectsOfProperty(OWL.imports).toList().stream()
                        .map(node -> node.asResource().getURI()).collect(Collectors.toSet()),
                "the merged files are no longer imported; the vocabulary import stays");
        assertTrue(RDFDataMgr.loadModel(config.toUri().toString())
                        .contains(model.createResource("http://example.com/config#Ontology"), RDF.type, OWL.Ontology),
                "control: this is the configuration's header");
        assertFalse(model.contains(model.createResource("http://example.com/config#Ontology"), null, (RDFNode) null),
                "the configuration's header is replaced");
        Resource header = model.createResource("http://example.com/config-merged");
        assertTrue(model.contains(header, RDF.type, OWL.Ontology));
        assertEquals("urn:uuid:00000000-0000-0000-0000-000000000002", header.getProperty(DCTerms.identifier).getString());

        assertEquals(2, result.removedShapes());
        assertEquals(1, result.droppedReferences(), "Thing.d-sparql is not referenced; Thing.a-required is");
        assertEquals(List.of("base.ttl", "override.ttl"), result.documents());
        assertTrue(result.keptDeactivated().isEmpty());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    /** A deactivated shape reached through sh:node is kept, switched off, rather than removed. */
    @Test
    void shapeReachedThroughShNodeIsKeptDeactivated() throws Exception {
        Path base = write("base.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix equ: <http://example.com/cgmes#> .
                @prefix ex: <urn:test:> .
                equ:Holder a sh:NodeShape ; sh:targetClass ex:Thing ; sh:node equ:Inner .
                equ:Inner a sh:NodeShape ; sh:deactivated false ;
                    sh:property [ sh:path ex:e ; sh:minCount 1 ; sh:name "C:301:EQ:Thing.e:required" ] .
                """);
        Path data = writeData();
        Path config = writeConfig(List.of(base), List.of(new Entry(CGMES + "Inner", List.of(SH_NODE_SHAPE), "")));

        Result result = ShapeConfigurationMerger.mergeConfiguration(config, HEADER);
        Path merged = tempDir.resolve("merged.ttl");
        ShapeConfigurationMerger.write(result, merged);

        assertEquals(List.of(CGMES + "Inner"), result.keptDeactivated());
        assertEquals(0, result.removedShapes());
        assertFalse(result.warnings().isEmpty());
        Model model = RDFDataMgr.loadModel(merged.toUri().toString());
        Resource inner = model.createResource(CGMES + "Inner");
        assertEquals(List.of(model.createTypedLiteral(true)), model.listObjectsOfProperty(inner, SH.deactivated).toList(),
                "only sh:deactivated true is left");
        assertTrue(model.contains(model.createResource(CGMES + "Holder"), SH.node, inner));
        assertEquals(violatedRules(config, data), violatedRules(merged, data));
    }

    /** An import that is not read must fail the merge, not give a merged file without it. */
    @Test
    void anImportThatIsNotReadIsAnError() throws Exception {
        Path base = writeBase();
        Path config = tempDir.resolve("config.ttl");
        ShapeDeactivationConfig.write(options(List.of(base.toUri().toString(), "https://example.com/shapes/EQ"),
                List.of()), config);

        IOException ex = assertThrows(IOException.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER));
        assertTrue(ex.getMessage().contains("https://example.com/shapes/EQ"), ex.getMessage());
    }

    @Test
    void localFilesMergeWithoutTheGivenShapes() throws Exception {
        Path base = writeBase();
        Path override = writeOverride();

        Result result = ShapeConfigurationMerger.mergeFiles(List.of(base, override),
                List.of(CGMES + "Thing.a-required", CGMES + "Thing.gone"), HEADER);

        Model model = result.model();
        assertFalse(model.contains(model.createResource(CGMES + "Thing.a-required"), null, (RDFNode) null));
        assertTrue(model.contains(model.createResource(CGMES + "Thing.b-required"), RDF.type, SH.PropertyShape));
        assertTrue(model.contains(model.createResource("http://example.com/extension#Thing.c-required"), RDF.type,
                SH.PropertyShape));
        assertEquals(1, result.removedShapes());
        assertEquals(1, result.warnings().size(), "the shape that is in neither file is reported");
        assertTrue(result.warnings().getFirst().contains("Thing.gone"));
    }

    @Test
    void mergedHeaderIsDerivedFromTheConfiguration() {
        Header header = ShapeConfigurationMerger.mergedHeader("http://example.com/config#Ontology", "EQ constraints", "2",
                " ");
        assertEquals("http://example.com/config#Ontology-merged", header.ontologyIri());
        assertEquals("EQ constraints (merged)", header.title());
        assertEquals("2", header.version());
        assertNull(header.base(), "a blank @base means none");
        assertThrows(IllegalArgumentException.class, () -> header.withBase("not an iri"));
        assertThrows(IllegalArgumentException.class, () -> header.withBase("C:\\constraints"));
    }

    /** The @base given for the merged file is written, and the file reads back to the same triples. */
    @Test
    void mergedFileHasTheGivenBase() throws Exception {
        Path base = writeBase();
        Path config = writeConfig(List.of(base), List.of(new Entry(CGMES + "Thing.a-required", List.of(SH_PROPERTY_SHAPE), "")));

        Result result = ShapeConfigurationMerger.mergeConfigurationWithBase(config, "http://example.com/merged/Constraints");
        String turtle = ShapeConfigurationMerger.toTurtle(result);

        assertTrue(turtle.lines().anyMatch(line -> line.matches("(?i)@base\\s+<http://example.com/merged/Constraints>\\s*\\.")),
                turtle.lines().limit(5).collect(Collectors.joining("\n")));
        Model parsed = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(parsed);
        assertTrue(parsed.isIsomorphicWith(result.model()), "the @base changes how the file is written, not what it says");
        assertEquals("Test configuration (merged)", parsed.createResource("http://example.com/config#Ontology-merged")
                .getProperty(DCTerms.title).getString(), "the header is derived from the configuration's");

        Result withoutBase = ShapeConfigurationMerger.mergeConfigurationWithBase(config, "");
        assertFalse(ShapeConfigurationMerger.toTurtle(withoutBase).contains("@base"));
    }

    // ---- refusals and failures ------------------------------------------------------

    /** Credentials and query strings in an import that was not read are not repeated in the error. */
    @Test
    void importErrorDoesNotRepeatCredentials() throws Exception {
        Path base = writeBase();
        Path config = writeRawConfig("<" + base.toUri() + "> , <https://user:s3cret@example.com/shapes/EQ?sig=t0ken>");

        IOException ex = assertThrows(IOException.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER));

        assertTrue(ex.getMessage().contains("example.com/shapes/EQ"), ex.getMessage());
        assertFalse(ex.getMessage().contains("s3cret"), ex.getMessage());
        assertFalse(ex.getMessage().contains("t0ken"), ex.getMessage());
    }

    /** A web import the egress policy does not allow fails the merge before any request is made. */
    @Test
    void importFromHostOutsideTheAllowlistFails() throws Exception {
        Path config = writeRawConfig("<https://not-allowlisted.invalid/shapes/EQ.ttl>");

        assertThrows(IOException.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER));
    }

    /** Imports naming a network host are refused, in both spellings. */
    @Test
    void importOfNetworkPathIsRefused() throws Exception {
        for (String iri : List.of("<file://fileserver/share/EQ.ttl>", "<file:////fileserver/share/EQ.ttl>")) {
            Path config = writeRawConfig(iri);
            Exception ex = assertThrows(Exception.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER),
                    iri);
            assertTrue(ex instanceof PathNotAllowedException || ex instanceof IOException, iri + ": " + ex);
        }
    }

    @Test
    void malformedConfigurationFailsWithoutOutput() throws Exception {
        Path config = write("config.ttl", "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<x> a owl:Ontology ; owl:imports .");

        assertThrows(IOException.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER));
        assertFalse(Files.exists(tempDir.resolve("config-merged.ttl")));
    }

    @Test
    void configurationWithoutImportsFails() throws Exception {
        Path config = writeConfig(List.of(), List.of());

        IOException ex = assertThrows(IOException.class, () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER));
        assertTrue(ex.getMessage().contains("no owl:imports"), ex.getMessage());
    }

    /** Under serve/mcp/run a configuration outside the roots is refused before it is read. */
    @Test
    void configurationOutsideTheRootsIsRefused() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path config = Files.writeString(outside.resolve("config.ttl"), "this is not turtle");
        PathPolicy policy = PathPolicy.builder().root(root).build();

        Exception ex = assertThrows(Exception.class, () -> PathPolicy.runWith(policy,
                () -> ShapeConfigurationMerger.mergeConfiguration(config, HEADER)));
        assertInstanceOf(PathNotAllowedException.class, ex, "refused by the policy, not failed by the parser");
    }

    /** The merged file never replaces a file merged into it: not the configuration, not an import. */
    @Test
    void mergedFileDoesNotReplaceAnInput() throws Exception {
        Path base = writeBase();
        Path config = writeConfig(List.of(base), List.of());
        String original = Files.readString(base);
        Result result = ShapeConfigurationMerger.mergeConfiguration(config, HEADER);

        assertThrows(IOException.class, () -> ShapeConfigurationMerger.write(result, base));
        assertThrows(IOException.class, () -> ShapeConfigurationMerger.write(result, config));
        assertThrows(IOException.class, () -> ShapeConfigurationMerger.write(result,
                tempDir.resolve("sub").resolve("..").resolve("base.ttl")), "another spelling of the same file");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertThrows(IOException.class, () -> ShapeConfigurationMerger.write(result, tempDir.resolve("BASE.TTL")),
                    "the same file in other letter case");
        }
        assertEquals(original, Files.readString(base));
        assertTrue(ShapeConfigurationMerger.isInput(base, List.of(base)));
        assertFalse(ShapeConfigurationMerger.isInput(tempDir.resolve("new.ttl"), List.of(base)));
    }

    private Path writeRawConfig(String imports) throws IOException {
        return write("config.ttl", """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <http://example.com/config> a owl:Ontology ; owl:imports %s .
                """.formatted(imports));
    }

    // ---- fixtures ----------------------------------------------------------------

    private Path writeBase() throws IOException {
        return write("base.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix equ: <http://example.com/cgmes#> .
                @prefix ex: <urn:test:> .
                <http://example.com/cgmes> a owl:Ontology ; owl:imports sh: ;
                    sh:declare [ sh:prefix "ex" ; sh:namespace "urn:test:"^^xsd:anyURI ] .
                equ:Thing a sh:NodeShape ; sh:targetClass ex:Thing ;
                    sh:property equ:Thing.a-required , equ:Thing.b-required , equ:Thing.f-sparql .
                equ:Thing.a-required a sh:PropertyShape ; sh:path ex:a ; sh:minCount 1 ; sh:name "C:301:EQ:Thing.a:required" .
                equ:Thing.b-required a sh:PropertyShape ; sh:path ex:b ; sh:minCount 1 ; sh:name "C:301:EQ:Thing.b:required" .
                equ:Thing.d-sparql a sh:PropertyShape ; sh:targetClass ex:Thing ; sh:path ex:d ;
                    sh:name "C:301:EQ:Thing.d:sparql" ; sh:sparql equ:Thing.d-sparqlQuery .
                equ:Thing.d-sparqlQuery a sh:SPARQLConstraint ; sh:message "ex:d is not allowed" ;
                    sh:prefixes <http://example.com/cgmes> ;
                    sh:select "SELECT $this ?value WHERE { $this ex:d ?value . }" .
                equ:Thing.f-sparql a sh:PropertyShape ; sh:path ex:f ; sh:name "C:301:EQ:Thing.f:sparql" ;
                    sh:sparql [ a sh:SPARQLConstraint ; sh:message "ex:f is not allowed" ;
                        sh:prefixes <http://example.com/cgmes> ;
                        sh:select "SELECT $this ?value WHERE { $this ex:f ?value . }" ] .
                """);
    }

    private Path writeOverride() throws IOException {
        return write("override.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix eqc: <http://example.com/extension#> .
                @prefix ex: <urn:test:> .
                <http://example.com/cgmes> sh:declare [ sh:prefix "ex" ; sh:namespace "urn:test:"^^xsd:anyURI ] .
                eqc:Thing a sh:NodeShape ; sh:targetClass ex:Thing ; sh:property eqc:Thing.c-required .
                eqc:Thing.c-required a sh:PropertyShape ; sh:path ex:c ; sh:minCount 1 ; sh:name "C:EXT:EQ:Thing.c:required" .
                """);
    }

    private Path writeData() throws IOException {
        return write("data.xml", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                  <ex:Thing rdf:about="urn:test:thing1"><ex:d>set</ex:d><ex:f>set</ex:f></ex:Thing>
                </rdf:RDF>
                """);
    }

    private Path writeConfig(List<Path> imports, List<Entry> entries) throws IOException {
        Path config = tempDir.resolve("config.ttl");
        ShapeDeactivationConfig.write(options(imports.stream().map(path -> path.toUri().toString()).toList(), entries),
                config);
        return config;
    }

    private static Options options(List<String> imports, List<Entry> entries) {
        return new Options("http://example.com/config", "config", "Test configuration", "", "1", imports, entries,
                Map.of("equ", CGMES), Instant.parse("2026-11-16T09:00:00Z"),
                "urn:uuid:00000000-0000-0000-0000-000000000001");
    }

    private Set<String> violatedRules(Path shapes, Path data) throws Exception {
        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(data).shapeFiles(shapes).xmlBase("urn:test:").build()).validate();
        return report.getResults().stream().map(SHACLValidationResult::getName).collect(Collectors.toSet());
    }

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
