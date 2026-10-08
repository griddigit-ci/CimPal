/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SIGTERM drains {@code serve} (DEP-6): a forked JVM ({@link ServeSignalItMain}) gets SIGTERM
 * while a job runs and another waits. {@code Process.destroy()} sends SIGTERM on Linux and macOS
 * only (on Windows it kills the process), so this runs there, in CI on Ubuntu.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ServeSignalIT {

    private static final String TOKEN = "it-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LISTENING = Pattern.compile("listening on http://127\\.0\\.0\\.1:(\\d+)");

    @TempDir
    Path tempDir;

    private Process process;
    private final List<String> stderr = new ArrayList<>();

    @AfterEach
    void kill() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private int startServe(long jobMillis, String grace) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "-Dit.jobMillis=" + jobMillis, "-Dit.dir=" + tempDir, ServeSignalItMain.class.getName(),
                "--host", "127.0.0.1", "--port", "0", "--root", tempDir.toString(),
                "--shutdown-grace", grace);
        pb.environment().put(ServeSecurity.TOKEN_ENV, TOKEN);
        process = pb.start();
        CompletableFuture<Integer> port = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> read(process.inputReader(), port, null));
        Thread.ofVirtual().start(() -> read(new BufferedReader(new InputStreamReader(process.getErrorStream(),
                StandardCharsets.UTF_8)), null, stderr));
        // Fail fast, with the child's stderr, if it exits instead of listening.
        process.onExit().thenRun(() -> port.completeExceptionally(new IllegalStateException(
                "serve exited with " + process.exitValue() + " before listening; stderr:\n" + String.join("\n", stderr))));
        return port.get(60, TimeUnit.SECONDS);
    }

    private static void read(BufferedReader in, CompletableFuture<Integer> port, List<String> lines) {
        try (in) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (lines != null) {
                    synchronized (lines) {
                        lines.add(line);
                    }
                }
                Matcher m = LISTENING.matcher(line);
                if (port != null && m.find()) {
                    port.complete(Integer.parseInt(m.group(1)));
                }
            }
        } catch (Exception ignored) {
            // the process ended
        }
    }

    private static HttpResponse<String> call(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN);
        request = body == null ? request.method(method, HttpRequest.BodyPublishers.noBody())
                : request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String submit(int port, String query) throws Exception {
        HttpResponse<String> response = call(port, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"config\":{\"query\":\"" + query + "\"}}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JSON.readTree(response.body()).path("jobId").asString();
    }

    private static void awaitRunning(int port, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!JSON.readTree(call(port, "GET", "/v1/jobs/" + id, null).body()).path("status").asString().equals("running")) {
            assertThat(System.nanoTime()).as("job running within 30 s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void sigtermLetsTheRunningJobFinishCancelsTheQueuedOneAndExits0() throws Exception {
        int port = startServe(3000, "PT60S");
        String running = submit(port, "first");
        awaitRunning(port, running);
        submit(port, "second");

        process.destroy(); // SIGTERM

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("exited").isTrue();
        assertThat(process.exitValue()).as(String.join("\n", stderr)).isEqualTo(0);
        assertThat(tempDir.resolve("first.done")).as("the running job finished").exists();
        assertThat(tempDir.resolve("second.done")).as("the queued job never ran").doesNotExist();
    }

    @Test
    void aJobOutlivingTheGraceIsExit3WithALogLine() throws Exception {
        int port = startServe(60_000, "PT2S");
        String running = submit(port, "slow");
        awaitRunning(port, running);

        process.destroy(); // SIGTERM

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("exited within the grace and some").isTrue();
        assertThat(process.exitValue()).isEqualTo(3);
        Thread.sleep(200);
        synchronized (stderr) {
            assertThat(String.join("\n", stderr)).contains("--shutdown-grace");
        }
        assertThat(Files.exists(tempDir.resolve("slow.done"))).isFalse();
    }
}
