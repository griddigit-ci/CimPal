/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.models.SHACLValidationReport.ConstraintFileResults;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Conforms column of the workbook rows a SHACLValidationReport writes per constraint file. */
class SHACLValidationReportTest {

    private static final SHACLValidationResult VIOLATION = new SHACLValidationResult(
            "urn:test:S", "urn:test:_1", "sh:Violation", "minCount", "", "urn:test:size",
            "sh:MinCountConstraintComponent", "", "", "", "", "");

    @TempDir
    Path tempDir;

    private static SHACLValidationReport report(boolean conforms, boolean partial, List<SHACLValidationResult> results,
                                                List<ConstraintFileResults> byFile) {
        return new SHACLValidationReport(conforms, partial, results, null, List.of(), "data.xml", "data.xml",
                "a.ttl; b.ttl", 0, byFile);
    }

    /** The "Conforms" cell of each row of the "Validation statistics" sheet. */
    private List<String> conformsColumn(SHACLValidationReport report) throws Exception {
        Path workbook = tempDir.resolve("report-" + System.nanoTime() + ".xlsx");
        report.writeExcel(workbook);
        DataFormatter formatter = new DataFormatter();
        List<String> cells = new ArrayList<>();
        try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
            Sheet sheet = book.getSheet("Validation statistics");
            for (Row row : sheet) {
                if (row.getRowNum() > 0) {
                    cells.add(formatter.formatCellValue(row.getCell(7)));
                }
            }
        }
        return cells;
    }

    @Test
    void nonConformingRunWithoutExtractedResultsShowsNoFileAsConforming() throws Exception {
        // A Python engine's report can say "does not conform" while no result was extracted from it.
        // Which file failed is then unknown, so none may be shown as conforming.
        List<ConstraintFileResults> twoFilesWithoutResults = List.of(
                new ConstraintFileResults("a.ttl", List.of()), new ConstraintFileResults("b.ttl", List.of()));

        assertEquals(List.of("FALSE", "FALSE"), conformsColumn(report(false, false, List.of(), twoFilesWithoutResults)));
        assertEquals(List.of("FALSE"), conformsColumn(report(false, false, List.of(), List.of())));
    }

    @Test
    void fileWithoutResultsConformsWhenTheFindingsBelongToAnother() throws Exception {
        List<ConstraintFileResults> byFile = List.of(
                new ConstraintFileResults("a.ttl", List.of(VIOLATION)), new ConstraintFileResults("b.ttl", List.of()));

        assertEquals(List.of("FALSE", "TRUE"), conformsColumn(report(false, false, List.of(VIOLATION), byFile)));
    }

    @Test
    void partialRunShowsNoFileAsConforming() throws Exception {
        // Cut short, the run says nothing about the checks that did not run.
        List<ConstraintFileResults> byFile = List.of(
                new ConstraintFileResults("a.ttl", List.of(VIOLATION)), new ConstraintFileResults("b.ttl", List.of()));

        assertEquals(List.of("FALSE", "FALSE"), conformsColumn(report(false, true, List.of(VIOLATION), byFile)));
    }

    @Test
    void theTurtleReportIsWrittenBesideTheWorkbookUnderItsName() throws Exception {
        // The GUI and the CLI both write a run's reports this way.
        SHACLValidationReport report = new SHACLValidationReport(false, false, List.of(VIOLATION),
                ModelFactory.createDefaultModel(), List.of(), "data.xml", "data.xml", "a.ttl", 0);

        SHACLValidationReport.WrittenReports both = report.writeReportsTo(tempDir.resolve("out"), true);

        assertEquals(tempDir.resolve("out"), both.workbook().getParent());
        assertTrue(both.workbook().getFileName().toString().matches("validation_report__\\d{8}_\\d{6}\\.xlsx"),
                both.workbook()::toString);
        assertEquals(both.workbook().resolveSibling(both.workbook().getFileName().toString().replace(".xlsx", ".ttl")),
                both.turtle());
        assertTrue(Files.isRegularFile(both.turtle()));
        assertNull(report.writeReportsTo(tempDir.resolve("workbook-only"), false).turtle());
    }

    @Test
    void conformingRunShowsEveryFileAsConforming() throws Exception {
        List<ConstraintFileResults> byFile = List.of(
                new ConstraintFileResults("a.ttl", List.of()), new ConstraintFileResults("b.ttl", List.of()));

        assertEquals(List.of("TRUE", "TRUE"), conformsColumn(report(true, false, List.of(), byFile)));
    }
}
