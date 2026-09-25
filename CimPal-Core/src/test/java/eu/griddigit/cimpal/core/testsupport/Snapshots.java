/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Golden-file (snapshot) assertions for RDF, Excel workbooks and JSON.
 *
 * <p>Snapshots live under {@code src/test/resources/snapshots/<feature>/} of the module running
 * the test (surefire runs with the module directory as working directory). A mismatch fails with
 * a readable diff; a missing snapshot fails too. Snapshots are only written when the run has
 * {@code -Dsnapshot.update=true}; review the resulting diff before committing it.
 *
 * <pre>{@code
 * Snapshots snapshots = Snapshots.forFeature("mapping-validation");
 * snapshots.assertIsomorphic("report", reportModel);
 * snapshots.assertExcelEquals("workbook", xlsx, Normalizer.timestamps(), Normalizer.paths(tempDir));
 * }</pre>
 */
public final class Snapshots {

    /** System property that switches every snapshot assertion into write mode. */
    public static final String UPDATE_PROPERTY = "snapshot.update";

    private static final Path DEFAULT_ROOT = Path.of("src", "test", "resources", "snapshots");
    private static final int MAX_DIFF_LINES = 40;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path directory;

    private Snapshots(Path directory) {
        this.directory = directory;
    }

    /** Snapshots for one feature, stored in {@code src/test/resources/snapshots/<feature>/}. */
    public static Snapshots forFeature(String feature) {
        return new Snapshots(DEFAULT_ROOT.resolve(feature));
    }

    /** Snapshots stored in an explicit directory (used by the harness's own tests). */
    public static Snapshots at(Path directory) {
        return new Snapshots(directory);
    }

    public static boolean updateMode() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    // ---- RDF ----

    /** Compares {@code actual} with snapshot {@code <name>.ttl} by graph isomorphism (blank nodes allowed). */
    public void assertIsomorphic(String name, Model actual) {
        Path file = directory.resolve(name + ".ttl");
        if (updateMode()) {
            write(file, toTurtle(actual));
            return;
        }
        requireExists(file);
        Model expected = ModelFactory.createDefaultModel();
        RDFDataMgr.read(expected, file.toString(), Lang.TURTLE);
        if (!expected.isIsomorphicWith(actual)) {
            throw new AssertionError("RDF snapshot mismatch: " + file + "\n" + graphDiff(expected, actual)
                    + rerunHint());
        }
    }

    /** Compares two graphs by isomorphism, no snapshot file involved. */
    public static void assertIsomorphic(Model expected, Model actual) {
        if (!expected.isIsomorphicWith(actual)) {
            throw new AssertionError("Graphs are not isomorphic\n" + graphDiff(expected, actual));
        }
    }

    // ---- Excel ----

    /**
     * Flattens every sheet of {@code workbook} to CSV-like text and compares it with snapshot
     * {@code <name>.csv}. Each cell passes through the normalizers first.
     */
    public void assertExcelEquals(String name, Path workbook, Normalizer... normalizers) {
        assertTextEquals(name + ".csv", flattenWorkbook(workbook, Normalizer.chain(normalizers)));
    }

