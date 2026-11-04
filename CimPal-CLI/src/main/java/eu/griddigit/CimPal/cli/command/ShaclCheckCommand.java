/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.shacl_tools.ShaclExcelExport;
import eu.griddigit.cimpal.core.shacl_tools.ShaclShapeCheck;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.UntrustedText;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RiotException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code shacl-check} subcommand — checks SHACL shape files against the SHACL-SHACL shapes, so a
 * malformed shape (a wrong datatype on sh:minCount, an unknown sh:nodeKind, ...) is found before it
 * silently never fires. The GUI's Tools ▸ SHACL Constraints ▸ Validate SHACL shapes.
 *
 * <p>Exit codes: 0 every file conforms; 1 a file does not conform (a result, not a failure);
 * 2 bad input (a missing, unreadable, unparseable or JSON-LD file); 3 internal error.
 */
@Command(
        name = "shacl-check",
        mixinStandardHelpOptions = true,
        description = "Check SHACL shape files against the SHACL-SHACL shapes (well-formed SHACL).",
        sortOptions = false
)
public class ShaclCheckCommand implements Callable<Integer> {

    @Option(names = "--config", description = "Optional JSON config file; individual flags override values from it.")
    private File configFile;

    @Option(names = "--shacl-files", split = ",", description = "SHACL shape files (.ttl, .rdf), comma-separated (required).")
    private List<File> shaclFiles;

    @Option(names = "--dry-run", description = "Print resolved configuration and exit without processing.")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            if (configFile != null && !loadConfig(configFile)) return ExitCode.INVALID_INPUT;
            if (dryRun) {
                System.out.println("=== CimPal shacl-check -- Dry Run ===");
                System.out.println("  shacl-files : " + (shaclFiles == null ? "(not set)"
                        : shaclFiles.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
                return ExitCode.OK;
            }
            if (!FileCommands.inputsExist(shaclFiles, "--shacl-files")) return ExitCode.INVALID_INPUT;

            // load everything first: a file that can't be read is bad input, never an empty "conforming" model
            List<ShaclExcelExport.Source> sources = new ArrayList<>();
            for (File f : shaclFiles) {
                try {
                    sources.add(ShaclExcelExport.load(f.toPath()));
                } catch (RiotException | IOException ex) {
                    System.err.println("[ERROR] Could not read " + f.getAbsolutePath() + ": " + FileCommands.message(ex));
                    return ExitCode.INVALID_INPUT;
                }
            }

            Model shaclShacl = ModelFactory.LoadSHACLSHACL();
            int failing = 0;
            for (int i = 0; i < sources.size(); i++) {
                ShaclShapeCheck.Result result = ShaclShapeCheck.check(shaclFiles.get(i).getName(), sources.get(i).model(), shaclShacl);
                if (result.conforms()) {
                    System.out.println("[OK] " + result.name() + ": conforms to SHACL-SHACL");
                    continue;
                }
                failing++;
                System.out.println("[FAIL] " + result.name() + ": " + result.findings().size() + " problem(s)");
                // the findings quote the shape file, so control characters and bidi overrides are neutralised
                for (ShaclShapeCheck.Finding f : result.findings()) {
                    System.out.println("  - " + safe(f.focusNode()) + (f.path().isEmpty() ? "" : " " + safe(f.path()))
                            + " [" + safe(f.severity()) + "] " + safe(f.message())
                            + (f.value().isEmpty() ? "" : " (value: " + safe(f.value()) + ")"));
                }
            }
            System.out.println(sources.size() + " file(s) checked, " + failing + " not conforming.");
            return failing == 0 ? ExitCode.OK : ExitCode.VIOLATIONS;
        } catch (Exception ex) {
            System.err.println("[ERROR] shacl-check failed: " + FileCommands.message(ex));
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private static String safe(String untrusted) {
        return UntrustedText.forAgent(untrusted, LogSanitizer.MAX_LENGTH);
    }

    private boolean loadConfig(File config) {
        JsonNode root = FileCommands.readConfig(config);
        if (root == null) return false;
        if (shaclFiles == null) shaclFiles = FileCommands.files(root, "shaclFiles", FileCommands.dir(config));
        return true;
    }
}
