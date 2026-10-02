/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code run} must not start servers or nested pipelines as steps (SEC-1, gap G6). */
class RunCommandTest {

    @TempDir
    Path tempDir;

    private record Result(int exitCode, String err) {
    }

    private Result run(String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Result(new CommandLine(new CimPalCli()).execute(args), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(origErr);
        }
    }

    private Path pipeline(String... commands) throws Exception {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < commands.length; i++) {
            if (i > 0) steps.append(',');
            steps.append("{\"id\":\"s").append(i + 1).append("\",\"command\":\"").append(commands[i]).append("\"}");
        }
        return Files.writeString(tempDir.resolve("pipeline.json"), "{\"name\":\"p\",\"steps\":[" + steps + "]}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"serve", "mcp", "run", "SERVE", " run "})
    void nestedServerOrPipelineStepIsRefusedBeforeAnythingRuns(String forbidden) throws Exception {
        // The first step would fail on its own (no config); it must not even start.
        Path file = pipeline("convert", forbidden);

        Result result = run("run", file.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(result.err()).contains("s2").contains("not allowed");
        assertThat(result.err()).doesNotContain("[1/2]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"@steps.args", "--help", "help", "Validate", "validate --config x"})
    void onlyKnownPipelineCommandsAreAllowed(String command) throws Exception {
        // An @-file would otherwise let picocli read "serve --host 0.0.0.0 ..." from disk.
        Files.writeString(tempDir.resolve("steps.args"), "serve --port 0");
        Path file = pipeline(command);

        Result result = run("run", file.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(result.err()).contains("not allowed");
    }

    @Test
    void nonTextCommandIsRefused() throws Exception {
        Path file = Files.writeString(tempDir.resolve("pipeline.json"),
                "{\"steps\":[{\"id\":\"s1\",\"command\":{\"name\":\"serve\"}}]}");

        Result result = run("run", file.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(result.err()).contains("not allowed");
    }

    @Test
    void allowedCommandPassesTheCheckAndRuns() throws Exception {
        // convert without inputs fails on its own; what matters is that it was started.
        Result result = run("run", pipeline("convert").toString());

        assertThat(result.err()).doesNotContain("not allowed");
        assertThat(result.exitCode()).isNotEqualTo(ExitCode.OK);
    }

    @Test
    void dryRunAlsoRefusesNestedSteps() throws Exception {
        Result result = run("run", "--dry-run", pipeline("serve").toString());

        assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void nestedRunInvocationIsRefusedByTheDepthGuard() throws Exception {
        Path inner = pipeline("convert");
        int[] innerExit = new int[1];

        RunCommand.withinPipeline(() -> innerExit[0] = run("run", inner.toString()).exitCode());

        assertThat(innerExit[0]).isEqualTo(ExitCode.INVALID_INPUT);
    }
}
