/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.ShaclRuleTestReport;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.ModelResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Outcome;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.RuleResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Verdict;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rule test's results workbook. */
class ShaclRuleTestWorkbookTest {

    @TempDir
    Path tempDir;

    /**
     * Regression: a message longer than a cell holds, such as the parse error of a model with an
     * enormous IRI, made POI throw and the whole run end without its results workbook.
     */
    @Test
    void messageLongerThanACellHoldsIsCutToFit() throws Exception {
        String longError = "Bad IRI: " + "x".repeat(40_000);
        Path model = tempDir.resolve("Rule/Conform/broken.zip");
        RuleResult rule = new RuleResult("Rule", Verdict.ERROR, "1 model(s) could not be validated.",
                List.of(new ModelResult(model, true, 0, 0, Outcome.ERROR, longError)));
        ShaclRuleTestReport report = new ShaclRuleTestReport(tempDir, List.of(tempDir.resolve("shapes.ttl")), "None",
                "http://example.com/data", LocalDateTime.of(2026, 1, 1, 0, 0), List.of(rule), List.of(), List.of(), 1, 1, null);
        Path workbook = tempDir.resolve("results.xlsx");

        ShaclRuleTestWorkbook.write(report, workbook);

        try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
            String message = book.getSheet("Models").getRow(1).getCell(6).getStringCellValue();
            assertEquals(ExcelTools.MAX_CELL_TEXT, message.length());
            assertTrue(message.startsWith("Bad IRI: xxx"));
        }
    }
}
