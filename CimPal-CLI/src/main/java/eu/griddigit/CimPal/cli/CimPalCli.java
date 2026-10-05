/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import eu.griddigit.CimPal.cli.command.CompareCommand;
import eu.griddigit.cimpal.core.utils.OutOfMemoryRethrow;
import eu.griddigit.cimpal.core.utils.SparqlServicePolicy;
import eu.griddigit.CimPal.cli.command.CompareInstancesCommand;
import eu.griddigit.CimPal.cli.command.ConvertCommand;
import eu.griddigit.CimPal.cli.command.ExcelToShaclCommand;
import eu.griddigit.CimPal.cli.command.GenInstancesCommand;
import eu.griddigit.CimPal.cli.command.ManifestCommand;
import eu.griddigit.CimPal.cli.command.McpCommand;
import eu.griddigit.CimPal.cli.command.OrganizeCommand;
import eu.griddigit.CimPal.cli.command.RdfsToShaclCommand;
import eu.griddigit.CimPal.cli.command.RunCommand;
import eu.griddigit.CimPal.cli.command.ServeCommand;
import eu.griddigit.CimPal.cli.command.SparqlCommand;
import eu.griddigit.CimPal.cli.command.ValidateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root entry point for the CimPal CLI fat-JAR.
 *
 * <p>Usage: {@code java -jar CimPal-CLI.jar <subcommand> [options]}
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code validate}   — run SHACL validation (mapping or timestamped workflow)
 *   <li>{@code sparql}     — execute a SPARQL SELECT query against RDF model files
 *   <li>{@code manifest}   — generate a DCAT manifest for a set of CGMES model files
 *   <li>{@code convert}    — convert RDF files between RDF/XML, Turtle, and JSON-LD formats
 *   <li>{@code rdfs2shacl} — generate SHACL shapes from RDFS CIM profile definitions
 *   <li>{@code compare}          — compare two RDF model files and report differences
 *   <li>{@code compare-instances} — compare two sets of CIM instance-data model files
 *   <li>{@code excel2shacl}    — generate SHACL constraints from Excel + RDFS profile
 *   <li>{@code organize}       — reorganize SHACL files per Excel mapping template
 *   <li>{@code help}           — display help for a subcommand
 * </ul>
 *
 * <p>Backward compatibility: {@code ManifestService.main()} is still callable directly when the
 * JAR is invoked with the legacy {@code --dir} / {@code --files} flags (those are not picocli
 * flags and will therefore fall through to this dispatcher, which will print a help message).
 * To keep the original behaviour, callers should migrate to {@code manifest --dir ...}.
 */
@Command(
        name = "cimpal",
        mixinStandardHelpOptions = true,
        versionProvider = CliVersion.class,
        description = {
                "CimPal command-line interface for RDF/SHACL tooling.",
                "",
                "Run 'cimpal help <command>' for detailed help on any subcommand."
        },
        subcommands = {
                ValidateCommand.class,
                SparqlCommand.class,
                ManifestCommand.class,
                ConvertCommand.class,
                RdfsToShaclCommand.class,
                CompareCommand.class,
                CompareInstancesCommand.class,
                ExcelToShaclCommand.class,
                OrganizeCommand.class,
                GenInstancesCommand.class,
                RunCommand.class,
                ServeCommand.class,
                McpCommand.class,
                CommandLine.HelpCommand.class
        }
)
public class CimPalCli {

    static {
        // No SPARQL SERVICE for anything run through the CLI, including in-process use (SEC-2).
        SparqlServicePolicy.disableRemoteServiceGlobally();
    }

    /**
     * Main entry point.  Delegates all subcommand dispatch to picocli and exits with
     * the return code of the executed subcommand.
     */
    public static void main(String[] args) {
        // No SPARQL SERVICE: user queries and SHACL-SPARQL shapes must not reach the network (SEC-2).
        SparqlServicePolicy.disableRemoteServiceGlobally();
        int exitCode;
        try {
            exitCode = rethrowingOutOfMemory(new CommandLine(new CimPalCli())).execute(args);
        } catch (Throwable t) {
            if (OutOfMemoryRethrow.find(t).isPresent()) {
                outOfMemoryExit();
            }
            throw t;
        }
        System.exit(exitCode);
    }

    /**
     * A command line for running a subcommand inside this JVM on behalf of {@code run},
     * {@code serve} or {@code mcp}. {@code @file} argument expansion is off, so pipeline or
     * request data can't pull extra arguments (e.g. another subcommand) in from a file.
     */
    public static CommandLine inProcess() {
        return rethrowingOutOfMemory(new CommandLine(new CimPalCli()).setExpandAtFiles(false));
    }

    /**
     * Lets an {@link OutOfMemoryError} out of picocli, which would otherwise report it like any
     * other failure, as exit code 1: the same as "violations found" (DEP-2, R2).
     */
    private static CommandLine rethrowingOutOfMemory(CommandLine cli) {
        CommandLine.IExecutionExceptionHandler fallback = cli.getExecutionExceptionHandler();
        return cli.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            OutOfMemoryRethrow.ifCause(ex);
            return fallback.handleExecutionException(ex, commandLine, parseResult);
        });
    }

    /**
     * Ends the JVM with exit code 3 after an out-of-memory error. The message is built before it
     * is needed, and {@code halt} skips the shutdown hooks, because both could need memory there
     * is none of.
     */
    private static void outOfMemoryExit() {
        System.err.println(OUT_OF_MEMORY_MESSAGE);
        System.err.flush();
        Runtime.getRuntime().halt(ExitCode.INTERNAL_ERROR);
    }

    private static final String OUT_OF_MEMORY_MESSAGE = "[ERROR] Out of memory (max heap "
            + (Runtime.getRuntime().maxMemory() >> 20) + " MB). Give the JVM more memory (-Xmx, or the"
            + " container's memory limit); see docs/guide/sizing.md for sizes by model.";
}
