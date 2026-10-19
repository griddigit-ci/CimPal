/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job store that can't be reached (DEP-11): {@code /ready} answers 503 and requests that need
 * the store get 503 with {@code Retry-After}, never a hang or a silent success; both recover when
 * the store is back.
 */
class JobStoreOutageTest {

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ServeServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** A memory store that fails every call while {@link #down} is set, as a lost database does. */
    static final class SwitchableStore implements JobStore {
        final MemoryJobStore inner = new MemoryJobStore(1000, Duration.ofHours(1), Duration.ofHours(1), 100,
                Long.MAX_VALUE, InstantSource.system());
        volatile boolean down;
        /** Only status() fails, as when the database blips while a job waits to start. */
        volatile boolean statusDown;

        private <T> T call(Supplier<T> s) {
            if (down) {
                throw new Unavailable("The job store failed: SQLException (SQL state 08001)", null);
            }
            return s.get();
        }

        private void run(Runnable r) {
            call(() -> {
                r.run();
                return null;
            });
        }

        @Override public Instant now() { return inner.now(); }
        @Override public void onRemoved(Consumer<UUID> listener) { inner.onRemoved(listener); }
        @Override public UUID newId() { return inner.newId(); }
        @Override public Job.Snapshot create(UUID id, String command, String label, String owner, byte[] body, UUID uploadId) {
            return call(() -> inner.create(id, command, label, owner, body, uploadId));
        }
        @Override public Job.Snapshot get(UUID id) { return call(() -> inner.get(id)); }
        @Override public JobStatus status(UUID id) {
            if (statusDown) {
                throw new Unavailable("The job store failed: SQLException (SQL state 08006)", null);
            }
            return call(() -> inner.status(id));
        }
        @Override public List<Job.Snapshot> list(JobStatus status, String owner, int limit) {
            return call(() -> inner.list(status, owner, limit));
        }
        @Override public boolean start(UUID id) { return call(() -> inner.start(id)); }
        @Override public boolean cancel(UUID id, String reason) { return call(() -> inner.cancel(id, reason)); }
        @Override public void finish(UUID id, int exitCode, String stdout, long max) { run(() -> inner.finish(id, exitCode, stdout, max)); }
        @Override public void fail(UUID id, String message) { run(() -> inner.fail(id, message)); }
        @Override public String result(UUID id) { return call(() -> inner.result(id)); }
        @Override public LogSink logSink(UUID id) { return inner.logSink(id); }
        @Override public JobLog.Slice readLog(UUID id, long from, int max) { return call(() -> inner.readLog(id, from, max)); }
        @Override public boolean timedOut(UUID id) { return call(() -> inner.timedOut(id)); }
        @Override public void evict() { run(inner::evict); }
        @Override public List<Job.Snapshot> queued() { return call(inner::queued); }
        @Override public long count(JobStatus status) { return call(() -> inner.count(status)); }
        @Override public int size() { return call(inner::size); }
        @Override public boolean usable() { return !down; }
        @Override public IdempotencyStore idempotency() { return inner.idempotency(); }
        @Override public CallbackStore callbacks() { return inner.callbacks(); }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> submit() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"validate\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void whileTheStoreIsDownReadyAndJobRequestsAre503AndBothRecover() throws Exception {
        SwitchableStore store = new SwitchableStore();
        server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                        .jobStore(store).build(),
                (command, configFile) -> new ServeServer.CommandResult(0, "{}"));
        assertThat(get("/ready").statusCode()).isEqualTo(200);

        store.down = true;

        HttpResponse<String> ready = get("/ready");
        assertThat(ready.statusCode()).isEqualTo(503);
        assertThat(ready.body()).contains("job store unavailable");
        for (HttpResponse<String> r : List.of(get("/v1/jobs"), get("/v1/jobs/" + UUID.randomUUID()), submit())) {
            assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
            assertThat(r.headers().firstValue("Retry-After")).isPresent();
            assertThat(r.body()).contains("job store is unavailable").doesNotContain("SQL state");
        }
        assertThat(get("/v1/health").statusCode()).as("liveness doesn't depend on the store").isEqualTo(200);

        store.down = false;

        assertThat(get("/ready").statusCode()).isEqualTo(200);
        assertThat(submit().statusCode()).isEqualTo(202);
        assertThat(server.inFlight()).as("no queue slot leaked by the refused submit").isLessThanOrEqualTo(1);
    }

    private HttpResponse<String> submit(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/jobs"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void aStoreBlipWhileJobsWaitToStartNeitherDropsThemNorLeaksTheirSlots() throws Exception {
        SwitchableStore store = new SwitchableStore();
        store.statusDown = true;
        server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN).queueSize(2)
                .workers(1).jobStore(store).build(), (command, configFile) -> new ServeServer.CommandResult(0, "{}"));

        for (int i = 0; i < 6; i++) { // more than --queue-size + --workers: a leaked slot would show
            assertThat(submit("{\"command\":\"validate\"}").statusCode()).isEqualTo(202);
            waitUntil(() -> server.inFlight() == 0);
        }
        assertThat(store.inner.count(JobStatus.SUCCEEDED)).isEqualTo(6);
    }

    @Test
    void aLabelWithAControlCharacterIs400AndReadinessStays() throws Exception {
        server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN).build(),
                (command, configFile) -> new ServeServer.CommandResult(0, "{}"));

        HttpResponse<String> r = submit("{\"command\":\"validate\",\"label\":\"a\u0000b\"}");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(get("/ready").statusCode()).isEqualTo(200);
    }

    @Test
    void healthNeverAsksTheStoreSoARunningJobSurvivesAnOutage() throws Exception {
        SwitchableStore store = new SwitchableStore();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                .jobStore(store).build(), (command, configFile) -> {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    return new ServeServer.CommandResult(0, "{}");
                });
        submit("{\"command\":\"validate\"}");
        waitUntil(() -> store.inner.count(JobStatus.RUNNING) == 1);

        store.down = true;
        try {
            assertThat(get("/health").statusCode()).isEqualTo(200);
            assertThat(get("/v1/health").statusCode()).isEqualTo(200);
            assertThat(get("/metrics").statusCode()).as("not enabled here: 404, never a hang").isEqualTo(404);
        } finally {
            store.down = false;
            release.countDown();
        }
    }
}
