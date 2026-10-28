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
import eu.griddigit.cimpal.core.utils.RunScope;
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
 * <p>Commands run on {@code --workers} threads, one by default (DEP-8). Each command has its own
 * path policy, remote-import caches and output streams ({@link CliStreams}), so a command's
 * results don't depend on what else runs. With more than one worker, a command starts only while
 * the estimated heap of the running ones ({@link #estimateHeap}) plus its own fits
 * {@code --max-concurrent-heap-bytes}. At most {@code --queue-size} requests wait; more get 503.
 * A request not finished within {@code --request-timeout} gets 504.
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
            description = "Read the accepted bearer token(s) from this file instead, one per line, each at "
                    + "least 32 characters: name:role:token for a named client (role client or admin), or "
                    + "one or two bare tokens (client 'default', admin). The file is never "
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
            description = "Requests that may wait while the workers are busy (default: 4); more get 503.")
    private int queueSize;

    @Option(names = "--workers",
            defaultValue = "1",
            description = "Commands that may run at once (default: 1, at most 64). More than the number of "
                    + "processors rarely helps; see also --max-concurrent-heap-bytes.")
    private int workers;

    @Option(names = "--max-concurrent-heap-bytes",
            description = "With --workers above 1: a command starts only while the estimated heap of the running "
                    + "ones plus its own fits this (default: three quarters of the max heap). A command is estimated "
                    + "at 10 times the size of its input files, at least 64 MiB; one with nothing else running "
                    + "always starts.")
    private Long maxConcurrentHeapBytes;

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
            description = "Serve Prometheus metrics at GET /metrics (needs an admin token).")
    private boolean metrics;

    @Option(names = "--job-store",
            defaultValue = "${env:CIMPAL_JOB_STORE:-memory}",
            description = "memory (default, or CIMPAL_JOB_STORE): jobs are lost on restart; or jdbc: jobs, results, logs and the uploads'"
                    + " bookkeeping in PostgreSQL (--job-store-url), so queued jobs run after a restart and"
                    + " finished ones can still be fetched. A job running when serve stopped ends as interrupted.")
    private String jobStoreKind;

    @Option(names = "--job-store-url",
            description = "JDBC URL of the PostgreSQL database, e.g. jdbc:postgresql://db:5432/cimpal?sslmode=verify-full"
                    + " (or CIMPAL_JOB_STORE_URL). It must not hold the password.")
    private String jobStoreUrl;

    @Option(names = "--job-store-user",
            description = "Database user (or CIMPAL_JOB_STORE_USER).")
    private String jobStoreUser;

    @Option(names = "--job-store-password-file",
            description = "File holding the database password, e.g. a mounted secret (or CIMPAL_JOB_STORE_PASSWORD_FILE,"
                    + " or the password itself in CIMPAL_JOB_STORE_PASSWORD). Never given on the command line.")
    private Path jobStorePasswordFile;

    @Option(names = "--job-store-pool",
            description = "Database connections (default: --workers + 4, at most 64).")
    private Integer jobStorePool;

    @Option(names = "--idempotency-ttl",
            defaultValue = "PT24H",
            description = "How long an Idempotency-Key of POST /v1/jobs and /v1/uploads is remembered (ISO-8601"
                    + " duration, default: PT24H, PT1M to P30D): a repeat within it gets the same job or upload.")
    private Duration idempotencyTtl;

    @Option(names = "--callback-secrets",
            description = "File of name:secret lines that sign each client's job callbacks (DEP-14), e.g. a mounted"
                    + " secret; a client also needs callbackAllow prefixes in --client-quotas. Without it, jobs"
                    + " with a callback are refused.")
    private Path callbackSecrets;

    @Option(names = "--requeue-interrupted",
            description = "With --job-store jdbc: after a restart, run the jobs that were running again instead of"
                    + " failing them as interrupted. Only safe when a job's outputs are simply overwritten.")
    private boolean requeueInterrupted;

    @Option(names = "--client-quotas",
            description = "JSON file of per-client limits (maxQueued, maxRunning, maxUploadBytes, maxWorkBytes, "
                    + "requestsPerMinute) under \"default\" and \"clients\": {name: {...}}; read at start. "
                    + "Without it each client may use the server's whole limits.")
    private Path clientQuotas;

    @Option(names = "--work-root",
            description = "Folder for uploads and job outputs of the /v1 file exchange (default: .cimpal-work "
                    + "in the first --write-root, else in the first --root). It is emptied at start and no "
                    + "request path may point into it.")
    private Path workRoot;

    @Option(names = "--no-file-exchange",
            description = "Turn uploads and downloads off: no work folder is created, and the /v1/uploads and "
                    + "job file routes answer 404.")
    private boolean noFileExchange;

    @Option(names = "--max-upload-bytes",
            defaultValue = "2147483648",
            description = "Largest upload (POST /v1/uploads) in bytes (default: 2147483648 = 2 GiB, at most 16 GiB).")
    private long maxUploadBytes;

    @Option(names = "--upload-ttl",
            defaultValue = "PT24H",
            description = "How long an upload is kept once no queued or running job uses it (ISO-8601 duration, "
                    + "default: PT24H). Job outputs are kept as long as their job (--job-ttl).")
    private Duration uploadTtl;

    @Option(names = "--work-max-bytes",
            defaultValue = "21474836480",
            description = "Disk space for uploads and job outputs together (default: 21474836480 = 20 GiB); "
                    + "an upload that doesn't fit gets 507.")
    private long workMaxBytes;

    @Option(names = "--request-read-time",
            defaultValue = "PT60S",
            description = "Time a client has to send a whole request, body included (ISO-8601 duration, default: "
                    + "PT60S, at most PT2H). Protects against slow clients; raise it for large uploads sent "
                    + "without a buffering proxy in front.")
    private Duration requestReadTime;

    @CommandLine.Mixin
    RootOptions rootOptions = new RootOptions();

    @CommandLine.Mixin
    PythonOptions pythonOptions = new PythonOptions();

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
        // SEC-3 (G9): the interpreter of the Python engines is set here, never by a request.
        if (!pythonOptions.apply(true)) {
            return ExitCode.INVALID_INPUT;
        }
        // The roots must exist before anything is created in them (the default work folder) or a
        // token file is written: a mistyped --root or an unmounted volume is bad input (TEST-4),
        // as it is for mcp and run.
        try {
            rootOptions.policy(List.of(Path.of("").toAbsolutePath()));
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }
        if (!logFormat.equalsIgnoreCase("json")) {
            return serve(ServeLog.text());
        }
        // Every stderr line becomes a JSON object, also the commands' own output.
        PrintStream originalErr = System.err;
        ServeLog log = ServeLog.json(originalErr, clock);
        // A line is attributed to the job whose thread wrote it (the label of its CliStreams binding).
        PrintStream wrapped = CliStreams.routing(log.stderr(CliStreams::label), false);
        System.setErr(wrapped);
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
        ClientQuotas.Settings quotaSettings = ClientQuotas.Settings.NONE;
        StoreSettings storeSettings = null;
        CallbackSecrets callbackSecretsRead = CallbackSecrets.NONE;
        try {
            for (String name : allowedHostNames) {
                allowedHosts.add(ServeSecurity.allowedHostEntry(name));
            }
            if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
                throw new IllegalArgumentException("--shutdown-grace must be positive.");
            }
            storeSettings = jobStoreSettings(jobStoreKind, jobStoreUrl, jobStoreUser, jobStorePasswordFile,
                    jobStorePool, workers, requeueInterrupted, environment);
            if (storeSettings != null && WEAK_SSLMODE.matcher(storeSettings.url()).find()) {
                log.warn("store.tls", "serve: --job-store-url turns certificate checks off (sslmode disable, allow or"
                        + " prefer); use sslmode=verify-full unless the database is on a private network");
            }
            if (callbackSecrets != null) {
                callbackSecretsRead = CallbackSecrets.read(callbackSecrets);
            }
            if (clientQuotas != null) {
                quotaSettings = ClientQuotas.read(clientQuotas);
                Set<String> known = tokenSource != null ? tokenSource.clientNames()
                        : Set.of(TokenSource.Client.DEFAULT.name());
                for (String name : new java.util.TreeSet<>(quotaSettings.clients().keySet())) {
                    if (!known.contains(name)) {
                        log.warn("quota.unknown", "serve: --client-quotas names client '" + name
                                + "', which has no token (yet)", "client", name);
                    }
                }
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
                .workers(workers)
                .sizeEstimator((command, body, workspace) -> estimateHeap(command, body))
                .clientQuotas(quotaSettings)
                .requeueInterrupted(requeueInterrupted)
                .idempotencyTtl(idempotencyTtl)
                .callbackSecrets(callbackSecretsRead)
                .requestTimeout(requestTimeout)
                .jobTimeout(jobTimeout)
                .maxJobs(maxJobs)
                .jobTtl(jobTtl)
                .jobLogLines(jobLogLines)
                .maxResultBytes(maxResultBytes);
        if (jobStoreBytes != null) {
            builder.jobStoreBytes(jobStoreBytes);
        }
        if (maxConcurrentHeapBytes != null) {
            builder.maxConcurrentHeapBytes(maxConcurrentHeapBytes);
        }

        List<Path> defaultRoots = List.of(Path.of("").toAbsolutePath());
        PathPolicy policy;
        Path work = null;
        try {
            if (!noFileExchange && workRoot == null) {
                // The default lies in the data folder, which may well be read-only (a container
                // with a read-only /data, shared-volume mode only): then serve runs without uploads.
                Path defaultWork = rootOptions.defaultWorkRoot(defaultRoots).toAbsolutePath();
                try {
                    Files.createDirectories(defaultWork);
                } catch (IOException ex) {
                    System.err.println("[WARN] File exchange is off: the default work folder " + defaultWork
                            + " can't be created (" + ex.getClass().getSimpleName() + "). Give a writable --work-root"
                            + " to accept uploads, or --no-file-exchange to silence this.");
                    defaultWork = null;
                }
                work = defaultWork;
            } else if (!noFileExchange) {
                work = workRoot.toAbsolutePath();
                Files.createDirectories(work);
            }
            if (work == null) {
                policy = rootOptions.policy(defaultRoots);
            } else {
                // The work area holds other callers' uploads and outputs: no request path may reach it.
                policy = rootOptions.policy(defaultRoots, work);
                Path workReal = work.toRealPath();
                for (Path root : java.util.stream.Stream.concat(policy.readRoots().stream(), policy.writeRoots().stream())
                        .toList()) {
                    if (root.startsWith(workReal)) {
                        throw new IllegalArgumentException("--work-root " + work + " is or contains the allowed folder "
                                + root + ", which would open other callers' uploads to every request. Use a folder"
                                + " inside a root (the default .cimpal-work) or outside all of them.");
                    }
                }
            }
        } catch (IllegalArgumentException | IOException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        }
        Path base = rootOptions.base(defaultRoots);
        ServeServer.Config builtConfig = builder
                .workRoot(work)
                .maxUploadBytes(maxUploadBytes)
                .uploadTtl(uploadTtl)
                .workMaxBytes(workMaxBytes)
                .requestReadTime(requestReadTime)
                .build();
        Path workArea = work;
        ServeServer.Config config = builtConfig;
        ServeServer.CommandRunner runner = runnerForTest != null ? runnerForTest : new ServeServer.CommandRunner() {
            @Override
            public ServeServer.CommandResult run(String command, Path configFile) throws Exception {
                return runWithPolicy(policy, base, command, configFile);
            }

            @Override
            public ServeServer.CommandResult run(String command, Path configFile, WorkArea.JobWorkspace workspace)
                    throws Exception {
                return runWithPolicy(rootOptions.jobPolicy(workspace.uploadDir(), workspace.outDir(), workArea),
                        workspace.uploadDir(), command, configFile, true);
            }
        };
        ServeServer.RequestCheck check = new ServeServer.RequestCheck() {
            @Override
            public byte[] check(String command, byte[] body) throws ServeServer.Rejected {
                return checkPaths(policy, base, command, body);
            }

            @Override
            public byte[] check(String command, byte[] body, WorkArea.JobWorkspace workspace) throws ServeServer.Rejected {
                return checkUploadPaths(rootOptions.jobPolicy(workspace.uploadDir(), workspace.outDir(), workArea),
                        workspace, command, body);
            }
        };

        // While the server runs, a path check on a thread without a command's policy (e.g. a pool
        // thread that didn't inherit it) is refused rather than unrestricted (DEP-8).
        JobStore store = null;
        if (storeSettings != null) {
            try {
                store = new JdbcJobStore(new JdbcPool(storeSettings.url(), storeSettings.user(), storeSettings.password(),
                        storeSettings.pool()), maxJobs, jobTtl, jobTimeout, jobLogLines, clock);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                System.err.println("[ERROR] " + ex.getMessage());
                deleteIfOurs(writtenTokenFile, token);
                return ExitCode.INVALID_INPUT;
            }
            config = builder.jobStore(store).build();
        }
        PathPolicy.Requirement policyRequired = PathPolicy.requireContext();
        ServeServer server;
        try {
            server = ServeServer.start(config, runner, check);
        } catch (IllegalArgumentException ex) {
            policyRequired.close();
            closeQuietly(store);
            System.err.println("[ERROR] " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        } catch (IOException ex) {
            policyRequired.close();
            closeQuietly(store);
            System.err.println("[ERROR] Could not bind to " + host + ":" + port + " - " + ex.getMessage());
            deleteIfOurs(writtenTokenFile, token);
            return ExitCode.INVALID_INPUT;
        } catch (RuntimeException | Error ex) {
            policyRequired.close();
            closeQuietly(store);
            throw ex;
        }
        String storeOrigin = storeSettings == null ? "memory (jobs are lost on restart)"
                : "PostgreSQL at " + storeSettings.displayUrl() + " (jobs survive a restart)";

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
                    "metrics", metrics, "sigterm", termHandled, "jobStore", storeOrigin);
        } else {
            System.out.println("[INFO] CimPal daemon listening on http://" + host + ":" + server.port());
            System.out.println("[INFO] Bearer token " + tokenOrigin + " (send it as 'Authorization: Bearer <token>')");
            if (!allowedHosts.isEmpty()) {
                System.out.println("[INFO] Also accepted as Host: " + String.join(", ", allowedHosts));
            }
            System.out.println("[INFO] Allowed roots: read " + policy.readRoots() + ", write " + policy.writeRoots());
            System.out.println("[INFO] Job store: " + storeOrigin);
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
                            + "output may be incomplete.", "jobId", String.join(",", server.runningJobIds()));
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
            policyRequired.close();
            closeQuietly(store); // after the running jobs wrote their results
            log.info("server.stopped", "serve: stopped", "exitCode", exit);
        }
        return exit;
    }

    private static void closeQuietly(JobStore store) {
        if (store != null) {
            try {
                store.close();
            } catch (RuntimeException ignored) {
                // stopping anyway
            }
        }
    }

    /** {@code --job-store jdbc}: where the database is and how to log in. {@link #toString()} hides the password. */
    record StoreSettings(String url, String user, String password, int pool) {

        /** The URL without its query (which may name the user or other settings). */
        String displayUrl() {
            int q = url.indexOf('?');
            return q < 0 ? url : url.substring(0, q);
        }

        @Override
        public String toString() {
            return "StoreSettings[" + displayUrl() + ", user " + user + ", pool " + pool + "]";
        }
    }

    static final String STORE_URL_ENV = "CIMPAL_JOB_STORE_URL";
    static final String STORE_USER_ENV = "CIMPAL_JOB_STORE_USER";
    static final String STORE_PASSWORD_ENV = "CIMPAL_JOB_STORE_PASSWORD";
    static final String STORE_PASSWORD_FILE_ENV = "CIMPAL_JOB_STORE_PASSWORD_FILE";
    private static final java.util.regex.Pattern PASSWORD_IN_URL =
            java.util.regex.Pattern.compile("(?i)[?&;]password=");
    /**
     * pgjdbc parameters a URL must not carry: secrets on a command line, or class names the driver
     * would load and run (socket and SSL factories, callbacks, plugins), or a log file it would write.
     */
    private static final java.util.regex.Pattern REFUSED_URL_PARAMETER = java.util.regex.Pattern.compile(
            "(?i)[?&;](sslpassword|sslfactory|sslfactoryarg|socketfactory|socketfactoryarg|sslhostnameverifier"
                    + "|sslpasswordcallback|authenticationpluginclassname|loggerfile|loggerlevel|sslresponsetimeout"
                    + "|gssencmode|jaasapplicationname|kerberosservername)=");
    private static final java.util.regex.Pattern WEAK_SSLMODE =
            java.util.regex.Pattern.compile("(?i)[?&;]sslmode=(disable|allow|prefer)(?=$|[&;])");

    /**
     * Checks the {@code --job-store} options (DEP-11) without connecting: null for the in-memory
     * store, the settings for {@code jdbc}.
     *
     * @throws IllegalArgumentException with a message that never holds the password
     */
    static StoreSettings jobStoreSettings(String kind, String url, String user, Path passwordFile, Integer pool,
                                          int workers, boolean requeue, java.util.Map<String, String> env) {
        String k = kind == null ? "memory" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        String envUrl = env.get(STORE_URL_ENV);
        String envUser = env.get(STORE_USER_ENV);
        String envPassword = env.get(STORE_PASSWORD_ENV);
        String envPasswordFile = env.get(STORE_PASSWORD_FILE_ENV);
        switch (k) {
            case "memory" -> {
                if (url != null || user != null || passwordFile != null || pool != null || requeue) {
                    throw new IllegalArgumentException("--job-store-url, --job-store-user, --job-store-password-file,"
                            + " --job-store-pool and --requeue-interrupted need --job-store jdbc.");
                }
                if (envUrl != null || envPassword != null || envPasswordFile != null) {
                    // Set up for a database but not told to use it: jobs would be lost on every restart.
                    throw new IllegalArgumentException(STORE_URL_ENV + " or the database password is set, but the"
                            + " job store is memory; set CIMPAL_JOB_STORE=jdbc (or --job-store jdbc), or unset them.");
                }
                return null;
            }
            case "jdbc" -> {
                // below
            }
            default -> throw new IllegalArgumentException("--job-store must be memory or jdbc.");
        }
        String u = url != null ? url.strip() : envUrl == null ? null : envUrl.strip();
        if (u == null || u.isEmpty()) {
            throw new IllegalArgumentException("--job-store jdbc needs --job-store-url (or " + STORE_URL_ENV + ").");
        }
        if (!u.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("--job-store-url must be a PostgreSQL JDBC URL (jdbc:postgresql://...).");
        }
        if (PASSWORD_IN_URL.matcher(u).find()) {
            throw new IllegalArgumentException("--job-store-url must not hold the password; use"
                    + " --job-store-password-file or " + STORE_PASSWORD_ENV + ".");
        }
        java.util.regex.Matcher refused = REFUSED_URL_PARAMETER.matcher(u);
        if (refused.find()) {
            throw new IllegalArgumentException("--job-store-url must not set " + refused.group(1)
                    + " (a secret, a class to load, or a file to write).");
        }
        if (u.contains("@")) {
            throw new IllegalArgumentException("--job-store-url must not hold user information (user@host);"
                    + " use --job-store-user.");
        }
        Path file = passwordFile != null ? passwordFile
                : envPasswordFile == null || envPasswordFile.isBlank() ? null : Path.of(envPasswordFile.strip());
        if (file != null && envPassword != null) {
            throw new IllegalArgumentException("Give the database password either in a file or in "
                    + STORE_PASSWORD_ENV + ", not both.");
        }
        String password = envPassword;
        if (file != null) {
            try {
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("The database password file " + file + " is not a regular file.");
                }
                byte[] bytes;
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(4097);
                }
                if (bytes.length > 4096) {
                    throw new IllegalArgumentException("The database password file " + file + " is larger than 4 KiB.");
                }
                password = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read the database password file " + file + ": "
                        + e.getClass().getSimpleName());
            }
        }
        int size = pool != null ? pool : Math.min(64, workers + 4);
        if (size < 1 || size > 64) {
            throw new IllegalArgumentException("--job-store-pool must be between 1 and 64.");
        }
        String usr = user != null ? user : envUser;
        return new StoreSettings(u, usr == null || usr.isBlank() ? null : usr.strip(), password, size);
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
     * {@link #checkPaths} for a job that reads an upload (DEP-7): relative paths resolve against
     * the upload, relative or missing outputs go to the job's {@code out/}, and {@code policy}
     * allows nothing else to be written.
     */
    static byte[] checkUploadPaths(PathPolicy policy, WorkArea.JobWorkspace workspace, String command, byte[] body)
            throws ServeServer.Rejected {
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ServeServer.Rejected(400, "Request body is not valid JSON.");
        }
        try {
            ObjectNode placed = PathGuard.placeOutputs(command, json, workspace.outDir());
            return MAPPER.writeValueAsBytes(PathGuard.check(command, placed, policy, workspace.uploadDir(), true));
        } catch (PathNotAllowedException e) {
            throw new ServeServer.Rejected(403, e.getMessage());
        }
    }

    private static ServeServer.CommandResult runWithPolicy(PathPolicy policy, Path base, String command,
                                                           Path configFile) throws Exception {
        return runWithPolicy(policy, base, command, configFile, false);
    }

    /**
     * Runs the command with the path policy in force for paths Core resolves internally. The
     * paths are checked again first: an earlier request in the queue may have created an output
     * file, or the file system may have changed, since the request was accepted.
     */
    private static ServeServer.CommandResult runWithPolicy(PathPolicy policy, Path base, String command,
                                                           Path configFile, boolean baseOnly) throws Exception {
        ObjectNode checked;
        try {
            checked = PathGuard.check(command, MAPPER.readTree(configFile.toFile()), policy, base, baseOnly);
        } catch (PathNotAllowedException e) {
            return new ServeServer.CommandResult(ExitCode.INVALID_INPUT,
                    "{\"exitCode\":2,\"status\":\"INVALID_INPUT\",\"error\":" + ServeServer.jsonStr(e.getMessage()) + "}");
        }
        MAPPER.writeValue(configFile.toFile(), checked);
        return PathPolicy.runWith(policy, () -> runInProcess(command, configFile));
    }

    /**
     * Runs a subcommand in this JVM with JSON output and returns what it printed on stdout. Its
     * stdout is bound to a buffer for this thread and the threads it starts ({@link CliStreams}),
     * and its remote-import caches are its own ({@link RunScope}), so commands on other workers
     * neither see nor disturb them (DEP-8).
     */
    private static ServeServer.CommandResult runInProcess(String command, Path configFile) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
        // The --format flag in the request body is overridden by the inline flag.
        // Only some commands have --format; adding it to the others fails as an unknown option.
        String[] args = JSON_COMMANDS.contains(command)
                ? new String[] {command, "--config", configFile.toString(), "--format", "json"}
                : new String[] {command, "--config", configFile.toString()};
        int exitCode = CliStreams.bind(captured, null, null,
                () -> RunScope.runIn(() -> CimPalCli.inProcess().execute(args)));
        return new ServeServer.CommandResult(exitCode, baos.toString(StandardCharsets.UTF_8));
    }

    /** Heap per byte of input: the sizing guide measures about 9 times the RDF/XML size. */
    static final long HEAP_PER_INPUT_BYTE = 10;
    /** The least a command is estimated at, for its own working set. */
    static final long MIN_HEAP_ESTIMATE = 64L << 20;
    /**
     * File system entries (files, folders, anything) one estimate may look at in total. Beyond
     * that the command counts as too large to share memory: it runs alone.
     */
    static final int MAX_ESTIMATE_ENTRIES = 100_000;
    /** How deep an input folder is searched. */
    static final int MAX_ESTIMATE_DEPTH = 32;

    /**
     * The heap a checked command is estimated to need, for the admission of {@code --workers}
     * (DEP-8): {@value #HEAP_PER_INPUT_BYTE} times the size of everything it reads (files, the
     * files in folders, archives as they are), at least {@link #MIN_HEAP_ESTIMATE}. Paths in a
     * checked body are absolute and allowed; a value that is no file (a preset name, an inline
     * query) counts nothing. A path given twice counts once.
     *
     * <p>It runs on a worker before the command starts, outside the job timeout, so it is
     * bounded: after {@value #MAX_ESTIMATE_ENTRIES} entries in total, or a folder it can't read,
     * the estimate is {@link Long#MAX_VALUE} and the command runs alone. Links and other
     * reparse points (Windows junctions) inside folders are not followed.
     */
    static long estimateHeap(String command, byte[] checkedBody) {
        return estimateHeap(command, checkedBody, MAX_ESTIMATE_ENTRIES);
    }

    /** {@link #estimateHeap(String, byte[])} with at most {@code maxEntries} entries (tests). */
    static long estimateHeap(String command, byte[] checkedBody, int maxEntries) {
        JsonNode json = MAPPER.readTree(checkedBody);
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        for (PathGuard.Field field : PathGuard.FIELDS.getOrDefault(command, List.of())) {
            if (field.kind() == PathGuard.Kind.WRITE_FILE || field.kind() == PathGuard.Kind.WRITE_DIR) {
                continue;
            }
            JsonNode value = json.get(field.key());
            if (value != null && value.isArray()) {
                value.forEach(v -> paths.add(v.asString().strip()));
            } else if (value != null && value.isString()) {
                for (String p : field.kind() == PathGuard.Kind.READ_LIST
                        ? value.asString().split(",") : new String[] {value.asString()}) {
                    paths.add(p.strip());
                }
            }
        }
        int[] entries = {0};
        long input = 0;
        for (String p : paths) {
            if (entries[0] > maxEntries) {
                return Long.MAX_VALUE;
            }
            long bytes = inputBytes(p, entries, maxEntries);
            if (bytes == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            input = saturatedAdd(input, bytes);
        }
        long estimate = input > Long.MAX_VALUE / HEAP_PER_INPUT_BYTE ? Long.MAX_VALUE : input * HEAP_PER_INPUT_BYTE;
        return Math.max(MIN_HEAP_ESTIMATE, estimate);
    }

    /** The bytes under {@code value}, or {@link Long#MAX_VALUE} when the budget ran out or a folder can't be read. */
    private static long inputBytes(String value, int[] entries, int maxEntries) {
        if (value.isEmpty()) {
            return 0;
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (java.nio.file.InvalidPathException e) {
            return 0; // text, not a path
        }
        if (++entries[0] > maxEntries) {
            return Long.MAX_VALUE;
        }
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            if (!Files.isDirectory(path)) {
                return 0;
            }
            long[] sum = {0};
            boolean[] exhausted = {false};
            // Without FOLLOW_LINKS: links inside the folder are visited as themselves, not followed.
            Files.walkFileTree(path, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_ESTIMATE_DEPTH,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(
                                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                            return count(attrs);
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            java.nio.file.FileVisitResult next = count(attrs);
                            if (next == java.nio.file.FileVisitResult.CONTINUE && attrs.isRegularFile()) {
                                sum[0] = saturatedAdd(sum[0], attrs.size());
                            }
                            return next;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                            throw e;
                        }

                        private java.nio.file.FileVisitResult count(java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (++entries[0] > maxEntries) {
                                exhausted[0] = true;
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            // A junction (Windows) is "other", not a link: its target is not searched.
                            return attrs.isOther() || attrs.isSymbolicLink()
                                    ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                    : java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
            return exhausted[0] ? Long.MAX_VALUE : sum[0];
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE; // can't be told: it runs alone
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
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
