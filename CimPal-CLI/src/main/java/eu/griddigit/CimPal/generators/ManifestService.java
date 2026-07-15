package eu.griddigit.CimPal.generators;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class ManifestService {

    private ManifestService() {
        // utility class
    }

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help") || (!opts.containsKey("dir") && !opts.containsKey("files"))) {
            printUsage();
            System.exit(opts.containsKey("help") ? 0 : 1);
        }

        String accessUrl = opts.getOrDefault("accessUrl", null);
        String outputPath = opts.getOrDefault("output", null);

        List<File> sourceFiles = new ArrayList<>();
        if (opts.containsKey("dir")) {
            File dir = new File(opts.get("dir"));
            if (!dir.exists() || !dir.isDirectory()) {
                System.err.println("Directory not found: " + dir.getAbsolutePath());
                System.exit(2);
            }
            File[] matches = dir.listFiles((d, name) -> {
                String l = name.toLowerCase();
                return l.endsWith(".xml") || l.endsWith(".rdf") || l.endsWith(".ttl") || l.endsWith(".rdfxml");
            });
            if (matches != null) {
                sourceFiles.addAll(Arrays.asList(matches));
            }
            // default output: parent of the models folder
            if (outputPath == null) {
                File parent = dir.getParentFile();
                outputPath = new File(parent, "manifest.ttl").getAbsolutePath();
            }
            // default accessUrl: the --dir path itself, so accessURL reflects the full
            // folder structure (e.g. "Instance/Belgovia/Grid/cimxml") instead of just "cimxml"
            if (accessUrl == null) {
                accessUrl = opts.get("dir").replace("\\", "/");
            }
        } else if (opts.containsKey("files")) {
            String[] parts = opts.get("files").split(",");
            for (String p : parts) {
                File f = new File(p.trim());
                if (f.exists() && f.isFile()) {
                    sourceFiles.add(f);
                } else {
                    System.err.println("Skipping missing file: " + p);
                }
            }
            if (sourceFiles.isEmpty()) {
                System.err.println("No valid files provided.");
                System.exit(3);
            }
            if (outputPath == null) {
                // place manifest in parent of the models folder (assume all files share the same folder)
                File first = sourceFiles.getFirst();
                File modelsFolder = first.getParentFile();
                File parent = modelsFolder != null ? modelsFolder.getParentFile() : null;
                if (parent != null) {
                    outputPath = new File(parent, "manifest.ttl").getAbsolutePath();
                } else {
                    outputPath = new File("manifest.ttl").getAbsolutePath();
                }
            }
        }

        if (sourceFiles.isEmpty()) {
            System.err.println("No model files found to process.");
            System.exit(4);
        }

        // Read models - same loader the GUI uses, so relative URIs (rdf:about="#_uuid", standard in CIM/CGMES) resolve correctly
        Map<String, Model> models;
        try {
            models = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadPerFiles(sourceFiles, "", Lang.RDFXML);
            models.keySet().forEach(name -> System.out.println("Loaded model: " + name));
        } catch (Exception e) {
            System.err.println("Failed to read model files: " + e.getMessage());
            models = Map.of();
        }

        if (models.isEmpty()) {
            System.err.println("No readable models loaded. Exiting.");
            System.exit(5);
        }

        // Ensure deterministic ordering of files
        List<File> orderedFiles = sourceFiles.stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .collect(Collectors.toList());

        try (OutputStream os = new FileOutputStream(outputPath)) {
            eu.griddigit.cimpal.core.generators.ManifestGenerator.generateManifestTtl(models, orderedFiles, accessUrl, os);
            System.out.println("Manifest written to: " + outputPath);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Failed to generate or write manifest: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(6);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--dir":
                    if (i + 1 < args.length) {
                        opts.put("dir", args[++i]);
                    }
                    break;
                case "--files":
                    if (i + 1 < args.length) {
                        opts.put("files", args[++i]);
                    }
                    break;
                case "--accessUrl":
                    if (i + 1 < args.length) {
                        opts.put("accessUrl", args[++i]);
                    }
                    break;
                case "--output":
                    if (i + 1 < args.length) {
                        opts.put("output", args[++i]);
                    }
                    break;
                case "--help":
                case "-h":
                    opts.put("help", "true");
                    break;
                default:
                    // ignore unknown
            }
        }
        return opts;
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar CimPal-CLI.jar --dir <models-folder> [--accessUrl <url>] [--output <file>]\n" +
                "       Or: --files <file1,file2,...>\n" +
                "Default output when --dir is given: parent folder of the models folder/manifest.ttl\n" +
                "Example: java -jar CimPal-CLI.jar --dir Instance\\Belgovia\\Grid\\cimxml\n");
    }
}
