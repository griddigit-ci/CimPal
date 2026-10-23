/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.CliSchemas;
import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract of {@code compare} (TEST-4): differences are exit 1, data in JSON, failures exit 3. */
class CompareCommandTest {

    private static final String SHAPE_PREFIX = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n";

    @TempDir
    Path tempDir;

    private Path shapes(String name, String value) throws Exception {
        return Files.writeString(tempDir.resolve(name), SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"" + value + "\" .\n");
    }

    @Test
    void identicalFilesExitZeroWithNoDifferences() throws Exception {
        Path a = shapes("a.ttl", "x");
        Path b = shapes("b.ttl", "x");

        CliRun.Result run = CliRun.run("compare", "--file-a", a.toString(), "--file-b", b.toString(), "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        JsonNode json = run.json();
        assertThat(CliSchemas.errors("compare.schema.json", json)).isEmpty();
        assertThat(json.path("totalDifferences").asInt()).isZero();
        assertThat(json.path("compareType").asString()).as("auto from the .ttl extension").isEqualTo("shacl");
    }

    @Test
    void differencesExitOneAndAreListed() throws Exception {
        Path a = shapes("a.ttl", "x");
        Path b = shapes("b.ttl", "y");

        CliRun.Result run = CliRun.run("compare", "--file-a", a.toString(), "--file-b", b.toString(), "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode json = run.json();
        assertThat(json.path("totalDifferences").asInt()).isPositive().isEqualTo(json.path("differences").size());
    }

    @Test
    void aMissingFileIsBadInput() throws Exception {
        Path a = shapes("a.ttl", "x");

        CliRun.Result run = CliRun.run("compare", "--file-a", a.toString(),
                "--file-b", tempDir.resolve("missing.ttl").toString(), "--format", "json");

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).isEmpty();
    }

    @Test
    void anOutputThatCannotBeWrittenIsAnInternalError() throws Exception {
        // D3: a natural failure, no hook: the output's parent folder is a file.
        Path a = shapes("a.ttl", "x");
        Path b = shapes("b.ttl", "y");
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result toFile = CliRun.run("compare", "--file-a", a.toString(), "--file-b", b.toString(),
                "--output", blocker.resolve("diff.csv").toString());
        CliRun.Result summary = CliRun.run("compare", "--file-a", a.toString(), "--file-b", b.toString(),
                "--format", "json", "--summary-file", blocker.resolve("summary.json").toString());

        assertThat(toFile.exitCode()).as(toFile.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
        assertThat(summary.exitCode()).as(summary.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
        assertThat(summary.stdout()).as("nothing on stdout when the summary file fails").isEmpty();
    }
}
