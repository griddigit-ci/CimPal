/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.stats.RunStatsSnapshot;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --stats} (DEP-2, R3): the statistics are one more field of the single JSON object on
 * stdout (so {@code serve} and {@code mcp} still return one JSON value), a {@code [STATS]} line on
 * stderr otherwise, and the output without {@code --stats} is unchanged.
 */
class StatsJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static List<Error> schemaErrors(JsonNode stats) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Fixtures.read("cli-json", "stats.schema.json"), InputFormat.JSON);
        return schema.validate(stats);
    }

    /** A mapping-validation setup under {@code dir}: one violating Thing. */
    private static String[] validateArgs(Path dir, String... extra) throws Exception {
        Files.createDirectories(dir.resolve("models"));
        Files.createDirectories(dir.resolve("constraints"));
        Files.writeString(dir.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        Files.writeString(dir.resolve("constraints/shapes.ttl"), TestModels.THING_SHAPES);
        Files.writeString(dir.resolve("mapping.csv"), "xml_inputs,ttl,notes\ndata.xml,shapes.ttl,Thing check\n");
        List<String> args = new ArrayList<>(List.of("validate", "--workflow", "mapping",
                "--mapping-csv", dir.resolve("mapping.csv").toString(),
                "--models", dir.resolve("models").toString(),
                "--constraints-root", dir.resolve("constraints").toString(),
                "--output", dir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--workers", "1", "--samples", "0"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    @Test
    void validateJsonCarriesStatsThatMatchTheSchema() throws Exception {
        Run run = run(validateArgs(tempDir, "--format", "json", "--stats"));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode result = JSON.readTree(run.stdout());
        assertThat(result.path("totals").path("violations").asInt()).isEqualTo(1);
        JsonNode stats = result.path("stats");
        assertThat(schemaErrors(stats)).as(stats.toString()).isEmpty();
        assertThat(stats.path("triplesLoaded").asLong())
                .isEqualTo(TestModels.parseRdfXml(TestModels.VIOLATING_THING_MODEL).size());
        assertThat(stats.path("inputBytes").asLong()).isEqualTo(Files.size(tempDir.resolve("models/data.xml")));
        assertThat(stats.path("phases").has("validate")).isTrue();
        assertThat(run.stderr()).doesNotContain("[STATS]");
    }

    @Test
    void validateJsonWithoutStatsIsUnchanged() throws Exception {
        Path a = tempDir.resolve("a");
        Path b = tempDir.resolve("b");
        Run plain = run(validateArgs(a, "--format", "json"));
        Run withStats = run(validateArgs(b, "--format", "json", "--stats"));

        assertThat(plain.stdout()).doesNotContain("\"stats\"");
        String expected = Normalizer.timestamps().apply(plain.stdout().replace(jsonEscaped(a), "<DIR>"));
        String actual = Normalizer.timestamps().apply(withStats.stdout().replace(jsonEscaped(b), "<DIR>")
                .replaceAll(",\\R  \"stats\": \\{[^\\r\\n]*}", ""));
        assertThat(actual).isEqualTo(expected);
    }

    /** A path as it appears inside a JSON string (Windows backslashes doubled). */
    private static String jsonEscaped(Path dir) {
        return dir.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    @Test
    void textOutputPutsStatsOnStderr() throws Exception {
        Run run = run(validateArgs(tempDir, "--stats"));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(run.stdout()).doesNotContain("[STATS]").doesNotContain("triplesLoaded");
        String line = run.stderr().lines().filter(l -> l.startsWith("[STATS] ")).findFirst().orElseThrow();
        assertThat(schemaErrors(JSON.readTree(line.substring("[STATS] ".length())))).isEmpty();
    }

    @Test
    void theConfigKeyTurnsStatsOn() throws Exception {
        String[] args = validateArgs(tempDir, "--format", "json");
        Path config = Files.writeString(tempDir.resolve("run.json"), "{\"stats\": true}");
        List<String> withConfig = new ArrayList<>(List.of(args));
        withConfig.addAll(List.of("--config", config.toString()));

        Run run = run(withConfig.toArray(String[]::new));

        assertThat(JSON.readTree(run.stdout()).has("stats")).as(run.stdout()).isTrue();
    }

    @Test
    void stringsAreEscapedSoTheObjectStaysValidJson() {
        String hostile = "Linux \"quoted\" back\\slash\nnew line \u0001   end";
        RunStatsSnapshot snapshot = new RunStatsSnapshot(1, java.util.Map.of("load", 1L), null, 2, 3, null, 0, 1,
                0, 0, hostile, hostile);

        JsonNode parsed = JSON.readTree(StatsJson.object(snapshot));

        assertThat(parsed.path("os").asText()).isEqualTo(hostile);
        assertThat(parsed.path("javaVersion").asText()).isEqualTo(hostile);
        assertThat(parsed.path("cpuMs").isNull()).isTrue();
        assertThat(schemaErrors(parsed)).isEmpty();
    }

    @Test
    void sparqlJsonCarriesStats() throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:a> <urn:p> \"x\" .\n");

        Run run = run("sparql", "--models", model.toString(), "--query", "SELECT ?o WHERE { ?s ?p ?o }",
                "--format", "json", "--stats");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        JsonNode result = JSON.readTree(run.stdout());
        assertThat(result.path("rows")).hasSize(1);
        assertThat(schemaErrors(result.path("stats"))).isEmpty();
        assertThat(result.path("stats").path("triplesLoaded").asLong()).isEqualTo(1);
        assertThat(result.path("stats").path("phases").has("query")).isTrue();
    }

    @Test
    void compareCommandsCarryStats() throws Exception {
        // compare infers its "shacl" type from .ttl, which needs typed shapes: 2 + 4 triples.
        String prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n";
        Path fileA = Files.writeString(tempDir.resolve("a.ttl"),
                prefixes + "<urn:x:a> a sh:NodeShape ; sh:name \"x\" .\n");
        Path fileB = Files.writeString(tempDir.resolve("b.ttl"),
                prefixes + "<urn:x:a> a sh:NodeShape ; sh:name \"y\" .\n<urn:x:b> a sh:NodeShape ; sh:name \"z\" .\n");

        Run compare = run("compare", "--file-a", fileA.toString(), "--file-b", fileB.toString(),
                "--format", "json", "--stats");
        Run instances = run("compare-instances", "--models-a", fileA.toString(), "--models-b", fileB.toString(),
                "--format", "json", "--stats");

        for (Run r : List.of(compare, instances)) {
            assertThat(r.exitCode()).as(r.stderr()).isIn(ExitCode.OK, ExitCode.VIOLATIONS);
            JsonNode stats = JSON.readTree(r.stdout()).path("stats");
            assertThat(schemaErrors(stats)).as(r.stdout()).isEmpty();
            assertThat(stats.path("triplesLoaded").asLong()).isEqualTo(6);
            assertThat(stats.path("phases").has("compare")).isTrue();
        }
    }
}
