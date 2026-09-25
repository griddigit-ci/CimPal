/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MappingValidatorTest {

    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <urn:test:> .
            ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
                sh:property [ sh:path ex:size ; sh:minCount 1 ] .
            """;

    /** One ex:Thing without the ex:size the shapes require, under a model header. */
    private static final String VIOLATING_MODEL = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                     xmlns:ex="urn:test:">
              <md:FullModel rdf:about="urn:uuid:header">
                <md:Model.scenarioTime>2026-01-01T00:00:00Z</md:Model.scenarioTime>
              </md:FullModel>
              <ex:Thing rdf:about="#_1"/>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    private Path write(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private MappingValidationOptions.Builder options(String modelPath, String mappingInput) throws Exception {
        write("models/" + modelPath, VIOLATING_MODEL);
        write("constraints/shapes.ttl", SHAPES);
        return MappingValidationOptions.builder()
                .mappingCsv(write("mapping.csv", "xml_inputs,ttl,notes\n" + mappingInput + ",shapes.ttl,Thing check\n"))
                .modelsInput(tempDir.resolve("models"))
                .constraintsRoot(tempDir.resolve("constraints"))
                .outputDir(tempDir.resolve("out"))
                .xmlBase("http://example.com/data")
                .threads(1);
    }

    @Test
    void mappingRunWritesOneWorkbookAndCountsTheViolation() throws Exception {
        MappingValidationSummary summary = new MappingValidator(
                options("data.xml", "data.xml").exportTurtleReports(true).build()).validate();

        assertEquals(1, summary.violations());
        assertEquals(0, summary.conforming());
        assertEquals(0, summary.errors());
        assertEquals(1, summary.reports().size());
        assertTrue(Files.isRegularFile(summary.reports().getFirst()));
        try (Stream<Path> out = Files.list(tempDir.resolve("out"))) {
            assertTrue(out.anyMatch(p -> p.getFileName().toString().endsWith("__report.ttl")));
        }
    }

    @Test
    void timestampedRunValidatesEachTimestampOfEachInputGroup() throws Exception {
        MappingValidationSummary summary = new MappingValidator(
                options("IGM_Test/IGM_Test_EQ_20260101T0000Z.xml", "EQ").timestamped(true).build()).validate();

        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertEquals(0, summary.errors(), () -> "summary: " + summary);
        assertFalse(summary.reports().isEmpty());
        summary.reports().forEach(report -> assertTrue(Files.isRegularFile(report), report::toString));
    }
}
