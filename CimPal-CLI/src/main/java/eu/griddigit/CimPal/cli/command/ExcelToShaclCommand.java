/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.shacl_tools.ShaclFromXls;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDataBuilder;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code excel2shacl} subcommand — generate SHACL constraints from an Excel (.xlsx) template
 * and an RDFS profile (.rdf) file.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load the RDFS profile model from {@code --rdfs-file}</li>
 *   <li>Build shape data using {@link ShapeDataBuilder#constructShapeData}</li>
 *   <li>Load the Excel data sheet (sheet 0) and config sheet ("Config") from {@code --excel-file}</li>
 *   <li>Generate SHACL shapes using {@link ShaclFromXls#generateShaclFromXls}</li>
 *   <li>Write the result as Turtle to {@code --output}</li>
 * </ol>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — shapes generated successfully
 *   <li>2 — missing or bad input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "excel2shacl",
        mixinStandardHelpOptions = true,
        description = "Generate SHACL constraints from an Excel template and RDFS profile.",
        sortOptions = false
)
public class ExcelToShaclCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- inputs ------------------------------------------------------------

    @Option(names = "--rdfs-file",
            description = "RDFS profile .rdf file used to build class/attribute shape data (required).")
    private File rdfsFile;

    @Option(names = "--excel-file",
            description = "Excel .xlsx file containing constraint definitions (required).")
    private File excelFile;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output",
            description = "Output Turtle (.ttl) file path (required).")
    private File outputFile;

    // ---- namespace options -------------------------------------------------

    @Option(names = "--ns-prefix",
            description = "Namespace prefix for generated shapes (e.g. eu-nc).")
    private String nsPrefix;

    @Option(names = "--ns-uri",
            description = "Namespace URI for generated shapes (e.g. https://cim.ucaiug.io/ns/nc#).")
    private String nsUri;

    @Option(names = "--cims-namespace",
            description = "CIMS namespace URI (default: http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#).")
    private String cimsNamespace;

    @Option(names = "--cim-namespace",
            description = "CIM namespace URI (default: http://iec.ch/TC57/CIM100#).")
    private String cimNamespace;

    // ---- misc --------------------------------------------------------------

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without generating shapes.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            if (configFile != null) loadConfig(configFile);
            applyDefaults();

            if (dryRun) { printDryRun(); return ExitCode.OK; }

            if (!validateInputs()) return ExitCode.INVALID_INPUT;

            // 1. Load RDFS model
            System.err.println("[INFO] Loading RDFS profile: " + rdfsFile.getAbsolutePath());
            Model rdfsModel = ModelFactory.createDefaultModel();
            try (InputStream in = new FileInputStream(rdfsFile)) {
                RDFDataMgr.read(rdfsModel, in, Lang.RDFXML);
            }

            // 2. Build shape data
            String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
            System.err.println("[INFO] Building shape data from RDFS model...");
            ArrayList<Object> shapeData = ShapeDataBuilder.constructShapeData(rdfsModel, cimsNamespace, concreteNs);

            // 3. Load Excel sheets
            System.err.println("[INFO] Loading Excel file: " + excelFile.getAbsolutePath());
            ArrayList<Object> dataExcel  = ExcelTools.importXLSX(excelFile.getAbsolutePath(), 0);
            ArrayList<Object> configSheet = ExcelTools.importXLSX(excelFile.getAbsolutePath(), "Config");

            // 4. Build namespace fallback map
            Map<String, String> namespaceFallbacks = new HashMap<>();
            namespaceFallbacks.put("prefixEU",       nsPrefix != null ? nsPrefix : "");
            namespaceFallbacks.put("uriEU",          nsUri    != null ? nsUri    : "");
            namespaceFallbacks.put("cimsNamespace",  cimsNamespace);
            namespaceFallbacks.put("CIMnamespace",   cimNamespace);
            namespaceFallbacks.put("prefixOther",    "");
            namespaceFallbacks.put("uriOther",       "");

            // 5. Generate SHACL
            System.err.println("[INFO] Generating SHACL shapes...");
            String resolvedNsPrefix = nsPrefix != null ? nsPrefix : "";
            String resolvedNsUri    = nsUri    != null ? nsUri    : "";
            Model shapeModel = ShaclFromXls.generateShaclFromXls(
                    namespaceFallbacks, dataExcel, configSheet, shapeData,
                    resolvedNsPrefix, resolvedNsUri);

            // 6. Write output
            File outParent = outputFile.getParentFile();
            if (outParent != null && !outParent.exists()) outParent.mkdirs();
            System.err.println("[INFO] Writing output to: " + outputFile.getAbsolutePath());
            try (OutputStream out = new FileOutputStream(outputFile)) {
                RDFDataMgr.write(out, shapeModel, Lang.TURTLE);
            }

            System.out.println("[OK] SHACL shapes written to: " + outputFile.getAbsolutePath());
            return ExitCode.OK;

        } catch (Exception ex) {
            System.err.println("[ERROR] excel2shacl failed: " + ex.getMessage());
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
        if (rdfsFile == null)    { String v = root.path("rdfsFile").asText(null);    if (v != null && !v.isBlank()) rdfsFile    = resolveRelative(configDir, v); }
        if (excelFile == null)   { String v = root.path("excelFile").asText(null);   if (v != null && !v.isBlank()) excelFile   = resolveRelative(configDir, v); }
        if (outputFile == null)  { String v = root.path("output").asText(null);      if (v != null && !v.isBlank()) outputFile  = resolveRelative(configDir, v); }
        if (nsPrefix == null)    { String v = root.path("nsPrefix").asText(null);    if (v != null && !v.isBlank()) nsPrefix    = v; }
        if (nsUri == null)       { String v = root.path("nsUri").asText(null);       if (v != null && !v.isBlank()) nsUri       = v; }
        if (cimsNamespace == null) { String v = root.path("cimsNamespace").asText(null); if (v != null && !v.isBlank()) cimsNamespace = v; }
        if (cimNamespace == null)  { String v = root.path("cimNamespace").asText(null);  if (v != null && !v.isBlank()) cimNamespace  = v; }
    }

    private void applyDefaults() {
        if (cimsNamespace == null) cimsNamespace = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        if (cimNamespace  == null) cimNamespace  = "http://iec.ch/TC57/CIM100#";
        if (nsPrefix == null) nsPrefix = "";
        if (nsUri    == null) nsUri    = "";
    }

    private boolean validateInputs() {
        boolean ok = true;
        if (rdfsFile == null || !rdfsFile.exists()) {
            System.err.println("[ERROR] --rdfs-file is required and must exist: " + (rdfsFile == null ? "(not set)" : rdfsFile.getAbsolutePath()));
            ok = false;
        }
        if (excelFile == null || !excelFile.exists()) {
            System.err.println("[ERROR] --excel-file is required and must exist: " + (excelFile == null ? "(not set)" : excelFile.getAbsolutePath()));
            ok = false;
        }
        if (outputFile == null) {
            System.err.println("[ERROR] --output is required.");
            ok = false;
        }
        if (nsPrefix.isBlank()) {
            System.err.println("[WARN] --ns-prefix is empty; shapes will be generated without a prefix.");
        }
        if (nsUri.isBlank()) {
            System.err.println("[WARN] --ns-uri is empty; shapes will be generated without a namespace URI.");
        }
        return ok;
    }

    private void printDryRun() {
        System.out.println("=== CimPal excel2shacl -- Dry Run ===");
        System.out.println("  rdfs-file       : " + abs(rdfsFile));
        System.out.println("  excel-file      : " + abs(excelFile));
        System.out.println("  output          : " + abs(outputFile));
        System.out.println("  ns-prefix       : " + nsPrefix);
        System.out.println("  ns-uri          : " + nsUri);
        System.out.println("  cims-namespace  : " + cimsNamespace);
        System.out.println("  cim-namespace   : " + cimNamespace);
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : configDir.resolve(p).normalize().toFile();
    }

    private static String abs(File f) { return f == null ? "(not set)" : f.getAbsolutePath(); }
}
