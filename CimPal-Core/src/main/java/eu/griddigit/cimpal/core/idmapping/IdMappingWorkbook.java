/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.IdMappingResult.Statistic;
import eu.griddigit.cimpal.core.idmapping.IdMappingResult.TypeCount;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes an {@link IdMappingResult} as a workbook. Text that a spreadsheet would read as a formula
 * (starting with =, +, -, @, tab or CR) is stored quote-prefixed, so it shows and stays as text.
 */
final class IdMappingWorkbook {

    static final List<String> MATCHED_HEADER = List.of("Model1_ID", "Element_type", "Model2_ID", "Model2_2nd_name",
            "Confidence", "Model1_name", "Model2_type", "Method", "Note");
    static final List<String> UNMATCHED_HEADER = List.of("Side", "ID", "Element_type", "Name", "Substation",
            "Reason", "Candidates");

    private static final String FORMULA_TRIGGERS = "=+-@\t\r";
    private static final int MAX_CELL_TEXT = 32_767;

    private final Workbook workbook;
    private final CellStyle header;
    private final CellStyle quoted;

    private IdMappingWorkbook(Workbook workbook) {
        this.workbook = workbook;
        this.header = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        header.setFont(bold);
        header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        this.quoted = workbook.createCellStyle();
        quoted.setQuotePrefixed(true);
    }

