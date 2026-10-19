/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The durable {@link JobStore} (DEP-11, D-14): jobs, their checked configs, results and logs in a
 * PostgreSQL database, so they survive a restart, a redeploy and a crash of {@code serve}.
 *
 * <ul>
 *   <li><b>Transitions</b> are single conditional {@code UPDATE}s ({@code … WHERE id = ? AND
 *       status = 'queued'}), so a cancel and a start can't both win, without locks in Java.</li>
 *   <li><b>Logs:</b> a running job's lines go to an in-memory ring (read while it runs) and to the
 *       database in batches; the last batch is written when the job ends. The database keeps the
 *       last {@code --job-log-lines} lines of each job.</li>
 *   <li><b>Results</b> live in the database, not the heap, so {@code --job-store-bytes} doesn't
 *       apply; {@code --max-result-bytes} still caps one result, and {@code --max-jobs} and
 *       {@code --job-ttl} bound the table ({@link JobEviction}).</li>
 *   <li><b>Errors:</b> a database that can't be reached gives {@link Unavailable} (503 to the
 *       caller) and {@link #usable()} false ({@code /ready} 503), and counts in {@link #errors()}.
 *       Nothing about the URL or the password reaches a message.</li>
 * </ul>
 * Every statement is a {@link PreparedStatement} with fixed SQL text; request data only ever goes
 * into parameters.
 */
final class JdbcJobStore implements JobStore, WorkArea.UploadIndex {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /** Lines written to the database at once while a job runs. */
    static final int LOG_BATCH = 64;
    private static final Duration USABLE_CACHE = Duration.ofSeconds(2);
    private static final String COLUMNS = "id, command, label, owner, status, created_at, started_at, finished_at,"
            + " exit_code, error, result IS NOT NULL AS has_result, result_dropped";

    /** Lines of one job taken for the database, numbered from {@code first}. */
    private record Batch(long first, List<String> lines) {
    }

    /**
     * A running job's log: the lines in memory and those not yet in the database. Writing it never
     * throws into the command that logs (its lines come through {@code System.err}): a failed batch
     * stays pending, up to the ring's size, and is tried again with the next one.
     */
    private final class LiveLog implements LogSink {
        final UUID id;
        final JobLog ring;
        /** Not yet in the database; the first of them gets number {@link #next} - pending.size(). */
        final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        /** The number the next line gets; guarded by this. */
        long next;
        /** Held while a batch is written, so the batches of one job go in order. */
        final Object writing = new Object();

        LiveLog(UUID id) {
            this.id = id;
            this.ring = new JobLog(logLines);
        }

        @Override
        public void append(String line) {
            String bounded = line.length() > JobLog.MAX_LINE_CHARS
                    ? line.substring(0, JobLog.MAX_LINE_CHARS) + "..." : line;
            boolean full;
            synchronized (this) {
                ring.append(bounded);
                pending.addLast(bounded);
                next++;
                if (pending.size() > logLines) {
                    pending.removeFirst(); // older than the ring: the database would drop it anyway
                }
                full = pending.size() >= LOG_BATCH;
            }
            if (full) {
                try {
                    writePending(this);
                } catch (Unavailable e) {
                    // Counted in errors(); the lines stay pending and the command carries on.
                }
            }
        }

        /** Under this lock: the pending lines with the number of the first. */
        Batch take() {
            Batch batch = new Batch(next - pending.size(), new ArrayList<>(pending));
            pending.clear();
            return batch;
        }

        /** Under this lock: a batch that couldn't be written goes back in front of what came since. */
        void putBack(Batch batch) {
            long firstKept = next - logLines;
            List<String> back = new ArrayList<>();
            for (int i = 0; i < batch.lines().size(); i++) {
                if (batch.first() + i >= firstKept) {
                    back.add(batch.lines().get(i));
                }
            }
            for (int i = back.size() - 1; i >= 0; i--) {
                pending.addFirst(back.get(i));
            }
            while (pending.size() > logLines) {
                pending.removeFirst();
            }
        }
    }

    private final JdbcPool pool;
    private final int maxJobs;
    private final Duration ttl;
    private final Duration timeout;
    private final int logLines;
    private final InstantSource clock;
    private final Map<UUID, LiveLog> live = new ConcurrentHashMap<>();
    private final AtomicLong errors = new AtomicLong();
    private volatile Consumer<UUID> onRemoved = id -> { };
    private volatile Instant usableCheckedAt = Instant.MIN;
    private volatile boolean usable = true;
    /** Log batches that couldn't be written (their lines may be missing after a crash). */
    private final AtomicLong logWriteFailures = new AtomicLong();

    /**
     * Opens the store and brings its schema up to date ({@link JdbcMigrator}).
     *
     * @throws IllegalArgumentException when the database can't be reached or migrated
     */
    JdbcJobStore(JdbcPool pool, int maxJobs, Duration ttl, Duration timeout, int logLines, InstantSource clock) {
        this.pool = pool;
        this.maxJobs = maxJobs;
        this.ttl = ttl;
        this.timeout = timeout;
        this.logLines = logLines;
        this.clock = clock;
        try (JdbcPool.Lease lease = pool.borrow()) {
            JdbcMigrator.migrate(lease.connection);
        } catch (SQLException e) {
            pool.close();
            throw new IllegalArgumentException("Cannot open the job store: " + describe(e));
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    /** What a failure was, without the URL, user or password the driver may have put in its message. */
    static String describe(SQLException e) {
        String state = e.getSQLState();
        return e.getClass().getSimpleName() + (state == null ? "" : " (SQL state " + state + ")");
    }

    // ---- plumbing ------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Sql<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T sql(Sql<T> work) {
        boolean connectionLost = true; // borrowing failed: no connection at all
        try (JdbcPool.Lease lease = pool.borrow()) {
            try {
                return work.run(lease.connection);
            } catch (SQLException e) {
                connectionLost = isConnectionFailure(e) || !lease.connection.isValid(1);
                if (connectionLost) {
                    lease.broken();
                }
                throw e;
            }
        } catch (SQLException e) {
            errors.incrementAndGet();
            if (connectionLost) {
                // Only a lost database takes the instance out of the load balancer, never a refused value.
                usable = false;
                usableCheckedAt = clock.instant();
            }
            throw new Unavailable("The job store failed: " + describe(e), e);
        }
    }

    /** SQL state class 08 (connection exception), or none at all (a socket timeout, for instance). */
    private static boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        return state == null || state.startsWith("08") || state.startsWith("57P");
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Job.Snapshot snapshot(ResultSet rs) throws SQLException {
        int exit = rs.getInt("exit_code");
        Integer exitCode = rs.wasNull() ? null : exit;
        Instant finishedAt = instant(rs, "finished_at");
        return new Job.Snapshot(rs.getObject("id", UUID.class), rs.getString("command"), rs.getString("label"),
                rs.getString("owner"), JobStatus.fromApiName(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "started_at"), finishedAt, exitCode, rs.getString("error"), finishedAt != null,
                rs.getBoolean("has_result"), rs.getBoolean("result_dropped"));
    }

    // ---- JobStore ------------------------------------------------------------------------------

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void onRemoved(Consumer<UUID> listener) {
        this.onRemoved = listener;
    }

    @Override
    public UUID newId() {
        return UUID.randomUUID(); // a collision makes create() return null, which the caller handles
    }

    @Override
    public Job.Snapshot create(UUID id, String command, String label, String owner, byte[] body, UUID uploadId) {
        Instant now = now();
        Boolean created = sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cimpal_jobs (id, command, label, owner,"
                    + " status, created_at, config, upload_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, id);
                ps.setString(2, command);
                ps.setString(3, label);
                ps.setString(4, owner);
                ps.setString(5, JobStatus.QUEUED.apiName());
                ps.setObject(6, ts(now));
                ps.setString(7, body == null ? "{}" : new String(body, StandardCharsets.UTF_8));
                ps.setObject(8, uploadId);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    return false; // the id is taken
                }
                throw e;
            }
        });
        return created ? new Job.Snapshot(id, command, label, owner, JobStatus.QUEUED, now, null, null, null, null,
                false, false, false) : null;
    }

    /** Marks running jobs past {@code --job-timeout} as timed out: one of them, or all with {@code id} null. */
    private void markTimedOut(Connection c, UUID id) throws SQLException {
        String sql = "UPDATE cimpal_jobs SET status = 'timed_out', error = ? WHERE status = 'running'"
                + " AND started_at < ?" + (id == null ? "" : " AND id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "Running longer than --job-timeout (" + timeout
                    + "); not interrupted, a later result is kept");
            ps.setObject(2, ts(now().minus(timeout)));
            if (id != null) {
                ps.setObject(3, id);
            }
            ps.executeUpdate();
        }
    }

    @Override
    public Job.Snapshot get(UUID id) {
        return sql(c -> {
            markTimedOut(c, id);
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM cimpal_jobs WHERE id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? snapshot(rs) : null;
                }
            }
        });
    }

    @Override
    public JobStatus status(UUID id) {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM cimpal_jobs WHERE id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? JobStatus.fromApiName(rs.getString(1)) : null;
                }
            }
        });
    }

    @Override
    public List<Job.Snapshot> list(JobStatus status, String owner, int limit) {
        evict();
        return sql(c -> {
            markTimedOut(c, null);
            // Fixed SQL for each combination; the values are parameters.
            String sql = "SELECT " + COLUMNS + " FROM cimpal_jobs WHERE (CAST(? AS VARCHAR(16)) IS NULL OR status = ?)"
                    + " AND (CAST(? AS VARCHAR(64)) IS NULL OR owner = ?) ORDER BY created_at DESC, id LIMIT ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                String s = status == null ? null : status.apiName();
                ps.setString(1, s);
                ps.setString(2, s);
                ps.setString(3, owner);
                ps.setString(4, owner);
                ps.setInt(5, limit);
                List<Job.Snapshot> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(snapshot(rs));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public boolean start(UUID id) {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET status = 'running', started_at = ?"
                    + " WHERE id = ? AND status = 'queued'")) {
                ps.setObject(1, ts(now()));
                ps.setObject(2, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean cancel(UUID id, String reason) {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET status = 'cancelled',"
                    + " finished_at = ?, error = ? WHERE id = ? AND status = 'queued'")) {
                ps.setObject(1, ts(now()));
                ps.setString(2, reason);
                ps.setObject(3, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void finish(UUID id, int exitCode, String stdout, long maxResultBytes) {
        flushLog(id);
        String body = stdout == null ? "" : stdout.trim();
        boolean dropped = Job.utf8Length(body) > maxResultBytes;
        String result = dropped ? null : body;
        String error = exitCode <= 1 ? null
                : exitCode == 2 ? "Bad input (exit code 2); see the result" : "Internal error (exit code " + exitCode + ")";
        sql(c -> {
            // Exit 0 and 1 are results, 2 and up failures; a job that timed out keeps that status.
            try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET exit_code = ?, finished_at = ?,"
                    + " result = ?, result_dropped = ?, retained_bytes = ?,"
                    + " error = CASE WHEN status = 'running' THEN ? ELSE error END,"
                    + " status = CASE WHEN status = 'running' THEN ? ELSE status END WHERE id = ?")) {
                ps.setInt(1, exitCode);
                ps.setObject(2, ts(now()));
                ps.setString(3, result);
                ps.setBoolean(4, dropped);
                ps.setLong(5, result == null ? 0 : 2L * result.length());
                ps.setString(6, error);
                ps.setString(7, (exitCode <= 1 ? JobStatus.SUCCEEDED : JobStatus.FAILED).apiName());
                ps.setObject(8, id);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void fail(UUID id, String message) {
        flushLog(id);
        sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET finished_at = ?, error = ?,"
                    + " status = CASE WHEN status IN ('running', 'timed_out') THEN 'failed' ELSE status END"
                    + " WHERE id = ?")) {
                ps.setObject(1, ts(now()));
                ps.setString(2, message);
                ps.setObject(3, id);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public String result(UUID id) {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT result FROM cimpal_jobs WHERE id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // ---- logs ----------------------------------------------------------------------------------

    @Override
    public LogSink logSink(UUID id) {
        return live.computeIfAbsent(id, LiveLog::new);
    }

    /**
     * Writes what a running job logged since the last batch, and forgets its ring. A failure here
     * loses those lines (counted in {@link #logWriteFailures()}); the job's status is written anyway.
     */
    private void flushLog(UUID id) {
        LiveLog log = live.remove(id);
        if (log == null) {
            return;
        }
        try {
            writePending(log);
        } catch (Unavailable e) {
            // counted; finishing the job matters more than its last lines
        }
    }

    /** Failed log writes since start (tests, and an operator wondering about missing lines). */
    long logWriteFailures() {
        return logWriteFailures.get();
    }

    /** Writes the job's pending lines; on failure they go back to pending and the error is rethrown. */
    private void writePending(LiveLog log) {
        synchronized (log.writing) { // batches of one job go in order
            Batch batch;
            synchronized (log) {
                batch = log.take();
            }
            if (batch.lines().isEmpty()) {
                return;
            }
            try {
                writeBatch(log.id, batch);
            } catch (Unavailable e) {
                logWriteFailures.incrementAndGet();
                synchronized (log) {
                    log.putBack(batch);
                }
                throw e;
            }
        }
    }

    /** Appends {@code batch} to the job's log in the database and drops lines beyond the ring's size. */
    private void writeBatch(UUID id, Batch batch) {
        List<String> lines = batch.lines();
        long first = batch.first();
        sql(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cimpal_job_log (job_id, seq, line) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < lines.size(); i++) {
                        ps.setObject(1, id);
                        ps.setLong(2, first + i);
                        ps.setString(3, lines.get(i));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                long total = first + lines.size();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cimpal_jobs SET log_lines = ? WHERE id = ? AND log_lines < ?")) {
                    ps.setLong(1, total);
                    ps.setObject(2, id);
                    ps.setLong(3, total);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM cimpal_job_log WHERE job_id = ? AND seq < ?")) {
                    ps.setObject(1, id);
                    ps.setLong(2, total - logLines);
                    ps.executeUpdate();
                }
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    @Override
    public JobLog.Slice readLog(UUID id, long from, int max) {
        LiveLog running = live.get(id);
        if (running != null) {
            return running.ring.read(from, max);
        }
        return sql(c -> {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT log_lines FROM cimpal_jobs WHERE id = ?")) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            long first = Math.max(0, total - logLines);
            long start = Math.min(Math.max(from, first), total);
            long end = Math.min(total, start + Math.max(0, max));
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT line FROM cimpal_job_log WHERE job_id = ?"
                    + " AND seq >= ? AND seq < ? ORDER BY seq")) {
                ps.setObject(1, id);
                ps.setLong(2, start);
                ps.setLong(3, end);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
            }
            return new JobLog.Slice(lines, start, start + lines.size(), from < first);
        });
    }

    // ---- housekeeping --------------------------------------------------------------------------

    @Override
    public boolean timedOut(UUID id) {
        Job.Snapshot s = get(id);
        return s != null && s.status() == JobStatus.TIMED_OUT;
    }

    @Override
    public void evict() {
        List<UUID> removed = sql(c -> {
            int stored;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM cimpal_jobs");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                stored = rs.getInt(1);
            }
            List<JobEviction.Finished> finished = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, finished_at, retained_bytes"
                    + " FROM cimpal_jobs WHERE finished_at IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    finished.add(new JobEviction.Finished(rs.getObject(1, UUID.class), rs.getString(2),
                            instant(rs, "finished_at"), rs.getLong(4)));
                }
            }
            // Results are in the database, not the heap: --job-store-bytes doesn't apply.
            List<UUID> victims = JobEviction.victims(finished, stored, 0, now(), ttl, maxJobs, Long.MAX_VALUE);
            for (UUID id : victims) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cimpal_job_log WHERE job_id = ?")) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cimpal_jobs WHERE id = ?")) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }
            }
            return victims;
        });
        removed.forEach(onRemoved);
    }

    @Override
    public List<Job.Snapshot> queued() {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM cimpal_jobs"
                    + " WHERE status = 'queued' ORDER BY created_at, id")) {
                List<Job.Snapshot> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(snapshot(rs));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public long count(JobStatus status) {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM cimpal_jobs WHERE status = ?")) {
                ps.setString(1, status.apiName());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public int size() {
        return sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM cimpal_jobs");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    static final String INTERRUPTED = "Interrupted: the server stopped while the job ran; its outputs may be"
            + " incomplete";

    @Override
    public List<Queued> recover(boolean requeue) {
        Instant now = now();
        return sql(c -> {
            if (requeue) {
                // Its log starts again from line 0, so the lines of the interrupted run go.
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cimpal_job_log WHERE job_id IN"
                        + " (SELECT id FROM cimpal_jobs WHERE status = 'running')")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET status = 'queued',"
                        + " started_at = NULL, node = NULL, lease_until = NULL, log_lines = 0"
                        + " WHERE status = 'running'")) {
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET status = 'failed',"
                        + " finished_at = ?, error = ? WHERE status = 'running'")) {
                    ps.setObject(1, ts(now));
                    ps.setString(2, INTERRUPTED);
                    ps.executeUpdate();
                }
            }
            // A job that had timed out was still running too: it ends now, and keeps its status.
            try (PreparedStatement ps = c.prepareStatement("UPDATE cimpal_jobs SET finished_at = ?"
                    + " WHERE status = 'timed_out' AND finished_at IS NULL")) {
                ps.setObject(1, ts(now));
                ps.executeUpdate();
            }
            List<Queued> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + ", config, upload_id"
                    + " FROM cimpal_jobs WHERE status = 'queued' ORDER BY created_at, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Queued(snapshot(rs), rs.getString("config").getBytes(StandardCharsets.UTF_8),
                            rs.getObject("upload_id", UUID.class)));
                }
            }
            return out;
        });
    }

    // ---- uploads (DEP-7 bookkeeping across restarts) ---------------------------------------------

    @Override
    public WorkArea.UploadIndex uploadIndex() {
        return this;
    }

    @Override
    public void saved(WorkArea.UploadRecord upload) {
        tools.jackson.databind.node.ArrayNode files = JSON.createArrayNode();
        upload.files().forEach(f -> files.addObject().put("name", f.name()).put("size", f.size()));
        sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO cimpal_uploads (id, owner, created_at,"
                    + " expires_at, total_bytes, charged_bytes, files) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, upload.id());
                ps.setString(2, upload.owner());
                ps.setObject(3, ts(upload.createdAt()));
                ps.setObject(4, ts(upload.expiresAt()));
                ps.setLong(5, upload.files().stream().mapToLong(eu.griddigit.cimpal.core.utils.ZipExtractor.Entry::size)
                        .sum());
                ps.setLong(6, upload.chargedBytes());
                ps.setString(7, JSON.writeValueAsString(files));
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void removed(UUID uploadId) {
        sql(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cimpal_uploads WHERE id = ?")) {
                ps.setObject(1, uploadId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<WorkArea.UploadRecord> uploadRecords() {
        return sql(c -> {
            List<WorkArea.UploadRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, created_at, expires_at, charged_bytes,"
                    + " files FROM cimpal_uploads ORDER BY created_at, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<eu.griddigit.cimpal.core.utils.ZipExtractor.Entry> files = new ArrayList<>();
                    for (tools.jackson.databind.JsonNode f : JSON.readTree(rs.getString("files"))) {
                        files.add(new eu.griddigit.cimpal.core.utils.ZipExtractor.Entry(f.path("name").asString(),
                                f.path("size").asLong()));
                    }
                    out.add(new WorkArea.UploadRecord(rs.getObject("id", UUID.class), rs.getString("owner"),
                            instant(rs, "created_at"), instant(rs, "expires_at"), rs.getLong("charged_bytes"),
                            List.copyOf(files)));
                }
            }
            return out;
        });
    }

    @Override
    public List<WorkArea.JobRef> jobRefs() {
        return sql(c -> {
            List<WorkArea.JobRef> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, upload_id, status FROM cimpal_jobs"
                    + " WHERE upload_id IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorkArea.JobRef(rs.getObject("id", UUID.class), rs.getString("owner"),
                            rs.getObject("upload_id", UUID.class), "queued".equals(rs.getString("status"))));
                }
            }
            return out;
        });
    }

    @Override
    public boolean usable() {
        Instant now = clock.instant();
        if (Duration.between(usableCheckedAt, now).compareTo(USABLE_CACHE) < 0) {
            return usable;
        }
        boolean ok;
        try (JdbcPool.Lease lease = pool.borrow()) {
            ok = lease.connection.isValid(2);
            if (!ok) {
                lease.broken();
            }
        } catch (SQLException e) {
            ok = false;
        }
        usable = ok;
        usableCheckedAt = now;
        return ok;
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public long errors() {
        return errors.get();
    }

    @Override
    public void close() {
        pool.close();
    }
}
