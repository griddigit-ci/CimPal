package eu.griddigit.cimpal.core.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ValidationTools {

    public enum CaseFolder {
        CGMES_SINGLE_PROFILE,
        CGMES_IGM_COMPLETE,
        CGMES_CGM,
        NC_SINGLE,
        UNKNOWN
    }

    public static class MappingRow {
        public final String xmlInputsRaw;
        public final String ttl;
        public final String notes;

        public MappingRow(String xmlInputsRaw, String ttl, String notes) {
            this.xmlInputsRaw = xmlInputsRaw;
            this.ttl = ttl;
            this.notes = notes;
        }
    }

    /**
     * Builds ONE zip per mapping row.
     *
     * Skips only:
     *  - empty xml_inputs OR empty ttl_constraints
     *  - xml_inputs starts with '#'
     *  - first header row
     *
     * @param mappingCsvPath path to mapping csv (UTF-8)
     * @param modelsBaseDir  base directory that contains "Instance/..." etc.
     * @param outputBaseDir  output folder where case subfolders + zips will be created
     */
    public static void buildZips(Path mappingCsvPath, Path modelsBaseDir, Path outputBaseDir) throws IOException {
        List<MappingRow> rows = readMappingCsv(mappingCsvPath);

        Files.createDirectories(outputBaseDir);

        int created = 0;
        int skipped = 0;
        int rowIdx = 0;
        for (MappingRow row : rows) {
            rowIdx++;
            // Skip empty cells
            if (row.xmlInputsRaw == null || row.xmlInputsRaw.trim().isEmpty()
                    || row.ttl == null || row.ttl.trim().isEmpty()) {
                skipped++;
                continue;
            }

            // Expand tokens (single or multiple)
            List<String> tokens = parseXmlInputs(row.xmlInputsRaw);

            // Expand each token into concrete files
            LinkedHashSet<Path> filesToZip = new LinkedHashSet<>();
            for (String token : tokens) {
                List<Path> expanded = expandToken(modelsBaseDir, token);
                if (expanded.isEmpty()) {
                    System.err.println("[WARN][row " + rowIdx + "] No files matched token: [" + token + "] (ttl=" + row.ttl + ")");
                }
                filesToZip.addAll(expanded);
            }

            if (filesToZip.isEmpty()) {
                System.err.println("[WARN][row " + rowIdx + "] Skipping row; nothing matched. xml_inputs=[" + row.xmlInputsRaw + "], ttl=[" + row.ttl + "]");
                skipped++;
                continue;
            }

            CaseFolder cf = categorize(row, tokens);
            Path caseDir = outputBaseDir.resolve(cf.name());
            Files.createDirectories(caseDir);

            String zipName = makeZipName(row, filesToZip);
            Path zipPath = caseDir.resolve(zipName);

            zipFiles(modelsBaseDir, zipPath, filesToZip);
            created++;
        }

        System.out.println("ZIP creation finished. created=" + created + ", skipped=" + skipped);
    }

    // ---------------- CSV reading ----------------

    private static List<MappingRow> readMappingCsv(Path csvPath) throws IOException {
        List<MappingRow> out = new ArrayList<>();
        boolean headerSkipped = false;

        try (BufferedReader br = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Exclude rows whose first cell starts with '#'
                // (User requirement: "cells that starts with #")
                if (line.startsWith("#")) continue;

                // Skip the first title/header row (only once)
                if (!headerSkipped) {
                    // Most common header is: xml_inputs,ttl_constraints,notes
                    // We'll skip the first non-empty, non-comment line if it starts with "xml_inputs"
                    if (line.toLowerCase(Locale.ROOT).startsWith("xml_inputs")) {
                        headerSkipped = true;
                        continue;
                    }
                    // If the file doesn't have a header, still treat the first row as data.
                    headerSkipped = true;
                }

                List<String> cols = parseCsvLine(line);
                if (cols.size() < 2) continue;

                String xml = cols.get(0).trim();
                String ttl = cols.get(1).trim();
                String notes = cols.size() >= 3 ? cols.get(2).trim() : "";

                // Also exclude if first cell begins with '#'
                if (xml.startsWith("#")) continue;

                out.add(new MappingRow(xml, ttl, notes));
            }
        }
        return out;
    }

    /**
     * Minimal CSV parser handling quoted fields with commas.
     */
    private static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                // escaped quote: ""
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols;
    }

    // ---------------- tokenization ----------------

    private static List<String> parseXmlInputs(String xmlInputsRaw) {
        String s = xmlInputsRaw.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("ZIP:")) {
            s = s.substring(4).trim();
        }
        String[] parts = s.split(";");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            String t = cleanToken(p);
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    // ---------------- wildcard expansion ----------------

    /**
     * Expands a token like:
     * - Instance/Grid/IGM_Belgovia/*EQ*.xml
     * - Instance/Grid/CommonAndBoundaryData/CommonData_and_Boundary_merged.xml
     */
    private static List<Path> expandToken(Path modelsBaseDir, String token) throws IOException {
        String norm = token.replace("\\", "/");
        boolean hasGlob = norm.contains("*") || norm.contains("?") || norm.contains("[");

        if (!hasGlob) {
            Path p = modelsBaseDir.resolve(norm).normalize();
            if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                return List.of(p);
            }
            return List.of();
        }


        int lastSlash = norm.lastIndexOf('/');
        String parent = (lastSlash >= 0) ? norm.substring(0, lastSlash) : "";
        String pattern = (lastSlash >= 0) ? norm.substring(lastSlash + 1) : norm;

        Path parentDir = modelsBaseDir.resolve(parent).normalize();
        if (!Files.isDirectory(parentDir)) return List.of();

        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parentDir)) {
            for (Path child : ds) {
                if (Files.isRegularFile(child) && matcher.matches(child.getFileName())) {
                    matches.add(child);
                }
            }
        }

        matches.sort(Comparator.comparing(Path::toString));
        return matches;
    }

    // ---------------- categorization (for subfolders) ----------------

    private static CaseFolder categorize(MappingRow row, List<String> tokens) {
        String ttl = row.ttl == null ? "" : row.ttl;

        if (ttl.startsWith("NC_")) return CaseFolder.NC_SINGLE;

        boolean mentionsCGM = tokens.stream().anyMatch(t -> t.replace("\\", "/").contains("Instance/Grid/CGM"));
        if (mentionsCGM) return CaseFolder.CGMES_CGM;

        boolean hasBoundary = tokens.stream().anyMatch(t -> t.contains("CommonData_and_Boundary_merged.xml"));
        boolean hasEQ = tokens.stream().anyMatch(t -> t.contains("*EQ*") || t.contains("_EQ"));
        boolean hasSSH = tokens.stream().anyMatch(t -> t.contains("*SSH*") || t.contains("_SSH"));
        boolean hasTP = tokens.stream().anyMatch(t -> t.contains("*TP*") || t.contains("_TP"));
        boolean hasSV = tokens.stream().anyMatch(t -> t.contains("*SV*") || t.contains("_SV"));

        if (hasBoundary && hasEQ && hasSSH && hasTP && hasSV) return CaseFolder.CGMES_IGM_COMPLETE;

        if (ttl.startsWith("CGMES_")) return CaseFolder.CGMES_SINGLE_PROFILE;

        return CaseFolder.UNKNOWN;
    }

    // ---------------- zip writing ----------------

    private static void zipFiles(Path modelsBaseDir, Path zipPath, Collection<Path> files) throws IOException {
        Files.deleteIfExists(zipPath);

        // Prevent duplicate names if two files have the same filename
        Map<String, Integer> nameCounts = new HashMap<>();

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            for (Path f : files) {

                // Safety: only zip real XML files
                if (!Files.isRegularFile(f)) continue;
                String fileName = f.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xml")) continue;

                String entryName = fileName;

                // If a filename repeats, prefix with counter
                int n = nameCounts.getOrDefault(entryName, 0);
                if (n > 0) {
                    int dot = entryName.lastIndexOf('.');
                    String base = (dot > 0) ? entryName.substring(0, dot) : entryName;
                    String ext  = (dot > 0) ? entryName.substring(dot) : "";
                    entryName = base + "__" + n + ext;
                }
                nameCounts.put(fileName, n + 1);

                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
    }

    private static String makeZipName(MappingRow row, Collection<Path> filesToZip) {
        String ttlBase = stripExt(safe(row.ttl));
        String area = guessArea(filesToZip);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return safe(area + "__" + ttlBase + "__" + ts) + ".zip";
    }

    private static String guessArea(Collection<Path> files) {
        for (Path p : files) {
            String s = p.toString().replace("\\", "/");

            int igmIdx = s.indexOf("/IGM_");
            if (igmIdx >= 0) {
                String rest = s.substring(igmIdx + 1);
                return rest.split("/")[0]; // IGM_Belgovia
            }

            int ncIdx = s.indexOf("/NetworkCode/");
            if (ncIdx >= 0) {
                String rest = s.substring(ncIdx + "/NetworkCode/".length());
                return "NC_" + rest.split("/")[0]; // NC_Galia
            }

            if (s.contains("/Grid/CGM")) return "CGM";
        }
        return "UNKNOWN";
    }

    private static String cleanToken(String t) {
        if (t == null) return "";
        // remove BOM + NBSP, strip quotes, trim
        return t.replace("\uFEFF", "")
                .replace("\u00A0", " ")
                .replace("\"", "")
                .trim();
    }
    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return s.replaceAll("[^A-Za-z0-9_\\-]+", "_");
    }

    // ---------------- example CLI ----------------

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java ValidationZipBuilder <mapping.csv> <modelsBaseDir> <outputBaseDir>");
            System.out.println("Example: java ValidationZipBuilder mapping.csv C:/relicapgrid C:/out/zips");
            return;
        }
        buildZips(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
    }
}