package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.generators.ManifestGenerator;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code manifest} subcommand — generate a DCAT/CGMES manifest Turtle file for a set of
 * model files.
 *
 * <p>Wraps the existing {@link ManifestGenerator} logic from {@code CimPal-Core} in a
 * picocli-structured command.  The original {@code ManifestService.main()} entry point
 * continues to work unchanged for backward compatibility.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — manifest written successfully
 *   <li>2 — bad or missing input
 *   <li>3 — internal error
 * </ul>
 */
@Command(
        name = "manifest",
        mixinStandardHelpOptions = true,
        description = "Generate a DCAT/CGMES manifest Turtle file for a folder or list of model files.",
        sortOptions = false
)
public class ManifestCommand implements Callable<Integer> {

    @Option(names = "--dir",
            description = "Scan this folder for .xml, .rdf, and .ttl model files.")
    private File dir;

    @Option(names = "--files",
            description = "Comma-separated list of model files to include.",
            split = ",")
    private List<File> files;

    @Option(names = "--access-url",
            description = "The dcat:accessURL value written into the manifest. "
                    + "Defaults to the value of --dir (with forward slashes).")
    private String accessUrl;

    @Option(names = "--output",
            description = "Output path for the generated manifest .ttl file. "
                    + "Default: parent folder of the models folder / manifest.ttl.")
    private File output;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        // ---- resolve source files ------------------------------------------

        List<File> sourceFiles = new ArrayList<>();
        String resolvedAccessUrl = accessUrl;
        String resolvedOutputPath = (output != null) ? output.getAbsolutePath() : null;

        if (dir != null) {
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("[ERROR] Directory not found: " + dir.getAbsolutePath());
                return ExitCode.INVALID_INPUT;
            }

            File[] matches = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".xml") || lower.endsWith(".rdf") || lower.endsWith(".ttl");
            });
            if (matches != null) {
                sourceFiles.addAll(Arrays.asList(matches));
            }

            // default output: parent of the models folder / manifest.ttl
            if (resolvedOutputPath == null) {
                File parent = dir.getParentFile();
                if (parent != null) {
                    resolvedOutputPath = new File(parent, "manifest.ttl").getAbsolutePath();
                } else {
                    resolvedOutputPath = new File("manifest.ttl").getAbsolutePath();
                }
            }

            // default accessUrl: the --dir path itself with forward slashes
            if (resolvedAccessUrl == null) {
                resolvedAccessUrl = dir.getAbsolutePath().replace("\\", "/");
            }

        } else if (files != null && !files.isEmpty()) {
            for (File f : files) {
                if (f.exists() && f.isFile()) {
                    sourceFiles.add(f);
                } else {
                    System.err.println("[WARN] Skipping missing file: " + f.getAbsolutePath());
                }
            }
            if (sourceFiles.isEmpty()) {
                System.err.println("[ERROR] No valid files provided.");
                return ExitCode.INVALID_INPUT;
            }

            // default output: parent of the first file's parent folder / manifest.ttl
            if (resolvedOutputPath == null) {
                File first = sourceFiles.getFirst();
                File modelsFolder = first.getParentFile();
                File parent = (modelsFolder != null) ? modelsFolder.getParentFile() : null;
                resolvedOutputPath = (parent != null)
                        ? new File(parent, "manifest.ttl").getAbsolutePath()
                        : new File("manifest.ttl").getAbsolutePath();
            }

        } else {
            System.err.println("[ERROR] Either --dir or --files must be specified.");
            return ExitCode.INVALID_INPUT;
        }

        if (sourceFiles.isEmpty()) {
            System.err.println("[ERROR] No model files found to process.");
            return ExitCode.INVALID_INPUT;
        }

        // ---- load models ---------------------------------------------------

        Map<String, Model> models;
        try {
            models = ModelFactory.modelLoadPerFiles(sourceFiles, "", Lang.RDFXML);
            models.keySet().forEach(name -> System.out.println("[INFO] Loaded model: " + name));
        } catch (Exception ex) {
            System.err.println("[ERROR] Failed to read model files: " + ex.getMessage());
            models = Map.of();
        }

        if (models.isEmpty()) {
            System.err.println("[ERROR] No readable models loaded. Exiting.");
            return ExitCode.INTERNAL_ERROR;
        }

        // ---- deterministic ordering ----------------------------------------

        List<File> orderedFiles = sourceFiles.stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .collect(Collectors.toList());

        // ---- generate manifest ---------------------------------------------

        try (OutputStream os = new FileOutputStream(resolvedOutputPath)) {
            ManifestGenerator.generateManifestTtl(models, orderedFiles, resolvedAccessUrl, os);
            System.out.println("[INFO] Manifest written to: " + resolvedOutputPath);
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] Failed to generate or write manifest: " + ex.getMessage());
            ex.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }
}
