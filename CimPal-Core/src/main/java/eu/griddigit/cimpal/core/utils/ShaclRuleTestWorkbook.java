/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.ShaclRuleTestReport;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.ModelResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Note;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Outcome;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.RuleResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Verdict;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Writes a {@link ShaclRuleTestReport} as the rule test's results workbook:
 * <ul>
 *   <li><b>Summary</b> - a row per rule: its verdict and how many of its Conform and NonConform
 *       models came out as they must;</li>
 *   <li><b>Models</b> - a row per model of each rule, with the findings of the rule and of all
 *       other shapes, so a rule's Conform and NonConform models can be compared side by side;</li>
 *   <li><b>Notes</b> - only when something in the suite was not tested, or the run has warnings;</li>
 *   <li><b>Run</b> - the inputs and the totals.</li>
 * </ul>
 * Text from the suite and the shapes goes in with {@code setCellValue(String)}, which POI writes
 * as a string cell, never as a formula, and is cut to fit when longer than a cell holds.
 */
final class ShaclRuleTestWorkbook {

    /** Widest a text column is sized to, in characters. */
    private static final int MAX_COLUMN_CHARS = 100;

    private ShaclRuleTestWorkbook() {
    }

    static void write(ShaclRuleTestReport report, Path file) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            writeSummary(workbook.createSheet("Summary"), report, styles);
            writeModels(workbook.createSheet("Models"), report, styles);
            if (!report.getNotes().isEmpty() || !report.getWarnings().isEmpty()) {
                writeNotes(workbook.createSheet("Notes"), report, styles);
            }
            writeRun(workbook.createSheet("Run"), report, styles);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
    }

    private static void writeSummary(Sheet sheet, ShaclRuleTestReport report, Styles styles) {
        String[] headers = {"Rule (sh:name)", "Result", "Conform models", "Conform passed",
                "NonConform models", "NonConform passed", "Message"};
        header(sheet, headers, styles);
        int rowNumber = 1;
        for (RuleResult rule : report.getRules()) {
            Row row = sheet.createRow(rowNumber++);
            text(row, 0, rule.rule());
            text(row, 1, rule.verdict().label()).setCellStyle(styles.of(rule.verdict()));
            number(row, 2, rule.conformModels());
            number(row, 3, rule.conformPassed());
            number(row, 4, rule.nonConformModels());
            number(row, 5, rule.nonConformPassed());
            text(row, 6, rule.message());
        }
        finish(sheet, headers.length, rowNumber - 1);
    }

    private static void writeModels(Sheet sheet, ShaclRuleTestReport report, Styles styles) {
        String[] headers = {"Rule (sh:name)", "Folder", "Model", "Findings of the rule", "Other findings",
                "Result", "Message"};
        header(sheet, headers, styles);
        int rowNumber = 1;
        for (RuleResult rule : report.getRules()) {
            for (ModelResult model : rule.models()) {
                Row row = sheet.createRow(rowNumber++);
                text(row, 0, rule.rule());
                text(row, 1, model.conform() ? ShaclRuleTester.CONFORM : ShaclRuleTester.NON_CONFORM);
                text(row, 2, model.model().getFileName().toString());
                number(row, 3, model.ruleFindings());
                number(row, 4, model.otherFindings());
                text(row, 5, model.outcome().label()).setCellStyle(styles.of(model.outcome()));
                text(row, 6, model.message());
            }
        }
        finish(sheet, headers.length, rowNumber - 1);
    }

    private static void writeNotes(Sheet sheet, ShaclRuleTestReport report, Styles styles) {
        String[] headers = {"Item", "Note"};
        header(sheet, headers, styles);
        int rowNumber = 1;
        for (String warning : report.getWarnings()) {
            Row row = sheet.createRow(rowNumber++);
            text(row, 0, "Warning");
            text(row, 1, warning);
        }
        for (Note note : report.getNotes()) {
            Row row = sheet.createRow(rowNumber++);
            text(row, 0, ShaclRuleTester.relative(report.getSuiteFolder(), note.path()));
            text(row, 1, note.message());
        }
        finish(sheet, headers.length, rowNumber - 1);
    }

    private static void writeRun(Sheet sheet, ShaclRuleTestReport report, Styles styles) {
        header(sheet, new String[]{"Setting", "Value"}, styles);
        int rowNumber = 1;
        rowNumber = setting(sheet, rowNumber, "Started",
                report.getStarted().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        rowNumber = setting(sheet, rowNumber, "Test suite", report.getSuiteFolder().toString());
        for (Path shapeFile : report.getShapeFiles()) {
            rowNumber = setting(sheet, rowNumber, "Constraint file", shapeFile.toString());
        }
        rowNumber = setting(sheet, rowNumber, "Datatype map", report.getDatatypeMap());
        rowNumber = setting(sheet, rowNumber, "Base URI", report.getXmlBase());
        rowNumber = setting(sheet, rowNumber, "Rules", report.getRules().size());
        rowNumber = setting(sheet, rowNumber, "Passed", report.count(Verdict.PASS));
        rowNumber = setting(sheet, rowNumber, "Failed", report.count(Verdict.FAIL));
        rowNumber = setting(sheet, rowNumber, "Could not be tested", report.count(Verdict.ERROR));
        rowNumber = setting(sheet, rowNumber, "Model archives", report.getModelFiles());
        setting(sheet, rowNumber, "Distinct models validated", report.getValidatedModels());
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        clampWidth(sheet, 1);
        sheet.createFreezePane(0, 1);
    }

    private static int setting(Sheet sheet, int rowNumber, String name, String value) {
        Row row = sheet.createRow(rowNumber);
        text(row, 0, name);
        text(row, 1, value);
        return rowNumber + 1;
    }

    private static int setting(Sheet sheet, int rowNumber, String name, long value) {
        Row row = sheet.createRow(rowNumber);
        text(row, 0, name);
        number(row, 1, value);
        return rowNumber + 1;
    }

    private static void header(Sheet sheet, String[] headers, Styles styles) {
        Row row = sheet.createRow(0);
        for (int column = 0; column < headers.length; column++) {
            text(row, column, headers[column]).setCellStyle(styles.header);
        }
    }

    /** Sizes the columns, keeps the header in view and filters every column. */
    private static void finish(Sheet sheet, int columns, int lastRow) {
        for (int column = 0; column < columns; column++) {
            sheet.autoSizeColumn(column);
            clampWidth(sheet, column);
        }
        sheet.createFreezePane(0, 1);
        if (lastRow > 0) {
            sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, columns - 1));
        }
    }

    private static void clampWidth(Sheet sheet, int column) {
        sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column), MAX_COLUMN_CHARS * 256));
    }

    /** A text cell; text longer than a cell holds, such as a long parse error, is cut to fit. */
    private static Cell text(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(ExcelTools.cellText(value));
        return cell;
    }

    private static void number(Row row, int column, long value) {
        row.createCell(column).setCellValue(value);
    }

    /** The header style, and a fill for each kind of result so failures stand out in a long sheet. */
    private static final class Styles {
        final CellStyle header;
        final CellStyle pass;
        final CellStyle fail;
        final CellStyle error;

        Styles(Workbook workbook) {
            header = ExcelTools.createHeaderStyle(workbook);
            pass = fill(workbook, IndexedColors.LIGHT_GREEN);
            fail = fill(workbook, IndexedColors.ROSE);
            error = fill(workbook, IndexedColors.LIGHT_ORANGE);
        }

        CellStyle of(Verdict verdict) {
            return switch (verdict) {
                case PASS -> pass;
                case FAIL -> fail;
                case ERROR -> error;
            };
        }

        CellStyle of(Outcome outcome) {
            return switch (outcome) {
                case PASS -> pass;
                case UNEXPECTED_TRIGGER, EXPECTED_TRIGGER_NOT_FOUND -> fail;
                case ERROR, NOT_TESTED -> error;
            };
        }

        private static CellStyle fill(Workbook workbook, IndexedColors color) {
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }
    }
}