    /** One {@code ## <sheet>} header per sheet, then one line per row, cells joined with {@code ,}. */
    public static String flattenWorkbook(Path workbook, Normalizer normalizer) {
        DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder();
        try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
            for (Sheet sheet : book) {
                out.append("## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        String text = cell == null ? "" : cellText(cell, formatter);
                        cells.add(csvEscape(normalizer.apply(text)));
                    }
                    out.append(String.join(",", cells).replaceAll(",+$", "")).append('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read workbook " + workbook, e);
        }
        return out.toString();
    }

    // ---- JSON ----

    /**
     * Compares {@code json} with snapshot {@code <name>.json} after sorting object keys and passing
     * every string value through the normalizers ({@link Normalizer#timestamps()} and
     * {@link Normalizer#absolutePaths()} are always applied last).
     */
    public void assertJsonEquals(String name, String json, Normalizer... normalizers) {
        assertTextEquals(name + ".json", normalizeJson(json, normalizers));
    }

    public static String normalizeJson(String json, Normalizer... normalizers) {
        Normalizer normalizer = Normalizer.chain(normalizers)
                .andThen(Normalizer.timestamps())
                .andThen(Normalizer.absolutePaths());
        JsonNode tree = JSON.readTree(json);
        return JSON.writeValueAsString(canonical(tree, normalizer)) + "\n";
    }

    // ---- text ----

    /** Compares arbitrary text with snapshot file {@code fileName} (line endings normalized). */
    public void assertTextEquals(String fileName, String actual) {
        Path file = directory.resolve(fileName);
        String normalizedActual = actual.replace("\r\n", "\n");
        if (updateMode()) {
            write(file, normalizedActual);
            return;
        }
        requireExists(file);
        String expected = read(file);
        if (!expected.equals(normalizedActual)) {
            throw new AssertionError("Snapshot mismatch: " + file + "\n"
                    + lineDiff(expected, normalizedActual) + rerunHint());
        }
    }

    // ---- internals ----

    private static JsonNode canonical(JsonNode node, Normalizer normalizer) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        if (node.isObject()) {
            ObjectNode sorted = nodes.objectNode();
            for (String key : new TreeSet<>(node.propertyNames())) {
                sorted.set(key, canonical(node.get(key), normalizer));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = nodes.arrayNode();
            for (JsonNode element : node) {
                array.add(canonical(element, normalizer));
            }
            return array;
        }
        if (node.isString()) {
            return nodes.stringNode(normalizer.apply(node.stringValue()));
        }
        return node;
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        return switch (cell.getCellType()) {
            case FORMULA -> "=" + cell.getCellFormula();
            default -> formatter.formatCellValue(cell);
        };
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
        }
        return value;
    }

    private static String toTurtle(Model model) {
        StringWriter out = new StringWriter();
        RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        return out.toString();
    }

    private static String graphDiff(Model expected, Model actual) {
        return "  only in expected (" + expected.difference(actual).size() + "):\n"
                + statements(expected.difference(actual))
                + "  only in actual (" + actual.difference(expected).size() + "):\n"
                + statements(actual.difference(expected))
                + "  (blank-node statements can appear on both sides even when only labels differ)\n";
    }

    private static String statements(Model model) {
        List<String> lines = new ArrayList<>();
        for (Statement statement : model.listStatements().toList()) {
            lines.add("    " + statement.asTriple());
        }
        lines.sort(null);
        if (lines.size() > MAX_DIFF_LINES) {
            int more = lines.size() - MAX_DIFF_LINES;
            lines = new ArrayList<>(lines.subList(0, MAX_DIFF_LINES));
            lines.add("    ... " + more + " more");
        }
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    /** Unified-style diff (LCS based) of two texts, limited to {@value #MAX_DIFF_LINES} change lines. */
    static String lineDiff(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder("  --- expected (snapshot)\n  +++ actual\n");
        int i = 0, j = 0, shown = 0;
        while ((i < a.length || j < b.length) && shown < MAX_DIFF_LINES) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                i++;
                j++;
            } else if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.append("  +").append(j + 1).append(": ").append(b[j++]).append('\n');
                shown++;
            } else {
                out.append("  -").append(i + 1).append(": ").append(a[i++]).append('\n');
                shown++;
            }
        }
        if (shown == MAX_DIFF_LINES) {
            out.append("  ... diff truncated\n");
        }
        return out.toString();
    }

    private static String rerunHint() {
        return "If the new output is correct, rerun with -D" + UPDATE_PROPERTY + "=true and review the diff.";
    }

    private static void requireExists(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("Snapshot " + file.toAbsolutePath() + " does not exist. "
                    + "Create it by running the test with -D" + UPDATE_PROPERTY + "=true and review it before committing.");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write snapshot " + file, e);
        }
    }

    /** Parses Turtle text into a model, for building expected graphs inline. */
    public static Model turtle(String ttl) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8)), Lang.TURTLE);
        return model;
    }
}
