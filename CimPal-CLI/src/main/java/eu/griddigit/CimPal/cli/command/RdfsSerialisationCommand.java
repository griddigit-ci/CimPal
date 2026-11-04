/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.RdfsSerialisation;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RiotException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code rdfs-serialisation} subcommand — the RDFS serialisation information (which classes are
 * written with rdf:about, which values are enumerations) of one or more profiles, as Turtle. The
 * GUI's Tools ▸ RDFS Profiles ▸ Generate information from RDFS.
 *
 * <p>Exit codes: 0 success; 2 bad input (missing, unreadable or unparseable file, or a profile
 * without a header); 3 internal error.
 */
@Command(
        name = "rdfs-serialisation",
        mixinStandardHelpOptions = true,
        description = "Generate the RDFS serialisation info (RdfAbout and RdfEnum members) of RDFS profiles as Turtle.",
        sortOptions = false
)
public class RdfsSerialisationCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--rdfs-files", split = ",", description = "RDFS profiles (.rdf, RDF/XML), comma-separated (required).")
    private List<File> rdfsFiles;

    @Option(names = "--output", description = "The .ttl file to write (required); parent folders are created.")
    private File output;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal rdfs-serialisation -- Dry Run ===");
                System.out.println("  rdfs-files : " + (rdfsFiles == null ? "(not set)"
                        : rdfsFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
                System.out.println("  output     : " + FileCommands.abs(output));
                return ExitCode.OK;
            }
            if (!(FileCommands.inputsExist(rdfsFiles, "--rdfs-files") & FileCommands.outputValid(output, "--output", ".ttl"))) {
                return ExitCode.INVALID_INPUT;
            }
            List<Map.Entry<String, Model>> profiles = new ArrayList<>();
            for (File f : rdfsFiles) {
                try {
                    profiles.add(Map.entry(f.getName(), FileCommands.loadRdf(f)));
                } catch (RiotException | IOException ex) {
                    System.err.println("[ERROR] Could not read " + f.getAbsolutePath() + ": " + FileCommands.message(ex));
                    return ExitCode.INVALID_INPUT;
                }
            }
            Model info;
            try {
                info = RdfsSerialisation.build(profiles);
            } catch (IllegalArgumentException ex) {
                System.err.println("[ERROR] " + FileCommands.message(ex));
                return ExitCode.INVALID_INPUT;
            }
            FileCommands.writeRdf(info, output.toPath(), Lang.TURTLE);
            System.out.println("[OK] RDFS serialisation info (" + profiles.size() + " profile(s)) written to: "
                    + output.getAbsolutePath());
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] rdfs-serialisation failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        Path dir = FileCommands.dir(config);
        if (rdfsFiles == null) rdfsFiles = FileCommands.files(root, "rdfsFiles", dir);
        if (output == null) output = FileCommands.file(root, "output", dir);
        return true;
    }
}
