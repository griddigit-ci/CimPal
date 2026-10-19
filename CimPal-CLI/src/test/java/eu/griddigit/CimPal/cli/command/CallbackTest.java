/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completion callbacks of {@code /v1} jobs (DEP-14, D-19): refused without an allowlist match and
 * for every kind of non-public target (SSRF), signed, retried with backoff, never following a
 * redirect, never carrying the result or a token. Runs on every job store.
 */
@ParameterizedClass
@EnumSource(TestStores.class)
class CallbackTest {

    @Parameter
    TestStores store;

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final String SECRET = "callback-secret-0123456789abcdef0123456789";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private ServeServer server;
    private HttpServer receiver;
    /** What the receiver got: path, headers' signature, body. */
    private final List<Map<String, String>> received = Collections.synchronizedList(new ArrayList<>());
    /** The receiver's answers, one per request; 204 once they run out. */
    private final ConcurrentLinkedQueue<Integer> answers = new ConcurrentLinkedQueue<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
            if (server.jobsForTest() instanceof JdbcJobStore jdbc) {
                jdbc.close();
            }
        }
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    private void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        receiver.createContext("/hooks/slow", exchange -> {
            // Headers at once, then a body that never ends: a receiver holding the connection.
            received.add(Map.of("path", "/hooks/slow", "signature", "", "contentType", "", "body", ""));
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 300; i++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                // the server gave up: that is the point
            } finally {
                exchange.close();
            }
        });
        receiver.createContext("/", exchange -> {
            try (exchange) {
                received.add(Map.of("path", exchange.getRequestURI().getPath(),
                        "signature", String.valueOf(exchange.getRequestHeaders().getFirst("CimPal-Signature")),
                        "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                        "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Integer status = answers.poll();
                int code = status == null ? 204 : status;
                if (code == 302) {
                    exchange.getResponseHeaders().set("Location", "/hooks/redirected");
                }
                exchange.sendResponseHeaders(code, -1);
            }
        });
        receiver.start();
    }

    private String hooks() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hooks/";
    }

    /** A server whose callbacks may go to {@code allow}; loopback http allowed when {@code forTests}. */
    private void start(String allow, boolean withSecret, boolean forTests) throws Exception {
        ServeServer.Config.Builder config = ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                .maxBodyBytes(8192)
                .clientQuotas(ClientQuotas.parse(JSON.readTree(allow == null ? "{}"
                        : "{\"default\":{\"callbackAllow\":[\"" + allow + "\"]}}")));
        if (withSecret) {
            config.callbackSecrets(new CallbackSecrets(Map.of("default", SECRET.getBytes(StandardCharsets.UTF_8))));
        }
        if (forTests) {
            config.callbacksForTests(Duration.ofMillis(30)).callbackAttemptTimeout(Duration.ofMillis(700));
        }
        server = ServeServer.start(store.apply(config, clock).build(),
                (command, configFile) -> new ServeServer.CommandResult(1, "{\"secretResult\":\"do not send\"}"),
                ServeServer.RequestCheck.NONE, () -> { }, clock);
    }

    private HttpResponse<String> submit(String callbackUrl) throws Exception {
        String body = "{\"command\":\"validate\",\"callback\":{\"url\":\"" + callbackUrl + "\"}}";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs"))
                        .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode job(String id) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs/" + id))
                .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void waitForCallbackState(String id, String state) throws InterruptedException {
        waitUntil(() -> {
            try {
                return state.equals(job(id).path("callback").path("state").asString());
            } catch (Exception e) {
                return false;
            }
        });
    }

    // ---- refusals (SSRF) -----------------------------------------------------------------------

    @Test
    void withoutASecretOrAnAllowlistCallbacksAreRefused() throws Exception {
        start(null, false, false);
        HttpResponse<String> noSecret = submit("https://hooks.example.com/cimpal/x");
        assertThat(noSecret.statusCode()).isEqualTo(400);
        assertThat(noSecret.body()).contains("not enabled");
        server.close();

        start(null, true, false);
        HttpResponse<String> noAllow = submit("https://hooks.example.com/cimpal/x");
        assertThat(noAllow.statusCode()).isEqualTo(400);
        assertThat(noAllow.body()).contains("allowed prefixes");
    }

    @Test
    void onlyHttpsUrlsUnderAnAllowedPrefixAreAccepted() throws Exception {
        start("https://hooks.example.com/cimpal/", true, false);
        // All refused before any DNS lookup.
        for (String url : new String[] {
                "https://hooks.example.com.evil.example/cimpal/x", // a longer host that starts the same
                "https://hooks.example.com/other/x",
                "http://hooks.example.com/cimpal/x",
                "https://user:pw@hooks.example.com/cimpal/x",
                "https://hooks.example.com/cimpal/x#frag",
                "file:///etc/passwd",
                "not a url"}) {
            HttpResponse<String> r = submit(url);
            assertThat(r.statusCode()).as(url).isEqualTo(400);
        }
    }

    @Test
    void nonPublicTargetsAreRefusedEvenWhenAllowlisted() throws Exception {
        for (String host : new String[] {"127.0.0.1", "10.0.0.5", "192.168.1.10", "169.254.169.254", "[::1]",
                "[fd00::1]", "100.64.0.1"}) {
            start("https://" + host + "/hooks/", true, false);
            HttpResponse<String> r = submit("https://" + host + "/hooks/x");
            assertThat(r.statusCode()).as(host).isEqualTo(400);
            assertThat(r.body()).as(host).contains("public addresses");
            server.close();
            server = null;
        }
    }

    @Test
    void dotSegmentsAndEncodedSeparatorsCantStepOutOfAPrefix() throws Exception {
        startReceiver();
        start(hooks() + "tenant-a/", true, true);
        String base = hooks() + "tenant-a/";
        for (String url : new String[] {base + "../tenant-b/x", base + "./x", base + "%2e%2e/tenant-b/x",
                base + "x%2f..%2fy", base + "a%5cb"}) {
            HttpResponse<String> r = submit(url);
            assertThat(r.statusCode()).as(url).isEqualTo(400);
        }
        assertThat(submit(base + "done").statusCode()).isEqualTo(202);
    }

    // ---- delivery ------------------------------------------------------------------------------

    @Test
    void theSignatureMatchesTheSdksTestVector() {
        // The same vector is in clients/python/tests/test_callbacks.py: both sides sign alike.
        String body = "{\"event\":\"job.finished\",\"jobId\":\"x\"}";
        assertThat(Callbacks.signature(SECRET.getBytes(StandardCharsets.UTF_8), 1760000000L,
                body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("t=1760000000,v1=f86cb11876d3c92366db706f4ddbd2d6717bd012809dad1050f5cf404de37555");
    }

    @Test
    void aFinishedJobCallsBackSignedAndWithoutItsResult() throws Exception {
        startReceiver();
        start(hooks(), true, true);
        HttpResponse<String> submitted = submit(hooks() + "done");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = JSON.readTree(submitted.body()).path("jobId").asString();

        waitForCallbackState(id, "delivered");

        assertThat(received).hasSize(1);
        Map<String, String> request = received.getFirst();
        assertThat(request.get("path")).isEqualTo("/hooks/done");
        assertThat(request.get("contentType")).isEqualTo("application/json");
        JsonNode body = JSON.readTree(request.get("body"));
        assertThat(body.path("jobId").asString()).isEqualTo(id);
        assertThat(body.path("status").asString()).isEqualTo("succeeded");
        assertThat(body.path("hasViolations").asBoolean()).isTrue();
        assertThat(request.get("body")).doesNotContain("secretResult", TOKEN, SECRET);
        // The receiver checks the signature with the shared secret.
        String signature = request.get("signature");
        long t = Long.parseLong(signature.substring(2, signature.indexOf(',')));
        assertThat(signature).isEqualTo(Callbacks.signature(SECRET.getBytes(StandardCharsets.UTF_8), t,
                request.get("body").getBytes(StandardCharsets.UTF_8)));
        JsonNode callback = job(id).path("callback");
        assertThat(callback.path("lastStatus").asInt()).isEqualTo(204);
        assertThat(callback.path("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void aFailedAttemptIsRetriedWithBackoffAndARedirectIsNeverFollowed() throws Exception {
        startReceiver();
        answers.add(500);
        answers.add(302);
        start(hooks(), true, true);
        String id = JSON.readTree(submit(hooks() + "retry").body()).path("jobId").asString();

        waitUntil(() -> received.size() == 1);
        Thread.sleep(200);
        assertThat(received).as("no retry before the backoff").hasSize(1);
        assertThat(job(id).path("callback").path("state").asString()).isEqualTo("pending");

        clock.advance(Callbacks.FIRST_RETRY);
        waitUntil(() -> received.size() == 2); // the 302
        clock.advance(Callbacks.FIRST_RETRY.multipliedBy(2));
        waitForCallbackState(id, "delivered");

        assertThat(received).extracting(r -> r.get("path"))
                .containsExactly("/hooks/retry", "/hooks/retry", "/hooks/retry")
                .doesNotContain("/hooks/redirected");
        assertThat(job(id).path("callback").path("attempts").asInt()).isEqualTo(3);
    }

    @Test
    void aCallbackGivesUpADayAfterItsJobEnded() throws Exception {
        startReceiver();
        for (int i = 0; i < 10; i++) {
            answers.add(503);
        }
        start(hooks(), true, true);
        String id = JSON.readTree(submit(hooks() + "down").body()).path("jobId").asString();
        waitUntil(() -> received.size() == 1);

        clock.advance(Duration.ofHours(25));

        waitForCallbackState(id, "failed");
        assertThat(job(id).path("callback").path("lastError").asString()).isEqualTo("HTTP 503");
    }

    @Test
    void aPendingCallbackIsDeliveredAfterARestart() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(store == TestStores.H2, "only a durable store keeps callbacks");
        startReceiver();
        answers.add(503);
        String url = TestStores.h2Url();
        ServeServer.Config.Builder config = ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                .clientQuotas(ClientQuotas.parse(JSON.readTree("{\"default\":{\"callbackAllow\":[\"" + hooks()
                        + "\"]}}")))
                .callbackSecrets(new CallbackSecrets(Map.of("default", SECRET.getBytes(StandardCharsets.UTF_8))))
                .callbacksForTests(Duration.ofMillis(30));
        JdbcJobStore first = TestStores.h2(url, config.build(), clock);
        server = ServeServer.start(config.jobStore(first).build(),
                (command, configFile) -> new ServeServer.CommandResult(0, "{}"), ServeServer.RequestCheck.NONE,
                () -> { }, clock);
        String id = JSON.readTree(submit(hooks() + "later").body()).path("jobId").asString();
        waitUntil(() -> received.size() == 1);
        server.close();
        first.close();

        JdbcJobStore second = TestStores.h2(url, config.build(), clock);
        server = ServeServer.start(config.jobStore(second).build(),
                (command, configFile) -> new ServeServer.CommandResult(0, "{}"), ServeServer.RequestCheck.NONE,
                () -> { }, clock);
        clock.advance(Callbacks.FIRST_RETRY);

        waitForCallbackState(id, "delivered");
        assertThat(received).hasSize(2);
        assertThat(job(id).path("callback").path("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void aReceiverThatHoldsTheConnectionDoesNotHoldBackOtherCallbacks() throws Exception {
        startReceiver();
        start(hooks(), true, true);
        String slow = JSON.readTree(submit(hooks() + "slow").body()).path("jobId").asString();
        waitUntil(() -> received.stream().anyMatch(r -> r.get("path").equals("/hooks/slow")));
        String fast = JSON.readTree(submit(hooks() + "fast").body()).path("jobId").asString();

        waitForCallbackState(fast, "delivered");
        waitUntil(() -> {
            try {
                return "timed out".equals(job(slow).path("callback").path("lastError").asString());
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void aFailedConnectionShowsNoMoreThanThat() throws Exception {
        startReceiver();
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        String prefix = "http://127.0.0.1:" + closedPort + "/hooks/";
        start(prefix, true, true);
        String id = JSON.readTree(submit(prefix + "x").body()).path("jobId").asString();

        waitUntil(() -> {
            try {
                return job(id).path("callback").path("attempts").asInt() >= 1;
            } catch (Exception e) {
                return false;
            }
        });
        // Never the exception's class: that would tell a client about the network behind an address.
        assertThat(job(id).path("callback").path("lastError").asString()).isEqualTo("connection failed");
    }
}