    static void write(IdMappingResult result, Path file) throws IOException {
        boolean xls = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xls");
        int maxRows = (xls ? SpreadsheetVersion.EXCEL97 : SpreadsheetVersion.EXCEL2007).getMaxRows() - 1;
        if (result.getMatched().size() > maxRows || result.getUnmatched().size() > maxRows) {
            throw new IOException("Too many rows for " + (xls ? ".xls (at most " + maxRows + "); write .xlsx instead"
                    : "one sheet (at most " + maxRows + ")"));
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, ".id-mapping-", xls ? ".xls" : ".xlsx");
        try (Workbook wb = xls ? new HSSFWorkbook() : new SXSSFWorkbook(500)) {
            IdMappingWorkbook writer = new IdMappingWorkbook(wb);
            writer.matched(result.getMatched());
            writer.unmatched(result.getUnmatched());
            writer.summary(result);
            try (OutputStream out = Files.newOutputStream(temp)) {
                wb.write(out);
            }
            if (wb instanceof SXSSFWorkbook streaming) {
                streaming.dispose();
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void matched(List<MatchedRow> rows) {
        Sheet sheet = sheet("Matched", MATCHED_HEADER, new int[]{40, 24, 40, 32, 12, 32, 18, 16, 60});
        int r = 1;
        for (MatchedRow m : rows) {
            Row row = sheet.createRow(r++);
            text(row, 0, m.model1Id());
            text(row, 1, m.elementType());
            text(row, 2, m.model2Id());
            text(row, 3, m.model2SecondName());
            text(row, 4, m.confidence());
            text(row, 5, m.model1Name());
            text(row, 6, m.model2Type());
            text(row, 7, m.method());
            text(row, 8, m.note());
        }
        filter(sheet, r, MATCHED_HEADER.size());
    }

    private void unmatched(List<UnmatchedRow> rows) {
        Sheet sheet = sheet("Unmatched", UNMATCHED_HEADER, new int[]{8, 40, 24, 32, 28, 50, 80});
        int r = 1;
        for (UnmatchedRow u : rows) {
            Row row = sheet.createRow(r++);
            text(row, 0, u.side().label());
            text(row, 1, u.id());
            text(row, 2, u.elementType());
            text(row, 3, u.name());
            text(row, 4, u.substation());
            text(row, 5, u.reason());
            text(row, 6, u.candidates());
        }
        filter(sheet, r, UNMATCHED_HEADER.size());
    }

    private void summary(IdMappingResult result) {
        Sheet sheet = workbook.createSheet("Summary");
        int[] widths = {34, 14, 14, 14, 14, 14, 14, 14, 14, 16};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        int r = 0;
        r = headerRow(sheet, r, List.of("Overview (classes in both models)", "Objects", "Certain", "Certain %",
                "Probable", "Probable %", "Not matched", "Not matched %"));
        r = overviewRow(sheet, r, "Model 1", result.getModel1Overview());
        r = overviewRow(sheet, r, "Model 2", result.getModel2Overview());
        Row explain = sheet.createRow(r++);
        text(explain, 0, "Certain: the evidence leaves no other reading. Probable: check it (see the Note). "
                + "Not matched: listed on the Unmatched sheet.");
        r++;
        r = headerRow(sheet, r, List.of("Input", "Value"));
        for (Statistic s : result.getInputs()) {
            Row row = sheet.createRow(r++);
            text(row, 0, s.label());
            text(row, 1, s.model1());
        }
        r++;
        r = headerRow(sheet, r, List.of("Element_type", "Model 1 objects", "Certain", "Probable", "Model 1 unmatched",
                "Model 2 objects", "Model 2 certain", "Model 2 probable", "Model 2 unmatched", "In both models"));
        int[] totals = new int[8];
        for (TypeCount t : result.getTypeCounts()) {
            Row row = sheet.createRow(r++);
            text(row, 0, t.elementType());
            int[] values = {t.model1Objects(), t.certain(), t.probable(), t.model1Unmatched(), t.model2Objects(),
                    t.model2Certain(), t.model2Probable(), t.model2Unmatched()};
            for (int i = 0; i < values.length; i++) {
                row.createCell(i + 1).setCellValue(values[i]);
                totals[i] += values[i];
            }
            text(row, 9, t.inBothModels() ? "yes" : t.model1Objects() > 0 ? "model 1 only (not in Unmatched)"
                    : "model 2 only (not in Unmatched)");
        }
        Row total = sheet.createRow(r++);
        text(total, 0, "Total");
        for (int i = 0; i < totals.length; i++) {
            total.createCell(i + 1).setCellValue(totals[i]);
        }
        r++;
        r = headerRow(sheet, r, List.of("Method", "Matched"));
        for (Map.Entry<String, Integer> e : new TreeMap<>(result.getMethodCounts()).entrySet()) {
            Row row = sheet.createRow(r++);
            text(row, 0, e.getKey());
            row.createCell(1).setCellValue(e.getValue());
        }
        r++;
        r = headerRow(sheet, r, List.of("Model statistics", "Model 1", "Model 2"));
        for (Statistic s : result.getStatistics()) {
            Row row = sheet.createRow(r++);
            text(row, 0, s.label());
            text(row, 1, s.model1());
            text(row, 2, s.model2());
        }
    }

    private int overviewRow(Sheet sheet, int r, String label, IdMappingResult.Overview o) {
        Row row = sheet.createRow(r);
        text(row, 0, label);
        row.createCell(1).setCellValue(o.objects());
        int[] counts = {o.certain(), o.probable(), o.unmatched()};
        for (int i = 0; i < counts.length; i++) {
            row.createCell(2 + 2 * i).setCellValue(counts[i]);
            row.createCell(3 + 2 * i).setCellValue(Math.round(o.percent(counts[i]) * 10) / 10.0);
        }
        return r + 1;
    }

    private Sheet sheet(String name, List<String> columns, int[] widths) {
        Sheet sheet = workbook.createSheet(name);
        headerRow(sheet, 0, columns);
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        return sheet;
    }

    private int headerRow(Sheet sheet, int r, List<String> columns) {
        Row row = sheet.createRow(r);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(header);
        }
        return r + 1;
    }

    private static void filter(Sheet sheet, int rows, int columns) {
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rows - 1), 0, columns - 1));
    }

    private void text(Row row, int column, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String s = value.length() > MAX_CELL_TEXT ? value.substring(0, MAX_CELL_TEXT) : value;
        Cell cell = row.createCell(column);
        cell.setCellValue(s);
        if (FORMULA_TRIGGERS.indexOf(s.charAt(0)) >= 0) {
            cell.setCellStyle(quoted);
        }
    }
}
