/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.generators.ManifestGenerator;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>With {@code --config} the same settings come from a JSON file (keys {@code dir},
 * {@code files}, {@code accessUrl}, {@code output}; relative paths resolve against the file), so
 * {@code mcp}, {@code serve} and {@code run} can call it like the other commands. Flags win over
 * the file.
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

    @Option(names = "--config",
            description = "Optional JSON config file (keys dir, files, accessUrl, output); individual flags "
                    + "override values from it.")
    private File configFile;

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
        if (configFile != null && !loadConfig(configFile)) {
            return ExitCode.INVALID_INPUT;
        }
        try {
            return generate();
        } catch (PathNotAllowedException ex) {
            // serve, mcp and run: a file the scan found, or the default output, is outside the roots.
            System.err.println("[ERROR] " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INVALID_INPUT;
        }
    }

    private Integer generate() {

        // ---- resolve source files ------------------------------------------

        List<File> sourceFiles = new ArrayList<>();
        String resolvedAccessUrl = accessUrl;
        String resolvedOutputPath = (output != null) ? output.getAbsolutePath() : null;

        if (dir != null) {
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("[ERROR] Directory not found: " + LogSanitizer.forLog(dir.getAbsolutePath()));
                return ExitCode.INVALID_INPUT;
            }

            File[] matches = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".xml") || lower.endsWith(".rdf") || lower.endsWith(".ttl");
            });
            if (matches != null) {
                // Each file found, not only the folder: a link in it must not lead out of the roots.
                for (File match : matches) {
                    sourceFiles.add(PathPolicy.checkReadIfActive(match.toPath()).toFile());
                }
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
                File checked = PathPolicy.checkReadIfActive(f.toPath()).toFile();
                if (checked.exists() && checked.isFile()) {
                    sourceFiles.add(checked);
                } else {
                    System.err.println("[WARN] Skipping missing file: " + LogSanitizer.forLog(f.getAbsolutePath()));
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

        // The default output (next to the models folder) was never in the request, so no guard has
        // checked it: with a policy active it must be a write location, and an existing file stays.
        Optional<PathPolicy> policy = PathPolicy.active();
        if (output == null && policy.isPresent()) {
            resolvedOutputPath = policy.get().checkWrite(Path.of(resolvedOutputPath), false).toString();
        }

        Map<String, Model> models;
        try {
            models = ModelFactory.modelLoadPerFiles(sourceFiles, "", Lang.RDFXML);
            models.keySet().forEach(name -> System.out.println("[INFO] Loaded model: " + LogSanitizer.forLog(name)));
        } catch (PathNotAllowedException ex) {
            throw ex;
        } catch (Exception ex) {
            System.err.println("[ERROR] Failed to read model files: " + LogSanitizer.forLog(ex.getMessage()));
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
            System.out.println("[INFO] Manifest written to: " + LogSanitizer.forLog(resolvedOutputPath));
            return ExitCode.OK;
        } catch (Exception ex) {
            System.err.println("[ERROR] Failed to generate or write manifest: " + LogSanitizer.forLog(ex.getMessage()));
            return ExitCode.INTERNAL_ERROR;
        }
    }

    /** Fills what the flags left unset from the config; false (bad input) when it can't be read. */
    private boolean loadConfig(File config) {
        Path configDir = config.toPath().toAbsolutePath().getParent();
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(config);
        } catch (Exception ex) {
            System.err.println("[ERROR] Could not read config file " + config + ": " + ex.getMessage());
            return false;
        }
        if (root == null || !root.isObject()) {
            System.err.println("[ERROR] The config file must hold a JSON object: " + config);
            return false;
        }
        // Keys starting with _ are documentation and ignored.
        if (dir == null) {
            String v = root.path("dir").asText(null);
            if (v != null && !v.isBlank()) dir = resolveRelative(configDir, v);
        }
        if (files == null || files.isEmpty()) {
            JsonNode n = root.path("files");
            List<File> list = new ArrayList<>();
            if (n.isArray()) {
                for (JsonNode element : n) {
                    String v = element.asText(null);
                    if (v != null && !v.isBlank()) list.add(resolveRelative(configDir, v));
                }
            } else {
                String v = n.asText(null);
                if (v != null) {
                    for (String s : v.split(",")) {
                        if (!s.isBlank()) list.add(resolveRelative(configDir, s.trim()));
                    }
                }
            }
            if (!list.isEmpty()) files = list;
        }
        if (accessUrl == null) {
            String v = root.path("accessUrl").asText(null);
            if (v != null && !v.isBlank()) accessUrl = v;
        }
        if (output == null) {
            String v = root.path("output").asText(null);
            if (v != null && !v.isBlank()) output = resolveRelative(configDir, v);
        }
        return true;
    }

    private static File resolveRelative(Path configDir, String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p.toFile() : configDir.resolve(p).normalize().toFile();
    }
}
