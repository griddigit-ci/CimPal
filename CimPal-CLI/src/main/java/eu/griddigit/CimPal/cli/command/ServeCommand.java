/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@code serve} subcommand — starts a local HTTP server that exposes CimPal operations as
 * REST endpoints.
 *
 * <h2>Why a daemon?</h2>
 * <p>Every CimPal CLI invocation starts a fresh JVM and re-parses all Jena/SHACL libraries.
 * For an automated validation agent that calls CimPal hundreds of times per session that cold
 * start is significant.  Running the daemon once keeps the JVM warm so subsequent requests
 * cost only the actual work.
 *
 * <h2>API</h2>
 * <p>Each subcommand is exposed as {@code POST /<command>}.  The request body is a JSON object
 * with the same keys as the command's config file.  The response is the JSON output the command
 * would produce on stdout (the same as {@code --format json}).  For commands that produce file
 * outputs (Excel reports, Turtle files) the paths must be absolute — server and caller share a
 * filesystem.
 *
 * <pre>
 *   POST /validate          — SHACL validation (mapping, timestamped, combined)
 *   POST /sparql            — SPARQL SELECT query
 *   POST /convert           — RDF format conversion
 *   POST /compare           — RDF diff
 *   POST /compare-instances — Instance-data diff
 *   POST /rdfs2shacl        — RDFS → SHACL generation
 *   POST /organize          — SHACL organizer
 *   POST /excel2shacl       — Excel → SHACL
 *   POST /gen-instances     — Instance data generation
 *   POST /manifest          — Manifest generation
 *   GET  /health            — {"status":"ok","version":"..."} (no token needed)
 *   GET  /commands          — list of available endpoints
 *   POST /shutdown          — stop the server
 * </pre>
 *
 * <p>The asynchronous job API under {@code /v1} (DEP-5) queues a command and answers at once:
 * <pre>
 *   POST   /v1/jobs              — submit {"command", "config", "label"}; 202 + Location
 *   GET    /v1/jobs              — list jobs (?status=, ?limit=)
 *   GET    /v1/jobs/{id}         — the job's status
 *   GET    /v1/jobs/{id}/result  — its JSON result once finished
 *   GET    /v1/jobs/{id}/log     — its progress lines (?offset=)
 *   DELETE /v1/jobs/{id}         — cancel a queued job
 *   GET    /v1/health            — health with queue figures (no token needed)
 *   GET    /v1/openapi.json      — the OpenAPI 3.1 document (no token needed)
 * </pre>
 *
 * <h2>Service deployment (DEP-6)</h2>
 * <p>{@code --allowed-host} accepts the public names of a reverse proxy or ingress in the Host
 * header. {@code --token-from-file} (or {@code CIMPAL_API_TOKEN_FILE}) reads one or two tokens
 * from a secret file that is never written or deleted and is read again when it changes.
 * {@code GET /ready} is the readiness probe; {@code --metrics} adds {@code GET /metrics}.
 * {@code --log-format json} writes one JSON object per line on stderr. SIGTERM stops the server
 * like {@code POST /shutdown} and waits up to {@code --shutdown-grace} for a running command.
 *
 * <h2>Security</h2>
 * <p>Every start creates a new 256-bit bearer token and writes it to a user-only token file
 * (or takes it from {@code CIMPAL_API_TOKEN}). All endpoints except {@code GET /health} require
 * {@code Authorization: Bearer <token>}. The Host header must name loopback and the bound port,
 * an Origin header must be in {@code --allow-origin}, and POST bodies must be
 * {@code application/json} and at most {@code --max-body-bytes}. See {@link ServeServer}.
 *
 * <h2>Thread safety</h2>
 * <p>Commands run one at a time on a single worker thread, so requests are serialised.  This
 * avoids races on the static flags in {@code ValidationTools} (debug mode, turtle export).  At
 * most {@code --queue-size} requests wait; more get 503.  A request not finished within
 * {@code --request-timeout} gets 504.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — server ran and was stopped (blocks until SIGTERM, SIGINT or {@code POST /shutdown})
 *   <li>2 — could not bind to the requested port, or invalid options / token
 *   <li>3 — unexpected startup error, or a command was still running after {@code --shutdown-grace}
 * </ul>
 */
