/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig.Entry;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig.Options;
import eu.griddigit.cimpal.core.utils.SHACLValidator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topbraid.shacl.vocabulary.SH;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ShapeDeactivationConfigTest {

    private static final String SH_PROPERTY_SHAPE = "http://www.w3.org/ns/shacl#PropertyShape";
    private static final Instant ISSUED = Instant.parse("2026-11-05T10:15:30Z");

    @TempDir
    Path tempDir;

    @Test
    void turtleParsesBackWithHeaderImportsAndDeactivatedShapes() {
        String turtle = ShapeDeactivationConfig.toTurtle(options(
                List.of("https://example.com/cgmes/EQ.ttl", "file:///C:/extension/EQ.ttl"),
                List.of(new Entry("http://example.com/cgmes#Switch.ratedCurrent-valueRange", List.of(SH_PROPERTY_SHAPE),
                                "Same rule: C:EXT:EQ:Switch.ratedCurrent\nsecond line"),
                        new Entry("http://example.com/cgmes#Odd(name)", null, ""))));

        Model model = parse(turtle);
        Resource ontology = model.createResource("http://example.com/config#Ontology");
        assertTrue(ontology.hasProperty(RDF.type, OWL2.Ontology));
        assertEquals(Set.of("https://example.com/cgmes/EQ.ttl", "file:///C:/extension/EQ.ttl"),
                ontology.listProperties(OWL2.imports).mapWith(s -> s.getResource().getURI()).toSet());
        assertEquals(Set.of("http://example.com/cgmes#Switch.ratedCurrent-valueRange", "http://example.com/cgmes#Odd(name)"),
                model.listSubjectsWithProperty(SH.deactivated, model.createTypedLiteral(true))
                        .mapWith(Resource::getURI).toSet());
        assertTrue(model.contains(model.createResource("http://example.com/cgmes#Odd(name)"), RDF.type, SH.PropertyShape));

        assertTrue(turtle.contains("equ:Switch.ratedCurrent-valueRange\n"), "a safe IRI is written with its prefix");
        assertTrue(turtle.contains("<http://example.com/cgmes#Odd(name)>"), "an unsafe local name is written in full");
        assertTrue(turtle.contains("# Same rule: C:EXT:EQ:Switch.ratedCurrent\n# second line\n"), turtle);
        assertTrue(turtle.contains("\"2026-11-05T10:15:30Z\"^^xsd:dateTime"));
        assertTrue(turtle.contains("\"2026-11-05\"^^xsd:date"));
        assertTrue(turtle.contains("\"urn:uuid:00000000-0000-0000-0000-000000000001\""));
    }

    @Test
    void outputIsStableForTheSameInput() {
        Options options = options(List.of("https://example.com/a.ttl"),
                List.of(new Entry("http://example.com/cgmes#A", List.of(SH_PROPERTY_SHAPE), "why")));

        assertEquals(ShapeDeactivationConfig.toTurtle(options), ShapeDeactivationConfig.toTurtle(options));
    }

    @Test
    void entriesWithoutACommentAreWrittenWithoutCommentLines() {
        String turtle = ShapeDeactivationConfig.toTurtle(options(List.of("https://example.com/a.ttl"),
                List.of(new Entry("http://example.com/cgmes#A", List.of(SH_PROPERTY_SHAPE), ""),
                        new Entry("http://example.com/cgmes#B", List.of(SH_PROPERTY_SHAPE), null))));

        assertFalse(turtle.lines().anyMatch(line -> line.startsWith("#")), turtle);
        Model model = parse(turtle);
        assertTrue(model.contains(model.createResource("http://example.com/cgmes#A"), SH.deactivated));
        assertTrue(model.contains(model.createResource("http://example.com/cgmes#B"), SH.deactivated));
    }

    @Test
    void invalidImportsAndPrefixesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ShapeDeactivationConfig.toTurtle(
                options(List.of("C:\\extension\\EQ.ttl"), List.of())));
        assertThrows(IllegalArgumentException.class, () -> ShapeDeactivationConfig.toTurtle(
                options(List.of("https://example.com/a b.ttl"), List.of())));
        assertThrows(IllegalArgumentException.class, () -> ShapeDeactivationConfig.toTurtle(
                new Options("http://example.com/config", "1bad", "", "", "", List.of(), List.of(), Map.of(), ISSUED, null)));
    }

    @Test
    void importChecks() {
        assertNull(ShapeDeactivationConfig.importProblem("https://example.com/EQ.ttl"));
        assertNull(ShapeDeactivationConfig.importProblem(Path.of("C:/x/EQ.ttl").toUri().toString()));
        assertNotNull(ShapeDeactivationConfig.importProblem(""));
        assertNotNull(ShapeDeactivationConfig.importProblem("relative/EQ.ttl"));
        assertNotNull(ShapeDeactivationConfig.importProblem("https://example.com/<EQ>.ttl"));

        assertTrue(ShapeDeactivationConfig.isFetchableWebImport("https://example.com/EQ.ttl"));
        assertTrue(ShapeDeactivationConfig.isFetchableWebImport("https://example.com/EQ.TTL?raw=true"));
        assertFalse(ShapeDeactivationConfig.isFetchableWebImport("https://example.com/constraints/EQ"));
    }

    /**
     * End to end: a configuration importing a base and an override file, deactivating a base
     * property shape that an active node shape reaches through sh:property. Validation must skip
     * that shape and still apply the rest of the base file and the override file.
     */
    @Test
    void validationThroughTheConfigurationSkipsOnlyTheDeactivatedShapes() throws Exception {
        Path base = write("base.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix equ: <http://example.com/cgmes#> .
                @prefix ex: <urn:test:> .
                equ:Thing a sh:NodeShape ; sh:targetClass ex:Thing ;
                    sh:property equ:Thing.a-required , equ:Thing.b-required , equ:Thing.d-sparql .
                equ:Thing.a-required a sh:PropertyShape ; sh:path ex:a ; sh:minCount 1 ; sh:name "C:301:EQ:Thing.a:required" .
                equ:Thing.b-required a sh:PropertyShape ; sh:path ex:b ; sh:minCount 1 ; sh:name "C:301:EQ:Thing.b:required" .
                equ:Thing.d-sparql a sh:PropertyShape ; sh:path ex:d ; sh:name "C:301:EQ:Thing.d:sparql" ;
                    sh:sparql equ:Thing.d-sparqlQuery .
                equ:Thing.d-sparqlQuery a sh:SPARQLConstraint ; sh:message "ex:d is not allowed" ;
                    sh:select "SELECT $this ?value WHERE { $this $PATH ?value . }" .
                """);
        Path override = write("override.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix eqc: <http://example.com/extension#> .
                @prefix ex: <urn:test:> .
                eqc:Thing a sh:NodeShape ; sh:targetClass ex:Thing ; sh:property eqc:Thing.c-required .
                eqc:Thing.c-required a sh:PropertyShape ; sh:path ex:c ; sh:minCount 1 ; sh:name "C:EXT:EQ:Thing.c:required" .
                """);
        Path data = write("data.xml", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                  <ex:Thing rdf:about="urn:test:thing1"><ex:d>set</ex:d></ex:Thing>
                </rdf:RDF>
                """);

        Path config = tempDir.resolve("config.ttl");
        ShapeDeactivationConfig.write(options(List.of(base.toUri().toString(), override.toUri().toString()),
                List.of(new Entry("http://example.com/cgmes#Thing.a-required", List.of(SH_PROPERTY_SHAPE), "test"),
                        new Entry("http://example.com/cgmes#Thing.d-sparql", List.of(SH_PROPERTY_SHAPE), "test"))), config);

        assertEquals(Set.of("C:301:EQ:Thing.b:required", "C:EXT:EQ:Thing.c:required"), violatedRules(config, data),
                "neither the deactivated core constraint nor the deactivated SPARQL-based shape fires");

        Path withoutDeactivation = tempDir.resolve("plain.ttl");
        ShapeDeactivationConfig.write(options(List.of(base.toUri().toString(), override.toUri().toString()),
                List.of()), withoutDeactivation);
        assertEquals(Set.of("C:301:EQ:Thing.a:required", "C:301:EQ:Thing.b:required", "C:301:EQ:Thing.d:sparql",
                        "C:EXT:EQ:Thing.c:required"),
                violatedRules(withoutDeactivation, data), "control: the shape fires when it is not deactivated");
    }

    private Set<String> violatedRules(Path config, Path data) throws Exception {
        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(data).shapeFiles(config).xmlBase("urn:test:").build()).validate();
        return report.getResults().stream().map(SHACLValidationResult::getName).collect(Collectors.toSet());
    }

    private static Options options(List<String> imports, List<Entry> entries) {
        return new Options("http://example.com/config", "config", "Test configuration", "Deactivates replaced rules",
                "1", imports, entries, Map.of("equ", "http://example.com/cgmes#"), ISSUED,
                "urn:uuid:00000000-0000-0000-0000-000000000001");
    }

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static Model parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(model);
        return model;
    }
}
