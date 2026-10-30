/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code shacl2xlsx}: both layouts, config, and exit codes 2 and 3. */
class ShaclToXlsxCommandTest {

    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix eq: <http://example.org/eq/constraints#> .
            eq:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ; sh:property eq:Line.r-cardinality .
            eq:Line.r-cardinality a sh:PropertyShape ; sh:name "Line.r-cardinality" ; sh:path cim:ACLineSegment.r ;
                sh:minCount 1 ; sh:severity sh:Violation ; sh:message "Missing r" .
            """;

    @TempDir
    Path tempDir;

    private Path shapes(String name) throws Exception {
        return Files.writeString(tempDir.resolve(name), SHAPES);
    }

    private static Workbook read(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return WorkbookFactory.create(in);
        }
    }

    private static String cell(Sheet sheet, int row, int column) {
        return new DataFormatter().formatCellValue(sheet.getRow(row).getCell(column));
    }

    @Test
    void summaryIsTheDefaultLayout() throws Exception {
        Path out = tempDir.resolve("reports/shapes.xlsx");

        CliRun.Result run = CliRun.run("shacl2xlsx", "--shacl-files", shapes("EQ.ttl").toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("[OK]", "summary");
        try (Workbook book = read(out)) {
            Sheet sheet = book.getSheet("SHACL Constraints info");
            assertThat(cell(sheet, 0, 0)).isEqualTo("PropertyShape/NodeShape ID");
            assertThat(cell(sheet, 1, 0)).isEqualTo("eq:Line.r-cardinality");
            assertThat(cell(sheet, 1, 2)).as("no sh:group: an empty cell, not a crash").isEmpty();
        }
    }

    @Test
    void byTypeOfSeveralFilesNamesEachRowsFile() throws Exception {
        Path out = tempDir.resolve("by-type.xlsx");

        CliRun.Result run = CliRun.run("shacl2xlsx", "--shacl-files", shapes("EQ.ttl") + "," + shapes("SSH.ttl"),
                "--output", out.toString(), "--layout", "by-type");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (Workbook book = read(out)) {
            Sheet shapes = book.getSheet("PropertyShapes");
            assertThat(cell(shapes, 0, 0)).isEqualTo("Source file");
            assertThat(shapes.getLastRowNum()).isEqualTo(2);
            assertThat(cell(shapes, 1, 0) + "," + cell(shapes, 2, 0)).isEqualTo("EQ,SSH");
        }
    }

    @Test
    void configFileSuppliesTheKeysRelativeToItsFolder() throws Exception {
        shapes("EQ.ttl");
        Path config = Files.writeString(tempDir.resolve("shacl2xlsx.json"), """
                {"_comment": "ignored", "shaclFiles": ["EQ.ttl"], "output": "out/eq.xlsx", "layout": "by-type"}
                """);

        CliRun.Result run = CliRun.run("shacl2xlsx", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (Workbook book = read(tempDir.resolve("out/eq.xlsx"))) {
            assertThat(book.getSheet("NodeShapes")).isNotNull();
        }
    }

    @Test
    void aFileThatDoesNotParseIsBadInputAndWritesNothing() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("broken.ttl"), "this is not turtle .");
        Path out = tempDir.resolve("out.xlsx");

        CliRun.Result run = CliRun.run("shacl2xlsx", "--shacl-files", broken.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("broken.ttl");
        assertThat(out).doesNotExist();
    }

    @Test
    void aJsonLdFileIsBadInput() throws Exception {
        Path jsonld = Files.writeString(tempDir.resolve("shapes.jsonld"), "{\"@id\": \"http://example.org/s\"}");
        Path out = tempDir.resolve("out.xlsx");

        CliRun.Result run = CliRun.run("shacl2xlsx", "--shacl-files", jsonld.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("JSON-LD");
        assertThat(out).doesNotExist();
    }

    @Test
    void aMissingFileAnUnknownLayoutAndANonXlsxOutputAreBadInput() throws Exception {
        assertThat(CliRun.run("shacl2xlsx", "--shacl-files", tempDir.resolve("missing.ttl").toString(),
                "--output", tempDir.resolve("o.xlsx").toString()).exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(CliRun.run("shacl2xlsx", "--shacl-files", shapes("EQ.ttl").toString(),
                "--output", tempDir.resolve("o.xlsx").toString(), "--layout", "wide").exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(CliRun.run("shacl2xlsx", "--shacl-files", shapes("EQ.ttl").toString(),
                "--output", tempDir.resolve("o.csv").toString()).exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void anOutputUnderAFileIsAnInternalError() throws Exception {
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = CliRun.run("shacl2xlsx", "--shacl-files", shapes("EQ.ttl").toString(),
                "--output", blocker.resolve("out.xlsx").toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }
}
