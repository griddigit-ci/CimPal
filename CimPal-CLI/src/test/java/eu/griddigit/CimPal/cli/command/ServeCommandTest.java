/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
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

/** Token lifecycle of the {@code serve} command itself (SEC-1, gap G1). */
class ServeCommandTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream origOut;
    private PrintStream origErr;

    @BeforeEach
    void captureOutput() {
        origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private record Running(ServeServer server, CompletableFuture<Integer> exit) {
    }

    private Running startInBackground(Map<String, String> env, String... args) throws Exception {
        ServeCommand command = new ServeCommand();
        command.environment = env;
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(args));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);
        assertThat(server).as("server started; output: %s", output()).isNotNull();
        return new Running(server, exit);
    }

    private int runToExit(Map<String, String> env, String... args) {
        ServeCommand command = new ServeCommand();
        command.environment = env;
        command.onTerm = onTerm -> false;
        return new CommandLine(command).execute(args);
    }

    private static HttpResponse<String> shutdown(ServeServer server, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/shutdown"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void writesTheTokenFileNeverPrintsTheTokenAndRemovesTheFileOnShutdown() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tokenFile.toString());
        String token = Files.readString(tokenFile);

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(tokenFile).doesNotExist();
        assertThat(output()).contains(tokenFile.toAbsolutePath().toString()).doesNotContain(token);
    }

    @Test
    void environmentTokenIsUsedAndNoFileIsWritten() throws Exception {
        String token = "env-token-0123456789abcdef0123456789";
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--token-file", tokenFile.toString());

        assertThat(tokenFile).doesNotExist();
        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(output()).doesNotContain(token);
    }

    @Test
    void shortEnvironmentTokenIsRefused() {
        int exit = runToExit(Map.of(ServeSecurity.TOKEN_ENV, "short"), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tempDir.resolve("serve.token").toString());

        assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains(ServeSecurity.TOKEN_ENV).doesNotContain("short\n");
    }

    @Test
    void bindFailureIsExit2AndRemovesTheTokenFile() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        try (ServerSocket occupied = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int exit = runToExit(Map.of(), "--host", "127.0.0.1", "--port", Integer.toString(occupied.getLocalPort()),
                    "--token-file", tokenFile.toString());

            assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        }
        assertThat(tokenFile).doesNotExist();
    }

    @Test
    void nonLoopbackHostWithoutAllowRemoteIsExit2AndRemovesTheTokenFile() {
        Path tokenFile = tempDir.resolve("serve.token");
        int exit = runToExit(Map.of(), "--host", "0.0.0.0", "--port", "0", "--token-file", tokenFile.toString());

        assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("--allow-remote");
        assertThat(tokenFile).doesNotExist();
    }

    @Test
    void tokenFileOverwrittenByAnotherInstanceIsLeftInPlace() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tokenFile.toString());
        String token = Files.readString(tokenFile);
        Files.writeString(tokenFile, "token-of-a-second-instance");

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(Files.readString(tokenFile)).isEqualTo("token-of-a-second-instance");
    }

    private static HttpResponse<String> v1(ServeServer server, String token, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRealJobRunsThroughTheJobApiAndPathsAreCheckedAtSubmit() throws Exception {
        // DEP-5 end to end: the real serve command, path policy and sparql command.
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path model = Files.writeString(root.resolve("m.ttl"), "<urn:x:a> <urn:x:p> \"x\" .\n");
        Path outside = Files.writeString(tempDir.resolve("outside.ttl"), "<urn:x:a> <urn:x:p> \"y\" .\n");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--root", root.toString());
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        String query = "SELECT ?o WHERE { ?s ?p ?o }";

        HttpResponse<String> refused = v1(running.server(), token, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"config\":{\"models\":[" + json.writeValueAsString(outside.toString())
                        + "],\"query\":\"" + query + "\"}}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
        assertThat(running.server().jobsForTest().size()).as("a refused job is never queued").isZero();

        HttpResponse<String> submitted = v1(running.server(), token, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"label\":\"e2e\",\"config\":{\"models\":["
                        + json.writeValueAsString(model.toString()) + "],\"query\":\"" + query + "\"}}");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = json.readTree(submitted.body()).path("jobId").asString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String status;
        do {
            Thread.sleep(50);
            status = json.readTree(v1(running.server(), token, "GET", "/v1/jobs/" + id, null).body())
                    .path("status").asString();
        } while (!status.equals("succeeded") && !status.equals("failed") && System.nanoTime() < deadline);

        assertThat(status).as(output()).isEqualTo("succeeded");
        tools.jackson.databind.JsonNode result = json.readTree(v1(running.server(), token, "GET",
                "/v1/jobs/" + id + "/result", null).body());
        assertThat(result.path("rows")).hasSize(1);
        tools.jackson.databind.JsonNode log = json.readTree(v1(running.server(), token, "GET",
                "/v1/jobs/" + id + "/log", null).body());
        assertThat(log.path("lines").toString()).contains("Query returned 1 row");

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
    }

    // ---- service deployment (DEP-6) ---------------------------------------------------------

    private static final String FILE_TOKEN_A = "file-token-a-0123456789abcdef0123456789";
    private static final String FILE_TOKEN_B = "file-token-b-0123456789abcdef0123456789";

    private static int status(ServeServer server, String token, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void aTokenFileFromTheEnvironmentIsReadNeverWrittenOrDeleted() throws Exception {
        Path secret = Files.writeString(tempDir.resolve("secret"), FILE_TOKEN_A + "\n" + FILE_TOKEN_B + "\n");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_FILE_ENV, secret.toString()),
                "--host", "127.0.0.1", "--port", "0", "--metrics");

        assertThat(status(running.server(), FILE_TOKEN_A, "/commands")).isEqualTo(200);
        assertThat(status(running.server(), FILE_TOKEN_B, "/metrics")).isEqualTo(200);
        assertThat(status(running.server(), "x" + FILE_TOKEN_A, "/commands")).isEqualTo(401);
        assertThat(shutdown(running.server(), FILE_TOKEN_A).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);

        assertThat(Files.readString(secret)).isEqualTo(FILE_TOKEN_A + "\n" + FILE_TOKEN_B + "\n");
        assertThat(output()).contains("read from").doesNotContain(FILE_TOKEN_A).doesNotContain(FILE_TOKEN_B);
    }

    @Test
    void conflictingTokenSourcesRefuseToStart() throws Exception {
        Path secret = Files.writeString(tempDir.resolve("secret"), FILE_TOKEN_A + "\n");
        String envToken = "env-token-0123456789abcdef0123456789";

        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, envToken), "--port", "0",
                "--token-from-file", secret.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_FILE_ENV, secret.toString()), "--port", "0",
                "--token-from-file", secret.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(), "--port", "0", "--token-from-file", secret.toString(),
                "--token-file", tempDir.resolve("serve.token").toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(), "--port", "0", "--token-from-file",
                tempDir.resolve("missing").toString())).isEqualTo(ExitCode.INVALID_INPUT);

        assertThat(output()).contains("not both").contains("can't be combined").doesNotContain(envToken)
                .doesNotContain(FILE_TOKEN_A);
        assertThat(secret).exists();
        assertThat(tempDir.resolve("serve.token")).doesNotExist();
    }

    @Test
    void badDeploymentOptionsAreExit2() {
        String token = "env-token-0123456789abcdef0123456789";
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--allowed-host", "https://cimpal.example.com")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--log-format", "xml")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--shutdown-grace", "PT0S")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("--allowed-host").contains("--log-format").contains("--shutdown-grace");
    }

    @Test
    void jsonLogFormatWritesOnlyJsonLinesOnStderrAndNothingOnStdout() throws Exception {
        String token = "env-token-0123456789abcdef0123456789";
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--log-format", "json", "--allowed-host", "cimpal.example.com");
        assertThat(status(running.server(), "wrong-" + token, "/commands")).isEqualTo(401);
        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);

        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        String stderr = err.toString(StandardCharsets.UTF_8);
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        java.util.List<String> events = new java.util.ArrayList<>();
        for (String line : stderr.split("\\R")) {
            events.add(json.readTree(line).path("event").asString());
        }
        assertThat(events).contains("server.started", "request.rejected", "server.stopping", "server.stopped");
        assertThat(stderr).contains("cimpal.example.com").doesNotContain(token);
    }
}
