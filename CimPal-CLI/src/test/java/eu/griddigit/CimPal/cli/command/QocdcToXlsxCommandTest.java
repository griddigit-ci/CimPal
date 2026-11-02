/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code qocdc2xlsx}: the workbook it writes, config, and exit code 2. */
class QocdcToXlsxCommandTest {

    private static final String RULES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="#Rule_A">
                <rdf:type rdf:resource="#UMLRestrictionRule"/>
                <UMLRestrictionRule.name xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">ACLineSegment:A</UMLRestrictionRule.name>
                <UMLRestrictionRule.message xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">=A failed</UMLRestrictionRule.message>
              </rdf:Description>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    @Test
    void eachRuleIsARowAndFormulaLikeTextStaysText() throws Exception {
        Path rules = Files.writeString(tempDir.resolve("QoCDC.xml"), RULES);
        Path out = tempDir.resolve("qocdc.xlsx");

        CliRun.Result run = CliRun.run("qocdc2xlsx", "--input", rules.toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (InputStream in = Files.newInputStream(out); Workbook book = WorkbookFactory.create(in)) {
            Sheet sheet = book.getSheet("QoCDC321");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("ACLineSegment:A");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("NA");
            assertThat(sheet.getRow(1).getCell(3).getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("QoCDC.xml"), RULES);
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"input\": \"QoCDC.xml\", \"output\": \"q.xlsx\"}");

        assertThat(CliRun.run("qocdc2xlsx", "--config", config.toString()).exitCode()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("q.xlsx")).exists();
    }

    /** Security review: a file without rules gave a header-only workbook and exit 0. */
    @Test
    void aFileWithoutRulesIsBadInputAndWritesNothing() throws Exception {
        Path other = Files.writeString(tempDir.resolve("other.xml"), RULES.replace("UMLRestrictionRule\"", "Something\""));
        Path out = tempDir.resolve("o.xlsx");

        CliRun.Result run = CliRun.run("qocdc2xlsx", "--input", other.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("UMLRestrictionRule");
        assertThat(out).doesNotExist();
    }

    @Test
    void dryRunPrintsTheConfigurationAndWritesNothing() throws Exception {
        Path out = tempDir.resolve("o.xlsx");

        CliRun.Result run = CliRun.run("qocdc2xlsx", "--input", "in.xml", "--output", out.toString(), "--dry-run");

        assertThat(run.exitCode()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("Dry Run", "in.xml");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputUnderAFileIsAnInternalError() throws Exception {
        Path rules = Files.writeString(tempDir.resolve("QoCDC.xml"), RULES);
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");

        CliRun.Result run = CliRun.run("qocdc2xlsx", "--input", rules.toString(), "--output", blocker.resolve("o.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }

    @Test
    void anUnparseableFileIsBadInput() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("broken.xml"), "<not xml");

        CliRun.Result run = CliRun.run("qocdc2xlsx", "--input", broken.toString(), "--output", tempDir.resolve("o.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("broken.xml");
    }
}
