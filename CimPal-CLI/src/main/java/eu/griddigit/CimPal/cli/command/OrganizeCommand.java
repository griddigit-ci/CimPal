package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.shacl_tools.ShaclOrganizer;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code organize} subcommand — split and reorganize SHACL constraint files into a
 * per-document structure defined by an Excel mapping template.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load each SHACL file from {@code --shacl-files}</li>
 *   <li>Load the Excel mapping template from {@code --template-xlsx} (sheet 0)</li>
 *   <li>Create {@code --output-dir}</li>
 *   <li>Call {@link ShaclOrganizer#splitShaclPerXlsInput} to split and write files</li>
 * </ol>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — success
 *   <li>2 — missing or bad input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "organize",
        mixinStandardHelpOptions = true,
        description = "Reorganize SHACL constraint files per an Excel mapping template.",
        sortOptions = false
)
public class OrganizeCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- inputs ------------------------------------------------------------

    @Option(names = "--shacl-files",
            description = "SHACL .ttl/.rdf files to organize (comma-separated, required).",
            split = ",")
    private List<File> shaclFiles;

    @Option(names = "--template-xlsx",
            description = "Excel template (.xlsx) defining the output structure (required).")
    private File templateXlsx;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output-dir",
            description = "Root directory where reorganized files are written (required).")
    private File outputDir;

    // ---- misc --------------------------------------------------------------

    @Option(names = "--xml-base",
            description = "Base URI used when loading SHACL files (default: empty string).")
    private String xmlBase;

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            if (configFile != null) loadConfig(configFile);
            applyDefaults();

            if (dryRun) { printDryRun(); return ExitCode.OK; }

            if (!validateInputs()) return ExitCode.INVALID_INPUT;

            // 1. Load all SHACL models
            List<Model> shapeModels = new ArrayList<>();
            for (File f : shaclFiles) {
                System.err.println("[INFO] Loading SHACL file: " + f.getAbsolutePath());
                Model model = ModelFactory.createDefaultModel();
                try (InputStream in = new FileInputStream(f)) {
                    Lang lang = detectLang(f.getName());
                    RDFDataMgr.read(model, in, lang);
                }
                shapeModels.add(model);
            }

            // 2. Load Excel template
            System.err.println("[INFO] Loading template: " + templateXlsx.getAbsolutePath());
            ArrayList<Object> inputXLSdata = ExcelTools.importXLSX(templateXlsx.getAbsolutePath(), 0);

            // 3. Create output directory
            Path outPath = outputDir.toPath();
            Files.createDirectories(outPath);

            // 4. Split and write
            System.err.println("[INFO] Organizing constraints into: " + outPath);
            ShaclOrganizer.splitShaclPerXlsInput(inputXLSdata, shapeModels, outPath);

            System.out.println("[OK] Organize complete. Files written to: " + outPath);
            return ExitCode.OK;

        } catch (Exception ex) {
            System.err.println("[ERROR] organize failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    // -------------------------------------------------------------------------
    // Config loading
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
        if (shaclFiles == null) {
            JsonNode n = root.path("shaclFiles");
            if (n.isArray()) {
                shaclFiles = new ArrayList<>();
                for (JsonNode item : n) shaclFiles.add(resolveRelative(configDir, item.asText()));
            }
        }
        if (templateXlsx == null) { String v = root.path("templateXlsx").asText(null); if (v != null && !v.isBlank()) templateXlsx = resolveRelative(configDir, v); }
        if (outputDir == null)    { String v = root.path("outputDir").asText(null);    if (v != null && !v.isBlank()) outputDir    = resolveRelative(configDir, v); }
        if (xmlBase == null)      { String v = root.path("xmlBase").asText(null);      if (v != null && !v.isBlank()) xmlBase      = v; }
    }

    private void applyDefaults() {
        if (xmlBase == null) xmlBase = "";
    }

    private boolean validateInputs() {
        boolean ok = true;
        if (shaclFiles == null || shaclFiles.isEmpty()) {
            System.err.println("[ERROR] --shacl-files is required."); ok = false;
        } else {
            for (File f : shaclFiles) {
                if (!f.exists()) { System.err.println("[ERROR] SHACL file not found: " + f.getAbsolutePath()); ok = false; }
            }
        }
        if (templateXlsx == null || !templateXlsx.exists()) {
            System.err.println("[ERROR] --template-xlsx is required and must exist: " + (templateXlsx == null ? "(not set)" : templateXlsx.getAbsolutePath()));
            ok = false;
        }
        if (outputDir == null) {
            System.err.println("[ERROR] --output-dir is required."); ok = false;
        }
        return ok;
    }

    private void printDryRun() {
        System.out.println("=== CimPal organize -- Dry Run ===");
        System.out.println("  shacl-files   : " + (shaclFiles == null ? "(not set)" : shaclFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
        System.out.println("  template-xlsx : " + abs(templateXlsx));
        System.out.println("  output-dir    : " + abs(outputDir));
        System.out.println("  xml-base      : " + xmlBase);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Lang detectLang(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".ttl"))  return Lang.TURTLE;
        if (lower.endsWith(".jsonld")) return Lang.JSONLD;
        return Lang.RDFXML; // default for .rdf, .xml
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : configDir.resolve(p).normalize().toFile();
    }

    private static String abs(File f) { return f == null ? "(not set)" : f.getAbsolutePath(); }
}
