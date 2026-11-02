/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.UntrustedText;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared plumbing of the file-in, file-out commands that expose a GUI Tools menu item
 * ({@code rdfs-describe}, {@code rdf2xlsx}, {@code rdfs-serialisation}, ...): reading the JSON
 * config (relative paths resolve against its folder), checking inputs and outputs, and loading RDF.
 * Messages go to stderr; the callers turn a false or an exception into exit code 2.
 */
final class FileCommands {

    private FileCommands() {
    }

    /** The config's JSON, or null (with a message) when it can't be read. */
    static JsonNode readConfig(File config) {
        try {
            return new ObjectMapper().readTree(config);
        } catch (Exception ex) {
            System.err.println("[ERROR] Could not read config file: " + ex.getMessage());
            return null;
        }
    }

    /** {@code value} relative to {@code dir} unless it is absolute. */
    static File resolve(Path dir, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.toFile() : dir.resolve(p).normalize().toFile();
    }

    /** The config file's folder, against which its relative paths resolve. */
    static Path dir(File config) {
        return config.toPath().toAbsolutePath().getParent();
    }

    /** A string key of the config as a file, or null when absent or blank. */
    static File file(JsonNode root, String key, Path dir) {
        String v = root.path(key).asText(null);
        return v == null || v.isBlank() ? null : resolve(dir, v);
    }

    /** An array key of the config as files, or null when absent. */
    static List<File> files(JsonNode root, String key, Path dir) {
        JsonNode n = root.path(key);
        if (!n.isArray()) return null;
        List<File> files = new ArrayList<>();
        for (JsonNode item : n) files.add(resolve(dir, item.asText()));
        return files;
    }

    /** A string key of the config, or null when absent or blank. */
    static String text(JsonNode root, String key) {
        String v = root.path(key).asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    /** True when every file is given and exists; else prints why. */
    static boolean inputsExist(List<File> files, String flag) {
        if (files == null || files.isEmpty()) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        boolean ok = true;
        for (File f : files) {
            if (!f.isFile()) {
                System.err.println("[ERROR] File not found: " + f.getAbsolutePath());
                ok = false;
            }
        }
        return ok;
    }

    /** True when the output is given and has one of the extensions; else prints why. */
    static boolean outputValid(File output, String flag, String... extensions) {
        if (output == null) {
            System.err.println("[ERROR] " + flag + " is required.");
            return false;
        }
        String name = output.getName().toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        System.err.println("[ERROR] " + flag + " must end in " + String.join(" or ", extensions) + ": " + output.getAbsolutePath());
        return false;
    }

    /**
     * The base relative IRIs in an input resolve against when the file declares none: fixed, so the
     * local path of the file never ends up in an output.
     */
    static final String BASE = "urn:cimpal:base#";

    /**
     * Reads an RDF file; the format follows the extension (.rdf, .xml, .owl → RDF/XML; .ttl → Turtle;
     * RDF/XML when unknown). Refused: JSON-LD (its reader may fetch a remote {@code @context}) and
     * quad formats (.trig, .nq; their named graphs would be dropped silently). Parse errors propagate
     * as {@link org.apache.jena.riot.RiotException}.
     */
    static Model loadRdf(File file) throws IOException {
        Lang lang = RDFLanguages.filenameToLang(file.getName());
        if (lang != null && (RDFLanguages.sameLang(lang, Lang.JSONLD)
                || lang.getLabel().toUpperCase(Locale.ROOT).contains("JSON-LD"))) {
            throw new IOException("JSON-LD is not supported; convert to Turtle or RDF/XML first: " + file.getName());
        }
        if (lang != null && RDFLanguages.isQuads(lang)) {
            throw new IOException("Quad formats (TriG, N-Quads) are not supported; convert to Turtle or RDF/XML first: "
                    + file.getName());
        }
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            RDFDataMgr.read(model, in, BASE, lang != null ? lang : Lang.RDFXML);
        }
        return model;
    }

    /** An exception message for stderr: it may quote the input, so control characters and bidi overrides are neutralised. */
    static String message(Throwable ex) {
        return UntrustedText.forAgent(String.valueOf(ex.getMessage()), LogSanitizer.MAX_LENGTH);
    }

    /**
     * Writes {@code model} in {@code lang} through a temporary sibling, creating parent folders, so a
     * failure leaves no partial file. An existing file is replaced.
     */
    static void writeRdf(Model model, Path file, Lang lang) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".cimpal-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                RDFDataMgr.write(out, model, lang);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The file name without its extension. */
    static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String abs(File f) {
        return f == null ? "(not set)" : f.getAbsolutePath();
    }
}
