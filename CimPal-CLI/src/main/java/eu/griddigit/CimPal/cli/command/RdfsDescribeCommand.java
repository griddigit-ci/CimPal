/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.ExcelSheets;
import eu.griddigit.cimpal.core.utils.RdfsDescriptions;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RiotException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code rdfs-describe} subcommand — one row per item of an RDFS profile (description, multiplicity,
 * type, association used, stereotype, concrete class) in an Excel workbook. The GUI's Tools ▸ RDFS
 * Profiles ▸ Export RDFS descriptions.
 *
 * <p>Exit codes: 0 success; 2 bad input (missing, unreadable or unparseable file); 3 internal error.
 */
@Command(
        name = "rdfs-describe",
        mixinStandardHelpOptions = true,
        description = "Export the descriptions of an RDFS profile's classes and properties to Excel.",
        sortOptions = false
)
public class RdfsDescribeCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--rdfs-file", description = "The RDFS profile (.rdf, RDF/XML) to describe (required).")
    private File rdfsFile;

    @Option(names = "--output", description = "The .xlsx workbook to write (required); parent folders are created.")
    private File output;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal rdfs-describe -- Dry Run ===");
                System.out.println("  rdfs-file : " + FileCommands.abs(rdfsFile));
                System.out.println("  output    : " + FileCommands.abs(output));
                return ExitCode.OK;
            }
            if (!(FileCommands.inputsExist(rdfsFile == null ? null : List.of(rdfsFile), "--rdfs-file")
                    & FileCommands.outputValid(output, "--output", ".xlsx"))) {
                return ExitCode.INVALID_INPUT;
            }
            Model rdfs;
            try {
                rdfs = FileCommands.loadRdf(rdfsFile);
            } catch (RiotException | IOException ex) {
                System.err.println("[ERROR] Could not read " + rdfsFile.getAbsolutePath() + ": " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            org.apache.poi.xssf.usermodel.XSSFWorkbook workbook;
            try {
                workbook = RdfsDescriptions.workbook(rdfs);
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] " + rdfsFile.getName() + ": " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            ExcelSheets.save(workbook, output.toPath());
            System.out.println("[OK] RDFS descriptions written to: " + output.getAbsolutePath());
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] rdfs-describe failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (rdfsFile == null) rdfsFile = FileCommands.file(root, "rdfsFile", dir);
        if (output == null) output = FileCommands.file(root, "output", dir);
        return true;
    }
}
