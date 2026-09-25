package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.generators.InstanceDataBuilder;
import eu.griddigit.cimpal.core.generators.InstanceDataWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * {@code gen-instances} subcommand — generate CIM/RDF instance data from an Excel template.
 *
 * <p>Reads a CimPal generation template (.xlsx) and serialises the resulting Jena model as
 * an RDF/XML file. All model-building and serialisation logic lives in {@code CimPal-Core}
 * and is therefore usable headlessly without JavaFX.
 *
 * <p>Flags can be supplied via a JSON config file ({@code --config}); individual flags on the
 * command line override values from the file.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — instance data written successfully
 *   <li>2 — bad or missing input
 *   <li>3 — internal error during model building or serialisation
 * </ul>
 *
 * <p>JSON config keys: {@code templateXlsx}, {@code output}, {@code xmlBase},
 * {@code stripPrefixes}, {@code exportExtensions}.
 */
@Command(
        name = "gen-instances",
        mixinStandardHelpOptions = true,
        description = "Generate CIM/RDF instance data from a CimPal Excel template.",
        sortOptions = false
)
public class GenInstancesCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- required inputs ---------------------------------------------------

    @Option(names = "--template-xlsx",
            description = "CimPal Excel generation template file (.xlsx). Required.")
    private File templateXlsx;

    @Option(names = "--output",
            description = "Output RDF/XML file path. Required.")
    private File output;

    // ---- optional inputs ---------------------------------------------------

    @Option(names = "--xml-base",
            description = "RDF base URI.  Default: http://iec.ch/TC57/CIM100 (CGMES 3.0).")
    private String xmlBase;

    @Option(names = "--strip-prefixes",
            description = "Strip unused namespace prefixes from the output model.")
    private Boolean stripPrefixes;

    @Option(names = "--export-extensions",
            description = "Include columns marked as extensions in the output.")
    private Boolean exportExtensions;

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without generating output.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            // 1. Load config file if provided
            if (configFile != null) {
                loadConfig(configFile);
            }

            // 2. Apply defaults
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

            // 5. Build the Jena model from the Excel template
            System.out.println("[INFO] Building instance data from: " + templateXlsx.getAbsolutePath());
            InstanceDataBuilder.BuildResult result =
                    InstanceDataBuilder.buildFromXls(xmlBase, templateXlsx, stripPrefixes, exportExtensions);

            // 6. Configure serialisation properties
            result.saveProperties().put("filename", output.getName());
            // useFileDialog must be false for headless operation (already default)
            result.saveProperties().put("useFileDialog", false);
            result.saveProperties().put("fileFolder", "");

            // 7. Ensure the output directory exists
            File outputDir = output.getAbsoluteFile().getParentFile();
            if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
                System.err.println("[ERROR] Could not create output directory: " + outputDir.getAbsolutePath());
                return ExitCode.INVALID_INPUT;
            }

            // 8. Write RDF/XML
            System.out.println("[INFO] Writing instance data to: " + output.getAbsolutePath());
            try (OutputStream out = new FileOutputStream(output)) {
                InstanceDataWriter.write(result.model(), result.saveProperties(), out);
            }

            System.out.println("[INFO] Instance data written to: " + output.getAbsolutePath());
            return ExitCode.OK;

        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.err.println("[ERROR] Invalid input: " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
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

        if (templateXlsx == null) {
            String v = root.path("templateXlsx").asText(null);
            if (v != null && !v.isBlank()) templateXlsx = resolveRelative(configDir, v);
        }
        if (output == null) {
            String v = root.path("output").asText(null);
            if (v != null && !v.isBlank()) output = resolveRelative(configDir, v);
        }
        if (xmlBase == null) {
            String v = root.path("xmlBase").asText(null);
            if (v != null && !v.isBlank()) xmlBase = v;
        }
        if (stripPrefixes == null) {
            JsonNode n = root.path("stripPrefixes");
            if (!n.isMissingNode() && !n.isNull()) stripPrefixes = n.asBoolean(false);
        }
        if (exportExtensions == null) {
            JsonNode n = root.path("exportExtensions");
            if (!n.isMissingNode() && !n.isNull()) exportExtensions = n.asBoolean(false);
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
        if (xmlBase == null)           xmlBase           = "http://iec.ch/TC57/CIM100";
        if (stripPrefixes == null)     stripPrefixes     = false;
        if (exportExtensions == null)  exportExtensions  = false;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = true;

        if (templateXlsx == null) {
            System.err.println("[ERROR] --template-xlsx is required.");
            ok = false;
        } else if (!templateXlsx.exists() || !templateXlsx.isFile()) {
            System.err.println("[ERROR] Template file not found: " + templateXlsx.getAbsolutePath());
            ok = false;
        }

        if (output == null) {
            System.err.println("[ERROR] --output is required.");
            ok = false;
        }

        return ok;
    }

    // -------------------------------------------------------------------------
    // Dry-run output
    // -------------------------------------------------------------------------

    private void printDryRun() {
        System.out.println("=== CimPal gen-instances -- Dry Run (no output generated) ===");
        System.out.println("  templateXlsx     : " + abs(templateXlsx));
        System.out.println("  output           : " + abs(output));
        System.out.println("  xmlBase          : " + xmlBase);
        System.out.println("  stripPrefixes    : " + stripPrefixes);
        System.out.println("  exportExtensions : " + exportExtensions);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String abs(File f) {
        return f == null ? "(not set)" : f.getAbsolutePath();
    }
}
