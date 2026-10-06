/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --summary-file} and {@code --violations-exit-code} (DEP-3, R5/R6) on the four
 * JSON-capable commands: the file holds exactly the JSON document, only the "violations found"
 * exit changes, and serve/mcp/run keep their own exit-code logic.
 */
class AutomationOptionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHAPE_PREFIX = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n";

    @TempDir
    Path tempDir;

    private record Run(int exitCode, String stdout, String stderr) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = CimPalCli.inProcess().execute(args);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** A mapping validation of one violating Thing, plus {@code extra} arguments. */
    private String[] validate(String... extra) throws Exception {
        writeValidationInputs();
        List<String> args = new ArrayList<>(List.of("validate", "--workflow", "mapping",
                "--mapping-csv", tempDir.resolve("mapping.csv").toString(),
                "--models", tempDir.resolve("models").toString(),
                "--constraints-root", tempDir.resolve("constraints").toString(),
                "--output", tempDir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--workers", "1"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    private void writeValidationInputs() throws Exception {
        Files.createDirectories(tempDir.resolve("models"));
        Files.createDirectories(tempDir.resolve("constraints"));
        Files.writeString(tempDir.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(tempDir.resolve("constraints/shapes.ttl"), TestModels.THING_SHAPES);
        Files.writeString(tempDir.resolve("mapping.csv"), "xml_inputs,ttl,notes\ndata.xml,shapes.ttl,Thing check\n");
    }

    /** Two SHACL files that differ in one value, for both compare commands. */
    private Path[] shapeFiles() throws Exception {
        Path a = Files.writeString(tempDir.resolve("a.ttl"), SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"x\" .\n");
        Path b = Files.writeString(tempDir.resolve("b.ttl"), SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"y\" .\n");
        return new Path[] {a, b};
    }

    // ---- validate ---------------------------------------------------------------------------

    @Test
    void validateSummaryFileHoldsExactlyTheJsonOnStdout() throws Exception {
        Path summary = tempDir.resolve("xcom/return.json");

        Run run = run(validate("--format", "json", "--summary-file", summary.toString()));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(Files.readString(summary)).isEqualTo(run.stdout());
        assertThat(JSON.readTree(run.stdout()).path("hasViolations").asBoolean()).isTrue();
    }

    @Test
    void validateTextModeKeepsTextOnStdoutAndWritesTheJsonDocument() throws Exception {
        Path summary = tempDir.resolve("summary.json");

        Run run = run(validate("--summary-file", summary.toString()));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(run.stdout()).contains("=== Validation Summary ===").doesNotContain("\"schema\"");
        JsonNode result = JSON.readTree(Files.readString(summary));
        assertThat(result.path("schema").asString()).startsWith("cimpal-validate-summary/");
        assertThat(result.path("totals").path("violations").asInt()).isEqualTo(1);
        assertThat(result.path("shapes")).as("per-shape detail, as --format json gives").isNotEmpty();
    }

    @Test
    void violationsExitCodeZeroTurnsFindingsIntoData() throws Exception {
        Path summary = tempDir.resolve("summary.json");

        Run run = run(validate("--summary-file", summary.toString(), "--violations-exit-code", "0"));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(JSON.readTree(Files.readString(summary)).path("hasViolations").asBoolean()).isTrue();
    }

    @Test
    void violationsExitCodeReplacesOnlyTheViolationsExit() throws Exception {
        assertThat(run(validate("--violations-exit-code", "7")).exitCode()).isEqualTo(7);

        Run badInput = run("validate", "--workflow", "mapping", "--mapping-csv", tempDir.resolve("missing.csv").toString(),
                "--violations-exit-code", "0");
        assertThat(badInput.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void rowErrorsKeepExitOneEvenWhenViolationsAreRemapped() throws Exception {
        // A model that can't be parsed is an error row, not a finding: it must never end as a pass.
        Path summary = tempDir.resolve("summary.json");
        String[] args = validate("--summary-file", summary.toString(), "--violations-exit-code", "0");
        Files.writeString(tempDir.resolve("models/data.xml"), "<rdf:RDF this is not XML");

        Run run = run(args);

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(JSON.readTree(Files.readString(summary)).path("totals").path("errors").asInt()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "0.5", "\"0\"", "4294967296"})
    void aViolationsExitCodeThatIsNotAnIntegerIsBadInput(String value) throws Exception {
        writeValidationInputs();
        Path config = Files.writeString(tempDir.resolve("run.json"), """
                {"workflow": "mapping", "mappingCsv": "mapping.csv", "modelsDir": "models",
                 "constraintsRoot": "constraints", "outputDir": "out", "xmlBase": "%s", "workers": 1,
                 "violationsExitCode": %s}
                """.formatted(TestModels.XML_BASE, value));

        Run run = run("validate", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("violations-exit-code");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "256"})
    void anOutOfRangeViolationsExitCodeIsBadInput(String code) throws Exception {
        Path summary = tempDir.resolve("summary.json");

        Run run = run(validate("--violations-exit-code", code, "--summary-file", summary.toString()));

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("--violations-exit-code");
        assertThat(summary).doesNotExist();
        assertThat(tempDir.resolve("out")).doesNotExist();
    }

    @Test
    void configKeysAreReadAndTheSummaryPathIsRelativeToTheConfig() throws Exception {
        writeValidationInputs();
        Path config = Files.writeString(tempDir.resolve("run.json"), """
                {"workflow": "mapping", "mappingCsv": "mapping.csv", "modelsDir": "models",
                 "constraintsRoot": "constraints", "outputDir": "out", "xmlBase": "%s", "workers": 1,
                 "summaryFile": "xcom/return.json", "violationsExitCode": 0}
                """.formatted(TestModels.XML_BASE));

        Run run = run("validate", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(JSON.readTree(Files.readString(tempDir.resolve("xcom/return.json")))
                .path("totals").path("violations").asInt()).isEqualTo(1);
    }

    @Test
    void anUnwritableSummaryFileIsAnInternalError() throws Exception {
        // The summary path is an existing non-empty folder.
        Path summary = Files.createDirectories(tempDir.resolve("busy"));
        Files.writeString(summary.resolve("inside.txt"), "x");

        Run run = run(validate("--format", "json", "--summary-file", summary.toString()));

        assertThat(run.exitCode()).isEqualTo(ExitCode.INTERNAL_ERROR);
        assertThat(run.stderr()).contains("Could not write the summary file");
        assertThat(run.stdout()).as("no JSON when the summary could not be written").isEmpty();
    }

    // ---- sparql -----------------------------------------------------------------------------

    @Test
    void sparqlSummaryFileHoldsTheJsonInEveryFormat() throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:x:a> <urn:x:p> \"x\" .\n");
        Path jsonSummary = tempDir.resolve("json.json");
        Path textSummary = tempDir.resolve("text.json");
        String query = "SELECT ?o WHERE { ?s ?p ?o }";

        Run json = run("sparql", "--models", model.toString(), "--query", query, "--format", "json",
                "--summary-file", jsonSummary.toString(), "--violations-exit-code", "5");
        Run text = run("sparql", "--models", model.toString(), "--query", query,
                "--summary-file", textSummary.toString());

        assertThat(json.exitCode()).as("sparql has no violations exit").isEqualTo(ExitCode.OK);
        assertThat(Files.readString(jsonSummary)).isEqualTo(json.stdout());
        assertThat(text.stdout()).doesNotContain("\"columns\"");
        assertThat(JSON.readTree(Files.readString(textSummary)).path("rows")).hasSize(1);
    }

    // ---- compare and compare-instances --------------------------------------------------------

    @Test
    void compareSummaryFileAndExitCode() throws Exception {
        Path[] files = shapeFiles();
        Path summary = tempDir.resolve("compare.json");

        Run plain = run("compare", "--file-a", files[0].toString(), "--file-b", files[1].toString(), "--format", "json");
        Run remapped = run("compare", "--file-a", files[0].toString(), "--file-b", files[1].toString(), "--format", "json",
                "--summary-file", summary.toString(), "--violations-exit-code", "0");

        assertThat(plain.exitCode()).as(plain.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(remapped.exitCode()).as(remapped.stderr()).isEqualTo(ExitCode.OK);
        assertThat(Files.readString(summary)).isEqualTo(remapped.stdout());
        assertThat(JSON.readTree(remapped.stdout()).path("totalDifferences").asInt()).isPositive();
    }

    @Test
    void compareWritesTheSummaryAlsoWithAnOutputFile() throws Exception {
        Path[] files = shapeFiles();
        Path summary = tempDir.resolve("compare.json");
        Path csv = tempDir.resolve("diff.csv");

        Run run = run("compare", "--file-a", files[0].toString(), "--file-b", files[1].toString(),
                "--output", csv.toString(), "--summary-file", summary.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(run.stdout()).contains("[OK] Comparison results written to");
        assertThat(csv).exists();
        assertThat(JSON.readTree(Files.readString(summary)).path("schema").asString())
                .startsWith("cimpal-compare-result/");
    }

    @Test
    void compareInstancesSummaryFileAndExitCode() throws Exception {
        Path[] files = shapeFiles();
        Path summary = tempDir.resolve("instances.json");

        Run run = run("compare-instances", "--models-a", files[0].toString(), "--models-b", files[1].toString(),
                "--format", "json", "--summary-file", summary.toString(), "--violations-exit-code", "4");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(4);
        assertThat(Files.readString(summary)).isEqualTo(run.stdout());
        assertThat(JSON.readTree(run.stdout()).path("schema").asString())
                .startsWith("cimpal-compare-instances-result/");
    }

    // ---- serve, mcp and run -------------------------------------------------------------------

    @Test
    void pathGuardChecksTheSummaryFileAsAWritePath() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("a.ttl"), "");
        Files.writeString(root.resolve("b.ttl"), "");
        PathPolicy policy = PathPolicy.builder().root(root).build();
        ObjectNode request = (ObjectNode) JSON.readTree(
                "{\"fileA\":\"a.ttl\",\"fileB\":\"b.ttl\",\"summaryFile\":\"out/s.json\"}");

        ObjectNode checked = PathGuard.check("compare", request, policy, root);

        assertThat(checked.path("summaryFile").asString()).isEqualTo(root.toRealPath().resolve("out/s.json").toString());

        ObjectNode outside = (ObjectNode) JSON.readTree(
                "{\"fileA\":\"a.ttl\",\"fileB\":\"b.ttl\",\"summaryFile\":" + JSON.writeValueAsString(
                        tempDir.resolve("elsewhere/s.json").toString()) + "}");
        assertThatThrownBy(() -> PathGuard.check("compare", outside, policy, root))
                .isInstanceOf(PathNotAllowedException.class);

        // An existing summary file is only replaced when the request says so.
        Files.createDirectories(root.resolve("out"));
        Files.writeString(root.resolve("out/s.json"), "{}");
        assertThatThrownBy(() -> PathGuard.check("compare", request, policy, root))
                .isInstanceOf(PathNotAllowedException.class).hasMessageContaining("overwrite");
        request.put("overwrite", true);
        assertThat(PathGuard.check("compare", request, policy, root).path("summaryFile").asString()).isNotBlank();
    }

    @Test
    void aRunStepStillCountsViolationsWhenItAsksForAnotherExitCode() throws Exception {
        writeValidationInputs();
        Path pipeline = Files.writeString(tempDir.resolve("pipeline.json"), """
                {"name": "p", "steps": [{"id": "s1", "command": "validate", "workflow": "mapping",
                  "mappingCsv": "mapping.csv", "modelsDir": "models", "constraintsRoot": "constraints",
                  "outputDir": "out", "xmlBase": "%s", "workers": 1, "violationsExitCode": 0}]}
                """.formatted(TestModels.XML_BASE));

        Run run = run("run", pipeline.toString(), "--root", tempDir.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
    }
}
