/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the file paths in a {@code serve} request, {@code mcp} tool call or {@code run} step
 * against a {@link PathPolicy} before the command runs (SEC-2, gap G2).
 *
 * <p>{@link #FIELDS} lists, per command, every config key that names a file or folder. Each
 * value is resolved (relative paths against {@code base}), checked, and replaced by the checked
 * absolute path, so the command reads exactly what was checked. Explicit output files that
 * already exist are refused unless the JSON sets {@code "overwrite": true}.
 * {@code PathGuardTest} keeps the list in sync with each command's file-type options.
 */
final class PathGuard {

    /** Key that allows existing output files to be replaced. */
    static final String OVERWRITE_KEY = "overwrite";

    enum Kind {
        /** An existing file or folder that is read. */
        READ,
        /** Like READ, but the command also accepts one string of comma-separated paths. */
        READ_LIST,
        /** A file or folder that is read if it exists, else the value is text (a preset, an inline query). */
        READ_IF_EXISTS,
        /** An output file; an existing one is replaced only with "overwrite": true. */
        WRITE_FILE,
        /** An output folder (created if missing; files in it are named by the command). */
        WRITE_DIR
    }

    /** A config key, the CLI option it corresponds to, and how it is used. */
    record Field(String key, String option, Kind kind) {
    }

    static final Map<String, List<Field>> FIELDS = Map.ofEntries(
            Map.entry("validate", List.of(
                    new Field("mappingCsv", "--mapping-csv", Kind.READ),
                    new Field("modelsDir", "--models", Kind.READ),
                    new Field("constraintsRoot", "--constraints-root", Kind.READ),
                    new Field("outputDir", "--output", Kind.WRITE_DIR),
                    new Field("datatypeMap", "--datatype-map", Kind.READ_IF_EXISTS),
                    new Field("previousComparison", "--previous-comparison", Kind.READ),
                    new Field("summaryFile", "--summary-file", Kind.WRITE_FILE))),
            Map.entry("sparql", List.of(
                    new Field("models", "--models", Kind.READ_LIST),
                    new Field("query", "--query", Kind.READ_IF_EXISTS),
                    new Field("output", "--output", Kind.WRITE_FILE),
                    new Field("summaryFile", "--summary-file", Kind.WRITE_FILE))),
            Map.entry("convert", List.of(
                    new Field("input", "--input", Kind.READ),
                    new Field("inputFiles", "--input-files", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE))),
            Map.entry("compare", List.of(
                    new Field("fileA", "--file-a", Kind.READ),
                    new Field("fileB", "--file-b", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE),
                    new Field("summaryFile", "--summary-file", Kind.WRITE_FILE))),
            Map.entry("compare-instances", List.of(
                    new Field("modelsA", "--models-a", Kind.READ),
                    new Field("modelsB", "--models-b", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE),
                    new Field("summaryFile", "--summary-file", Kind.WRITE_FILE))),
            Map.entry("rdfs2shacl", List.of(
                    new Field("rdfsFiles", "--rdfs-files", Kind.READ),
                    new Field("outputDir", "--output-dir", Kind.WRITE_DIR))),
            Map.entry("organize", List.of(
                    new Field("shaclFiles", "--shacl-files", Kind.READ),
                    new Field("templateXlsx", "--template-xlsx", Kind.READ),
                    new Field("outputDir", "--output-dir", Kind.WRITE_DIR))),
            Map.entry("excel2shacl", List.of(
                    new Field("rdfsFile", "--rdfs-file", Kind.READ),
                    new Field("excelFile", "--excel-file", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE))),
            Map.entry("gen-instances", List.of(
                    new Field("templateXlsx", "--template-xlsx", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE))),
            Map.entry("manifest", List.of(
                    new Field("dir", "--dir", Kind.READ),
                    new Field("files", "--files", Kind.READ),
                    new Field("output", "--output", Kind.WRITE_FILE))));

    /** Read by no command today, but resolved by {@code run}; checked so it can't point anywhere. */
    private static final Field STEP_CONFIG = new Field("config", null, Kind.READ);

    private PathGuard() {
    }

    static boolean overwrite(JsonNode json) {
        return json.path(OVERWRITE_KEY).asBoolean(false);
    }

    /**
     * Returns a copy of {@code json} with every path field of {@code command} checked and made
     * absolute.
     *
     * @throws PathNotAllowedException naming the first refused path
     */
    static ObjectNode check(String command, JsonNode json, PathPolicy policy, Path base) {
        if (json == null || !json.isObject()) {
            throw new PathNotAllowedException("The request must be a JSON object.");
        }
        ObjectNode copy = ((ObjectNode) json).deepCopy();
        boolean overwrite = overwrite(json);
        List<Field> fields = new ArrayList<>(FIELDS.getOrDefault(command, List.of()));
        fields.add(STEP_CONFIG);
        for (Field field : fields) {
            JsonNode value = copy.get(field.key());
            if (value == null || value.isNull()) {
                continue;
            }
            copy.set(field.key(), checkValue(field, value, policy, base, overwrite));
        }
        return copy;
    }

    private static JsonNode checkValue(Field field, JsonNode value, PathPolicy policy, Path base, boolean overwrite) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        if (value.isArray()) {
            ArrayNode checked = nodes.arrayNode();
            for (JsonNode element : value) {
                checked.add(checkOne(field, element, policy, base, overwrite));
            }
            return checked;
        }
        if (value.isString() && value.asString().contains(",") && field.kind() == Kind.READ_LIST) {
            // sparql "models" also accepts one comma-separated string.
            List<String> parts = new ArrayList<>();
            for (String part : value.asString().split(",")) {
                if (!part.isBlank()) {
                    parts.add(checkOne(field, nodes.stringNode(part.strip()), policy, base, overwrite).asString());
                }
            }
            return nodes.stringNode(String.join(",", parts));
        }
        return checkOne(field, value, policy, base, overwrite);
    }

    private static JsonNode checkOne(Field field, JsonNode value, PathPolicy policy, Path base, boolean overwrite) {
        if (!value.isString()) {
            throw new PathNotAllowedException("\"" + field.key() + "\" must be a path string.");
        }
        String text = value.asString();
        Path checked = switch (field.kind()) {
            case READ, READ_LIST -> policy.checkRead(text, base);
            case READ_IF_EXISTS -> checkIfPath(text, policy, base);
            case WRITE_FILE -> policy.checkWrite(text, base, overwrite);
            case WRITE_DIR -> policy.checkWrite(text, base, true);
        };
        return checked == null ? value : JsonNodeFactory.instance.stringNode(checked.toString());
    }

    /**
     * For a value that is a file path or text (a preset name, an inline query): returns the
     * checked path if it names an existing file, else null (the value stays as it is).
     *
     * <p>A UNC or device value goes to the policy before anything touches it: on Windows, even
     * probing {@code \\host\share} can send the user's credentials to that host. A relative value
     * is looked up in the working directory, where the command itself resolves it, and in
     * {@code base}; whichever exists is checked, so a file outside the roots can't be reached
     * through the working directory.
     */
    private static Path checkIfPath(String text, PathPolicy policy, Path base) {
        if (PathPolicy.isUncOrDevice(text)) {
            return policy.checkRead(text, base);
        }
        if (text.isBlank() || text.length() > 4096 || text.contains("\n")) {
            return null; // inline query text, not a path
        }
        Path p;
        try {
            p = Path.of(text.strip());
        } catch (InvalidPathException e) {
            return null;
        }
        List<Path> candidates = p.isAbsolute() ? List.of(p)
                : List.of(Path.of("").toAbsolutePath().resolve(p), base.resolve(p));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return policy.checkRead(candidate);
            }
        }
        return null;
    }
}
