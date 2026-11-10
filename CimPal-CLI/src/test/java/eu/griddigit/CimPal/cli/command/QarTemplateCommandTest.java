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

/** {@code qar-template}: the workbook it writes and exit code 2. */
class QarTemplateCommandTest {

    private static final String MODEL = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#">
              <md:FullModel rdf:about="urn:uuid:%s">
                <md:Model.modelingAuthoritySet>http://www.ree.es/OperationalPlanning</md:Model.modelingAuthoritySet>
              </md:FullModel>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    @Test
    void qarTemplateHasARowPerModel() throws Exception {
        Path a = Files.writeString(tempDir.resolve("REE_EQ.xml"), MODEL.formatted("1"));
        Path b = Files.writeString(tempDir.resolve("REE_SSH.xml"), MODEL.formatted("2"));
        Path out = tempDir.resolve("QAR_template.xlsx");

        CliRun.Result run = CliRun.run("qar-template", "--models", a + "," + b, "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (InputStream in = Files.newInputStream(out); Workbook book = WorkbookFactory.create(in)) {
            Sheet sheet = book.getSheet("FileData");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("REE_EQ.xml");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("urn:uuid:1");
        }
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("REE_EQ.xml"), MODEL.formatted("1"));
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"models\": [\"REE_EQ.xml\"], \"output\": \"q.xlsx\"}");

        assertThat(CliRun.run("qar-template", "--config", config.toString()).exitCode()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("q.xlsx")).exists();
    }

    @Test
    void dryRunPrintsTheConfigurationAndWritesNothing() {
        Path out = tempDir.resolve("q.xlsx");

        CliRun.Result run = CliRun.run("qar-template", "--models", "a.xml", "--output", out.toString(), "--dry-run");

        assertThat(run.exitCode()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("Dry Run", "a.xml");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputUnderAFileIsAnInternalError() throws Exception {
        Path model = Files.writeString(tempDir.resolve("REE_EQ.xml"), MODEL.formatted("1"));
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");

        CliRun.Result run = CliRun.run("qar-template", "--models", model.toString(), "--output", blocker.resolve("q.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }

    @Test
    void qarTemplateOfAMissingModelIsBadInput() {
        CliRun.Result run = CliRun.run("qar-template", "--models", tempDir.resolve("none.xml").toString(),
                "--output", tempDir.resolve("o.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
    }
}
