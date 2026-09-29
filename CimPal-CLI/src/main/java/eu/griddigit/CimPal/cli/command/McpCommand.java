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
                "    \"args\":[\"-jar\",\"CimPal-CLI.jar\",\"mcp\"]}}}"
        },
        sortOptions = false
)
public class McpCommand implements Callable<Integer> {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME    = "CimPal";
    private static final String SERVER_VERSION = "2026.9";

    @Option(names = "--debug",
            description = "Write MCP message traffic to stderr for debugging.")
    private boolean debug;

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
        JsonNode args   = params.path("arguments");

        ToolSpec spec = findTool(toolName);
        if (spec == null) {
            sendError(id, -32602, "Unknown tool: " + toolName);
            return;
        }

        // Build a temp config file from the arguments JsonNode
        Path tempConfig = null;
        try {
            tempConfig = Files.createTempFile("cimpal-mcp-", ".json");
            mapper.writeValue(tempConfig.toFile(), args);

            // Capture System.out during command execution
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream captured = new PrintStream(baos, true, StandardCharsets.UTF_8);
            System.setOut(captured);

            int exitCode;
            try {
                // Always add --format json for commands that support it
                String[] cliArgs = spec.supportsJson
                        ? new String[]{spec.command, "--config", tempConfig.toString(), "--format", "json"}
                        : new String[]{spec.command, "--config", tempConfig.toString()};
                exitCode = new CommandLine(new CimPalCli()).execute(cliArgs);
            } finally {
                System.setOut(System.err); // restore to stderr
            }

            String output = baos.toString(StandardCharsets.UTF_8).strip();

            // Wrap non-JSON output in a minimal envelope
            if (output.isEmpty()) {
                output = "{\"exitCode\":" + exitCode + ",\"status\":\"" + statusLabel(exitCode) + "\"}";
            } else if (!output.startsWith("{") && !output.startsWith("[")) {
                output = "{\"exitCode\":" + exitCode + ",\"output\":" + jsonStr(output) + "}";
            }

            // MCP tool result
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode textNode = content.addObject();
            textNode.put("type", "text");
            textNode.put("text", output);
            result.put("isError", exitCode >= 2);

            send(id, result);

        } finally {
            if (tempConfig != null) {
                try { Files.deleteIfExists(tempConfig); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tool specifications
    // -------------------------------------------------------------------------

    private record ToolSpec(String name, String command, boolean supportsJson) {}

    private static ToolSpec findTool(String name) {
        for (ToolSpec s : ALL_TOOLS) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }

    private static final ToolSpec[] ALL_TOOLS = {
            new ToolSpec("validate",         "validate",         true),
            new ToolSpec("sparql",           "sparql",           true),
            new ToolSpec("compare",          "compare",          true),
            new ToolSpec("compare_instances","compare-instances", true),
            new ToolSpec("convert",          "convert",          false),
            new ToolSpec("rdfs_to_shacl",    "rdfs2shacl",       false),
            new ToolSpec("organize",         "organize",         false),
            new ToolSpec("excel_to_shacl",   "excel2shacl",      false),
            new ToolSpec("gen_instances",    "gen-instances",    false),
            new ToolSpec("manifest",         "manifest",         false),
    };

    private void buildTools(ArrayNode tools) {
        // --- validate ---
        addTool(tools, "validate",
                "Validate CGMES/CIM model files against SHACL constraint shapes. "
                + "Returns a JSON summary with totals (conforming/violations/errors) "
                + "and per-shape violation groups with sample focus nodes. "
                + "Use this as the primary diagnostic step in the validation loop.",
                obj -> {
obj.set("workflow", schema("string",
                            "Workflow: 'mapping' (default, uses a CSV mapping file), "
                            + "'timestamped' (groups by timestamp), 'manual' (hand-pick shapes)."));
obj.set("mappingCsv", schema("string",
                            "Absolute path to the CSV mapping file. "
                            + "Three columns: model file names | constraint .ttl path | label. "
                            + "Required for mapping and timestamped workflows."));
obj.set("modelsDir", schema("string",
                            "Absolute path to the root folder containing model ZIP/XML archives."));
obj.set("constraintsRoot", schema("string",
                            "Absolute path to the root folder for SHACL constraint .ttl files."));
obj.set("outputDir", schema("string",
                            "Absolute path to the output folder for Excel reports and ZIPs."));
obj.set("shaclConstraintFiles", arraySchema(
                            "Absolute paths to SHACL .ttl files (manual workflow only)."));
obj.set("datatypeMap", schema("string",
                            "CGMES version preset: 'CGMES30NC25' (default), 'CGMES30NC24', 'CGMES24NC22', "
                            + "or absolute path to a custom .properties file."));
obj.set("xmlBase", schema("string",
                            "Base URI for parsing model files. "
                            + "CGMES 3.0: 'http://iec.ch/TC57/CIM100' (default). "
                            + "CGMES 2.4: 'http://iec.ch/TC57/2013/CIM-schema-cim16'."));
obj.set("engine", schema("string",
                            "Validation engine: 'APACHE_JENA' (default, production-grade), "
                            + "'PYSHACL', 'PYSHACL_OXIGRAPH', 'RUST_SHACL' (experimental)."));
obj.set("workers", schema("integer",
                            "Parallel validation workers. 0 = auto-sized from available heap."));
obj.set("maxResultsPerConstraint", schema("integer",
                            "Max violations per shape (0 = unlimited). Use 10 for quick checks."));
obj.set("samples", schema("integer",
                            "Focus-node samples per shape group in JSON output (default 3). "
                            + "Set 0 to disable per-shape detail."));
obj.set("exportTurtle", schema("boolean",
                            "Write .ttl validation report files alongside Excel output."));
                },
                new String[0]);

        // --- sparql ---
        addTool(tools, "sparql",
                "Execute a SPARQL SELECT query against one or more CIM/RDF model files. "
                + "Use to inspect model data, count triples, or diagnose why a SHACL constraint fires.",
                obj -> {
obj.set("models", arraySchema(
                            "Absolute paths to model files (.xml, .rdf, .ttl, .zip)."));
obj.set("query", schema("string",
                            "SPARQL SELECT query string, or absolute path to a .sparql/.rq file."));
obj.set("xmlBase", schema("string",
                            "Base URI for parsing model files (default: http://iec.ch/TC57/CIM100)."));
obj.set("output", schema("string",
                            "Absolute path for output file (.xlsx or .csv). "
                            + "If omitted, results are returned inline."));
                },
                new String[]{"models", "query"});

        // --- compare ---
        addTool(tools, "compare",
                "Compare two RDF files (RDFS profiles or SHACL shapes) and report differences. "
                + "Use to audit what changed between profile versions or shape-set revisions.",
                obj -> {
obj.set("fileA", schema("string",
                            "Absolute path to the first (reference/before) file."));
obj.set("fileB", schema("string",
                            "Absolute path to the second (changed/after) file."));
obj.set("compareType", schema("string",
                            "Algorithm: 'rdfs' (augmented RDFS), 'shacl' (any RDF), "
                            + "'rdfs-cimtool', 'auto' (detect from extension, default)."));
obj.set("normalizeCimVersion", schema("boolean",
                            "Rename the 'cim' namespace in fileB to match fileA before comparing. "
                            + "Use when comparing across CIM version boundaries."));
obj.set("output", schema("string",
                            "Absolute path for the diff report (.xlsx or .csv)."));
                },
                new String[]{"fileA", "fileB"});

        // --- compare_instances ---
        addTool(tools, "compare_instances",
                "Compare two sets of CIM instance-data model files and report differences. "
                + "Use to verify a conversion or transformation preserved all data.",
                obj -> {
obj.set("modelsA", arraySchema(
                            "Absolute paths to the first (before/reference) model files."));
obj.set("modelsB", arraySchema(
                            "Absolute paths to the second (after/changed) model files."));
obj.set("xmlBase", schema("string",
                            "Base URI for parsing files (default: http://iec.ch/TC57/CIM100)."));
obj.set("ignoreSv", schema("boolean",
                            "Ignore SV (state-variable) profile differences."));
obj.set("ignoreTp", schema("boolean",
                            "Ignore TP (topology) differences."));
obj.set("ignoreDl", schema("boolean",
                            "Ignore DL (diagram layout) differences."));
obj.set("output", schema("string",
                            "Absolute path for output (.xlsx or .csv)."));
                },
                new String[]{"modelsA", "modelsB"});

        // --- convert ---
        addTool(tools, "convert",
                "Convert RDF model files between formats (RDF/XML, Turtle, JSON-LD). "
                + "Use to produce canonical Turtle for git-friendly diffs.",
                obj -> {
obj.set("input", schema("string",
                            "Absolute path to the source file."));
obj.set("inputFiles", arraySchema(
                            "Absolute paths to source files for model union (mutually exclusive with 'input')."));
obj.set("output", schema("string",
                            "Absolute path for the output file. Extension determines format: "
                            + ".ttl â†’ Turtle, .xml/.rdf â†’ RDF/XML, .jsonld â†’ JSON-LD."));
obj.set("xmlBase", schema("string",
                            "Base URI for parsing RDF/XML files."));
obj.set("sort", schema("boolean",
                            "Sort triples for deterministic, diffable output."));
obj.set("rdfFormat", schema("string",
                            "RDF/XML sub-format: RDFXML_PLAIN (default), RDFXML_ABBREV, CIMXML, RDFS_CIMXML."));
                },
                new String[]{"output"});

        // --- rdfs_to_shacl ---
        addTool(tools, "rdfs_to_shacl",
                "Generate SHACL shape files from RDFS CIM profile definitions. "
                + "Produces one .ttl shape file per input RDFS file.",
                obj -> {
obj.set("rdfsFiles", arraySchema(
                            "Absolute paths to RDFS profile .rdf files."));
obj.set("outputDir", schema("string",
                            "Absolute path to the output directory for generated .ttl files."));
obj.set("rdfsFormat", schema("string",
                            "RDFS format version: '2020' (default, cimsyntaxgen augmented) or '2019'."));
obj.set("closedShapes", schema("boolean",
                            "Generate sh:closed shapes (very strict â€” rejects undeclared properties)."));
obj.set("splitDatatypes", schema("boolean",
                            "Write a separate datatype constraint file alongside the main shapes."));
obj.set("validateShapes", schema("boolean",
                            "Run SHACL-SHACL validation on generated shapes and report results."));
                },
                new String[]{"rdfsFiles", "outputDir"});

        // --- organize ---
        addTool(tools, "organize",
                "Reorganize SHACL constraint files according to an Excel mapping template. "
                + "Splits constraints from input .ttl files into per-file outputs with canonical ordering.",
                obj -> {
obj.set("shaclFiles", arraySchema(
                            "Absolute paths to SHACL .ttl/.rdf files to organize."));
obj.set("templateXlsx", schema("string",
                            "Absolute path to the Excel template defining the output structure."));
obj.set("outputDir", schema("string",
                            "Absolute path to the root output directory."));
                },
                new String[]{"shaclFiles", "templateXlsx", "outputDir"});

        // --- excel_to_shacl ---
        addTool(tools, "excel_to_shacl",
                "Generate SHACL constraint shapes from an Excel spreadsheet and RDFS profile.",
                obj -> {
obj.set("rdfsFile", schema("string",
                            "Absolute path to the RDFS profile .rdf file."));
obj.set("excelFile", schema("string",
                            "Absolute path to the Excel .xlsx constraint definitions file."));
obj.set("output", schema("string",
                            "Absolute path for the generated .ttl shape file."));
obj.set("nsPrefix", schema("string", "Namespace prefix for generated shapes."));
obj.set("nsUri", schema("string", "Namespace URI for generated shapes."));
                },
                new String[]{"rdfsFile", "excelFile", "output"});

        // --- gen_instances ---
        addTool(tools, "gen_instances",
                "Generate CIM/RDF instance data from a CimPal Excel template. "
                + "Use to create synthetic pass/fail fixtures for shape testing.",
                obj -> {
obj.set("templateXlsx", schema("string",
                            "Absolute path to the CimPal Advanced template .xlsx file."));
obj.set("output", schema("string",
                            "Absolute path for the generated RDF/XML file."));
obj.set("xmlBase", schema("string",
                            "Base URI (default: http://iec.ch/TC57/CIM100)."));
obj.set("stripPrefixes", schema("boolean", "Remove unused namespace prefixes."));
                },
                new String[]{"templateXlsx", "output"});

        // --- manifest ---
        addTool(tools, "manifest",
                "Generate a DCAT/CGMES manifest Turtle file describing a set of model files.",
                obj -> {
obj.set("dir", schema("string",
                            "Absolute path to a folder containing the model files to describe."));
obj.set("files", arraySchema(
                            "Absolute paths to individual model files (alternative to 'dir')."));
obj.set("accessUrl", schema("string",
                            "The dcat:accessURL written into the manifest."));
obj.set("output", schema("string",
                            "Absolute path for the manifest .ttl file."));
                },
                new String[0]);
    }

    // -------------------------------------------------------------------------
    // Schema helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    interface PropertiesBuilder {
        void build(ObjectNode properties);
    }

    private void addTool(ArrayNode tools, String name, String description,
                         PropertiesBuilder builder, String[] required) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);

        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        builder.build(properties);

        if (required.length > 0) {
            ArrayNode req = schema.putArray("required");
            for (String r : required) req.add(r);
        }
    }

    private static ObjectNode schema(String type, String description) {
        ObjectMapper m = new ObjectMapper();
        ObjectNode n = m.createObjectNode();
        n.put("type", type);
        n.put("description", description);
        return n;
    }

    private static ObjectNode arraySchema(String description) {
        ObjectMapper m = new ObjectMapper();
        ObjectNode n = m.createObjectNode();
        n.put("type", "array");
        n.putObject("items").put("type", "string");
        n.put("description", description);
        return n;
    }

    // -------------------------------------------------------------------------
    // JSON-RPC send helpers
    // -------------------------------------------------------------------------

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
