/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.converters.CommonDataConverter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RiotException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code ref2common} subcommand — converts reference data (cim and nc typed subjects) into common
 * data serialised as CIM/XML instance data with document-local ids. The GUI's Tools ▸ Reference
 * Data ▸ Convert Reference data to Common data.
 *
 * <p>Exit codes: 0 success; 2 bad input (missing, unreadable or unparseable file, or no cim/nc
 * prefix); 3 internal error.
 */
@Command(
        name = "ref2common",
        mixinStandardHelpOptions = true,
        description = "Convert reference data (cim/nc classes) into common data as CIM/XML.",
        sortOptions = false
)
public class RefToCommonCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--rdf-files", split = ",", description = "Reference data files (.rdf/.xml RDF/XML, .ttl), comma-separated; "
            + "they are merged (required).")
    private List<File> rdfFiles;

    @Option(names = "--output", description = "The .xml file to write (required); parent folders are created.")
    private File output;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal ref2common -- Dry Run ===");
                System.out.println("  rdf-files : " + (rdfFiles == null ? "(not set)"
                        : rdfFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
                System.out.println("  output    : " + FileCommands.abs(output));
                return ExitCode.OK;
            }
            if (!(FileCommands.inputsExist(rdfFiles, "--rdf-files") & FileCommands.outputValid(output, "--output", ".xml"))) {
                return ExitCode.INVALID_INPUT;
            }
            Model union = ModelFactory.createDefaultModel();
            for (File f : rdfFiles) {
                try {
                    Model m = FileCommands.loadRdf(f);
                    union.add(m);
                    m.getNsPrefixMap().forEach(union::setNsPrefix);
                } catch (RiotException | IOException ex) {
                    System.err.println("[ERROR] Could not read " + f.getAbsolutePath() + ": " + FileCommands.message(ex));
                    return ExitCode.INVALID_INPUT;
                }
            }
            Model common;
            try {
                common = CommonDataConverter.convert(union);
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }

            Path target = output.toPath().toAbsolutePath();
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".cimpal-", ".xml");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    CommonDataConverter.write(common, out);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            System.out.println("[OK] Common data (" + common.listSubjects().toList().size() + " object(s)) written to: "
                    + output.getAbsolutePath());
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] ref2common failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (rdfFiles == null) rdfFiles = FileCommands.files(root, "rdfFiles", dir);
        if (output == null) output = FileCommands.file(root, "output", dir);
        return true;
    }
}
