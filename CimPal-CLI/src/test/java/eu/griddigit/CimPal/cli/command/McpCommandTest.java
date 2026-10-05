/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
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
}
