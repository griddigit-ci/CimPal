/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The JDBC job store (DEP-11) on H2 in PostgreSQL mode: transitions, logs, eviction and restart recovery. */
class JdbcJobStoreTest {

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private final List<JdbcJobStore> opened = new ArrayList<>();
    private String url;

    /** A new, empty database for one test: H2 here, a PostgreSQL schema in {@code JdbcJobStorePostgresIT}. */
    String newDatabase() {
        return TestStores.h2Url();
    }

    String user() {
        return "sa";
    }

    String password() {
        return "";
    }

    @org.junit.jupiter.api.BeforeEach
    void database() {
        url = newDatabase();
    }

    @AfterEach
    void close() {
        opened.forEach(JdbcJobStore::close);
    }

    private JdbcJobStore open(int maxJobs, int logLines) {
        JdbcJobStore store = new JdbcJobStore(new JdbcPool(url, user(), password(), 8), maxJobs, Duration.ofHours(1),
                Duration.ofMinutes(10), logLines, clock);
        opened.add(store);
        return store;
    }

    private JdbcJobStore open() {
        return open(1000, 100);
    }

    private static Job.Snapshot create(JobStore store, String owner) {
        return store.create(UUID.randomUUID(), "validate", "nightly", owner,
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void aJobGoesThroughItsStatesAndKeepsItsResult() {
        JdbcJobStore store = open();
        Job.Snapshot job = create(store, "alice");

        assertThat(store.get(job.id())).isEqualTo(job);
        assertThat(store.create(job.id(), "validate", null, "alice", null, null)).as("the id is taken").isNull();
        assertThat(store.start(job.id())).isTrue();
        assertThat(store.start(job.id())).as("only a queued job starts").isFalse();
        assertThat(store.cancel(job.id(), "too late")).isFalse();
        clock.advance(Duration.ofSeconds(5));
        store.finish(job.id(), 1, "  {\"rows\":1}\n", 1000);

        Job.Snapshot done = store.get(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.hasViolations()).isTrue();
        assertThat(done.finished()).isTrue();
        assertThat(done.owner()).isEqualTo("alice");
        assertThat(done.label()).isEqualTo("nightly");
        assertThat(Duration.between(done.startedAt(), done.finishedAt())).isEqualTo(Duration.ofSeconds(5));
        assertThat(store.result(job.id())).isEqualTo("{\"rows\":1}");
        assertThat(store.count(JobStatus.SUCCEEDED)).isEqualTo(1);
    }

    @Test
    void failuresTooLargeResultsAndTimeoutsAreKeptAsTheMemoryStoreKeepsThem() {
        JdbcJobStore store = open();
        Job.Snapshot bad = create(store, "a");
        store.start(bad.id());
        store.finish(bad.id(), 2, "{\"error\":\"x\"}", 1000);
        assertThat(store.get(bad.id()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(store.get(bad.id()).error()).contains("exit code 2");

        Job.Snapshot big = create(store, "a");
        store.start(big.id());
        store.finish(big.id(), 0, "x".repeat(2000), 1000);
        assertThat(store.get(big.id()).resultDropped()).isTrue();
        assertThat(store.result(big.id())).isNull();

        Job.Snapshot slow = create(store, "a");
        store.start(slow.id());
        clock.advance(Duration.ofMinutes(11));
        assertThat(store.timedOut(slow.id())).isTrue();
        store.finish(slow.id(), 0, "{}", 1000);
        assertThat(store.get(slow.id()).status()).as("a timed-out job keeps that status").isEqualTo(JobStatus.TIMED_OUT);

        Job.Snapshot crashed = create(store, "a");
        store.start(crashed.id());
        store.fail(crashed.id(), "Internal error");
        assertThat(store.get(crashed.id()).status()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void aCancelRacingAStartNeverBothWin() throws Exception {
        JdbcJobStore store = open();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                UUID id = create(store, "a").id();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Boolean>> starts = new ArrayList<>();
                List<Future<Boolean>> cancels = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    starts.add(pool.submit(() -> {
                        go.await();
                        return store.start(id);
                    }));
                    cancels.add(pool.submit(() -> {
                        go.await();
                        return store.cancel(id, "race");
                    }));
                }
                go.countDown();
                int wins = 0;
                for (Future<Boolean> f : starts) {
                    wins += f.get(10, TimeUnit.SECONDS) ? 1 : 0;
                }
                for (Future<Boolean> f : cancels) {
                    wins += f.get(10, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertThat(wins).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLogIsReadWhileItRunsAndFromTheDatabaseAfterwardsKeepingTheLastLines() {
        JdbcJobStore store = open(1000, 100);
        Job.Snapshot job = create(store, "a");
        store.start(job.id());
        LogSink sink = store.logSink(job.id());
        for (int i = 0; i < 250; i++) {
            sink.append("line " + i);
        }
        assertThat(store.readLog(job.id(), 0, 5).lines()).startsWith("line 150");

        store.finish(job.id(), 0, "{}", 1000);
        JobLog.Slice slice = store.readLog(job.id(), 0, 1000);
        assertThat(slice.truncated()).isTrue();
        assertThat(slice.offset()).isEqualTo(150);
        assertThat(slice.nextOffset()).isEqualTo(250);
        assertThat(slice.lines()).hasSize(100).first().isEqualTo("line 150");
        assertThat(store.readLog(job.id(), 245, 10).lines()).containsExactly("line 245", "line 246", "line 247",
                "line 248", "line 249");
    }

    @Test
    void evictionDropsTheHeaviestClientsOldestJobsAndTellsTheServer() {
        JdbcJobStore store = open(3, 100);
        List<UUID> removed = new ArrayList<>();
        store.onRemoved(removed::add);
        Job.Snapshot bobs = create(store, "bob");
        store.start(bobs.id());
        store.finish(bobs.id(), 0, "{}", 1000);
        List<UUID> alices = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(1));
            Job.Snapshot a = create(store, "alice");
            store.start(a.id());
            store.finish(a.id(), 0, "{}", 1000);
            alices.add(a.id());
        }

        store.evict();

        assertThat(removed).containsExactlyInAnyOrder(alices.get(0), alices.get(1));
        assertThat(store.get(bobs.id())).isNotNull();
        assertThat(store.size()).isEqualTo(3);
        clock.advance(Duration.ofHours(2));
        store.evict();
        assertThat(store.size()).as("past the TTL").isZero();
    }

    @Test
    void afterARestartRunningJobsAreInterruptedAndQueuedOnesComeBackWithTheirConfig() {
        JdbcJobStore before = open();
        Job.Snapshot running = create(before, "a");
        before.start(running.id());
        before.logSink(running.id()).append("half way");
        UUID upload = UUID.randomUUID();
        Job.Snapshot queued = before.create(UUID.randomUUID(), "sparql", null, "b",
                "{\"q\":2}".getBytes(StandardCharsets.UTF_8), upload);
        Job.Snapshot finished = create(before, "a");
        before.start(finished.id());
        before.finish(finished.id(), 0, "{\"ok\":true}", 1000);

        JdbcJobStore after = open(); // the same database, as a new process would see it
        List<JobStore.Queued> back = after.recover(false);

        assertThat(after.get(running.id()).status()).isEqualTo(JobStatus.FAILED);
        assertThat(after.get(running.id()).error()).startsWith("Interrupted");
        assertThat(back).singleElement().satisfies(q -> {
            assertThat(q.job().id()).isEqualTo(queued.id());
            assertThat(new String(q.body(), StandardCharsets.UTF_8)).isEqualTo("{\"q\":2}");
            assertThat(q.uploadId()).isEqualTo(upload);
        });
        assertThat(after.result(finished.id())).isEqualTo("{\"ok\":true}");
    }

    @Test
    void withRequeueARunningJobIsQueuedAgain() {
        JdbcJobStore before = open();
        Job.Snapshot running = create(before, "a");
        before.start(running.id());

        List<JobStore.Queued> back = open().recover(true);

        assertThat(back).extracting(q -> q.job().id()).containsExactly(running.id());
        assertThat(back.getFirst().job().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(back.getFirst().job().startedAt()).isNull();
    }

    @Test
    void theSchemaIsAppliedOnceAndANewerOneIsRefused() throws Exception {
        open();
        open(); // migrating again does nothing
        try (Connection c = DriverManager.getConnection(url, user(), password());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO cimpal_schema_version (version, applied_at) VALUES (99, CURRENT_TIMESTAMP)");
        }
        assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class).hasMessageContaining("newer");
    }

    @Test
    void anUnreachableDatabaseIsUnavailableNeverAnotherError() {
        JdbcJobStore store = open();
        Job.Snapshot job = create(store, "a");
        store.close();

        assertThatThrownBy(() -> store.get(job.id())).isInstanceOf(JobStore.Unavailable.class)
                .hasMessageNotContaining(url);
        clock.advance(Duration.ofSeconds(3));
        assertThat(store.usable()).isFalse();
        assertThat(store.errors()).isPositive();
    }

    /** Every SQL text is fixed: built only from literals and the store's own constants, never from data. */
    @Test
    void everyStatementIsPreparedFromFixedText() throws Exception {
        String source = Files.readString(Path.of("src/main/java/eu/griddigit/CimPal/cli/command/JdbcJobStore.java"));
        assertThat(source).doesNotContain("createStatement(", ".execute(\"", "executeQuery(\"");
        Matcher m = Pattern.compile("prepareStatement\\(([^;]*?)\\)\\)?\\s*[{;]", Pattern.DOTALL).matcher(source);
        int found = 0;
        while (m.find()) {
            found++;
            String expression = m.group(1).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "").replaceAll("[\\s+()]", "");
            // Only the store's own constant column lists (COLUMNS, CALLBACK_COLUMNS) or a fixed `sql` local.
            assertThat(expression).as("SQL text of: %s", m.group(1)).matches("(COLUMNS|CALLBACK_COLUMNS|sql)?");
        }
        assertThat(found).isGreaterThan(15);
        Matcher dynamic = Pattern.compile("String sql = ([^;]*);", Pattern.DOTALL).matcher(source);
        while (dynamic.find()) {
            String expression = dynamic.group(1).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "")
                    .replaceAll("[\\s+()]", "");
            assertThat(expression).as("SQL text of: %s", dynamic.group(1)).matches("(COLUMNS|id==null\\?:)*");
        }
    }

    // ---- from the security review (DEP-11) ----------------------------------------------------

    @Test
    void aRequeuedJobWithALogFromBeforeTheRestartRunsAgainWithAFreshLog() {
        JdbcJobStore before = open(1000, 100);
        Job.Snapshot job = create(before, "a");
        before.start(job.id());
        LogSink first = before.logSink(job.id());
        for (int i = 0; i < 150; i++) {
            first.append("first run " + i); // two batches reach the database
        }

        JdbcJobStore after = open(1000, 100);
        assertThat(after.recover(true)).extracting(q -> q.job().id()).containsExactly(job.id());
        after.start(job.id());
        LogSink second = after.logSink(job.id());
        for (int i = 0; i < 70; i++) {
            second.append("second run " + i);
        }
        after.finish(job.id(), 0, "{}", 1000);

        assertThat(after.get(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.logWriteFailures()).isZero();
        JobLog.Slice log = after.readLog(job.id(), 0, 1000);
        assertThat(log.lines()).hasSize(70).first().isEqualTo("second run 0");
    }

    @Test
    void aFailingLogWriteNeverReachesTheCommandThatLogs() {
        JdbcJobStore store = open(1000, 100);
        Job.Snapshot job = create(store, "a");
        store.start(job.id());
        LogSink sink = store.logSink(job.id());
        store.close(); // the database is gone while the command runs

        for (int i = 0; i < 200; i++) {
            sink.append("line " + i); // no exception reaches the command
        }

        assertThat(store.logWriteFailures()).isPositive();
        assertThat(store.readLog(job.id(), 0, 5).lines()).as("still readable while it runs").isNotEmpty();
    }

    @Test
    void aRefusedValueIsAnErrorButTheStoreStaysUsable() {
        JdbcJobStore store = open();
        assertThatThrownBy(() -> store.create(UUID.randomUUID(), "validate", "x".repeat(500), "a", null, null))
                .isInstanceOf(JobStore.Unavailable.class);
        clock.advance(Duration.ofSeconds(3));
        assertThat(store.usable()).as("a value the database refuses is no outage").isTrue();
    }
}
