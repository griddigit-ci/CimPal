/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code csv2shacl}: restored files, a failing CSV, and an unsafe name from the CSV. */
class CsvToShaclCommandTest {

    private static final String CSV = """
            SourceFile,PropertyShape/NodeShape ID,Payload
            EQ.ttl,__HEADER__,"'@prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/s#> ."
            EQ.ttl,ex:Line,"ex:Line a sh:NodeShape ;
                sh:targetClass ex:ACLineSegment ."
            """;

    @TempDir
    Path tempDir;

    @Test
    void restoresValidTurtle() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("EQ_EA.csv"), CSV);
        Path out = tempDir.resolve("restored");

        CliRun.Result run = CliRun.run("csv2shacl", "--csv-files", csv.toString(), "--output-dir", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("EQ.ttl");
        assertThat(RDFDataMgr.loadModel(out.resolve("EQ.ttl").toString()).size()).isEqualTo(2);
    }

    @Test
    void aFailingCsvIsReportedTheOthersAreStillRestored() throws Exception {
        Path good = Files.writeString(tempDir.resolve("good.csv"), CSV);
        Path noPayload = Files.writeString(tempDir.resolve("other.csv"), "SourceFile,Notes\nX.ttl,x\n");
        Path out = tempDir.resolve("restored");

        CliRun.Result run = CliRun.run("csv2shacl", "--csv-files", noPayload + "," + good, "--output-dir", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("other.csv", "Payload");
        assertThat(out.resolve("EQ.ttl")).exists();
    }

    @Test
    void aSourceFileThatWouldLeaveTheFolderIsRefused() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("evil.csv"),
                CSV.replace("EQ.ttl,__HEADER__", "C:evil.ttl,__HEADER__").replace("EQ.ttl,ex:Line", "C:evil.ttl,ex:Line"));
        Path out = tempDir.resolve("restored");

        CliRun.Result run = CliRun.run("csv2shacl", "--csv-files", csv.toString(), "--output-dir", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("Unsafe output file name");
        try (var files = Files.list(out)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    /** Security review: a Windows-reserved character in a name was an internal error (exit 3). */
    @Test
    void aWindowsReservedCharacterInANameIsBadInput() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("q.csv"), CSV.replace("EQ.ttl,", "a?.ttl,"));

        CliRun.Result run = CliRun.run("csv2shacl", "--csv-files", csv.toString(), "--output-dir", tempDir.resolve("r").toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("EQ_EA.csv"), CSV);
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"csvFiles\": [\"EQ_EA.csv\"], \"outputDir\": \"r\"}");

        CliRun.Result run = CliRun.run("csv2shacl", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("r/EQ.ttl")).exists();
    }
}
