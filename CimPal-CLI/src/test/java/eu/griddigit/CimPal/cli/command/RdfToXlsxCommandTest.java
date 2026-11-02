/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code rdf2xlsx}: one sheet per type, config, and exit code 2. */
class RdfToXlsxCommandTest {

    private static final String DATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#">
              <cim:ACLineSegment rdf:about="http://example.org/d#_L1">
                <cim:IdentifiedObject.name>Line 1</cim:IdentifiedObject.name>
              </cim:ACLineSegment>
              <cim:Terminal rdf:about="http://example.org/d#_T1">
                <cim:Terminal.ConductingEquipment rdf:resource="http://example.org/d#_L1"/>
              </cim:Terminal>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    @Test
    void writesOneSheetPerType() throws Exception {
        Path xml = Files.writeString(tempDir.resolve("EQ.xml"), DATA);
        Path out = tempDir.resolve("eq.xlsx");

        CliRun.Result run = CliRun.run("rdf2xlsx", "--input", xml.toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (InputStream in = Files.newInputStream(out); Workbook book = WorkbookFactory.create(in)) {
            assertThat(book.getSheet("ACLineSegment").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Line 1");
            assertThat(book.getSheet("Terminal")).isNotNull();
        }
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("EQ.xml"), DATA);
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"input\": \"EQ.xml\", \"output\": \"x/eq.xlsx\"}");

        CliRun.Result run = CliRun.run("rdf2xlsx", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("x/eq.xlsx")).exists();
    }

    /** Security review: a file without typed subjects gave a workbook without sheets (a corrupt file) and exit 0. */
    @Test
    void aFileWithoutTypedSubjectsIsBadInputAndWritesNothing() throws Exception {
        Path ttl = Files.writeString(tempDir.resolve("untyped.ttl"), "<http://x#a> <http://x#p> 1 .");
        Path out = tempDir.resolve("o.xlsx");

        CliRun.Result run = CliRun.run("rdf2xlsx", "--input", ttl.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(out).doesNotExist();
    }

    /** Security review: quad formats lost their named graphs silently; relative IRIs leaked the local path. */
    @Test
    void quadFormatsAreRefusedAndRelativeIrisDoNotLeakTheLocalPath() throws Exception {
        Path trig = Files.writeString(tempDir.resolve("d.trig"), "<http://x#g> { <http://x#a> a <http://x#T> . }");
        assertThat(CliRun.run("rdf2xlsx", "--input", trig.toString(), "--output", tempDir.resolve("t.xlsx").toString())
                .exitCode()).isEqualTo(ExitCode.INVALID_INPUT);

        Path relative = Files.writeString(tempDir.resolve("rel.ttl"), "<#a> a <#Thing> ; <#name> \"x\" .");
        Path out = tempDir.resolve("rel.xlsx");
        CliRun.Result run = CliRun.run("rdf2xlsx", "--input", relative.toString(), "--output", out.toString());
        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        try (InputStream in = Files.newInputStream(out); Workbook book = WorkbookFactory.create(in)) {
            String header = book.getSheetAt(0).getRow(0).getCell(1).getStringCellValue();
            assertThat(header).doesNotContain("file:").doesNotContain(tempDir.getFileName().toString());
        }
    }

    /** Security review: a parse error quoting the input reached stderr with its control characters. */
    @Test
    void parseErrorsOnStderrAreNeutralised() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("b.ttl"), "<http://x#a> <http://x#p> \"x\r\n[OK] fake\u001B[2J");

        CliRun.Result run = CliRun.run("rdf2xlsx", "--input", broken.toString(), "--output", tempDir.resolve("o.xlsx").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).doesNotContain("\u001B");
    }

    @Test
    void aMissingOrJsonLdInputIsBadInput() throws Exception {
        Path out = tempDir.resolve("o.xlsx");
        Path jsonld = Files.writeString(tempDir.resolve("d.jsonld"), "{\"@id\": \"http://example.org/x\"}");

        assertThat(CliRun.run("rdf2xlsx", "--input", tempDir.resolve("missing.xml").toString(), "--output", out.toString())
                .exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        CliRun.Result run = CliRun.run("rdf2xlsx", "--input", jsonld.toString(), "--output", out.toString());
        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("JSON-LD");
        assertThat(out).doesNotExist();
    }
}
