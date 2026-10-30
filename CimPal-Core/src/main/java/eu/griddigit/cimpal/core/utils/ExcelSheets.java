/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;

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
    /** A number written the way it would be read back: no leading zeros, no trailing fraction zeros, no exponent. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d{0,14}[1-9])?");

    private ExcelSheets() {
    }

    /**
     * Adds a sheet for {@code rows} under {@code headers}. A row shorter than the headers leaves the
     * remaining cells empty; a null value is an empty cell.
     */
    public static Sheet write(Workbook workbook, String wantedName, List<String> headers, List<? extends List<?>> rows) {
        Sheet sheet = workbook.createSheet(uniqueName(workbook, wantedName));
        CellStyle header = dataStyle(workbook);
        Font bold = workbook.createFont();
        bold.setBold(true);
        header.setFont(bold);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setFillForegroundColor(IndexedColors.SKY_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle data = dataStyle(workbook);
        CellStyle quoted = dataStyle(workbook);
        quoted.setQuotePrefixed(true);

        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(ExcelTools.cellText(headers.get(c)));
            cell.setCellStyle(header);
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
        for (int c = 0; c < headers.size(); c++) {
            sheet.autoSizeColumn(c);
            sheet.setColumnWidth(c, Math.min(sheet.getColumnWidth(c), MAX_COLUMN_WIDTH));
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
