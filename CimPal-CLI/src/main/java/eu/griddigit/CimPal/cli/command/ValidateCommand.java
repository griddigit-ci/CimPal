/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.shacl_tools.ShaclAutoTester;
import eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import eu.griddigit.cimpal.core.utils.ValidationTools.ValidationRunSummary;
import eu.griddigit.cimpal.core.utils.ValidationTools.ValidationTimestampedRunSummary;
import org.apache.jena.datatypes.RDFDatatype;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

/**
 * {@code validate} subcommand — run SHACL validation against RDF/CIM model files.
 *
 * <p>Supports three workflows:
 * <ul>
 *   <li>{@code mapping}     — validate files according to a CSV mapping (default)
 *   <li>{@code timestamped} — like mapping, but groups files by timestamp and produces per-timestamp reports
 *   <li>{@code manual}      — validate all model files in a directory against hand-picked SHACL shapes
 * </ul>
 *
 * <p>All flags can be supplied via a JSON config file ({@code --config}); individual flags on the
 * command line override the values from the file.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — no violations
 *   <li>1 — validation completed; violations found
 *   <li>2 — bad or missing input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Run SHACL validation against CIM/RDF model files.",
        sortOptions = false
)
public class ValidateCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- workflow ----------------------------------------------------------

    @Option(names = "--workflow",
            description = "Workflow: mapping (default), timestamped, or manual.")
    private String workflow;

    // ---- input paths -------------------------------------------------------

    @Option(names = "--mapping-csv",
            description = "CSV mapping file (required for mapping/timestamped workflows).")
    private File mappingCsv;

    @Option(names = "--models",
            description = "Models root folder (required for all workflows).")
    private File modelsDir;

    @Option(names = "--constraints-root",
            description = "Constraints root folder (required for mapping/timestamped workflows).")
    private File constraintsRoot;

    @Option(names = "--output",
            description = "Output folder (required for mapping/timestamped workflows).")
    private File outputDir;

    @Option(names = "--shacl-files",
            description = "Comma-separated SHACL .ttl files (required for manual workflow).",
            split = ",")
    private List<File> shaclFiles;

    // ---- datatype / RDF options -------------------------------------------

    @Option(names = "--datatype-map",
            description = "Datatype map preset (CGMES30NC25, CGMES30NC24, CGMES24NC22) or path to a .properties file.")
    private String datatypeMap;

    @Option(names = "--xml-base",
            description = "Base URI for resolving relative URIs in model files.")
    private String xmlBase;

    @Option(names = "--engine",
            description = "Validation engine: APACHE_JENA (default), PYSHACL, PYSHACL_OXIGRAPH, RUST_SHACL.")
    private String engine;

    // ---- performance / sampling -------------------------------------------

    @Option(names = "--workers",
            description = "Number of worker threads (0 = auto).")
    private Integer workers;

    @Option(names = "--max-results",
            description = "Maximum SHACL results per constraint (0 = unlimited).")
    private Integer maxResults;

    // ---- output options ---------------------------------------------------

    @Option(names = "--format",
            description = "Output format: text (default) or json.")
    private String format;

    @Option(names = "--export-turtle",
            description = "Also write .ttl validation report files alongside the Excel output.")
    private Boolean exportTurtle;

    @Option(names = "--samples",
            description = "When --format json: max focus-node samples per shape group in the JSON output. "
                    + "Default 3. Set 0 to disable per-shape detail. "
                    + "Enables --export-turtle automatically when positive.")
    private Integer samples;

    @Option(names = "--previous-comparison",
            description = "XLSX from a previous timestamped run, used to build a delta comparison sheet.")
    private File previousComparison;

    @Option(names = "--dry-run",
            description = "Print the resolved configuration and exit without running validation.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            // 1. Load config file if provided, filling in any flag still null
            if (configFile != null) {
                loadConfig(configFile);
            }

            // 2. Apply defaults for anything still unset
            applyDefaults();

            // 3. Dry-run: print resolved config and exit
            if (dryRun) {
                printDryRun();
                return ExitCode.OK;
            }

            // 4. Validate inputs
            if (!validateInputs()) {
                return ExitCode.INVALID_INPUT;
            }

            // 5. Load datatype map
            Map<String, RDFDatatype> dataTypeMap = loadDatatypeMap();

            // 6. Configure turtle export on the shared ValidationTools flag
            ValidationTools.setExportTurtleValidationReports(Boolean.TRUE.equals(exportTurtle));

            // 7. Resolve validation engine
            ValidationEngine validationEngine = resolveEngine();

            // 8. Execute workflow
            return executeWorkflow(dataTypeMap, validationEngine);

        } catch (Exception ex) {
            System.err.println("[ERROR] Unexpected error: " + ex.getMessage());
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
        if (workflow == null) {
            String v = root.path("workflow").asText(null);
            if (v != null && !v.isBlank()) workflow = v;
        }
        if (mappingCsv == null) {
            String v = root.path("mappingCsv").asText(null);
            if (v != null && !v.isBlank()) mappingCsv = resolveRelative(configDir, v);
        }
        if (modelsDir == null) {
            String v = root.path("modelsDir").asText(null);
            if (v != null && !v.isBlank()) modelsDir = resolveRelative(configDir, v);
        }
        if (constraintsRoot == null) {
            String v = root.path("constraintsRoot").asText(null);
            if (v != null && !v.isBlank()) constraintsRoot = resolveRelative(configDir, v);
        }
        if (outputDir == null) {
            String v = root.path("outputDir").asText(null);
            if (v != null && !v.isBlank()) outputDir = resolveRelative(configDir, v);
        }
        if (shaclFiles == null || shaclFiles.isEmpty()) {
            JsonNode arr = root.path("shaclConstraintFiles");
            if (arr.isArray() && arr.size() > 0) {
                shaclFiles = new ArrayList<>();
                for (JsonNode element : arr) {
                    String v = element.asText(null);
                    if (v != null && !v.isBlank()) shaclFiles.add(resolveRelative(configDir, v));
                }
            }
        }
        if (datatypeMap == null) {
            String v = root.path("datatypeMap").asText(null);
            if (v != null && !v.isBlank()) datatypeMap = v;
        }
        if (xmlBase == null) {
            String v = root.path("xmlBase").asText(null);
            if (v != null && !v.isBlank()) xmlBase = v;
        }
        if (engine == null) {
            String v = root.path("engine").asText(null);
            if (v != null && !v.isBlank()) engine = v;
        }
        if (workers == null) {
            JsonNode n = root.path("workers");
            if (!n.isMissingNode() && !n.isNull()) workers = n.asInt(0);
        }
        if (maxResults == null) {
            JsonNode n = root.path("maxResultsPerConstraint");
            if (!n.isMissingNode() && !n.isNull()) maxResults = n.asInt(0);
        }
        if (format == null) {
            String v = root.path("format").asText(null);
            if (v != null && !v.isBlank()) format = v;
        }
        if (exportTurtle == null) {
            JsonNode n = root.path("exportTurtle");
            if (!n.isMissingNode() && !n.isNull()) exportTurtle = n.asBoolean(false);
        }
        if (samples == null) {
            JsonNode n = root.path("samples");
            if (!n.isMissingNode() && !n.isNull()) samples = n.asInt(3);
        }
        if (previousComparison == null) {
            String v = root.path("previousComparison").asText(null);
            if (v != null && !v.isBlank()) previousComparison = resolveRelative(configDir, v);
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
        if (workflow == null) workflow = "mapping";
        if (datatypeMap == null) datatypeMap = "CGMES30NC25";
        if (xmlBase == null) xmlBase = "http://iec.ch/TC57/CIM100";
        if (engine == null) engine = "APACHE_JENA";
        if (workers == null) workers = 0;
        if (maxResults == null) maxResults = 0;
        if (format == null) format = "text";
        if (exportTurtle == null) exportTurtle = false;
        // Default samples to 3 for JSON mode (per-shape detail); 0 disables it
        if (samples == null) samples = "json".equalsIgnoreCase(format) ? 3 : 0;
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = true;

        switch (workflow) {
            case "mapping" -> {
                ok &= requireFile(mappingCsv, "--mapping-csv");
                ok &= requireDir(modelsDir, "--models");
                ok &= requireDir(constraintsRoot, "--constraints-root");
                ok &= requireOutputDir(outputDir, "--output");
            }
            case "timestamped" -> {
                ok &= requireFile(mappingCsv, "--mapping-csv");
                ok &= requireExists(modelsDir, "--models");
                ok &= requireDir(constraintsRoot, "--constraints-root");
                ok &= requireOutputDir(outputDir, "--output");
            }
            case "manual" -> {
                ok &= requireDir(modelsDir, "--models");
                if (shaclFiles == null || shaclFiles.isEmpty()) {
                    System.err.println("[ERROR] --shacl-files is required for the manual workflow.");
                    ok = false;
                } else {
                    for (File f : shaclFiles) {
                        if (!f.exists() || !f.isFile()) {
                            System.err.println("[ERROR] SHACL file not found: " + f.getAbsolutePath());
                            ok = false;
                        }
                    }
                }
            }
            default -> {
                System.err.println("[ERROR] Unknown workflow: " + workflow
                        + ". Valid values: mapping, timestamped, manual.");
                ok = false;
            }
        }

        if (previousComparison != null && !previousComparison.exists()) {
            System.err.println("[ERROR] Previous comparison file not found: "
                    + previousComparison.getAbsolutePath());
            ok = false;
        }

        return ok;
    }

    private static boolean requireFile(File f, String flag) {
        if (f == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        if (!f.exists() || !f.isFile()) {
            System.err.println("[ERROR] File not found for " + flag + ": " + f.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static boolean requireDir(File d, String flag) {
        if (d == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        if (!d.exists() || !d.isDirectory()) {
            System.err.println("[ERROR] Directory not found for " + flag + ": " + d.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static boolean requireExists(File f, String flag) {
        if (f == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        if (!f.exists()) {
            System.err.println("[ERROR] Path not found for " + flag + ": " + f.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static boolean requireOutputDir(File d, String flag) {
        if (d == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        // output dir may not yet exist — we create it during validation
        return true;
    }

    // -------------------------------------------------------------------------
    // Datatype map
    // -------------------------------------------------------------------------

    private Map<String, RDFDatatype> loadDatatypeMap() throws IOException {
        return switch (datatypeMap) {
            case "CGMES30NC25" ->
                    CompleteDatatypeMapLoader.loadFromResource(
                            "/CompleteDatatypeMap_CIM17_CGMES3_NC25.properties");
            case "CGMES30NC24" ->
                    CompleteDatatypeMapLoader.loadFromResource(
                            "/CompleteDatatypeMap_CIM17_CGMES3_NC24.properties");
            case "CGMES24NC22" ->
                    CompleteDatatypeMapLoader.loadFromResource(
                            "/CompleteDatatypeMap_CIM16_CGMES24_NC22.properties");
            default -> CompleteDatatypeMapLoader.loadFromFile(Paths.get(datatypeMap));
        };
    }

    // -------------------------------------------------------------------------
    // Engine resolution
    // -------------------------------------------------------------------------

    private ValidationEngine resolveEngine() {
        return switch (engine.toUpperCase()) {
            case "PYSHACL" -> ValidationEngine.PYSHACL;
            case "PYSHACL_OXIGRAPH" -> ValidationEngine.PYSHACL_OXIGRAPH;
            case "RUST_SHACL" -> ValidationEngine.RUST_SHACL;
            default -> ValidationEngine.APACHE_JENA;
        };
    }

    // -------------------------------------------------------------------------
    // Workflow execution
    // -------------------------------------------------------------------------

    private int executeWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                ValidationEngine validationEngine) throws Exception {
        boolean jsonOutput = "json".equalsIgnoreCase(format);

        // For JSON mode: redirect System.out → System.err so validation progress
        // messages from ValidationTools do not pollute the JSON output on stdout.
        PrintStream origOut = System.out;
        if (jsonOutput) {
            System.setOut(System.err);
        }

        try {
            return switch (workflow) {
                case "mapping"     -> runMappingWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
                case "timestamped" -> runTimestampedWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
                case "manual"      -> runManualWorkflow(dataTypeMap);
                default            -> ExitCode.INVALID_INPUT;
            };
        } finally {
            if (jsonOutput) {
                System.setOut(origOut);
            }
        }
    }

    // ---- mapping workflow ---------------------------------------------------

    private int runMappingWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                   ValidationEngine validationEngine,
                                   boolean jsonOutput,
                                   PrintStream origOut) throws Exception {

        // Per-shape detail requires TTL reports to be written so we can read them back.
        boolean needShapeDetail = jsonOutput && samples > 0;
        boolean autoTurtle = needShapeDetail && !Boolean.TRUE.equals(exportTurtle);
        if (autoTurtle) ValidationTools.setExportTurtleValidationReports(true);

        ValidationRunSummary summary = ValidationTools.validateByMapping(
                mappingCsv.toPath(),
                modelsDir.toPath(),
                constraintsRoot.toPath(),
                outputDir.toPath(),
                workers,
                dataTypeMap,
                xmlBase,
                maxResults,
                validationEngine
        );

        if (autoTurtle) ValidationTools.setExportTurtleValidationReports(false);

        List<ShapeGroup> shapeGroups = List.of();
        if (needShapeDetail && outputDir != null && outputDir.exists()) {
            shapeGroups = extractShapeGroups(outputDir.toPath(), samples);
        }

        if (jsonOutput) {
            System.setOut(origOut);
            System.out.println(buildMappingJson(summary, shapeGroups));
        } else {
            printTextSummary(summary);
        }

        return summary.hasViolations() ? ExitCode.VIOLATIONS : ExitCode.OK;
    }

    // ---- timestamped workflow -----------------------------------------------

    private int runTimestampedWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                       ValidationEngine validationEngine,
                                       boolean jsonOutput,
                                       PrintStream origOut) throws Exception {
        Path prevPath = (previousComparison != null) ? previousComparison.toPath() : null;

        ValidationTimestampedRunSummary summary = ValidationTools.validateByTimestampedMapping(
                mappingCsv.toPath(),
                modelsDir.toPath(),
                constraintsRoot.toPath(),
                outputDir.toPath(),
                workers,
                dataTypeMap,
                xmlBase,
                prevPath,
                maxResults,
                validationEngine
        );

        if (jsonOutput) {
            System.setOut(origOut);
            System.out.println(buildTimestampedJson(summary));
        } else {
            printTextSummaryTimestamped(summary);
        }

        return summary.hasViolations() ? ExitCode.VIOLATIONS : ExitCode.OK;
    }

    // ---- manual workflow ----------------------------------------------------

    private int runManualWorkflow(Map<String, RDFDatatype> dataTypeMap) throws Exception {
        // Collect all model files (XML, ZIP) from modelsDir recursively
        List<File> modelFiles = collectModelFiles(modelsDir);
        if (modelFiles.isEmpty()) {
            System.err.println("[WARN] No model files found in: " + modelsDir.getAbsolutePath());
        }

        ShaclAutoTesterCallback callback = new ShaclAutoTesterCallback() {
            @Override
            public void updateProgress(double progress) {
                System.err.printf("[PROGRESS] %.0f%%%n", progress * 100.0);
            }

            @Override
            public void appendOutput(String message) {
                System.err.print(message);
            }
        };

        ShaclAutoTester tester = new ShaclAutoTester(callback);
        tester.setDatatypeMapping(dataTypeMap, xmlBase);
        tester.setValidationOptions(workers > 0 ? workers : Runtime.getRuntime().availableProcessors(), maxResults);

        tester.runTestsInternal(
                shaclFiles,
                modelsDir,
                modelFiles,
                false,
                Boolean.TRUE.equals(exportTurtle)
        );

        // Manual workflow does not produce a structured summary; return OK
        return ExitCode.OK;
    }

    private static List<File> collectModelFiles(File dir) throws IOException {
        List<File> result = new ArrayList<>();
        try (var stream = Files.walk(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".xml") || name.endsWith(".rdf")
                              || name.endsWith(".ttl") || name.endsWith(".zip");
                  })
                  .map(Path::toFile)
                  .forEach(result::add);
        }
        result.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
        return result;
    }

    // -------------------------------------------------------------------------
    // Text output
    // -------------------------------------------------------------------------

    private void printTextSummary(ValidationRunSummary summary) {
        System.out.println();
        System.out.println("=== Validation Summary ===");
        System.out.println("  Conforming : " + summary.conforming());
        System.out.println("  Violations : " + summary.violations());
        System.out.println("  Errors     : " + summary.errors());
        System.out.println("  Total rows : " + summary.totalRows());
        System.out.println("  Report     : " + summary.reportPath().toAbsolutePath());
        if (summary.hasViolations()) {
            System.out.println("  Result     : VIOLATIONS FOUND");
        } else {
            System.out.println("  Result     : ALL CONFORMING");
        }
    }

    private void printTextSummaryTimestamped(ValidationTimestampedRunSummary summary) {
        System.out.println();
        System.out.println("=== Timestamped Validation Summary ===");
        System.out.println("  Conforming : " + summary.conforming());
        System.out.println("  Violations : " + summary.violations());
        System.out.println("  Errors     : " + summary.errors());
        System.out.println("  Total rows : " + summary.totalRows());
        System.out.println("  Reports    :");
        for (Path p : summary.reports()) {
            System.out.println("    " + p.toAbsolutePath());
        }
        if (summary.hasViolations()) {
            System.out.println("  Result     : VIOLATIONS FOUND");
        } else {
            System.out.println("  Result     : ALL CONFORMING");
        }
    }

    // -------------------------------------------------------------------------
    // JSON output (mapping workflow)
    // -------------------------------------------------------------------------

    private String buildMappingJson(ValidationRunSummary summary, List<ShapeGroup> shapeGroups) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"cimpal-validate-summary/1\",\n");
        sb.append("  \"run\": {\n");
        sb.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("    \"workflow\": \"mapping\",\n");
        sb.append("    \"inputs\": {\n");
        sb.append("      \"mappingCsv\": ").append(jsonStr(abs(mappingCsv))).append(",\n");
        sb.append("      \"modelsDir\": ").append(jsonStr(abs(modelsDir))).append(",\n");
        sb.append("      \"constraintsRoot\": ").append(jsonStr(abs(constraintsRoot))).append(",\n");
        sb.append("      \"outputDir\": ").append(jsonStr(abs(outputDir))).append("\n");
        sb.append("    },\n");
        sb.append("    \"options\": ").append(buildOptionsJson()).append("\n");
        sb.append("  },\n");
        sb.append("  \"totals\": {\n");
        sb.append("    \"conforming\": ").append(summary.conforming()).append(",\n");
        sb.append("    \"violations\": ").append(summary.violations()).append(",\n");
        sb.append("    \"errors\": ").append(summary.errors()).append(",\n");
        sb.append("    \"total\": ").append(summary.totalRows()).append("\n");
        sb.append("  },\n");
        sb.append("  \"hasViolations\": ").append(summary.hasViolations()).append(",\n");
        if (!shapeGroups.isEmpty()) {
            sb.append("  \"shapes\": ").append(buildShapeGroupsJson(shapeGroups)).append(",\n");
        }
        sb.append("  \"report\": ").append(jsonStr(summary.reportPath().toAbsolutePath().toString())).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildTimestampedJson(ValidationTimestampedRunSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"cimpal-validate-summary/1\",\n");
        sb.append("  \"run\": {\n");
        sb.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("    \"workflow\": \"timestamped\",\n");
        sb.append("    \"inputs\": {\n");
        sb.append("      \"mappingCsv\": ").append(jsonStr(abs(mappingCsv))).append(",\n");
        sb.append("      \"modelsDir\": ").append(jsonStr(abs(modelsDir))).append(",\n");
        sb.append("      \"constraintsRoot\": ").append(jsonStr(abs(constraintsRoot))).append(",\n");
        sb.append("      \"outputDir\": ").append(jsonStr(abs(outputDir))).append("\n");
        sb.append("    },\n");
        sb.append("    \"options\": ").append(buildOptionsJson()).append("\n");
        sb.append("  },\n");
        sb.append("  \"totals\": {\n");
        sb.append("    \"conforming\": ").append(summary.conforming()).append(",\n");
        sb.append("    \"violations\": ").append(summary.violations()).append(",\n");
        sb.append("    \"errors\": ").append(summary.errors()).append(",\n");
        sb.append("    \"total\": ").append(summary.totalRows()).append("\n");
        sb.append("  },\n");
        sb.append("  \"hasViolations\": ").append(summary.hasViolations()).append(",\n");
        sb.append("  \"reports\": [");
        List<Path> reports = summary.reports();
        for (int i = 0; i < reports.size(); i++) {
            sb.append(jsonStr(reports.get(i).toAbsolutePath().toString()));
            if (i < reports.size() - 1) sb.append(", ");
        }
        sb.append("]\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildOptionsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("      \"datatypeMap\": ").append(jsonStr(datatypeMap)).append(",\n");
        sb.append("      \"xmlBase\": ").append(jsonStr(xmlBase)).append(",\n");
        sb.append("      \"engine\": ").append(jsonStr(engine)).append(",\n");
        sb.append("      \"workers\": ").append(workers).append(",\n");
        sb.append("      \"maxResultsPerConstraint\": ").append(maxResults).append("\n");
        sb.append("    }");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Dry-run output
    // -------------------------------------------------------------------------

    private void printDryRun() {
        System.out.println("=== CimPal Validate -- Dry Run (no validation executed) ===");
        System.out.println("  workflow         : " + workflow);
        System.out.println("  mappingCsv       : " + abs(mappingCsv));
        System.out.println("  modelsDir        : " + abs(modelsDir));
        System.out.println("  constraintsRoot  : " + abs(constraintsRoot));
        System.out.println("  outputDir        : " + abs(outputDir));
        if (shaclFiles != null && !shaclFiles.isEmpty()) {
            System.out.println("  shaclFiles       : "
                    + shaclFiles.stream().map(File::getAbsolutePath)
                                .collect(Collectors.joining(", ")));
        }
        System.out.println("  datatypeMap      : " + datatypeMap);
        System.out.println("  xmlBase          : " + xmlBase);
        System.out.println("  engine           : " + engine);
        System.out.println("  workers          : " + workers);
        System.out.println("  maxResults       : " + maxResults);
        System.out.println("  format           : " + format);
        System.out.println("  samples          : " + samples);
        System.out.println("  exportTurtle     : " + exportTurtle);
        System.out.println("  previousCompar.  : " + abs(previousComparison));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String abs(File f) {
        return f == null ? "(not set)" : f.getAbsolutePath();
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // -------------------------------------------------------------------------
    // Per-shape detail (Phase 7)
    // -------------------------------------------------------------------------

    /** One shape + constraint combination that fired, with a bounded sample of focus nodes. */
    private record ShapeGroup(
            String shapeId,
            String constraint,
            String path,
            int count,
            List<String> sampleFocusNodes) {}

    /**
     * Reads {@code *__report.ttl} files written by ValidationTools to {@code outputDir} and
     * aggregates per-shape violation groups.  All values in these files are stored as plain
     * string literals (that is how {@code saveValidationReportTurtle} writes them), so the
     * Jena model API is used directly rather than SPARQL.
     */
    private static List<ShapeGroup> extractShapeGroups(Path outputDir, int maxSamples) {
        if (!java.nio.file.Files.isDirectory(outputDir)) return List.of();

        String SH = "http://www.w3.org/ns/shacl#";
        org.apache.jena.rdf.model.Property typeProp      = org.apache.jena.rdf.model.ResourceFactory.createProperty(org.apache.jena.vocabulary.RDF.getURI() + "type");
        org.apache.jena.rdf.model.Resource resultClass   = org.apache.jena.rdf.model.ResourceFactory.createResource(SH + "ValidationResult");
        org.apache.jena.rdf.model.Property shapeProp     = org.apache.jena.rdf.model.ResourceFactory.createProperty(SH + "sourceShape");
        org.apache.jena.rdf.model.Property constraintProp= org.apache.jena.rdf.model.ResourceFactory.createProperty(SH + "sourceConstraintComponent");
        org.apache.jena.rdf.model.Property pathProp      = org.apache.jena.rdf.model.ResourceFactory.createProperty(SH + "resultPath");
        org.apache.jena.rdf.model.Property focusProp     = org.apache.jena.rdf.model.ResourceFactory.createProperty(SH + "focusNode");

        // key → (count, path, sampleFocusNodes)
        java.util.LinkedHashMap<String, int[]> counts       = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> paths       = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, List<String>> nodes = new java.util.LinkedHashMap<>();

        try (var stream = java.nio.file.Files.list(outputDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith("__report.ttl"))
                  .sorted()
                  .forEach(ttlFile -> {
                      org.apache.jena.rdf.model.Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                      try {
                          org.apache.jena.riot.RDFDataMgr.read(m, ttlFile.toString(), org.apache.jena.riot.Lang.TURTLE);
                      } catch (Exception ignored) { return; }

                      for (var i = m.listSubjectsWithProperty(typeProp, resultClass); i.hasNext(); ) {
                          var res = i.next();
                          String shape = literal(res, shapeProp);
                          String constraint = literal(res, constraintProp);
                          if (shape == null || constraint == null) continue;

                          String key = shape + "||" + constraint;
                          counts.merge(key, new int[]{1}, (a, b) -> { a[0]++; return a; });
                          if (!paths.containsKey(key)) paths.put(key, literal(res, pathProp));

                          List<String> focusList = nodes.computeIfAbsent(key, k -> new ArrayList<>());
                          if (focusList.size() < maxSamples) {
                              String fn = literal(res, focusProp);
                              if (fn != null && !focusList.contains(fn)) focusList.add(fn);
                          }
                      }
                  });
        } catch (Exception ignored) {}

        // Build sorted list (highest count first)
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                .map(e -> {
                    String key = e.getKey();
                    String[] parts = key.split("\\|\\|", 2);
                    String shape = parts[0];
                    String constraint = abbreviateUri(parts.length > 1 ? parts[1] : "");
                    String p = abbreviateUri(paths.getOrDefault(key, ""));
                    List<String> fn = nodes.getOrDefault(key, List.of());
                    return new ShapeGroup(shape, constraint, p, e.getValue()[0], fn);
                })
                .collect(Collectors.toList());
    }

    private static String literal(org.apache.jena.rdf.model.Resource res,
                                   org.apache.jena.rdf.model.Property prop) {
        var stmt = res.getProperty(prop);
        return (stmt != null && stmt.getObject().isLiteral()) ? stmt.getString() : null;
    }

    /** Shortens well-known SHACL/XSD/RDF URIs to their prefixed form for readability. */
    private static String abbreviateUri(String uri) {
        if (uri == null) return "";
        return uri.replace("http://www.w3.org/ns/shacl#", "sh:")
                  .replace("http://www.w3.org/2001/XMLSchema#", "xsd:")
                  .replace("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:")
                  .replace("http://www.w3.org/2000/01/rdf-schema#", "rdfs:");
    }

    private static String buildShapeGroupsJson(List<ShapeGroup> groups) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < groups.size(); i++) {
            ShapeGroup g = groups.get(i);
            sb.append("    {\n");
            sb.append("      \"shapeId\": ").append(jsonStr(g.shapeId())).append(",\n");
            sb.append("      \"constraint\": ").append(jsonStr(g.constraint())).append(",\n");
            sb.append("      \"path\": ").append(jsonStr(g.path())).append(",\n");
            sb.append("      \"count\": ").append(g.count()).append(",\n");
            sb.append("      \"sampleFocusNodes\": [");
            List<String> fn = g.sampleFocusNodes();
            for (int j = 0; j < fn.size(); j++) {
                sb.append(jsonStr(fn.get(j)));
                if (j < fn.size() - 1) sb.append(", ");
            }
            sb.append("]\n");
            sb.append("    }");
            if (i < groups.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]");
        return sb.toString();
    }
}
