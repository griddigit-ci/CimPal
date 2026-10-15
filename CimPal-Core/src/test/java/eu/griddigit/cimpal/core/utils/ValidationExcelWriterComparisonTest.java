/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Charts sheet of the {@code validation_comparison__*.xlsx} workbook: the numbers a user reads
 * (dataset totals, shares, deltas and compliance rates), checked after evaluating every formula.
 */
class ValidationExcelWriterComparisonTest {

    private static final double EPS = 1e-9;

    @TempDir
    Path tempDir;

    /** April 2060: the previous run. */
    private Path writeApril() throws Exception {
        try (var writer = new ValidationExcelWriter.ComparisonExcelWriter(null, "Previous", "April 2060")) {
            writer.addTimestampDataset("REE", "t1", "A", 1, 0, 0);
            writer.addTimestampDataset("REE", "t1", "B", 0, 5, 2);
            writer.addTimestampDataset("REE", "t1", "C", 0, 0, 0);
            writer.addTimestampDataset("REN", "t1", "A", 0, 0, 0);
            writer.addTimestampDataset("REN", "t1", "C", 0, 0, 1);
            return writer.saveTo(tempDir.resolve("april"));
        }
    }

    /** May 2060: the current run, with a dataset seen at two timestamps and a wildcard-like name. */
    private Path writeMay(Path previous) throws Exception {
        try (var writer = new ValidationExcelWriter.ComparisonExcelWriter(previous, "Previous", "May 2060")) {
            writer.addTimestampDataset("REE", "t1", "A", 1, 0, 0);
            writer.addTimestampDataset("REE", "t2", "A", 0, 0, 0);
            writer.addTimestampDataset("REE", "t1", "B", 0, 0, 0);
            writer.addTimestampDataset("REE", "t1", "C", 0, 4, 3);
            writer.addTimestampDataset("REE", "t1", "Line*", 2, 0, 0);
            writer.addTimestampDataset("REE", "t1", "LineX", 0, 0, 0);
            writer.addTimestampDataset("REN", "t1", "A", 0, 0, 0);
            writer.addTimestampDataset("REN", "t1", "B", 1, 0, 1);
            writer.addTimestampDataset("REN", "t1", "C", 0, 0, 1);
            writer.addTimestampDataset("REN", "t2", "C", 0, 0, 0);
            return writer.saveTo(tempDir.resolve("may"));
        }
    }

    @Test
    void complianceRatesCountTheDatasetsWithoutHits() throws Exception {
        Path may = writeMay(writeApril());

        try (Workbook wb = open(may)) {
            Sheet charts = wb.getSheet("Charts");
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();

            assertTable(Map.of(
                            "April 2060", List.of(100.0 / 3, 50.0, 40.0),
                            "May 2060", List.of(40.0, 100.0 / 3, 37.5)),
                    table(charts, ev, "Compliance Rate (Warnings and Violations) by Region"));
            assertTable(Map.of(
                            "April 2060", List.of(200.0 / 3, 50.0, 60.0),
                            "May 2060", List.of(80.0, 100.0 / 3, 62.5)),
                    table(charts, ev, "Violations Compliance Rate (Violations) by Region"));
            assertTable(Map.of(
                            "April 2060", List.of(200.0 / 3, 100.0, 80.0),
                            "May 2060", List.of(60.0, 200.0 / 3, 62.5)),
                    table(charts, ev, "Warnings Compliance Rate (Warnings) by Region"));
        }
    }

    @Test
    void regionBlockShowsTotalsSharesAndDelta() throws Exception {
        Path may = writeMay(writeApril());

        try (Workbook wb = open(may)) {
            Map<String, List<Double>> ree = table(wb.getSheet("Charts"),
                    wb.getCreationHelper().createFormulaEvaluator(), "REE");
            // W+V total, W+V %, W, W %, V, V %, previous W+V total, previous W+V %, delta
            assertTable(Map.of(
                    "A", List.of(1.0, 100.0 / 6, 1.0, 100.0 / 3, 0.0, 0.0, 1.0, 100.0 / 3, 0.0),
                    "B", List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 200.0 / 3, -2.0),
                    "C", List.of(3.0, 50.0, 0.0, 0.0, 3.0, 100.0, 0.0, 0.0, 3.0),
                    "Line*", List.of(2.0, 100.0 / 3, 2.0, 200.0 / 3, 0.0, 0.0, 0.0, 0.0, 2.0),
                    "LineX", List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)), ree);

