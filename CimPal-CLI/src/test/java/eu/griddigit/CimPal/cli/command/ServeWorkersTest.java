/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code serve --workers} (DEP-8) on a real {@link ServeServer} with stub commands: commands run
 * at once, each job's log holds only its own lines, health and metrics count every running
 * command, and memory admission keeps large commands apart.
 */
class ServeWorkersTest {

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIB = 1L << 20;

    private final CountDownLatch release = new CountDownLatch(1);
    private ServeServer server;

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.close();
        }
    }

    private static ServeServer.Config.Builder config(int workers) {
        return ServeServer.Config.builder()
                .host("127.0.0.1")
                .port(0)
                .token(TOKEN)
                .maxBodyBytes(4096)
                .queueSize(8)
                .metrics(true)
                .workers(workers)
                .requestTimeout(Duration.ofSeconds(30));
    }

    private void start(ServeServer.Config.Builder config, ServeServer.CommandRunner runner) throws Exception {
        server = ServeServer.start(config.build(), runner, ServeServer.RequestCheck.NONE, () -> { });
    }

    /** {@code {"size": n}} in a job's config: its estimate in MiB (the stub estimator). */
    private static long sizeOf(byte[] body) {
        return JSON.readTree(body).path("size").asLong(0) * MIB;
    }

    private static JsonNode config(java.nio.file.Path configFile) throws Exception {
        return JSON.readTree(Files.readAllBytes(configFile));
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String submit(String config) throws Exception {
        HttpResponse<String> response = call("POST", "/v1/jobs", "{\"command\":\"validate\",\"config\":" + config + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return JSON.readTree(response.body()).path("jobId").asString();
    }

    private String status(String id) throws Exception {
        return JSON.readTree(call("GET", "/v1/jobs/" + id, null).body()).path("status").asString();
    }

    private List<String> log(String id) throws Exception {
        List<String> lines = new ArrayList<>();
        JSON.readTree(call("GET", "/v1/jobs/" + id + "/log", null).body()).path("lines")
                .forEach(l -> lines.add(l.asString()));
        return lines;
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

    private static ServeServer.CommandResult ok() {
        return new ServeServer.CommandResult(0, "{\"exitCode\":0}");
    }

    // -------------------------------------------------------------------------------------------

    @Test
    void twoWorkersRunTwoJobsAtOnceAndHealthAndMetricsCountBoth() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        start(config(2), (command, configFile) -> {
            bothStarted.countDown();
            release.await(30, TimeUnit.SECONDS);
            return ok();
        });
        String a = submit("{}");
        String b = submit("{}");
        String c = submit("{}");
        assertThat(bothStarted.await(10, TimeUnit.SECONDS)).as("both jobs started before either ended").isTrue();
        waitForStatus(a, "running");
        waitForStatus(b, "running");
        assertThat(status(c)).isEqualTo("queued");

        JsonNode health = JSON.readTree(call("GET", "/v1/health", null).body());
        assertThat(health.path("running").asInt()).isEqualTo(2);
        assertThat(health.path("queued").asInt()).isEqualTo(1);
        assertThat(health.path("workers").asInt()).isEqualTo(2);
        assertThat(health.path("busy").asBoolean()).isTrue();
        assertThat(call("GET", "/metrics", null).body())
                .contains("cimpal_running 2\n", "cimpal_workers 2\n", "cimpal_queue_depth 1\n", "cimpal_busy 1\n");
        assertThat(server.runningJobIds()).containsExactlyInAnyOrder(a, b);

        release.countDown();
        waitForStatus(c, "succeeded");
        waitUntil(() -> server.inFlight() == 0);
    }

    @Test
    void withOneWorkerJobsStillRunOneAtATime() throws Exception {
        AtomicInteger started = new AtomicInteger();
        start(config(1), (command, configFile) -> {
            started.incrementAndGet();
            release.await(30, TimeUnit.SECONDS);
            return ok();
        });
        String a = submit("{}");
        String b = submit("{}");
        waitForStatus(a, "running");
        Thread.sleep(200);
        assertThat(status(b)).isEqualTo("queued");
        assertThat(started.get()).isEqualTo(1);
        assertThat(JSON.readTree(call("GET", "/v1/health", null).body()).path("running").asInt()).isEqualTo(1);
        release.countDown();
        waitForStatus(b, "succeeded");
    }

    @Test
    void eachJobsLogHoldsOnlyItsOwnLinesWhileTheyWriteAtOnce() throws Exception {
        CyclicBarrier inStep = new CyclicBarrier(2);
        start(config(2), (command, configFile) -> {
            String name = config(configFile).path("name").asString();
            for (int i = 0; i < 20; i++) {
                inStep.await(10, TimeUnit.SECONDS); // both jobs write their i-th line together
                System.err.println("[INFO] " + name + " line " + i);
            }
            // and from a pool the command starts, as Core's validation does
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> System.err.println("[INFO] " + name + " from a pool thread")).get();
            } finally {
                pool.shutdownNow();
            }
            return ok();
        });
        String a = submit("{\"name\":\"alpha\"}");
        String b = submit("{\"name\":\"beta\"}");
        waitForStatus(a, "succeeded");
        waitForStatus(b, "succeeded");

        assertThat(log(a)).hasSize(21).allMatch(l -> l.startsWith("[INFO] alpha "));
        assertThat(log(b)).hasSize(21).allMatch(l -> l.startsWith("[INFO] beta "));
    }

    @Test
    void largeCommandsNeverOverlapButSmallOnesRunBesideThem() throws Exception {
        AtomicInteger bigNow = new AtomicInteger();
        AtomicInteger maxBig = new AtomicInteger();
        AtomicInteger allNow = new AtomicInteger();
        AtomicInteger maxAll = new AtomicInteger();
        start(config(4).maxConcurrentHeapBytes(100 * MIB)
                        .sizeEstimator((command, body, workspace) -> sizeOf(body)),
                (command, configFile) -> {
                    boolean big = config(configFile).path("size").asLong() > 50;
                    maxAll.accumulateAndGet(allNow.incrementAndGet(), Math::max);
                    if (big) {
                        maxBig.accumulateAndGet(bigNow.incrementAndGet(), Math::max);
                    }
                    try {
                        Thread.sleep(300);
                    } finally {
                        if (big) {
                            bigNow.decrementAndGet();
                        }
                        allNow.decrementAndGet();
                    }
                    return ok();
                });
        List<String> ids = List.of(submit("{\"size\":80}"), submit("{\"size\":80}"), submit("{\"size\":10}"),
                submit("{\"size\":10}"));
        for (String id : ids) {
            waitForStatus(id, "succeeded");
        }
        assertThat(maxBig.get()).as("two 80 MiB jobs in a 100 MiB budget never ran together").isEqualTo(1);
        assertThat(maxAll.get()).as("a 10 MiB job ran beside an 80 MiB one").isGreaterThanOrEqualTo(2);
    }

    @Test
    void aJobCancelledWhileWaitingForMemoryNeverRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        start(config(2).maxConcurrentHeapBytes(100 * MIB)
                        .sizeEstimator((command, body, workspace) -> sizeOf(body)),
                (command, configFile) -> {
                    runs.incrementAndGet();
                    release.await(30, TimeUnit.SECONDS);
                    return ok();
                });
        String running = submit("{\"size\":80}");
        String waiting = submit("{\"size\":80}");
        waitForStatus(running, "running");
        Thread.sleep(200);
        assertThat(status(waiting)).as("it waits for memory, on the second worker").isEqualTo("queued");

        assertThat(call("DELETE", "/v1/jobs/" + waiting, null).statusCode()).isEqualTo(200);
        release.countDown();
        waitForStatus(running, "succeeded");
        waitUntil(() -> server.inFlight() == 0);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(status(waiting)).isEqualTo("cancelled");
    }

    @Test
    void aCommandWhoseSizeCannotBeToldRunsAlone() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger now = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        start(config(2).sizeEstimator((command, body, workspace) -> {
                    throw new IllegalStateException("cannot size");
                }),
                (command, configFile) -> {
                    max.accumulateAndGet(now.incrementAndGet(), Math::max);
                    firstStarted.countDown();
                    Thread.sleep(200);
                    now.decrementAndGet();
                    return ok();
                });
        String a = submit("{}");
        String b = submit("{}");
        waitForStatus(a, "succeeded");
        waitForStatus(b, "succeeded");
        assertThat(max.get()).isEqualTo(1);
    }

    @Test
    void synchronousRequestsAlsoRunAtOnce() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        start(config(2), (command, configFile) -> {
            together.await(10, TimeUnit.SECONDS); // only returns when both run at the same time
            return ok();
        });
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            var first = clients.submit(() -> call("POST", "/sparql", "{}").statusCode());
            var second = clients.submit(() -> call("POST", "/sparql", "{}").statusCode());
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            clients.shutdownNow();
        }
    }

    @Test
    void aSynchronousRequestThatTimesOutWhileWaitingForMemoryNeverRunsAndFreesItsSlot() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        start(config(2).requestTimeout(Duration.ofSeconds(1)).maxConcurrentHeapBytes(100 * MIB)
                        .sizeEstimator((command, body, workspace) -> sizeOf(body)),
                (command, configFile) -> {
                    runs.incrementAndGet();
                    release.await(30, TimeUnit.SECONDS);
                    return ok();
                });
        String running = submit("{\"size\":80}");
        waitForStatus(running, "running");

        HttpResponse<String> waited = call("POST", "/sparql", "{\"size\":80}");
        assertThat(waited.statusCode()).isEqualTo(504);
        waitUntil(() -> server.inFlight() == 1); // only the running job holds a slot
        release.countDown();
        waitForStatus(running, "succeeded");
        waitUntil(() -> server.inFlight() == 0);
        Thread.sleep(300); // the abandoned request must not start late either
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void anErrorWhileEstimatingFreesTheSlotAndOutOfMemoryStillStopsTheServer() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean halted = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger runs = new AtomicInteger();
        server = ServeServer.start(config(2).sizeEstimator((command, body, workspace) -> {
                    if (sizeOf(body) > 0) {
                        throw new OutOfMemoryError("estimating");
                    }
                    throw new StackOverflowError("estimating");
                }).build(), (command, configFile) -> {
                    runs.incrementAndGet();
                    return ok();
                }, ServeServer.RequestCheck.NONE, () -> halted.set(true));

        String overflow = submit("{}");
        waitForStatus(overflow, "cancelled");
        waitUntil(() -> server.inFlight() == 0);
        assertThat(halted).isFalse();
        assertThat(call("POST", "/sparql", "{}").statusCode()).isEqualTo(500);
        waitUntil(() -> server.inFlight() == 0);

        String oom = submit("{\"size\":1}");
        waitForStatus(oom, "cancelled");
        waitUntil(halted::get);
        assertThat(server.inFlight()).isZero();
        assertThat(runs.get()).isZero();
    }

    @Test
    void workerSettingsOutOfRangeAreRefused() {
        ServeServer.CommandRunner runner = (command, configFile) -> ok();
        assertThatThrownBy(() -> ServeServer.start(config(0).build(), runner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--workers");
        assertThatThrownBy(() -> ServeServer.start(config(ServeServer.MAX_WORKERS + 1).build(), runner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--workers");
        assertThatThrownBy(() -> ServeServer.start(config(2).maxConcurrentHeapBytes(1000).build(), runner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--max-concurrent-heap-bytes");
    }
}
