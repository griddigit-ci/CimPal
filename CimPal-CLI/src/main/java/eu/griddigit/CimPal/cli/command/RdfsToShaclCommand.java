package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.converters.SHACLFromRDF;
import eu.griddigit.cimpal.core.models.RDFtoSHACLOptions;
import eu.griddigit.cimpal.core.models.RdfsModelDefinition;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ValidationReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code rdfs2shacl} subcommand — generate SHACL shape files from RDFS profile definitions.
 *
 * <p>Reads one or more RDFS {@code .rdf} profile files and produces SHACL shape graphs in
 * Turtle format, one output file per input profile.
 *
 * <p>The {@code --io-prefix} and {@code --io-uri} flags identify the IdentifiedObject mRID
 * property in the profile.  For standard CIM/CGMES profiles the defaults
 * ({@code mRID} / {@code http://iec.ch/TC57/CIM100#IdentifiedObject.mRID}) are correct;
 * override them when working with a non-standard namespace.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — SHACL shapes generated successfully
 *   <li>2 — missing or bad input
 *   <li>3 — conversion failed (internal error)
 * </ul>
 */
@Command(
        name = "rdfs2shacl",
        mixinStandardHelpOptions = true,
        description = "Generate SHACL shape files from RDFS CIM profile definitions.",
        sortOptions = false
)
public class RdfsToShaclCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- input paths -------------------------------------------------------

    @Option(names = "--rdfs-files",
            description = "RDFS profile .rdf files (comma-separated, required).",
            split = ",")
    private List<File> rdfsFiles;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output-dir",
            description = "Output directory for the generated .ttl shape files (required).")
    private File outputDir;

    // ---- RDFS / SHACL options ----------------------------------------------

    @Option(names = "--rdfs-format",
            description = "RDFS format version: 2019 or 2020 (default: 2020).")
    private String rdfsFormat;

    @Option(names = "--cims-namespace",
            description = "CIMS extensions namespace URI (default: http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#).")
    private String cimsNamespace;

    @Option(names = "--io-prefix",
            description = "IdentifiedObject mRID property local name (default: mRID).")
    private String ioPrefix;

    @Option(names = "--io-uri",
            description = "IdentifiedObject mRID property full URI (default: http://iec.ch/TC57/CIM100#IdentifiedObject.mRID).")
    private String ioUri;

    @Option(names = "--shapes-base-uri",
            description = "Base URI for the generated shape graphs (default: empty).")
    private String shapesBaseUri;

    @Option(names = "--shapes-namespace-prefix",
            description = "Prefix for the SHACL shapes namespace (default: empty).")
    private String shapesNsPrefix;

    @Option(names = "--shapes-namespace-uri",
            description = "URI for the SHACL shapes namespace (default: empty).")
    private String shapesNsUri;

    // ---- boolean flags -----------------------------------------------------

    @Option(names = "--closed-shapes",
            description = "Generate sh:closed shapes.")
    private boolean closedShapes;

    @Option(names = "--split-datatypes",
            description = "Write a separate datatype constraint file per profile.")
    private boolean splitDatatypes;

    @Option(names = "--export-inherit-tree",
            description = "Write an inheritance model file per profile.")
    private boolean exportInheritTree;

    @Option(names = "--validate-shapes",
            description = "Run SHACL-SHACL validation on the generated shapes and print the results.")
    private boolean validateShapes;

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

            // Ensure output directory exists
            Path outPath = outputDir.toPath();
            Files.createDirectories(outPath);

            // Load each RDFS profile into a Jena model
            List<Model> loadedModels = new ArrayList<>();
            List<RdfsModelDefinition> modelDefinitions = new ArrayList<>();

            for (File rdfsFile : rdfsFiles) {
                System.out.println("[INFO] Loading RDFS profile: " + rdfsFile.getAbsolutePath());
                Model model = ModelFactory.createDefaultModel();
                try (InputStream in = new FileInputStream(rdfsFile)) {
                    RDFDataMgr.read(model, in, "", Lang.RDFXML);
                }
                loadedModels.add(model);
                modelDefinitions.add(buildDefinition(rdfsFile, model, shapesBaseUri));
            }

            boolean is2019 = "2019".equals(rdfsFormat);

            RDFtoSHACLOptions options = RDFtoSHACLOptions.builder()
                    .rdfsModels(new ArrayList<>(loadedModels))
                    .rdfsModelDefinitions(modelDefinitions)
                    .rdfsFormatShapes(is2019
                            ? RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2019
                            : RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2020)
                    .shaclOutputFormat(RDFtoSHACLOptions.SerializationFormat.TURTLE)
                    .cimsNamespace(cimsNamespace)
                    .iOprefix(ioPrefix)
                    .iOuri(ioUri)
                    .shaclCommonPref(shapesNsPrefix)
                    .shaclCommonURI(shapesNsUri)
                    .excludeMRID(false)
                    .closedShapes(closedShapes)
                    .splitDatatypes(splitDatatypes)
                    .associationValueTypeOption(false)
                    .associationValueTypeOptionSingle(false)
                    .shapesOnAbstractOption(false)
                    .exportInheritTree(exportInheritTree)
                    .shaclURIDatatypeAsResource(false)
                    .shaclSkipNcPropertyReference(false)
                    .baseprofilesshaclglag(false)
                    .baseprofilesshaclignorens(false)
                    .baseprofilesshaclglag2nd(false)
                    .baseprofilesshaclglag3rd(false)
                    .shaclFlagInverse(false)
                    .shaclFlagCount(false)
                    .shaclFlagCountDefaultURI(false)
                    .baseModelFiles1(null)
                    .baseModelFiles2(null)
                    .baseModelFiles3(null)
                    .build();

            SHACLFromRDF converter = new SHACLFromRDF(options);
            System.out.println("[INFO] Running RDFS-to-SHACL conversion...");
            converter.convert();

            System.out.println("[INFO] Saving shape models to: " + outPath.toAbsolutePath());
            converter.saveShapeModel(outPath);

            if (splitDatatypes) {
                System.out.println("[INFO] Saving datatype shape models...");
                converter.saveShapeModelDT(outPath);
            }

            if (exportInheritTree) {
                System.out.println("[INFO] Saving inheritance models...");
                converter.saveInheritanceModel(outPath);
            }

            if (validateShapes) {
                System.out.println("[INFO] Validating generated shapes with SHACL-SHACL...");
                converter.validateShapeModels();
                List<ValidationReport> reports = converter.getValidationReports();
                int i = 0;
                for (ValidationReport report : reports) {
                    String name = modelDefinitions.get(i).getModelName();
                    if (report.conforms()) {
                        System.out.println("  [CONFORMING]     " + name);
                    } else {
                        System.out.println("  [NON-CONFORMING] " + name);
                    }
                    i++;
                }
            }

            System.out.println("[OK] SHACL shapes written to: " + outPath.toAbsolutePath());
            return ExitCode.OK;

        } catch (Exception ex) {
            System.err.println("[ERROR] RDFS-to-SHACL conversion failed: " + ex.getMessage());
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

        if (rdfsFiles == null || rdfsFiles.isEmpty()) {
            JsonNode arr = root.path("rdfsFiles");
            if (arr.isArray() && arr.size() > 0) {
                rdfsFiles = new ArrayList<>();
                for (JsonNode el : arr) {
                    String v = el.asText(null);
                    if (v != null && !v.isBlank()) rdfsFiles.add(resolveRelative(configDir, v));
                }
            }
        }
        if (outputDir == null) {
            String v = root.path("outputDir").asText(null);
            if (v != null && !v.isBlank()) outputDir = resolveRelative(configDir, v);
        }
        if (rdfsFormat == null) {
            String v = root.path("rdfsFormat").asText(null);
            if (v != null && !v.isBlank()) rdfsFormat = v;
        }
        if (cimsNamespace == null) {
            String v = root.path("cimsNamespace").asText(null);
            if (v != null && !v.isBlank()) cimsNamespace = v;
        }
        if (ioPrefix == null) {
            String v = root.path("ioPrefix").asText(null);
            if (v != null && !v.isBlank()) ioPrefix = v;
        }
        if (ioUri == null) {
            String v = root.path("ioUri").asText(null);
            if (v != null && !v.isBlank()) ioUri = v;
        }
        if (shapesBaseUri == null) {
            String v = root.path("shapesBaseUri").asText(null);
            if (v != null && !v.isBlank()) shapesBaseUri = v;
        }
        if (shapesNsPrefix == null) {
            String v = root.path("shapesNsPrefix").asText(null);
            if (v != null && !v.isBlank()) shapesNsPrefix = v;
        }
        if (shapesNsUri == null) {
            String v = root.path("shapesNsUri").asText(null);
            if (v != null && !v.isBlank()) shapesNsUri = v;
        }
        if (!closedShapes) {
            JsonNode n = root.path("closedShapes");
            if (!n.isMissingNode() && !n.isNull()) closedShapes = n.asBoolean(false);
        }
        if (!splitDatatypes) {
            JsonNode n = root.path("splitDatatypes");
            if (!n.isMissingNode() && !n.isNull()) splitDatatypes = n.asBoolean(false);
        }
        if (!exportInheritTree) {
            JsonNode n = root.path("exportInheritTree");
            if (!n.isMissingNode() && !n.isNull()) exportInheritTree = n.asBoolean(false);
        }
        if (!validateShapes) {
            JsonNode n = root.path("validateShapes");
            if (!n.isMissingNode() && !n.isNull()) validateShapes = n.asBoolean(false);
        }
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void applyDefaults() {
        if (rdfsFormat == null) rdfsFormat = "2020";
        if (cimsNamespace == null)
            cimsNamespace = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        // iOprefix and iOuri must be non-empty (RDFtoSHACLOptions.build() validates this)
        if (ioPrefix == null || ioPrefix.isBlank()) ioPrefix = "mRID";
        if (ioUri == null || ioUri.isBlank())
            ioUri = "http://iec.ch/TC57/CIM100#IdentifiedObject.mRID";
        if (shapesBaseUri == null) shapesBaseUri = "";
        if (shapesNsPrefix == null) shapesNsPrefix = "";
        if (shapesNsUri == null) shapesNsUri = "";
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = true;
        if (rdfsFiles == null || rdfsFiles.isEmpty()) {
            System.err.println("[ERROR] --rdfs-files is required.");
            ok = false;
        } else {
            for (File f : rdfsFiles) {
                if (!f.exists() || !f.isFile()) {
                    System.err.println("[ERROR] RDFS file not found: " + f.getAbsolutePath());
                    ok = false;
                }
            }
        }
        if (outputDir == null) {
            System.err.println("[ERROR] --output-dir is required.");
            ok = false;
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // Build RdfsModelDefinition from a loaded Jena model
    // -------------------------------------------------------------------------

    private static RdfsModelDefinition buildDefinition(File rdfsFile, Model model, String shapesBaseUri) {
        String modelName = rdfsFile.getName().replaceFirst("\\.[^.]+$", "");

        String nsPrefix = "";
        String nsUri = "";

        // Try to extract namespace info from owl:Ontology triple
        StmtIterator ontIter = model.listStatements(null,
                ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                ResourceFactory.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        if (ontIter.hasNext()) {
            Resource ontRes = ontIter.next().getSubject();
            String ontUri = ontRes.getURI();
            if (ontUri != null && !ontUri.isBlank()) {
                nsUri = ontUri.endsWith("#") ? ontUri : ontUri + "#";
                // Try to find the matching prefix from the model prefix map
                for (Map.Entry<String, String> e : model.getNsPrefixMap().entrySet()) {
                    if (e.getValue().equals(nsUri) || e.getValue().startsWith(ontRes.getNameSpace())) {
                        nsPrefix = e.getKey();
                        break;
                    }
                }
            }
        }

        // Extract owl:imports
        String owlImport = "";
        StmtIterator impIter = model.listStatements(null,
                ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#imports"),
                (RDFNode) null);
        if (impIter.hasNext()) {
            owlImport = impIter.next().getObject().toString();
        }

        return new RdfsModelDefinition(modelName, nsPrefix, nsUri,
                shapesBaseUri != null ? shapesBaseUri : "", owlImport);
    }

    // -------------------------------------------------------------------------
    // Dry-run output
    // -------------------------------------------------------------------------

    private void printDryRun() {
        System.out.println("=== CimPal RDFS-to-SHACL -- Dry Run (no conversion executed) ===");
        if (rdfsFiles != null && !rdfsFiles.isEmpty()) {
            System.out.println("  rdfsFiles         : " + rdfsFiles.stream()
                    .map(File::getAbsolutePath).collect(Collectors.joining(", ")));
        } else {
            System.out.println("  rdfsFiles         : (not set)");
        }
        System.out.println("  outputDir         : " + abs(outputDir));
        System.out.println("  rdfsFormat        : " + rdfsFormat);
        System.out.println("  cimsNamespace     : " + cimsNamespace);
        System.out.println("  ioPrefix          : " + ioPrefix);
        System.out.println("  ioUri             : " + ioUri);
        System.out.println("  shapesBaseUri     : " + shapesBaseUri);
        System.out.println("  shapesNsPrefix    : " + shapesNsPrefix);
        System.out.println("  shapesNsUri       : " + shapesNsUri);
        System.out.println("  closedShapes      : " + closedShapes);
        System.out.println("  splitDatatypes    : " + splitDatatypes);
        System.out.println("  exportInheritTree : " + exportInheritTree);
        System.out.println("  validateShapes    : " + validateShapes);
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
