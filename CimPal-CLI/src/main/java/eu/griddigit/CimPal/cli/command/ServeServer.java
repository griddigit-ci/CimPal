/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.griddigit.CimPal.cli.CliVersion;
import eu.griddigit.CimPal.cli.OutOfMemoryExit;
import eu.griddigit.CimPal.cli.command.TokenSource.Client;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.OutOfMemoryRethrow;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP server behind {@code serve}, separated from the picocli command so it can be started
 * in-process on an ephemeral port with a stub {@link CommandRunner} in tests.
 *
 * <h2>Request pipeline</h2>
 * <p>Every request passes, in order: Host allowlist (403), Origin allowlist (403), exact path
 * (404), method (405), bearer token (401, not for {@code GET /health}), {@code application/json}
 * body on POST (415), body limit (413). Commands then take a slot in a bounded queue (503 when
 * full) and run on {@code --workers} threads, one by default. With more than one, an
 * {@link AdmissionGate} starts a command only while the estimated heap of the running ones fits
 * {@code --max-concurrent-heap-bytes}, and each command's path policy, caches and output are its
 * own (DEP-8). A request that has not finished within the timeout gets 504.
 *
 * <p>A timed-out command is left to finish rather than interrupted, because a command cut off in
 * the middle of writing could leave truncated reports behind. It keeps its queue slot until it
 * returns, so the queue bound stays honest. A command that timed out while still queued never
 * runs.
 *
 * <h2>Job API ({@code /v1}, DEP-5)</h2>
 * <p>{@code POST /v1/jobs} queues a command and answers 202 at once; the caller polls
 * {@code GET /v1/jobs/{id}} and fetches {@code /result} and {@code /log}. Jobs share the workers and
 * the queue slots with the synchronous endpoints. Paths in a
 * job's config are checked when it is submitted (403 then, not later). Every error under
 * {@code /v1} is {@code application/problem+json}; {@code GET /v1/health} and
 * {@code GET /v1/openapi.json} need no token. The routes are {@link #V1_ROUTES}; the OpenAPI
 * document {@code openapi/cimpal-v1.json} describes them.
 *
 * <h2>Service deployment (DEP-6)</h2>
 * <p>Behind a reverse proxy, {@code --allowed-host} adds the public names the Host check accepts.
 * The tokens come from a {@link TokenSource}, which may be a reloadable secret file.
 * {@code GET /ready} (no token) is 200 while the server takes work and 503 when it is shutting
 * down or its queue is full. With {@code --metrics}, {@code GET /metrics} (token) serves
 * Prometheus text. Server events go to a {@link ServeLog}, as text or JSON lines.
 *
 * <h2>Clients (DEP-13)</h2>
 * <p>Each token stands for a {@link Client}. Jobs and uploads belong to the client that created
 * them: a client sees, lists, reads, downloads and cancels only its own, and anything else answers
 * 404 as if it didn't exist; an admin sees everything. {@code /shutdown} and {@code /metrics} need
 * an admin. {@link ClientQuotas} limit each client's queued and running jobs (429), uploads (413),
 * share of the work area (507) and requests per minute (429), and {@link JobScheduler} lets other
 * clients' jobs overtake those of a client at its {@code maxRunning}. Logs and metrics carry the
 * client's name, never its token.
 */
final class ServeServer implements AutoCloseable {

    /** Commands exposed as POST endpoints. Must match the picocli command names exactly. */
    static final List<String> COMMANDS = List.of(
            "validate", "sparql", "convert", "compare", "compare-instances",
            "rdfs2shacl", "organize", "excel2shacl", "gen-instances", "manifest"
    );

    static final String VERSION = CliVersion.displayName();

    /** Upper bounds for the operator-set limits. */
    static final int MAX_QUEUE_SIZE = 64;
    static final int MAX_WORKERS = 64;
    static final long MIN_CONCURRENT_HEAP_BYTES = 1L << 20;
    static final long MAX_BODY_BYTES = 64L * 1024 * 1024;

    /** Handler threads beyond those that can be waiting on a command. */
    static final int SPARE_HTTP_THREADS = 16;

    static final String MAX_REQ_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    static final int REQUEST_READ_SECONDS = 60;

    /** The job API's version and its limits (DEP-5). */
    static final String API_VERSION = "1";
    static final int MIN_MAX_JOBS = 100;
    static final int MAX_MAX_JOBS = 100_000;
    static final int MAX_JOB_LOG_LINES = 100_000;
    static final long MIN_RESULT_BYTES = 1024;
    static final long MAX_RESULT_BYTES = 256L * 1024 * 1024;
    static final long MIN_JOB_STORE_BYTES = 1024L * 1024;
    static final int MAX_LABEL_CHARS = 200;
    static final int DEFAULT_LIST_LIMIT = 50;
    static final int MAX_LIST_LIMIT = 1000;
    static final int MAX_LOG_PAGE = 1000;

    /** Every {@code /v1} route, as method and path template; the OpenAPI document must match. */
    static final List<String> V1_ROUTES = List.of(
            "GET /v1/health",
            "GET /v1/openapi.json",
            "POST /v1/jobs",
            "GET /v1/jobs",
            "GET /v1/jobs/{jobId}",
            "DELETE /v1/jobs/{jobId}",
            "GET /v1/jobs/{jobId}/result",
            "GET /v1/jobs/{jobId}/log",
            "POST /v1/uploads",
            "GET /v1/uploads/{uploadId}",
            "DELETE /v1/uploads/{uploadId}",
            "GET /v1/jobs/{jobId}/files",
            "GET /v1/jobs/{jobId}/files/{path}",
            "GET /v1/jobs/{jobId}/archive"
    );

    /** File exchange limits (DEP-7). */
    static final long MIN_UPLOAD_BYTES = 1024;
    static final long MAX_UPLOAD_BYTES = 16L << 30;
    static final long MIN_WORK_MAX_BYTES = 1L << 20;
    static final String ZIP = "application/zip";

    private static final String OPENAPI_RESOURCE = "/openapi/cimpal-v1.json";
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The exchange attribute that holds the authenticated client's name, for the log. */
    private static final String CLIENT_ATTRIBUTE = "cimpal.client";

    /** What a command printed on stdout (its JSON) and its exit code. */
    record CommandResult(int exitCode, String stdout) {
    }

    /** Runs one CimPal subcommand with {@code --config configFile}. */
    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(String command, Path configFile) throws Exception;

        /** For a job with an uploaded input (DEP-7); by default as without one. */
        default CommandResult run(String command, Path configFile, WorkArea.JobWorkspace workspace) throws Exception {
            return run(command, configFile);
        }
    }

    /**
     * Checks (and may rewrite) a request body before it is queued, e.g. the path policy of
     * {@code serve}. Throws {@link Rejected} to answer with that status instead.
     */
    @FunctionalInterface
    interface RequestCheck {
        byte[] check(String command, byte[] body) throws Rejected;

        /** For a job with an uploaded input (DEP-7); by default as without one. */
        default byte[] check(String command, byte[] body, WorkArea.JobWorkspace workspace) throws Rejected {
            return check(command, body);
        }

        RequestCheck NONE = (command, body) -> body;
    }

    /**
     * Estimates the heap a checked command will need, for the admission of {@code --workers}
     * (DEP-8). {@code workspace} is the job's upload, or null.
     */
    @FunctionalInterface
    interface SizeEstimator {
        long estimate(String command, byte[] checkedBody, WorkArea.JobWorkspace workspace);

        SizeEstimator NONE = (command, body, workspace) -> 0;
    }

    /** A request refused by a {@link RequestCheck}; the message goes to the caller. */
    static final class Rejected extends Exception {
        private final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Server settings; {@link #builder()} holds the defaults. */
    record Config(String host, int port, TokenSource tokens, Set<String> allowedOrigins, boolean allowRemote,
                  long maxBodyBytes, int queueSize, Duration requestTimeout,
                  Duration jobTimeout, int maxJobs, Duration jobTtl, int jobLogLines, long maxResultBytes,
                  long jobStoreBytes, Set<String> allowedHosts, boolean metrics, ServeLog log,
                  Path workRoot, long maxUploadBytes, Duration uploadTtl, long workMaxBytes, Duration requestReadTime,
                  int workers, long maxConcurrentHeapBytes, SizeEstimator sizeEstimator,
                  ClientQuotas.Settings clientQuotas) {

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String host = "localhost";
            private int port = 7474;
            private String token;
            private TokenSource tokens;
            private Set<String> allowedOrigins = Set.of();
            private Set<String> allowedHosts = Set.of();
            private boolean metrics;
            private ServeLog log = ServeLog.text();
            private boolean allowRemote;
            private long maxBodyBytes = 1024 * 1024;
            private int queueSize = 4;
            private Duration requestTimeout = Duration.ofMinutes(30);
            private Duration jobTimeout = Duration.ofHours(2);
            private int maxJobs = 1000;
            private Duration jobTtl = Duration.ofHours(24);
            private int jobLogLines = 5000;
            private long maxResultBytes = 16L * 1024 * 1024;
            /** Results and logs of finished jobs together; by default a quarter of the heap. */
            private long jobStoreBytes = Runtime.getRuntime().maxMemory() / 4;
            /** Null: no file exchange (the upload and download routes answer 404). */
            private Path workRoot;
            private long maxUploadBytes = 2L << 30;
            private Duration uploadTtl = Duration.ofHours(24);
            private long workMaxBytes = 20L << 30;
            private Duration requestReadTime = Duration.ofSeconds(REQUEST_READ_SECONDS);
            private int workers = 1;
            /** By default three quarters of the heap. */
            private long maxConcurrentHeapBytes = Runtime.getRuntime().maxMemory() / 4 * 3;
            private SizeEstimator sizeEstimator = SizeEstimator.NONE;
            /** Per-client limits (DEP-13); by default every client gets the server's limits. */
            private ClientQuotas.Settings clientQuotas = ClientQuotas.Settings.NONE;

            Builder host(String host) { this.host = host; return this; }
            Builder port(int port) { this.port = port; return this; }
            Builder token(String token) { this.token = token; return this; }
            /** Instead of {@link #token(String)}: e.g. a reloadable token file (DEP-6). */
            Builder tokens(TokenSource tokens) { this.tokens = tokens; return this; }
            /** {@code --allowed-host} values, already normalised by {@link ServeSecurity#allowedHostEntry}. */
            Builder allowedHosts(Set<String> hosts) { this.allowedHosts = Set.copyOf(hosts); return this; }
            Builder metrics(boolean metrics) { this.metrics = metrics; return this; }
            Builder log(ServeLog log) { this.log = log; return this; }
            Builder allowedOrigins(Set<String> origins) { this.allowedOrigins = Set.copyOf(origins); return this; }
            Builder allowRemote(boolean allowRemote) { this.allowRemote = allowRemote; return this; }
            Builder maxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; return this; }
            Builder queueSize(int queueSize) { this.queueSize = queueSize; return this; }
            Builder requestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; return this; }
            Builder jobTimeout(Duration jobTimeout) { this.jobTimeout = jobTimeout; return this; }
            Builder maxJobs(int maxJobs) { this.maxJobs = maxJobs; return this; }
            Builder jobTtl(Duration jobTtl) { this.jobTtl = jobTtl; return this; }
            Builder jobLogLines(int jobLogLines) { this.jobLogLines = jobLogLines; return this; }
            Builder maxResultBytes(long maxResultBytes) { this.maxResultBytes = maxResultBytes; return this; }
            Builder jobStoreBytes(long jobStoreBytes) { this.jobStoreBytes = jobStoreBytes; return this; }
            Builder workRoot(Path workRoot) { this.workRoot = workRoot; return this; }
            Builder maxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; return this; }
            Builder uploadTtl(Duration uploadTtl) { this.uploadTtl = uploadTtl; return this; }
            Builder workMaxBytes(long workMaxBytes) { this.workMaxBytes = workMaxBytes; return this; }
            Builder requestReadTime(Duration requestReadTime) { this.requestReadTime = requestReadTime; return this; }
            Builder workers(int workers) { this.workers = workers; return this; }
            Builder maxConcurrentHeapBytes(long bytes) { this.maxConcurrentHeapBytes = bytes; return this; }
            Builder sizeEstimator(SizeEstimator sizeEstimator) { this.sizeEstimator = sizeEstimator; return this; }
            Builder clientQuotas(ClientQuotas.Settings clientQuotas) { this.clientQuotas = clientQuotas; return this; }

            Config build() {
                TokenSource source = tokens != null ? tokens
                        : token == null || token.isBlank() ? null : TokenSource.fixed(token);
                return new Config(host, port, source, allowedOrigins, allowRemote, maxBodyBytes, queueSize,
                        requestTimeout, jobTimeout, maxJobs, jobTtl, jobLogLines, maxResultBytes, jobStoreBytes,
                        allowedHosts, metrics, log, workRoot, maxUploadBytes, uploadTtl, workMaxBytes, requestReadTime,
                        workers, maxConcurrentHeapBytes, sizeEstimator, clientQuotas);
            }
        }
    }

    private final Config config;
    private final CommandRunner runner;
    private final RequestCheck requestCheck;
    /** Ends the JVM after a command ran out of memory ({@link OutOfMemoryExit#halt()}; tests record it). */
    private final Runnable onOutOfMemory;
    private HttpServer http;
    private final ExecutorService handlers;
    private final ThreadPoolExecutor worker;
    private final Semaphore slots;
    private final int totalSlots;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    /** The commands running now; empty while the workers are idle. */
    private final Map<Object, Running> running = new ConcurrentHashMap<>();
    /** Keeps the estimated heap of the running commands within --max-concurrent-heap-bytes (DEP-8). */
    private final AdmissionGate admission;
    /** The job store of the {@code /v1} API. */
    private final JobManager jobs;
    /** The worker tasks of queued jobs, so a cancelled job's task (and its body) leaves the queue at once. */
    private final Map<UUID, Future<?>> queuedTasks = new ConcurrentHashMap<>();
    private final ServeLog log;
    private final ServeMetrics metrics = new ServeMetrics();
    /** Uploads and job workspaces (DEP-7), or null without {@code --work-root}. */
    private WorkArea workArea;
    /** The workspace of each job that uses an upload. */
    private final Map<UUID, WorkArea.JobWorkspace> workspaces = new ConcurrentHashMap<>();
    /** Each client's limits (DEP-13). */
    private final ClientQuotas quotas;
    /** Which queued job runs next. */
    private final JobScheduler scheduler;
    /** Held while a submit checks its client's quota and queues the job, so two can't both pass. */
    private final Object submitLock = new Object();

    private ServeServer(Config config, CommandRunner runner, RequestCheck requestCheck, Runnable onOutOfMemory,
                        InstantSource clock) {
        this.config = config;
        this.runner = runner;
        this.requestCheck = requestCheck;
        this.onOutOfMemory = onOutOfMemory;
        this.log = config.log();
        this.jobs = new JobManager(config.maxJobs(), config.jobTtl(), config.jobTimeout(), config.jobLogLines(),
                config.jobStoreBytes(), clock);
        this.totalSlots = config.queueSize() + config.workers();
        this.slots = new Semaphore(totalSlots);
        // The JDK server reads request lines, headers and bodies on these threads too, and up to
        // totalSlots of them wait on a running or queued command. SPARE_HTTP_THREADS more stay
        // for reading new requests, /health and 503 rejections, and REQUEST_READ_SECONDS stops a
        // slow client from holding one of them indefinitely.
        this.handlers = Executors.newFixedThreadPool(totalSlots + SPARE_HTTP_THREADS,
                daemonThreads("cimpal-serve-http"));
        // --workers threads, and a queue from which a cancelled task can be removed (remove(Runnable)).
        this.worker = new ThreadPoolExecutor(config.workers(), config.workers(), 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreads("cimpal-serve-worker"));
        this.admission = new AdmissionGate(config.maxConcurrentHeapBytes());
        // maxQueued: no limit of a client's own unless the file sets one; the queue bounds it anyway.
        this.quotas = new ClientQuotas(config.clientQuotas(), new ClientQuotas.Limits(ClientQuotas.NO_QUEUE_LIMIT,
                config.workers(), config.maxUploadBytes(), config.workMaxBytes(), 0), clock);
        this.scheduler = new JobScheduler(owner -> quotas.limits(owner).maxRunning());
    }

    /** A running command: its job (null for a synchronous request) and when it started (System.nanoTime). */
    private static final class Running {
        final Job job;
        final long since;
        /** Health is polled without a token: a stall is logged once per command, not once per poll. */
        final AtomicBoolean stallWarned = new AtomicBoolean();

        Running(Job job, long since) {
            this.job = job;
            this.since = since;
        }
    }

    /**
     * Binds and starts the server.
     *
     * @throws IllegalArgumentException for an invalid configuration, e.g. a non-loopback host
     *                                  without {@code allowRemote}
     * @throws IOException              when the address can't be bound
     */
    static ServeServer start(Config config, CommandRunner runner) throws IOException {
        return start(config, runner, RequestCheck.NONE);
    }

    /** {@link #start(Config, CommandRunner)} with a check that runs on every command request first. */
    static ServeServer start(Config config, CommandRunner runner, RequestCheck requestCheck) throws IOException {
        return start(config, runner, requestCheck, OutOfMemoryExit::halt);
    }

    /** As above, with what to do after a command ran out of memory (tests; the default halts). */
    static ServeServer start(Config config, CommandRunner runner, RequestCheck requestCheck,
                             Runnable onOutOfMemory) throws IOException {
        return start(config, runner, requestCheck, onOutOfMemory, InstantSource.system());
    }

    /** As above, with the clock of the job API (tests advance it; the default is the system clock). */
    static ServeServer start(Config config, CommandRunner runner, RequestCheck requestCheck,
                             Runnable onOutOfMemory, InstantSource clock) throws IOException {
        Objects.requireNonNull(requestCheck, "requestCheck");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(onOutOfMemory, "onOutOfMemory");
        Objects.requireNonNull(clock, "clock");
        validate(config);
        limitRequestReadTime(config.requestReadTime());
        ServeServer server = new ServeServer(config, runner, requestCheck, onOutOfMemory, clock);
        if (config.workRoot() != null) {
            try {
                server.workArea = new WorkArea(config.workRoot(), config.maxUploadBytes(), config.workMaxBytes(),
                        config.uploadTtl(), clock);
            } catch (IOException e) {
                server.handlers.shutdownNow();
                server.worker.shutdownNow();
                throw new IllegalArgumentException("Cannot use --work-root " + config.workRoot() + ": " + e.getMessage());
            }
            server.jobs.onRemoved(id -> {
                server.workspaces.remove(id);
                server.workArea.deleteWorkspace(id);
            });
        }
        try {
            server.http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        } catch (IOException | RuntimeException e) {
            server.handlers.shutdownNow();
            server.worker.shutdownNow();
            throw e;
        }
        server.http.setExecutor(server.handlers);
        server.http.createContext("/", server::handle);
        server.http.start();
        return server;
    }

    /**
     * The JDK server has no read timeout by default, so a client that sends its request slowly
     * holds a handler thread forever. {@code sun.net.httpserver.maxReqTime} (seconds, read once
     * when the JDK server first loads) bounds the time to receive a request; an explicit
     * {@code -D} setting wins. {@code maxRspTime} is deliberately left alone: its clock starts
     * when the request has been read, so it would cut off long-running commands.
     *
     * <p>Caveats: the setting is JVM-wide; it has no effect if something else in this JVM created
     * an {@code HttpServer} first; and a request body that takes longer than this to upload (e.g.
     * a large body over a slow {@code --allow-remote} link) is dropped. It bounds how long a
     * connection can be held, not how many a local client can open.
     */
    private static void limitRequestReadTime(Duration readTime) {
        if (System.getProperty(MAX_REQ_TIME_PROPERTY) == null) {
            System.setProperty(MAX_REQ_TIME_PROPERTY, Long.toString(Math.max(1, readTime.toSeconds())));
        }
    }

    private static void validate(Config config) {
        if (config.tokens() == null) {
            throw new IllegalArgumentException("A token is required.");
        }
        if (!ServeSecurity.isLoopback(config.host()) && !config.allowRemote()) {
            throw new IllegalArgumentException("--host " + config.host()
                    + " is not a loopback address; binding to it requires --allow-remote.");
        }
        if (config.maxBodyBytes() <= 0 || config.maxBodyBytes() > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("--max-body-bytes must be between 1 and " + MAX_BODY_BYTES + ".");
        }
        if (config.queueSize() < 0 || config.queueSize() > MAX_QUEUE_SIZE) {
            throw new IllegalArgumentException("--queue-size must be between 0 and " + MAX_QUEUE_SIZE + ".");
        }
        if (config.workers() < 1 || config.workers() > MAX_WORKERS) {
            throw new IllegalArgumentException("--workers must be between 1 and " + MAX_WORKERS + ".");
        }
        if (config.maxConcurrentHeapBytes() < MIN_CONCURRENT_HEAP_BYTES) {
            throw new IllegalArgumentException("--max-concurrent-heap-bytes must be at least "
                    + MIN_CONCURRENT_HEAP_BYTES + ".");
        }
        if (config.sizeEstimator() == null) {
            throw new IllegalArgumentException("A size estimator is required.");
        }
        if (config.clientQuotas() == null) {
            throw new IllegalArgumentException("Client quotas are required (ClientQuotas.Settings.NONE for none).");
        }
        if (config.requestTimeout() == null || config.requestTimeout().isNegative()
                || config.requestTimeout().isZero()) {
            throw new IllegalArgumentException("--request-timeout must be positive.");
        }
        if (config.jobTimeout() == null || config.jobTimeout().isNegative() || config.jobTimeout().isZero()) {
            throw new IllegalArgumentException("--job-timeout must be positive.");
        }
        if (config.jobTtl() == null || config.jobTtl().isNegative() || config.jobTtl().isZero()) {
            throw new IllegalArgumentException("--job-ttl must be positive.");
        }
        if (config.maxJobs() < MIN_MAX_JOBS || config.maxJobs() > MAX_MAX_JOBS) {
            throw new IllegalArgumentException("--max-jobs must be between " + MIN_MAX_JOBS + " and " + MAX_MAX_JOBS + ".");
        }
        if (config.jobLogLines() < 1 || config.jobLogLines() > MAX_JOB_LOG_LINES) {
            throw new IllegalArgumentException("--job-log-lines must be between 1 and " + MAX_JOB_LOG_LINES + ".");
        }
        if (config.maxResultBytes() < MIN_RESULT_BYTES || config.maxResultBytes() > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("--max-result-bytes must be between " + MIN_RESULT_BYTES
                    + " and " + MAX_RESULT_BYTES + ".");
        }
        if (config.jobStoreBytes() < MIN_JOB_STORE_BYTES) {
            throw new IllegalArgumentException("--job-store-bytes must be at least " + MIN_JOB_STORE_BYTES + ".");
        }
        if (config.maxUploadBytes() < MIN_UPLOAD_BYTES || config.maxUploadBytes() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("--max-upload-bytes must be between " + MIN_UPLOAD_BYTES + " and "
                    + MAX_UPLOAD_BYTES + ".");
        }
        if (config.workMaxBytes() < MIN_WORK_MAX_BYTES) {
            throw new IllegalArgumentException("--work-max-bytes must be at least " + MIN_WORK_MAX_BYTES + ".");
        }
        if (config.uploadTtl() == null || config.uploadTtl().isNegative() || config.uploadTtl().isZero()) {
            throw new IllegalArgumentException("--upload-ttl must be positive.");
        }
        if (config.requestReadTime() == null || config.requestReadTime().toSeconds() < 1
                || config.requestReadTime().compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("--request-read-time must be between PT1S and PT2H.");
        }
    }

    /** The work area (tests). */
    WorkArea workAreaForTest() {
        return workArea;
    }

    int port() {
        return http.getAddress().getPort();
    }

    /** The job store (tests). */
    JobManager jobsForTest() {
        return jobs;
    }

    /** The job queue (tests). */
    JobScheduler schedulerForTest() {
        return scheduler;
    }

    /** Tasks waiting in the worker's queue (tests: a cancelled job must not stay there). */
    int workerQueueLengthForTest() {
        return worker.getQueue().size();
    }

    /** Requests currently running or waiting for the worker. */
    int inFlight() {
        return totalSlots - slots.availablePermits();
    }

    /** Blocks until the server has been stopped by {@code POST /shutdown} or {@link #close()}. */
    void awaitStop() throws InterruptedException {
        stopped.await();
    }

    /**
     * After {@link #close()}: waits up to {@code grace} for the running commands to finish, so
     * they aren't cut off in the middle of writing their reports when the JVM exits. Queued
     * commands are not started. True when the workers are idle.
     */
    boolean awaitWorker(Duration grace) throws InterruptedException {
        return worker.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitStop(Duration timeout) throws InterruptedException {
        return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        // Queued jobs never start; running ones finish, as for synchronous requests.
        log.info("server.stopping", "serve: stopping; queued jobs are cancelled, running commands finish",
                "queued", (long) jobs.queued().size());
        for (Job job : jobs.queued()) {
            if (job.cancel(jobs.now(), "Server shut down before the job started")) {
                dropQueuedTask(job);
                slots.release();
                jobCancelled(job);
            }
        }
        http.stop(0);
        worker.shutdown();
        handlers.shutdownNow();
        stopped.countDown();
    }

    // -------------------------------------------------------------------------
    // Request handling
    // -------------------------------------------------------------------------

    private void handle(HttpExchange exchange) {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            // An opaque request target (e.g. "mailto:x") has no path.
            String path = exchange.getRequestURI().getPath() == null ? "" : exchange.getRequestURI().getPath();

            if (!ServeSecurity.isAllowedHost(exchange.getRequestHeaders().getFirst("Host"), port(),
                    config.host(), config.allowRemote(), config.allowedHosts())) {
                reject(exchange, 403, "Host header not allowed", method, path);
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !config.allowedOrigins().contains(origin)) {
                reject(exchange, 403, "Origin not allowed", method, path);
                return;
            }
            if (isV1(path)) {
                handleV1(exchange, method, path);
                return;
            }

            String command = path.length() > 1 && COMMANDS.contains(path.substring(1)) ? path.substring(1) : null;
            switch (path) {
                case "/health" -> {
                    if (requireMethod(exchange, method, "GET", path)) {
                        respond(exchange, 200, healthJson());
                    }
                }
                case "/ready" -> {
                    if (requireMethod(exchange, method, "GET", path)) {
                        String state = readiness();
                        respond(exchange, state.equals("ready") ? 200 : 503, "{\"status\":" + jsonStr(state) + "}");
                    }
                }
                case "/metrics" -> {
                    if (!config.metrics()) {
                        reject(exchange, 404, "Not found (start serve with --metrics)", method, path);
                    } else if (requireMethod(exchange, method, "GET", path) && requireAdmin(exchange, method, path) != null) {
                        respond(exchange, 200, metricsText(), ServeMetrics.CONTENT_TYPE);
                    }
                }
                case "/commands" -> {
                    if (requireMethod(exchange, method, "GET", path) && requireClient(exchange, method, path) != null) {
                        respond(exchange, 200, commandsJson());
                    }
                }
                case "/shutdown" -> {
                    if (requireMethod(exchange, method, "POST", path) && requireAdmin(exchange, method, path) != null
                            && readJsonBody(exchange, method, path) != null) {
                        respond(exchange, 200, "{\"status\":\"shutting down\"}");
                        Thread stopper = new Thread(this::close, "cimpal-serve-stop");
                        stopper.setDaemon(true);
                        stopper.start();
                    }
                }
                default -> {
                    if (command == null) {
                        reject(exchange, 404, "Not found", method, path);
                    } else if (requireMethod(exchange, method, "POST", path)) {
                        Client client = requireClient(exchange, method, path);
                        byte[] body = client == null ? null : readJsonBody(exchange, method, path);
                        if (body != null) {
                            runCommand(exchange, command, body, client);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Usually the client went away (closed or timed-out connection).
            log.warn("connection.error", "serve: connection error: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("request.failed", "serve: request failed: " + e.getClass().getSimpleName());
        } catch (OutOfMemoryError e) {
            // As for a command: the heap is exhausted and this JVM can't be trusted any more (DEP-2).
            onOutOfMemory.run();
        }
    }

    private boolean requireMethod(HttpExchange exchange, String method, String expected, String path)
            throws IOException {
        if (method.equals(expected)) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", expected);
        reject(exchange, 405, "Method not allowed, use " + expected, method, path);
        return false;
    }

    /**
     * The client whose bearer token the request carries; null after answering 401 (no valid
     * token) or 429 (the client's {@code requestsPerMinute} is used up).
     */
    private Client requireClient(HttpExchange exchange, String method, String path) throws IOException {
        Client client = config.tokens().authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (client == null) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            reject(exchange, 401, "Missing or invalid bearer token", method, path);
            return null;
        }
        exchange.setAttribute(CLIENT_ATTRIBUTE, client.name());
        long wait = quotas.acquireRequest(client.name());
        if (wait > 0) {
            metrics.quotaRefused(client.name(), "requests_per_minute");
            exchange.getResponseHeaders().set("Retry-After", Long.toString(wait));
            reject(exchange, 429, "Too many requests: this client may send "
                    + quotas.limits(client.name()).requestsPerMinute() + " per minute", method, path);
            return null;
        }
        return client;
    }

    /** As {@link #requireClient}, and 403 (null) unless the client is an admin. */
    private Client requireAdmin(HttpExchange exchange, String method, String path) throws IOException {
        Client client = requireClient(exchange, method, path);
        if (client != null && !client.admin()) {
            reject(exchange, 403, "This needs an admin token", method, path);
            return null;
        }
        return client;
    }

    /** The request body, or null after answering 415 or 413. An empty body becomes {@code {}}. */
    private byte[] readJsonBody(HttpExchange exchange, String method, String path) throws IOException {
        if (!ServeSecurity.isJson(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            reject(exchange, 415, "Content-Type must be application/json", method, path);
            return null;
        }
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        byte[] body;
        try {
            if (declared != null && Long.parseLong(declared.strip()) > config.maxBodyBytes()) {
                throw new ServeSecurity.BodyTooLargeException(config.maxBodyBytes());
            }
            body = ServeSecurity.readBounded(exchange.getRequestBody(), config.maxBodyBytes());
        } catch (ServeSecurity.BodyTooLargeException e) {
            exchange.getResponseHeaders().set("Connection", "close");
            reject(exchange, 413, "Request body larger than " + config.maxBodyBytes() + " bytes", method, path);
            return null;
        } catch (NumberFormatException e) {
            reject(exchange, 400, "Invalid Content-Length", method, path);
            return null;
        }
        return body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body;
    }

    // -------------------------------------------------------------------------
    // Job API (/v1, DEP-5)
    // -------------------------------------------------------------------------

    private static boolean isV1(String path) {
        return path.equals("/v1") || path.startsWith("/v1/");
    }

    private void handleV1(HttpExchange exchange, String method, String path) throws IOException {
        switch (path) {
            case "/v1/health" -> {
                if (allowMethods(exchange, method, path, "GET")) {
                    respond(exchange, 200, v1HealthJson());
                }
            }
            case "/v1/openapi.json" -> {
                if (allowMethods(exchange, method, path, "GET")) {
                    byte[] spec = OpenApiHolder.SPEC;
                    if (spec == null) {
                        reject(exchange, 500, "OpenAPI document missing from this build", method, path);
                    } else {
                        respond(exchange, 200, new String(spec, StandardCharsets.UTF_8), "application/json; charset=UTF-8");
                    }
                }
            }
            case "/v1/jobs" -> {
                Client client = allowMethods(exchange, method, path, "GET", "POST")
                        ? requireClient(exchange, method, path) : null;
                if (client != null) {
                    if (method.equals("POST")) {
                        byte[] body = readJsonBody(exchange, method, path);
                        if (body != null) {
                            submitJob(exchange, body, client);
                        }
                    } else {
                        listJobs(exchange, path, client);
                    }
                }
            }
            case "/v1/uploads" -> {
                Client client = allowMethods(exchange, method, path, "POST")
                        ? requireClient(exchange, method, path) : null;
                if (client != null && requireWorkArea(exchange, method, path)) {
                    receiveUpload(exchange, path, client);
                }
            }
            default -> {
                if (path.startsWith("/v1/uploads/")) {
                    handleUploadPath(exchange, method, path);
                } else {
                    handleJobPath(exchange, method, path);
                }
            }
        }
    }

    /** {@code /v1/jobs/{id}}, {@code /v1/jobs/{id}/result} and {@code /v1/jobs/{id}/log}. */
    private void handleJobPath(HttpExchange exchange, String method, String path) throws IOException {
        String prefix = "/v1/jobs/";
        if (!path.startsWith(prefix)) {
            reject(exchange, 404, "Not found", method, path);
            return;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String idText = slash < 0 ? rest : rest.substring(0, slash);
        String sub = slash < 0 ? "" : rest.substring(slash + 1);
        boolean fileRoute = sub.equals("files") || sub.equals("archive") || sub.startsWith("files/");
        if (slash >= 0 && !sub.equals("result") && !sub.equals("log") && !fileRoute) { // also a trailing "/"
            reject(exchange, 404, "Not found", method, path);
            return;
        }
        boolean allowed = sub.isEmpty()
                ? allowMethods(exchange, method, path, "GET", "DELETE")
                : allowMethods(exchange, method, path, "GET");
        Client client = allowed ? requireClient(exchange, method, path) : null;
        if (client == null) {
            return;
        }
        // Only the canonical lower-case form: anything else is simply not a job of this server.
        Job job = UUID_TEXT.matcher(idText).matches() ? jobs.get(UUID.fromString(idText)) : null;
        // Another client's job answers exactly as one that doesn't exist (DEP-13).
        if (job == null || !client.sees(job.owner())) {
            reject(exchange, 404, "No such job", method, path);
            return;
        }
        switch (sub) {
            case "" -> {
                if (method.equals("DELETE")) {
                    cancelJob(exchange, job, path);
                } else {
                    respond(exchange, 200, jobJson(job.snapshot()));
                }
            }
            case "result" -> jobResult(exchange, job, path);
            case "log" -> jobLog(exchange, job, path);
            case "files" -> jobFiles(exchange, job, path);
            case "archive" -> jobArchive(exchange, job, path);
            default -> jobFile(exchange, job, path, idText);
        }
    }

    private void submitJob(HttpExchange exchange, byte[] requestBody, Client client) throws IOException {
        String path = "/v1/jobs";
        JsonNode request;
        try {
            request = JSON.readTree(requestBody);
        } catch (JacksonException e) {
            reject(exchange, 400, "The request body is not valid JSON", "POST", path);
            return;
        }
        if (request == null || !request.isObject()) {
            reject(exchange, 400, "The request body must be a JSON object", "POST", path);
            return;
        }
        for (String key : request.propertyNames()) {
            if (!key.equals("command") && !key.equals("config") && !key.equals("label") && !key.equals("inputUpload")) {
                reject(exchange, 400, "Unknown field '" + LogSanitizer.forLog(key)
                        + "'; allowed: command, config, label, inputUpload", "POST", path);
                return;
            }
        }
        JsonNode commandNode = request.get("command");
        String command = commandNode != null && commandNode.isString() ? commandNode.asString() : null;
        if (command == null || !COMMANDS.contains(command)) {
            reject(exchange, 400, "'command' must be one of " + COMMANDS, "POST", path);
            return;
        }
        JsonNode config = request.get("config");
        if (config != null && !config.isNull() && !config.isObject()) {
            reject(exchange, 400, "'config' must be a JSON object", "POST", path);
            return;
        }
        JsonNode labelNode = request.get("label");
        String label = null;
        if (labelNode != null && !labelNode.isNull()) {
            if (!labelNode.isString() || labelNode.asString().length() > MAX_LABEL_CHARS) {
                reject(exchange, 400, "'label' must be a string of at most " + MAX_LABEL_CHARS + " characters",
                        "POST", path);
                return;
            }
            label = labelNode.asString();
        }
        UUID jobId = jobs.newId();
        ClientQuotas.Limits limits = quotas.limits(client.name());
        WorkArea.JobWorkspace workspace = null;
        JsonNode uploadNode = request.get("inputUpload");
        if (uploadNode != null && !uploadNode.isNull()) {
            if (!uploadNode.isString()) {
                reject(exchange, 400, "'inputUpload' must be an upload id", "POST", path);
                return;
            }
            if (!requireWorkArea(exchange, "POST", path)) {
                return;
            }
            workArea.evict();
            // Its outputs will count against the client's share: none is left for them.
            if (workArea.usedBytes(client.name()) >= limits.maxWorkBytes()) {
                reject(exchange, 507, "This client's share of the work area is full (maxWorkBytes "
                        + limits.maxWorkBytes() + "); delete uploads or wait for old jobs to expire", "POST", path);
                return;
            }
            String uploadText = uploadNode.asString();
            try {
                // Only the client's own upload (DEP-13); another client's is "no such upload".
                workspace = UUID_TEXT.matcher(uploadText).matches()
                        ? workArea.prepare(jobId, UUID.fromString(uploadText), client.name()) : null;
            } catch (IOException | RuntimeException e) {
                log.error("job.workspace", "serve: could not prepare a job workspace: " + e.getClass().getSimpleName());
                reject(exchange, 500, "Could not prepare the job's workspace", "POST", path);
                return;
            }
            if (workspace == null) {
                reject(exchange, 404, "No such upload: " + LogSanitizer.forLog(uploadText), "POST", path);
                return;
            }
        }
        byte[] body;
        try {
            byte[] configBytes = JSON.writeValueAsBytes(config == null || config.isNull() ? JSON.createObjectNode() : config);
            // The same path policy as the synchronous endpoints, checked now rather than when it runs.
            body = workspace == null ? requestCheck.check(command, configBytes)
                    : requestCheck.check(command, configBytes, workspace);
        } catch (Rejected e) {
            discardWorkspace(jobId, workspace);
            reject(exchange, e.status(), e.getMessage(), "POST", path);
            return;
        } catch (RuntimeException e) {
            // e.g. a --read-root removed since start: the job policy can't be built
            discardWorkspace(jobId, workspace);
            log.error("job.check", "serve: checking a job failed: " + e.getClass().getSimpleName());
            reject(exchange, 500, "Could not check the job's paths", "POST", path);
            return;
        }
        jobs.evict();
        if (closing.get()) {
            discardWorkspace(jobId, workspace);
            reject(exchange, 503, "Server is shutting down", "POST", path);
            return;
        }
        Job job = null;
        int unfinished = 0;
        boolean busy = false;
        synchronized (submitLock) {
            unfinished = scheduler.unfinished(client.name());
            if (unfinished < limits.maxQueued() + limits.maxRunning()) {
                busy = !slots.tryAcquire();
                if (!busy) {
                    job = jobs.create(jobId, command, label, client.name());
                    if (job == null) { // newId() rules this out; never keep the slot
                        slots.release();
                        busy = true;
                    }
                }
                if (job != null) {
                    if (workspace != null) {
                        workspaces.put(jobId, workspace);
                    }
                    scheduler.add(job, body);
                }
            }
        }
        if (job == null) {
            discardWorkspace(jobId, workspace);
            exchange.getResponseHeaders().set("Retry-After", "30");
            if (busy) {
                reject(exchange, 503, "Server busy: " + config().queueSize() + " requests already waiting", "POST", path);
            } else {
                metrics.quotaRefused(client.name(), "max_queued");
                reject(exchange, 429, "Too many jobs: this client has " + unfinished + " queued or running (maxQueued "
                        + limits.maxQueued() + ", maxRunning " + limits.maxRunning() + ")", "POST", path);
            }
            return;
        }
        log.info("job.queued", "serve: job " + job.id() + " (" + command + ") queued", "jobId", job.id().toString(),
                "command", command, "client", client.name(), "remote", remote(exchange));
        try {
            queuedTasks.put(job.id(), worker.submit(this::jobTurn));
        } catch (RejectedExecutionException e) {
            if (job.cancel(jobs.now(), "Server shut down before the job started")) {
                dropQueuedTask(job);
                slots.release();
                jobCancelled(job);
            }
            reject(exchange, 503, "Server is shutting down", "POST", path);
            return;
        }
        JobStatus now = job.snapshot().status();
        if (now == JobStatus.CANCELLED) {
            dropQueuedTask(job); // Cancelled (listed and deleted) before its task was recorded.
        } else if (now != JobStatus.QUEUED) {
            queuedTasks.remove(job.id()); // Another turn ran it already; its own turn is a spare.
        }
        exchange.getResponseHeaders().set("Location", "/v1/jobs/" + job.id());
        respond(exchange, 202, jobJson(job.snapshot()));
    }

    /**
     * On a worker: one turn of the job queue. It runs the job {@link JobScheduler} picks, which
     * need not be the one it was queued for; with none it parks (or its job was cancelled).
     */
    private void jobTurn() {
        JobScheduler.Entry next = scheduler.next();
        if (next == null) {
            return;
        }
        try {
            runJob(next.job(), next.body());
        } finally {
            if (scheduler.finished(next.job().owner())) {
                try {
                    worker.submit(this::jobTurn); // a parked turn: a queued job may run now
                } catch (RejectedExecutionException e) {
                    // Shutting down: close() cancels what is still queued.
                }
            }
        }
    }

    /** On the worker. The job owns its queue slot from submit until this returns (or a cancel). */
    private void runJob(Job job, byte[] body) {
        queuedTasks.remove(job.id());
        if (closing.get()) {
            // Submitted while close() was cancelling the queue: it must not start either.
            if (job.cancel(jobs.now(), "Server shut down before the job started")) {
                slots.release();
                jobCancelled(job);
            }
            return;
        }
        AdmissionGate.Ticket ticket;
        try {
            ticket = admission.acquire(estimate(job.command(), body, workspaces.get(job.id())),
                    () -> closing.get() || job.snapshot().status() != JobStatus.QUEUED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ticket = null;
        } catch (Error e) {
            // e.g. out of memory while estimating: the job never started, and its slot is freed once.
            if (job.cancel(jobs.now(), "Internal error before the job started")) {
                slots.release();
                jobCancelled(job);
            }
            if (OutOfMemoryRethrow.find(e).isPresent()) {
                onOutOfMemory.run();
            }
            return;
        }
        if (ticket == null) {
            // Cancelled while it waited for memory (whoever cancelled it released the slot), or shutting down.
            if (job.cancel(jobs.now(), "Server shut down before the job started")) {
                slots.release();
                jobCancelled(job);
            }
            return;
        }
        if (!job.start(jobs.now())) {
            ticket.close();
            return; // Cancelled while queued: whoever cancelled it released the slot.
        }
        long started = System.nanoTime();
        log.info("job.started", "serve: job " + job.id() + " (" + job.command() + ") started",
                "jobId", job.id().toString(), "command", job.command(), "client", job.owner());
        Object key = new Object();
        running.put(key, new Running(job, started));
        try {
            CommandResult result;
            // The job's stderr (its log) and its id for the JSON log, on this thread and those it starts.
            try (StderrTee.Capture capture = StderrTee.capture(job.log())) {
                result = CliStreams.bind(null, capture.stream(), job.id().toString(),
                        () -> runWithConfigFile(job.command(), body, workspaces.get(job.id())));
            }
            job.finish(jobs.now(), result.exitCode(), result.stdout(), config.maxResultBytes());
        } catch (Throwable t) {
            if (OutOfMemoryRethrow.find(t).isPresent()) {
                job.fail(jobs.now(), "Out of memory; the server stops");
                onOutOfMemory.run();
                return;
            }
            log.error("job.error", "serve: job " + job.id() + " (" + job.command() + ") failed: "
                    + t.getClass().getSimpleName(), "jobId", job.id().toString(), "command", job.command(),
                    "client", job.owner());
            job.fail(jobs.now(), "Internal error while running " + job.command());
        } finally {
            running.remove(key);
            ticket.close();
            slots.release();
            if (workArea != null && workspaces.containsKey(job.id())) {
                workArea.jobEnded(job.id());
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Job.Snapshot s = job.snapshot();
            metrics.jobFinished(job.owner(), s.status(), millis / 1000.0);
            log.info("job.finished", "serve: job " + job.id() + " (" + job.command() + ") " + s.status().apiName(),
                    "jobId", job.id().toString(), "command", job.command(), "client", job.owner(),
                    "status", s.status().apiName(), "exitCode", s.exitCode(), "durationMs", millis);
        }
        jobs.evict(); // Keep the store within --job-store-bytes as results arrive.
    }

    private void jobCancelled(Job job) {
        if (workArea != null && workspaces.containsKey(job.id())) {
            workArea.jobEnded(job.id()); // its upload is free to expire again
        }
        metrics.jobFinished(job.owner(), JobStatus.CANCELLED, -1);
        log.info("job.finished", "serve: job " + job.id() + " (" + job.command() + ") cancelled",
                "jobId", job.id().toString(), "command", job.command(), "client", job.owner(),
                "status", JobStatus.CANCELLED.apiName());
    }

    /** The ids of the jobs running now (synchronous requests have none). */
    List<String> runningJobIds() {
        return running.values().stream().filter(r -> r.job != null).map(r -> r.job.id().toString())
                .sorted().toList();
    }

    /** The heap a command is estimated to need; when that can't be told, all of the budget (it runs alone). */
    private long estimate(String command, byte[] body, WorkArea.JobWorkspace workspace) {
        try {
            return config.sizeEstimator().estimate(command, body, workspace);
        } catch (RuntimeException e) {
            log.warn("admission.estimate", "serve: could not estimate the size of a " + command
                    + " command; it runs alone: " + e.getClass().getSimpleName(), "command", command);
            return Long.MAX_VALUE;
        }
    }

    /**
     * Takes a cancelled job out of the queue, so its request body is freed now, and one turn out
     * of the worker's queue (turns are interchangeable).
     */
    private void dropQueuedTask(Job job) {
        scheduler.remove(job.id());
        Future<?> task = queuedTasks.remove(job.id());
        if (task instanceof Runnable runnable) {
            worker.remove(runnable);
        }
    }

    private void cancelJob(HttpExchange exchange, Job job, String path) throws IOException {
        if (job.cancel(jobs.now(), "Cancelled by the client")) {
            dropQueuedTask(job);
            slots.release();
            jobCancelled(job);
            respond(exchange, 200, jobJson(job.snapshot()));
            return;
        }
        // A running job is not interrupted: a command cut off mid-write could leave truncated reports.
        rejectJob(exchange, 409, "Job is " + job.snapshot().status().apiName() + "; only queued jobs can be cancelled",
                job, "DELETE", path);
    }

    // -------------------------------------------------------------------------
    // File exchange (DEP-7)
    // -------------------------------------------------------------------------

    private boolean requireWorkArea(HttpExchange exchange, String method, String path) throws IOException {
        if (workArea != null) {
            return true;
        }
        reject(exchange, 404, "File exchange is not enabled on this server", method, path);
        return false;
    }

    /** A workspace prepared for a job that is then not created. */
    private void discardWorkspace(UUID jobId, WorkArea.JobWorkspace workspace) {
        if (workspace != null) {
            workArea.deleteWorkspace(jobId);
        }
    }

    /** {@code POST /v1/uploads}: a ZIP body, streamed to disk and extracted; it belongs to {@code client}. */
    private void receiveUpload(HttpExchange exchange, String path, Client client) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        String mediaType = type == null ? "" : type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!mediaType.equals(ZIP)) {
            reject(exchange, 415, "Content-Type must be " + ZIP, "POST", path);
            return;
        }
        long declared = -1;
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        // With Transfer-Encoding the body length is not Content-Length: reserve the full limit.
        if (length != null && exchange.getRequestHeaders().getFirst("Transfer-Encoding") == null) {
            try {
                declared = Long.parseLong(length.strip());
            } catch (NumberFormatException e) {
                reject(exchange, 400, "Invalid Content-Length", "POST", path);
                return;
            }
        }
        WorkArea.Upload upload;
        ClientQuotas.Limits limits = quotas.limits(client.name());
        try {
            upload = workArea.receive(exchange.getRequestBody(), declared, client.name(), limits.maxUploadBytes(),
                    limits.maxWorkBytes());
        } catch (WorkArea.Refused e) {
            if (e.status() == 413) {
                exchange.getResponseHeaders().set("Connection", "close");
            }
            reject(exchange, e.status(), e.getMessage(), "POST", path);
            return;
        }
        log.info("upload.received", "serve: upload " + upload.id + " (" + upload.files.size() + " files, "
                + upload.totalBytes + " bytes)", "uploadId", upload.id.toString(), "client", client.name(),
                "remote", remote(exchange));
        exchange.getResponseHeaders().set("Location", "/v1/uploads/" + upload.id);
        respond(exchange, 201, JSON.writeValueAsString(uploadNode(upload)));
    }

    /** {@code GET} and {@code DELETE /v1/uploads/{uploadId}}. */
    private void handleUploadPath(HttpExchange exchange, String method, String path) throws IOException {
        String idText = path.substring("/v1/uploads/".length());
        if (idText.contains("/")) {
            reject(exchange, 404, "Not found", method, path);
            return;
        }
        Client client = allowMethods(exchange, method, path, "GET", "DELETE")
                ? requireClient(exchange, method, path) : null;
        if (client == null || !requireWorkArea(exchange, method, path)) {
            return;
        }
        workArea.evict();
        UUID id = UUID_TEXT.matcher(idText).matches() ? UUID.fromString(idText) : null;
        WorkArea.Upload upload = id == null ? null : workArea.upload(id);
        // Another client's upload answers exactly as one that doesn't exist (DEP-13).
        if (upload == null || !client.sees(upload.owner)) {
            reject(exchange, 404, "No such upload", method, path);
            return;
        }
        if (method.equals("GET")) {
            respond(exchange, 200, JSON.writeValueAsString(uploadNode(upload)));
            return;
        }
        try {
            if (!workArea.deleteUpload(id)) {
                reject(exchange, 404, "No such upload", method, path);
                return;
            }
        } catch (WorkArea.Refused e) {
            reject(exchange, e.status(), e.getMessage(), method, path);
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1);
    }

    private static ObjectNode uploadNode(WorkArea.Upload upload) {
        ObjectNode node = JSON.createObjectNode();
        node.put("uploadId", upload.id.toString());
        ArrayNode files = node.putArray("files");
        for (var entry : upload.files) {
            files.addObject().put("name", entry.name()).put("size", entry.size());
        }
        node.put("totalBytes", upload.totalBytes);
        node.put("createdAt", upload.createdAt.toString());
        node.put("expiresAt", upload.expiresAt.toString());
        return node;
    }

    /**
     * True when {@code job} has a workspace and has ended; otherwise answers 404 (no workspace)
     * or 409 (not finished, or cancelled) and returns false.
     */
    private boolean requireOutputs(HttpExchange exchange, Job job, String path) throws IOException {
        if (workArea == null || !workArea.hasWorkspace(job.id())) {
            rejectJob(exchange, 404, "This job has no workspace: it read and wrote shared paths, not an upload",
                    job, "GET", path);
            return false;
        }
        Job.Snapshot s = job.snapshot();
        if (!s.finished() || s.status() == JobStatus.CANCELLED) {
            rejectJob(exchange, 409, "Job is " + s.status().apiName() + "; it has no outputs"
                    + (s.status() == JobStatus.CANCELLED ? "" : " yet"), job, "GET", path);
            return false;
        }
        return true;
    }

    /** {@code GET /v1/jobs/{id}/files}: the files in the job's {@code out/}. */
    private void jobFiles(HttpExchange exchange, Job job, String path) throws IOException {
        if (!requireOutputs(exchange, job, path)) {
            return;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("jobId", job.id().toString());
        ArrayNode files = node.putArray("files");
        for (WorkArea.OutputFile f : workArea.outputs(job.id())) {
            files.addObject().put("path", f.path()).put("size", f.size()).put("mediaType", mediaType(f.path()));
        }
        respond(exchange, 200, JSON.writeValueAsString(node));
    }

    /** {@code GET /v1/jobs/{id}/files/{path}}: one output file, streamed. */
    private void jobFile(HttpExchange exchange, Job job, String path, String idText) throws IOException {
        if (!requireOutputs(exchange, job, path)) {
            return;
        }
        // Segments from the raw path, each decoded on its own: an encoded "/" or "\" stays inside
        // its segment, where WorkArea.output refuses it.
        String raw = exchange.getRequestURI().getRawPath();
        String prefix = "/v1/jobs/" + idText + "/files/";
        List<String> segments = new ArrayList<>();
        if (raw != null && raw.startsWith(prefix)) {
            for (String part : raw.substring(prefix.length()).split("/", -1)) {
                try {
                    segments.add(URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    segments = List.of();
                    break;
                }
            }
        }
        Path file = workArea.output(job.id(), segments);
        if (file == null) {
            rejectJob(exchange, 404, "No such output file", job, "GET", path);
            return;
        }
        String name = segments.getLast();
        exchange.getResponseHeaders().set("Content-Type", mediaType(name));
        exchange.getResponseHeaders().set("Content-Disposition", contentDisposition(name));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    /** {@code GET /v1/jobs/{id}/archive}: all outputs as one ZIP, streamed. */
    private void jobArchive(HttpExchange exchange, Job job, String path) throws IOException {
        if (!requireOutputs(exchange, job, path)) {
            return;
        }
        List<WorkArea.OutputFile> files = workArea.outputs(job.id());
        exchange.getResponseHeaders().set("Content-Type", ZIP);
        exchange.getResponseHeaders().set("Content-Disposition", contentDisposition("job-" + job.id() + ".zip"));
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(exchange.getResponseBody());
        try {
            for (WorkArea.OutputFile f : files) {
                Path file = workArea.output(job.id(), List.of(f.path().split("/")));
                if (file == null) {
                    throw new IOException("output vanished during the download");
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(f.path()));
                Files.copy(file, zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException e) {
            // No central directory: the client gets an unreadable archive, never a complete-looking
            // one with reports missing.
            log.warn("download.aborted", "serve: archive of job " + job.id() + " aborted: " + e.getMessage(),
                    "jobId", job.id().toString());
            throw e;
        }
        zip.flush();
        exchange.getResponseBody().close();
    }

    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("json", "application/json"),
            Map.entry("jsonld", "application/ld+json"),
            Map.entry("ttl", "text/turtle"),
            Map.entry("nt", "application/n-triples"),
            Map.entry("nq", "application/n-quads"),
            Map.entry("trig", "application/trig"),
            Map.entry("rdf", "application/rdf+xml"),
            Map.entry("owl", "application/rdf+xml"),
            Map.entry("xml", "application/xml"),
            Map.entry("csv", "text/csv"),
            Map.entry("txt", "text/plain"),
            Map.entry("log", "text/plain"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("zip", ZIP));

    /** From a fixed list by extension, never sniffed; anything else is a plain download. */
    static String mediaType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MEDIA_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * {@code attachment} with an ASCII-only {@code filename} (anything else becomes {@code _}) and
     * the exact name as RFC 8187 {@code filename*}; no quote, CR or LF can reach the header.
     */
    static String contentDisposition(String name) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        for (char c : name.toCharArray()) {
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            ascii.append(safe ? c : '_');
        }
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            boolean attrChar = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (attrChar) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private void jobResult(HttpExchange exchange, Job job, String path) throws IOException {
        Job.Snapshot s = job.snapshot();
        if (s.resultDropped()) {
            rejectJob(exchange, 410, "The result was larger than --max-result-bytes (" + config.maxResultBytes()
                    + " bytes) and was not kept; write large results to a file (the command's output or "
                    + "summaryFile option)", job, "GET", path);
            return;
        }
        if (!s.finished() || s.status() == JobStatus.CANCELLED) {
            rejectJob(exchange, 409, "Job is " + s.status().apiName() + "; it has no result"
                    + (s.status() == JobStatus.CANCELLED ? "" : " yet"), job, "GET", path);
            return;
        }
        String body = job.result();
        if (body == null || body.isEmpty()) {
            int exit = s.exitCode() == null ? 3 : s.exitCode();
            body = "{\"exitCode\":" + exit + ",\"status\":\"" + statusLabel(exit) + "\""
                    + (s.error() == null ? "" : ",\"error\":" + jsonStr(s.error())) + "}";
        }
        respond(exchange, 200, body);
    }

    private void jobLog(HttpExchange exchange, Job job, String path) throws IOException {
        long offset = 0;
        Map<String, String> query = query(exchange);
        if (query == null) {
            rejectJob(exchange, 400, "Malformed query string", job, "GET", path);
            return;
        }
        String raw = query.get("offset");
        if (raw != null) {
            try {
                offset = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                offset = -1;
            }
            if (offset < 0) {
                rejectJob(exchange, 400, "'offset' must be a non-negative integer", job, "GET", path);
                return;
            }
        }
        JobLog.Slice slice = job.log().read(offset, MAX_LOG_PAGE);
        ObjectNode node = JSON.createObjectNode();
        node.put("jobId", job.id().toString());
        node.put("offset", slice.offset());
        node.put("nextOffset", slice.nextOffset());
        node.put("truncated", slice.truncated());
        ArrayNode lines = node.putArray("lines");
        slice.lines().forEach(lines::add);
        respond(exchange, 200, JSON.writeValueAsString(node));
    }

    /** A client lists its own jobs; an admin every client's, or one client's with {@code ?owner=}. */
    private void listJobs(HttpExchange exchange, String path, Client client) throws IOException {
        Map<String, String> query = query(exchange);
        if (query == null) {
            reject(exchange, 400, "Malformed query string", "GET", path);
            return;
        }
        String owner = client.admin() ? null : client.name();
        if (query.containsKey("owner")) {
            if (!client.sees(query.get("owner"))) {
                reject(exchange, 403, "Only an admin token may list other clients' jobs", "GET", path);
                return;
            }
            owner = query.get("owner");
        }
        JobStatus status = null;
        if (query.containsKey("status")) {
            status = JobStatus.fromApiName(query.get("status"));
            if (status == null) {
                reject(exchange, 400, "'status' must be one of queued, running, succeeded, failed, cancelled, timed_out",
                        "GET", path);
                return;
            }
        }
        int limit = DEFAULT_LIST_LIMIT;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > MAX_LIST_LIMIT) {
                reject(exchange, 400, "'limit' must be between 1 and " + MAX_LIST_LIMIT, "GET", path);
                return;
            }
        }
        ObjectNode node = JSON.createObjectNode();
        ArrayNode list = node.putArray("jobs");
        for (Job job : jobs.list(status, owner, limit)) {
            list.add(jobNode(job.snapshot()));
        }
        respond(exchange, 200, JSON.writeValueAsString(node));
    }

    private static String jobJson(Job.Snapshot s) {
        return JSON.writeValueAsString(jobNode(s));
    }

    private static ObjectNode jobNode(Job.Snapshot s) {
        ObjectNode node = JSON.createObjectNode();
        String self = "/v1/jobs/" + s.id();
        node.put("jobId", s.id().toString());
        node.put("command", s.command());
        node.put("label", s.label());
        node.put("owner", s.owner());
        node.put("status", s.status().apiName());
        node.put("createdAt", s.createdAt().toString());
        node.put("startedAt", s.startedAt() == null ? null : s.startedAt().toString());
        node.put("finishedAt", s.finishedAt() == null ? null : s.finishedAt().toString());
        if (s.exitCode() == null) {
            node.putNull("exitCode");
        } else {
            node.put("exitCode", s.exitCode().intValue());
        }
        if (s.hasViolations() == null) {
            node.putNull("hasViolations");
        } else {
            node.put("hasViolations", s.hasViolations().booleanValue());
        }
        node.put("error", s.error());
        ObjectNode links = node.putObject("links");
        links.put("self", self);
        links.put("result", self + "/result");
        links.put("log", self + "/log");
        return node;
    }

    private String v1HealthJson() {
        int runningNow = running.size();
        boolean stalled = stalled();
        ObjectNode node = JSON.createObjectNode();
        node.put("status", stalled ? "stalled" : "ok");
        node.put("version", VERSION);
        node.put("apiVersion", API_VERSION);
        node.put("busy", runningNow > 0);
        node.put("stalled", stalled);
        node.put("queued", Math.max(0, inFlight() - runningNow));
        node.put("running", runningNow);
        node.put("workers", config.workers());
        return JSON.writeValueAsString(node);
    }

    /** The first value of each query parameter, decoded; null when it can't be decoded. */
    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        try {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.putIfAbsent(key, value);
            }
        } catch (IllegalArgumentException e) {
            return null; // A malformed %-escape.
        }
        return params;
    }

    private boolean allowMethods(HttpExchange exchange, String method, String path, String... allowed)
            throws IOException {
        for (String m : allowed) {
            if (m.equals(method)) {
                return true;
            }
        }
        String allow = String.join(", ", allowed);
        exchange.getResponseHeaders().set("Allow", allow);
        reject(exchange, 405, "Method not allowed, use " + allow, method, path);
        return false;
    }

    private void rejectJob(HttpExchange exchange, int status, String message, Job job, String method,
                           String path) throws IOException {
        logRejected(exchange, status, message, method, path, job.id());
        respond(exchange, status, Problem.json(status, message, job.id()), Problem.CONTENT_TYPE);
    }

    private Config config() {
        return config;
    }

    /** The OpenAPI document, read once from the CLI JAR. */
    private static final class OpenApiHolder {
        static final byte[] SPEC = load();

        private static byte[] load() {
            try (InputStream in = ServeServer.class.getResourceAsStream(OPENAPI_RESOURCE)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Synchronous command endpoints
    // -------------------------------------------------------------------------

    private void runCommand(HttpExchange exchange, String command, byte[] requestBody, Client client)
            throws IOException {
        long received = System.nanoTime();
        byte[] body;
        try {
            body = requestCheck.check(command, requestBody);
        } catch (Rejected e) {
            reject(exchange, e.status(), e.getMessage(), "POST", "/" + command);
            return;
        }
        if (!slots.tryAcquire()) {
            metrics.request(client.name(), command, "busy");
            exchange.getResponseHeaders().set("Retry-After", "30");
            reject(exchange, 503, "Server busy: " + config.queueSize() + " requests already waiting",
                    "POST", "/" + command);
            return;
        }
        // Whoever flips 'claimed' first owns the slot: the task when it starts, or the handler
        // when the request times out while the task is still queued (the task then never runs).
        AtomicBoolean claimed = new AtomicBoolean();
        Future<CommandResult> future;
        try {
            future = worker.submit(() -> {
                AdmissionGate.Ticket ticket;
                try {
                    // Abandoned when the request timed out (or the server stops) while it waited.
                    ticket = admission.acquire(estimate(command, body, null), claimed::get);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ticket = null;
                } catch (Error e) {
                    // The handler sees it as a failure (or out of memory); the slot is freed once.
                    if (claimed.compareAndSet(false, true)) {
                        slots.release();
                    }
                    throw e;
                }
                if (ticket == null || !claimed.compareAndSet(false, true)) {
                    if (ticket != null) {
                        ticket.close();
                    } else if (claimed.compareAndSet(false, true)) {
                        slots.release(); // interrupted: nobody else claimed the slot
                    }
                    return null;
                }
                Object key = new Object();
                running.put(key, new Running(null, System.nanoTime()));
                try {
                    return runWithConfigFile(command, body);
                } finally {
                    running.remove(key);
                    ticket.close();
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }

        CommandResult result;
        try {
            result = future.get(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (claimed.compareAndSet(false, true)) {
                worker.remove((Runnable) future); // It will never run; free its request body now.
                slots.release();
            } else {
                // Don't interrupt: a command cut off mid-write could leave truncated reports.
                future.cancel(false);
            }
            metrics.request(client.name(), command, "timeout");
            reject(exchange, 504, "Command did not finish within " + config.requestTimeout(), "POST", "/" + command);
            return;
        } catch (ExecutionException e) {
            if (OutOfMemoryRethrow.find(e).isPresent()) {
                // The heap is exhausted and the run's other workers may still be going: this JVM
                // can't be trusted with another request, so the server ends with exit 3 (DEP-2).
                try {
                    respond(exchange, 500, OUT_OF_MEMORY_BODY);
                } catch (Throwable ignored) {
                    // Best effort: the client sees the connection close instead.
                }
                onOutOfMemory.run();
                return;
            }
            metrics.request(client.name(), command, "error");
            log.error("command.failed", "serve: /" + command + " failed: " + e.getCause().getClass().getSimpleName(),
                    "command", command, "client", client.name(), "remote", remote(exchange));
            respond(exchange, 500, error("Internal error while running " + command));
            return;
        } catch (InterruptedException e) {
            // Shutting down: a command still waiting in the queue must not start any more.
            if (claimed.compareAndSet(false, true)) {
                slots.release();
            }
            Thread.currentThread().interrupt();
            respond(exchange, 503, error("Server is shutting down"));
            return;
        }

        String responseBody = result.stdout() == null ? "" : result.stdout().trim();
        if (responseBody.isEmpty()) {
            responseBody = "{\"exitCode\":" + result.exitCode() + ",\"status\":\"" + statusLabel(result.exitCode()) + "\"}";
        }
        // Exit 0 and 1 (violations) are results; 2 is bad input; anything else is an error.
        int status = result.exitCode() <= 1 ? 200 : (result.exitCode() == 2 ? 400 : 500);
        metrics.request(client.name(), command, ServeMetrics.outcome(result.exitCode()));
        log.info("command.finished", "serve: /" + command + " exit " + result.exitCode(), "command", command,
                "client", client.name(), "exitCode", result.exitCode(), "status", status,
                "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - received), "remote", remote(exchange));
        respond(exchange, status, responseBody);
    }

    private CommandResult runWithConfigFile(String command, byte[] body) throws Exception {
        return runWithConfigFile(command, body, null);
    }

    private CommandResult runWithConfigFile(String command, byte[] body, WorkArea.JobWorkspace workspace) throws Exception {
        Path configFile = Files.createTempFile("cimpal-serve-", ".json");
        try {
            Files.write(configFile, body);
            return workspace == null ? runner.run(command, configFile) : runner.run(command, configFile, workspace);
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * {@code status} is {@code "ok"}, or {@code "stalled"} when a running command has taken
     * longer than its timeout (it can't be stopped, so its worker stays blocked until it returns;
     * restart the server). {@code busy} tells whether a command is running.
     */
    private String healthJson() {
        int runningNow = running.size();
        boolean stalled = stalled();
        return "{\"status\":" + jsonStr(stalled ? "stalled" : "ok") + ",\"version\":" + jsonStr(VERSION)
                + ",\"busy\":" + (runningNow > 0) + ",\"stalled\":" + stalled + ",\"queued\":"
                + Math.max(0, inFlight() - runningNow) + "}";
    }

    /**
     * True when a running command has taken longer than its limit: {@code --request-timeout}
     * for a synchronous request, {@code --job-timeout} for a job. It can't be stopped, so its
     * worker stays blocked until it returns; restart the server if it doesn't.
     */
    private boolean stalled() {
        boolean any = false;
        for (Running r : running.values()) {
            boolean stalled = r.job == null
                    ? System.nanoTime() - r.since > config.requestTimeout().toNanos()
                    : jobs.timedOut(r.job);
            if (stalled) {
                any = true;
                if (r.stallWarned.compareAndSet(false, true)) {
                    log.warn("stall", "serve: a running command has exceeded its timeout; "
                            + "restart the server if it doesn't return.",
                            "jobId", r.job == null ? null : r.job.id().toString());
                }
            }
        }
        return any;
    }

    private static String commandsJson() {
        StringBuilder sb = new StringBuilder("{\"commands\":[");
        for (int i = 0; i < COMMANDS.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr("/" + COMMANDS.get(i)));
        }
        sb.append("],\"utility\":[\"/health\",\"/commands\",\"/shutdown\"],\"v1\":[");
        for (int i = 0; i < V1_ROUTES.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(V1_ROUTES.get(i)));
        }
        return sb.append("]}").toString();
    }

    private void reject(HttpExchange exchange, int status, String message, String method, String path)
            throws IOException {
        logRejected(exchange, status, message, method, path, null);
        if (isV1(path)) {
            respond(exchange, status, Problem.json(status, message, null), Problem.CONTENT_TYPE);
        } else {
            respond(exchange, status, error(message));
        }
    }

    private void logRejected(HttpExchange exchange, int status, String message, String method, String path,
                             UUID jobId) {
        log.warn("request.rejected", "serve: " + status + " " + LogSanitizer.forLog(method) + " "
                        + LogSanitizer.forLog(path) + " - " + LogSanitizer.forLog(message),
                "status", status, "method", method, "path", path, "remote", remote(exchange),
                "client", exchange.getAttribute(CLIENT_ATTRIBUTE), "jobId", jobId == null ? null : jobId.toString());
    }

    /** The client's address (the proxy's, behind one); never a header the client chose. */
    private static String remote(HttpExchange exchange) {
        return exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null ? null
                : exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * {@code ready}, or why not: {@code shutting down}, or {@code no usable token} (a broken token
     * file). A full queue doesn't count: with one replica that would take the only endpoint out of
     * the load balancer and block polling and cancelling too; a full queue is answered per request
     * with 503 and {@code Retry-After} instead.
     */
    String readiness() {
        if (closing.get()) {
            return "shutting down";
        }
        return config.tokens().usable() ? "ready" : "no usable token";
    }

    private String metricsText() {
        Map<JobStatus, Long> byStatus = new java.util.EnumMap<>(JobStatus.class);
        for (JobStatus status : JobStatus.values()) {
            byStatus.put(status, jobs.count(status));
        }
        int runningNow = running.size();
        return metrics.render(VERSION, byStatus, Math.max(0, inFlight() - runningNow), runningNow, config.workers());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json; charset=UTF-8");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Built before it is needed: answering an out-of-memory request may find no heap. */
    private static final String OUT_OF_MEMORY_BODY = "{\"exitCode\":3,\"status\":\"INTERNAL_ERROR\","
            + "\"error\":\"Out of memory; the server stops. Restart it with more memory (-Xmx, or the"
            + " container's memory limit); see docs/guide/sizing.md.\"}";

    private static String error(String message) {
        return "{\"error\":" + jsonStr(message) + "}";
    }

    private static String statusLabel(int exitCode) {
        return switch (exitCode) {
            case 0 -> "OK";
            case 1 -> "VIOLATIONS";
            case 2 -> "INVALID_INPUT";
            default -> "ERROR";
        };
    }

    static String jsonStr(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
