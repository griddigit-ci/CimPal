package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
}