            Map<String, List<Double>> combined = table(wb.getSheet("Charts"),
                    wb.getCreationHelper().createFormulaEvaluator(), "Combined (all regions)");
            // W+V total, W+V %, previous W+V total, previous W+V %, delta
            assertTable(Map.of(
                    "A", List.of(1.0, 100.0 / 9, 1.0, 25.0, 0.0),
                    "B", List.of(2.0, 200.0 / 9, 2.0, 50.0, 0.0),
                    "C", List.of(4.0, 400.0 / 9, 1.0, 25.0, 3.0),
                    "Line*", List.of(2.0, 200.0 / 9, 0.0, 0.0, 2.0),
                    "LineX", List.of(0.0, 0.0, 0.0, 0.0, 0.0)), combined);
        }
    }

    @Test
    void aThirdRunKeepsTheWholeHistory() throws Exception {
        Path may = writeMay(writeApril());
        Path june;
        try (var writer = new ValidationExcelWriter.ComparisonExcelWriter(may, "Previous", "June 2060")) {
            writer.addTimestampDataset("REE", "t1", "A", 0, 0, 0);
            june = writer.saveTo(tempDir.resolve("june"));
        }

        try (Workbook wb = open(june)) {
            Map<String, List<Double>> rates = table(wb.getSheet("Charts"),
                    wb.getCreationHelper().createFormulaEvaluator(),
                    "Compliance Rate (Warnings and Violations) by Region");
            assertEquals(List.of("April 2060", "May 2060", "June 2060"), new ArrayList<>(rates.keySet()));
            // REN has no data in June; Combined is REE alone.
            assertEquals(List.of(100.0, 0.0, 100.0), rates.get("June 2060"));
        }
    }

    private static Workbook open(Path xlsx) throws Exception {
        try (var in = Files.newInputStream(xlsx)) {
            return WorkbookFactory.create(in);
        }
    }

    private static void assertTable(Map<String, List<Double>> expected, Map<String, List<Double>> actual) {
        assertEquals(expected.keySet(), actual.keySet(), () -> "rows: " + actual);
        for (var e : expected.entrySet()) {
            List<Double> want = e.getValue();
            List<Double> got = actual.get(e.getKey());
            assertEquals(want.size(), got.size(), () -> e.getKey() + ": " + got);
            for (int i = 0; i < want.size(); i++) {
                int col = i;
                assertEquals(want.get(i), got.get(i), EPS, () -> e.getKey() + " column " + (col + 1) + ": " + got);
            }
        }
    }

    /**
     * Reads the table under the title row whose first cell is {@code title}: the header is the next
     * row starting with "Dataset" or "Run"; data rows run until the first row with an empty first cell.
     * Returns first-cell label -> evaluated numeric values of the other header columns, which must be formulas.
     */
    private static Map<String, List<Double>> table(Sheet sheet, FormulaEvaluator ev, String title) {
        int r = 0;
        while (r <= sheet.getLastRowNum() && !title.equals(text(sheet.getRow(r), 0))) {
            r++;
        }
        assertNotNull(sheet.getRow(r), () -> "no table titled " + title);
        while (!List.of("Dataset", "Run").contains(text(sheet.getRow(r), 0))) {
            r++;
        }
        Row header = sheet.getRow(r);
        int cols = header.getLastCellNum();
        Map<String, List<Double>> rows = new LinkedHashMap<>();
        for (r++; r <= sheet.getLastRowNum() && !text(sheet.getRow(r), 0).isEmpty(); r++) {
            Row row = sheet.getRow(r);
            List<Double> values = new ArrayList<>();
            for (int c = 1; c < cols; c++) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    // Users must be able to see how every number is calculated.
                    assertEquals(CellType.FORMULA, cell.getCellType(), () -> title + " " + cell.getAddress());
                }
                CellValue v = cell == null ? null : ev.evaluate(cell);
                values.add(v == null || v.getCellType() != CellType.NUMERIC ? Double.NaN : v.getNumberValue());
            }
            rows.put(text(row, 0), values);
        }
        return rows;
    }

    private static String text(Row row, int col) {
        if (row == null || row.getCell(col) == null || row.getCell(col).getCellType() != CellType.STRING) {
            return "";
        }
        return row.getCell(col).getStringCellValue();
    }
}
