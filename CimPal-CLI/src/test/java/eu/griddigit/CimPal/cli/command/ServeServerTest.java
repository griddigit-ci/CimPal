/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security behaviour of the {@code serve} HTTP server (SEC-1, gaps G1 and G4). The server runs
 * in-process on an ephemeral loopback port with a stub command runner, so no CimPal command runs.
 */
class ServeServerTest {

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** {@link #rawStatus} result when the server closed the connection without answering. */
    private static final int NO_RESPONSE = -1;

    private ServeServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.close();
        }
    }

    private ServeServer.Config.Builder config() {
        return ServeServer.Config.builder()
                .host("127.0.0.1")
                .port(0)
                .token(TOKEN)
                .maxBodyBytes(1024)
                .queueSize(4)
                .requestTimeout(Duration.ofSeconds(30));
    }

    private ServeServer start(ServeServer.Config.Builder config) throws Exception {
        return start(config, (command, configFile) -> new ServeServer.CommandResult(0, "{\"command\":\"" + command + "\"}"));
    }

    private ServeServer start(ServeServer.Config.Builder config, ServeServer.CommandRunner runner) throws Exception {
        server = ServeServer.start(config.build(), runner);
        return server;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpRequest.Builder post(String path, String contentType, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return request;
    }

    private HttpRequest.Builder authorizedPost(String path, String body) {
        return post(path, "application/json", body).header("Authorization", "Bearer " + TOKEN);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a raw request so the Host header can be set (HttpClient treats Host as restricted). */
    private int rawStatus(String requestHead) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            return statusLine == null ? NO_RESPONSE : Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    // ---- token -----------------------------------------------------------------------------

    @Test
    void healthNeedsNoToken() throws Exception {
        start(config());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/health")).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
    }

    /** The version comes from the pom (CliVersion), not a literal; the Docker image tag relies on it (CI-3). */
    @Test
    void healthReportsTheReleaseVersion() throws Exception {
        start(config());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/health")).GET());

        assertThat(response.body())
                .contains("\"version\":\"CimPal CLI " + System.getProperty("cimpal.expectedVersion") + "\"");
    }

    @Test
    void commandWithoutTokenIs401AndNeverRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        start(config(), (command, file) -> {
            runs.incrementAndGet();
            return new ServeServer.CommandResult(0, "{}");
        });

        HttpResponse<String> response = send(post("/validate", "application/json", "{}"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(runs).hasValue(0);
    }

    @Test
    void wrongTokenIs401() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/validate", "application/json", "{}")
                .header("Authorization", "Bearer " + TOKEN + "x"));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void commandsListingNeedsToken() throws Exception {
        start(config());
        assertThat(send(HttpRequest.newBuilder(uri("/commands")).GET()).statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(uri("/commands")).GET()
                .header("Authorization", "Bearer " + TOKEN)).statusCode()).isEqualTo(200);
    }

    @Test
    void validRequestReachesTheRunnerAndReturnsItsJson() throws Exception {
        start(config());
        HttpResponse<String> response = send(authorizedPost("/sparql", "{}"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"command\":\"sparql\"}");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void errorResponsesNeverEchoTheToken() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/validate", "text/plain", "{}")
                .header("Authorization", "Bearer " + TOKEN));

        assertThat(response.body()).doesNotContain(TOKEN);
    }

    // ---- Host and Origin -------------------------------------------------------------------

    @Test
    void foreignHostHeaderIs403() throws Exception {
        start(config());
        int status = rawStatus("GET /health HTTP/1.1\r\nHost: evil.example:" + server.port()
                + "\r\nConnection: close\r\n\r\n");

        assertThat(status).isEqualTo(403);
    }

    @Test
    void loopbackHostWithWrongPortIs403() throws Exception {
        start(config());
        int status = rawStatus("GET /health HTTP/1.1\r\nHost: localhost:" + (server.port() + 1)
                + "\r\nConnection: close\r\n\r\n");

        assertThat(status).isEqualTo(403);
    }

    @Test
    void loopbackHostNamesWithTheRightPortAreAccepted() throws Exception {
        start(config());
        for (String host : new String[] {"localhost", "127.0.0.1", "[::1]", "LOCALHOST"}) {
            int status = rawStatus("GET /health HTTP/1.1\r\nHost: " + host + ":" + server.port()
                    + "\r\nConnection: close\r\n\r\n");
            assertThat(status).as(host).isEqualTo(200);
        }
    }

    @Test
    void foreignOriginIs403EvenWithToken() throws Exception {
        start(config());
        HttpResponse<String> response = send(authorizedPost("/validate", "{}")
                .header("Origin", "https://evil.example"));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void allowListedOriginIsAccepted() throws Exception {
        start(config().allowedOrigins(Set.of("http://localhost:3000")));
        HttpResponse<String> response = send(authorizedPost("/validate", "{}")
                .header("Origin", "http://localhost:3000"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ---- method, content type, size, paths --------------------------------------------------

    @Test
    void textPlainPostIs415() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/validate", "text/plain", "{}")
                .header("Authorization", "Bearer " + TOKEN));

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void jsonWithCharsetIsAccepted() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/validate", "application/json; charset=UTF-8", "{}")
                .header("Authorization", "Bearer " + TOKEN));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        start(config(), (command, file) -> {
            runs.incrementAndGet();
            return new ServeServer.CommandResult(0, "{}");
        });
        String body = "{\"x\":\"" + "a".repeat(2048) + "\"}";

        HttpResponse<String> response = send(authorizedPost("/validate", body));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(runs).hasValue(0);
    }

    @Test
    void getShutdownIs405AndServerKeepsRunning() throws Exception {
        start(config());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/shutdown")).GET()
                .header("Authorization", "Bearer " + TOKEN));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).statusCode()).isEqualTo(200);
    }

    @Test
    void shutdownWithoutTokenIs401AndServerKeepsRunning() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/shutdown", "application/json", ""));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(server.awaitStop(Duration.ofMillis(200))).isFalse();
    }

    @Test
    void authorizedPostShutdownStopsTheServer() throws Exception {
        start(config());
        HttpResponse<String> response = send(authorizedPost("/shutdown", ""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(server.awaitStop(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void pathsMatchExactlyNotByPrefix() throws Exception {
        start(config());
        assertThat(send(authorizedPost("/validatefoo", "{}")).statusCode()).isEqualTo(404);
        assertThat(send(authorizedPost("/validate/x", "{}")).statusCode()).isEqualTo(404);
        assertThat(send(authorizedPost("/run", "{}")).statusCode()).isEqualTo(404);
    }

    @Test
    void optionsPreflightIsRejected() throws Exception {
        start(config());
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/validate"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isIn(401, 405);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
    }

    // ---- queue and timeout -----------------------------------------------------------------

    @Test
    void fullQueueIs503() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(1), (command, file) -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });

        CompletableFuture<HttpResponse<String>> running = HTTP.sendAsync(
                authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<HttpResponse<String>> queued = HTTP.sendAsync(
                authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        waitUntil(() -> server.inFlight() == 2);

        HttpResponse<String> rejected = send(authorizedPost("/validate", "{}"));

        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).statusCode()).isEqualTo(200);
        release.countDown();
        assertThat(running.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(queued.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void slowCommandTimesOutWith504() throws Exception {
        start(config().requestTimeout(Duration.ofMillis(300)), (command, file) -> {
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });

        HttpResponse<String> response = send(authorizedPost("/validate", "{}"));

        assertThat(response.statusCode()).isEqualTo(504);
    }

    @Test
    void runnerFailureIs500WithoutDetailLeak() throws Exception {
        start(config(), (command, file) -> {
            throw new IllegalStateException("secret internal detail");
        });

        HttpResponse<String> response = send(authorizedPost("/validate", "{}"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("secret internal detail");
    }

    @Test
    void stalledConnectionsDoNotBlockHealthWhileCommandsWait() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(1), (command, file) -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });
        // Fill both command slots, then open slow connections that never finish their headers.
        HTTP.sendAsync(authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        HTTP.sendAsync(authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        waitUntil(() -> server.inFlight() == 2);
        java.util.List<Socket> stalled = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                Socket socket = new Socket("127.0.0.1", server.port());
                socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: 127.0".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                stalled.add(socket);
            }

            HttpResponse<String> health = send(HttpRequest.newBuilder(uri("/health")).timeout(Duration.ofSeconds(5)).GET());

            assertThat(health.statusCode()).isEqualTo(200);
        } finally {
            for (Socket socket : stalled) {
                socket.close();
            }
        }
    }

    @Test
    void requestReadTimeIsBoundedUnlessSetExplicitly() throws Exception {
        start(config());

        assertThat(System.getProperty(ServeServer.MAX_REQ_TIME_PROPERTY)).isNotNull();
    }

    @Test
    void timedOutQueuedRequestNeverRunsAndSlotsAreReleased() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(1).requestTimeout(Duration.ofMillis(500)), (command, file) -> {
            runs.incrementAndGet();
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });

        CompletableFuture<HttpResponse<String>> running = HTTP.sendAsync(
                authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> queued = send(authorizedPost("/validate", "{}"));

        assertThat(queued.statusCode()).isEqualTo(504);
        assertThat(running.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(504);
        release.countDown();
        waitUntil(() -> server.inFlight() == 0);
        assertThat(runs).hasValue(1);
    }

    @Test
    void shutdownDoesNotStartQueuedCommandsAndWaitsForTheRunningOne() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(1), (command, file) -> {
            runs.incrementAndGet();
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });
        HTTP.sendAsync(authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<HttpResponse<String>> queued = HTTP.sendAsync(
                authorizedPost("/validate", "{}").build(), HttpResponse.BodyHandlers.ofString());
        waitUntil(() -> server.inFlight() == 2);

        server.close();
        assertThat(server.awaitWorker(Duration.ofMillis(200))).as("running command still holds the worker").isFalse();
        release.countDown();

        assertThat(server.awaitWorker(Duration.ofSeconds(10))).isTrue();
        assertThat(runs).as("the queued command never started").hasValue(1);
        try {
            // Either the queued caller is told the server is stopping, or its connection is closed.
            assertThat(queued.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        } catch (java.util.concurrent.ExecutionException closed) {
            // connection closed by the stopping server
        }
    }

    @Test
    void healthReportsBusyAndStalledCommands() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        start(config().requestTimeout(Duration.ofMillis(300)), (command, file) -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return new ServeServer.CommandResult(0, "{}");
        });
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).body())
                .contains("\"busy\":false", "\"stalled\":false", "\"status\":\"ok\"");

        HttpResponse<String> timedOut = send(authorizedPost("/validate", "{}"));
        assertThat(timedOut.statusCode()).isEqualTo(504);

        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).body())
                .contains("\"busy\":true", "\"stalled\":true", "\"status\":\"stalled\"");
        release.countDown();
        waitUntil(() -> server.inFlight() == 0);
        assertThat(send(HttpRequest.newBuilder(uri("/health")).GET()).body()).contains("\"busy\":false");
    }

    @Test
    void chunkedBodyOverTheLimitIs413() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        start(config(), (command, file) -> {
            runs.incrementAndGet();
            return new ServeServer.CommandResult(0, "{}");
        });
        byte[] body = ("{\"x\":\"" + "a".repeat(4096) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder chunked = HttpRequest.newBuilder(uri("/validate"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)));

        HttpResponse<String> response = send(chunked);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(runs).hasValue(0);
    }

    @Test
    void postWithoutContentTypeIs415() throws Exception {
        start(config());
        HttpResponse<String> response = send(post("/validate", null, "{}").header("Authorization", "Bearer " + TOKEN));

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void edgeCaseRequestsAreRefused() throws Exception {
        start(config());
        String host = "Host: 127.0.0.1:" + server.port() + "\r\n";

        // The JDK server drops an opaque target itself; if it ever passes one on, the handler answers 404.
        assertThat(rawStatus("GET mailto:x HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).isIn(404, NO_RESPONSE);
        assertThat(rawStatus("GET /health HTTP/1.0\r\nConnection: close\r\n\r\n")).isEqualTo(403);
        assertThat(rawStatus("HEAD /health HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).isEqualTo(405);
        assertThat(rawStatus("GET /health HTTP/1.1\r\n" + host + "Origin: null\r\nConnection: close\r\n\r\n"))
                .isEqualTo(403);
        assertThat(rawStatus("GET /health HTTP/1.1\r\nHost: localhost.:" + server.port()
                + "\r\nConnection: close\r\n\r\n")).isEqualTo(403);
        assertThat(rawStatus("GET /Health HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).isEqualTo(404);
        assertThat(rawStatus("GET /health/ HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).isEqualTo(404);
        // Percent-encoding decodes to the real endpoint, which still needs the token.
        assertThat(rawStatus("GET /%63ommands HTTP/1.1\r\n" + host + "Connection: close\r\n\r\n")).isEqualTo(401);
    }

    @Test
    void operatorLimitsAreCapped() {
        ServeServer.CommandRunner runner = (c, f) -> new ServeServer.CommandResult(0, "{}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ServeServer.start(
                        config().queueSize(ServeServer.MAX_QUEUE_SIZE + 1).build(), runner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--queue-size");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ServeServer.start(
                        config().maxBodyBytes(ServeServer.MAX_BODY_BYTES + 1).build(), runner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--max-body-bytes");
    }

    // ---- bind address ----------------------------------------------------------------------

    @Test
    void nonLoopbackHostWithoutAllowRemoteIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ServeServer.start(config().host("0.0.0.0").build(),
                                (c, f) -> new ServeServer.CommandResult(0, "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--allow-remote");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(20);
        }
    }
}
