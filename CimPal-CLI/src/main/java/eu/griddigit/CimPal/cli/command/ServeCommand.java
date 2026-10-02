/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *   POST /validate          — SHACL validation (mapping, timestamped)
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
 *   <li>0 — server ran and was stopped (blocks until SIGINT / {@code POST /shutdown})
 *   <li>2 — could not bind to the requested port, or invalid options / token
 *   <li>3 — unexpected startup error
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

    /** How long a stopping server waits for a running command to finish writing its output. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(60);

    /** Environment the token is looked up in; replaced by tests. */
    Map<String, String> environment = System.getenv();

    /** Called with the running server once it listens; tests use it to find the port. */
    Consumer<ServeServer> onStarted = server -> { };

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        String token;
        Path writtenTokenFile = null;
        try {
            Optional<String> fromEnv = ServeSecurity.tokenFromEnvironment(environment);
            if (fromEnv.isPresent()) {
                token = fromEnv.get();
            } else {
                token = ServeSecurity.newToken();
                writtenTokenFile = tokenFile != null ? tokenFile
                        : ServeSecurity.defaultTokenFile(environment, System.getProperty("os.name"),
                                Path.of(System.getProperty("user.home")));
                ServeSecurity.writeTokenFile(writtenTokenFile, token);
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        } catch (IOException ex) {
            System.err.println("[ERROR] Could not write the token file: " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }

        ServeServer.Config config = ServeServer.Config.builder()
                .host(host)
                .port(port)
                .token(token)
                .allowedOrigins(new LinkedHashSet<>(allowedOrigins))
                .allowRemote(allowRemote)
                .maxBodyBytes(maxBodyBytes)
                .queueSize(queueSize)
                .requestTimeout(requestTimeout)
                .build();

        ServeServer server;
        try {
            server = ServeServer.start(config, ServeCommand::runInProcess);
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
            server.close();
            deleteIfOurs(tokenFileToDelete, ownToken);
        }, "cimpal-serve-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        if (!ServeSecurity.isLoopback(host)) {
            System.err.println("[WARN] Listening on non-loopback address " + host
                    + ". Anyone who can reach it and has the token can run CimPal commands on this "
                    + "machine, and traffic is not encrypted.");
        }
        System.out.println("[INFO] CimPal daemon listening on http://" + host + ":" + server.port());
        if (writtenTokenFile != null) {
            System.out.println("[INFO] Bearer token written to " + writtenTokenFile.toAbsolutePath()
                    + " (send it as 'Authorization: Bearer <token>')");
        } else {
            System.out.println("[INFO] Bearer token taken from " + ServeSecurity.TOKEN_ENV);
        }
        System.out.println("[INFO] Endpoints: " + ServeServer.COMMANDS.stream().map(c -> "/" + c)
                .reduce((a, b) -> a + "  " + b).orElse(""));
        System.out.println("[INFO] GET /health  GET /commands  POST /shutdown");
        System.out.println("[INFO] Send POST /shutdown or press Ctrl-C to stop.");
        onStarted.accept(server);

        try {
            server.awaitStop();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
            deleteIfOurs(tokenFileToDelete, ownToken);
            try {
                if (!server.awaitWorker(SHUTDOWN_GRACE)) {
                    System.err.println("[WARN] A command was still running after " + SHUTDOWN_GRACE.toSeconds()
                            + " s; its output may be incomplete.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // the JVM is already shutting down and runs the hook itself
            }
        }
        return ExitCode.OK;
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
            String[] args = {command, "--config", configFile.toString(), "--format", "json"};
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
