/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEP-2 (R2): a run that runs out of memory ends with exit code 3 and one clear line on stderr,
 * never with exit 1 ("violations found") or a hang. Each case runs the CLI in a forked JVM with a
 * 16 MB heap against a model that needs far more.
 */
class OutOfMemoryExitTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private record Forked(int exitCode, String stdout, String stderr) {
    }

    @Test
    void aMappingValidationThatRunsOutOfMemoryExitsWithThree() throws Exception {
        Path models = Files.createDirectories(tempDir.resolve("models"));
        writeLargeModel(models.resolve("data.xml"), 100_000);
        Forked run = fork(null, validateArgs("mapping", "data.xml"));

        assertOutOfMemoryExit(run);
        assertThat(run.stdout()).as("stdout carries no partial JSON").isEmpty();
    }

    @Test
    void aTimestampedValidationThatRunsOutOfMemoryExitsWithThree() throws Exception {
        // The timestamped workflow validates in its own worker pools (other rethrow sites).
        Path group = Files.createDirectories(tempDir.resolve("models/IGM_Test"));
        writeLargeModel(group.resolve("IGM_Test_EQ_20260101T0000Z.xml"), 100_000);
        Forked run = fork(null, validateArgs("timestamped", "EQ"));

        assertOutOfMemoryExit(run);
        assertThat(run.stdout()).as("stdout carries no partial JSON").isEmpty();
    }

    @Test
    void anMcpToolCallThatRunsOutOfMemoryAnswersWithAnErrorAndExitsWithThree() throws Exception {
        Path models = Files.createDirectories(tempDir.resolve("models"));
        writeLargeModel(models.resolve("data.xml"), 100_000);
        writeShapesAndMapping("data.xml");
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call");
        ObjectNode arguments = call.putObject("params").put("name", "validate").putObject("arguments");
        arguments.put("workflow", "mapping")
                .put("mappingCsv", tempDir.resolve("mapping.csv").toString())
                .put("modelsDir", models.toString())
                .put("constraintsRoot", tempDir.resolve("constraints").toString())
                .put("outputDir", tempDir.resolve("out").toString())
                .put("xmlBase", TestModels.XML_BASE)
                .put("workers", 1);

        Forked run = fork(JSON.writeValueAsString(call) + "\n", "mcp", "--root", tempDir.toString());

        assertOutOfMemoryExit(run);
        List<JsonNode> replies = run.stdout().lines().filter(l -> !l.isBlank()).map(JSON::readTree).toList();
        assertThat(replies).as("stdout: %s", run.stdout()).hasSize(1);
        assertThat(replies.getFirst().path("id").asInt()).isEqualTo(7);
        assertThat(replies.getFirst().path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(replies.getFirst().path("error").path("message").asText()).contains("Out of memory");
    }

    private static void assertOutOfMemoryExit(Forked run) {
        assertThat(run.exitCode()).as("exit code; stderr:%n%s", run.stderr()).isEqualTo(3);
        assertThat(run.stderr()).contains("[ERROR] Out of memory").contains("-Xmx").contains("docs/guide/sizing.md");
    }

    private String[] validateArgs(String workflow, String mappingInput) throws IOException {
        writeShapesAndMapping(mappingInput);
        return new String[] {"validate", "--workflow", workflow,
                "--mapping-csv", tempDir.resolve("mapping.csv").toString(),
                "--models", tempDir.resolve("models").toString(),
                "--constraints-root", tempDir.resolve("constraints").toString(),
                "--output", tempDir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--workers", "1", "--format", "json"};
    }

    private void writeShapesAndMapping(String mappingInput) throws IOException {
        Path constraints = Files.createDirectories(tempDir.resolve("constraints"));
        Files.writeString(constraints.resolve("shapes.ttl"), TestModels.THING_SHAPES);
        Files.writeString(tempDir.resolve("mapping.csv"), "xml_inputs,ttl,notes\n" + mappingInput + ",shapes.ttl,large\n");
    }

    /** Runs the CLI in a JVM with a 16 MB heap; {@code stdin} may be null. */
    private Forked fork(String stdin, String... cliArgs) throws Exception {
        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-Xmx16m", "-cp", testClassPath(),
                CimPalCli.class.getName()));
        command.addAll(List.of(cliArgs));
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Path input = null;
        if (stdin != null) {
            input = Files.writeString(tempDir.resolve("stdin.txt"), stdin);
            builder.redirectInput(input.toFile());
        }
        Process process = builder.start();
        boolean finished = process.waitFor(3, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("the JVM ends instead of hanging").isTrue();
        return new Forked(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    /** A model with a header and {@code count} {@code ex:Thing}s with a name each (about 2 triples per thing). */
    private static void writeLargeModel(Path file, int count) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                    + "         xmlns:md=\"http://iec.ch/TC57/61970-552/ModelDescription/1#\" xmlns:ex=\"urn:test:\">\n"
                    + "  <md:FullModel rdf:about=\"urn:uuid:header\">\n"
                    + "    <md:Model.scenarioTime>2026-01-01T00:00:00Z</md:Model.scenarioTime>\n"
                    + "  </md:FullModel>\n");
            for (int i = 0; i < count; i++) {
                w.write("  <ex:Thing rdf:about=\"#_" + i + "\"><ex:name>thing number " + i + "</ex:name></ex:Thing>\n");
            }
            w.write("</rdf:RDF>\n");
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Surefire's full test classpath (java.class.path may be its manifest-only booter jar). */
    private static String testClassPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }
}