@Command(
        name = "serve",
        mixinStandardHelpOptions = true,
        description = {
                "Start a local HTTP server exposing CimPal operations as REST endpoints.",
                "",
                "Useful for the automated validation agent: a single JVM stays warm across",
                "many validation calls, avoiding repeated cold-start overhead.",
                "",
                "Every request except GET /health needs 'Authorization: Bearer <token>'.",
                "The token is new on every start and is written to --token-file."
        },
        sortOptions = false
)
public class ServeCommand implements Callable<Integer> {

    @Option(names = {"--port", "-p"},
            defaultValue = "7474",
            description = "Port to listen on (default: 7474).")
    private int port;

    @Option(names = "--host",
            defaultValue = "localhost",
            description = "Bind address (default: localhost — not accessible from the network). "
                    + "A non-loopback address also needs --allow-remote.")
    private String host;

    @Option(names = "--allow-remote",
            description = "Allow a non-loopback --host. The token is still required, and traffic "
                    + "is plain HTTP.")
    private boolean allowRemote;

    @Option(names = "--token-file",
            description = "Where to write the bearer token (default: %%LOCALAPPDATA%%\\CimPal\\serve.token "
                    + "on Windows, ~/.cimpal/serve.token elsewhere). Not used when CIMPAL_API_TOKEN is set.")
    private Path tokenFile;

    @Option(names = "--token-from-file",
            description = "Read the accepted bearer token(s) from this file instead: one or two tokens "
                    + "(two while rotating), one per line, each at least 32 characters. The file is never "
                    + "written or deleted and is read again when it changes (see --token-reload). Same as "
                    + "the CIMPAL_API_TOKEN_FILE environment variable. Can't be combined with "
                    + "CIMPAL_API_TOKEN or --token-file.")
    private Path tokenFromFile;

    @Option(names = "--token-reload",
            defaultValue = "PT60S",
            description = "How often the --token-from-file file is checked for changes (ISO-8601 "
                    + "duration, default: PT60S).")
    private Duration tokenReload;

    @Option(names = "--allowed-host",
            description = "A host name (optionally with :port) clients use to reach the server through "
                    + "a reverse proxy or ingress, e.g. cimpal.example.com (repeatable). Without a port "
                    + "it matches a Host header without a port or with :80 or :443.")
    private List<String> allowedHostNames = List.of();

    @Option(names = "--allow-origin",
            description = "Browser Origin allowed to call the server (repeatable). A request with any "
                    + "other Origin header gets 403. No CORS headers are ever sent.")
    private List<String> allowedOrigins = List.of();

    @Option(names = "--max-body-bytes",
            defaultValue = "1048576",
            description = "Largest accepted request body in bytes (default: 1048576 = 1 MB).")
    private long maxBodyBytes;

    @Option(names = "--queue-size",
            defaultValue = "4",
            description = "Requests that may wait while one runs (default: 4); more get 503.")
    private int queueSize;

    @Option(names = "--request-timeout",
            defaultValue = "PT30M",
            description = "Per-request timeout as an ISO-8601 duration (default: PT30M). A request "
                    + "not finished in time gets 504.")
    private Duration requestTimeout;

    @Option(names = "--job-timeout",
            defaultValue = "PT2H",
            description = "A /v1 job running longer than this (ISO-8601 duration, default: PT2H) is "
                    + "marked timed_out. It is not interrupted.")
    private Duration jobTimeout;

    @Option(names = "--max-jobs",
            defaultValue = "1000",
            description = "Finished /v1 jobs kept in memory (default: 1000, 100-100000); the oldest "
                    + "are dropped first.")
    private int maxJobs;

    @Option(names = "--job-ttl",
            defaultValue = "PT24H",
            description = "How long a finished /v1 job is kept (ISO-8601 duration, default: PT24H).")
    private Duration jobTtl;

    @Option(names = "--job-log-lines",
            defaultValue = "5000",
            description = "Progress lines kept per /v1 job (default: 5000, 1-100000); older lines "
                    + "are dropped.")
    private int jobLogLines;

    @Option(names = "--max-result-bytes",
            defaultValue = "16777216",
            description = "Largest /v1 job result kept in memory (default: 16777216 = 16 MiB); a larger "
                    + "result is answered with 410. Write large results to a file instead.")
    private long maxResultBytes;

    @Option(names = "--job-store-bytes",
            description = "Memory for the results and logs of finished /v1 jobs together (default: a "
                    + "quarter of the maximum heap, at least 1048576); beyond it the oldest finished "
                    + "jobs are dropped.")
    private Long jobStoreBytes;

