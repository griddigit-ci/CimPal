/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File paths reaching serve, mcp and run must stay under the allowed roots (SEC-2, gap G2). Each
 * entry point runs a real {@code convert} with paths inside and outside the root.
 */
class AllowedRootsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TURTLE = "<urn:a> <urn:p> \"x\" .\n";

    @TempDir
    Path tempDir;

    private Path root;
    private Path outside;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream origErr;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(tempDir.resolve("root"));
        outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(root.resolve("in.ttl"), TURTLE);
        Files.writeString(outside.resolve("secret.ttl"), TURTLE);
        origErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setErr(origErr);
    }

    private String convertRequest(Path input, Path output, boolean overwrite) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("input", input.toString());
        body.put("output", output.toString());
        if (overwrite) {
            body.put("overwrite", true);
        }
        return body.toString();
    }

    // ---- serve -----------------------------------------------------------------------------

    @Test
    void serveRefusesPathsOutsideTheRootAndRunsInsideIt() throws Exception {
        ServeCommand command = new ServeCommand();
        command.environment = Map.of(ServeSecurity.TOKEN_ENV, "t".repeat(40));
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(
                "--host", "127.0.0.1", "--port", "0", "--root", root.toString()));
        ServeServer server = started.get(30, TimeUnit.SECONDS);
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpResponse<String> refused = post(http, server, "/convert",
                    convertRequest(outside.resolve("secret.ttl"), root.resolve("a.ttl"), false));
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.body()).contains("outside the allowed");
            assertThat(root.resolve("a.ttl")).doesNotExist();

            HttpResponse<String> writeOutside = post(http, server, "/convert",
                    convertRequest(root.resolve("in.ttl"), outside.resolve("stolen.ttl"), false));
            assertThat(writeOutside.statusCode()).isEqualTo(403);
            assertThat(outside.resolve("stolen.ttl")).doesNotExist();

            // Relative paths resolve against the root; convert also works through serve now.
            HttpResponse<String> ok = post(http, server, "/convert", "{\"input\":\"in.ttl\",\"output\":\"out.ttl\"}");
            assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
            assertThat(root.resolve("out.ttl")).exists();

            HttpResponse<String> again = post(http, server, "/convert", "{\"input\":\"in.ttl\",\"output\":\"out.ttl\"}");
            assertThat(again.statusCode()).isEqualTo(403);
            assertThat(again.body()).contains("overwrite");
            HttpResponse<String> replaced = post(http, server, "/convert",
                    "{\"input\":\"in.ttl\",\"output\":\"out.ttl\",\"overwrite\":true}");
            assertThat(replaced.statusCode()).isEqualTo(200);

            HttpResponse<String> notJson = post(http, server, "/convert", "{not json");
            assertThat(notJson.statusCode()).isEqualTo(400);
        } finally {
            post(http, server, "/shutdown", "");
            exit.get(30, TimeUnit.SECONDS);
        }
    }

    private static HttpResponse<String> post(HttpClient http, ServeServer server, String path, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + "t".repeat(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- mcp -------------------------------------------------------------------------------

    @Test
    void mcpRefusesPathsOutsideTheRootAndRunsInsideIt() throws Exception {
        McpCommand mcp = new McpCommand();
        mcp.usePolicy(PathPolicy.builder().root(root).build(), root);

        ObjectNode refused = mcp.callTool("convert",
                MAPPER.readTree(convertRequest(outside.resolve("secret.ttl"), root.resolve("a.ttl"), false)));
        assertThat(refused.get("isError").asBoolean()).isTrue();
        assertThat(refused.get("content").get(0).get("text").asString()).contains("outside the allowed");
        assertThat(root.resolve("a.ttl")).doesNotExist();

        ObjectNode ok = mcp.callTool("convert", MAPPER.readTree("{\"input\":\"in.ttl\",\"output\":\"m.ttl\"}"));
        assertThat(ok.get("isError").asBoolean()).as(ok.toString()).isFalse();
        assertThat(root.resolve("m.ttl")).exists();
    }

    // ---- run -------------------------------------------------------------------------------

    @Test
    void runRefusesAStepWithAPathOutsideTheRoots() throws Exception {
        Path pipeline = Files.writeString(root.resolve("p.json"), "{\"steps\":[{\"id\":\"s1\",\"command\":\"convert\","
                + "\"input\":" + MAPPER.writeValueAsString(outside.resolve("secret.ttl").toString())
                + ",\"output\":\"r.ttl\"}]}");

        int exit = new CommandLine(new RunCommand()).execute(pipeline.toString());

        assertThat(exit).isNotEqualTo(ExitCode.OK);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("s1").contains("outside the allowed");
        assertThat(root.resolve("r.ttl")).doesNotExist();
    }

    @Test
    void runResolvesRelativeStepPathsAgainstThePipelineFolder() throws Exception {
        Path pipeline = Files.writeString(root.resolve("p.json"),
                "{\"steps\":[{\"id\":\"s1\",\"command\":\"convert\",\"input\":\"in.ttl\",\"output\":\"r.ttl\"}]}");

        int exit = new CommandLine(new RunCommand()).execute(pipeline.toString());

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(ExitCode.OK);
        assertThat(root.resolve("r.ttl")).exists();
    }

    @Test
    void runAcceptsAnExtraRoot() throws Exception {
        Path pipeline = Files.writeString(root.resolve("p.json"), "{\"steps\":[{\"id\":\"s1\",\"command\":\"convert\","
                + "\"input\":" + MAPPER.writeValueAsString(outside.resolve("secret.ttl").toString())
                + ",\"output\":\"r.ttl\"}]}");

        int exit = new CommandLine(new RunCommand()).execute("--root", root.toString(), "--read-root",
                outside.toString(), pipeline.toString());

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(ExitCode.OK);
        assertThat(root.resolve("r.ttl")).exists();
    }

    @Test
    void directCliUseIsUnchanged() throws Exception {
        int exit = new CommandLine(new eu.griddigit.CimPal.cli.CimPalCli()).execute("convert",
                "--input", outside.resolve("secret.ttl").toString(), "--output", outside.resolve("d.ttl").toString());

        assertThat(exit).isEqualTo(ExitCode.OK);
        assertThat(outside.resolve("d.ttl")).exists();
    }
}
