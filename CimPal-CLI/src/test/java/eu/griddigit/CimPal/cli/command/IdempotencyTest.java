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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Idempotency-Key} header on {@code POST /v1/jobs} and {@code POST /v1/uploads}
 * (DEP-14): a request repeated with the same key gives the same job or upload, never a second
 * one, so a client whose answer got lost can safely send it again. Runs on every job store.
 */
@ParameterizedClass
@EnumSource(TestStores.class)
class IdempotencyTest {

    /** The job store the server runs on. */
    @Parameter
    TestStores store;

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The documented responses this class exercises, "METHOD template status". */
    private static final Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FINISHED = new AtomicInteger();

    /** The OpenAPI responses that are this class's to exercise (the other API tests skip them). */
    static boolean covers(String response) {
        return Set.of("POST /v1/jobs 409", "POST /v1/jobs 422", "POST /v1/uploads 409", "POST /v1/uploads 422",
                "POST /v1/uploads 404").contains(response);
    }

    @TempDir
    Path tempDir;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private final AtomicInteger runs = new AtomicInteger();
    private final CountDownLatch checkGate = new CountDownLatch(1);
    private volatile boolean holdCheck;
    private ServeServer server;

    @AfterEach
    void stop() {
        FINISHED.incrementAndGet();
        checkGate.countDown();
        if (server != null) {
            server.close();
            if (server.jobsForTest() instanceof JdbcJobStore jdbc) {
                jdbc.close();
            }
        }
    }

