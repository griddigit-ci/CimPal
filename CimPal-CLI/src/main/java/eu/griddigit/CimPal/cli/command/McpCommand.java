/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.CliVersion;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.OutOfMemoryRethrow;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code mcp} subcommand â€” starts a Model Context Protocol server on stdin/stdout.
 *
 * <p>The MCP server exposes every CimPal operation as a typed tool that a Claude agent
 * (or any MCP-compatible client) can call without shell invocations.  Communication uses
 * the standard MCP stdio transport: newline-delimited JSON-RPC 2.0 messages on
 * stdin/stdout.
 *
 * <h2>Usage</h2>
 * <p>Add to your {@code claude_desktop_config.json}:
 * <pre>
 * {
 *   "mcpServers": {
 *     "cimpal": {
 *       "command": "java",
 *       "args": ["-jar", "C:/path/to/CimPal-CLI.jar", "mcp"]
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Or start manually and pipe in MCP messages:
 * <pre>
 *   java -jar CimPal-CLI.jar mcp
 * </pre>
 *
 * <h2>Protocol</h2>
 * <p>Implements MCP 2024-11-05:
 * <ul>
 *   <li>{@code initialize} / {@code notifications/initialized}
 *   <li>{@code tools/list}
 *   <li>{@code tools/call}
 *   <li>{@code ping}
 * </ul>
 *
 * <h2>Tools</h2>
 * <p>Each CimPal CLI command is exposed as a tool.  Arguments map 1-to-1 to the command's
 * config-file JSON keys.  All tool calls automatically use {@code --format json} so responses
 * are machine-readable.
 */
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = {
                "Start a Model Context Protocol (MCP) server on stdin/stdout.",
                "",
                "Exposes all CimPal operations as typed MCP tools so a Claude agent can call",
                "them without shell invocations.  Add to claude_desktop_config.json:",
                "  {\"mcpServers\":{\"cimpal\":{\"command\":\"java\",",
                "    \"args\":[\"-jar\",\"CimPal-CLI.jar\",\"mcp\",\"--root\",\"C:/Data\"]}}}",
                "",
                "File paths in tool calls must lie under a --root (default: the working directory)."
        },
        sortOptions = false
)
public class McpCommand implements Callable<Integer> {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME    = "CimPal";
    private static final String SERVER_VERSION = CliVersion.version();

    @Option(names = "--debug",
            description = "Write MCP message traffic to stderr for debugging.")
    private boolean debug;

    @CommandLine.Mixin
    RootOptions rootOptions = new RootOptions();

    /** Allowed roots for file paths in tool arguments (SEC-2); set in {@link #call()}. */
    private PathPolicy policy;
    private Path base;

    // The REAL stdout â€” used exclusively for MCP protocol output.
    // System.out is redirected to System.err at startup so that any accidental
    // println from the CLI infrastructure does not corrupt the JSON-RPC stream.
    private PrintStream mcpOut;
    private ObjectMapper mapper;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        mcpOut = System.out;
        System.setOut(System.err); // protect the MCP channel from accidental writes

        mapper = new ObjectMapper();
        List<Path> defaultRoots = List.of(Path.of("").toAbsolutePath());
        try {
            usePolicy(rootOptions.policy(defaultRoots), rootOptions.base(defaultRoots));
        } catch (IllegalArgumentException ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }
        System.err.println("[MCP] allowed roots: read " + policy.readRoots() + ", write " + policy.writeRoots());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                if (debug) System.err.println("[MCP â†] " + line);
                try {
                    dispatch(mapper.readTree(line));
                } catch (Exception ex) {
                    System.err.println("[MCP parse error] " + ex.getMessage());
                    // Best-effort error response with no id (we couldn't parse the id)
                    sendError(mapper.nullNode(), -32700, "Parse error: " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            System.err.println("[MCP fatal] " + ex.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }

        return ExitCode.OK;
    }

    // -------------------------------------------------------------------------
    // Dispatcher
    // -------------------------------------------------------------------------

    private void dispatch(JsonNode msg) throws Exception {
        JsonNode idNode = msg.path("id");
        String method   = msg.path("method").asText("");

        switch (method) {
            case "initialize"              -> handleInitialize(idNode, msg.path("params"));
            case "notifications/initialized" -> {}  // notification â€” no response
            case "tools/list"              -> handleToolsList(idNode);
            case "tools/call"              -> handleToolsCall(idNode, msg.path("params"));
            case "ping"                    -> send(idNode, mapper.createObjectNode());
            default -> sendError(idNode, -32601, "Method not found: " + method);
        }
    }

    // -------------------------------------------------------------------------
    // Protocol handlers
    // -------------------------------------------------------------------------

    private void handleInitialize(JsonNode id, JsonNode params) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        send(id, result);

        // Send the required initialized notification
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        notif.putObject("params");
        write(notif);
    }

    private void handleToolsList(JsonNode id) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools   = result.putArray("tools");
        buildTools(tools);
        send(id, result);
    }

    private void handleToolsCall(JsonNode id, JsonNode params) throws Exception {
        String toolName = params.path("name").asText("");
        ObjectNode result;
        try {
            result = callTool(toolName, params.path("arguments"));
        } catch (Error e) {
            if (OutOfMemoryRethrow.find(e).isPresent()) {
                // Tell the client why the server goes away; main then ends the JVM with exit 3.
                try {
                    synchronized (mcpOut) {
                        mcpOut.println("{\"jsonrpc\":\"2.0\",\"id\":" + (id.isMissingNode() ? "null" : id) + OUT_OF_MEMORY_ERROR);
                        mcpOut.flush();
                    }
                } catch (Throwable ignored) {
                    // Best effort: the client sees the server's output end instead.
                }
            }
            throw e;
        }
        if (result == null) {
            sendError(id, -32602, "Unknown tool: " + toolName);
            return;
        }
        send(id, result);
    }

    /** Sets the path policy (done by {@link #call()}; tests set it directly). */
    void usePolicy(PathPolicy policy, Path base) {
        this.policy = policy;
        this.base = base;
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
    }

    /**
     * Runs one tool and returns the MCP tool result, or null for an unknown tool. File paths in
     * {@code args} are checked against the allowed roots first; a refused path is an
     * {@code isError} result naming it, and the command doesn't run.
     */
    ObjectNode callTool(String toolName, JsonNode args) throws Exception {
        CommandSchemas.Entry spec = CommandSchemas.byToolName(toolName);
        if (spec == null) {
            return null;
        }
        ObjectNode checked;
        try {
            checked = PathGuard.check(spec.command(), args, policy, base);
        } catch (PathNotAllowedException e) {
            return toolResult("{\"exitCode\":2,\"status\":\"INVALID_INPUT\",\"error\":" + jsonStr(e.getMessage()) + "}",
                    true);
        }

        // Build a temp config file from the checked arguments
        Path tempConfig = null;
        try {
            tempConfig = Files.createTempFile("cimpal-mcp-", ".json");
            mapper.writeValue(tempConfig.toFile(), checked);
            Path config = tempConfig;

            // Capture System.out during command execution
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
            PrintStream previousOut = System.out;
            System.setOut(captured);

            int exitCode;
            try {
                // Always add --format json for commands that support it
                String[] cliArgs = spec.supportsJson()
                        ? new String[]{spec.command(), "--config", config.toString(), "--format", "json"}
                        : new String[]{spec.command(), "--config", config.toString()};
                exitCode = PathPolicy.runWith(policy,
                        () -> CimPalCli.inProcess().execute(cliArgs));
            } finally {
                System.setOut(previousOut);
            }

            String output = baos.toString(StandardCharsets.UTF_8).strip();

            // Wrap non-JSON output in a minimal envelope
            if (output.isEmpty()) {
                output = "{\"exitCode\":" + exitCode + ",\"status\":\"" + statusLabel(exitCode) + "\"}";
            } else if (!output.startsWith("{") && !output.startsWith("[")) {
                output = "{\"exitCode\":" + exitCode + ",\"output\":" + jsonStr(output) + "}";
            }
            return toolResult(output, exitCode >= 2);

        } finally {
            if (tempConfig != null) {
                try { Files.deleteIfExists(tempConfig); } catch (Exception ignored) {}
            }
        }
    }

    private ObjectNode toolResult(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        result.put("isError", isError);
        return result;
    }

    // -------------------------------------------------------------------------
    // Tool specifications (shared with the serve /v1 OpenAPI document)
    // -------------------------------------------------------------------------

    private void buildTools(ArrayNode tools) {
        for (CommandSchemas.Entry entry : CommandSchemas.all()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", entry.toolName());
            tool.put("description", entry.description());
            tool.set("inputSchema", entry.inputSchema());
        }
    }

    // -------------------------------------------------------------------------
    // JSON-RPC send helpers
    // -------------------------------------------------------------------------

    /** The rest of a JSON-RPC error line after the id, built before an out-of-memory error needs it. */
    private static final String OUT_OF_MEMORY_ERROR = ",\"error\":{\"code\":-32603,\"message\":"
            + "\"Out of memory; the CimPal MCP server stops (exit 3). Restart it with more memory (-Xmx, or the"
            + " container's memory limit); see docs/guide/sizing.md.\"}}";

    private void send(JsonNode id, JsonNode result) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        write(msg);
    }

    private void sendError(JsonNode id, int code, String message) throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        ObjectNode error = msg.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(msg);
    }

    private void write(ObjectNode msg) throws Exception {
        String json = mapper.writeValueAsString(msg);
        if (debug) System.err.println("[MCP â†’] " + json);
        synchronized (mcpOut) {
            mcpOut.println(json);
            mcpOut.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String statusLabel(int exitCode) {
        return switch (exitCode) {
            case 0 -> "OK";
            case 1 -> "VIOLATIONS";
            case 2 -> "INVALID_INPUT";
            default -> "ERROR";
        };
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
