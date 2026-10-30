/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.shacl_tools.ShaclExcelExport;
import org.apache.jena.riot.RiotException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code shacl2xlsx} subcommand — export SHACL shape files to an Excel workbook, the same export as
 * the GUI's Tools → Export SHACL constraints information.
 *
 * <p>Layouts ({@code --layout}):
 * <ul>
 *   <li>{@code summary} (default) — one row per constraint: ID, name, group, type, SPARQL constraint,
 *       severity, message, description; one sheet per input file;</li>
 *   <li>{@code by-type} — every predicate of every NodeShape, PropertyShape, SPARQL constraint and
 *       PropertyGroup, one sheet per kind; with several files a "Source file" column names each row's file.</li>
 * </ul>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — success
 *   <li>2 — missing or bad input (including a SHACL file that can't be read or parsed, or is JSON-LD)
 *   <li>3 — internal error (e.g. the workbook can't be written)
 * </ul>
 */
@Command(
        name = "shacl2xlsx",
        mixinStandardHelpOptions = true,
        description = "Export SHACL shape files to an Excel workbook (constraint summary or all predicates by shape kind).",
        sortOptions = false
)
public class ShaclToXlsxCommand implements Callable<Integer> {

    // ---- config file -------------------------------------------------------

    @Option(names = "--config",
            description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    // ---- inputs ------------------------------------------------------------

    @Option(names = "--shacl-files",
            description = "SHACL .ttl/.rdf files to export (comma-separated, required). JSON-LD is refused.",
            split = ",")
    private List<File> shaclFiles;

    // ---- output ------------------------------------------------------------

    @Option(names = "--output",
            description = "The .xlsx workbook to write (required); parent folders are created.")
    private File output;

    @Option(names = "--layout",
            description = "summary (default): one row per constraint; by-type: every predicate, one sheet per shape kind.")
    private String layout;

    // ---- misc --------------------------------------------------------------

    @Option(names = "--dry-run",
            description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (layout == null) layout = "summary";
            layout = layout.toLowerCase(Locale.ROOT);

            if (dryRun) { printDryRun(); return ExitCode.OK; }

            if (!validateInputs()) return ExitCode.INVALID_INPUT;

            List<ShaclExcelExport.Source> sources = new ArrayList<>();
            for (File f : shaclFiles) {
                System.err.println("[INFO] Loading SHACL file: " + f.getAbsolutePath());
                try {
                    sources.add(ShaclExcelExport.load(f.toPath()));
                } catch (RiotException | IOException ex) {
                    System.err.println("[ERROR] Could not read " + f.getAbsolutePath() + ": " + ex.getMessage());
                    return ExitCode.INVALID_INPUT;
                }
            }

            XSSFWorkbook workbook = layout.equals("by-type") ? ShaclExcelExport.byType(sources) : ShaclExcelExport.summary(sources);
            ShaclExcelExport.write(workbook, output.toPath());

            System.out.println("[OK] SHACL exported (" + layout + ", " + sources.size() + " file(s)) to: "
                    + output.getAbsolutePath());
            return ExitCode.OK;

        } catch (Exception ex) {
            System.err.println("[ERROR] shacl2xlsx failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    /** Reads the config file; an unreadable one is bad input. Flags already given win. */
    private boolean loadConfig(File config) {
        Path configDir = config.toPath().toAbsolutePath().getParent();
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(config);
        } catch (Exception ex) {
            System.err.println("[ERROR] Could not read config file: " + ex.getMessage());
            return false;
        }
        if (shaclFiles == null) {
            JsonNode n = root.path("shaclFiles");
            if (n.isArray()) {
                shaclFiles = new ArrayList<>();
                for (JsonNode item : n) shaclFiles.add(resolveRelative(configDir, item.asText()));
            }
        }
        if (output == null) { String v = root.path("output").asText(null); if (v != null && !v.isBlank()) output = resolveRelative(configDir, v); }
        if (layout == null) { String v = root.path("layout").asText(null); if (v != null && !v.isBlank()) layout = v; }
        return true;
    }

    private boolean validateInputs() {
        boolean ok = true;
        if (shaclFiles == null || shaclFiles.isEmpty()) {
            System.err.println("[ERROR] --shacl-files is required."); ok = false;
        } else {
            for (File f : shaclFiles) {
                if (!f.isFile()) { System.err.println("[ERROR] SHACL file not found: " + f.getAbsolutePath()); ok = false; }
            }
        }
        if (output == null) {
            System.err.println("[ERROR] --output is required."); ok = false;
        } else if (!output.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            System.err.println("[ERROR] --output must be an .xlsx file: " + output.getAbsolutePath()); ok = false;
        }
        if (!layout.equals("summary") && !layout.equals("by-type")) {
            System.err.println("[ERROR] --layout must be 'summary' or 'by-type', got: " + layout); ok = false;
        }
        return ok;
    }

    private void printDryRun() {
        System.out.println("=== CimPal shacl2xlsx -- Dry Run ===");
        System.out.println("  shacl-files : " + (shaclFiles == null ? "(not set)" : shaclFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
        System.out.println("  output      : " + (output == null ? "(not set)" : output.getAbsolutePath()));
        System.out.println("  layout      : " + layout);
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : configDir.resolve(p).normalize().toFile();
    }
}
