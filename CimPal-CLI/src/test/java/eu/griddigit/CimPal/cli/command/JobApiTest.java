/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The asynchronous job API {@code /v1} (DEP-5) on a real {@link ServeServer} with stub commands
 * and a clock the tests move. Every (method, route, status) these tests see is recorded, and
 * {@link #everyResponseInTheSpecIsExercised()} checks that the OpenAPI document lists nothing
 * that isn't tested.
 */
class JobApiTest {

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** "METHOD /v1/route/{jobId} STATUS" for every response these tests received. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FINISHED = new AtomicInteger();

    private final MovableClock clock = new MovableClock();
    private final CountDownLatch release = new CountDownLatch(1);
    private ServeServer server;

    /** A clock the test moves forward; starts at a fixed instant. */
    static final class MovableClock implements InstantSource {
        private volatile Instant now = Instant.parse("2026-10-06T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @AfterEach
    void stop() {
        FINISHED.incrementAndGet();
        release.countDown();
        if (server != null) {
            server.close();
        }
    }

    private static ServeServer.Config.Builder config() {
        return ServeServer.Config.builder()
                .host("127.0.0.1")
                .port(0)
                .token(TOKEN)
                .maxBodyBytes(4096)
                .queueSize(4)
                .requestTimeout(Duration.ofSeconds(30));
    }

    private void start(ServeServer.Config.Builder config, ServeServer.CommandRunner runner) throws Exception {
        start(config, runner, ServeServer.RequestCheck.NONE);
    }

    private void start(ServeServer.Config.Builder config, ServeServer.CommandRunner runner,
                       ServeServer.RequestCheck check) throws Exception {
        server = ServeServer.start(config.build(), runner, check, () -> { }, clock);
    }

    /** A runner that answers at once with a small JSON document naming the command. */
    private static ServeServer.CommandResult quick(String command) {
        return new ServeServer.CommandResult(1, "{\"command\":\"" + command + "\",\"hasViolations\":true}");
    }

    /** A runner that blocks until {@link #release} and counts how often it ran. */
    private ServeServer.CommandRunner blocking(AtomicInteger runs, CountDownLatch started) {
        return (command, configFile) -> {
            runs.incrementAndGet();
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return quick(command);
        };
    }

    // ---- HTTP helpers ------------------------------------------------------------------------

    private HttpResponse<String> call(String method, String template, String path, String body, boolean token)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (token) {
            request.header("Authorization", "Bearer " + TOKEN);
        }
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        SEEN.add(method + " " + template + " " + response.statusCode());
        return response;
    }

    private HttpResponse<String> submit(String command, String config) throws Exception {
        return call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"" + command + "\",\"config\":" + config + "}", true);
    }

    private HttpResponse<String> job(String method, String id, String sub) throws Exception {
        String suffix = sub.isEmpty() ? "" : "/" + sub;
        return call(method, "/v1/jobs/{jobId}" + suffix, "/v1/jobs/" + id + suffix, null, true);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String id(HttpResponse<String> submitted) {
        return json(submitted).path("jobId").asString();
    }

    private String status(String id) throws Exception {
        return json(job("GET", id, "")).path("status").asString();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void waitForStatus(String id, String expected) throws Exception {
        waitUntil(() -> {
            try {
                return status(id).equals(expected);
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
        JsonNode problem = json(response);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("title").asString()).isEqualTo(Problem.title(status));
        assertThat(problem.path("type").asString()).isEqualTo("about:blank");
        assertThat(problem.path("detail").asString()).isNotBlank();
    }

    // ---- submit, poll, result ----------------------------------------------------------------

    @Test
    void submitAnswersAtOnceWhileALongJobRunsThenTheResultMatchesTheSyncEndpoint() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config(), blocking(runs, started));

        long before = System.nanoTime();
        HttpResponse<String> submitted = submit("validate", "{\"workers\":1}");
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        assertThat(millis).as("submit doesn't wait for the job").isLessThan(1000);
        String id = id(submitted);
        assertThat(submitted.headers().firstValue("Location")).hasValue("/v1/jobs/" + id);
        assertThat(json(submitted).path("status").asString()).isIn("queued", "running");
        assertThat(json(submitted).path("links").path("result").asString()).isEqualTo("/v1/jobs/" + id + "/result");

        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        waitForStatus(id, "running");
        assertProblem(job("GET", id, "result"), 409);

        release.countDown();
        waitForStatus(id, "succeeded");
        JsonNode done = json(job("GET", id, ""));
        assertThat(done.path("exitCode").asInt()).isEqualTo(1);
        assertThat(done.path("hasViolations").asBoolean()).isTrue();
        assertThat(done.path("startedAt").asString()).isNotBlank();
        assertThat(done.path("finishedAt").asString()).isNotBlank();

        HttpResponse<String> result = job("GET", id, "result");
        assertThat(result.statusCode()).isEqualTo(200);
        HttpResponse<String> sync = call("POST", "/validate", "/validate", "{\"workers\":1}", true);
        assertThat(result.body()).isEqualTo(sync.body());
    }

    @Test
    void failuresAreFailedJobsWithAnError() throws Exception {
        start(config(), (command, configFile) -> {
            if (command.equals("convert")) {
                throw new IllegalStateException("boom");
            }
            return new ServeServer.CommandResult(2, "{\"exitCode\":2,\"error\":\"bad input\"}");
        });

        String badInput = id(submit("sparql", "{}"));
        String crashed = id(submit("convert", "{}"));
        waitForStatus(badInput, "failed");
        waitForStatus(crashed, "failed");

        JsonNode bad = json(job("GET", badInput, ""));
        assertThat(bad.path("exitCode").asInt()).isEqualTo(2);
        assertThat(bad.path("error").asString()).contains("exit code 2");
        assertThat(json(job("GET", badInput, "result")).path("error").asString()).isEqualTo("bad input");
        JsonNode crash = json(job("GET", crashed, ""));
        assertThat(crash.path("exitCode").isNull()).isTrue();
        assertThat(crash.path("error").asString()).contains("Internal error");
        assertThat(json(job("GET", crashed, "result")).path("exitCode").asInt()).isEqualTo(3);
    }

    @Test
    void unknownOrMalformedIdsAreNotFound() throws Exception {
        start(config(), (command, configFile) -> quick(command));

        assertProblem(job("GET", UUID.randomUUID().toString(), ""), 404);
        assertProblem(job("DELETE", UUID.randomUUID().toString(), ""), 404);
        assertProblem(job("GET", UUID.randomUUID().toString(), "result"), 404);
        assertProblem(job("GET", UUID.randomUUID().toString(), "log"), 404);
        String real = id(submit("sparql", "{}"));
        assertProblem(job("GET", "not-a-uuid", ""), 404);
        assertProblem(job("GET", real.toUpperCase(), ""), 404);
        assertProblem(call("GET", "-", "/v1/jobs/" + real + "/other", null, true), 404);
        assertProblem(call("GET", "-", "/v1/nothing", null, true), 404);
    }

    // ---- cancel and the queue ----------------------------------------------------------------

    @Test
    void aQueuedJobCanBeCancelledAndNeverRunsARunningOneCannot() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config(), blocking(runs, started));

        String running = id(submit("validate", "{}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        String queued = id(submit("sparql", "{}"));
        assertThat(status(queued)).isEqualTo("queued");

        HttpResponse<String> cancelled = job("DELETE", queued, "");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(json(cancelled).path("status").asString()).isEqualTo("cancelled");
        assertProblem(job("DELETE", running, ""), 409);
        assertProblem(job("DELETE", queued, ""), 409);
        assertProblem(job("GET", queued, "result"), 409);

        release.countDown();
        waitForStatus(running, "succeeded");
        Thread.sleep(200);
        assertThat(runs.get()).as("the cancelled job never ran").isEqualTo(1);
        assertThat(status(queued)).isEqualTo("cancelled");
        assertProblem(job("DELETE", running, ""), 409);
    }

    @Test
    void aFullQueueIs503WithRetryAfter() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(1), blocking(runs, started));

        assertThat(submit("validate", "{}").statusCode()).isEqualTo(202);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(submit("validate", "{}").statusCode()).isEqualTo(202);

        HttpResponse<String> full = submit("validate", "{}");
        assertProblem(full, 503);
        assertThat(full.headers().firstValue("Retry-After")).hasValue("30");
        // Jobs and synchronous requests share the queue.
        assertThat(call("POST", "/validate", "/validate", "{}", true).statusCode()).isEqualTo(503);
    }

    @Test
    void shutdownCancelsQueuedJobs() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config(), blocking(runs, started));
        String running = id(submit("validate", "{}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        String queued = id(submit("validate", "{}"));
        ServeServer s = server;

        s.close();
        release.countDown();
        assertThat(s.awaitWorker(Duration.ofSeconds(10))).isTrue();

        assertThat(runs.get()).isEqualTo(1);
        assertThat(s.jobsForTest().get(UUID.fromString(queued)).snapshot().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(s.jobsForTest().get(UUID.fromString(running)).snapshot().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    // ---- store limits ------------------------------------------------------------------------

    @Test
    void finishedJobsExpireAfterTheTtl() throws Exception {
        start(config().jobTtl(Duration.ofHours(1)), (command, configFile) -> quick(command));
        String old = id(submit("sparql", "{}"));
        waitForStatus(old, "succeeded");

        clock.advance(Duration.ofMinutes(59));
        assertThat(call("GET", "/v1/jobs", "/v1/jobs", null, true).body()).contains(old);
        clock.advance(Duration.ofMinutes(2));
        HttpResponse<String> list = call("GET", "/v1/jobs", "/v1/jobs", null, true);

        assertThat(list.body()).doesNotContain(old);
        assertProblem(job("GET", old, ""), 404);
    }

    @Test
    void theOldestFinishedJobsAreDroppedBeyondMaxJobs() throws Exception {
        start(config().maxJobs(ServeServer.MIN_MAX_JOBS), (command, configFile) -> quick(command));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ServeServer.MIN_MAX_JOBS + 2; i++) {
            String id = id(submit("sparql", "{}"));
            waitForStatus(id, "succeeded");
            ids.add(id);
            clock.advance(Duration.ofSeconds(1));
        }

        // The last submit evicted down to the limit before it was added; one more evicts again.
        waitForStatus(id(submit("sparql", "{}")), "succeeded");

        assertProblem(job("GET", ids.get(0), ""), 404);
        assertProblem(job("GET", ids.get(1), ""), 404);
        assertThat(job("GET", ids.get(ids.size() - 1), "").statusCode()).isEqualTo(200);
        assertThat(server.jobsForTest().size()).isLessThanOrEqualTo(ServeServer.MIN_MAX_JOBS + 1);
    }

    @Test
    void aJobRunningPastTheJobTimeoutIsTimedOutAndHealthStalledButNotInterrupted() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().jobTimeout(Duration.ofMinutes(10)), blocking(runs, started));
        String id = id(submit("validate", "{}"));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        waitForStatus(id, "running");

        clock.advance(Duration.ofMinutes(11));
        assertThat(status(id)).isEqualTo("timed_out");
        JsonNode health = json(call("GET", "/v1/health", "/v1/health", null, false));
        assertThat(health.path("stalled").asBoolean()).isTrue();
        assertThat(health.path("status").asString()).isEqualTo("stalled");

        release.countDown();
        waitUntil(() -> server.jobsForTest().get(UUID.fromString(id)).snapshot().finished());
        assertThat(status(id)).as("stays timed_out").isEqualTo("timed_out");
        assertThat(job("GET", id, "result").statusCode()).as("the late result is kept").isEqualTo(200);
        assertThat(json(call("GET", "/v1/health", "/v1/health", null, false)).path("stalled").asBoolean()).isFalse();
    }

    @Test
    void aResultLargerThanTheLimitIsNotKept() throws Exception {
        start(config().maxResultBytes(ServeServer.MIN_RESULT_BYTES),
                (command, configFile) -> new ServeServer.CommandResult(0, "{\"rows\":\"" + "x".repeat(2000) + "\"}"));
        String id = id(submit("sparql", "{}"));
        waitForStatus(id, "succeeded");

        HttpResponse<String> result = job("GET", id, "result");
        assertProblem(result, 410);
        assertThat(json(result).path("jobId").asString()).isEqualTo(id);
    }

    // ---- log ---------------------------------------------------------------------------------

    @Test
    void theLogHoldsTheJobsStderrLinesBoundedAndSanitised() throws Exception {
        start(config().jobLogLines(3), (command, configFile) -> {
            for (int i = 1; i <= 4; i++) {
                System.err.println("[INFO] step " + i);
            }
            Thread pool = new Thread(() -> System.err.println("[INFO] from a pool \u001b[31m thread"));
            pool.start();
            pool.join();
            Thread serverThread = new Thread(() -> System.err.println("[WARN] serve: not part of the job"),
                    StderrTee.SERVER_THREAD_PREFIX + "-test");
            serverThread.start();
            serverThread.join();
            System.err.print("[INFO] partial, no newline");
            return quick(command);
        });
        String id = id(submit("validate", "{}"));
        waitForStatus(id, "succeeded");

        JsonNode all = json(job("GET", id, "log"));
        assertThat(all.path("truncated").asBoolean()).isTrue();
        assertThat(all.path("offset").asLong()).isEqualTo(3);
        assertThat(all.path("nextOffset").asLong()).isEqualTo(6);
        List<String> lines = new ArrayList<>();
        all.path("lines").forEach(l -> lines.add(l.asString()));
        assertThat(lines).containsExactly("[INFO] step 4", "[INFO] from a pool ␞[31m thread",
                "[INFO] partial, no newline");
        assertThat(String.join("\n", lines)).doesNotContain("not part of the job");

        JsonNode next = json(call("GET", "/v1/jobs/{jobId}/log", "/v1/jobs/" + id + "/log?offset=6", null, true));
        assertThat(next.path("lines")).isEmpty();
        assertThat(next.path("nextOffset").asLong()).isEqualTo(6);
        assertThat(next.path("truncated").asBoolean()).isFalse();
        assertProblem(call("GET", "/v1/jobs/{jobId}/log", "/v1/jobs/" + id + "/log?offset=-1", null, true), 400);
        assertProblem(call("GET", "/v1/jobs/{jobId}/log", "/v1/jobs/" + id + "/log?offset=x", null, true), 400);
    }

    // ---- list --------------------------------------------------------------------------------

    @Test
    void listIsNewestFirstAndFiltersByStatus() throws Exception {
        start(config(), (command, configFile) -> command.equals("convert")
                ? new ServeServer.CommandResult(3, "")
                : quick(command));
        String first = id(submit("sparql", "{}"));
        waitForStatus(first, "succeeded");
        clock.advance(Duration.ofSeconds(1));
        String second = id(submit("convert", "{}"));
        waitForStatus(second, "failed");

        JsonNode all = json(call("GET", "/v1/jobs", "/v1/jobs", null, true)).path("jobs");
        assertThat(all.get(0).path("jobId").asString()).isEqualTo(second);
        assertThat(all.get(1).path("jobId").asString()).isEqualTo(first);
        JsonNode failed = json(call("GET", "/v1/jobs", "/v1/jobs?status=failed&limit=10", null, true)).path("jobs");
        assertThat(failed).hasSize(1);
        assertThat(json(call("GET", "/v1/jobs", "/v1/jobs?limit=1", null, true)).path("jobs")).hasSize(1);
        assertProblem(call("GET", "/v1/jobs", "/v1/jobs?status=done", null, true), 400);
        assertProblem(call("GET", "/v1/jobs", "/v1/jobs?limit=0", null, true), 400);
        assertProblem(call("GET", "/v1/jobs", "/v1/jobs?limit=1001", null, true), 400);
    }

    // ---- request checks ----------------------------------------------------------------------

    @Test
    void submitChecksTheRequest() throws Exception {
        start(config(), (command, configFile) -> quick(command), (command, body) -> {
            if (new String(body).contains("outside")) {
                throw new ServeServer.Rejected(403, "Path not allowed: outside");
            }
            return body;
        });

        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{not json", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "[1]", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"validate\",\"extra\":1}", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"serve\"}", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"run\"}", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"validate\",\"config\":[]}", true), 400);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs",
                "{\"command\":\"validate\",\"label\":\"" + "x".repeat(201) + "\"}", true), 400);
        assertProblem(submit("validate", "{\"outputDir\":\"outside\"}"), 403);
        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"validate\",\"pad\":\"" + "x".repeat(5000) + "\"}", true), 413);

        HttpResponse<String> noJson = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        SEEN.add("POST /v1/jobs " + noJson.statusCode());
        assertProblem(noJson, 415);

        HttpResponse<String> labelled = call("POST", "/v1/jobs", "/v1/jobs",
                "{\"command\":\"sparql\",\"label\":\"nightly run\"}", true);
        assertThat(labelled.statusCode()).isEqualTo(202);
        assertThat(json(labelled).path("label").asString()).isEqualTo("nightly run");
    }

    @Test
    void everyRouteButHealthAndSpecNeedsTheToken() throws Exception {
        start(config(), (command, configFile) -> quick(command));
        String id = UUID.randomUUID().toString();

        assertProblem(call("POST", "/v1/jobs", "/v1/jobs", "{\"command\":\"validate\"}", false), 401);
        assertProblem(call("GET", "/v1/jobs", "/v1/jobs", null, false), 401);
        assertProblem(call("GET", "/v1/jobs/{jobId}", "/v1/jobs/" + id, null, false), 401);
        assertProblem(call("DELETE", "/v1/jobs/{jobId}", "/v1/jobs/" + id, null, false), 401);
        assertProblem(call("GET", "/v1/jobs/{jobId}/result", "/v1/jobs/" + id + "/result", null, false), 401);
        assertProblem(call("GET", "/v1/jobs/{jobId}/log", "/v1/jobs/" + id + "/log", null, false), 401);

        HttpResponse<String> health = call("GET", "/v1/health", "/v1/health", null, false);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json(health).path("apiVersion").asString()).isEqualTo(ServeServer.API_VERSION);
        HttpResponse<String> spec = call("GET", "/v1/openapi.json", "/v1/openapi.json", null, false);
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(json(spec).path("openapi").asString()).startsWith("3.1");
    }

    @Test
    void wrongMethodsAndOldEndpointsKeepTheirAnswers() throws Exception {
        start(config(), (command, configFile) -> quick(command));

        HttpResponse<String> put = call("PUT", "-", "/v1/jobs", "{}", true);
        assertProblem(put, 405);
        assertThat(put.headers().firstValue("Allow")).hasValue("GET, POST");
        assertProblem(call("POST", "-", "/v1/health", "{}", true), 405);
        assertProblem(call("POST", "-", "/v1/jobs/" + UUID.randomUUID() + "/log", "{}", true), 405);

        // The synchronous endpoints keep their {"error": ...} bodies.
        HttpResponse<String> old = call("POST", "-", "/validate", "{}", false);
        assertThat(old.statusCode()).isEqualTo(401);
        assertThat(json(old).has("error")).isTrue();
        assertThat(old.headers().firstValue("Content-Type")).hasValue("application/json; charset=UTF-8");
        assertThat(json(call("GET", "-", "/commands", null, true)).path("v1")).hasSize(ServeServer.V1_ROUTES.size());
    }

    @Test
    void jobIdsAreRandomVersion4Uuids() {
        JobManager jobs = new JobManager(1000, Duration.ofHours(1), Duration.ofHours(1), 10, Long.MAX_VALUE, InstantSource.system());
        Set<UUID> ids = new HashSet<>();
        UUID previous = null;
        int adjacent = 0;
        for (int i = 0; i < 1000; i++) {
            UUID id = jobs.create("validate", null).id();
            assertThat(id.version()).isEqualTo(4);
            assertThat(id.variant()).isEqualTo(2);
            if (previous != null && Math.abs(id.getLeastSignificantBits() - previous.getLeastSignificantBits()) < 1000) {
                adjacent++;
            }
            ids.add(id);
            previous = id;
        }
        assertThat(ids).hasSize(1000);
        assertThat(adjacent).as("not sequential").isZero();
    }

    @Test
    void operatorLimitsAreChecked() {
        ServeServer.CommandRunner runner = (command, configFile) -> quick(command);
        for (ServeServer.Config.Builder bad : List.of(
                config().jobTimeout(Duration.ZERO), config().jobTtl(Duration.ofSeconds(-1)),
                config().maxJobs(ServeServer.MIN_MAX_JOBS - 1), config().maxJobs(ServeServer.MAX_MAX_JOBS + 1),
                config().jobLogLines(0), config().jobLogLines(ServeServer.MAX_JOB_LOG_LINES + 1),
                config().maxResultBytes(ServeServer.MIN_RESULT_BYTES - 1),
                config().maxResultBytes(ServeServer.MAX_RESULT_BYTES + 1),
                config().jobStoreBytes(ServeServer.MIN_JOB_STORE_BYTES - 1))) {
            assertThatThrownBy(() -> ServeServer.start(bad.build(), runner)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- security review findings (DEP-5) ----------------------------------------------------

    @Test
    void aCancelledJobLeavesTheWorkerQueueAtOnce() throws Exception {
        // Submit and cancel in a loop must not pile request bodies up behind a long job.
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().queueSize(2), blocking(runs, started));
        submit("validate", "{}");
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 2 + 5; i++) {
            String id = id(submit("validate", "{\"pad\":\"" + "x".repeat(1000) + "\"}"));
            assertThat(job("DELETE", id, "").statusCode()).isEqualTo(200);
            assertThat(server.workerQueueLengthForTest()).isZero();
        }
        assertThat(server.inFlight()).isEqualTo(1);
    }

    @Test
    void finishedJobsAreDroppedOldestFirstBeyondTheJobStoreBytes() throws Exception {
        // About 600 kB per result (two bytes per character), 1 MiB for all of them: one fits.
        String big = "{\"rows\":\"" + "x".repeat(300_000) + "\"}";
        start(config().jobStoreBytes(ServeServer.MIN_JOB_STORE_BYTES),
                (command, configFile) -> new ServeServer.CommandResult(0, big));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = id(submit("sparql", "{}"));
            waitForStatus(id, "succeeded");
            ids.add(id);
            clock.advance(Duration.ofSeconds(1));
        }

        assertProblem(job("GET", ids.get(0), ""), 404);
        assertProblem(job("GET", ids.get(1), ""), 404);
        assertThat(job("GET", ids.get(2), "result").body()).isEqualTo(big);
    }

    @Test
    void aJobRunningOutOfMemoryFailsAndStopsTheServer() throws Exception {
        AtomicInteger oom = new AtomicInteger();
        server = ServeServer.start(config().build(), (command, configFile) -> {
            throw new OutOfMemoryError("Java heap space");
        }, ServeServer.RequestCheck.NONE, oom::incrementAndGet, clock);

        String id = id(submit("validate", "{}"));
        waitForStatus(id, "failed");

        assertThat(oom.get()).isEqualTo(1);
        assertThat(json(job("GET", id, "")).path("error").asString()).contains("Out of memory");
        waitUntil(() -> server.inFlight() == 0);
    }

    /** A raw request (HttpClient won't send a foreign Host); answers the status line and headers. */
    private String raw(String requestHead) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            StringBuilder head = new StringBuilder();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                head.append(line).append('\n');
            }
            return head.toString();
        }
    }

    @Test
    void hostAndOriginAreCheckedOnV1RoutesWithProblemBodies() throws Exception {
        start(config(), (command, configFile) -> quick(command));
        String auth = "Authorization: Bearer " + TOKEN + "\r\n";

        for (String path : List.of("/v1/health", "/v1/openapi.json", "/v1/jobs")) {
            String badHost = raw("GET " + path + " HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\n"
                    + auth + "Connection: close\r\n\r\n");
            assertThat(badHost).startsWith("HTTP/1.1 403").containsIgnoringCase("content-type: " + Problem.CONTENT_TYPE);
            HttpResponse<String> badOrigin = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + path))
                    .header("Authorization", "Bearer " + TOKEN).header("Origin", "https://evil.example")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertProblem(badOrigin, 403);
        }
    }

    @Test
    void oddPathsAndMethodsUnderV1AreRefused() throws Exception {
        start(config(), (command, configFile) -> quick(command));
        String id = id(submit("sparql", "{}"));
        waitForStatus(id, "succeeded");

        assertProblem(call("GET", "-", "/v1", null, true), 404);
        assertProblem(call("GET", "-", "/v1/jobs/%2e%2e", null, true), 404);
        // An encoded slash is decoded: the canonical route, with the same checks.
        assertThat(call("GET", "-", "/v1/jobs/" + id + "%2Fresult", null, true).body())
                .isEqualTo(job("GET", id, "result").body());
        assertProblem(call("GET", "-", "/v1/jobs/" + id + "/", null, true), 404);
        assertProblem(call("GET", "-", "/v1/jobs/" + id + "/result/x", null, true), 404);
        for (String method : List.of("HEAD", "OPTIONS")) {
            HttpResponse<String> response = call(method, "-", "/v1/jobs/" + id, null, true);
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).hasValue("GET, DELETE");
        }
        // Without the token, a well-formed id gets 401, never a hint whether the job exists.
        assertProblem(call("GET", "-", "/v1/jobs/" + id, null, false), 401);
    }

    @Test
    void aMalformedQueryStringIs400() throws Exception {
        start(config(), (command, configFile) -> quick(command));
        String id = id(submit("sparql", "{}"));
        waitForStatus(id, "succeeded");

        // HttpClient refuses such a URI, so these go over a raw socket. The JDK server itself answers
        // 400 for some malformed targets before the handler sees them; query() refuses the rest.
        String head = " HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nAuthorization: Bearer " + TOKEN
                + "\r\nConnection: close\r\n\r\n";
        for (String target : List.of("/v1/jobs/" + id + "/log?offset=%zz", "/v1/jobs?limit=%2")) {
            String response = raw("GET " + target + head);
            assertThat(response).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void healthWithoutTokenShowsNoJobCountAndWarnsOncePerStall() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        start(config().jobTimeout(Duration.ofMinutes(1)), blocking(runs, started));
        submit("validate", "{}");
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofMinutes(2));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            for (int i = 0; i < 3; i++) {
                JsonNode health = json(call("GET", "/v1/health", "/v1/health", null, false));
                assertThat(health.path("stalled").asBoolean()).isTrue();
                assertThat(health.has("jobs")).isFalse();
            }
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8).split("exceeded its timeout", -1)).hasSize(2);
    }

    @Test
    void theStderrTeeStopsCopyingWhenClosed() throws Exception {
        JobLog log = new JobLog(10);
        PrintStream original = System.err;
        PrintStream saved;
        try (StderrTee.Capture ignored = StderrTee.capture(log)) {
            saved = System.err;
            saved.println("during");
            Thread dispatcher = new Thread(() -> System.err.println("server line"), "HTTP-Dispatcher");
            dispatcher.start();
            dispatcher.join();
        }
        // e.g. a logging handler that kept the stream it saw while the job ran
        saved.println("after");

        assertThat(System.err).isSameAs(original);
        assertThat(log.read(0, 10).lines()).containsExactly("during");
    }

    // ---- the spec lists only what is tested --------------------------------------------------

    @AfterAll
    static void everyResponseInTheSpecIsExercised() throws Exception {
        long tests = java.util.Arrays.stream(JobApiTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        // Only meaningful when the whole class ran, not for a single selected test.
        org.junit.jupiter.api.Assumptions.assumeTrue(FINISHED.get() == tests, "not all tests of the class ran");
        JsonNode spec;
        try (InputStream in = JobApiTest.class.getResourceAsStream("/openapi/cimpal-v1.json")) {
            spec = JSON.readTree(in);
        }
        Set<String> expected = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (op.getKey().equals("parameters")) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    expected.add(op.getKey().toUpperCase() + " " + path.getKey() + " " + response.getKey());
                }
            }
        }
        assertThat(SEEN).as("every response in the OpenAPI document is exercised by a test").containsAll(expected);
    }
}
