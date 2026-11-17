/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationOptions;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.MappingValidator;
import eu.griddigit.cimpal.core.utils.OutOfMemoryRethrow;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.ProfileValidator;
import eu.griddigit.cimpal.core.profiles.ProfileGroup;
import eu.griddigit.cimpal.core.profiles.ProfileGroupPlanner;
import eu.griddigit.cimpal.core.utils.SHACLValidator;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.shared.JenaException;
import picocli.CommandLine;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 *   <li>{@code combined}    — merge the given data files into one dataset and validate it against all the
 *                             given constraint files at once: the GUI's "Validate selected files together"
 *   <li>{@code profiles}    — validate the documents of a folder against the shapes their declared profiles
 *                             name in a DX-PROF profile library, in groups, with cardinality and closed-shape
 *                             constraints evaluated per document ({@link ProfileValidator})
 * </ul>
 *
 * <p>The former {@code manual} workflow tested SHACL rules against Conform / NonConform models
 * rather than validating datasets. It is no longer a CLI workflow: that is the GUI's rule test,
 * SHACL ▸ Constraints Operations ▸ Test SHACL rules.
 *
 * <p>All flags can be supplied via a JSON config file ({@code --config}); individual flags on the
 * command line override the values from the file.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — no violations
 *   <li>1 — validation completed; violations found. Also, never remapped by
 *       {@code --violations-exit-code}, a profiles run that left something unchecked
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

    private static final String WORKFLOWS = "mapping, timestamped, combined, profiles";

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- workflow ----------------------------------------------------------

    @Option(names = "--workflow",
            description = "Workflow: mapping (default), timestamped, combined or profiles.")
    private String workflow;

    // ---- input paths -------------------------------------------------------

    @Option(names = "--mapping-csv",
            description = "CSV mapping file (required for mapping/timestamped workflows).")
    private File mappingCsv;

    @Option(names = "--models",
            description = "Models root folder (required for mapping/timestamped/profiles workflows).")
    private File modelsDir;

    @Option(names = "--constraints-root",
            description = "Constraints root folder (required for mapping/timestamped workflows). Optional for "
                    + "combined: relative owl:imports are then also resolved against it.")
    private File constraintsRoot;

    @Option(names = "--constraint-files", split = ",",
            description = "SHACL constraint files (.ttl, .rdf) or ZIP archives of them, comma-separated "
                    + "(required for the combined workflow). Together they form one shapes graph.")
    private List<File> constraintFiles;

    @Option(names = "--data-files", split = ",",
            description = "Instance data files (.xml) or ZIP archives of them, comma-separated "
                    + "(required for the combined workflow). Together they form one dataset.")
    private List<File> dataFiles;

    @Option(names = "--output",
            description = "Output folder (required).")
    private File outputDir;

    // ---- profiles workflow -----------------------------------------------

    @Option(names = "--profiles-library", split = ",",
            description = "Profiles workflow: folder(s) holding DX-PROF descriptors and the SHACL files they name, "
                    + "for example a checkout of the ENTSO-E application-profiles-library (required).")
    private List<File> profilesLibraries;

    @Option(names = "--boundary", split = ",",
            description = "Profiles workflow: folder(s) of boundary data, relative to --models. Their documents are "
                    + "validated as boundary groups and loaded as context for every other group.")
    private List<String> boundaryFolders;

    @Option(names = "--boundary-shapes-pattern",
            description = "Profiles workflow: shape files whose name contains this apply to boundary groups only "
                    + "(default EquipmentBoundary; empty applies every shape everywhere).")
    private String boundaryShapesPattern;

    @Option(names = "--common-shapes", split = ",",
            description = "Profiles workflow: shapes added to every group with CGMES documents, as file names in "
                    + "the profile library or as paths (default: 61970-600-1_AllProfiles-AP-Con-Complex-SHACL.ttl "
                    + "where the library has it; 'none' for none).")
    private List<String> commonShapes;

    @Option(names = "--profile-namespace", split = ",",
            description = "Profiles workflow: IRI prefix(es) that mark a dcterms:conformsTo value as a profile "
                    + "(default https://ap.cim4.eu/).")
    private List<String> profileNamespaces;

    @Option(names = "--allow-unmapped",
            description = "Profiles workflow: skip documents whose profile is unknown or lacks shapes, and report the "
                    + "run as incomplete (exit 1), instead of refusing the run (exit 2).")
    private Boolean allowUnmapped;

    /** Profiles workflow, config file only: explicit groups replacing the grouping by folder. */
    private JsonNode groupsConfig;
    private Path groupsConfigDir;

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
                    + "Default 3. Set 0 to disable per-shape detail. For the mapping workflow, "
                    + "enables --export-turtle automatically when positive.")
    private Integer samples;

    @Option(names = "--previous-comparison",
            description = "XLSX from a previous timestamped run, used to build a delta comparison sheet.")
    private File previousComparison;

    @Option(names = "--incremental-timestamps",
            description = "For timestamped validation, reuse unaffected Jena focus-node findings between consecutive timestamps.")
    private Boolean incrementalTimestamps;

    @Option(names = "--stats",
            description = "Report the run's resource use (wall/CPU time, peak heap, GC, triples loaded): "
                    + "a \"stats\" field in the JSON output, otherwise a [STATS] line on stderr.")
    private Boolean stats;

    /** The collector when {@code --stats} is on, otherwise null. */
    private RunStats runStats;

    @Option(names = "--summary-file",
            description = "Also write the JSON result (the document --format json prints) to this file, "
                    + "atomically, creating parent folders. For Airflow's KubernetesPodOperator: /airflow/xcom/return.json.")
    private File summaryFile;

    @Option(names = "--violations-exit-code",
            description = "Exit code when violations are found (default 1, 0..255). 0 lets a scheduler treat "
                    + "findings as data; bad input (2) and internal errors (3) are unchanged.")
    private Integer violationsExitCode;

    @Option(names = "--dry-run",
            description = "Print the resolved configuration and exit without running validation.")
    private boolean dryRun;

    @CommandLine.Mixin
    PythonOptions pythonOptions = new PythonOptions();

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        // SEC-3 (G9): a start-up flag only, checked before anything runs.
        if (!pythonOptions.apply(false)) {
            return ExitCode.INVALID_INPUT;
        }
        try {
            // 1. Load config file if provided, filling in any flag still null
            if (configFile != null) {
                loadConfig(configFile);
            }

            // 2. Apply defaults for anything still unset
            applyDefaults();
            runStats = StatsJson.startIf(stats);

            // 3. Dry-run: print resolved config and exit
            if (dryRun) {
                printDryRun();
                // The profiles workflow can say what it would do: its groups, without validating.
                return "profiles".equals(workflow) ? printProfilesPlan() : ExitCode.OK;
            }

            // 4. Validate inputs
            if (!validateInputs()) {
                return ExitCode.INVALID_INPUT;
            }

            // 5. Load datatype map
            Map<String, RDFDatatype> dataTypeMap = loadDatatypeMap();

            // 6. Resolve validation engine
            ValidationEngine validationEngine = resolveEngine();

            // 7. Execute workflow
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
        if (constraintFiles == null) {
            constraintFiles = fileList(configDir, root.path("constraintFiles"));
        }
        if (dataFiles == null) {
            dataFiles = fileList(configDir, root.path("dataFiles"));
        }
        if (outputDir == null) {
            String v = root.path("outputDir").asText(null);
            if (v != null && !v.isBlank()) outputDir = resolveRelative(configDir, v);
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
        if (incrementalTimestamps == null) {
            JsonNode n = root.path("incrementalTimestamps");
            if (!n.isMissingNode() && !n.isNull()) incrementalTimestamps = n.asBoolean(false);
        }
        if (previousComparison == null) {
            String v = root.path("previousComparison").asText(null);
            if (v != null && !v.isBlank()) previousComparison = resolveRelative(configDir, v);
        }
        if (profilesLibraries == null) {
            profilesLibraries = fileList(configDir, root.path("profilesLibrary"));
        }
        if (boundaryFolders == null) {
            boundaryFolders = stringList(root.path("boundaryFolders"));
        }
        if (boundaryShapesPattern == null) {
            JsonNode n = root.path("boundaryShapesPattern");
            if (n.isString()) boundaryShapesPattern = n.asString();
        }
        if (commonShapes == null) {
            commonShapes = stringList(root.path("commonShapes"));
        }
        if (profileNamespaces == null) {
            profileNamespaces = stringList(root.path("profileNamespaces"));
        }
        if (allowUnmapped == null) {
            JsonNode n = root.path("allowUnmapped");
            if (!n.isMissingNode() && !n.isNull()) allowUnmapped = n.asBoolean(false);
        }
        loadGroupsConfig(root, configDir);
    }

    /**
     * The explicit groups of the profiles workflow. Kept out of {@code loadConfig} on purpose: the
     * request schemas of serve, mcp and run list what {@code loadConfig} reads, and they refuse this
     * key (PathGuard), since its paths sit inside objects. It is for configuration files only.
     */
    private void loadGroupsConfig(JsonNode root, Path configDir) {
        JsonNode groups = root.path(PathGuard.VALIDATE_GROUPS_KEY);
        if (!groups.isMissingNode() && !groups.isNull()) {
            groupsConfig = groups;
            groupsConfigDir = configDir;
        }
    }

    /** A config value that lists texts: an array of strings, or one string. Null when the key is absent. */
    private static List<String> stringList(JsonNode node) {
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String v = item.asText(null);
                if (v != null && !v.isBlank()) values.add(v.strip());
            }
            return values;
        }
        String v = node.asText(null);
        return v == null || v.isBlank() ? null : List.of(v.strip());
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        if (p.isAbsolute()) return p.toFile();
        return configDir.resolve(p).normalize().toFile();
    }

    /**
     * A config value that lists files: an array of paths, or a single path. Unlike the
     * {@code --constraint-files} flag, a string is never split on commas, because {@code PathGuard}
     * checks it as one path. Null when the key is absent.
     */
    private static List<File> fileList(Path configDir, JsonNode node) {
        if (node.isArray()) {
            List<File> files = new ArrayList<>();
            for (JsonNode item : node) {
                String v = item.asText(null);
                if (v != null && !v.isBlank()) files.add(resolveRelative(configDir, v));
            }
            return files;
        }
        String v = node.asText(null);
        return v == null || v.isBlank() ? null : List.of(resolveRelative(configDir, v));
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
        // Default samples to 3 whenever the JSON document is produced (per-shape detail); 0 disables it
        if (samples == null) samples = producesJson() ? 3 : 0;
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    /** The JSON document is printed ({@code --format json}) or written ({@code --summary-file}). */
    private boolean producesJson() {
        return "json".equalsIgnoreCase(format) || summaryFile != null;
    }

    private boolean validateInputs() {
        boolean ok = AutomationOptions.checkViolationsExitCode(violationsExitCode);

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
            case "combined" -> {
                // A folder stands for its files, as the mapping workflow takes a models folder.
                constraintFiles = expandFolders(constraintFiles, CONSTRAINT_EXTENSIONS, "--constraint-files");
                dataFiles = expandFolders(dataFiles, DATA_EXTENSIONS, "--data-files");
                ok &= requireFiles(constraintFiles, "--constraint-files");
                ok &= requireFiles(dataFiles, "--data-files");
                if (constraintsRoot != null) {
                    ok &= requireDir(constraintsRoot, "--constraints-root");
                }
                ok &= requireOutputDir(outputDir, "--output");
            }
            case "profiles" -> {
                ok &= requireDir(modelsDir, "--models");
                if (profilesLibraries == null || profilesLibraries.isEmpty()) {
                    System.err.println("[ERROR] --profiles-library is required.");
                    ok = false;
                } else {
                    for (File library : profilesLibraries) {
                        ok &= requireDir(library, "--profiles-library");
                    }
                }
                ok &= requireOutputDir(outputDir, "--output");
                // Inputs of the other workflows would be ignored here; say so rather than drop them.
                ok &= notWithProfiles(mappingCsv != null, "--mapping-csv");
                ok &= notWithProfiles(constraintsRoot != null, "--constraints-root");
                ok &= notWithProfiles(constraintFiles != null && !constraintFiles.isEmpty(), "--constraint-files");
                ok &= notWithProfiles(dataFiles != null && !dataFiles.isEmpty(), "--data-files");
                ok &= notWithProfiles(previousComparison != null, "--previous-comparison");
                ok &= notWithProfiles(Boolean.TRUE.equals(incrementalTimestamps), "--incremental-timestamps");
                ok &= parseGroups();
            }
            case "manual" -> {
                // Configurations written for the removed workflow get a pointer, not a bare "unknown".
                System.err.println("[ERROR] The manual workflow has been removed from the CLI. It tested SHACL "
                        + "rules against Conform / NonConform models; use the GUI's SHACL > Constraints "
                        + "Operations > Test SHACL rules. Valid workflows: " + WORKFLOWS + ".");
                ok = false;
            }
            default -> {
                System.err.println("[ERROR] Unknown workflow: " + workflow
                        + ". Valid values: " + WORKFLOWS + ".");
                ok = false;
            }
        }

        if (!"profiles".equals(workflow) && usesProfileOptions()) {
            System.err.println("[ERROR] --profiles-library, --boundary, --boundary-shapes-pattern, --common-shapes, "
                    + "--profile-namespace, --allow-unmapped and \"groups\" belong to the profiles workflow"
                    + " (--workflow profiles).");
            ok = false;
        }

        if (previousComparison != null && !previousComparison.exists()) {
            System.err.println("[ERROR] Previous comparison file not found: "
                    + previousComparison.getAbsolutePath());
            ok = false;
        }

        return ok;
    }

    private static boolean notWithProfiles(boolean given, String flag) {
        if (given) {
            System.err.println("[ERROR] " + flag + " does not apply to the profiles workflow: the shapes come from"
                    + " the profiles the documents declare.");
        }
        return !given;
    }

    private boolean usesProfileOptions() {
        return (profilesLibraries != null && !profilesLibraries.isEmpty()) || boundaryFolders != null
                || boundaryShapesPattern != null || commonShapes != null || profileNamespaces != null
                || allowUnmapped != null || groupsConfig != null;
    }

    /** The explicit groups of the config file, parsed; prints what is wrong and returns false. */
    private List<ProfileGroupPlanner.GroupSpec> groups = List.of();

    private boolean parseGroups() {
        if (groupsConfig == null) {
            return true;
        }
        if (!groupsConfig.isArray() || groupsConfig.isEmpty()) {
            System.err.println("[ERROR] \"groups\" must be a non-empty array of group objects.");
            return false;
        }
        List<ProfileGroupPlanner.GroupSpec> parsed = new ArrayList<>();
        for (JsonNode g : groupsConfig) {
            try {
                List<File> reported = g.isObject() ? fileList(groupsConfigDir, g.path("reported")) : null;
                if (reported == null || reported.isEmpty()) {
                    throw new IllegalArgumentException("each group needs \"name\" and \"reported\" (files or folders)");
                }
                List<File> context = fileList(groupsConfigDir, g.path("context"));
                List<File> extra = fileList(groupsConfigDir, g.path("extraShapes"));
                List<String> assume = stringList(g.path("assumeProfiles"));
                parsed.add(new ProfileGroupPlanner.GroupSpec(g.path("name").asText(""), paths(reported),
                        context == null ? List.of() : paths(context), g.path("boundary").asBoolean(false),
                        extra == null ? List.of() : paths(extra), assume == null ? List.of() : assume));
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] \"groups\": " + LogSanitizer.forLog(ex.getMessage()));
                return false;
            }
        }
        groups = parsed;
        return true;
    }

    private static boolean requireFile(File f, String flag) {
        if (f == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        if (f.isDirectory()) {
            System.err.println("[ERROR] " + flag + " names a folder with no file to use in it: " + f.getAbsolutePath()
                    + (flag.equals("--data-files") ? " (expected .xml or .zip files)"
                    : flag.equals("--constraint-files") ? " (expected .ttl, .rdf or .zip files)" : ""));
            return false;
        }
        if (!f.exists() || !f.isFile()) {
            System.err.println("[ERROR] File not found for " + flag + ": " + f.getAbsolutePath());
            return false;
        }
        return true;
    }

    /** What a folder given to the combined workflow contributes. */
    static final List<String> CONSTRAINT_EXTENSIONS = List.of(".ttl", ".rdf", ".zip");
    static final List<String> DATA_EXTENSIONS = List.of(".xml", ".zip");

    /**
     * Replaces each folder in {@code files} by the files directly in it whose name ends in one of
     * {@code extensions} (any case), sorted by name; sub-folders are not entered. A folder with no
     * such file stays as it is, so the next check reports it. Files and missing paths are kept.
     */
    static List<File> expandFolders(List<File> files, List<String> extensions, String flag) {
        if (files == null) {
            return null;
        }
        List<File> expanded = new ArrayList<>();
        for (File f : files) {
            if (!f.isDirectory()) {
                expanded.add(f);
                continue;
            }
            File[] children = f.listFiles(child -> child.isFile() && extensions.stream()
                    .anyMatch(ext -> child.getName().toLowerCase(Locale.ROOT).endsWith(ext)));
            if (children == null || children.length == 0) {
                expanded.add(f);
                continue;
            }
            java.util.Arrays.sort(children, Comparator.comparing(File::getName));
            expanded.addAll(List.of(children));
        }
        return expanded;
    }

    /** At least one file, and every one of them an existing file. */
    private static boolean requireFiles(List<File> files, String flag) {
        if (files == null || files.isEmpty()) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        boolean ok = true;
        for (File f : files) {
            ok &= requireFile(f, flag);
        }
        return ok;
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

        // For JSON mode: System.out goes to stderr on this thread and those it starts, so
        // validation progress messages from ValidationTools do not pollute the JSON on stdout.
        // Only this run's threads are redirected (CliStreams), not the whole process (DEP-8).
        PrintStream origOut = CliStreams.currentOut();
        java.util.concurrent.Callable<Integer> run = () -> switch (workflow) {
            case "mapping"     -> runMappingWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
            case "timestamped" -> runTimestampedWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
            case "combined"    -> runCombinedWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
            case "profiles"    -> runProfilesWorkflow(dataTypeMap, validationEngine, jsonOutput, origOut);
            default            -> ExitCode.INVALID_INPUT;
        };
        return jsonOutput ? CliStreams.outToErr(run) : run.call();
    }

    // ---- mapping workflow ---------------------------------------------------

    private int runMappingWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                   ValidationEngine validationEngine,
                                   boolean jsonOutput,
                                   PrintStream origOut) throws Exception {

        // Per-shape detail requires TTL reports to be written so we can read them back.
        boolean needShapeDetail = producesJson() && samples > 0;
        boolean exportTurtleForRun = Boolean.TRUE.equals(exportTurtle) || needShapeDetail;

        MappingValidationOptions options = MappingValidationOptions.builder()
                .mappingCsv(mappingCsv.toPath())
                .modelsInput(modelsDir.toPath())
                .constraintsRoot(constraintsRoot.toPath())
                .outputDir(outputDir.toPath())
                .datatypeMap(dataTypeMap)
                .xmlBase(xmlBase)
                .engine(validationEngine)
                .maxResultsPerConstraint(maxResults)
                .threads(workers)
                .exportTurtleReports(exportTurtleForRun)
                .runStats(runStats)
                .build();

        MappingValidationSummary summary;
        try (StatsJson.Span ignored = StatsJson.phase(runStats, "validate")) {
            summary = new MappingValidator(options).validate();
        }

        List<ShapeGroup> shapeGroups = List.of();
        if (needShapeDetail && outputDir != null && outputDir.exists()) {
            try (StatsJson.Span ignored = StatsJson.phase(runStats, "shapeDetail")) {
                shapeGroups = extractShapeGroups(outputDir.toPath(), samples);
            }
        }

        // Built once, so stdout and the summary file carry the same document (and stats snapshot).
        String json = producesJson() ? buildMappingJson(summary, shapeGroups) : null;
        if (summaryFile != null && !AutomationOptions.writeSummary(summaryFile, json)) {
            return ExitCode.INTERNAL_ERROR;
        }
        if (jsonOutput) {
            origOut.println(json);
        } else {
            printTextSummary(summary);
            StatsJson.toStderr(runStats);
        }

        return exitCodeFor(summary);
    }

    /**
     * 1 when a row failed, which is never remapped (an error must not end as a pass); the
     * {@code --violations-exit-code} when rows have findings; 0 when all conform.
     */
    private int exitCodeFor(MappingValidationSummary summary) {
        if (summary.errors() > 0) {
            return ExitCode.VIOLATIONS;
        }
        return summary.violations() > 0 ? AutomationOptions.violationsExitCode(violationsExitCode) : ExitCode.OK;
    }

    // ---- timestamped workflow -----------------------------------------------

    private int runTimestampedWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                       ValidationEngine validationEngine,
                                       boolean jsonOutput,
                                       PrintStream origOut) throws Exception {
        Path prevPath = (previousComparison != null) ? previousComparison.toPath() : null;

        MappingValidationOptions.Builder optionsBuilder = MappingValidationOptions.builder()
                .mappingCsv(mappingCsv.toPath())
                .modelsInput(modelsDir.toPath())
                .constraintsRoot(constraintsRoot.toPath())
                .outputDir(outputDir.toPath())
                .timestamped(true)
                .datatypeMap(dataTypeMap)
                .xmlBase(xmlBase)
                .engine(validationEngine)
                .maxResultsPerConstraint(maxResults)
                .threads(workers)
                .exportTurtleReports(Boolean.TRUE.equals(exportTurtle))
                .incrementalTimestampValidation(Boolean.TRUE.equals(incrementalTimestamps))
                .runStats(runStats);
        if (prevPath != null) {
            optionsBuilder.previousComparisonCsv(prevPath);
        }

        MappingValidationSummary summary;
        try (StatsJson.Span ignored = StatsJson.phase(runStats, "validate")) {
            summary = new MappingValidator(optionsBuilder.build()).validate();
        }

        String json = producesJson() ? buildTimestampedJson(summary) : null;
        if (summaryFile != null && !AutomationOptions.writeSummary(summaryFile, json)) {
            return ExitCode.INTERNAL_ERROR;
        }
        if (jsonOutput) {
            origOut.println(json);
        } else {
            printTextSummaryTimestamped(summary);
            StatsJson.toStderr(runStats);
        }

        return exitCodeFor(summary);
    }

    // ---- combined workflow --------------------------------------------------

    private int runCombinedWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                    ValidationEngine validationEngine,
                                    boolean jsonOutput,
                                    PrintStream origOut) throws Exception {
        SHACLValidationReport report;
        try (StatsJson.Span ignored = StatsJson.phase(runStats, "validate")) {
            // As a mapping run does: re-read remote imports rather than reuse what an earlier
            // command in this JVM (serve, mcp) fetched.
            ValidationTools.clearRemoteCaches();
            report = new SHACLValidator(SHACLValidationOptions.builder()
                    .shapeFiles(paths(constraintFiles))
                    .dataFiles(paths(dataFiles))
                    .constraintsRoot(constraintsRoot == null ? null : constraintsRoot.toPath())
                    .datatypeMap(dataTypeMap)
                    .xmlBase(xmlBase)
                    .engine(validationEngine)
                    .maxResultsPerConstraint(maxResults)
                    .workers(workers)
                    .runStats(runStats)
                    .build()).validate();
        } catch (IOException | JenaException | PathNotAllowedException ex) {
            // There is one validation, so an input that can't be read, parsed or allowed (an
            // owl:imports not found, an archive without shape files, a path outside the roots)
            // stops the run: bad input, never a pass. Out of memory in a worker arrives wrapped.
            OutOfMemoryRethrow.ifCause(ex);
            // Jena's parse errors quote the input: one line, bounded, like every logged value.
            System.err.println("[ERROR] Validation could not run: " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INVALID_INPUT;
        }
        report.getWarnings().forEach(warning -> System.err.println("[WARN] " + LogSanitizer.forLog(warning)));
        if (report.checkedNothing()) {
            // No shape with a target, or no data: it "conforms" without a single check, which
            // a scheduler reading exit 0 would take for a pass.
            System.err.println("[ERROR] Nothing was validated (see the warnings above); no report was written.");
            return ExitCode.INVALID_INPUT;
        }

        SHACLValidationReport.WrittenReports written;
        try (StatsJson.Span ignored = StatsJson.phase(runStats, "report")) {
            written = report.writeReportsTo(outputDir.toPath(), Boolean.TRUE.equals(exportTurtle));
        } catch (IOException ex) {
            // The input was fine; the output folder, or the disk, was not.
            System.err.println("[ERROR] Could not write the report: " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INTERNAL_ERROR;
        }

        // The results are in memory, so per-shape detail needs no Turtle reports to read back.
        List<ShapeGroup> shapeGroups = producesJson() && samples > 0
                ? shapeGroups(report.getResults(), samples) : List.of();

        String json = producesJson() ? buildCombinedJson(report, written, shapeGroups) : null;
        if (summaryFile != null && !AutomationOptions.writeSummary(summaryFile, json)) {
            return ExitCode.INTERNAL_ERROR;
        }
        if (jsonOutput) {
            origOut.println(json);
        } else {
            printTextSummaryCombined(report, written);
            StatsJson.toStderr(runStats);
        }

        return conformingFiles(report) < report.getResultsByConstraintFile().size()
                ? AutomationOptions.violationsExitCode(violationsExitCode) : ExitCode.OK;
    }

    // ---- profiles workflow --------------------------------------------------

    private ProfileValidationOptions.Builder profileOptions() {
        ProfileValidationOptions.Builder b = ProfileValidationOptions.builder()
                .modelsDir(modelsDir.toPath())
                .profileLibraries(paths(profilesLibraries))
                .xmlBase(xmlBase)
                .engine(resolveEngine())
                .maxResultsPerConstraint(maxResults)
                .workers(workers)
                .exportTurtleReports(Boolean.TRUE.equals(exportTurtle))
                .allowUnmapped(Boolean.TRUE.equals(allowUnmapped))
                .groups(groups)
                .runStats(runStats);
        if (outputDir != null) b.outputDir(outputDir.toPath());
        if (boundaryFolders != null) b.boundaryFolders(boundaryFolders);
        if (boundaryShapesPattern != null) b.boundaryShapesPattern(boundaryShapesPattern);
        if (commonShapes != null) {
            b.commonShapes(commonShapes.size() == 1 && "none".equalsIgnoreCase(commonShapes.getFirst())
                    ? List.of() : commonShapes);
        }
        if (profileNamespaces != null) b.profileNamespaces(profileNamespaces);
        return b;
    }

    /** {@code --dry-run}: the groups the run would validate, without validating. */
    private int printProfilesPlan() {
        if (!validateInputs()) {
            return ExitCode.INVALID_INPUT;
        }
        ProfileValidator.Preview preview;
        try {
            preview = new ProfileValidator(profileOptions().build()).plan();
        } catch (IOException | JenaException | IllegalArgumentException
                 | IllegalStateException ex) {
            System.err.println("[ERROR] " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INVALID_INPUT;
        }
        System.out.println();
        System.out.println("=== Groups (" + preview.plan().groups().size() + ") ===");
        for (ProfileGroup group : preview.plan().groups()) {
            System.out.println("  " + LogSanitizer.forLog(group.name()) + (group.boundary() ? " (boundary)" : ""));
            group.reported().forEach(h -> System.out.println("    reported : " + LogSanitizer.forLog(h.source())));
            group.context().forEach(h -> System.out.println("    context  : " + LogSanitizer.forLog(h.source())));
            group.unionShapes().forEach(p -> System.out.println("    shapes   : " + p.getFileName()));
        }
        preview.plan().skipped().forEach(s -> System.out.println("  skipped  : " + LogSanitizer.forLog(s.source())
                + " (" + LogSanitizer.forLog(s.reason()) + ")"));
        preview.gaps().forEach(g -> System.out.println("  gap      : " + LogSanitizer.forLog(g)));
        return ExitCode.OK;
    }

    private int runProfilesWorkflow(Map<String, RDFDatatype> dataTypeMap,
                                    ValidationEngine validationEngine,
                                    boolean jsonOutput,
                                    PrintStream origOut) throws Exception {
        ProfileValidationSummary summary;
        try (StatsJson.Span ignored = StatsJson.phase(runStats, "validate")) {
            // As the other workflows do: re-read remote imports rather than reuse what an earlier
            // command in this JVM (serve, mcp) fetched.
            ValidationTools.clearRemoteCaches();
            summary = new ProfileValidator(profileOptions().datatypeMap(dataTypeMap).engine(validationEngine).build())
                    .validate();
        } catch (IOException | JenaException | IllegalArgumentException
                 | IllegalStateException ex) {
            // An input that can't be read, parsed or allowed, a profile that is not in the library,
            // documents of which none can be validated: bad input, never a pass.
            OutOfMemoryRethrow.ifCause(ex);
            System.err.println("[ERROR] Validation could not run: " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INVALID_INPUT;
        }
        summary.warnings().forEach(w -> System.err.println("[WARN] " + LogSanitizer.forLog(w)));
        summary.gaps().forEach(g -> System.err.println("[GAP] " + LogSanitizer.forLog(g)));
        summary.skipped().forEach(s -> System.err.println("[SKIPPED] " + LogSanitizer.forLog(s.source()) + ": "
                + LogSanitizer.forLog(s.reason())));

        List<SHACLValidationResult> all = summary.findings().stream()
                .map(ProfileValidationSummary.Finding::result).toList();
        List<ShapeGroup> shapeGroups = producesJson() && samples > 0 ? shapeGroups(all, samples) : List.of();
        String json = producesJson() ? buildProfilesJson(summary, shapeGroups) : null;
        if (summaryFile != null && !AutomationOptions.writeSummary(summaryFile, json)) {
            return ExitCode.INTERNAL_ERROR;
        }
        if (jsonOutput) {
            origOut.println(json);
        } else {
            printTextSummaryProfiles(summary);
            StatsJson.toStderr(runStats);
        }
        return exitCodeFor(summary);
    }

    /**
     * 1 when part of what should have been checked was not, which is never remapped (an
     * incomplete run must not end as a pass); the {@code --violations-exit-code} for findings or
     * a run cut short by the result limit; 0 when everything was checked and nothing found.
     */
    private int exitCodeFor(ProfileValidationSummary summary) {
        if (!summary.complete()) {
            return ExitCode.VIOLATIONS;
        }
        return summary.conforms() ? ExitCode.OK : AutomationOptions.violationsExitCode(violationsExitCode);
    }

    private static boolean groupConforms(ProfileValidationSummary.GroupResult group) {
        return group.findings() == 0 && !group.partial() && !group.incomplete();
    }

    private void printTextSummaryProfiles(ProfileValidationSummary summary) {
        long conforming = summary.groups().stream().filter(ValidateCommand::groupConforms).count();
        SeverityCounts results = SeverityCounts.of(summary.findings().stream()
                .map(ProfileValidationSummary.Finding::result).toList());
        System.out.println();
        System.out.println("=== Profile Validation Summary ===");
        for (ProfileValidationSummary.GroupResult group : summary.groups()) {
            System.out.println("  " + LogSanitizer.forLog(group.name()) + " : " + group.reported().size()
                    + " document(s), " + group.findings() + " finding(s)"
                    + (group.incomplete() ? ", INCOMPLETE" : "") + (group.partial() ? ", partial" : ""));
        }
        System.out.println("  Conforming : " + conforming + " of " + summary.groups().size() + " groups");
        System.out.println("  Results    : " + results.total() + " (violations " + results.violations()
                + ", warnings " + results.warnings() + ", infos " + results.infos() + ")");
        System.out.println("  Skipped    : " + summary.skipped().size() + " document(s)");
        System.out.println("  Gaps       : " + summary.gaps().size());
        if (summary.workbook() != null) {
            System.out.println("  Report     : " + summary.workbook().toAbsolutePath());
        }
        System.out.println(!summary.complete() ? "  Result     : INCOMPLETE (part of the data or shapes was not checked)"
                : summary.conforms() ? "  Result     : ALL CONFORMING" : "  Result     : VIOLATIONS FOUND");
    }

    /**
     * The profiles workflow's document. {@code totals} count groups, as the other workflows count
     * workbook rows; {@code results} counts findings. {@code complete} false means part of what
     * should have been checked was not: the run is then never clean, whatever it found.
     */
    private String buildProfilesJson(ProfileValidationSummary summary, List<ShapeGroup> shapeGroups) {
        List<ProfileValidationSummary.GroupResult> groupResults = summary.groups();
        long conforming = groupResults.stream().filter(ValidateCommand::groupConforms).count();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"cimpal-validate-summary/1\",\n");
        sb.append("  \"run\": {\n");
        sb.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("    \"workflow\": \"profiles\",\n");
        sb.append("    \"inputs\": {\n");
        sb.append("      \"modelsDir\": ").append(jsonStr(abs(modelsDir))).append(",\n");
        sb.append("      \"profilesLibrary\": ").append(jsonPaths(profilesLibraries)).append(",\n");
        sb.append("      \"outputDir\": ").append(jsonStr(abs(outputDir))).append("\n");
        sb.append("    },\n");
        sb.append("    \"options\": ").append(buildOptionsJson()).append("\n");
        sb.append("  },\n");
        sb.append("  \"totals\": {\n");
        sb.append("    \"conforming\": ").append(conforming).append(",\n");
        sb.append("    \"violations\": ").append(groupResults.size() - conforming).append(",\n");
        sb.append("    \"errors\": 0,\n");
        sb.append("    \"total\": ").append(groupResults.size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"hasViolations\": ").append(!summary.conforms()).append(",\n");
        sb.append("  \"complete\": ").append(summary.complete()).append(",\n");
        sb.append("  \"partial\": ").append(summary.partial()).append(",\n");
        sb.append("  \"results\": ").append(SeverityCounts.of(summary.findings().stream()
                .map(ProfileValidationSummary.Finding::result).toList()).json()).append(",\n");
        sb.append("  \"byGroup\": [\n");
        for (int i = 0; i < groupResults.size(); i++) {
            ProfileValidationSummary.GroupResult g = groupResults.get(i);
            List<SHACLValidationResult> own = summary.findings().stream()
                    .filter(f -> f.group().equals(g.name()) && !f.documents().isEmpty())
                    .map(ProfileValidationSummary.Finding::result).toList();
            sb.append("    {\"group\": ").append(jsonStr(g.name()))
                    .append(", \"conforms\": ").append(groupConforms(g))
                    .append(", \"complete\": ").append(!g.incomplete())
                    .append(", \"partial\": ").append(g.partial())
                    .append(", \"reported\": ").append(jsonStrings(g.reported()))
                    .append(", \"context\": ").append(jsonStrings(g.context()))
                    .append(", \"shapes\": ").append(jsonStrings(g.shapes()))
                    .append(", \"results\": ").append(SeverityCounts.of(own).json())
                    .append(", \"contextFindings\": ").append(g.contextFindings())
                    .append(", \"millis\": ").append(g.millis()).append("}")
                    .append(i < groupResults.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        List<SHACLValidationResult> aboutShapes = summary.findings().stream().filter(f -> f.documents().isEmpty())
                .map(ProfileValidationSummary.Finding::result).toList();
        sb.append("  \"shapeFindings\": ").append(SeverityCounts.of(aboutShapes).json()).append(",\n");
        sb.append("  \"skipped\": [");
        for (int i = 0; i < summary.skipped().size(); i++) {
            ProfileGroupPlanner.Skipped s = summary.skipped().get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("    {\"document\": ").append(jsonStr(s.source()))
                    .append(", \"reason\": ").append(jsonStr(s.reason())).append("}");
        }
        sb.append(summary.skipped().isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"gaps\": ").append(jsonStrings(summary.gaps())).append(",\n");
        sb.append("  \"warnings\": ").append(jsonStrings(summary.warnings())).append(",\n");
        if (!shapeGroups.isEmpty()) {
            sb.append("  \"shapes\": ").append(buildShapeGroupsJson(shapeGroups)).append(",\n");
        }
        if (!summary.turtleReports().isEmpty()) {
            sb.append("  \"turtleReports\": ").append(jsonStrings(summary.turtleReports().stream()
                    .map(p -> p.toAbsolutePath().toString()).toList())).append(",\n");
        }
        sb.append("  \"report\": ").append(summary.workbook() == null ? "null"
                        : jsonStr(summary.workbook().toAbsolutePath().toString()))
                .append(StatsJson.field(runStats, "\n  ")).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /** Constraint files whose row of the workbook says they conform. */
    private static long conformingFiles(SHACLValidationReport report) {
        return report.getResultsByConstraintFile().stream().filter(report::conforms).count();
    }

    private static List<Path> paths(List<File> files) {
        return files.stream().map(File::toPath).toList();
    }

    // -------------------------------------------------------------------------
    // Text output
    // -------------------------------------------------------------------------

    private void printTextSummary(MappingValidationSummary summary) {
        System.out.println();
        System.out.println("=== Validation Summary ===");
        System.out.println("  Conforming : " + summary.conforming());
        System.out.println("  Violations : " + summary.violations());
        System.out.println("  Errors     : " + summary.errors());
        System.out.println("  Total rows : " + summary.totalRows());
        System.out.println("  Report     : " + summary.reports().get(0).toAbsolutePath());
        if (summary.hasViolations()) {
            System.out.println("  Result     : VIOLATIONS FOUND");
        } else {
            System.out.println("  Result     : ALL CONFORMING");
        }
    }

    private void printTextSummaryTimestamped(MappingValidationSummary summary) {
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

    private void printTextSummaryCombined(SHACLValidationReport report,
                                          SHACLValidationReport.WrittenReports written) {
        int files = report.getResultsByConstraintFile().size();
        long conforming = conformingFiles(report);
        SeverityCounts results = SeverityCounts.of(report.getResults());
        System.out.println();
        System.out.println("=== Combined Validation Summary ===");
        System.out.println("  Conforming : " + conforming);
        System.out.println("  Violations : " + (files - conforming));
        System.out.println("  Total rows : " + files + " (one per constraint file)");
        System.out.println("  Results    : " + results.total() + " (violations " + results.violations()
                + ", warnings " + results.warnings() + ", infos " + results.infos() + ")");
        if (report.isPartial()) {
            System.out.println("  Scope      : Partial (stopped after " + maxResults + " results per shape)");
        }
        System.out.println("  Report     : " + written.workbook().toAbsolutePath());
        if (written.turtle() != null) {
            System.out.println("  Turtle     : " + written.turtle().toAbsolutePath());
        }
        System.out.println(conforming < files ? "  Result     : VIOLATIONS FOUND" : "  Result     : ALL CONFORMING");
    }

    /**
     * Results by severity, counted the way the workbook's statistics count them: a severity that
     * names neither a violation nor a warning is an info.
     */
    private record SeverityCounts(int total, int violations, int warnings, int infos) {
        static SeverityCounts of(List<SHACLValidationResult> results) {
            int violations = 0;
            int warnings = 0;
            int infos = 0;
            for (SHACLValidationResult result : results) {
                String severity = result.getSeverity() == null ? "" : result.getSeverity().toLowerCase(Locale.ROOT);
                if (severity.contains("violation")) violations++;
                else if (severity.contains("warning")) warnings++;
                else infos++;
            }
            return new SeverityCounts(results.size(), violations, warnings, infos);
        }

        String json() {
            return "{\"total\": " + total + ", \"violations\": " + violations + ", \"warnings\": " + warnings
                    + ", \"infos\": " + infos + "}";
        }
    }

    // -------------------------------------------------------------------------
    // JSON output (mapping workflow)
    // -------------------------------------------------------------------------

    private String buildMappingJson(MappingValidationSummary summary, List<ShapeGroup> shapeGroups) {
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
        sb.append("  \"report\": ").append(jsonStr(summary.reports().get(0).toAbsolutePath().toString()))
                .append(StatsJson.field(runStats, "\n  ")).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private String buildTimestampedJson(MappingValidationSummary summary) {
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
        sb.append("]").append(StatsJson.field(runStats, "\n  ")).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * The combined workflow's document. {@code totals} count the workbook's rows, one per
     * constraint file, as the mapping workflow's count its rows; {@code results} counts findings.
     */
    private String buildCombinedJson(SHACLValidationReport report,
                                     SHACLValidationReport.WrittenReports written,
                                     List<ShapeGroup> shapeGroups) {
        List<SHACLValidationReport.ConstraintFileResults> files = report.getResultsByConstraintFile();
        long conforming = conformingFiles(report);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"cimpal-validate-summary/1\",\n");
        sb.append("  \"run\": {\n");
        sb.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("    \"workflow\": \"combined\",\n");
        sb.append("    \"inputs\": {\n");
        sb.append("      \"constraintFiles\": ").append(jsonPaths(constraintFiles)).append(",\n");
        sb.append("      \"dataFiles\": ").append(jsonPaths(dataFiles)).append(",\n");
        sb.append("      \"constraintsRoot\": ")
                .append(constraintsRoot == null ? "null" : jsonStr(abs(constraintsRoot))).append(",\n");
        sb.append("      \"outputDir\": ").append(jsonStr(abs(outputDir))).append("\n");
        sb.append("    },\n");
        sb.append("    \"options\": ").append(buildOptionsJson()).append("\n");
        sb.append("  },\n");
        sb.append("  \"totals\": {\n");
        sb.append("    \"conforming\": ").append(conforming).append(",\n");
        sb.append("    \"violations\": ").append(files.size() - conforming).append(",\n");
        sb.append("    \"errors\": 0,\n");
        sb.append("    \"total\": ").append(files.size()).append("\n");
        sb.append("  },\n");
        sb.append("  \"hasViolations\": ").append(conforming < files.size()).append(",\n");
        sb.append("  \"partial\": ").append(report.isPartial()).append(",\n");
        sb.append("  \"results\": ").append(SeverityCounts.of(report.getResults()).json()).append(",\n");
        sb.append("  \"byConstraintFile\": [\n");
        for (int i = 0; i < files.size(); i++) {
            SHACLValidationReport.ConstraintFileResults file = files.get(i);
            sb.append("    {\"constraintFile\": ").append(jsonStr(file.constraintFile()))
                    .append(", \"conforms\": ").append(report.conforms(file))
                    .append(", \"results\": ").append(SeverityCounts.of(file.results()).json()).append("}")
                    .append(i < files.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"warnings\": ").append(jsonStrings(report.getWarnings())).append(",\n");
        if (!shapeGroups.isEmpty()) {
            sb.append("  \"shapes\": ").append(buildShapeGroupsJson(shapeGroups)).append(",\n");
        }
        if (written.turtle() != null) {
            sb.append("  \"turtleReport\": ").append(jsonStr(written.turtle().toAbsolutePath().toString())).append(",\n");
        }
        sb.append("  \"report\": ").append(jsonStr(written.workbook().toAbsolutePath().toString()))
                .append(StatsJson.field(runStats, "\n  ")).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonPaths(List<File> files) {
        return jsonStrings(files == null ? List.of() : files.stream().map(File::getAbsolutePath).toList());
    }

    private static String jsonStrings(List<String> values) {
        return values.stream().map(ValidateCommand::jsonStr).collect(Collectors.joining(", ", "[", "]"));
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
        System.out.println("  constraintFiles  : " + absList(constraintFiles));
        System.out.println("  dataFiles        : " + absList(dataFiles));
        System.out.println("  outputDir        : " + abs(outputDir));
        System.out.println("  datatypeMap      : " + datatypeMap);
        System.out.println("  xmlBase          : " + xmlBase);
        System.out.println("  engine           : " + engine);
        System.out.println("  workers          : " + workers);
        System.out.println("  maxResults       : " + maxResults);
        System.out.println("  format           : " + format);
        System.out.println("  samples          : " + samples);
        System.out.println("  summaryFile      : " + abs(summaryFile));
        System.out.println("  violationsExit   : " + AutomationOptions.violationsExitCode(violationsExitCode));
        System.out.println("  exportTurtle     : " + exportTurtle);
        System.out.println("  previousCompar.  : " + abs(previousComparison));
        System.out.println("  incrementalTimestamps : " + Boolean.TRUE.equals(incrementalTimestamps));
        if ("profiles".equals(workflow)) {
            System.out.println("  profilesLibrary  : " + absList(profilesLibraries));
            System.out.println("  boundaryFolders  : " + (boundaryFolders == null ? "(not set)" : boundaryFolders));
            System.out.println("  boundaryShapes   : " + (boundaryShapesPattern == null
                    ? ProfileValidationOptions.DEFAULT_BOUNDARY_SHAPES_PATTERN + " (default)" : boundaryShapesPattern));
            System.out.println("  commonShapes     : " + (commonShapes == null
                    ? ProfileValidationOptions.DEFAULT_COMMON_SHAPES + " (default, where present)" : commonShapes));
            System.out.println("  profileNamespace : " + (profileNamespaces == null ? "https://ap.cim4.eu/ (default)"
                    : profileNamespaces));
            System.out.println("  allowUnmapped    : " + Boolean.TRUE.equals(allowUnmapped));
            System.out.println("  groups           : " + (groupsConfig == null ? "(by folder)"
                    : groupsConfig.size() + " explicit"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String abs(File f) {
        return f == null ? "(not set)" : f.getAbsolutePath();
    }

    private static String absList(List<File> files) {
        return files == null || files.isEmpty() ? "(not set)"
                : files.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "));
    }

    /** A JSON string literal. Control characters are escaped too, so the document always parses. */
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

    /**
     * The combined workflow's per-shape detail, grouped as {@link #extractShapeGroups} groups the
     * mapping workflow's Turtle reports, but straight from the results in memory.
     */
    private static List<ShapeGroup> shapeGroups(List<SHACLValidationResult> results, int maxSamples) {
        Map<String, List<SHACLValidationResult>> byShapeAndConstraint = new LinkedHashMap<>();
        for (SHACLValidationResult result : results) {
            byShapeAndConstraint.computeIfAbsent(result.getSourceShape() + "||" + result.getConstraintComponent(),
                    key -> new ArrayList<>()).add(result);
        }
        return byShapeAndConstraint.values().stream()
                .map(group -> new ShapeGroup(
                        // A Python engine's result may lack a source shape.
                        Objects.toString(group.getFirst().getSourceShape(), ""),
                        abbreviateUri(Objects.toString(group.getFirst().getConstraintComponent(), "")),
                        abbreviateUri(group.getFirst().getPath()),
                        group.size(),
                        group.stream().map(SHACLValidationResult::getFocusNode)
                                .filter(node -> node != null && !node.isBlank())
                                .distinct().limit(maxSamples).toList()))
                // Highest count first; shape and constraint make the order the same on every run.
                .sorted(Comparator.comparingInt(ShapeGroup::count).reversed()
                        .thenComparing(ShapeGroup::shapeId).thenComparing(ShapeGroup::constraint))
                .toList();
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
