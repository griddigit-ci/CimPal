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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP server behind {@code serve}, separated from the picocli command so it can be started
 * in-process on an ephemeral port with a stub {@link CommandRunner} in tests.
 *
 * <h2>Request pipeline</h2>
 * <p>Every request passes, in order: Host allowlist (403), Origin allowlist (403), exact path
 * (404), method (405), bearer token (401, not for {@code GET /health}), {@code application/json}
 * body on POST (415), body limit (413). Commands then take a slot in a bounded queue (503 when
 * full) and run one at a time on a single worker thread, because {@code ValidationTools} keeps
 * static flags. A request that has not finished within the timeout gets 504.
 *
 * <p>A timed-out command is left to finish rather than interrupted, because a command cut off in
 * the middle of writing could leave truncated reports behind. It keeps its queue slot until it
 * returns, so the queue bound stays honest. A command that timed out while still queued never
 * runs.
 *
 * <h2>Job API ({@code /v1}, DEP-5)</h2>
 * <p>{@code POST /v1/jobs} queues a command and answers 202 at once; the caller polls
 * {@code GET /v1/jobs/{id}} and fetches {@code /result} and {@code /log}. Jobs share the worker and
 * the queue slots with the synchronous endpoints, so commands still run one at a time. Paths in a
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
            "GET /v1/jobs/{jobId}/log"
    );

    private static final String OPENAPI_RESOURCE = "/openapi/cimpal-v1.json";
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a command printed on stdout (its JSON) and its exit code. */
    record CommandResult(int exitCode, String stdout) {
    }

    /** Runs one CimPal subcommand with {@code --config configFile}. */
    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(String command, Path configFile) throws Exception;
    }

    /**
     * Checks (and may rewrite) a request body before it is queued, e.g. the path policy of
     * {@code serve}. Throws {@link Rejected} to answer with that status instead.
     */
    @FunctionalInterface
    interface RequestCheck {
        byte[] check(String command, byte[] body) throws Rejected;

        RequestCheck NONE = (command, body) -> body;
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
                  long jobStoreBytes, Set<String> allowedHosts, boolean metrics, ServeLog log) {

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

            Config build() {
                TokenSource source = tokens != null ? tokens
                        : token == null || token.isBlank() ? null : TokenSource.fixed(token);
                return new Config(host, port, source, allowedOrigins, allowRemote, maxBodyBytes, queueSize,
                        requestTimeout, jobTimeout, maxJobs, jobTtl, jobLogLines, maxResultBytes, jobStoreBytes,
                        allowedHosts, metrics, log);
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
    /** When the running command started (System.nanoTime), or 0 when the worker is idle. */
    private final AtomicLong runningSince = new AtomicLong();
    /** The job store of the {@code /v1} API. */
    private final JobManager jobs;
    /** The {@code /v1} job running on the worker, or null (a synchronous request, or idle). */
    private volatile Job runningJob;
    /** The worker tasks of queued jobs, so a cancelled job's task (and its body) leaves the queue at once. */
    private final Map<UUID, Future<?>> queuedTasks = new ConcurrentHashMap<>();
    /** The runningSince value the last stall warning was logged for, so it is logged once per command. */
    private final AtomicLong stallWarned = new AtomicLong();
    private final ServeLog log;
    private final ServeMetrics metrics = new ServeMetrics();

    private ServeServer(Config config, CommandRunner runner, RequestCheck requestCheck, Runnable onOutOfMemory,
                        InstantSource clock) {
        this.config = config;
        this.runner = runner;
        this.requestCheck = requestCheck;
        this.onOutOfMemory = onOutOfMemory;
        this.log = config.log();
        this.jobs = new JobManager(config.maxJobs(), config.jobTtl(), config.jobTimeout(), config.jobLogLines(),
                config.jobStoreBytes(), clock);
        this.totalSlots = config.queueSize() + 1;
        this.slots = new Semaphore(totalSlots);
        // The JDK server reads request lines, headers and bodies on these threads too, and up to
        // totalSlots of them wait on a running or queued command. SPARE_HTTP_THREADS more stay
        // for reading new requests, /health and 503 rejections, and REQUEST_READ_SECONDS stops a
        // slow client from holding one of them indefinitely.
        this.handlers = Executors.newFixedThreadPool(totalSlots + SPARE_HTTP_THREADS,
                daemonThreads("cimpal-serve-http"));
        // One thread, and a queue from which a cancelled task can be removed (remove(Runnable)).
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                daemonThreads("cimpal-serve-worker"));
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
        limitRequestReadTime();
        ServeServer server = new ServeServer(config, runner, requestCheck, onOutOfMemory, clock);
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
    private static void limitRequestReadTime() {
        if (System.getProperty(MAX_REQ_TIME_PROPERTY) == null) {
            System.setProperty(MAX_REQ_TIME_PROPERTY, Integer.toString(REQUEST_READ_SECONDS));
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
    }

    int port() {
        return http.getAddress().getPort();
    }

    /** The job store (tests). */
    JobManager jobsForTest() {
        return jobs;
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
     * After {@link #close()}: waits up to {@code grace} for a running command to finish, so it
     * isn't cut off in the middle of writing its reports when the JVM exits. Queued commands are
     * not started. True when the worker is idle.
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
        // Queued jobs never start; the running one finishes, as for synchronous requests.
        log.info("server.stopping", "serve: stopping; queued jobs are cancelled, a running command finishes",
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
                    } else if (requireMethod(exchange, method, "GET", path) && requireToken(exchange, method, path)) {
                        respond(exchange, 200, metricsText(), ServeMetrics.CONTENT_TYPE);
                    }
                }
                case "/commands" -> {
                    if (requireMethod(exchange, method, "GET", path) && requireToken(exchange, method, path)) {
                        respond(exchange, 200, commandsJson());
                    }
                }
                case "/shutdown" -> {
                    if (requireMethod(exchange, method, "POST", path) && requireToken(exchange, method, path)
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
                    } else if (requireMethod(exchange, method, "POST", path) && requireToken(exchange, method, path)) {
                        byte[] body = readJsonBody(exchange, method, path);
                        if (body != null) {
                            runCommand(exchange, command, body);
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

    private boolean requireToken(HttpExchange exchange, String method, String path) throws IOException {
        if (config.tokens().matches(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        reject(exchange, 401, "Missing or invalid bearer token", method, path);
        return false;
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
                if (allowMethods(exchange, method, path, "GET", "POST") && requireToken(exchange, method, path)) {
                    if (method.equals("POST")) {
                        byte[] body = readJsonBody(exchange, method, path);
                        if (body != null) {
                            submitJob(exchange, body);
                        }
                    } else {
                        listJobs(exchange, path);
                    }
                }
            }
            default -> handleJobPath(exchange, method, path);
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
        if (slash >= 0 && !sub.equals("result") && !sub.equals("log")) { // also a trailing "/"
            reject(exchange, 404, "Not found", method, path);
            return;
        }
        boolean allowed = sub.isEmpty()
                ? allowMethods(exchange, method, path, "GET", "DELETE")
                : allowMethods(exchange, method, path, "GET");
        if (!allowed || !requireToken(exchange, method, path)) {
            return;
        }
        // Only the canonical lower-case form: anything else is simply not a job of this server.
        Job job = UUID_TEXT.matcher(idText).matches() ? jobs.get(UUID.fromString(idText)) : null;
        if (job == null) {
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
            default -> jobLog(exchange, job, path);
        }
    }

    private void submitJob(HttpExchange exchange, byte[] requestBody) throws IOException {
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
            if (!key.equals("command") && !key.equals("config") && !key.equals("label")) {
                reject(exchange, 400, "Unknown field '" + LogSanitizer.forLog(key) + "'; allowed: command, config, label",
                        "POST", path);
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
        byte[] body;
        try {
            byte[] configBytes = JSON.writeValueAsBytes(config == null || config.isNull() ? JSON.createObjectNode() : config);
            // The same path policy as the synchronous endpoints, checked now rather than when it runs.
            body = requestCheck.check(command, configBytes);
        } catch (Rejected e) {
            reject(exchange, e.status(), e.getMessage(), "POST", path);
            return;
        }
        jobs.evict();
        if (closing.get()) {
            reject(exchange, 503, "Server is shutting down", "POST", path);
            return;
        }
        if (!slots.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "30");
            reject(exchange, 503, "Server busy: " + config().queueSize() + " requests already waiting", "POST", path);
            return;
        }
        Job job = jobs.create(command, label);
        log.info("job.queued", "serve: job " + job.id() + " (" + command + ") queued", "jobId", job.id().toString(),
                "command", command, "remote", remote(exchange));
        try {
            queuedTasks.put(job.id(), worker.submit(() -> runJob(job, body)));
        } catch (RejectedExecutionException e) {
            if (job.cancel(jobs.now(), "Server shut down before the job started")) {
                slots.release();
            }
            reject(exchange, 503, "Server is shutting down", "POST", path);
            return;
        }
        if (job.snapshot().status() == JobStatus.CANCELLED) {
            dropQueuedTask(job); // Cancelled (listed and deleted) before its task was recorded.
        }
        exchange.getResponseHeaders().set("Location", "/v1/jobs/" + job.id());
        respond(exchange, 202, jobJson(job.snapshot()));
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
        if (!job.start(jobs.now())) {
            return; // Cancelled while queued: whoever cancelled it released the slot.
        }
        long started = System.nanoTime();
        log.info("job.started", "serve: job " + job.id() + " (" + job.command() + ") started",
                "jobId", job.id().toString(), "command", job.command());
        runningJob = job;
        runningSince.set(started);
        try {
            CommandResult result;
            try (StderrTee.Capture ignored = StderrTee.capture(job.log())) {
                result = runWithConfigFile(job.command(), body);
            }
            job.finish(jobs.now(), result.exitCode(), result.stdout(), config.maxResultBytes());
        } catch (Throwable t) {
            if (OutOfMemoryRethrow.find(t).isPresent()) {
                job.fail(jobs.now(), "Out of memory; the server stops");
                onOutOfMemory.run();
                return;
            }
            log.error("job.error", "serve: job " + job.id() + " (" + job.command() + ") failed: "
                    + t.getClass().getSimpleName(), "jobId", job.id().toString(), "command", job.command());
            job.fail(jobs.now(), "Internal error while running " + job.command());
        } finally {
            runningJob = null;
            runningSince.set(0);
            slots.release();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Job.Snapshot s = job.snapshot();
            metrics.jobFinished(s.status(), millis / 1000.0);
            log.info("job.finished", "serve: job " + job.id() + " (" + job.command() + ") " + s.status().apiName(),
                    "jobId", job.id().toString(), "command", job.command(), "status", s.status().apiName(),
                    "exitCode", s.exitCode(), "durationMs", millis);
        }
        jobs.evict(); // Keep the store within --job-store-bytes as results arrive.
    }

    private void jobCancelled(Job job) {
        metrics.jobFinished(JobStatus.CANCELLED, -1);
        log.info("job.finished", "serve: job " + job.id() + " (" + job.command() + ") cancelled",
                "jobId", job.id().toString(), "command", job.command(), "status", JobStatus.CANCELLED.apiName());
    }

    /** The id of the job running now, or null (for the JSON log's output lines). */
    String runningJobId() {
        Job job = runningJob;
        return job == null ? null : job.id().toString();
    }

    /** Takes a cancelled job's task out of the worker's queue, so its request body is freed now. */
    private void dropQueuedTask(Job job) {
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

    private void listJobs(HttpExchange exchange, String path) throws IOException {
        Map<String, String> query = query(exchange);
        if (query == null) {
            reject(exchange, 400, "Malformed query string", "GET", path);
            return;
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
        for (Job job : jobs.list(status, limit)) {
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
        long since = runningSince.get();
        boolean busy = since != 0;
        boolean stalled = stalled();
        ObjectNode node = JSON.createObjectNode();
        node.put("status", stalled ? "stalled" : "ok");
        node.put("version", VERSION);
        node.put("apiVersion", API_VERSION);
        node.put("busy", busy);
        node.put("stalled", stalled);
        node.put("queued", Math.max(0, inFlight() - (busy ? 1 : 0)));
        node.put("running", busy ? 1 : 0);
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

    private void runCommand(HttpExchange exchange, String command, byte[] requestBody) throws IOException {
        long received = System.nanoTime();
        byte[] body;
        try {
            body = requestCheck.check(command, requestBody);
        } catch (Rejected e) {
            reject(exchange, e.status(), e.getMessage(), "POST", "/" + command);
            return;
        }
        if (!slots.tryAcquire()) {
            metrics.request(command, "busy");
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
                if (!claimed.compareAndSet(false, true)) {
                    return null;
                }
                runningSince.set(System.nanoTime());
                try {
                    return runWithConfigFile(command, body);
                } finally {
                    runningSince.set(0);
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
            metrics.request(command, "timeout");
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
            metrics.request(command, "error");
            log.error("command.failed", "serve: /" + command + " failed: " + e.getCause().getClass().getSimpleName(),
                    "command", command, "remote", remote(exchange));
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
        metrics.request(command, ServeMetrics.outcome(result.exitCode()));
        log.info("command.finished", "serve: /" + command + " exit " + result.exitCode(), "command", command,
                "exitCode", result.exitCode(), "status", status,
                "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - received), "remote", remote(exchange));
        respond(exchange, status, responseBody);
    }

    private CommandResult runWithConfigFile(String command, byte[] body) throws Exception {
        Path configFile = Files.createTempFile("cimpal-serve-", ".json");
        try {
            Files.write(configFile, body);
            return runner.run(command, configFile);
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * {@code status} is {@code "ok"}, or {@code "stalled"} when the running command has taken
     * longer than the request timeout (it can't be stopped, so the worker stays blocked until it
     * returns; restart the server). {@code busy} tells whether a command is running.
     */
    private String healthJson() {
        long since = runningSince.get();
        boolean busy = since != 0;
        boolean stalled = stalled();
        return "{\"status\":" + jsonStr(stalled ? "stalled" : "ok") + ",\"version\":" + jsonStr(VERSION)
                + ",\"busy\":" + busy + ",\"stalled\":" + stalled + ",\"queued\":"
                + Math.max(0, inFlight() - (busy ? 1 : 0)) + "}";
    }

    /**
     * True when the running command has taken longer than its limit: {@code --request-timeout}
     * for a synchronous request, {@code --job-timeout} for a job. It can't be stopped, so the
     * worker stays blocked until it returns; restart the server if it doesn't.
     */
    private boolean stalled() {
        long since = runningSince.get();
        Job job = runningJob;
        boolean stalled = since != 0 && (job == null
                ? System.nanoTime() - since > config.requestTimeout().toNanos()
                : jobs.timedOut(job));
        // Health is polled without a token: log once per command, not once per poll.
        if (stalled && stallWarned.getAndSet(since) != since) {
            log.warn("stall", "serve: the running command has exceeded its timeout; "
                    + "restart the server if it doesn't return.", "jobId", job == null ? null : job.id().toString());
        }
        return stalled;
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
                "jobId", jobId == null ? null : jobId.toString());
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
        boolean busy = runningSince.get() != 0;
        return metrics.render(VERSION, byStatus, Math.max(0, inFlight() - (busy ? 1 : 0)), busy);
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