    @Option(names = "--shutdown-grace",
            defaultValue = "PT60S",
            description = "On SIGTERM, Ctrl-C or POST /shutdown: how long to wait for a running command "
                    + "(ISO-8601 duration, default: PT60S). Exit code 3 if it is still running then.")
    private Duration shutdownGrace;

    @Option(names = "--log-format",
            defaultValue = "text",
            description = "text (default) or json: one JSON object per line on stderr, for log collectors.")
    private String logFormat;

    @Option(names = "--metrics",
            description = "Serve Prometheus metrics at GET /metrics (needs the token).")
    private boolean metrics;

    @CommandLine.Mixin
    RootOptions rootOptions = new RootOptions();

    /** Commands that accept {@code --format json}; the others print their result files' paths. */
    static final Set<String> JSON_COMMANDS = Set.of("validate", "sparql", "compare", "compare-instances");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Clock for token file reloads and log timestamps; replaced by tests. */
    InstantSource clock = InstantSource.system();

    /** Installs the SIGTERM handler; replaced by tests, which must not take over the JVM's signals. */
    java.util.function.Predicate<Runnable> onTerm = ServeSignals::onTerm;

    /** Runs the commands instead of CimPal (tests: a slow stand-in command); null for the real ones. */
    ServeServer.CommandRunner runnerForTest;

    /** Environment the token is looked up in; replaced by tests. */
    Map<String, String> environment = System.getenv();

