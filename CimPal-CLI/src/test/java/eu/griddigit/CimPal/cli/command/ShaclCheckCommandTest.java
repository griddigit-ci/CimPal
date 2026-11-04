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

/** {@code shacl-check}: exit 0 conforming, 1 not conforming, 2 bad input (fail closed). */
class ShaclCheckCommandTest {

    private static final String PREFIXES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <http://example.org/s#> .
            """;

    @TempDir
    Path tempDir;

    @Test
    void wellFormedShapesExitZero() throws Exception {
        Path ok = Files.writeString(tempDir.resolve("ok.ttl"), PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass ex:C .");

        CliRun.Result run = CliRun.run("shacl-check", "--shacl-files", ok.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(run.stdout()).contains("[OK] ok.ttl");
    }

    @Test
    void aMalformedShapeExitsOneAndNamesTheProblem() throws Exception {
        Path ok = Files.writeString(tempDir.resolve("ok.ttl"), PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass ex:C .");
        Path bad = Files.writeString(tempDir.resolve("bad.ttl"),
                PREFIXES + "ex:P a sh:PropertyShape ; sh:path ex:p ; sh:minCount \"one\" .");

        CliRun.Result run = CliRun.run("shacl-check", "--shacl-files", ok + "," + bad);

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(run.stdout()).contains("[OK] ok.ttl", "[FAIL] bad.ttl", "ex:P", "1 not conforming");
    }

    /** Regression: the GUI loaded an unreadable file as an empty model, which "conformed". */
    @Test
    void aFileThatDoesNotParseIsBadInputNotAPass() throws Exception {
        Path broken = Files.writeString(tempDir.resolve("broken.ttl"), "this is not turtle .");

        CliRun.Result run = CliRun.run("shacl-check", "--shacl-files", broken.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).doesNotContain("[OK]");
        assertThat(run.stderr()).contains("broken.ttl");
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Files.writeString(tempDir.resolve("ok.ttl"), PREFIXES + "ex:S a sh:NodeShape ; sh:targetClass ex:C .");
        Path config = Files.writeString(tempDir.resolve("c.json"), "{\"shaclFiles\": [\"ok.ttl\"]}");

        assertThat(CliRun.run("shacl-check", "--config", config.toString()).exitCode()).isEqualTo(ExitCode.OK);
    }
}
