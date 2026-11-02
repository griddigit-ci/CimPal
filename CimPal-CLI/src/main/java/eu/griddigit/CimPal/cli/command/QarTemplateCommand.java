/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.ExcelSheets;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.QarTemplate;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code qar-template} subcommand — the QAR template workbook (file name, profile, MAS and model id
 * of each model file), the input of the GUI's "Generate QAR based on xls". The GUI's Tools ▸ Quality
 * Reports ▸ Create xlsx template from QAR.
 *
 * <p>Exit codes: 0 success; 2 bad input (a missing, unreadable or unparseable model); 3 internal error.
 */
@Command(
        name = "qar-template",
        mixinStandardHelpOptions = true,
        description = "Create the QAR template workbook (file, profile, MAS, model id) from model files.",
        sortOptions = false
)
public class QarTemplateCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--models", split = ",", description = "Model files (.xml, or .zip archives of them), comma-separated (required).")
    private List<File> models;

    @Option(names = "--output", description = "The .xlsx workbook to write (required); parent folders are created.")
    private File output;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal qar-template -- Dry Run ===");
                System.out.println("  models : " + (models == null ? "(not set)"
                        : models.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
                System.out.println("  output : " + FileCommands.abs(output));
                return ExitCode.OK;
            }
            if (!(FileCommands.inputsExist(models, "--models") & FileCommands.outputValid(output, "--output", ".xlsx"))) {
                return ExitCode.INVALID_INPUT;
            }
            Map<String, Model> loaded;
            try {
                loaded = ModelFactory.modelLoadPerFiles(models, "http://griddigit.eu#", Lang.RDFXML);
            } catch (Exception ex) {
                System.err.println("[ERROR] Could not read the models: " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            ExcelSheets.save(QarTemplate.workbook(loaded), output.toPath());
            System.out.println("[OK] QAR template (" + loaded.size() + " file(s)) written to: " + output.getAbsolutePath());
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] qar-template failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (models == null) models = FileCommands.files(root, "models", dir);
        if (output == null) output = FileCommands.file(root, "output", dir);
        return true;
    }
}
