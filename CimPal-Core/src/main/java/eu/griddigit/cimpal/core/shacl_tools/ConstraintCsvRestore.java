/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.UntrustedText;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Rebuilds SHACL .ttl files from an EA constraint CSV, the inverse of the GUI's "EA Import Format"
 * export: each row's {@code Payload} (or, from older EA exports, {@code NotesWithoutPayload}) is a
 * verbatim Turtle block; rows are grouped by {@code SourceFile}, header rows first, duplicates once.
 * GUI: Tools ▸ SHACL Constraints ▸ Restore ttl from constraint csv; CLI: {@code csv2shacl}.
 *
 * <p>Output names come from the CSV, so they are reduced to a plain file name and must resolve
 * inside the output folder: a name with a drive or device colon, a control character, or that
 * still escapes the folder after normalisation is refused. Each restored file is parsed as Turtle
 * before it replaces anything, so a corrupt payload never leaves a broken file behind.
 */
public final class ConstraintCsvRestore {

    /** What one CSV gave: rows read, payload blocks written, and the .ttl files written. */
    public record Result(Path csvFile, int csvRowsRead, int payloadBlocksWritten, List<Path> ttlFiles) {
    }

    /**
     * CON, PRN, AUX, NUL, CONIN$, CONOUT$, COM0-9 and LPT0-9 (also with superscript ¹²³), with any
     * extension: Windows opens the device, not a file.
     */
    private static final java.util.regex.Pattern WINDOWS_DEVICE =
            java.util.regex.Pattern.compile("(?i)(con|prn|aux|nul|conin\\$|conout\\$|(com|lpt)[0-9¹²³])(\\..*)?");
    /** Characters a file name from the CSV may not contain: separators, drive/stream colon, and those Windows refuses. */
    private static final String UNSAFE_NAME_CHARS = "/\\:<>\"|?*";
    /** Characters before which the EA CSV export puts an apostrophe (formula neutralisation). */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private ConstraintCsvRestore() {
    }

