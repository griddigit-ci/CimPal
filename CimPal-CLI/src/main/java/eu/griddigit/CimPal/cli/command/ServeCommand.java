package eu.griddigit.CimPal.cli.command;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
 *   GET  /health            — {"status":"ok","version":"..."}
 *   GET  /commands          — list of available endpoints
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <p>The server uses a single-threaded executor, so requests are serialised.  This avoids
 * races on the static flags in {@code ValidationTools} (debug mode, turtle export).  For a
 * local agent tool this is the right trade-off: a single validation run saturates the CPU
 * anyway, and the agent sends one request at a time.
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — server started and is listening (blocks until SIGINT / {@code /shutdown})
 *   <li>2 — could not bind to the requested port
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
                "many validation calls, avoiding repeated cold-start overhead."
        },
        sortOptions = false
)
public class ServeCommand implements Callable<Integer> {

    private static final String VERSION = "CimPal CLI 2026.9";

    /** Commands exposed as POST endpoints. Must match the picocli command names exactly. */
    private static final List<String> COMMANDS = List.of(
            "validate", "sparql", "convert", "compare", "compare-instances",
            "rdfs2shacl", "organize", "excel2shacl", "gen-instances", "manifest"
    );

    @Option(names = {"--port", "-p"},
            defaultValue = "7474",
            description = "Port to listen on (default: 7474).")
    private int port;

    @Option(names = "--host",
            defaultValue = "localhost",
            description = "Bind address (default: localhost — not accessible from the network).")
    private String host;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException ex) {
            System.err.println("[ERROR] Could not bind to " + host + ":" + port + " — " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }

        // Single-threaded executor: serialises requests to avoid races on ValidationTools statics.
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cimpal-serve");
            t.setDaemon(false);
            return t;
        }));

        // --- command endpoints ---
        for (String cmd : COMMANDS) {
            final String cmdName = cmd;
            server.createContext("/" + cmdName, exchange -> handleCommand(exchange, cmdName));
        }

        // --- utility endpoints ---
        server.createContext("/health",   this::handleHealth);
        server.createContext("/commands", this::handleCommands);
        server.createContext("/shutdown", exchange -> {
            respond(exchange, 200, "{\"status\":\"shutting down\"}");
            server.stop(1);
        });

        server.start();
        System.out.println("[INFO] CimPal daemon listening on http://" + host + ":" + port);
        System.out.println("[INFO] Endpoints: " + COMMANDS.stream().map(c -> "/" + c)
                .reduce((a, b) -> a + "  " + b).orElse(""));
        System.out.println("[INFO] GET /health  GET /commands  POST /shutdown");
        System.out.println("[INFO] Send POST /shutdown or press Ctrl-C to stop.");

        // Block main thread until the server is stopped.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}

        return ExitCode.OK;
    }

    // -------------------------------------------------------------------------
    // Command dispatcher
    // -------------------------------------------------------------------------

    private void handleCommand(HttpExchange exchange, String commandName) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed — use POST"));
            return;
        }

        byte[] body;
        try {
            body = exchange.getRequestBody().readAllBytes();
        } catch (IOException ex) {
            respond(exchange, 400, error("Could not read request body: " + ex.getMessage()));
            return;
        }

        if (body.length == 0) {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }

        // Write the request body to a temp config file
        Path tempConfig = null;
        try {
            tempConfig = Files.createTempFile("cimpal-serve-", ".json");
            Files.write(tempConfig, body);

            // Capture stdout so we can return it in the response body.
            // ValidateCommand redirects System.out→System.err for --format json internally,
            // so we wrap around that entire flow.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
            PrintStream origOut  = System.out;
            System.setOut(captured);

            int exitCode;
            try {
                // Always request JSON output so the response is machine-readable.
                // The --format flag in the request body is overridden by the inline flag.
                String[] args = {commandName, "--config", tempConfig.toString(), "--format", "json"};
                exitCode = new CommandLine(new CimPalCli()).execute(args);
            } finally {
                System.setOut(origOut);
            }

            String responseBody = baos.toString(StandardCharsets.UTF_8).trim();
            if (responseBody.isEmpty()) {
                // Command produced no JSON output — synthesise a minimal response.
                responseBody = "{\"exitCode\":" + exitCode
                        + ",\"status\":\"" + statusLabel(exitCode) + "\"}";
            }

            // HTTP status: treat exit 0 and 1 (violations) as success; 2+ as error.
            int httpStatus = exitCode <= 1 ? 200 : (exitCode == 2 ? 400 : 500);
            respond(exchange, httpStatus, responseBody);

        } catch (Exception ex) {
            respond(exchange, 500, error("Internal error: " + ex.getMessage()));
        } finally {
            if (tempConfig != null) {
                try { Files.deleteIfExists(tempConfig); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility endpoints
    // -------------------------------------------------------------------------

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed"));
            return;
        }
        respond(exchange, 200,
                "{\"status\":\"ok\",\"version\":" + jsonStr(VERSION) + "}");
    }

    private void handleCommands(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed"));
            return;
        }
        StringBuilder sb = new StringBuilder("{\"commands\":[");
        for (int i = 0; i < COMMANDS.size(); i++) {
            sb.append(jsonStr("/" + COMMANDS.get(i)));
            if (i < COMMANDS.size() - 1) sb.append(",");
        }
        sb.append("],\"utility\":[");
        sb.append(jsonStr("/health")).append(",");
        sb.append(jsonStr("/commands")).append(",");
        sb.append(jsonStr("/shutdown"));
        sb.append("]}");
        respond(exchange, 200, sb.toString());
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
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

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
