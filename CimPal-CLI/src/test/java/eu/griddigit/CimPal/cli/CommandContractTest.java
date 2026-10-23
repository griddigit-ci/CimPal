/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules every CLI command keeps (TEST-4, D1), one parameterised test per rule: {@code --help}
 * exits 0; missing required input exits 2 with a message on stderr and, in {@code --format json}
 * mode, nothing on stdout; and in {@code --format json} mode stdout is exactly one JSON document
 * that matches the command's schema in {@code fixtures/cli-json/}. Rules that only some commands
 * have are in the per-command test classes.
 */
class CommandContractTest {

    /** Every subcommand: the 13 of the CLI documentation, plus id-map. */
    static final List<String> COMMANDS = List.of("validate", "sparql", "manifest", "convert", "rdfs2shacl", "compare",
            "compare-instances", "excel2shacl", "organize", "gen-instances", "run", "serve", "mcp", "id-map");

    static final List<String> JSON_COMMANDS = CliFixtures.JSON_COMMANDS;

    @TempDir
    Path tempDir;

    @Test
    void everySubcommandIsCovered() {
        Map<String, CommandLine> subcommands = new CommandLine(new CimPalCli()).getSubcommands();

        TreeSet<String> names = new TreeSet<>(subcommands.keySet());
        names.remove("help");
        assertThat(names).containsExactlyInAnyOrderElementsOf(COMMANDS);
    }

    @ParameterizedTest
    @FieldSource("COMMANDS")
    void helpExitsZeroAndPrintsTheUsage(String command) {
        CliRun.Result run = CliRun.run(command, "--help");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        // picocli colours the usage when it thinks it writes to a terminal.
        assertThat(run.stdout().replaceAll("\u001B\\[[;\\d]*m", "")).startsWith("Usage: cimpal " + command);
        assertThat(run.stderr()).isEmpty();
    }

    /**
     * Arguments that leave out the command's required input. serve and mcp need none, so they get
     * bad input that is refused before anything starts: a root that doesn't exist. serve also gets
     * a port of its own and a token file under @TempDir, so a regression could never bind 7474 or
     * write the user's default token file.
     */
    private String[] withoutRequiredInput(String command) {
        String missing = tempDir.resolve("no-such-folder").toString();
        return switch (command) {
            case "serve" -> new String[] {command, "--root", missing, "--port", "0",
                    "--token-file", tempDir.resolve("serve.token").toString()};
            case "mcp" -> new String[] {command, "--root", missing};
            default -> new String[] {command};
        };
    }

    @ParameterizedTest
    @FieldSource("COMMANDS")
    @org.junit.jupiter.api.Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD) // a server that starts fails, not hangs
    void missingRequiredInputExitsTwoWithAMessageOnStderr(String command) {
        CliRun.Result run = CliRun.run(withoutRequiredInput(command));

        assertThat(run.exitCode()).as("stdout: %s%nstderr: %s", run.stdout(), run.stderr())
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).as("a message on stderr").isNotBlank().doesNotContain("\tat ");
    }

    @ParameterizedTest
    @FieldSource("JSON_COMMANDS")
    void missingRequiredInputInJsonModeLeavesStdoutEmpty(String command) {
        List<String> args = new ArrayList<>(List.of(command, "--format", "json"));
        if (command.equals("run")) {
            args.add(tempDir.resolve("missing-pipeline.json").toString());
        }

        CliRun.Result run = CliRun.run(args.toArray(String[]::new));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).isEmpty();
        assertThat(run.stderr()).isNotBlank();
    }

    @ParameterizedTest
    @FieldSource("JSON_COMMANDS")
    void jsonModePrintsExactlyOneDocumentMatchingTheSchema(String command) {
        CliFixtures.Scenario scenario = CliFixtures.scenario(command, tempDir);

        CliRun.Result run = CliRun.run(scenario.args());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(scenario.exitCode());
        JsonNode document = run.json();
        assertThat(CliSchemas.errors(CliSchemas.schemaFile(command), document)).as(document.toString()).isEmpty();
    }
}
