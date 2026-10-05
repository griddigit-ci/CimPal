/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.stats.RunStatsSnapshot;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference example for the TEST-1 test-support package: synthetic inputs from {@link TestModels},
 * count asserts on the summary, and {@link Snapshots} golden files for the Turtle report and the
 * workbook. Regenerate the snapshots with {@code -Dsnapshot.update=true} and review the diff.
 */
class MappingValidatorTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("mapping-validation");

    @TempDir
    Path tempDir;

    private Path write(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private MappingValidationOptions.Builder options(String modelPath, String mappingInput) throws Exception {
        write("models/" + modelPath, TestModels.VIOLATING_THING_MODEL);
        write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        return MappingValidationOptions.builder()
                .mappingCsv(write("mapping.csv", "xml_inputs,ttl,notes\n" + mappingInput + ",shapes.ttl,Thing check\n"))
                .modelsInput(tempDir.resolve("models"))
                .constraintsRoot(tempDir.resolve("constraints"))
                .outputDir(tempDir.resolve("out"))
                .xmlBase(TestModels.XML_BASE)
                .threads(1);
    }

    /**
     * Workbook cells carry run timestamps, temp-dir paths and the per-run blank-node label of the
     * anonymous property shape ({@code Source}); all three are replaced by placeholders.
     */
    private Normalizer[] workbookNormalizers() {
        return new Normalizer[] {Normalizer.timestamps(), Normalizer.paths(tempDir), Normalizer.blankNodeLabels()};
    }

    private Path singleTurtleReport() throws Exception {
        try (Stream<Path> out = Files.list(tempDir.resolve("out"))) {
            List<Path> reports = out.filter(p -> p.getFileName().toString().endsWith("__report.ttl")).toList();
            assertEquals(1, reports.size(), () -> "turtle reports: " + reports);
            return reports.getFirst();
        }
    }

    @Test
    void mappingRunWritesOneWorkbookAndCountsTheViolation() throws Exception {
        MappingValidationSummary summary = new MappingValidator(
                options("data.xml", "data.xml").exportTurtleReports(true).build()).validate();

        assertEquals(1, summary.violations());
        assertEquals(0, summary.conforming());
        assertEquals(0, summary.errors());
        assertEquals(1, summary.reports().size());
        Path workbook = summary.reports().getFirst();
        assertTrue(Files.isRegularFile(workbook));

        // sh:sourceShape holds the anonymous shape's blank-node label as a string, new on every run.
        Model report = Snapshots.normalizeLiterals(
                RDFDataMgr.loadModel(singleTurtleReport().toString()), Normalizer.blankNodeLabels());
        SNAPSHOTS.assertIsomorphic("mapping-run__report", report);
        SNAPSHOTS.assertExcelEquals("mapping-run__workbook", workbook, workbookNormalizers());
    }

    @Test
    void mappingRunCountsTheLoadedTriplesWithoutChangingTheResult() throws Exception {
        RunStats stats = RunStats.start();
        MappingValidationSummary summary = new MappingValidator(
                options("data.xml", "data.xml").runStats(stats).build()).validate();
        RunStatsSnapshot snapshot = stats.stop();

        assertEquals(1, summary.violations());
        assertEquals(0, summary.conforming());
        assertEquals(0, summary.errors());
        assertEquals(TestModels.parseRdfXml(TestModels.VIOLATING_THING_MODEL).size(), snapshot.triplesLoaded());
        assertEquals(Files.size(tempDir.resolve("models/data.xml")), snapshot.inputBytes());
    }

    @Test
    void timestampedRunCountsEachLoadedFileOnce() throws Exception {
        RunStats stats = RunStats.start();
        MappingValidationSummary summary = new MappingValidator(
                options("IGM_Test/IGM_Test_EQ_20260101T0000Z.xml", "EQ").timestamped(true).runStats(stats).build())
                .validate();
        RunStatsSnapshot snapshot = stats.stop();

        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertEquals(0, summary.errors(), () -> "summary: " + summary);
        assertEquals(TestModels.parseRdfXml(TestModels.VIOLATING_THING_MODEL).size(), snapshot.triplesLoaded());
        assertEquals(Files.size(tempDir.resolve("models/IGM_Test/IGM_Test_EQ_20260101T0000Z.xml")),
                snapshot.inputBytes());
    }

    @Test
    void timestampedRunValidatesEachTimestampOfEachInputGroup() throws Exception {
        MappingValidationSummary summary = new MappingValidator(
                options("IGM_Test/IGM_Test_EQ_20260101T0000Z.xml", "EQ").timestamped(true).build()).validate();

        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertEquals(0, summary.errors(), () -> "summary: " + summary);
        summary.reports().forEach(report -> assertTrue(Files.isRegularFile(report), report::toString));

        // One report per timestamp, a summary per input group, an overall summary and a comparison.
        Normalizer names = Normalizer.chain(Normalizer.paths(tempDir), Normalizer.timestamps());
        SNAPSHOTS.assertTextEquals("timestamped-run__reports.txt", summary.reports().stream()
                .map(report -> names.apply(report.toString()) + "\n")
                .sorted()
                .reduce("", String::concat));
        for (Path workbook : summary.reports()) {
            // The group and overall summaries share a file name, so the name keeps the sub-folder.
            String name = tempDir.resolve("out").relativize(workbook).toString()
                    .replace('\\', '/').replace("/", "__")
                    .replaceAll("__\\d{8}_\\d{6}", "").replace(".xlsx", "");
            SNAPSHOTS.assertExcelEquals("timestamped-run__" + name, workbook, workbookNormalizers());
        }
    }
}
