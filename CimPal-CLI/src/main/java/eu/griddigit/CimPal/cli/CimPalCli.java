package eu.griddigit.CimPal.cli;

import eu.griddigit.CimPal.cli.command.CompareCommand;
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
 *   <li>{@code validate}   — run SHACL validation (mapping, timestamped, or manual workflow)
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
        version = "CimPal CLI 2026.9",
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

    /**
     * Main entry point.  Delegates all subcommand dispatch to picocli and exits with
     * the return code of the executed subcommand.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CimPalCli()).execute(args);
        System.exit(exitCode);
    }
}
