/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofile;
import eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofileCIMTool;
import eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes;
import eu.griddigit.cimpal.core.interfaces.IRDFComparator;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code compare} subcommand — compare two RDF model files and report differences.
 *
 * <p>Supports three comparison algorithms selectable via {@code --compare-type}:
 * <ul>
 *   <li>{@code rdfs}       — compare two RDFS CIM profiles (classes, attributes, associations)
 *   <li>{@code rdfs-cimtool} — RDFS comparison adapted for CIMTool-generated profiles
 *   <li>{@code shacl}      — compare two SHACL shape graphs
 *   <li>{@code auto}       — detect from file extension: {@code .ttl} → shacl, {@code .rdf} → rdfs
 * </ul>
 *
 * <p>Results can be written to an Excel (.xlsx) file, a CSV file, or printed to stdout in
 * text or JSON format.
 *
 * <p><b>Note on {@code hasDifference()}:</b> {@link RDFCompareResult#hasDifference()} returns
 * {@code true} when the entries list is <em>empty</em> (inverted semantics).  This command uses
 * {@code result.getEntries().isEmpty()} directly to avoid confusion.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — files are identical (no differences found)
 *   <li>1 — differences found
 *   <li>2 — missing or bad input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "compare",
        mixinStandardHelpOptions = true,
        description = "Compare two RDF model files and report differences.",
        sortOptions = false
)
public class CompareCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- input files -------------------------------------------------------

    @Option(names = "--file-a",
            description = "First RDF file (required).")
    private File fileA;

    @Option(names = "--file-b",
            description = "Second RDF file (required).")
    private File fileB;

    // ---- comparison options ------------------------------------------------

    @Option(names = "--compare-type",
            description = "Comparison algorithm: rdfs, rdfs-cimtool, shacl, or auto (default: auto).")
    private String compareType;

    @Option(names = "--normalize-cim-version",
            description = "Rename the 'cim' namespace in file-b to match file-a before comparing.")
    private boolean normalizeCimVersion;

    @Option(names = "--normalize-profile-ns",
            description = "Rename the profile namespace in file-b to match file-a.")
    private boolean normalizeProfileNs;

    @Option(names = "--ns-prefix",
            description = "Namespace prefix to normalize when --normalize-profile-ns is set (default: empty).")
    private String nsPrefix;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output",
            description = "Write results to a file (.xlsx or .csv); if omitted, prints to stdout.")
    private File outputFile;

    @Option(names = "--format",
            description = "Stdout format when --output is not set: text (default), json, or csv.")
    private String format;

    // ---- misc --------------------------------------------------------------

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without comparing.")
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

            // Load models
            System.err.println("[INFO] Loading file A: " + fileA.getAbsolutePath());
            Model modelA = loadModel(fileA);
            System.err.println("[INFO] Loading file B: " + fileB.getAbsolutePath());
            Model modelB = loadModel(fileB);

            // Namespace normalisation
            if (normalizeCimVersion) {
                String cimNsA = modelA.getNsPrefixURI("cim");
                String cimNsB = modelB.getNsPrefixURI("cim");
                if (cimNsA != null && cimNsB != null && !cimNsA.equals(cimNsB)) {
                    System.err.println("[INFO] Normalising CIM namespace: " + cimNsB + " → " + cimNsA);
                    modelB = renameNs(modelB, cimNsB, cimNsA);
                }
            }
            if (normalizeProfileNs && nsPrefix != null && !nsPrefix.isBlank()) {
                String nsA = modelA.getNsPrefixURI(nsPrefix);
                String nsB = modelB.getNsPrefixURI(nsPrefix);
                if (nsA != null && nsB != null && !nsA.equals(nsB)) {
                    System.err.println("[INFO] Normalising profile namespace '" + nsPrefix
                            + "': " + nsB + " → " + nsA);
                    modelB = renameNs(modelB, nsB, nsA);
                }
            }

            // Select comparator
            String resolvedType = resolveCompareType(compareType, fileA.getName());
            System.err.println("[INFO] Running comparison (type=" + resolvedType + ")...");
            IRDFComparator comparator = switch (resolvedType) {
                case "rdfs-cimtool" -> new ComparisonRDFSprofileCIMTool();
                case "shacl" -> new ComparisonSHACLshapes();
                default -> new ComparisonRDFSprofile();
            };

            RDFCompareResult result = comparator.compare(modelA, modelB);
            List<RDFCompareResultEntry> entries = result.getEntries();

            // NOTE: result.hasDifference() returns true when entries is EMPTY (inverted).
            // We use entries.isEmpty() directly.
            boolean identical = entries.isEmpty();

            // Output results
            if (outputFile != null) {
                writeToFile(entries, outputFile, fileA, fileB, resolvedType);
                System.out.println("[OK] Comparison results written to: " + outputFile.getAbsolutePath());
                System.out.println("     Total differences: " + entries.size());
            } else {
                writeToStdout(entries, format, fileA, fileB, resolvedType);
            }

            if (identical) {
                System.err.println("[OK] Files are identical — no differences found.");
                return ExitCode.OK;
            } else {
                System.err.println("[OK] Found " + entries.size() + " difference(s).");
                return ExitCode.VIOLATIONS;
            }

        } catch (Exception ex) {
            System.err.println("[ERROR] Comparison failed: " + ex.getMessage());
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

        if (fileA == null) {
            String v = root.path("fileA").asText(null);
            if (v != null && !v.isBlank()) fileA = resolveRelative(configDir, v);
        }
        if (fileB == null) {
            String v = root.path("fileB").asText(null);
            if (v != null && !v.isBlank()) fileB = resolveRelative(configDir, v);
        }
        if (compareType == null) {
            String v = root.path("compareType").asText(null);
            if (v != null && !v.isBlank()) compareType = v;
        }
        if (!normalizeCimVersion) {
            JsonNode n = root.path("normalizeCimVersion");
            if (!n.isMissingNode() && !n.isNull()) normalizeCimVersion = n.asBoolean(false);
        }
        if (!normalizeProfileNs) {
            JsonNode n = root.path("normalizeProfileNs");
            if (!n.isMissingNode() && !n.isNull()) normalizeProfileNs = n.asBoolean(false);
        }
        if (nsPrefix == null) {
            String v = root.path("nsPrefix").asText(null);
            if (v != null && !v.isBlank()) nsPrefix = v;
        }
        if (outputFile == null) {
            String v = root.path("output").asText(null);
            if (v != null && !v.isBlank()) outputFile = resolveRelative(configDir, v);
        }
        if (format == null) {
            String v = root.path("format").asText(null);
            if (v != null && !v.isBlank()) format = v;
        }
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private void applyDefaults() {
        if (compareType == null) compareType = "auto";
        if (nsPrefix == null) nsPrefix = "";
        if (format == null) format = "text";
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    private boolean validateInputs() {
        boolean ok = true;
        if (fileA == null) {
            System.err.println("[ERROR] --file-a is required.");
            ok = false;
        } else if (!fileA.exists() || !fileA.isFile()) {
            System.err.println("[ERROR] File A not found: " + fileA.getAbsolutePath());
            ok = false;
        }
        if (fileB == null) {
            System.err.println("[ERROR] --file-b is required.");
            ok = false;
        } else if (!fileB.exists() || !fileB.isFile()) {
            System.err.println("[ERROR] File B not found: " + fileB.getAbsolutePath());
            ok = false;
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private static Model loadModel(File file) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        String name = file.getName().toLowerCase();
        Lang lang = name.endsWith(".ttl") ? Lang.TURTLE : Lang.RDFXML;
        try (InputStream in = new FileInputStream(file)) {
            RDFDataMgr.read(model, in, lang);
        }
        return model;
    }

    // -------------------------------------------------------------------------
    // Namespace renaming
    // -------------------------------------------------------------------------

    /**
     * Create a new model where every occurrence of {@code oldNs} in subject/predicate/object
     * URIs is replaced with {@code newNs}.
     */
    private static Model renameNs(Model model, String oldNs, String newNs) {
        if (oldNs == null || newNs == null || oldNs.equals(newNs)) return model;
        Model result = ModelFactory.createDefaultModel();
        result.setNsPrefixes(model.getNsPrefixMap());
        model.listStatements().forEachRemaining(stmt -> {
            Resource s = stmt.getSubject().isURIResource()
                    ? ResourceFactory.createResource(stmt.getSubject().getURI().replace(oldNs, newNs))
                    : stmt.getSubject();
            Property p = ResourceFactory.createProperty(
                    stmt.getPredicate().getURI().replace(oldNs, newNs));
            RDFNode o = stmt.getObject().isURIResource()
                    ? ResourceFactory.createResource(stmt.getObject().asResource().getURI().replace(oldNs, newNs))
                    : stmt.getObject();
            result.add(s, p, o);
        });
        return result;
    }

    // -------------------------------------------------------------------------
    // Compare type resolution
    // -------------------------------------------------------------------------

    private static String resolveCompareType(String type, String fileAName) {
        if (!"auto".equalsIgnoreCase(type)) return type.toLowerCase();
        // Auto-detect from file-A extension
        String lower = fileAName.toLowerCase();
        if (lower.endsWith(".ttl")) return "shacl";
        return "rdfs"; // .rdf, .xml default
    }

    // -------------------------------------------------------------------------
    // Output: file (Excel / CSV)
    // -------------------------------------------------------------------------

    private static void writeToFile(List<RDFCompareResultEntry> entries, File outFile,
                                    File fileA, File fileB, String compareType) throws IOException {
        String name = outFile.getName().toLowerCase();
        if (name.endsWith(".xlsx")) {
            writeExcel(entries, outFile);
        } else {
            writeCsvFile(entries, outFile, fileA, fileB);
        }
    }

    private static void writeExcel(List<RDFCompareResultEntry> entries, File outFile) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Comparison");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Type");
            header.createCell(2).setCellValue("Property");
            header.createCell(3).setCellValue("File A");
            header.createCell(4).setCellValue("File B");

            int rowNum = 1;
            for (RDFCompareResultEntry e : entries) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(nvl(e.getItem()));
                row.createCell(1).setCellValue(nvl(e.getRdfType()));
                row.createCell(2).setCellValue(nvl(e.getProperty()));
                row.createCell(3).setCellValue(nvl(e.getValueModelA()));
                row.createCell(4).setCellValue(nvl(e.getValueModelB()));
            }

            // Auto-size columns
            for (int c = 0; c < 5; c++) {
                sheet.autoSizeColumn(c);
            }

            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }
        }
    }

    private static void writeCsvFile(List<RDFCompareResultEntry> entries, File outFile,
                                     File fileA, File fileB) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (PrintWriter pw = new PrintWriter(outFile, StandardCharsets.UTF_8)) {
            pw.println("Item,Type,Property,FileA,FileB");
            for (RDFCompareResultEntry e : entries) {
                pw.printf("%s,%s,%s,%s,%s%n",
                        csvEscape(e.getItem()), csvEscape(e.getRdfType()),
                        csvEscape(e.getProperty()),
                        csvEscape(e.getValueModelA()), csvEscape(e.getValueModelB()));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Output: stdout
    // -------------------------------------------------------------------------

    private static void writeToStdout(List<RDFCompareResultEntry> entries, String format,
                                      File fileA, File fileB, String compareType) {
        switch (format.toLowerCase()) {
            case "json" -> writeJson(entries, fileA, fileB, compareType);
            case "csv"  -> writeCsvStdout(entries);
            default     -> writeText(entries, fileA, fileB);
        }
    }

    private static void writeText(List<RDFCompareResultEntry> entries, File fileA, File fileB) {
        // Column widths
        int itemW  = Math.max(60, entries.stream().mapToInt(e -> len(e.getItem())).max().orElse(60));
        int typeW  = Math.max(20, entries.stream().mapToInt(e -> len(e.getRdfType())).max().orElse(20));
        int propW  = Math.max(30, entries.stream().mapToInt(e -> len(e.getProperty())).max().orElse(30));
        int valW   = 30;

        String header = String.format("%-" + itemW + "s | %-" + typeW + "s | %-" + propW + "s | %-" + valW + "s | %-" + valW + "s",
                "Item", "Type", "Property", "File A", "File B");
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        for (RDFCompareResultEntry e : entries) {
            System.out.printf("%-" + itemW + "s | %-" + typeW + "s | %-" + propW + "s | %-" + valW + "s | %-" + valW + "s%n",
                    truncate(e.getItem(), itemW),
                    truncate(e.getRdfType(), typeW),
                    truncate(e.getProperty(), propW),
                    truncate(e.getValueModelA(), valW),
                    truncate(e.getValueModelB(), valW));
        }
        System.out.println();
        System.out.println("Total: " + entries.size() + " difference(s)");
    }

    private static void writeJson(List<RDFCompareResultEntry> entries,
                                  File fileA, File fileB, String compareType) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"cimpal-compare-result/1\",\n");
        sb.append("  \"fileA\": ").append(jsonStr(fileA.getAbsolutePath())).append(",\n");
        sb.append("  \"fileB\": ").append(jsonStr(fileB.getAbsolutePath())).append(",\n");
        sb.append("  \"compareType\": ").append(jsonStr(compareType)).append(",\n");
        sb.append("  \"totalDifferences\": ").append(entries.size()).append(",\n");
        sb.append("  \"differences\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            RDFCompareResultEntry e = entries.get(i);
            sb.append("    {");
            sb.append("\"item\": ").append(jsonStr(e.getItem())).append(", ");
            sb.append("\"rdfType\": ").append(jsonStr(e.getRdfType())).append(", ");
            sb.append("\"property\": ").append(jsonStr(e.getProperty())).append(", ");
            sb.append("\"valueA\": ").append(jsonStr(e.getValueModelA())).append(", ");
            sb.append("\"valueB\": ").append(jsonStr(e.getValueModelB()));
            sb.append("}");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        System.out.println(sb);
    }

    private static void writeCsvStdout(List<RDFCompareResultEntry> entries) {
        System.out.println("Item,Type,Property,FileA,FileB");
        for (RDFCompareResultEntry e : entries) {
            System.out.printf("%s,%s,%s,%s,%s%n",
                    csvEscape(e.getItem()), csvEscape(e.getRdfType()),
                    csvEscape(e.getProperty()),
                    csvEscape(e.getValueModelA()), csvEscape(e.getValueModelB()));
        }
    }

    // -------------------------------------------------------------------------
    // Dry-run output
    // -------------------------------------------------------------------------

    private void printDryRun() {
        System.out.println("=== CimPal Compare -- Dry Run (no comparison executed) ===");
        System.out.println("  fileA               : " + abs(fileA));
        System.out.println("  fileB               : " + abs(fileB));
        System.out.println("  compareType         : " + compareType);
        System.out.println("  normalizeCimVersion : " + normalizeCimVersion);
        System.out.println("  normalizeProfileNs  : " + normalizeProfileNs);
        System.out.println("  nsPrefix            : " + nsPrefix);
        System.out.println("  output              : " + abs(outputFile));
        System.out.println("  format              : " + format);
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

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
