/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code rdfs-describe}: the workbook, config, and exit codes 2 and 3. */
class RdfsDescribeCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void writesOneRowPerItem() throws Exception {
        Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);
        Path out = tempDir.resolve("out/describe.xlsx");

        CliRun.Result run = CliRun.run("rdfs-describe", "--rdfs-file", rdfs.toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (InputStream in = Files.newInputStream(out); Workbook book = WorkbookFactory.create(in)) {
            Sheet sheet = book.getSheet("RDFS descriptions");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Item");
            assertThat(sheet.getLastRowNum()).isGreaterThan(10);
        }
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);
        Path config = Files.writeString(tempDir.resolve("c.json"),
                "{\"rdfsFile\": \"mini-equipment-2020-cim100.rdf\", \"output\": \"d.xlsx\"}");

        CliRun.Result run = CliRun.run("rdfs-describe", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("d.xlsx")).exists();
    }

    @Test
    void anUnparseableFileIsBadInputAndWritesNothing() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("broken.rdf"), "<not rdf");
        Path out = tempDir.resolve("o.xlsx");

        CliRun.Result run = CliRun.run("rdfs-describe", "--rdfs-file", broken.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("broken.rdf");
        assertThat(out).doesNotExist();
    }

    @Test
    void aNonXlsxOutputIsBadInput() throws Exception {
        Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);

        CliRun.Result run = CliRun.run("rdfs-describe", "--rdfs-file", rdfs.toString(), "--output", tempDir.resolve("o.csv").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void anOutputUnderAFileIsAnInternalError() throws Exception {
        Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");

        CliRun.Result run = CliRun.run("rdfs-describe", "--rdfs-file", rdfs.toString(), "--output", blocker.resolve("o.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }
}
