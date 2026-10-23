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

    // ---- TEST-4: the --format json contract and the pipeline exit codes --------------------

    private static final String SHAPE_PREFIX = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n";

    /** A pipeline of {@code steps} (JSON objects) written next to the inputs; returns its path. */
    private Path pipelineOf(String... steps) throws Exception {
        Files.writeString(tempDir.resolve("m.ttl"), "<urn:x:a> <urn:x:p> \"one\" .\n");
        Files.writeString(tempDir.resolve("a.ttl"), SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"x\" .\n");
        Files.writeString(tempDir.resolve("b.ttl"), SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"y\" .\n");
        return Files.writeString(tempDir.resolve("pipeline.json"),
                "{\"name\":\"contract\",\"_comment\":\"ignored\",\"steps\":[" + String.join(",", steps) + "]}");
    }

    private static final String QUERY_STEP = "{\"id\":\"query\",\"command\":\"sparql\",\"models\":[\"m.ttl\"],"
            + "\"query\":\"SELECT ?o WHERE { ?s ?p ?o }\",\"output\":\"rows.csv\",\"overwrite\":true}";
    private static final String COMPARE_STEP = "{\"id\":\"diff\",\"command\":\"compare\",\"fileA\":\"a.ttl\","
            + "\"fileB\":\"b.ttl\"}";
    private static final String FAILING_STEP = "{\"id\":\"broken\",\"command\":\"sparql\",\"models\":[\"missing.ttl\"],"
            + "\"query\":\"SELECT ?o WHERE { ?s ?p ?o }\"}";

    @Test
    void jsonModePrintsOnlyThePipelineDocument() throws Exception {
        Path file = pipelineOf(QUERY_STEP, COMPARE_STEP);

        eu.griddigit.CimPal.cli.CliRun.Result run = eu.griddigit.CimPal.cli.CliRun.run("run", "--format", "json",
                "--root", tempDir.toString(), file.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        tools.jackson.databind.JsonNode json = run.json(); // the steps' own output went to stderr
        assertThat(eu.griddigit.CimPal.cli.CliSchemas.errors("run.schema.json", json)).as(json.toString()).isEmpty();
        assertThat(json.path("steps")).extracting(s -> s.path("status").asString()).containsExactly("OK", "VIOLATIONS");
        assertThat(json.path("summary").path("violations").asInt()).isEqualTo(1);
        assertThat(tempDir.resolve("rows.csv")).isRegularFile();
    }

    @Test
    void aFailingStepStopsThePipelineWithExitThree() throws Exception {
        // run reports a step that failed (exit 2 or 3) as exit 3, and skips the rest (docs/cli/run.md).
        Path file = pipelineOf(FAILING_STEP, QUERY_STEP);

        eu.griddigit.CimPal.cli.CliRun.Result run = eu.griddigit.CimPal.cli.CliRun.run("run", "--format", "json",
                "--root", tempDir.toString(), file.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
        tools.jackson.databind.JsonNode json = run.json();
        assertThat(json.path("steps")).singleElement()
                .satisfies(s -> assertThat(s.path("exitCode").asInt()).isEqualTo(ExitCode.INVALID_INPUT));
        assertThat(json.path("summary").path("skipped").asInt()).isEqualTo(1);
        assertThat(json.path("summary").path("stopped").asBoolean()).isTrue();
        assertThat(tempDir.resolve("rows.csv")).doesNotExist();
    }

    @Test
    void underscoreKeysInAStepChangeNothing() throws Exception {
        // _query would be a bad query if a step read it; _models a missing file.
        String commented = QUERY_STEP.replace("{\"id\"",
                "{\"_comment\":\"doc\",\"_query\":\"not SPARQL\",\"_models\":[\"missing.ttl\"],\"id\"");
        Path file = pipelineOf(commented);

        eu.griddigit.CimPal.cli.CliRun.Result run = eu.griddigit.CimPal.cli.CliRun.run("run", "--format", "json",
                "--root", tempDir.toString(), file.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(Files.readString(tempDir.resolve("rows.csv"))).contains("one");
    }

    @Test
    void nestedRunInvocationIsRefusedByTheDepthGuard() throws Exception {
        Path inner = pipeline("convert");
        int[] innerExit = new int[1];

        RunCommand.withinPipeline(() -> innerExit[0] = run("run", inner.toString()).exitCode());

        assertThat(innerExit[0]).isEqualTo(ExitCode.INVALID_INPUT);
    }
}
