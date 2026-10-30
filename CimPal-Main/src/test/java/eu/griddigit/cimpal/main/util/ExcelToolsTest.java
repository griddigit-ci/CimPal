/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExcelTools#exportMapToExcel}, the sheet writer of the instance data template and RDFS info
 * exports: each list's first element is its column header, the order list sets the column order.
 */
class ExcelToolsTest {

    @TempDir
    Path tempDir;

    /** One column per key; the first element of each list is the header. */
    private static Map<String, List<String>> table(String... columns) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String column : columns) {
            String[] cells = column.split("\\|", -1);
            map.put(cells[0], new ArrayList<>(List.of(cells)));
        }
        return map;
    }

    private Workbook roundTrip(XSSFWorkbook workbook) throws Exception {
        Path file = tempDir.resolve("out-" + System.nanoTime() + ".xlsx");
        try (workbook; OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return WorkbookFactory.create(in);
        }
    }

    private static String text(Sheet sheet, int row, int column) {
        Cell cell = sheet.getRow(row).getCell(column);
        return cell == null ? "" : new DataFormatter().formatCellValue(cell);
    }

    @Test
    void columnsFollowTheOrderListWithTheirFirstElementAsHeader() throws Exception {
        Map<String, List<String>> map = table("Class|cim:ACLineSegment|cim:Terminal", "Type|Attribute|Association");

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("Template", map, List.of("Type", "Class"), new XSSFWorkbook()))) {
            Sheet sheet = book.getSheet("Template");
            assertThat(text(sheet, 0, 0)).isEqualTo("Type");
            assertThat(text(sheet, 0, 1)).isEqualTo("Class");
            assertThat(text(sheet, 2, 0)).isEqualTo("Association");
            assertThat(text(sheet, 2, 1)).isEqualTo("cim:Terminal");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }

    /** Regression: every value that parsed as a number became the text "1.0". */
    @Test
    void aWholeNumberIsANumberCell() throws Exception {
        Map<String, List<String>> map = table("Count|1|-12|2.5");

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("S", map, List.of("Count"), new XSSFWorkbook()))) {
            Sheet sheet = book.getSheet("S");
            Cell one = sheet.getRow(1).getCell(0);
            assertThat(one.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(text(sheet, 1, 0)).isEqualTo("1");
            assertThat(text(sheet, 2, 0)).isEqualTo("-12");
            assertThat(text(sheet, 3, 0)).isEqualTo("2.5");
        }
    }

    /** Regression: text that only looks like a number ("007", "1E3", "NaN") lost its form ("7.0", "1000.0", "NaN"). */
    @Test
    void textThatLooksLikeANumberStaysText() throws Exception {
        Map<String, List<String>> map = table("Code|007|1E3|NaN|1.50");

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("S", map, List.of("Code"), new XSSFWorkbook()))) {
            Sheet sheet = book.getSheet("S");
            assertThat(List.of(text(sheet, 1, 0), text(sheet, 2, 0), text(sheet, 3, 0), text(sheet, 4, 0)))
                    .containsExactly("007", "1E3", "NaN", "1.50");
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
        }
    }

    /** Regression: a second export under the same sheet name wrote over the first one's rows. */
    @Test
    void aSheetNameThatIsTakenGetsASuffixInsteadOfOverwriting() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        ExcelTools.exportMapToExcel("Template", table("A|first|kept"), List.of("A"), workbook);
        ExcelTools.exportMapToExcel("Template", table("B|second"), List.of("B"), workbook);

        try (Workbook book = roundTrip(workbook)) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(text(book.getSheet("Template"), 2, 0)).isEqualTo("kept");
            assertThat(text(book.getSheetAt(1), 1, 0)).isEqualTo("second");
        }
    }

    /** Regression: a name over 31 characters, or with [ ] * ? / \ :, threw IllegalArgumentException. */
    @Test
    void anyNameGivesASheetExcelAccepts() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        ExcelTools.exportMapToExcel("IEC61970-600-2_CGMES_3_0_0_EQ_v1 [draft]", table("A|x"), List.of("A"), workbook);
        ExcelTools.exportMapToExcel("IEC61970-600-2_CGMES_3_0_0_EQ_v2 [draft]", table("A|y"), List.of("A"), workbook);

        try (Workbook book = roundTrip(workbook)) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(book.getSheetName(0)).hasSizeLessThanOrEqualTo(31).doesNotContain("[");
            assertThat(book.getSheetName(1)).hasSizeLessThanOrEqualTo(31).isNotEqualTo(book.getSheetName(0));
        }
    }

    /** Text a spreadsheet would read as a formula is stored quote-prefixed (security rule: reports). */
    @Test
    void formulaLikeTextIsQuotePrefixed() throws Exception {
        Map<String, List<String>> map = table("Note|=HYPERLINK(\"http://x\")|+1|@SUM(1)");

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("S", map, List.of("Note"), new XSSFWorkbook()))) {
            Sheet sheet = book.getSheet("S");
            for (int r = 1; r <= 3; r++) {
                Cell cell = sheet.getRow(r).getCell(0);
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell.getCellStyle().getQuotePrefixed()).as("row " + r).isTrue();
            }
            assertThat(text(sheet, 1, 0)).isEqualTo("=HYPERLINK(\"http://x\")");
        }
    }

    /** Regression: text longer than an Excel cell holds made POI throw. */
    @Test
    void textLongerThanACellHoldsIsCutToFit() throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Description", new ArrayList<>(List.of("Description", "x".repeat(40_000))));

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("S", map, List.of("Description"), new XSSFWorkbook()))) {
            assertThat(text(book.getSheet("S"), 1, 0)).hasSize(32_767).endsWith("…");
        }
    }

    /** Regression: a column shorter than the first one threw IndexOutOfBoundsException. */
    @Test
    void aShorterColumnLeavesEmptyCells() throws Exception {
        Map<String, List<String>> map = table("Class|a|b|c", "Type|t");

        try (Workbook book = roundTrip(ExcelTools.exportMapToExcel("S", map, List.of("Class", "Type"), new XSSFWorkbook()))) {
            Sheet sheet = book.getSheet("S");
            assertThat(text(sheet, 1, 1)).isEqualTo("t");
            assertThat(text(sheet, 3, 1)).isEmpty();
            assertThat(text(sheet, 3, 0)).isEqualTo("c");
        }
    }
}