    /** Called with the running server once it listens; tests use it to find the port. */
    Consumer<ServeServer> onStarted = server -> { };

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        if (!logFormat.equalsIgnoreCase("text") && !logFormat.equalsIgnoreCase("json")) {
            System.err.println("[ERROR] --log-format must be text or json.");
            return ExitCode.INVALID_INPUT;
        }
        if (!logFormat.equalsIgnoreCase("json")) {
            return serve(ServeLog.text());
        }
        // Every stderr line becomes a JSON object, also the commands' own output.
        PrintStream originalErr = System.err;
        ServeLog log = ServeLog.json(originalErr, clock);
        AtomicReference<ServeServer> running = new AtomicReference<>();
        PrintStream wrapped = log.stderr(() -> running.get() == null ? null : running.get().runningJobId());
        System.setErr(wrapped);
        Consumer<ServeServer> started = onStarted;
        onStarted = server -> {
            running.set(server);
            started.accept(server);
        };
        try {
            return serve(log);
        } finally {
            log.flushOutput();
            if (System.err == wrapped) {
                System.setErr(originalErr);
            }
        }
    }

    private int serve(ServeLog log) {
        String token = null;
        TokenSource tokenSource = null;
        Path writtenTokenFile = null;
        String tokenOrigin = "";
        try {
            String fileFromEnv = environment.get(ServeSecurity.TOKEN_FILE_ENV);
            Path tokenFileSource = tokenFromFile;
            if (fileFromEnv != null && !fileFromEnv.isBlank()) {
                if (tokenFromFile != null) {
                    throw new IllegalArgumentException("Give the token file either as --token-from-file or as "
                            + ServeSecurity.TOKEN_FILE_ENV + ", not both.");
                }
                tokenFileSource = Path.of(fileFromEnv.strip());
            }
            String envToken = environment.get(ServeSecurity.TOKEN_ENV);
            if (tokenFileSource != null && envToken != null && !envToken.isBlank()) {
                throw new IllegalArgumentException("Use either a token file (--token-from-file, "
                        + ServeSecurity.TOKEN_FILE_ENV + ") or " + ServeSecurity.TOKEN_ENV + ", not both.");
            }
            if (tokenFileSource != null && tokenFile != null) {
                throw new IllegalArgumentException("--token-file (where serve writes a generated token) can't be "
                        + "combined with a token file to read (--token-from-file, " + ServeSecurity.TOKEN_FILE_ENV + ").");
            }
            Optional<String> fromEnv = ServeSecurity.tokenFromEnvironment(environment);
            if (tokenFileSource != null) {
                Path source = tokenFileSource;
                tokenSource = new TokenSource.FileTokens(source, tokenReload, clock,
                        message -> log.warn("token.file", "serve: " + message));
                tokenOrigin = "read from " + source.toAbsolutePath() + " (checked for changes every "
                        + tokenReload.toSeconds() + " s)";
            } else if (fromEnv.isPresent()) {
                token = fromEnv.get();
            } else {
                token = ServeSecurity.newToken();
                writtenTokenFile = tokenFile != null ? tokenFile
                        : ServeSecurity.defaultTokenFile(environment, System.getProperty("os.name"),
                                Path.of(System.getProperty("user.home")));
                ServeSecurity.writeTokenFile(writtenTokenFile, token);
            }
            if (tokenSource == null) {
                tokenOrigin = writtenTokenFile != null ? "written to " + writtenTokenFile.toAbsolutePath()
                        : "taken from " + ServeSecurity.TOKEN_ENV;
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        } catch (IOException ex) {
            System.err.println("[ERROR] Could not write the token file: " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }

        Set<String> allowedHosts = new LinkedHashSet<>();
        try {
            for (String name : allowedHostNames) {
                allowedHosts.add(ServeSecurity.allowedHostEntry(name));
            }
            if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
                throw new IllegalArgumentException("--shutdown-grace must be positive.");
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        }

        ServeServer.Config.Builder builder = ServeServer.Config.builder()
                .host(host)
                .port(port)
                .token(token)
                .tokens(tokenSource)
                .allowedHosts(allowedHosts)
                .metrics(metrics)
                .log(log)
                .allowedOrigins(new LinkedHashSet<>(allowedOrigins))
                .allowRemote(allowRemote)
                .maxBodyBytes(maxBodyBytes)
                .queueSize(queueSize)
                .requestTimeout(requestTimeout)
                .jobTimeout(jobTimeout)
                .maxJobs(maxJobs)
                .jobTtl(jobTtl)
                .jobLogLines(jobLogLines)
                .maxResultBytes(maxResultBytes);
        if (jobStoreBytes != null) {
            builder.jobStoreBytes(jobStoreBytes);
        }
        ServeServer.Config config = builder.build();

        List<Path> defaultRoots = List.of(Path.of("").toAbsolutePath());
        PathPolicy policy;
        try {
            policy = rootOptions.policy(defaultRoots);
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        }
        Path base = rootOptions.base(defaultRoots);

        ServeServer server;
        try {
            server = ServeServer.start(config,
                    runnerForTest != null ? runnerForTest
                            : (command, configFile) -> runWithPolicy(policy, base, command, configFile),
                    (command, body) -> checkPaths(policy, base, command, body));
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        } catch (IOException ex) {
            System.err.println("[ERROR] Could not bind to " + host + ":" + port + " - " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        }

        Path tokenFileToDelete = writtenTokenFile;
        String ownToken = token;
        Thread shutdownHook = new Thread(() -> {
            // Ctrl-C (or SIGTERM without the handler): drain like POST /shutdown, within the grace.
            server.close();
            deleteIfOurs(tokenFileToDelete, ownToken);
            try {
                server.awaitWorker(shutdownGrace);
            } catch (InterruptedException ignored) {
                // the JVM is exiting anyway
            }
        }, "cimpal-serve-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        boolean termHandled = onTerm.test(server::close);

        if (!ServeSecurity.isLoopback(host)) {
            log.warn("server.remote", "Listening on non-loopback address " + host
                    + ". Anyone who can reach it and has the token can run CimPal commands on this "
                    + "machine, and traffic is plain HTTP: put a TLS proxy in front.", "host", host);
        }
        if (log.format() == ServeLog.Format.JSON) {
            log.info("server.started", "CimPal daemon listening on http://" + host + ":" + server.port(),
                    "host", host, "port", server.port(), "version", ServeServer.VERSION,
                    "tokenSource", tokenOrigin, "allowedHosts", String.join(",", allowedHosts),
                    "readRoots", policy.readRoots().toString(), "writeRoots", policy.writeRoots().toString(),
                    "metrics", metrics, "sigterm", termHandled);
        } else {
            System.out.println("[INFO] CimPal daemon listening on http://" + host + ":" + server.port());
            System.out.println("[INFO] Bearer token " + tokenOrigin + " (send it as 'Authorization: Bearer <token>')");
            if (!allowedHosts.isEmpty()) {
                System.out.println("[INFO] Also accepted as Host: " + String.join(", ", allowedHosts));
            }
            System.out.println("[INFO] Allowed roots: read " + policy.readRoots() + ", write " + policy.writeRoots());
            System.out.println("[INFO] Endpoints: " + ServeServer.COMMANDS.stream().map(c -> "/" + c)
                    .reduce((a, b) -> a + "  " + b).orElse(""));
            System.out.println("[INFO] GET /health  GET /ready  GET /commands  POST /shutdown"
                    + (metrics ? "  GET /metrics" : ""));
            System.out.println("[INFO] Job API: POST /v1/jobs, then GET /v1/jobs/{id}[/result|/log]; "
                    + "spec at GET /v1/openapi.json");
            System.out.println("[INFO] Send POST /shutdown, SIGTERM or press Ctrl-C to stop.");
        }
        onStarted.accept(server);

        int exit = ExitCode.OK;
        try {
            server.awaitStop();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
            deleteIfOurs(tokenFileToDelete, ownToken);
            try {
                if (!server.awaitWorker(shutdownGrace)) {
                    log.error("server.grace_exceeded", "A command was still running after "
                            + shutdownGrace.toSeconds() + " s (--shutdown-grace); it is cut off and its "
                            + "output may be incomplete.", "jobId", server.runningJobId());
                    exit = ExitCode.INTERNAL_ERROR;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // the JVM is already shutting down and runs the hook itself
            }
            log.info("server.stopped", "serve: stopped", "exitCode", exit);
        }
        return exit;
    }

    /**
     * Checks the file paths in a request against the allowed roots and makes them absolute
     * (SEC-2). Invalid JSON is 400, a refused path 403.
     */
    static byte[] checkPaths(PathPolicy policy, Path base, String command, byte[] body) throws ServeServer.Rejected {
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ServeServer.Rejected(400, "Request body is not valid JSON.");
        }
        try {
            return MAPPER.writeValueAsBytes(PathGuard.check(command, json, policy, base));
        } catch (PathNotAllowedException e) {
            throw new ServeServer.Rejected(403, e.getMessage());
        }
    }

    /**
     * Runs the command with the path policy in force for paths Core resolves internally. The
     * paths are checked again first: an earlier request in the queue may have created an output
     * file, or the file system may have changed, since the request was accepted.
     */
    private static ServeServer.CommandResult runWithPolicy(PathPolicy policy, Path base, String command,
                                                           Path configFile) throws Exception {
        ObjectNode checked;
        try {
            checked = PathGuard.check(command, MAPPER.readTree(configFile.toFile()), policy, base);
        } catch (PathNotAllowedException e) {
            return new ServeServer.CommandResult(ExitCode.INVALID_INPUT,
                    "{\"exitCode\":2,\"status\":\"INVALID_INPUT\",\"error\":" + ServeServer.jsonStr(e.getMessage()) + "}");
        }
        MAPPER.writeValue(configFile.toFile(), checked);
        return PathPolicy.runWith(policy, () -> runInProcess(command, configFile));
    }

    /** Runs a subcommand in this JVM with JSON output and returns what it printed on stdout. */
    private static ServeServer.CommandResult runInProcess(String command, Path configFile) {
        // ValidateCommand redirects System.out→System.err for --format json internally,
        // so we capture around that entire flow. Commands run one at a time (single worker).
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
        PrintStream origOut = System.out;
        System.setOut(captured);
        int exitCode;
        try {
            // The --format flag in the request body is overridden by the inline flag.
            // Only some commands have --format; adding it to the others fails as an unknown option.
            String[] args = JSON_COMMANDS.contains(command)
                    ? new String[] {command, "--config", configFile.toString(), "--format", "json"}
                    : new String[] {command, "--config", configFile.toString()};
            exitCode = CimPalCli.inProcess().execute(args);
        } finally {
            System.setOut(origOut);
        }
        return new ServeServer.CommandResult(exitCode, baos.toString(StandardCharsets.UTF_8));
    }

    /**
     * Deletes the token file if it still holds {@code token}. Another {@code serve} started with
     * the same token file has overwritten it with its own token, and that file must stay.
     */
    private static void deleteIfOurs(Path file, String token) {
        if (file == null) return;
        try {
            if (Files.size(file) <= 1024 && Files.readString(file, StandardCharsets.UTF_8).equals(token)) {
                Files.delete(file);
            }
        } catch (IOException ignored) {
            // best effort: the token is useless once the server has stopped
        }
    }
}
