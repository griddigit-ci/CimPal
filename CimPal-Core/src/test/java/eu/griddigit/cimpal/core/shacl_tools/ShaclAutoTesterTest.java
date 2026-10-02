/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-master tests (TEST-3) for the manual workflow: each rule folder holds {@code Conform} and
 * {@code NonConform} models, and the run checks that only the non-conform ones trigger the rule
 * whose {@code sh:name} is the folder name. Snapshots: {@code snapshots/manual-validation/}.
 */
class ShaclAutoTesterTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("manual-validation");
    private static final String BASE = "http://example.com/data";

    /** Rule {@code Thing.size}: every ex:Thing needs an ex:size. Rule {@code Thing.colour}: and an ex:colour. */
    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <urn:test:> .
            ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ; sh:property ex:Size , ex:Colour .
            ex:Size a sh:PropertyShape ; sh:path ex:size ; sh:minCount 1 ; sh:name "Thing.size" .
            ex:Colour a sh:PropertyShape ; sh:path ex:colour ; sh:minCount 1 ; sh:name "Thing.colour" .
            """;

    private static final String WITH_BOTH = thing("<ex:size>1</ex:size><ex:colour>red</ex:colour>");
    private static final String WITHOUT_SIZE = thing("<ex:colour>red</ex:colour>");
    private static final String WITHOUT_COLOUR = thing("<ex:size>1</ex:size>");

    @TempDir
    Path tempDir;

    private final List<File> models = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();

    private static String thing(String properties) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                  <ex:Thing rdf:about="#_1">%s</ex:Thing>
                </rdf:RDF>
                """.formatted(properties);
    }

    private Path rules() {
        return tempDir.resolve("rules");
    }

    /** Adds a model under {@code rules/<rule>/<category>/<name>}. */
    private void model(String rule, String category, String name, String content) throws Exception {
        Path file = rules().resolve(rule).resolve(category).resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        models.add(file.toFile());
    }

    private void run(String shapes) throws Exception {
        Path shapesFile = tempDir.resolve("shapes.ttl");
        Files.writeString(shapesFile, shapes);
        Files.createDirectories(rules());
        ShaclAutoTester tester = new ShaclAutoTester(new ShaclAutoTesterCallback() {
            @Override
            public void updateProgress(double progress) {
            }

            @Override
            public void appendOutput(String message) {
                output.append(message);
            }
        });
        tester.setDatatypeMapping(null, BASE);
        tester.setValidationOptions(1, 0);
        tester.runTestsInternal(new ArrayList<>(List.of(shapesFile.toFile())), rules().toFile(), models, true, true);
    }

    private String normalizedOutput() {
        return Normalizer.chain(Normalizer.paths(tempDir), Normalizer.timestamps()).apply(output.toString());
    }

    private Path validationLog() throws Exception {
        try (Stream<Path> files = Files.list(rules())) {
            List<Path> logs = files.filter(p -> p.getFileName().toString().startsWith("validation_log_")).toList();
            assertEquals(1, logs.size(), () -> "logs: " + logs);
            return logs.getFirst();
        }
    }

    private void assertGolden(String name) throws Exception {
        SNAPSHOTS.assertTextEquals(name + "__output.txt", normalizedOutput());
        SNAPSHOTS.assertExcelEquals(name + "__log", validationLog(), Normalizer.paths(tempDir));
    }

    @Test
    void ruleBehavingAsExpectedLogsNothing() throws Exception {
        model("Thing.size", "Conform", "ok.xml", WITH_BOTH);
        model("Thing.size", "NonConform", "bad.xml", WITHOUT_SIZE);
        run(SHAPES);

        assertFalse(output.toString().contains("WARNING"), output::toString);
        assertGolden("expected");

        // Reports are exported next to each model: Excel always, Turtle when asked.
        Path conform = rules().resolve("Thing.size/Conform");
        Path nonConform = rules().resolve("Thing.size/NonConform");
        SNAPSHOTS.assertExcelEquals("expected__ok_report", conform.resolve("ok_report.xlsx"),
                Normalizer.paths(tempDir), Normalizer.blankNodeLabels());
        SNAPSHOTS.assertExcelEquals("expected__bad_report", nonConform.resolve("bad_report.xlsx"),
                Normalizer.paths(tempDir), Normalizer.blankNodeLabels());
        Model badReport = RDFDataMgr.loadModel(nonConform.resolve("bad_report.ttl").toString());
        SNAPSHOTS.assertIsomorphic("expected__bad_report", badReport);
        assertTrue(Files.isRegularFile(conform.resolve("ok_report.ttl")));
    }

    @Test
    void conformModelTriggeringTheRuleIsLogged() throws Exception {
        model("Thing.size", "Conform", "bad.xml", WITHOUT_SIZE);
        run(SHAPES);

        assertTrue(output.toString().contains("WARNING: Triggered rule: Thing.size in conform model: bad.xml"),
                output::toString);
        assertGolden("conform-triggers");
    }

    @Test
    void nonConformModelNotTriggeringTheRuleIsLogged() throws Exception {
        // The model breaks the other rule, which does not count for this folder.
        model("Thing.size", "NonConform", "colourless.xml", WITHOUT_COLOUR);
        run(SHAPES);

        assertTrue(output.toString().contains("WARNING: Rule not triggered: Thing.size in non-conform model: colourless.xml"),
                output::toString);
        assertGolden("nonconform-not-triggered");
    }

    @Test
    void malformedModelIsLoggedAndTheRunContinues() throws Exception {
        model("Thing.size", "NonConform", "broken.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><unclosed>");
        model("Thing.size", "NonConform", "bad.xml", WITHOUT_SIZE);
        run(SHAPES);

        assertGolden("malformed-model");
    }

    @Test
    void noModelsStillWritesAnEmptyLog() throws Exception {
        run(SHAPES);

        assertEquals("", normalizedOutput().replaceAll("\\s*Validation log saved to: \\S+\\s*", ""));
        assertGolden("no-models");
    }

    // ---- Suspected bugs: each test asserts the correct behaviour and fails today ----

    /**
     * Models and reports are cached by file name, so a second rule's {@code model.xml} reuses the
     * first rule's report: one of the two non-conform models is reported as not triggering.
     */
    @Disabled("Suspected bug (TEST-3): ShaclAutoTester caches models and reports by file name, not path")
    @Test
    void sameFileNameInTwoRuleFoldersIsValidatedSeparately() throws Exception {
        model("Thing.size", "NonConform", "model.xml", WITHOUT_SIZE);
        model("Thing.colour", "NonConform", "model.xml", WITHOUT_COLOUR);
        run(SHAPES);

        assertFalse(output.toString().contains("WARNING"), output::toString);
    }

    /**
     * A model that cannot be parsed is logged twice: by the parallel pre-validation as a
     * "Validation Error" with no rule name, then by the rule loop as a "Model Load Error".
     */
    @Disabled("Suspected bug (TEST-3): an unparsable model is logged twice, once without its rule")
    @Test
    void unparsableModelIsLoggedOnceUnderItsRule() throws Exception {
        model("Thing.size", "NonConform", "broken.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><unclosed>");
        run(SHAPES);

        String log = Snapshots.flattenWorkbook(validationLog(), Normalizer.paths(tempDir));
        assertEquals(1, log.lines().filter(line -> line.contains("broken.xml")).count(), log);
        assertTrue(log.contains("Thing.size,broken.xml,Model Load Error"), log);
    }

    /** A result whose source shape has no {@code sh:name} ends the whole run with a NullPointerException. */
    @Disabled("Suspected bug (TEST-3): a triggered shape without sh:name throws NullPointerException")
    @Test
    void shapeWithoutNameDoesNotAbortTheRun() throws Exception {
        // The model has neither property, so the nameless colour shape fires next to Thing.size.
        model("Thing.size", "NonConform", "bare.xml", thing(""));

        assertDoesNotThrow(() -> run(SHAPES.replace(" ; sh:name \"Thing.colour\"", "")));
        validationLog();
    }
}
