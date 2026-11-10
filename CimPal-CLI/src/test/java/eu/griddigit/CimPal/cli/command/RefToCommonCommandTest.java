/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ref2common}: the CIM/XML it writes, config, and exit code 2. */
class RefToCommonCommandTest {

    private static final String REF = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#">
              <cim:BaseVoltage rdf:about="http://example.org/ref#BV400">
                <cim:IdentifiedObject.name>400 kV</cim:IdentifiedObject.name>
              </cim:BaseVoltage>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    @Test
    void writesCommonDataWithLocalIds() throws Exception {
        Path ref = Files.writeString(tempDir.resolve("ref.rdf"), REF);
        Path out = tempDir.resolve("out/common.xml");

        CliRun.Result run = CliRun.run("ref2common", "--rdf-files", ref.toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(Files.readString(out)).contains("_BV400", "400 kV").doesNotContain("delete.eu");
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("ref.rdf"), REF);
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"rdfFiles\": [\"ref.rdf\"], \"output\": \"c.xml\"}");

        assertThat(CliRun.run("ref2common", "--config", config.toString()).exitCode()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("c.xml")).exists();
    }

    /** Security review: data with the prefixes but no cim/nc typed subject gave an empty file and exit 0. */
    @Test
    void dataWithoutCimTypedSubjectsIsBadInput() throws Exception {
        Path ref = Files.writeString(tempDir.resolve("ref.rdf"), REF.replace("cim:BaseVoltage", "rdf:Description"));
        Path out = tempDir.resolve("common.xml");

        CliRun.Result run = CliRun.run("ref2common", "--rdf-files", ref.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(out).doesNotExist();
    }

    @Test
    void dryRunPrintsTheConfigurationAndWritesNothing() {
        Path out = tempDir.resolve("c.xml");

        CliRun.Result run = CliRun.run("ref2common", "--rdf-files", "a.rdf,b.rdf", "--output", out.toString(), "--dry-run");

        assertThat(run.exitCode()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("Dry Run", "a.rdf", "b.rdf");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputUnderAFileIsAnInternalError() throws Exception {
        Path ref = Files.writeString(tempDir.resolve("ref.rdf"), REF);
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");

        CliRun.Result run = CliRun.run("ref2common", "--rdf-files", ref.toString(), "--output", blocker.resolve("c.xml").toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }

    @Test
    void dataWithoutCimOrNcIsBadInputAndWritesNothing() throws Exception {
        Path ref = Files.writeString(tempDir.resolve("ref.rdf"), REF.replace("xmlns:cim", "xmlns:x").replace("cim:", "x:"));
        Path out = tempDir.resolve("common.xml");

        CliRun.Result run = CliRun.run("ref2common", "--rdf-files", ref.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("prefix");
        assertThat(out).doesNotExist();
    }
}
