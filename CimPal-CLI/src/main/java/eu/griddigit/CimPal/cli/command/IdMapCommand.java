/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.idmapping.IdMapper;
import eu.griddigit.cimpal.core.idmapping.IdMappingOptions;
import eu.griddigit.cimpal.core.idmapping.IdMappingResult;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code id-map} subcommand: maps the IDs of one CIM/CGMES model (model 1) onto another model of the
 * same grid (model 2), e.g. two EQ files onto one merged EQ, and writes the mapping workbook.
 *
 * <p>The workbook has a {@code Matched} sheet (Model1_ID, Element_type, Model2_ID, Model2_2nd_name, then
 * Model1_name, Model2_type, Method, Note), an {@code Unmatched} sheet with the objects left for a person
 * (with reasons and candidates) and a {@code Summary} sheet.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — every model 1 object was mapped
 *   <li>1 — the workbook was written and some objects are unmatched (a result, not a failure)
 *   <li>2 — missing or bad input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "id-map",
        mixinStandardHelpOptions = true,
        description = "Map the IDs of one CIM model (model 1) onto another model (model 2) of the same grid and write "
                + "the mapping workbook (Matched, Unmatched, Summary).",
        sortOptions = false
)
public class IdMapCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--model1", split = ",",
            description = "Files of the model whose IDs are mapped (Model1_ID side), comma-separated; "
                    + "RDF/XML files or ZIPs of them.")
    private List<File> model1Files;

    @Option(names = "--model2", split = ",",
            description = "Files of the model the IDs are mapped to (Model2_ID side), comma-separated.")
    private List<File> model2Files;

    @Option(names = "--common", split = ",",
            description = "Files loaded into both sides and not mapped: boundary set, common data with the "
                    + "BaseVoltages, comma-separated.")
    private List<File> commonFiles;

    @Option(names = "--output",
            description = "The mapping workbook to write (.xlsx, or .xls for the old format).")
    private File outputFile;

    @Option(names = "--query-folder",
            description = "Folder with edited copies of the ID mapping SPARQL queries; files missing from it "
                    + "are taken from the bundled set.")
    private File queryFolder;

    @Option(names = "--name-aliases",
            description = "CSV of substation name aliases, one station per line (e.g. code;full name).")
    private File nameAliases;

    @Option(names = "--names", negatable = true,
            description = "Seed the matching with substations of the same name (default: on).")
    private Boolean useNames;

    @Option(names = "--same-ids", negatable = true,
            description = "Match objects with the same ID and class first (default: on).")
    private Boolean matchSameIds;

    @Option(names = "--collapse-junctions", negatable = true,
            description = "Treat substations holding only line ends and switches as part of the line (default: on).")
    private Boolean collapseJunctions;

    @Option(names = "--group-stations", negatable = true,
            description = "Treat substations linked by a transformer or switch as one station (default: on).")
    private Boolean groupStations;

    @Option(names = "--terminals", negatable = true, description = "Map and report terminals (default: on).")
    private Boolean includeTerminals;

    @Option(names = "--nodes", negatable = true, description = "Map and report connectivity nodes (default: on).")
    private Boolean includeNodes;

    @Option(names = "--dependents", negatable = true,
            description = "Map and report dependent objects: tap changers, limits, controls, units (default: on).")
    private Boolean includeDependents;

    @Option(names = "--others", negatable = true,
            description = "Map and report objects outside the topology: limit types, regions, load groups "
                    + "(default: on).")
    private Boolean includeOthers;

    @Option(names = "--parameter-tolerance",
            description = "Relative tolerance for line r, x, bch and length (default: 0.05).")
    private Double parameterTolerance;

    @Option(names = "--voltage-tolerance",
            description = "Tolerance in kV for nominal voltages (default: 1).")
    private Double voltageTolerance;

    @Option(names = "--second-name",
            description = "What fills Model2_2nd_name: name (default), shortName or description.")
    private String secondName;

    @Option(names = "--xml-base",
            description = "XML base URI used when loading models (default: http://iec.ch/TC57/CIM100).")
    private String xmlBase;

    @Option(names = "--format",
            description = "Summary on stdout: text (default) or json (stdout then holds only the JSON).")
    private String format;

    @Option(names = "--dry-run", description = "Print the resolved configuration and exit.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) {
                return ExitCode.INVALID_INPUT;
            }
            if (format == null) {
                format = "text";
            }
            if (dryRun) {
                printDryRun();
                return ExitCode.OK;
            }
            IdMappingOptions options;
            try {
                options = buildOptions();
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] " + ex.getMessage());
                return ExitCode.INVALID_INPUT;
            }
            IdMappingResult result;
            try {
                result = new IdMapper(options, message -> System.err.println("[INFO] " + message)).run();
            } catch (IOException | UncheckedIOException | IllegalArgumentException ex) {
                System.err.println("[ERROR] Cannot map: " + LogSanitizer.forLog(ex.getMessage()));
                return ExitCode.INVALID_INPUT;
            }
            try {
                result.writeExcel(outputFile.toPath());
            } catch (IOException ex) {
                System.err.println("[ERROR] Cannot write " + outputFile + ": " + LogSanitizer.forLog(ex.getMessage()));
                return ExitCode.INTERNAL_ERROR;
            }
            printSummary(result);
            return result.getModel1UnmatchedCount() == 0 ? ExitCode.OK : ExitCode.VIOLATIONS;
        } catch (RuntimeException ex) {
            System.err.println("[ERROR] id-map failed: " + LogSanitizer.forLog(ex.toString()));
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private IdMappingOptions buildOptions() {
        List<String> problems = new ArrayList<>();
        if (model1Files == null || model1Files.isEmpty()) {
            problems.add("--model1 is required");
        }
        if (model2Files == null || model2Files.isEmpty()) {
            problems.add("--model2 is required");
        }
        if (outputFile == null) {
            problems.add("--output is required");
        }
        for (List<File> list : List.of(nvl(model1Files), nvl(model2Files), nvl(commonFiles))) {
            for (File f : list) {
                if (!f.isFile()) {
                    problems.add("file not found: " + f);
                }
            }
        }
        if (queryFolder != null && !queryFolder.isDirectory()) {
            problems.add("query folder not found: " + queryFolder);
        }
        if (nameAliases != null && !nameAliases.isFile()) {
            problems.add("name alias file not found: " + nameAliases);
        }
        if (!format.equalsIgnoreCase("text") && !format.equalsIgnoreCase("json")) {
            problems.add("--format must be text or json");
        }
        IdMappingOptions.SecondName second = parseSecondName(secondName, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        IdMappingOptions.Builder b = IdMappingOptions.builder()
                .model1Files(toPaths(model1Files))
                .model2Files(toPaths(model2Files))
                .commonFiles(toPaths(nvl(commonFiles)))
                .secondName(second);
        if (xmlBase != null) b.xmlBase(xmlBase);
        if (queryFolder != null) b.queryFolder(queryFolder.toPath());
        if (nameAliases != null) b.nameAliasFile(nameAliases.toPath());
        if (useNames != null) b.useNames(useNames);
        if (matchSameIds != null) b.matchSameIds(matchSameIds);
        if (collapseJunctions != null) b.collapsePassThroughSubstations(collapseJunctions);
        if (groupStations != null) b.groupStations(groupStations);
        if (includeTerminals != null) b.includeTerminals(includeTerminals);
        if (includeNodes != null) b.includeNodes(includeNodes);
        if (includeDependents != null) b.includeDependents(includeDependents);
        if (includeOthers != null) b.includeOthers(includeOthers);
        if (parameterTolerance != null) b.parameterTolerance(parameterTolerance);
        if (voltageTolerance != null) b.voltageTolerance(voltageTolerance);
        return b.build();
    }

    private static IdMappingOptions.SecondName parseSecondName(String value, List<String> problems) {
        if (value == null) {
            return IdMappingOptions.SecondName.NAME;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "name" -> IdMappingOptions.SecondName.NAME;
            case "shortname", "short-name" -> IdMappingOptions.SecondName.SHORT_NAME;
            case "description" -> IdMappingOptions.SecondName.DESCRIPTION;
            default -> {
                problems.add("--second-name must be name, shortName or description");
                yield IdMappingOptions.SecondName.NAME;
            }
        };
    }

    // ---- config -----------------------------------------------------------------------------

    /** Reads the config; keys starting with "_" are documentation. Returns false if it cannot be read. */
    private boolean loadConfig(File config) {
        Path dir = config.toPath().toAbsolutePath().getParent();
        JsonNode root;
        try {
            root = JSON.readTree(config);
        } catch (RuntimeException ex) {
            System.err.println("[ERROR] Cannot read config " + config + ": " + LogSanitizer.forLog(ex.getMessage()));
            return false;
        }
        try {
            applyConfig(root, dir);
        } catch (InvalidPathException ex) {
            System.err.println("[ERROR] Invalid path in config " + config + ": " + LogSanitizer.forLog(ex.getMessage()));
            return false;
        }
        return true;
    }

    private void applyConfig(JsonNode root, Path dir) {
        if (model1Files == null) model1Files = files(root.path("model1Files"), dir);
        if (model2Files == null) model2Files = files(root.path("model2Files"), dir);
        if (commonFiles == null) commonFiles = files(root.path("commonFiles"), dir);
        if (outputFile == null) outputFile = file(root.path("output"), dir);
        if (queryFolder == null) queryFolder = file(root.path("queryFolder"), dir);
        if (nameAliases == null) nameAliases = file(root.path("nameAliases"), dir);
        if (useNames == null) useNames = bool(root.path("useNames"));
        if (matchSameIds == null) matchSameIds = bool(root.path("matchSameIds"));
        if (collapseJunctions == null) collapseJunctions = bool(root.path("collapseJunctions"));
        if (groupStations == null) groupStations = bool(root.path("groupStations"));
        if (includeTerminals == null) includeTerminals = bool(root.path("includeTerminals"));
        if (includeNodes == null) includeNodes = bool(root.path("includeNodes"));
        if (includeDependents == null) includeDependents = bool(root.path("includeDependents"));
        if (includeOthers == null) includeOthers = bool(root.path("includeOthers"));
        if (parameterTolerance == null) parameterTolerance = number(root.path("parameterTolerance"));
        if (voltageTolerance == null) voltageTolerance = number(root.path("voltageTolerance"));
        if (secondName == null) secondName = text(root.path("secondName"));
        if (xmlBase == null) xmlBase = text(root.path("xmlBase"));
        if (format == null) format = text(root.path("format"));
    }

    private static List<File> files(JsonNode node, Path dir) {
        if (!node.isArray()) {
            return null;
        }
        List<File> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(resolve(dir, item.asString()));
        }
        return list;
    }

    private static File file(JsonNode node, Path dir) {
        String value = text(node);
        return value == null ? null : resolve(dir, value);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean bool(JsonNode node) {
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static File resolve(Path dir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : dir.resolve(p).normalize().toFile();
    }

    // ---- output -----------------------------------------------------------------------------

    private void printSummary(IdMappingResult result) {
        Map<String, Integer> methods = new TreeMap<>(result.getMethodCounts());
        IdMappingResult.Overview model1 = result.getModel1Overview();
        IdMappingResult.Overview model2 = result.getModel2Overview();
        if ("json".equalsIgnoreCase(format)) {
            ObjectNode root = JSON.createObjectNode();
            root.put("schema", "cimpal-id-map-summary/3");
            root.put("output", outputFile.getAbsolutePath());
            overview(root.putObject("model1"), model1);
            overview(root.putObject("model2"), model2);
            ObjectNode byMethod = root.putObject("methods");
            methods.forEach(byMethod::put);
            ArrayNode types = root.putArray("types");
            for (IdMappingResult.TypeCount t : result.getTypeCounts()) {
                ObjectNode row = types.addObject();
                row.put("elementType", t.elementType());
                row.put("inBothModels", t.inBothModels());
                row.put("model1Objects", t.model1Objects());
                row.put("certain", t.certain());
                row.put("probable", t.probable());
                row.put("model1Unmatched", t.model1Unmatched());
                row.put("model2Objects", t.model2Objects());
                row.put("model2Certain", t.model2Certain());
                row.put("model2Probable", t.model2Probable());
                row.put("model2Unmatched", t.model2Unmatched());
            }
            System.out.println(root.toPrettyString());
            return;
        }
        System.out.println("[OK] ID mapping written to: " + outputFile.getAbsolutePath());
        System.out.println("     Classes in both models:");
        System.out.println("     " + overviewLine("Model 1", model1));
        System.out.println("     " + overviewLine("Model 2", model2));
        System.out.println("     By method: " + methods.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
    }

    private static void overview(ObjectNode node, IdMappingResult.Overview o) {
        node.put("objects", o.objects());
        node.put("certain", o.certain());
        node.put("probable", o.probable());
        node.put("unmatched", o.unmatched());
    }

    private static String overviewLine(String label, IdMappingResult.Overview o) {
        return String.format(Locale.ROOT, "%s %d objects: certain %d (%.1f%%), probable %d (%.1f%%), not matched %d (%.1f%%)",
                label, o.objects(), o.certain(), o.percent(o.certain()), o.probable(), o.percent(o.probable()),
                o.unmatched(), o.percent(o.unmatched()));
    }

    private void printDryRun() {
        // in JSON mode stdout is reserved for JSON, so the plain-text dry run goes to stderr
        java.io.PrintStream out = "json".equalsIgnoreCase(format) ? System.err : System.out;
        out.println("=== CimPal id-map -- Dry Run ===");
        out.println("  model1          : " + list(model1Files));
        out.println("  model2      : " + list(model2Files));
        out.println("  common      : " + list(commonFiles));
        out.println("  output      : " + (outputFile == null ? "(not set)" : outputFile.getAbsolutePath()));
        out.println("  query-folder: " + (queryFolder == null ? "(bundled)" : queryFolder.getAbsolutePath()));
        out.println("  name-aliases: " + (nameAliases == null ? "(none)" : nameAliases.getAbsolutePath()));
        out.println("  names       : " + (useNames == null || useNames));
        out.println("  same-ids    : " + (matchSameIds == null || matchSameIds));
        out.println("  junctions   : " + (collapseJunctions == null || collapseJunctions));
        out.println("  stations    : " + (groupStations == null || groupStations));
        out.println("  format      : " + format);
    }

    private static String list(List<File> files) {
        return files == null || files.isEmpty() ? "(not set)"
                : files.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "));
    }

    private static List<Path> toPaths(List<File> files) {
        return files.stream().map(File::toPath).toList();
    }

    private static List<File> nvl(List<File> files) {
        return files == null ? List.of() : files;
    }
}
