/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 */
final class ServeServer implements AutoCloseable {

    /** Commands exposed as POST endpoints. Must match the picocli command names exactly. */
    static final List<String> COMMANDS = List.of(
            "validate", "sparql", "convert", "compare", "compare-instances",
            "rdfs2shacl", "organize", "excel2shacl", "gen-instances", "manifest"
    );

    static final String VERSION = "CimPal CLI 2026.9";

    /** Upper bounds for the operator-set limits. */
    static final int MAX_QUEUE_SIZE = 64;
    static final long MAX_BODY_BYTES = 64L * 1024 * 1024;

    /** Handler threads beyond those that can be waiting on a command. */
    static final int SPARE_HTTP_THREADS = 16;

    static final String MAX_REQ_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    static final int REQUEST_READ_SECONDS = 60;

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
    record Config(String host, int port, String token, Set<String> allowedOrigins, boolean allowRemote,
                  long maxBodyBytes, int queueSize, Duration requestTimeout) {

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String host = "localhost";
            private int port = 7474;
            private String token;
            private Set<String> allowedOrigins = Set.of();
            private boolean allowRemote;
            private long maxBodyBytes = 1024 * 1024;
            private int queueSize = 4;
            private Duration requestTimeout = Duration.ofMinutes(30);

            Builder host(String host) { this.host = host; return this; }
            Builder port(int port) { this.port = port; return this; }
            Builder token(String token) { this.token = token; return this; }
            Builder allowedOrigins(Set<String> origins) { this.allowedOrigins = Set.copyOf(origins); return this; }
            Builder allowRemote(boolean allowRemote) { this.allowRemote = allowRemote; return this; }
            Builder maxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; return this; }
            Builder queueSize(int queueSize) { this.queueSize = queueSize; return this; }
            Builder requestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; return this; }

            Config build() {
                return new Config(host, port, token, allowedOrigins, allowRemote, maxBodyBytes, queueSize,
                        requestTimeout);
            }
        }
    }

    private final Config config;
    private final CommandRunner runner;
    private final RequestCheck requestCheck;
    private HttpServer http;
    private final ExecutorService handlers;
    private final ExecutorService worker;
    private final Semaphore slots;
    private final int totalSlots;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    /** When the running command started (System.nanoTime), or 0 when the worker is idle. */
    private final AtomicLong runningSince = new AtomicLong();

    private ServeServer(Config config, CommandRunner runner, RequestCheck requestCheck) {
        this.config = config;
        this.runner = runner;
        this.requestCheck = requestCheck;
        this.totalSlots = config.queueSize() + 1;
        this.slots = new Semaphore(totalSlots);
        // The JDK server reads request lines, headers and bodies on these threads too, and up to
        // totalSlots of them wait on a running or queued command. SPARE_HTTP_THREADS more stay
        // for reading new requests, /health and 503 rejections, and REQUEST_READ_SECONDS stops a
        // slow client from holding one of them indefinitely.
        this.handlers = Executors.newFixedThreadPool(totalSlots + SPARE_HTTP_THREADS,
                daemonThreads("cimpal-serve-http"));
        this.worker = Executors.newSingleThreadExecutor(daemonThreads("cimpal-serve-worker"));
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
        Objects.requireNonNull(requestCheck, "requestCheck");
        Objects.requireNonNull(runner, "runner");
        validate(config);
        limitRequestReadTime();
        ServeServer server = new ServeServer(config, runner, requestCheck);
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
        if (config.token() == null || config.token().isBlank()) {
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
    }

    int port() {
        return http.getAddress().getPort();
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
                    config.host(), config.allowRemote())) {
                reject(exchange, 403, "Host header not allowed", method, path);
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !config.allowedOrigins().contains(origin)) {
                reject(exchange, 403, "Origin not allowed", method, path);
                return;
            }

            String command = path.length() > 1 && COMMANDS.contains(path.substring(1)) ? path.substring(1) : null;
            switch (path) {
                case "/health" -> {
                    if (requireMethod(exchange, method, "GET", path)) {
                        respond(exchange, 200, healthJson());
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
            System.err.println("[WARN] serve: connection error: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            System.err.println("[ERROR] serve: request failed: " + e.getClass().getSimpleName());
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
        if (ServeSecurity.tokenMatches(config.token(), exchange.getRequestHeaders().getFirst("Authorization"))) {
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

    private void runCommand(HttpExchange exchange, String command, byte[] requestBody) throws IOException {
        byte[] body;
        try {
            body = requestCheck.check(command, requestBody);
        } catch (Rejected e) {
            reject(exchange, e.status(), e.getMessage(), "POST", "/" + command);
            return;
        }
        if (!slots.tryAcquire()) {
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
                slots.release();
            } else {
                // Don't interrupt: a command cut off mid-write could leave truncated reports.
                future.cancel(false);
            }
            reject(exchange, 504, "Command did not finish within " + config.requestTimeout(), "POST", "/" + command);
            return;
        } catch (ExecutionException e) {
            System.err.println("[ERROR] serve: /" + command + " failed: " + e.getCause().getClass().getSimpleName());
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
        boolean stalled = busy && System.nanoTime() - since > config.requestTimeout().toNanos();
        if (stalled) {
            System.err.println("[WARN] serve: the running command has exceeded --request-timeout ("
                    + config.requestTimeout() + "); restart the server if it doesn't return.");
        }
        return "{\"status\":" + jsonStr(stalled ? "stalled" : "ok") + ",\"version\":" + jsonStr(VERSION)
                + ",\"busy\":" + busy + ",\"stalled\":" + stalled + ",\"queued\":"
                + Math.max(0, inFlight() - (busy ? 1 : 0)) + "}";
    }

    private static String commandsJson() {
        StringBuilder sb = new StringBuilder("{\"commands\":[");
        for (int i = 0; i < COMMANDS.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr("/" + COMMANDS.get(i)));
        }
        return sb.append("],\"utility\":[\"/health\",\"/commands\",\"/shutdown\"]}").toString();
    }

    private static void reject(HttpExchange exchange, int status, String message, String method, String path)
            throws IOException {
        System.err.println("[WARN] serve: " + status + " " + LogSanitizer.forLog(method) + " "
                + LogSanitizer.forLog(path) + " - " + message);
        respond(exchange, status, error(message));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

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
