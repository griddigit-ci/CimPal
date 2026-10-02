/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import eu.griddigit.cimpal.core.presets.MappingValidationOptionsPresets;
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

    // ---- Golden master (TEST-3): current behaviour of the remaining mapping and timestamped options ----

    /** The {@link TestModels#VIOLATING_THING_MODEL} with {@code ex:size} filled in, so it conforms. */
    private static final String CONFORMING_THING_MODEL =
            TestModels.VIOLATING_THING_MODEL.replace("<ex:Thing rdf:about=\"#_1\"/>",
                    "<ex:Thing rdf:about=\"#_1\"><ex:size>1</ex:size></ex:Thing>");

    private static String thingModelAt(String scenarioTime, boolean conforming) {
        return (conforming ? CONFORMING_THING_MODEL : TestModels.VIOLATING_THING_MODEL)
                .replace("2026-01-01T00:00:00Z", scenarioTime);
    }

    private MappingValidationOptions.Builder mapping(String csvRows) throws Exception {
        write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        return MappingValidationOptions.builder()
                .mappingCsv(write("mapping.csv", "xml_inputs,ttl,notes\n" + csvRows))
                .modelsInput(Files.createDirectories(tempDir.resolve("models")))
                .constraintsRoot(tempDir.resolve("constraints"))
                .outputDir(tempDir.resolve("out"))
                .xmlBase(TestModels.XML_BASE)
                .threads(1);
    }

    /** Snapshots every workbook of a run under {@code <prefix>__<path below out, / as __, run stamp dropped>}. */
    private void assertWorkbooks(String prefix, MappingValidationSummary summary) {
        for (Path workbook : summary.reports()) {
            String name = tempDir.resolve("out").relativize(workbook).toString()
                    .replace('\\', '/').replace("/", "__")
                    .replaceAll("_+\\d{8}_\\d{6}", "").replace(".xlsx", "");
            SNAPSHOTS.assertExcelEquals(prefix + "__" + name, workbook, workbookNormalizers());
        }
    }

    @Test
    void mappingRowsAreCountedAsConformingViolatingOrError() throws Exception {
        write("models/ok.xml", CONFORMING_THING_MODEL);
        write("models/bad.xml", TestModels.VIOLATING_THING_MODEL);
        write("models/broken.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><unclosed>");
        MappingValidationSummary summary = new MappingValidator(mapping("""
                ok.xml,shapes.ttl,Conforming
                bad.xml,shapes.ttl,Violating
                broken.xml,shapes.ttl,Malformed model
                missing.xml,shapes.ttl,Missing model
                ok.xml,nope.ttl,Missing shapes
                """).build()).validate();

        assertEquals(1, summary.conforming(), () -> "summary: " + summary);
        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertEquals(3, summary.errors(), () -> "summary: " + summary);
        assertWorkbooks("mapping-rows", summary);
    }

    @Test
    void headerOnlyAndMalformedMappingsValidateNothing() throws Exception {
        // Lines with fewer than two columns are skipped, so a mapping of only such lines is empty.
        MappingValidationSummary headerOnly = new MappingValidator(mapping("").build()).validate();
        assertEquals(0, headerOnly.totalRows(), () -> "summary: " + headerOnly);
        assertWorkbooks("header-only", headerOnly);

        Files.walk(tempDir.resolve("out")).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        MappingValidationSummary oneColumn = new MappingValidator(mapping("just-one-column\n").build()).validate();
        assertEquals(0, oneColumn.totalRows(), () -> "summary: " + oneColumn);
    }

    @Test
    void resultLimitMarksTheRowPartial() throws Exception {
        write("models/many.xml", TestModels.VIOLATING_THING_MODEL.replace("<ex:Thing rdf:about=\"#_1\"/>",
                "<ex:Thing rdf:about=\"#_1\"/><ex:Thing rdf:about=\"#_2\"/><ex:Thing rdf:about=\"#_3\"/>"));
        MappingValidationSummary summary = new MappingValidator(
                mapping("many.xml,shapes.ttl,Limited\n").maxResultsPerConstraint(1).build()).validate();

        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertWorkbooks("result-limit", summary);
    }

    /** One ACLineSegment with a float {@code r}, checked by a {@code sh:datatype xsd:float} shape. */
    private MappingValidationSummary validateLine(MappingValidationOptions.Builder preset, String cimNs) throws Exception {
        write("models/line.xml", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="%s">
                  <cim:ACLineSegment rdf:ID="_line"><cim:ACLineSegment.r>0.25</cim:ACLineSegment.r></cim:ACLineSegment>
                </rdf:RDF>
                """.formatted(cimNs));
        write("constraints/line.ttl", TestModels.datatypeShape("cim:ACLineSegment", "cim:ACLineSegment.r", "float")
                .replace(TestModels.CIM_NS, cimNs));
        return new MappingValidator(preset
                .mappingCsv(write("mapping.csv", "xml_inputs,ttl,notes\nline.xml,line.ttl,Line\n"))
                .modelsInput(tempDir.resolve("models"))
                .constraintsRoot(tempDir.resolve("constraints"))
                .outputDir(tempDir.resolve("out"))
                .threads(1)
                .build()).validate();
    }

    @Test
    void cgmes30PresetTypesCim100Literals() throws Exception {
        MappingValidationSummary summary = validateLine(MappingValidationOptionsPresets.cgmes30(), TestModels.CIM_NS);
        assertEquals(1, summary.conforming(), () -> "summary: " + summary);
    }

    @Test
    void cgmes24PresetTypesCim16Literals() throws Exception {
        MappingValidationSummary summary = validateLine(MappingValidationOptionsPresets.cgmes24(),
                "http://iec.ch/TC57/2013/CIM-schema-cim16#");
        assertEquals(1, summary.conforming(), () -> "summary: " + summary);
    }

    @Test
    void withoutADatatypeMapTheFloatCheckFails() throws Exception {
        MappingValidationSummary summary = validateLine(
                MappingValidationOptions.builder().xmlBase(TestModels.XML_BASE), TestModels.CIM_NS);
        assertEquals(1, summary.violations(), () -> "summary: " + summary);
    }

    @Test
    void timestampedRunCoversEveryTimestampOfEveryGroup() throws Exception {
        write("models/RegionA/RegionA_EQ_20260101T0000Z.xml", thingModelAt("2026-01-01T00:00:00Z", false));
        write("models/RegionA/RegionA_EQ_20260101T0100Z.xml", thingModelAt("2026-01-01T01:00:00Z", true));
        write("models/RegionB/RegionB_EQ_20260101T0000Z.xml", thingModelAt("2026-01-01T00:00:00Z", true));
        MappingValidationSummary summary = new MappingValidator(
                mapping("EQ,shapes.ttl,Thing check\n").timestamped(true).build()).validate();

        assertEquals(2, summary.conforming(), () -> "summary: " + summary);
        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertEquals(0, summary.errors(), () -> "summary: " + summary);
        // Report times are snapped to minute 30 of the hour (ValidationTools.normalizeTimestampToReportTime).
        assertTrue(summary.reports().stream().anyMatch(p -> p.getFileName().toString()
                .equals("validation_report_RegionA_2026-01-01T01_30_00Z.xlsx")), () -> "reports: " + summary.reports());
        assertWorkbooks("timestamped-groups", summary);
    }

    /** A ZIP archive is one input group named after the archive; its sub-folders are not groups. */
    @Test
    void timestampedRunReadsAZipArchive() throws Exception {
        Path zip = Files.createDirectories(tempDir.resolve("zipped")).resolve("models.zip");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("RegionA/RegionA_EQ_20260101T0000Z.xml"));
            out.write(thingModelAt("2026-01-01T00:00:00Z", false).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        MappingValidationSummary summary = new MappingValidator(
                mapping("EQ,shapes.ttl,Thing check\n").modelsInput(zip).timestamped(true).build()).validate();

        assertEquals(1, summary.violations(), () -> "summary: " + summary);
        assertWorkbooks("timestamped-zip", summary);
    }

    @Test
    void timestampedRunComparesWithThePreviousComparisonWorkbook() throws Exception {
        // Despite the option's name, the previous file is the earlier run's validation_comparison XLSX.
        write("models/RegionA/RegionA_EQ_20260101T0000Z.xml", thingModelAt("2026-01-01T00:00:00Z", false));
        MappingValidationSummary first = new MappingValidator(mapping("EQ,shapes.ttl,Thing check\n")
                .outputDir(tempDir.resolve("first")).timestamped(true).build()).validate();
        Path previous = first.reports().stream()
                .filter(p -> p.getFileName().toString().startsWith("validation_comparison")).findFirst().orElseThrow();

        write("models/RegionA/RegionA_EQ_20260201T0000Z.xml", thingModelAt("2026-02-01T00:00:00Z", true));
        MappingValidationSummary second = new MappingValidator(mapping("EQ,shapes.ttl,Thing check\n")
                .timestamped(true).previousComparisonCsv(previous).build()).validate();

        assertEquals(1, second.conforming(), () -> "summary: " + second);
        assertEquals(1, second.violations(), () -> "summary: " + second);
        Path comparison = second.reports().stream()
                .filter(p -> p.getFileName().toString().startsWith("validation_comparison")).findFirst().orElseThrow();
        SNAPSHOTS.assertExcelEquals("timestamped-previous__validation_comparison", comparison, workbookNormalizers());
    }

    @Test
    void timestampedRunOnAnEmptyFolderValidatesNothing() throws Exception {
        MappingValidationSummary summary = new MappingValidator(
                mapping("EQ,shapes.ttl,Thing check\n").timestamped(true).build()).validate();

        assertEquals(0, summary.totalRows(), () -> "summary: " + summary);
        assertWorkbooks("timestamped-empty", summary);
    }
}
