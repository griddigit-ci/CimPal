/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidationExcelWriterStreamingTest {
    @TempDir
    Path tempDir;

    @Test
    void savesRawValidationRowsAfterTheStreamingWindowHasFlushed() throws Exception {
        SHACLValidationResult result = new SHACLValidationResult(
                "urn:test:shape", "urn:test:focus", "Violation", "message", "value", "urn:test:path",
                "urn:test:component", "", "", "", "", "");
        Path report;

        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            for (int i = 0; i < 250; i++) {
                writer.appendValidation(
                        ValidationExcelWriter.CaseFolder.UNKNOWN,
                        "dataset-" + i,
                        "input.xml",
                        "constraints.ttl",
                        List.of(result),
                        false
                );
            }
            report = writer.saveTo(tempDir);
        }

        try (var input = Files.newInputStream(report);
             var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheet("Validation results");
            assertEquals(250, sheet.getLastRowNum());
            assertEquals("dataset-0", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("dataset-249", sheet.getRow(250).getCell(0).getStringCellValue());
        }
    }

    /**
     * Regenerating the comparison read whole reports with {@code WorkbookFactory}, which refuses any
     * part over 100 MB, so a large Validation results sheet broke it. The limit is lowered to 1 MB
     * here to keep the file small; only the statistics sheet may be read.
     */
    @Test
    void readsTheStatisticsOfAReportWithAValidationResultsSheetOverPoisMemoryLimit() throws Exception {
        Path report = tempDir.resolve("validation_report_RegionA.xlsx");
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            java.util.Random random = new java.util.Random(42);
            Sheet results = wb.createSheet("Validation results");
            for (int r = 0; r < 20_000; r++) {
                Row row = results.createRow(r);
                for (int c = 0; c < 5; c++) {
                    row.createCell(c).setCellValue(Long.toHexString(random.nextLong()));
                }
            }
            Sheet stats = wb.createSheet("Validation statistics");
            stats.createRow(0).createCell(0).setCellValue("Dataset");
            Row named = stats.createRow(1);
            named.createCell(0).setCellValue("EQ");
            named.createCell(4).setCellValue(2);
            named.createCell(5).setCellValue(1);
            named.createCell(6).setCellValue(3);
            named.createCell(10).setCellValue("Equipment");
            Row unnamed = stats.createRow(3);
            unnamed.createCell(0).setCellValue("SSH");
            unnamed.createCell(6).setCellValue(7);
            try (var out = Files.newOutputStream(report)) {
                wb.write(out);
            }
        }

        // Exercise the size cap, not the zip-bomb ratio check (which, on a stream, fires first on this
        // generated file). The old whole-workbook read fails here on the capped sheet part.
        double minInflateRatio = ZipSecureFile.getMinInflateRatio();
        ZipSecureFile.setMinInflateRatio(0);
        IOUtils.setByteArrayMaxOverride(1_000_000);
        try {
            assertEquals(List.of(
                            new ValidationExcelWriter.StatisticsRow("Equipment", 2, 1, 3),
                            new ValidationExcelWriter.StatisticsRow("SSH", 0, 0, 7)),
                    ValidationExcelWriter.readStatisticsSheet(report));
            assertEquals(List.of(), ValidationExcelWriter.readTimestampOverview(report));
        } finally {
            IOUtils.setByteArrayMaxOverride(-1);
            ZipSecureFile.setMinInflateRatio(minInflateRatio);
        }
    }
}
