/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.utils.CsvCells;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.SparqlTools;
import eu.griddigit.cimpal.core.utils.SparqlTools.QueryResults;
import org.apache.jena.rdf.model.Model;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code sparql} subcommand — execute a SPARQL SELECT query against one or more RDF model files.
 *
 * <p>Model files may be RDF/XML ({@code .xml}), Turtle ({@code .ttl}), or ZIP archives
 * containing RDF/XML files.  The query can be supplied as a file path or an inline string.
 *
 * <p>Results can be written to stdout (text table or JSON) or saved to an Excel file
 * ({@code .xlsx}) or CSV file.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — query executed successfully
 *   <li>2 — bad or missing input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "sparql",
        mixinStandardHelpOptions = true,
        description = "Execute a SPARQL SELECT query against RDF/CIM model files.",
        sortOptions = false
)
public class SparqlCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- input options -----------------------------------------------------

    @Option(names = "--models",
            description = "Comma-separated model files (RDF/XML, TTL, ZIP).",
            split = ",")
    private List<File> modelFiles;

    @Option(names = "--query",
            description = "SPARQL query: path to a .sparql/.rq file, or an inline query string.")
    private String query;

    @Option(names = "--xml-base",
            description = "Base URI for resolving relative URIs in model files (default: http://iec.ch/TC57/CIM100).")
    private String xmlBase;

    // ---- output options ---------------------------------------------------

    @Option(names = "--output",
            description = "Output file path. Use .xlsx for Excel, otherwise CSV.")
    private File outputFile;

    @Option(names = "--format",
            description = "Output format for stdout: text (default), json, or csv.")
    private String format;

    @Option(names = "--stats",
            description = "Report the run's resource use (wall/CPU time, peak heap, GC, triples loaded): "
                    + "a \"stats\" field in JSON output on stdout, otherwise a [STATS] line on stderr.")
    private Boolean stats;

    /** The collector when {@code --stats} is on, otherwise null. */
    private RunStats runStats;

    @Option(names = "--summary-file",
            description = "Also write the JSON result (the document --format json prints) to this file, "
                    + "atomically, creating parent folders.")
    private File summaryFile;

    @Option(names = "--violations-exit-code",
            description = "Accepted like on the other JSON commands (0..255); sparql never reports violations, "
                    + "so it never changes the exit code.")
    private Integer violationsExitCode;

    @Option(names = "--limit",
            description = "Warn if the query has no LIMIT and the model exceeds 100k triples; prepend LIMIT to the query (0 = no limit).")
    private int limit = 0;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            // 1. Load config if provided
            if (configFile != null) {
                loadConfig(configFile);
            }

            // 2. Apply defaults
            applyDefaults();
            runStats = StatsJson.startIf(stats);

            // 3. Validate inputs
            if (!validateInputs()) {
                return ExitCode.INVALID_INPUT;
            }

            // 4. Resolve and read the SPARQL query
            String queryText = resolveQuery(query);
            if (queryText == null) {
                System.err.println("[ERROR] Could not resolve query: " + query);
                return ExitCode.INVALID_INPUT;
            }

            // 5. Load model
            System.err.println("[INFO] Loading " + modelFiles.size() + " model file(s)...");
            Model model;
            try (StatsJson.Span ignored = StatsJson.phase(runStats, "load")) {
                model = ModelFactory.loadCombinedModelForSparql(modelFiles, xmlBase);
            }
            StatsJson.countLoaded(runStats, model.size(), modelFiles.stream().map(File::toPath).toList());
            System.err.println("[INFO] Model loaded: " + model.size() + " triples.");

            // 6. Warn and optionally cap if model is large and query has no LIMIT
            if (model.size() > 100_000 && !queryText.toUpperCase().contains("LIMIT")) {
                if (limit > 0) {
                    System.err.println("[WARN] Model has " + model.size()
                            + " triples and query has no LIMIT. Adding LIMIT " + limit + ".");
                    queryText = queryText.trim() + "\nLIMIT " + limit;
                } else {
                    System.err.println("[WARN] Model has " + model.size()
                            + " triples and query has no LIMIT. This may be slow.");
                }
            } else if (limit > 0 && !queryText.toUpperCase().contains("LIMIT")) {
                queryText = queryText.trim() + "\nLIMIT " + limit;
            }

            // 7. Execute query
            System.err.println("[INFO] Executing SPARQL query...");
            QueryResults results;
            try (StatsJson.Span ignored = StatsJson.phase(runStats, "query")) {
                results = SparqlTools.executeSparqlQuery(queryText, model);
            }
            System.err.println("[INFO] Query returned " + results.rows.size() + " row(s).");

            // 8. Output results. With a summary file the JSON document is built once, so the file
            //    and stdout carry the same document (and stats snapshot).
            String json = null;
            if (summaryFile != null) {
                StringWriter buffer = new StringWriter();
                writeJson(results, new PrintWriter(buffer), StatsJson.field(runStats, ""));
                json = buffer.toString().stripTrailing();
                if (!AutomationOptions.writeSummary(summaryFile, json)) {
                    return ExitCode.INTERNAL_ERROR;
                }
            }
            if (outputFile != null) {
                writeToFile(results);
                StatsJson.toStderr(runStats);
            } else {
                writeToStdout(results, json);
            }

            return ExitCode.OK;

        } catch (IllegalArgumentException ex) {
            // Refused query (e.g. SERVICE, not a SELECT) or empty model: bad input, not a crash.
            System.err.println("[ERROR] " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        } catch (Exception ex) {
            System.err.println("[ERROR] " + ex.getMessage());
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    // -------------------------------------------------------------------------
    // Config loading (Jackson 3 tree-model API)
    // -------------------------------------------------------------------------

    private void loadConfig(File config) {
        Path configDir = config.toPath().toAbsolutePath().getParent();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(config);
        } catch (Exception ex) {
            System.err.println("[WARN] Could not read config file: " + ex.getMessage());
            return;
        }

        // _comment is silently ignored
        if (modelFiles == null || modelFiles.isEmpty()) {
            JsonNode arr = root.path("models");
            if (arr.isArray() && arr.size() > 0) {
                modelFiles = new ArrayList<>();
                for (JsonNode element : arr) {
                    String v = element.asText(null);
                    if (v != null && !v.isBlank()) {
                        modelFiles.add(resolveRelative(configDir, v));
                    }
                }
            } else {
                String v = root.path("models").asText(null);
                if (v != null && !v.isBlank()) {
                    modelFiles = Arrays.stream(v.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .map(s -> resolveRelative(configDir, s))
                            .collect(Collectors.toList());
                }
            }
        }
        if (query == null) {
            String v = root.path("query").asText(null);
            if (v != null && !v.isBlank()) query = v;
        }
        if (xmlBase == null) {
            String v = root.path("xmlBase").asText(null);
            if (v != null && !v.isBlank()) xmlBase = v;
        }
        if (outputFile == null) {
            String v = root.path("output").asText(null);
            if (v != null && !v.isBlank()) outputFile = resolveRelative(configDir, v);
        }
        if (format == null) {
            String v = root.path("format").asText(null);
            if (v != null && !v.isBlank()) format = v;
        }
        if (stats == null) {
            JsonNode n = root.path("stats");
            if (!n.isMissingNode() && !n.isNull()) stats = n.asBoolean(false);
        }
        if (summaryFile == null) {
            String v = root.path("summaryFile").asText(null);
            if (v != null && !v.isBlank()) summaryFile = resolveRelative(configDir, v);
        }
        if (violationsExitCode == null) {
            JsonNode n = root.path("violationsExitCode");
            if (!n.isMissingNode() && !n.isNull()) violationsExitCode = AutomationOptions.exitCodeFromConfig(n);
        }
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        if (p.isAbsolute()) return p.toFile();
        return configDir.resolve(p).normalize().toFile();
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void applyDefaults() {
        if (xmlBase == null) xmlBase = "http://iec.ch/TC57/CIM100";
        if (format == null) format = "text";
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = AutomationOptions.checkViolationsExitCode(violationsExitCode);

        if (modelFiles == null || modelFiles.isEmpty()) {
            System.err.println("[ERROR] --models is required: specify one or more model files.");
            ok = false;
        } else {
            for (File f : modelFiles) {
                if (!f.exists() || !f.isFile()) {
                    System.err.println("[ERROR] Model file not found: " + f.getAbsolutePath());
                    ok = false;
                }
            }
        }

        if (query == null || query.isBlank()) {
            System.err.println("[ERROR] --query is required: specify a SPARQL file path or inline query.");
            ok = false;
        }

        return ok;
    }

    // -------------------------------------------------------------------------
    // Query resolution
    // -------------------------------------------------------------------------

    private static String resolveQuery(String queryArg) throws IOException {
        if (queryArg == null) return null;
        Path candidate;
        try {
            candidate = Paths.get(queryArg);
        } catch (java.nio.file.InvalidPathException e) {
            // Query text such as "SELECT ?s ..." is not a valid path on Windows ('?', '*'):
            // it is an inline query, not an error.
            return queryArg;
        }
        if (Files.isRegularFile(candidate)) {
            return Files.readString(candidate, StandardCharsets.UTF_8);
        }
        // Treat as an inline query string
        return queryArg;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private void writeToFile(QueryResults results) throws Exception {
        String name = outputFile.getName().toLowerCase();
        if (name.endsWith(".xlsx")) {
            SparqlTools.exportResultsToExcel(results, outputFile);
            System.err.println("[INFO] Results written to Excel: " + outputFile.getAbsolutePath());
        } else {
            // CSV output
            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8))) {
                writeCsv(results, pw);
            }
            System.err.println("[INFO] Results written to CSV: " + outputFile.getAbsolutePath());
        }
    }

    /** {@code json} is the already built JSON document (with a summary file), or null. */
    private void writeToStdout(QueryResults results, String json) {
        switch (format.toLowerCase()) {
            case "json" -> {
                if (json != null) {
                    System.out.println(json);
                } else {
                    writeJson(results, new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                            StatsJson.field(runStats, ""));
                }
            }
            case "csv"  -> {
                writeCsv(results, new PrintWriter(System.out, true, StandardCharsets.UTF_8));
                StatsJson.toStderr(runStats);
            }
            default     -> {
                writeText(results, new PrintWriter(System.out, true, StandardCharsets.UTF_8));
                StatsJson.toStderr(runStats);
            }
        }
    }

    // ---- text (pipe-separated table) ---------------------------------------

    private static void writeText(QueryResults results, PrintWriter out) {
        if (results.columns.isEmpty()) {
            out.println("(no columns returned)");
            return;
        }
        // Header
        out.println(String.join(" | ", results.columns));
        // Separator
        int sepLen = results.columns.stream().mapToInt(String::length).sum()
                + (results.columns.size() - 1) * 3;
        out.println("-".repeat(sepLen));
        // Data rows
        for (Map<String, String> row : results.rows) {
            List<String> cells = results.columns.stream()
                    .map(col -> nvl(row.get(col)))
                    .collect(Collectors.toList());
            out.println(String.join(" | ", cells));
        }
        out.flush();
    }

    // ---- JSON --------------------------------------------------------------

    /** {@code statsField} is {@code ,"stats":{...}} or empty (see {@link StatsJson#field}). */
    private static void writeJson(QueryResults results, PrintWriter out, String statsField) {
        out.print("{\"columns\":[");
        for (int i = 0; i < results.columns.size(); i++) {
            out.print(jsonStr(results.columns.get(i)));
            if (i < results.columns.size() - 1) out.print(",");
        }
        out.print("],\"rows\":[");
        for (int r = 0; r < results.rows.size(); r++) {
            Map<String, String> row = results.rows.get(r);
            out.print("{");
            List<String> cols = results.columns;
            for (int c = 0; c < cols.size(); c++) {
                out.print(jsonStr(cols.get(c)));
                out.print(":");
                out.print(jsonStr(nvl(row.get(cols.get(c)))));
                if (c < cols.size() - 1) out.print(",");
            }
            out.print("}");
            if (r < results.rows.size() - 1) out.print(",");
        }
        out.print("]");
        out.print(statsField);
        out.println("}");
        out.flush();
    }

    // ---- CSV (RFC 4180) ----------------------------------------------------

    private static void writeCsv(QueryResults results, PrintWriter out) {
        // Header
        out.println(results.columns.stream()
                .map(SparqlCommand::csvEscape)
                .collect(Collectors.joining(",")));
        // Data rows
        for (Map<String, String> row : results.rows) {
            out.println(results.columns.stream()
                    .map(col -> csvEscape(nvl(row.get(col))))
                    .collect(Collectors.joining(",")));
        }
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    /** RFC-4180 quoting with formula neutralisation (SEC-5, finding 4); see {@link CsvCells#escape}. */
    private static String csvEscape(String value) {
        return CsvCells.escape(value);
    }
}
