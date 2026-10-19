/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client identity, ownership and quotas of the {@code /v1} API (DEP-13), on a real
 * {@link ServeServer} with a named token file: a client never sees another client's jobs or
 * uploads (404, exactly as for an id that doesn't exist), an admin sees all of them, and each
 * client's quotas hold without holding back the others.
 *
 * <p>Runs on every {@link TestStores job store} (DEP-11).
 */
@ParameterizedClass
@EnumSource(TestStores.class)
class ClientIsolationTest {

    /** The job store the server runs on (DEP-11): every test runs on each. */
    @Parameter
    TestStores store;

    private static final String ALICE = "alice-token-0123456789abcdef0123456789abcdef";
    private static final String BOB = "bob-token-0123456789abcdef0123456789abcdef012";
    private static final String OPS = "ops-token-0123456789abcdef0123456789abcdef012";
    private static final String WRONG = "wrong-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The responses in the OpenAPI document this class exercises: {@code "METHOD template status"}. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FINISHED = new AtomicInteger();

    /** True for a documented response that this class, not JobApiTest or FileExchangeApiTest, exercises. */
    static boolean covers(String response) {
        return response.endsWith(" 429") || response.equals("GET /v1/jobs 403");
    }

    @TempDir
    Path tempDir;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private volatile boolean block;
    private ServeServer server;

    @AfterEach
    void stop() {
        FINISHED.incrementAndGet();
        release.countDown();
        if (server != null) {
            server.close();
        }
        if (server != null && server.jobsForTest() instanceof JdbcJobStore jdbc) {
            jdbc.close();
        }
    }

    // ---- set-up ------------------------------------------------------------------------------

    private void start(UnaryOperator<ServeServer.Config.Builder> settings) throws Exception {
        Path tokens = Files.writeString(tempDir.resolve("tokens"),
                "# clients of the test\nalice:client:" + ALICE + "\nbob:client:" + BOB + "\nops:admin:" + OPS + "\n");
        Path work = Files.createDirectories(tempDir.resolve("work"));
        ServeServer.Config.Builder config = ServeServer.Config.builder().host("127.0.0.1").port(0)
                .tokens(new TokenSource.FileTokens(tokens, Duration.ofSeconds(60), clock, warning -> { }))
                .maxBodyBytes(4096).queueSize(4).requestTimeout(Duration.ofSeconds(30)).workRoot(work)
                .metrics(true).log(ServeLog.json(new PrintStream(logBytes, true, StandardCharsets.UTF_8), clock));
        server = ServeServer.start(store.apply(settings.apply(config), clock).build(), runner(),
                ServeServer.RequestCheck.NONE, () -> { },
                clock);
    }

    /** Writes one output for a job with an upload; waits for {@link #release} while {@link #block} is set. */
    private ServeServer.CommandRunner runner() {
        return new ServeServer.CommandRunner() {
            @Override
            public ServeServer.CommandResult run(String command, Path configFile) throws Exception {
                if (block) {
                    release.await(30, TimeUnit.SECONDS);
                }
                return new ServeServer.CommandResult(0, "{\"ok\":true}");
            }

            @Override
            public ServeServer.CommandResult run(String command, Path configFile, WorkArea.JobWorkspace ws)
                    throws Exception {
                int outBytes = JSON.readTree(configFile.toFile()).path("outBytes").asInt(4);
                Files.writeString(ws.outDir().resolve("report.txt"), "d".repeat(outBytes));
                return run(command, configFile);
            }
        };
    }

    private static ClientQuotas.Settings quotas(String json) {
        return ClientQuotas.parse(JSON.readTree(json));
    }

    // ---- HTTP helpers ------------------------------------------------------------------------

    private HttpResponse<String> send(String token, String method, String path, byte[] body, String contentType)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return send(token, "GET", path, null, null);
    }

    private HttpResponse<String> submit(String token, String body) throws Exception {
        return send(token, "POST", "/v1/jobs", body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private String submitted(String token, String body) throws Exception {
        HttpResponse<String> response = submit(token, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JSON.readTree(response.body()).path("jobId").asString();
    }

    private String upload(String token, byte[] zip) throws Exception {
        HttpResponse<String> response = send(token, "POST", "/v1/uploads", zip, "application/zip");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).path("uploadId").asString();
    }

    private String status(String token, String jobId) throws Exception {
        return JSON.readTree(get(token, "/v1/jobs/" + jobId).body()).path("status").asString();
    }

    private void waitForStatus(String token, String jobId, String status) throws Exception {
        waitUntil(() -> {
            try {
                return status(token, jobId).equals(status);
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content);
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Incompressible, so the archive is about as large as its content. */
    private static byte[] randomZip(int bytes) throws Exception {
        byte[] content = new byte[bytes];
        new Random(bytes).nextBytes(content);
        return zip("random.bin", content);
    }

    private static List<String> jobIds(HttpResponse<String> list) {
        List<String> ids = new ArrayList<>();
        JSON.readTree(list.body()).path("jobs").forEach(job -> ids.add(job.path("jobId").asString()));
        return ids;
    }

    // ---- ownership ---------------------------------------------------------------------------

    @Test
    void anotherClientsJobAndUploadAnswer404OnEveryRouteAndAnAdminSeesThem() throws Exception {
        start(config -> config);
        String uploadId = upload(ALICE, zip("models/eq.ttl", "<urn:a> <urn:p> \"x\" .".getBytes(StandardCharsets.UTF_8)));
        String jobId = submitted(ALICE, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\"}");
        waitForStatus(ALICE, jobId, "succeeded");
        String job = "/v1/jobs/" + jobId;
        String missing = "/v1/jobs/" + UUID.randomUUID();
        String upload = "/v1/uploads/" + uploadId;
        String missingUpload = "/v1/uploads/" + UUID.randomUUID();

        Map<String, String> jobRoutes = Map.of(
                "GET " + job, "GET " + missing,
                "GET " + job + "/result", "GET " + missing + "/result",
                "GET " + job + "/log", "GET " + missing + "/log",
                "GET " + job + "/files", "GET " + missing + "/files",
                "GET " + job + "/files/report.txt", "GET " + missing + "/files/report.txt",
                "GET " + job + "/archive", "GET " + missing + "/archive",
                "DELETE " + job, "DELETE " + missing,
                "GET " + upload, "GET " + missingUpload,
                "DELETE " + upload, "DELETE " + missingUpload);
        for (Map.Entry<String, String> route : jobRoutes.entrySet()) {
            String[] own = route.getKey().split(" ", 2);
            String[] none = route.getValue().split(" ", 2);
            HttpResponse<String> asBob = send(BOB, own[0], own[1], null, null);
            HttpResponse<String> nothing = send(BOB, none[0], none[1], null, null);

            // Exactly as an id that doesn't exist: the answer tells nothing about the job or upload.
            assertThat(asBob.statusCode()).as(route.getKey()).isEqualTo(404);
            assertThat(asBob.body()).as(route.getKey()).isEqualTo(nothing.body()).doesNotContain(jobId, uploadId);
        }
        HttpResponse<String> foreignUpload = submit(BOB, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\"}");
        assertThat(foreignUpload.statusCode()).as(foreignUpload.body()).isEqualTo(404);
        assertThat(JSON.readTree(foreignUpload.body()).path("detail").asString()).startsWith("No such upload");

        // Nothing changed for the owner, and an admin sees everything.
        assertThat(get(ALICE, upload).statusCode()).isEqualTo(200);
        for (String path : List.of(job, job + "/result", job + "/log", job + "/files", job + "/files/report.txt",
                job + "/archive", upload)) {
            assertThat(get(ALICE, path).statusCode()).as("alice " + path).isEqualTo(200);
            assertThat(get(OPS, path).statusCode()).as("ops " + path).isEqualTo(200);
        }
        assertThat(JSON.readTree(get(OPS, job).body()).path("owner").asString()).isEqualTo("alice");
        assertThat(send(OPS, "DELETE", upload, null, null).statusCode()).isEqualTo(204);
    }

    @Test
    void anotherClientCantCancelAQueuedJobButAnAdminCan() throws Exception {
        start(config -> config);
        block = true;
        String running = submitted(ALICE, "{\"command\":\"validate\"}");
        waitForStatus(ALICE, running, "running");
        String queued = submitted(ALICE, "{\"command\":\"validate\"}");

        String missing = "/v1/jobs/" + UUID.randomUUID();
        // Checks of the job's state (409 not finished, not cancellable) come after the owner check.
        for (String id : List.of(running, queued)) {
            for (String[] route : new String[][] {{"DELETE", ""}, {"GET", "/result"}, {"GET", "/files"},
                    {"GET", "/archive"}, {"GET", ""}}) {
                HttpResponse<String> asBob = send(BOB, route[0], "/v1/jobs/" + id + route[1], null, null);
                assertThat(asBob.statusCode()).as(route[0] + route[1]).isEqualTo(404);
                assertThat(asBob.body()).isEqualTo(send(BOB, route[0], missing + route[1], null, null).body());
            }
        }
        assertThat(status(ALICE, queued)).isEqualTo("queued");
        HttpResponse<String> cancelled = send(OPS, "DELETE", "/v1/jobs/" + queued, null, null);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(status(ALICE, queued)).isEqualTo("cancelled");
        release.countDown();
        waitForStatus(ALICE, running, "succeeded");
    }

    @Test
    void aClientListsOnlyItsOwnJobsAndAnAdminFiltersByOwner() throws Exception {
        start(config -> config);
        String a1 = submitted(ALICE, "{\"command\":\"validate\"}");
        String a2 = submitted(ALICE, "{\"command\":\"validate\"}");
        String b1 = submitted(BOB, "{\"command\":\"validate\"}");
        for (String id : List.of(a1, a2)) {
            waitForStatus(ALICE, id, "succeeded");
        }
        waitForStatus(BOB, b1, "succeeded");

        assertThat(jobIds(get(ALICE, "/v1/jobs"))).containsExactlyInAnyOrder(a1, a2);
        assertThat(jobIds(get(ALICE, "/v1/jobs?owner=alice"))).containsExactlyInAnyOrder(a1, a2);
        assertThat(jobIds(get(BOB, "/v1/jobs"))).containsExactly(b1);
        assertThat(jobIds(get(OPS, "/v1/jobs"))).containsExactlyInAnyOrder(a1, a2, b1);
        assertThat(jobIds(get(OPS, "/v1/jobs?owner=bob"))).containsExactly(b1);
        HttpResponse<String> foreign = get(ALICE, "/v1/jobs?owner=bob");
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).doesNotContain(b1);
        SEEN.add("GET /v1/jobs 403");
    }

    @Test
    void shutdownAndMetricsNeedAnAdmin() throws Exception {
        start(config -> config);

        assertThat(get(ALICE, "/metrics").statusCode()).isEqualTo(403);
        assertThat(send(ALICE, "POST", "/shutdown", "{}".getBytes(StandardCharsets.UTF_8), "application/json")
                .statusCode()).isEqualTo(403);
        assertThat(get(ALICE, "/commands").statusCode()).isEqualTo(200);
        assertThat(get(OPS, "/metrics").statusCode()).isEqualTo(200);
        assertThat(server.readiness()).isEqualTo("ready");
    }

    // ---- quotas ------------------------------------------------------------------------------

    @Test
    void aClientAtMaxQueuedGets429WhileAnotherStillSubmits() throws Exception {
        start(config -> config.workers(1).clientQuotas(quotas("{\"default\":{\"maxQueued\":1,\"maxRunning\":1}}")));
        block = true;
        String first = submitted(ALICE, "{\"command\":\"validate\"}");
        waitForStatus(ALICE, first, "running");
        submitted(ALICE, "{\"command\":\"validate\"}");

        HttpResponse<String> refused = submit(ALICE, "{\"command\":\"validate\"}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(refused.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
        assertThat(JSON.readTree(refused.body()).path("title").asString()).isEqualTo("Too Many Requests");
        SEEN.add("POST /v1/jobs 429");
        assertThat(jobIds(get(ALICE, "/v1/jobs"))).hasSize(2);

        submitted(BOB, "{\"command\":\"validate\"}");
        release.countDown();
    }

    @Test
    void maxRunningLetsAnotherClientsJobOvertakeTheWaitingOnes() throws Exception {
        start(config -> config.workers(2).clientQuotas(quotas("{\"default\":{\"maxRunning\":1}}")));
        block = true;
        String a1 = submitted(ALICE, "{\"command\":\"validate\"}");
        waitForStatus(ALICE, a1, "running");
        String a2 = submitted(ALICE, "{\"command\":\"validate\"}");
        String b1 = submitted(BOB, "{\"command\":\"validate\"}");

        waitForStatus(BOB, b1, "running"); // submitted after a2, started before it
        assertThat(status(ALICE, a2)).isEqualTo("queued");

        release.countDown();
        for (String id : List.of(a1, a2)) {
            waitForStatus(ALICE, id, "succeeded");
        }
        waitForStatus(BOB, b1, "succeeded");
        assertThat(server.schedulerForTest().pendingForTest()).isZero();
    }

    @Test
    void perClientUploadAndWorkLimitsHold() throws Exception {
        start(config -> config.clientQuotas(quotas(
                "{\"clients\":{\"alice\":{\"maxUploadBytes\":9000},\"bob\":{\"maxWorkBytes\":20000}}}")));
        byte[] large = randomZip(10_000);

        HttpResponse<String> tooLarge = send(ALICE, "POST", "/v1/uploads", large, "application/zip");
        assertThat(tooLarge.statusCode()).as(tooLarge.body()).isEqualTo(413);
        assertThat(tooLarge.body()).contains("maxUploadBytes");
        upload(OPS, large); // the server's own limit is far larger

        upload(BOB, large); // about 14 kB charged: 10 kB and the per-entry overhead
        HttpResponse<String> full = send(BOB, "POST", "/v1/uploads", large, "application/zip");
        assertThat(full.statusCode()).as(full.body()).isEqualTo(507);
        assertThat(full.body()).contains("maxWorkBytes");
        upload(OPS, large);
        assertThat(server.workAreaForTest().usedBytes("bob")).isPositive().isLessThanOrEqualTo(20_000);
    }

    @Test
    void requestsPerMinuteGives429ToThatClientOnly() throws Exception {
        start(config -> config.clientQuotas(quotas("{\"clients\":{\"alice\":{\"requestsPerMinute\":2}}}")));

        assertThat(get(ALICE, "/v1/jobs").statusCode()).isEqualTo(200);
        assertThat(get(ALICE, "/v1/jobs").statusCode()).isEqualTo(200);
        HttpResponse<String> limited = get(ALICE, "/v1/jobs");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("30");
        assertThat(get(BOB, "/v1/jobs").statusCode()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(30)); // one request's worth refilled
        assertThat(get(ALICE, "/v1/jobs").statusCode()).isEqualTo(200);
        assertThat(get(ALICE, "/v1/jobs").statusCode()).isEqualTo(429);
    }

    @Test
    void anotherClientsUploadInUseAnswers404NotConflict() throws Exception {
        start(config -> config);
        block = true;
        String uploadId = upload(ALICE, zip("a.ttl", "x".getBytes(StandardCharsets.UTF_8)));
        String jobId = submitted(ALICE, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\"}");
        waitForStatus(ALICE, jobId, "running");

        HttpResponse<String> asBob = send(BOB, "DELETE", "/v1/uploads/" + uploadId, null, null);
        assertThat(asBob.statusCode()).isEqualTo(404);
        assertThat(asBob.body()).isEqualTo(send(BOB, "DELETE", "/v1/uploads/" + UUID.randomUUID(), null, null).body());
        assertThat(send(ALICE, "DELETE", "/v1/uploads/" + uploadId, null, null).statusCode()).isEqualTo(409);
        release.countDown();
        waitForStatus(ALICE, jobId, "succeeded");
    }

    @Test
    void jobOutputsCountAgainstMaxWorkBytes() throws Exception {
        start(config -> config.clientQuotas(quotas("{\"clients\":{\"bob\":{\"maxWorkBytes\":20000}}}")));
        String uploadId = upload(BOB, randomZip(10_000)); // about 14 kB charged
        String first = submitted(BOB, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId
                + "\",\"config\":{\"outBytes\":8000}}");
        waitForStatus(BOB, first, "succeeded");
        assertThat(server.workAreaForTest().usedBytes("bob")).isGreaterThanOrEqualTo(20_000);

        HttpResponse<String> full = submit(BOB, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\"}");
        assertThat(full.statusCode()).as(full.body()).isEqualTo(507);
        assertThat(full.body()).contains("maxWorkBytes");
        String alices = upload(ALICE, randomZip(10_000));
        submitted(ALICE, "{\"command\":\"sparql\",\"inputUpload\":\"" + alices + "\"}");
    }

    @Test
    void everyRouteWithATokenAnswers429OnceTheClientsRequestsAreUsedUp() throws Exception {
        start(config -> config.clientQuotas(quotas("{\"clients\":{\"alice\":{\"requestsPerMinute\":1}}}")));
        assertThat(get(ALICE, "/v1/jobs").statusCode()).isEqualTo(200);
        String id = UUID.randomUUID().toString();

        for (String route : ServeServer.V1_ROUTES) {
            if (route.equals("GET /v1/health") || route.equals("GET /v1/openapi.json")) {
                continue; // no token, no client
            }
            String[] parts = route.split(" ", 2);
            String path = parts[1].replace("{jobId}", id).replace("{uploadId}", id).replace("{path}", "a.txt");
            boolean post = parts[0].equals("POST");
            HttpResponse<String> response = send(ALICE, parts[0], path, post ? "{}".getBytes(StandardCharsets.UTF_8) : null,
                    post ? (path.equals("/v1/uploads") ? "application/zip" : "application/json") : null);

            assertThat(response.statusCode()).as(route).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After")).as(route).hasValue("60");
            SEEN.add(route + " 429");
        }
        assertThat(get(BOB, "/v1/jobs").statusCode()).isEqualTo(200);
    }

    // ---- attribution -------------------------------------------------------------------------

    @Test
    void logsAndMetricsCarryTheClientNameAndNeverAToken() throws Exception {
        start(config -> config);
        String uploadId = upload(ALICE, zip("a.ttl", "x".getBytes(StandardCharsets.UTF_8)));
        String jobId = submitted(ALICE, "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\"}");
        waitForStatus(ALICE, jobId, "succeeded");
        assertThat(get(BOB, "/v1/jobs/" + jobId).statusCode()).isEqualTo(404);
        assertThat(send(BOB, "POST", "/validate", "{}".getBytes(StandardCharsets.UTF_8), "application/json")
                .statusCode()).isEqualTo(200);
        String b1 = submitted(BOB, "{\"command\":\"validate\"}");
        waitForStatus(BOB, b1, "succeeded");
        assertThat(get(WRONG, "/v1/jobs").statusCode()).isEqualTo(401);

        String metrics = get(OPS, "/metrics").body();
        String log = logBytes.toString(StandardCharsets.UTF_8);

        assertThat(metrics).contains("cimpal_jobs_finished_total{client=\"alice\",status=\"succeeded\"} 1\n",
                "cimpal_requests_total{client=\"bob\",command=\"validate\",outcome=\"ok\"} 1\n");
        List<JsonNode> events = log.lines().map(JSON::readTree).toList();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.path("event").asString()).isEqualTo("job.queued");
            assertThat(e.path("client").asString()).isEqualTo("alice");
        }).anySatisfy(e -> {
            assertThat(e.path("event").asString()).isEqualTo("upload.received");
            assertThat(e.path("client").asString()).isEqualTo("alice");
        }).anySatisfy(e -> {
            assertThat(e.path("event").asString()).isEqualTo("request.rejected");
            assertThat(e.path("status").asInt()).isEqualTo(404);
            assertThat(e.path("client").asString()).isEqualTo("bob");
        }).anySatisfy(e -> {
            assertThat(e.path("event").asString()).isEqualTo("command.finished");
            assertThat(e.path("client").asString()).isEqualTo("bob");
        });
        for (String token : List.of(ALICE, BOB, OPS, WRONG)) {
            assertThat(log).doesNotContain(token);
            assertThat(metrics).doesNotContain(token);
        }
    }

    // ---- the spec lists only what is tested --------------------------------------------------

    @AfterAll
    static void everyClientResponseInTheSpecIsExercised() throws Exception {
        long tests = java.util.Arrays.stream(ClientIsolationTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        // Only meaningful when the whole class ran, not for a single selected test.
        org.junit.jupiter.api.Assumptions.assumeTrue(FINISHED.get() == tests * TestStores.values().length, "not all tests of the class ran");
        JsonNode spec;
        try (InputStream in = ClientIsolationTest.class.getResourceAsStream("/openapi/cimpal-v1.json")) {
            spec = JSON.readTree(in);
        }
        Set<String> expected = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    String key = op.getKey().toUpperCase() + " " + path.getKey() + " " + response.getKey();
                    if (covers(key)) {
                        expected.add(key);
                    }
                }
            }
        }
        assertThat(expected).isNotEmpty();
        assertThat(SEEN).as("every client response in the OpenAPI document is exercised").containsAll(expected);
    }

    @Test
    void idempotencyKeysArePerClient() throws Exception {
        start(config -> config);
        String body = "{\"command\":\"validate\"}";
        HttpResponse<String> alice = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                        + "/v1/jobs")).header("Authorization", "Bearer " + ALICE).header("Content-Type", "application/json")
                .header("Idempotency-Key", "same").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> bob = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                        + "/v1/jobs")).header("Authorization", "Bearer " + BOB).header("Content-Type", "application/json")
                .header("Idempotency-Key", "same").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(alice.statusCode()).isEqualTo(202);
        assertThat(bob.statusCode()).as("bob's key is his own: a new job, never alice's").isEqualTo(202);
        assertThat(JSON.readTree(bob.body()).path("jobId").asString())
                .isNotEqualTo(JSON.readTree(alice.body()).path("jobId").asString());
        assertThat(JSON.readTree(bob.body()).path("owner").asString()).isEqualTo("bob");
    }
}
