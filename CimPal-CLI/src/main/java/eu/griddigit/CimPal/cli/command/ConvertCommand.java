package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.converters.RDFConverter;
import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.riot.RDFFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code convert} subcommand — convert RDF model files between serialisation formats.
 *
 * <p>Supports single-file conversion and multi-file union conversion (where multiple input
 * files are merged into one RDF graph before writing the output).
 *
 * <p>Source and target formats are auto-detected from file extensions when not specified:
 * {@code .ttl} → Turtle, {@code .jsonld} → JSON-LD, everything else → RDF/XML.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — conversion succeeded
 *   <li>2 — missing or bad input
 *   <li>3 — conversion failed (internal error)
 * </ul>
 */
@Command(
        name = "convert",
        mixinStandardHelpOptions = true,
        description = "Convert RDF model files between formats (RDF/XML, Turtle, JSON-LD).",
        sortOptions = false
)
public class ConvertCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- input paths -------------------------------------------------------

    @Option(names = "--input",
            description = "Single source file (mutually exclusive with --input-files).")
    private File inputFile;

    @Option(names = "--input-files",
            description = "Multiple source files to union into one output (comma-separated).",
            split = ",")
    private List<File> inputFiles;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output",
            description = "Output file path (required).")
    private File outputFile;

    // ---- format options ----------------------------------------------------

    @Option(names = "--source-format",
            description = "Source format: RDFXML, TURTLE, JSONLD. Default: auto-detect from input extension.")
    private String sourceFormat;

    @Option(names = "--target-format",
            description = "Target format: RDFXML, TURTLE, JSONLD. Default: auto-detect from output extension.")
    private String targetFormat;

    @Option(names = "--xml-base",
            description = "Base URI used when reading/writing RDF/XML (default: empty string).")
    private String xmlBase;

    @Option(names = "--rdf-format",
            description = "RDF/XML sub-format: RDFXML_PLAIN (default), RDFXML_ABBREV, RDFXML_PRETTY, CIMXML, RDFS_CIMXML.")
    private String rdfFormatName;

    // ---- sort/prefix options -----------------------------------------------

    @Option(names = "--sort",
            description = "Sort triples in the output.")
    private boolean sort;

    @Option(names = "--sort-by-prefix",
            description = "Sort by namespace prefix (only meaningful with --sort).")
    private boolean sortByPrefix;

    @Option(names = "--strip-prefixes",
            description = "Strip namespace prefix declarations from the output.")
    private boolean stripPrefixes;

    // ---- misc --------------------------------------------------------------

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without converting.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            if (configFile != null) {
                loadConfig(configFile);
            }
            applyDefaults();

            if (dryRun) {
                printDryRun();
                return ExitCode.OK;
            }

            if (!validateInputs()) {
                return ExitCode.INVALID_INPUT;
            }

            // Register custom format writers before use
            if ("CIMXML".equalsIgnoreCase(rdfFormatName) || "RDFS_CIMXML".equalsIgnoreCase(rdfFormatName)) {
                CustomRDFFormat.RegisterCustomFormatWriters();
            }

            boolean isUnion = (inputFiles != null && !inputFiles.isEmpty());

            // Detect source format from first input file name (for union) or single input file
            String sourceFileName = isUnion ? inputFiles.get(0).getName()
                                            : (inputFile != null ? inputFile.getName() : null);
            RDFConvertOptions.RDFFormats srcFmt = resolveRdfFormat(sourceFileName, sourceFormat);
            RDFConvertOptions.RDFFormats tgtFmt = resolveRdfFormat(outputFile.getName(), targetFormat);
            RDFFormat rdfFmt = resolveRdfXmlSubFormat(rdfFormatName);

            RDFConvertOptions options = RDFConvertOptions.builder()
                    .sourceFile(isUnion ? null : inputFile)
                    .modelUnionFiles(isUnion ? inputFiles : null)
                    .modelUnionFlag(isUnion)
                    .sourceFormat(srcFmt)
                    .targetFormat(tgtFmt)
                    .xmlBase(xmlBase)
                    .rdfXmlFormat(tgtFmt == RDFConvertOptions.RDFFormats.RDFXML ? rdfFmt : null)
                    .showXmlDeclaration("true")
                    .showDoctypeDeclaration("false")
                    .tabCharacter("2")
                    .relativeURIs("same-document")
                    .modelUnionFlagDetailed(false)
                    .sortRDF(sort ? "true" : "false")
                    .rdfSortOptions(sortByPrefix ? "true" : "false")
                    .stripPrefixes(stripPrefixes)
                    .convertInstanceData("false")
                    .modelUnionFixPackage(false)
                    .keepOntologyHeaders(true)
                    .inheritanceOnly(false)
                    .inheritanceList(false)
                    .inheritanceListConcrete(false)
                    .addOwl(false)
                    .modelUnionDetailedFiles(null)
                    .build();

            RDFConverter converter = new RDFConverter(options);
            converter.convert();

            // Ensure parent directory exists
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                converter.writeConvertedModel(fos);
            }

            System.out.println("[OK] Converted output written to: " + outputFile.getAbsolutePath());
            return ExitCode.OK;

        } catch (Exception ex) {
            System.err.println("[ERROR] Conversion failed: " + ex.getMessage());
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

        if (inputFile == null) {
            String v = root.path("input").asText(null);
            if (v != null && !v.isBlank()) inputFile = resolveRelative(configDir, v);
        }
        if (inputFiles == null || inputFiles.isEmpty()) {
            JsonNode arr = root.path("inputFiles");
            if (arr.isArray() && arr.size() > 0) {
                inputFiles = new ArrayList<>();
                for (JsonNode el : arr) {
                    String v = el.asText(null);
                    if (v != null && !v.isBlank()) inputFiles.add(resolveRelative(configDir, v));
                }
            }
        }
        if (outputFile == null) {
            String v = root.path("output").asText(null);
            if (v != null && !v.isBlank()) outputFile = resolveRelative(configDir, v);
        }
        if (sourceFormat == null) {
            String v = root.path("sourceFormat").asText(null);
            if (v != null && !v.isBlank()) sourceFormat = v;
        }
        if (targetFormat == null) {
            String v = root.path("targetFormat").asText(null);
            if (v != null && !v.isBlank()) targetFormat = v;
        }
        if (xmlBase == null) {
            String v = root.path("xmlBase").asText(null);
            if (v != null && !v.isBlank()) xmlBase = v;
        }
        if (rdfFormatName == null) {
            String v = root.path("rdfFormat").asText(null);
            if (v != null && !v.isBlank()) rdfFormatName = v;
        }
        // boolean flags — only set from config if not already set on CLI
        if (!sort) {
            JsonNode n = root.path("sort");
            if (!n.isMissingNode() && !n.isNull()) sort = n.asBoolean(false);
        }
        if (!sortByPrefix) {
            JsonNode n = root.path("sortByPrefix");
            if (!n.isMissingNode() && !n.isNull()) sortByPrefix = n.asBoolean(false);
        }
        if (!stripPrefixes) {
            JsonNode n = root.path("stripPrefixes");
            if (!n.isMissingNode() && !n.isNull()) stripPrefixes = n.asBoolean(false);
        }
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void applyDefaults() {
        if (xmlBase == null) xmlBase = "";
        if (rdfFormatName == null) rdfFormatName = "RDFXML_PLAIN";
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = true;
        boolean hasInput = (inputFile != null) || (inputFiles != null && !inputFiles.isEmpty());
        if (!hasInput) {
            System.err.println("[ERROR] Either --input or --input-files is required.");
            ok = false;
        } else if (inputFile != null && inputFiles != null && !inputFiles.isEmpty()) {
            System.err.println("[ERROR] --input and --input-files are mutually exclusive; specify one.");
            ok = false;
        }
        if (inputFile != null && (!inputFile.exists() || !inputFile.isFile())) {
            System.err.println("[ERROR] Input file not found: " + inputFile.getAbsolutePath());
            ok = false;
        }
        if (inputFiles != null) {
            for (File f : inputFiles) {
                if (!f.exists() || !f.isFile()) {
                    System.err.println("[ERROR] Input file not found: " + f.getAbsolutePath());
                    ok = false;
                }
            }
        }
        if (outputFile == null) {
            System.err.println("[ERROR] --output is required.");
            ok = false;
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // Format helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve RDF format from a CLI/config override string, falling back to auto-detection
     * from the file extension.
     */
    private static RDFConvertOptions.RDFFormats resolveRdfFormat(String filename, String override) {
        if (override != null && !override.isBlank()) {
            return switch (override.toUpperCase()) {
                case "TURTLE" -> RDFConvertOptions.RDFFormats.TURTLE;
                case "JSONLD" -> RDFConvertOptions.RDFFormats.JSONLD;
                default -> RDFConvertOptions.RDFFormats.RDFXML;
            };
        }
        // Auto-detect from extension
        if (filename == null) return RDFConvertOptions.RDFFormats.RDFXML;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".ttl")) return RDFConvertOptions.RDFFormats.TURTLE;
        if (lower.endsWith(".jsonld")) return RDFConvertOptions.RDFFormats.JSONLD;
        return RDFConvertOptions.RDFFormats.RDFXML; // .xml, .rdf default
    }

    /** Resolve the RDF/XML sub-format from the --rdf-format flag value. */
    private static RDFFormat resolveRdfXmlSubFormat(String name) {
        if (name == null) return RDFFormat.RDFXML_PLAIN;
        return switch (name.toUpperCase()) {
            case "RDFXML_ABBREV" -> RDFFormat.RDFXML_ABBREV;
            case "RDFXML_PRETTY" -> RDFFormat.RDFXML_PRETTY;
            case "CIMXML" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
            case "RDFS_CIMXML" -> CustomRDFFormat.RDFXML_CUSTOM_PLAIN;
            default -> RDFFormat.RDFXML_PLAIN;
        };
    }

    // -------------------------------------------------------------------------
    // Dry-run output
    // -------------------------------------------------------------------------

    private void printDryRun() {
        System.out.println("=== CimPal Convert -- Dry Run (no conversion executed) ===");
        System.out.println("  input        : " + abs(inputFile));
        if (inputFiles != null && !inputFiles.isEmpty()) {
            System.out.println("  inputFiles   : " + inputFiles.stream()
                    .map(File::getAbsolutePath).collect(Collectors.joining(", ")));
        } else {
            System.out.println("  inputFiles   : (not set)");
        }
        System.out.println("  output       : " + abs(outputFile));
        System.out.println("  sourceFormat : " + (sourceFormat != null ? sourceFormat : "(auto-detect)"));
        System.out.println("  targetFormat : " + (targetFormat != null ? targetFormat : "(auto-detect)"));
        System.out.println("  xmlBase      : " + xmlBase);
        System.out.println("  rdfFormat    : " + rdfFormatName);
        System.out.println("  sort         : " + sort);
        System.out.println("  sortByPrefix : " + sortByPrefix);
        System.out.println("  stripPrefixes: " + stripPrefixes);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        if (p.isAbsolute()) return p.toFile();
        return configDir.resolve(p).normalize().toFile();
    }

    private static String abs(File f) {
        return f == null ? "(not set)" : f.getAbsolutePath();
    }
}
