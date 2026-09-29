/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.comparators.ComparisonInstanceData;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code compare-instances} subcommand — compare two sets of CIM instance-data (IGM/EQ/SSH)
 * models and report differences.
 *
 * <p>Both model sets are merged before comparison.  Classes from SV (state variable),
 * DL (diagram layout), and TP (topology) profiles can be excluded via flags.
 *
 * <p>Results can be written to an Excel (.xlsx) file, a CSV file, or printed to stdout
 * in text or JSON format.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — models are identical (no differences found)
 *   <li>1 — differences found
 *   <li>2 — missing or bad input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "compare-instances",
        mixinStandardHelpOptions = true,
        description = "Compare two sets of CIM instance-data model files and report differences.",
        sortOptions = false
)
public class CompareInstancesCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- input files -------------------------------------------------------

    @Option(names = "--models-a",
            description = "First set of instance-data files (comma-separated).",
            split = ",")
    private List<File> modelsA;

    @Option(names = "--models-b",
            description = "Second set of instance-data files (comma-separated).",
            split = ",")
    private List<File> modelsB;

    // ---- loading options ---------------------------------------------------

    @Option(names = "--xml-base",
            description = "XML base URI used when loading models (default: http://iec.ch/TC57/CIM100).")
    private String xmlBase;

    // ---- skip flags --------------------------------------------------------

    @Option(names = "--ignore-sv",
            description = "Ignore SV (state-variable) profile differences.")
    private boolean ignoreSv;

    @Option(names = "--ignore-tp",
            description = "Ignore TP (topology) profile differences.")
    private boolean ignoreTp;

    @Option(names = "--ignore-dl",
            description = "Ignore DL (diagram-layout) profile differences.")
    private boolean ignoreDl;

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
            if (configFile != null) loadConfig(configFile);
            applyDefaults();

            if (dryRun) { printDryRun(); return ExitCode.OK; }

            if (!validateInputs()) return ExitCode.INVALID_INPUT;

            System.err.println("[INFO] Loading model set A (" + modelsA.size() + " file(s))...");
            Model modelA = ModelFactory.loadCombinedModelForSparql(modelsA, xmlBase);

            System.err.println("[INFO] Loading model set B (" + modelsB.size() + " file(s))...");
            Model modelB = ModelFactory.loadCombinedModelForSparql(modelsB, xmlBase);

            // Build options list (indices match the Main.ComparisonInstanceData convention)
            // options[0]=SV, options[1]=DL, options[4]=TP
            LinkedList<Integer> options = new LinkedList<>(Arrays.asList(0, 0, 0, 0, 0));
            if (ignoreSv) options.set(0, 1);
            if (ignoreDl) options.set(1, 1);
            if (ignoreTp) options.set(4, 1);

            System.err.println("[INFO] Comparing instance data...");
            RDFCompareResult result = ComparisonInstanceData.compareInstanceData(
                    new RDFCompareResult(), modelA, modelB, options);

            List<RDFCompareResultEntry> entries = result.getEntries();
            boolean identical = entries.isEmpty();

            if (outputFile != null) {
                writeToFile(entries, outputFile);
                System.out.println("[OK] Comparison results written to: " + outputFile.getAbsolutePath());
                System.out.println("     Total differences: " + entries.size());
            } else {
                writeToStdout(entries, format);
            }

            if (identical) {
                System.err.println("[OK] Models are identical — no differences found.");
                return ExitCode.OK;
            } else {
                System.err.println("[OK] Found " + entries.size() + " difference(s).");
                return ExitCode.VIOLATIONS;
            }

        } catch (Exception ex) {
            System.err.println("[ERROR] compare-instances failed: " + ex.getMessage());
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

        if (modelsA == null) {
            JsonNode n = root.path("modelsA");
            if (n.isArray()) {
                modelsA = new ArrayList<>();
                for (JsonNode item : n) {
                    modelsA.add(resolveRelative(configDir, item.asText()));
                }
            }
        }
        if (modelsB == null) {
            JsonNode n = root.path("modelsB");
            if (n.isArray()) {
                modelsB = new ArrayList<>();
                for (JsonNode item : n) {
                    modelsB.add(resolveRelative(configDir, item.asText()));
                }
            }
        }
        if (xmlBase == null) { String v = root.path("xmlBase").asText(null); if (v != null && !v.isBlank()) xmlBase = v; }
        if (!ignoreSv) { JsonNode n = root.path("ignoreSv"); if (!n.isMissingNode()) ignoreSv = n.asBoolean(false); }
        if (!ignoreTp) { JsonNode n = root.path("ignoreTp"); if (!n.isMissingNode()) ignoreTp = n.asBoolean(false); }
        if (!ignoreDl) { JsonNode n = root.path("ignoreDl"); if (!n.isMissingNode()) ignoreDl = n.asBoolean(false); }
        if (outputFile == null) { String v = root.path("output").asText(null); if (v != null && !v.isBlank()) outputFile = resolveRelative(configDir, v); }
        if (format == null) { String v = root.path("format").asText(null); if (v != null && !v.isBlank()) format = v; }
    }

    private void applyDefaults() {
        if (xmlBase == null) xmlBase = "http://iec.ch/TC57/CIM100";
        if (format == null) format = "text";
    }

    private boolean validateInputs() {
        boolean ok = true;
        if (modelsA == null || modelsA.isEmpty()) {
            System.err.println("[ERROR] --models-a is required."); ok = false;
        } else {
            for (File f : modelsA) {
                if (!f.exists()) { System.err.println("[ERROR] Model A file not found: " + f.getAbsolutePath()); ok = false; }
            }
        }
        if (modelsB == null || modelsB.isEmpty()) {
            System.err.println("[ERROR] --models-b is required."); ok = false;
        } else {
            for (File f : modelsB) {
                if (!f.exists()) { System.err.println("[ERROR] Model B file not found: " + f.getAbsolutePath()); ok = false; }
            }
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    private static void writeToFile(List<RDFCompareResultEntry> entries, File outFile) throws IOException {
        String name = outFile.getName().toLowerCase();
        if (name.endsWith(".xlsx")) {
            writeExcel(entries, outFile);
        } else {
            writeCsvFile(entries, outFile);
        }
    }

    private static void writeExcel(List<RDFCompareResultEntry> entries, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Instance Comparison");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Type");
            header.createCell(2).setCellValue("Property");
            header.createCell(3).setCellValue("Model A");
            header.createCell(4).setCellValue("Model B");

            int rowNum = 1;
            for (RDFCompareResultEntry e : entries) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(nvl(e.getItem()));
                row.createCell(1).setCellValue(nvl(e.getRdfType()));
                row.createCell(2).setCellValue(nvl(e.getProperty()));
                row.createCell(3).setCellValue(nvl(e.getValueModelA()));
                row.createCell(4).setCellValue(nvl(e.getValueModelB()));
            }
            for (int c = 0; c < 5; c++) sheet.autoSizeColumn(c);
            try (FileOutputStream fos = new FileOutputStream(outFile)) { wb.write(fos); }
        }
    }

    private static void writeCsvFile(List<RDFCompareResultEntry> entries, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (PrintWriter pw = new PrintWriter(outFile, StandardCharsets.UTF_8)) {
            pw.println("Item,Type,Property,ModelA,ModelB");
            for (RDFCompareResultEntry e : entries) {
                pw.printf("%s,%s,%s,%s,%s%n",
                        csvEscape(e.getItem()), csvEscape(e.getRdfType()),
                        csvEscape(e.getProperty()),
                        csvEscape(e.getValueModelA()), csvEscape(e.getValueModelB()));
            }
        }
    }

    private static void writeToStdout(List<RDFCompareResultEntry> entries, String format) {
        switch (format.toLowerCase()) {
            case "json" -> writeJson(entries);
            case "csv"  -> writeCsvStdout(entries);
            default     -> writeText(entries);
        }
    }

    private static void writeText(List<RDFCompareResultEntry> entries) {
        int itemW  = Math.max(60, entries.stream().mapToInt(e -> len(e.getItem())).max().orElse(60));
        int typeW  = Math.max(20, entries.stream().mapToInt(e -> len(e.getRdfType())).max().orElse(20));
        int propW  = Math.max(30, entries.stream().mapToInt(e -> len(e.getProperty())).max().orElse(30));
        int valW   = 30;
        String hdr = String.format("%-" + itemW + "s | %-" + typeW + "s | %-" + propW + "s | %-" + valW + "s | %-" + valW + "s",
                "Item", "Type", "Property", "Model A", "Model B");
        System.out.println(hdr);
        System.out.println("-".repeat(hdr.length()));
        for (RDFCompareResultEntry e : entries) {
            System.out.printf("%-" + itemW + "s | %-" + typeW + "s | %-" + propW + "s | %-" + valW + "s | %-" + valW + "s%n",
                    trunc(e.getItem(), itemW), trunc(e.getRdfType(), typeW),
                    trunc(e.getProperty(), propW),
                    trunc(e.getValueModelA(), valW), trunc(e.getValueModelB(), valW));
        }
        System.out.println();
        System.out.println("Total: " + entries.size() + " difference(s)");
    }

    private static void writeJson(List<RDFCompareResultEntry> entries) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"schema\": \"cimpal-compare-instances-result/1\",\n");
        sb.append("  \"totalDifferences\": ").append(entries.size()).append(",\n");
        sb.append("  \"differences\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            RDFCompareResultEntry e = entries.get(i);
            sb.append("    {\"item\": ").append(jsonStr(e.getItem()))
              .append(", \"rdfType\": ").append(jsonStr(e.getRdfType()))
              .append(", \"property\": ").append(jsonStr(e.getProperty()))
              .append(", \"valueA\": ").append(jsonStr(e.getValueModelA()))
              .append(", \"valueB\": ").append(jsonStr(e.getValueModelB()))
              .append("}");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");
        System.out.println(sb);
    }

    private static void writeCsvStdout(List<RDFCompareResultEntry> entries) {
        System.out.println("Item,Type,Property,ModelA,ModelB");
        for (RDFCompareResultEntry e : entries) {
            System.out.printf("%s,%s,%s,%s,%s%n",
                    csvEscape(e.getItem()), csvEscape(e.getRdfType()),
                    csvEscape(e.getProperty()),
                    csvEscape(e.getValueModelA()), csvEscape(e.getValueModelB()));
        }
    }

    private void printDryRun() {
        System.out.println("=== CimPal compare-instances -- Dry Run ===");
        System.out.println("  models-A    : " + (modelsA == null ? "(not set)" : modelsA.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
        System.out.println("  models-B    : " + (modelsB == null ? "(not set)" : modelsB.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
        System.out.println("  xml-base    : " + xmlBase);
        System.out.println("  ignore-sv   : " + ignoreSv);
        System.out.println("  ignore-tp   : " + ignoreTp);
        System.out.println("  ignore-dl   : " + ignoreDl);
        System.out.println("  output      : " + (outputFile == null ? "(stdout)" : outputFile.getAbsolutePath()));
        System.out.println("  format      : " + format);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : configDir.resolve(p).normalize().toFile();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static int len(String s) { return s == null ? 0 : s.length(); }
    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