    /**
     * Restores the .ttl files of one CSV into {@code outputFolder} (created if needed; existing files
     * of the same name are replaced). All or nothing: every output name is checked and every file is
     * written and parsed before the first one replaces anything.
     *
     * @throws IOException when the CSV can't be read or has no Payload column, an output name is
     *                     unsafe, or a restored file is not valid Turtle; nothing is written then
     */
    public static Result restore(Path csvFile, Path outputFolder) throws IOException {
        CsvTable csv = readCsvTable(csvFile);
        if (csv.rows.isEmpty()) {
            return new Result(csvFile, 0, 0, List.of());
        }
        if (!csv.headerIndex.containsKey("payload")) {
            throw new IOException("CSV has no Payload column: " + csvFile.getFileName());
        }

        // Group by output name, ignoring case: EQ.ttl and eq.ttl are one file on Windows and macOS.
        String defaultSourceFile = findHeaderSourceFile(csv, csvFile);
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, List<CsvRow>> rowsBySourceFile = new LinkedHashMap<>();
        for (CsvRow row : csv.rows) {
            if (payload(row).isBlank()) continue;
            String sourceFile = row.get("SourceFile");
            if (sourceFile.isBlank()) sourceFile = defaultSourceFile;
            String name = normalizeTtlFileName(sourceFile);
            String key = name.toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(key, name);
            rowsBySourceFile.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Files.createDirectories(outputFolder);
        Path folder = outputFolder.toRealPath();
        Map<Path, List<CsvRow>> byTarget = new LinkedHashMap<>();
        for (Map.Entry<String, List<CsvRow>> entry : rowsBySourceFile.entrySet()) {
            byTarget.put(safeTarget(folder, spelling.get(entry.getKey())), entry.getValue());
        }

        Map<Path, Path> temps = new LinkedHashMap<>();
        try {
            int blocks = 0;
            for (Map.Entry<Path, List<CsvRow>> entry : byTarget.entrySet()) {
                Path temp = Files.createTempFile(folder, ".cimpal-", ".ttl");
                temps.put(entry.getKey(), temp);
                blocks += writeAndParse(temp, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<Path, Path> entry : temps.entrySet()) {
                Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(csvFile, csv.rows.size(), blocks, new ArrayList<>(byTarget.keySet()));
        } finally {
            for (Path temp : temps.values()) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** {@code name} inside {@code folder}, or an IOException when it would not stay there. */
    static Path safeTarget(Path folder, String name) throws IOException {
        if (name.isEmpty()) {
            throw new IOException("Empty output file name");
        }
        for (char c : name.toCharArray()) {
            if (c < 0x20 || c == 0x7F || UNSAFE_NAME_CHARS.indexOf(c) >= 0) {
                throw new IOException("Unsafe output file name from the CSV: " + forMessage(name));
            }
        }
        if (WINDOWS_DEVICE.matcher(name).matches()) {
            throw new IOException("Output file name from the CSV is a Windows device name: " + forMessage(name));
        }
        Path target;
        try {
            target = folder.resolve(name).normalize();
        } catch (InvalidPathException ex) {
            throw new IOException("Unsafe output file name from the CSV: " + forMessage(name), ex);
        }
        if (!folder.equals(target.getParent())) {
            throw new IOException("Output file name from the CSV leaves the output folder: " + forMessage(name));
        }
        return target;
    }

    /** Untrusted text for an error message: controls, bidi overrides and line breaks neutralised, length bounded. */
    private static String forMessage(String s) {
        return UntrustedText.forAgent(s, LogSanitizer.MAX_LENGTH);
    }

    /** Writes the blocks for {@code target} to {@code temp}, header rows first, and parses the result. */
    private static int writeAndParse(Path temp, Path target, List<CsvRow> rows) throws IOException {
        List<String> headers = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CsvRow row : rows) {
            String payload = payload(row).replace("\r\n", "\n").replace("\r", "\n").trim();
            if (payload.isEmpty() || !seen.add(payload)) continue;
            (isHeaderRow(row) ? headers : shapes).add(payload);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            for (String block : headers) {
                writer.write(block);
                writer.newLine();
                writer.newLine();
            }
            for (String block : shapes) {
                writer.write(block);
                writer.newLine();
                writer.newLine();
            }
        }
        try {
            RDFParser.source(temp).lang(Lang.TURTLE).parse(ModelFactory.createDefaultModel());
        } catch (RiotException ex) {
            throw new IOException(target.getFileName() + " would not be valid Turtle: " + forMessage(ex.getMessage()), ex);
        }
        return headers.size() + shapes.size();
    }

    private static String findHeaderSourceFile(CsvTable csv, Path csvFile) {
        for (CsvRow row : csv.rows) {
            if (!isHeaderRow(row)) continue;
            String sourceFile = row.get("SourceFile");
            if (!sourceFile.isBlank()) return sourceFile;
            String constraintName = row.get("ConstraintName");
            if (constraintName.isBlank()) constraintName = row.get("Constraint Name");
            String prefix = "SHACL:Header::";
            if (constraintName.startsWith(prefix)) return constraintName.substring(prefix.length());
        }
        return stripExtension(csvFile.getFileName().toString()) + ".ttl";
    }

    private static boolean isHeaderRow(CsvRow row) {
        return row.get("PayloadKind").trim().equalsIgnoreCase("Header")
                || row.get("PropertyShape/NodeShape ID").trim().equalsIgnoreCase("__HEADER__")
                || row.get("ConstraintName").trim().startsWith("SHACL:Header")
                || row.get("Constraint Name").trim().startsWith("SHACL:Header");
    }

    /**
     * The row's Turtle: {@code Payload}, or {@code NotesWithoutPayload} from older EA exports. The
     * apostrophe the EA export puts before a leading =, +, -, @, tab or CR is removed: a Turtle block
     * never starts with an apostrophe, and without this every header ({@code '@prefix ...}) broke.
     */
    static String payload(CsvRow row) {
        String payload = row.get("Payload");
        if (payload.isBlank()) payload = row.get("NotesWithoutPayload");
        String trimmed = payload.stripLeading();
        if (trimmed.length() > 1 && trimmed.charAt(0) == '\'' && FORMULA_TRIGGERS.indexOf(trimmed.charAt(1)) >= 0) {
            return trimmed.substring(1);
        }
        return payload;
    }

    /** The last path segment, with a .ttl extension; mixed separators are both treated as separators. */
    static String normalizeTtlFileName(String sourceFile) {
        String s = sourceFile.trim().replace("\\", "/");
        s = s.substring(s.lastIndexOf('/') + 1);
        if (!s.toLowerCase(Locale.ROOT).endsWith(".ttl")) s = stripExtension(s) + ".ttl";
        return s;
    }

    private static String stripExtension(String name) {
        if (name == null || name.isEmpty()) return "restored";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---- CSV -----------------------------------------------------------------

    private static final class CsvTable {
        final Map<String, Integer> headerIndex = new LinkedHashMap<>();
        final List<CsvRow> rows = new ArrayList<>();
    }

    record CsvRow(CsvTable table, List<String> values) {
        /** The value in the named column (case-insensitive), or "" when absent. */
        String get(String column) {
            Integer idx = table.headerIndex.get(column.trim().toLowerCase(Locale.ROOT));
            return idx == null || idx >= values.size() ? "" : values.get(idx);
        }
    }

    private static CsvTable readCsvTable(Path csvFile) throws IOException {
        CsvTable table = new CsvTable();
        List<String> records = readCsvRecords(csvFile);
        if (records.isEmpty()) return table;
        List<String> headers = parseCsvRecord(removeBom(records.getFirst()));
        for (int i = 0; i < headers.size(); i++) {
            table.headerIndex.put(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        for (String record : records.subList(1, records.size())) {
            if (record.isBlank()) continue;
            List<String> values = parseCsvRecord(record);
            while (values.size() < headers.size()) values.add("");
            table.rows.add(new CsvRow(table, values));
        }
        return table;
    }

    /**
     * Records, joining lines while a quoted field spans a line break. A record is open while it has
     * an odd number of quotes (an escaped "" counts twice), tracked per line so a long unterminated
     * field costs linear, not quadratic, time.
     */
    private static List<String> readCsvRecords(Path csvFile) throws IOException {
        List<String> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            StringBuilder record = new StringBuilder();
            boolean inQuotes = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!record.isEmpty()) record.append('\n');
                record.append(line);
                if (line.chars().filter(c -> c == '"').count() % 2 == 1) inQuotes = !inQuotes;
                if (!inQuotes) {
                    records.add(record.toString());
                    record.setLength(0);
                }
            }
            if (!record.isEmpty()) records.add(record.toString());
        }
        return records;
    }

    /** RFC-4180 fields: commas separate, double quotes enclose, "" is a quote inside quotes. */
    private static List<String> parseCsvRecord(String record) {
        List<String> values = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                values.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        values.add(cur.toString());
        return values;
    }

    private static String removeBom(String s) {
        if (s.startsWith("﻿")) return s.substring(1);
        if (s.startsWith("ï»¿")) return s.substring(3);
        return s;
    }
}
