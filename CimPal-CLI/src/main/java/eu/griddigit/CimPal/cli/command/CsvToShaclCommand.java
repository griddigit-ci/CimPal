/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.shacl_tools.ConstraintCsvRestore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code csv2shacl} subcommand — rebuilds SHACL .ttl files from EA constraint CSV files (the inverse
 * of the GUI's "EA Import Format" export). The GUI's Tools ▸ SHACL Constraints ▸ Restore ttl from
 * constraint csv.
 *
 * <p>Every CSV is processed; a CSV that fails (no Payload column, an unsafe output name, a restored
 * file that is not valid Turtle) writes nothing for that file and is reported.
 *
 * <p>Exit codes: 0 every CSV restored; 2 bad input (a missing input, or a CSV that failed);
 * 3 internal error.
 */
@Command(
        name = "csv2shacl",
        mixinStandardHelpOptions = true,
        description = "Restore SHACL .ttl files from EA constraint CSV files (the EA Import Format export).",
        sortOptions = false
)
public class CsvToShaclCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--csv-files", split = ",", description = "EA constraint CSV files, comma-separated (required).")
    private List<File> csvFiles;

    @Option(names = "--output-dir", description = "Folder for the restored .ttl files (required); created if needed. "
            + "Files of the same name are replaced.")
    private File outputDir;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal csv2shacl -- Dry Run ===");
                System.out.println("  csv-files  : " + (csvFiles == null ? "(not set)"
                        : csvFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
                System.out.println("  output-dir : " + FileCommands.abs(outputDir));
                return ExitCode.OK;
            }
            boolean ok = FileCommands.inputsExist(csvFiles, "--csv-files");
            if (outputDir == null) {
                System.err.println("[ERROR] --output-dir is required.");
                ok = false;
            } else if (Files.exists(outputDir.toPath()) && !Files.isDirectory(outputDir.toPath())) {
                System.err.println("[ERROR] --output-dir is a file, not a folder: " + outputDir.getAbsolutePath());
                ok = false;
            }
            if (!ok) return ExitCode.INVALID_INPUT;

            int failed = 0;
            int ttlFiles = 0;
            for (File csv : csvFiles) {
                try {
                    ConstraintCsvRestore.Result r = ConstraintCsvRestore.restore(csv.toPath(), outputDir.toPath());
                    ttlFiles += r.ttlFiles().size();
                    System.out.println("[OK] " + csv.getName() + ": " + r.ttlFiles().size() + " .ttl file(s), "
                            + r.payloadBlocksWritten() + " block(s) from " + r.csvRowsRead() + " row(s)"
                            + (r.ttlFiles().isEmpty() ? "" : " -> " + r.ttlFiles().stream()
                                    .map(p -> p.getFileName().toString()).collect(Collectors.joining(", "))));
                } catch (IOException ex) {
                    failed++;
                    System.err.println("[ERROR] " + csv.getName() + ": " + FileCommands.message(ex));
                }
            }
            System.out.println(csvFiles.size() + " CSV file(s), " + ttlFiles + " .ttl file(s) written to "
                    + outputDir.getAbsolutePath() + (failed == 0 ? "" : ", " + failed + " CSV file(s) failed"));
            return failed == 0 ? ExitCode.OK : ExitCode.INVALID_INPUT;
        } catch (Exception ex) {
            System.err.println("[ERROR] csv2shacl failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (csvFiles == null) csvFiles = FileCommands.files(root, "csvFiles", dir);
        if (outputDir == null) outputDir = FileCommands.file(root, "outputDir", dir);
        return true;
    }
}
