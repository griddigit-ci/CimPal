/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class McpCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Runs {@code mcp} in-process with {@code stdin} as its input and returns the JSON-RPC
     * messages it wrote. {@code mcp} reads until end of input, and it redirects System.out to
     * stderr for its own protection, so both streams are restored here.
     */
    private static List<JsonNode> runMcp(String stdin, String... mcpOptions) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(UTF_8)));
            System.setOut(new PrintStream(stdout, true, UTF_8));
            String[] args = new String[mcpOptions.length + 1];
            args[0] = "mcp";
            System.arraycopy(mcpOptions, 0, args, 1, mcpOptions.length);
            exitCode = new CommandLine(new CimPalCli()).execute(args);
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertThat(exitCode).isEqualTo(ExitCode.OK);
        return stdout.toString(UTF_8).lines().filter(line -> !line.isBlank()).map(JSON::readTree).toList();
    }

    @Test
    void initializeReportsTheReleaseVersion() {
        List<JsonNode> messages = runMcp("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                """);

        JsonNode serverInfo = messages.getFirst().path("result").path("serverInfo");
        assertThat(serverInfo.path("name").asText("")).isEqualTo("CimPal");
        assertThat(serverInfo.path("version").asText("")).isEqualTo(System.getProperty("cimpal.expectedVersion"));
    }

    @Test
    void statsFromTheToolArgumentsStayInsideTheOneJsonResult(@TempDir Path tempDir) throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:x:a> <urn:x:p> \"x\" .\n");
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode params = call.putObject("params").put("name", "sparql");
        ObjectNode arguments = params.putObject("arguments")
                .put("query", "SELECT ?o WHERE { ?s ?p ?o }").put("stats", true);
        arguments.putArray("models").add(model.toString());

        List<JsonNode> messages = runMcp(JSON.writeValueAsString(call) + "\n", "--root", tempDir.toString());

        String text = messages.getFirst().path("result").path("content").get(0).path("text").asText();
        // Exactly one JSON value: trailing content after the object would fail here.
        JsonNode result = JSON.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text);
        assertThat(result.path("rows")).hasSize(1);
        assertThat(result.path("stats").path("triplesLoaded").asLong()).isEqualTo(1);
    }

    // ---- validate --workflow combined through mcp: PathGuard, the config rewrite and Core's checks ----

    private static final String DATA_WITHOUT_SIZE = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
              <ex:Thing rdf:about="#_1"/>
            </rdf:RDF>
            """;

    private static String combinedCall(Path shapes, Path data, Path out) {
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode arguments = call.putObject("params").put("name", "validate").putObject("arguments");
        arguments.put("workflow", "combined").put("outputDir", out.toString())
                .put("xmlBase", TestModels.XML_BASE).put("workers", 1);
        arguments.putArray("constraintFiles").add(shapes.toString());
        arguments.putArray("dataFiles").add(data.toString());
        return JSON.writeValueAsString(call) + "\n";
    }

    private static JsonNode toolResult(List<JsonNode> messages) {
        return messages.getFirst().path("result");
    }

    @Test
    void combinedValidationRunsAsAToolCall(@TempDir Path tempDir) throws Exception {
        Path shapes = Files.writeString(tempDir.resolve("shapes.ttl"), TestModels.THING_SHAPES);
        Path data = Files.writeString(tempDir.resolve("data.xml"), DATA_WITHOUT_SIZE);

        JsonNode result = toolResult(runMcp(combinedCall(shapes, data, tempDir.resolve("out")), "--root", tempDir.toString()));

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode summary = JSON.readTree(result.path("content").get(0).path("text").asText());
        assertThat(summary.path("run").path("workflow").asText()).isEqualTo("combined");
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
        assertThat(Path.of(summary.path("report").asText())).isRegularFile();
    }

    @Test
    void combinedValidationRefusesADataFileOutsideTheRoot(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path shapes = Files.writeString(root.resolve("shapes.ttl"), TestModels.THING_SHAPES);
        Path outside = Files.writeString(tempDir.resolve("outside.xml"), DATA_WITHOUT_SIZE);

        JsonNode result = toolResult(runMcp(combinedCall(shapes, outside, root.resolve("out")), "--root", root.toString()));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("outside the allowed roots");
        assertThat(root.resolve("out")).doesNotExist();
    }

    @Test
    void combinedValidationRefusesAnImportOutsideTheRoot(@TempDir Path tempDir) throws Exception {
        // PathGuard only sees the request; the import is found by Core while the policy is active.
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(tempDir.resolve("secret.ttl"), TestModels.THING_SHAPES);
        Path shapes = Files.writeString(root.resolve("shapes.ttl"),
                "<urn:test:shapes> <http://www.w3.org/2002/07/owl#imports> <../secret.ttl> .\n");
        Path data = Files.writeString(root.resolve("data.xml"), DATA_WITHOUT_SIZE);

        JsonNode result = toolResult(runMcp(combinedCall(shapes, data, root.resolve("out")), "--root", root.toString()));

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asText()).path("exitCode").asInt())
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(root.resolve("out")).doesNotExist();
    }
}
