/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes a table as a new, styled sheet: a bold header row (frozen, with an auto-filter), then one
 * row per data row. A {@link Number} is a number cell; any other value is text, cut to what a cell
 * holds ({@link ExcelTools#cellText}) and stored quote-prefixed when a spreadsheet would read it as a
 * formula (it starts with =, +, -, @, tab or CR). The sheet name is made one Excel accepts and that
 * the workbook doesn't have yet. Used by the SHACL export and Main's {@code exportMapToExcel}.
 */
public final class ExcelSheets {

    private static final String FORMULA_TRIGGERS = "=+-@\t\r";
    private static final int MAX_SHEET_NAME = 31;
    private static final int MAX_COLUMN_WIDTH = 120 * 256;
    private static final int AUTO_SIZE_MAX_ROWS = 10_000;
    /** A number written the way it would be read back: no leading zeros, no trailing fraction zeros, no exponent. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d{0,14}[1-9])?");

    private ExcelSheets() {
    }

    /**
     * Adds a sheet for {@code rows} under {@code headers}. A row shorter than the headers leaves the
     * remaining cells empty; a null value is an empty cell. Header text is protected like data text.
     *
     * @throws IllegalArgumentException when the table has more rows or columns than a sheet holds
     */
    public static Sheet write(Workbook workbook, String wantedName, List<String> headers, List<? extends List<?>> rows) {
        SpreadsheetVersion limits = workbook.getSpreadsheetVersion();
        if (rows.size() + 1 > limits.getMaxRows() || headers.size() > limits.getMaxColumns()) {
            throw new IllegalArgumentException("Too large for one sheet (" + wantedName + "): " + rows.size() + " rows, "
                    + headers.size() + " columns; at most " + (limits.getMaxRows() - 1) + " and " + limits.getMaxColumns());
        }
        Sheet sheet = workbook.createSheet(uniqueName(workbook, wantedName));
        CellStyle header = dataStyle(workbook);
        Font bold = workbook.createFont();
        bold.setBold(true);
        header.setFont(bold);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setFillForegroundColor(IndexedColors.SKY_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle quotedHeader = workbook.createCellStyle();
        quotedHeader.cloneStyleFrom(header);
        quotedHeader.setQuotePrefixed(true);
        CellStyle data = dataStyle(workbook);
        CellStyle quoted = dataStyle(workbook);
        quoted.setQuotePrefixed(true);

        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            String s = ExcelTools.cellText(headers.get(c));
            cell.setCellValue(s);
            cell.setCellStyle(!s.isEmpty() && FORMULA_TRIGGERS.indexOf(s.charAt(0)) >= 0 ? quotedHeader : header);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            List<?> values = rows.get(r);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = row.createCell(c);
                cell.setCellStyle(data);
                Object value = c < values.size() ? values.get(c) : null;
                if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    String s = ExcelTools.cellText(value == null ? "" : value.toString());
                    cell.setCellValue(s);
                    if (!s.isEmpty() && FORMULA_TRIGGERS.indexOf(s.charAt(0)) >= 0) cell.setCellStyle(quoted);
                }
            }
        }
        // auto-sizing measures every cell; above AUTO_SIZE_MAX_ROWS it costs more than it is worth
        for (int c = 0; c < headers.size(); c++) {
            if (rows.size() <= AUTO_SIZE_MAX_ROWS) {
                sheet.autoSizeColumn(c);
                sheet.setColumnWidth(c, Math.min(sheet.getColumnWidth(c), MAX_COLUMN_WIDTH));
            } else {
                sheet.setColumnWidth(c, 30 * 256);
            }
        }
        sheet.createFreezePane(0, 1);
        if (!headers.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.size() - 1));
        }
        return sheet;
    }

    /**
     * {@code wanted} as a sheet name Excel accepts: characters it refuses replaced by '_', at most 31
     * characters, and a "~2", "~3"… suffix when the workbook already has it (names compare ignoring case).
     */
    public static String uniqueName(Workbook workbook, String wanted) {
        String base = WorkbookUtil.createSafeSheetName(wanted == null || wanted.isBlank() ? "Sheet" : wanted, '_');
        String name = base;
        for (int n = 2; workbook.getSheet(name) != null; n++) {
            String suffix = "~" + n;
            name = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME - suffix.length())) + suffix;
        }
        return name;
    }

    /**
     * {@code text} as a {@link Double} when it is a plain number that reads back the same ("1", "-12",
     * "2.5"), else the text itself ("007", "1E3", "NaN", "1.50" stay text).
     */
    public static Object numberOrText(String text) {
        return text != null && PLAIN_NUMBER.matcher(text).matches() ? Double.valueOf(text) : text;
    }

    /**
     * Writes the workbook to {@code file} and closes it. Parent folders are created; the file is
     * written to a temporary sibling first and moved into place, so a failure leaves no partial file.
     */
    public static void save(Workbook workbook, Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".cimpal-", ".xlsx");
        try (workbook) {
            try (OutputStream out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static CellStyle dataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }
}
