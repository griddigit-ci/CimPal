/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paths that Core resolves itself (mapping CSV cells, owl:imports) must stay under the allowed
 * roots while serve/mcp/run have a policy active (SEC-2, gap G2). A refused path is a row error,
 * never a silent pass.
 */
class ValidationPathPolicyTest {

    @TempDir
    Path tempDir;

    private Path root;
    private Path outside;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(tempDir.resolve("root"));
        outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.createDirectories(root.resolve("models"));
        Files.createDirectories(root.resolve("constraints"));
    }

    private MappingValidationOptions options(String xmlInputs, String ttl) throws Exception {
        Path mapping = Files.writeString(root.resolve("mapping.csv"),
                "xml_inputs,ttl,notes\n" + xmlInputs + "," + ttl + ",check\n");
        return MappingValidationOptions.builder()
                .mappingCsv(mapping)
                .modelsInput(root.resolve("models"))
                .constraintsRoot(root.resolve("constraints"))
                .outputDir(root.resolve("out"))
                .xmlBase(TestModels.XML_BASE)
                .threads(1)
                .build();
    }

    private MappingValidationSummary validate(MappingValidationOptions options, boolean withPolicy) throws Exception {
        if (!withPolicy) {
            return new MappingValidator(options).validate();
        }
        PathPolicy policy = PathPolicy.builder().root(root).build();
        return PathPolicy.runWith(policy, () -> new MappingValidator(options).validate());
    }

    @Test
    void mappingCellPointingOutsideTheRootIsAnError() throws Exception {
        Files.writeString(outside.resolve("data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(root.resolve("constraints/shapes.ttl"), TestModels.THING_SHAPES);
        MappingValidationOptions options = options("../../outside/data.xml", "shapes.ttl");

        // Without a policy (direct CLI use) the cell is followed, as before.
        assertThat(validate(options, false).violations()).isEqualTo(1);

        MappingValidationSummary guarded = validate(options, true);
        assertThat(guarded.violations()).isZero();
        assertThat(guarded.conforming()).isZero();
        assertThat(guarded.errors()).isEqualTo(1);
    }

    @Test
    void owlImportOutsideTheRootIsAnError() throws Exception {
        Files.writeString(root.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(outside.resolve("imported.ttl"), TestModels.THING_SHAPES);
        Files.writeString(root.resolve("constraints/shapes.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <urn:test:shapes> a owl:Ontology ; owl:imports <%s> .
                """.formatted(outside.resolve("imported.ttl").toUri()));
        MappingValidationOptions options = options("data.xml", "shapes.ttl");

        assertThat(validate(options, false).violations()).isEqualTo(1);

        MappingValidationSummary guarded = validate(options, true);
        assertThat(guarded.violations()).isZero();
        assertThat(guarded.conforming()).isZero();
        assertThat(guarded.errors()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "file://attacker.example/share/x.ttl", "file:////attacker.example/share/x.ttl",
            "//attacker.example/share/x.ttl", "\\\\attacker.example\\share\\x.ttl", "\\/attacker.example/share/x.ttl"})
    void networkImportsAreRecognised(String uri) {
        assertThat(ValidationTools.isNetworkImport(uri)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "file:///C:/shapes/x.ttl", "file:/C:/shapes/x.ttl", "file://localhost/C:/shapes/x.ttl",
            "file:///opt/shapes/x.ttl", "sub/x.ttl", "C://shapes/x.ttl", "https://example.org/x.ttl"})
    void localImportsAreNotNetworkImports(String uri) {
        assertThat(ValidationTools.isNetworkImport(uri)).isFalse();
    }

    @Test
    void networkImportFailsTheRowEvenWithoutAPolicy() throws Exception {
        // Opening \\host\share would make Windows send the user's credentials to that host.
        Files.writeString(root.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(root.resolve("constraints/shapes.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <urn:test:shapes> a owl:Ontology ; owl:imports <file://attacker.invalid/share/x.ttl> .
                """ + "\n" + TestModels.THING_SHAPES);

        MappingValidationSummary summary = validate(options("data.xml", "shapes.ttl"), false);

        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.violations()).isZero();
    }

    @Test
    void networkMappingCellIsAnErrorUnderAPolicy() throws Exception {
        Files.writeString(root.resolve("constraints/shapes.ttl"), TestModels.THING_SHAPES);

        MappingValidationSummary guarded = validate(options("//attacker.invalid/share/data.xml", "shapes.ttl"), true);

        assertThat(guarded.errors()).isEqualTo(1);
        assertThat(guarded.violations()).isZero();
    }

    @Test
    void pathsInsideTheRootStillValidate() throws Exception {
        Files.writeString(root.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(root.resolve("constraints/shapes.ttl"), TestModels.THING_SHAPES);

        MappingValidationSummary guarded = validate(options("data.xml", "shapes.ttl"), true);

        assertThat(guarded.violations()).isEqualTo(1);
        assertThat(guarded.errors()).isZero();
    }
}
