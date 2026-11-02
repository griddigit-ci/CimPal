/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.ExcelSheets;
import eu.griddigit.cimpal.core.utils.QocdcRules;
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
 * {@code qocdc2xlsx} subcommand — the rules of a QoCDC 3.2.1 rule file (UMLRestrictionRule) as an
 * Excel workbook. The GUI's Tools ▸ Quality Reports ▸ QoCDC 3.2.1 xml to Excel.
 *
 * <p>Exit codes: 0 success; 2 bad input (missing, unreadable or unparseable file); 3 internal error.
 */
@Command(
        name = "qocdc2xlsx",
        mixinStandardHelpOptions = true,
        description = "Export the rules of a QoCDC 3.2.1 rule file (RDF/XML) to Excel.",
        sortOptions = false
)
public class QocdcToXlsxCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--input", description = "The QoCDC 3.2.1 rule file (.xml, RDF/XML) (required).")
    private File input;

    @Option(names = "--output", description = "The .xlsx workbook to write (required); parent folders are created.")
    private File output;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal qocdc2xlsx -- Dry Run ===");
                System.out.println("  input  : " + FileCommands.abs(input));
                System.out.println("  output : " + FileCommands.abs(output));
                return ExitCode.OK;
            }
            if (!(FileCommands.inputsExist(input == null ? null : List.of(input), "--input")
                    & FileCommands.outputValid(output, "--output", ".xlsx"))) {
                return ExitCode.INVALID_INPUT;
            }
            Model rules;
            try {
                rules = QocdcRules.load(input.toPath());
            } catch (RiotException | IOException ex) {
                System.err.println("[ERROR] Could not read " + input.getAbsolutePath() + ": " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            org.apache.poi.xssf.usermodel.XSSFWorkbook workbook;
            try {
                workbook = QocdcRules.workbook(rules);
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] " + input.getName() + ": " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            ExcelSheets.save(workbook, output.toPath());
            System.out.println("[OK] QoCDC rules written to: " + output.getAbsolutePath());
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] qocdc2xlsx failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (input == null) input = FileCommands.file(root, "input", dir);
        if (output == null) output = FileCommands.file(root, "output", dir);
        return true;
    }
}