    private void start() throws Exception {
        Files.createDirectories(tempDir.resolve("work"));
        ServeServer.Config.Builder config = ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                .maxBodyBytes(4096).queueSize(16).workRoot(tempDir.resolve("work"));
        // The path check runs after the key is taken and before the job exists: holding it keeps
        // a request "in progress".
        ServeServer.RequestCheck check = (command, body) -> {
            if (holdCheck) {
                try {
                    checkGate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return body;
        };
        server = ServeServer.start(store.apply(config, clock).build(), (command, configFile) -> {
            runs.incrementAndGet();
            return new ServeServer.CommandResult(0, "{}");
        }, check, () -> { }, clock);
    }

    private HttpResponse<String> post(String path, String key, byte[] body, String type) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        HttpResponse<String> r = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        SEEN.add("POST " + path + " " + r.statusCode());
        return r;
    }

    private HttpResponse<String> submit(String key, String body) throws Exception {
        return post("/v1/jobs", key, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static String field(HttpResponse<String> r, String name) {
        return JSON.readTree(r.body()).path(name).asString();
    }

    private static byte[] zip(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("a.ttl"));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private int jobCount() throws Exception {
        HttpResponse<String> list = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs"))
                .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(list.body()).path("jobs").size();
    }

    @Test
    void fiveConcurrentIdenticalSubmitsMakeOneJob() throws Exception {
        start();
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<HttpResponse<String>>> answers = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < 5; i++) {
                answers.add(pool.submit(() -> {
                    go.await();
                    return submit(key, "{\"command\":\"validate\",\"label\":\"once\"}");
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<HttpResponse<String>> f : answers) {
                HttpResponse<String> r = f.get(20, TimeUnit.SECONDS);
                // 202 with the job, or 409 while the first one is still being created.
                assertThat(r.statusCode()).as(r.body()).isIn(202, 409);
                if (r.statusCode() == 202) {
                    ids.add(field(r, "jobId"));
                }
            }
            assertThat(ids).hasSize(1);
            HttpResponse<String> again = submit(key, "{\"command\":\"validate\",\"label\":\"once\"}");
            assertThat(again.statusCode()).isEqualTo(202);
            assertThat(field(again, "jobId")).isEqualTo(ids.iterator().next());
            assertThat(again.headers().firstValue("Location")).hasValue("/v1/jobs/" + ids.iterator().next());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void withoutAKeyEverySubmitIsANewJob() throws Exception {
        start();
        String a = field(submit(null, "{\"command\":\"validate\"}"), "jobId");
        String b = field(submit(null, "{\"command\":\"validate\"}"), "jobId");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void theSameKeyWithAnotherRequestIs422() throws Exception {
        start();
        String key = "nightly-2026-10-16";
        assertThat(submit(key, "{\"command\":\"validate\"}").statusCode()).isEqualTo(202);

        HttpResponse<String> other = submit(key, "{\"command\":\"sparql\"}");

        assertThat(other.statusCode()).as(other.body()).isEqualTo(422);
        assertThat(other.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
        assertThat(other.body()).contains("Idempotency-Key");
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void aKeyStillInProgressIs409WithRetryAfter() throws Exception {
        start();
        holdCheck = true;
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> submit(key, "{\"command\":\"validate\"}"));
            Thread.sleep(300); // the first request now holds the key in the path check

            HttpResponse<String> second = submit(key, "{\"command\":\"validate\"}");

            assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
            assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
            checkGate.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aMalformedKeyIs400() throws Exception {
        start();
        // (Non-ASCII can't be sent through the JDK client, which turns it into '?': see the next test.)
        for (String key : new String[] {"", "x".repeat(256), "with space"}) {
            HttpResponse<String> r = submit(key, "{\"command\":\"validate\"}");
            assertThat(r.statusCode()).as("key '%s'", key).isEqualTo(400);
        }
        assertThat(jobCount()).isZero();
    }

    @Test
    void onlyVisibleAsciiMakesAKey() {
        assertThat(ServeServer.validIdempotencyKey(UUID.randomUUID().toString())).isTrue();
        assertThat(ServeServer.validIdempotencyKey("airflow:dag/task/run/1")).isTrue();
        for (String bad : new String[] {"", "x".repeat(256), "a b", "tab\there", "\u00fcnicode", "line\nbreak", "del\u007f"}) {
            assertThat(ServeServer.validIdempotencyKey(bad)).as(bad).isFalse();
        }
    }

    @Test
    void aRefusedRequestDoesNotUseUpItsKey() throws Exception {
        start();
        String key = UUID.randomUUID().toString();
        assertThat(submit(key, "{\"command\":\"nonsense\"}").statusCode()).isEqualTo(400);

        // The client fixes nothing and retries: the same refusal, not a stored answer or a 422.
        assertThat(submit(key, "{\"command\":\"nonsense\"}").statusCode()).isEqualTo(400);
        assertThat(submit(key, "{\"command\":\"validate\"}").statusCode()).isEqualTo(202);
    }

    @Test
    void theSameUploadWithTheSameKeyIsOneUpload() throws Exception {
        start();
        String key = UUID.randomUUID().toString();
        byte[] bytes = zip("x"); // one archive: a ZIP entry carries the time it was made
        HttpResponse<String> first = post("/v1/uploads", key, bytes, "application/zip");
        HttpResponse<String> again = post("/v1/uploads", key, bytes, "application/zip");
        HttpResponse<String> other = post("/v1/uploads", key, zip("y"), "application/zip");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(field(again, "uploadId")).isEqualTo(field(first, "uploadId"));
        assertThat(other.statusCode()).as(other.body()).isEqualTo(422);
        // The repeat's and the refused one's copies are dropped (their folders go too, or at the
        // next start if Windows still holds a file).
        assertThat(server.workAreaForTest().uploadCount()).isEqualTo(1);
    }

    @Test
    void aKeyLeftUnfinishedAfterItsJobWasCreatedGivesThatJob() throws Exception {
        start();
        // As if the server died between creating the job and completing the key.
        byte[] body = "{\"command\":\"validate\"}".getBytes(StandardCharsets.UTF_8);
        JobStore jobs = server.jobsForTest();
        UUID id = jobs.newId();
        jobs.create(id, "validate", null, TokenSource.Client.DEFAULT.name(), body, null);
        String fingerprint = ServeServer.fingerprint("POST".getBytes(StandardCharsets.UTF_8),
                "/v1/jobs".getBytes(StandardCharsets.UTF_8), body);
        assertThat(jobs.idempotency().begin("default", "lost-answer", fingerprint, id, clock.instant())).isNull();

        HttpResponse<String> again = submit("lost-answer", "{\"command\":\"validate\"}");

        assertThat(again.statusCode()).as(again.body()).isEqualTo(202);
        assertThat(field(again, "jobId")).isEqualTo(id.toString());
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void aStaleKeyWhoseJobExistsGivesThatJobNotASecondOne() throws Exception {
        start();
        // The job was made, its completion was lost, and the client comes back after STALE.
        byte[] body = "{\"command\":\"validate\"}".getBytes(StandardCharsets.UTF_8);
        JobStore jobs = server.jobsForTest();
        UUID id = jobs.newId();
        jobs.create(id, "validate", null, TokenSource.Client.DEFAULT.name(), body, null);
        String fingerprint = ServeServer.fingerprint("POST".getBytes(StandardCharsets.UTF_8),
                "/v1/jobs".getBytes(StandardCharsets.UTF_8), body);
        jobs.idempotency().begin("default", "late", fingerprint, id, clock.instant());
        clock.advance(IdempotencyStore.STALE.plusMinutes(5));

        HttpResponse<String> again = submit("late", "{\"command\":\"validate\"}");

        assertThat(again.statusCode()).as(again.body()).isEqualTo(202);
        assertThat(field(again, "jobId")).isEqualTo(id.toString());
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void anUnfinishedKeyWithoutAJobIsFreeAgainOnceStale() throws Exception {
        start();
        byte[] body = "{\"command\":\"validate\"}".getBytes(StandardCharsets.UTF_8);
        String fingerprint = ServeServer.fingerprint("POST".getBytes(StandardCharsets.UTF_8),
                "/v1/jobs".getBytes(StandardCharsets.UTF_8), body);
        server.jobsForTest().idempotency().begin("default", "died", fingerprint, UUID.randomUUID(), clock.instant());
        assertThat(submit("died", "{\"command\":\"validate\"}").statusCode()).isEqualTo(409);

        clock.advance(IdempotencyStore.STALE.plusSeconds(1));

        assertThat(submit("died", "{\"command\":\"validate\"}").statusCode()).isEqualTo(202);
    }

    @Test
    void keysExpireAfterTheTtl() throws Exception {
        start();
        String first = field(submit("daily", "{\"command\":\"validate\"}"), "jobId");
        clock.advance(Duration.ofHours(25));

        String second = field(submit("daily", "{\"command\":\"validate\"}"), "jobId");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void anUploadStillInProgressIs409AndOneNoLongerThereIs404() throws Exception {
        start();
        byte[] bytes = zip("z");
        String fingerprint = ServeServer.fingerprint("POST".getBytes(StandardCharsets.UTF_8),
                "/v1/uploads".getBytes(StandardCharsets.UTF_8),
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        server.jobsForTest().idempotency().begin("default", "busy-upload", fingerprint, UUID.randomUUID(),
                clock.instant());

        HttpResponse<String> busy = post("/v1/uploads", "busy-upload", bytes, "application/zip");
        assertThat(busy.statusCode()).as(busy.body()).isEqualTo(409);

        HttpResponse<String> made = post("/v1/uploads", "gone-upload", bytes, "application/zip");
        HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/uploads/"
                        + field(made, "uploadId"))).header("Authorization", "Bearer " + TOKEN).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> gone = post("/v1/uploads", "gone-upload", bytes, "application/zip");
        assertThat(gone.statusCode()).as(gone.body()).isEqualTo(404);
    }

    @AfterAll
    static void everyIdempotencyResponseInTheSpecIsExercised() throws Exception {
        long tests = java.util.Arrays.stream(IdempotencyTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        org.junit.jupiter.api.Assumptions.assumeTrue(FINISHED.get() == tests * TestStores.values().length,
                "not all tests of the class ran");
        JsonNode spec;
        try (var in = IdempotencyTest.class.getResourceAsStream("/openapi/cimpal-v1.json")) {
            spec = JSON.readTree(in);
        }
        Set<String> expected = new HashSet<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
                op.getValue().path("responses").properties().forEach(r -> {
                    String key = op.getKey().toUpperCase() + " " + path.getKey() + " " + r.getKey();
                    if (covers(key)) {
                        expected.add(key);
                    }
                })));
        assertThat(expected).hasSize(5);
        assertThat(SEEN).containsAll(expected);
    }
}
