/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-model validation report the SHACL rule test writes beside each model. */
class ExcelToolsTest {

    @TempDir
    Path tempDir;

    private static SHACLValidationResult result(String value) {
        return new SHACLValidationResult("ex:Shape", "ex:focus", "sh:Violation", "Too long", value, "Literal",
                "cim:IdentifiedObject.name", "sh:MaxLengthConstraintComponent", "", "", "", "Rule", "");
    }

    /**
     * Regression: a value longer than an Excel cell holds made POI throw, which left an empty
     * report file behind. A model's literal can be that long.
     */
    @Test
    void textLongerThanACellHoldsIsCutToFit() throws Exception {
        String value = "x".repeat(40_000);

        ExcelTools.exportSHACLValidationToExcel(List.of(result(value)), tempDir.toFile(), "model_report.xlsx");

        try (InputStream in = Files.newInputStream(tempDir.resolve("model_report.xlsx"));
             Workbook book = WorkbookFactory.create(in)) {
            Row row = book.getSheetAt(0).getRow(1);
            String written = row.getCell(4).getStringCellValue();
            assertEquals(ExcelTools.MAX_CELL_TEXT, written.length());
            assertTrue(written.endsWith("…"));
            assertEquals("ex:focus", row.getCell(0).getStringCellValue());
        }
    }

    /** Regression: a report that could not be written was only printed to stderr. */
    @Test
    void missingFolderIsAnError() {
        assertThrows(FileNotFoundException.class, () -> ExcelTools.exportSHACLValidationToExcel(
                List.of(result("v")), tempDir.resolve("missing").toFile(), "model_report.xlsx"));
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        // U+1F600 is two chars; an odd prefix puts a pair across the limit.
        String text = "a" + "😀".repeat(20_000);

        String cut = ExcelTools.cellText(text);

        assertTrue(cut.length() <= ExcelTools.MAX_CELL_TEXT);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "a lone high surrogate before the ellipsis");
        assertEquals("", ExcelTools.cellText(null));
        assertEquals("short", ExcelTools.cellText("short"));
    }
}
