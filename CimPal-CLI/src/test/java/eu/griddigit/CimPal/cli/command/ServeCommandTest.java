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
}
