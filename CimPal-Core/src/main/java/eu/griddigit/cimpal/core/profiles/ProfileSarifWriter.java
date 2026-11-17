/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.Finding;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes the outcome of a profile-driven validation as SARIF 2.1.0, the format GitHub code
 * scanning reads, so that findings become alerts that point at the file and line of the object,
 * close when the data is fixed and open again on a regression.
 *
 * <p>A rule is a pair of source shape and constraint component, with a stable id (the shape's
 * local name and the component's). In {@link Mode#RULE} mode a result is a rule in one document:
 * it points at the first occurrence and counts the others, so an alert closes when that document
 * no longer breaks the rule. In {@link Mode#OBJECT} mode a result is one finding on one object.
 * Each result carries a fingerprint made of the rule, the file and (per object) the object, never
 * the line, so an alert keeps its identity when the file is edited above it.
 *
 * <p>An incomplete or partial run is never written as if it were clean: code scanning closes the
 * alerts a new upload no longer reports, so a run that skipped documents or lost constraints adds
 * one {@code error} result, rule {@value #INCOMPLETE_RULE}, that says what was not checked, and its
 * invocation is marked unsuccessful.
 *
 * <p>GitHub rejects a file with more than {@value #MAX_RESULTS} results, so a run that would produce
 * more is refused rather than written. Paths are relative to a base folder (the repository root in
 * CI); a document inside a ZIP archive points at the archive.
 */
public final class ProfileSarifWriter {

    /** What one result stands for. */
    public enum Mode { RULE, OBJECT }

    /** GitHub code scanning rejects a run with more results than this. */
    public static final int MAX_RESULTS = 25_000;

    /** The rule of the result that says a run did not check everything. */
    public static final String INCOMPLETE_RULE = "CimPal/IncompleteRun";

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final int SAMPLES = 5;

    /**
     * @param base        folder the paths in the file are relative to; every document must be in it
     * @param category    the code-scanning category (several runs of one repository need different ones)
     * @param mode        what a result stands for
     * @param toolVersion the CimPal version, for the tool record
     * @param xmlBase     base IRI for relative IRIs in the documents, as for validation
     */
    public record Settings(Path base, String category, Mode mode, String toolVersion, String xmlBase) {
    }

    private record Rule(String key, String id, String shape, String component, String name, String description,
                        String level) {
    }

    /** One finding in one document (or in none, for a finding about the shapes). */
    private record Placement(Finding finding, InstanceHeader document) {
    }

    private ProfileSarifWriter() {
    }

    /**
     * The SARIF document for {@code summary}.
     *
     * @throws IllegalArgumentException if a document is outside the base folder, or the run would
     *                                  give more results than GitHub accepts
     */
    public static String toJson(ProfileValidationSummary summary, Settings settings) {
        Path base = settings.base().toAbsolutePath().normalize();

        // Rules, by shape and component, in a stable order.
        Map<String, List<Placement>> byRule = new TreeMap<>();
        InstanceHeader anyDocument = null;
        for (ProfileValidationSummary.GroupResult group : summary.groups()) {
            if (anyDocument == null && !group.reportedDocuments().isEmpty()) {
                anyDocument = group.reportedDocuments().getFirst();
            }
        }
        Map<String, InstanceHeader> firstOfGroup = new HashMap<>();
        summary.groups().forEach(g -> {
            if (!g.reportedDocuments().isEmpty()) {
                firstOfGroup.put(g.name(), g.reportedDocuments().getFirst());
            }
        });
        for (Finding finding : summary.findings()) {
            String key = ruleKey(finding.result());
            List<Placement> list = byRule.computeIfAbsent(key, k -> new ArrayList<>());
            if (finding.headers().isEmpty()) {
                // About the shapes: GitHub shows a result only with a location, so line 1 of a file of its group.
                list.add(new Placement(finding, firstOfGroup.getOrDefault(finding.group(), anyDocument)));
            } else {
                finding.headers().forEach(h -> list.add(new Placement(finding, h)));
            }
        }
        Map<String, Rule> rules = new LinkedHashMap<>();
        Map<String, Integer> idCount = new HashMap<>();
        byRule.forEach((key, placements) -> {
            SHACLValidationResult r = placements.getFirst().finding().result();
            String id = local(r.getSourceShape()) + "/" + component(r.getConstraintComponent());
            int n = idCount.merge(id, 1, Integer::sum);
            if (n > 1) {
                id = id + "-" + n;
            }
            String name = blank(r.getName()) ? id : r.getName();
            rules.put(key, new Rule(key, id, r.getSourceShape(), r.getConstraintComponent(), name,
                    r.getDescription(), level(r.getSeverity())));
        });

        // Results.
        List<List<Placement>> results = new ArrayList<>();
        byRule.forEach((key, placements) -> {
            Map<String, List<Placement>> grouped = new LinkedHashMap<>();
            for (Placement p : placements) {
                String document = p.document() == null ? "" : p.document().id();
                String resultKey = settings.mode() == Mode.RULE ? document
                        : document + "|" + identity(p.finding());
                grouped.computeIfAbsent(resultKey, k -> new ArrayList<>()).add(p);
            }
            results.addAll(grouped.values());
        });
        boolean incomplete = !summary.complete() || summary.partial();
        if (results.size() + (incomplete ? 1 : 0) > MAX_RESULTS) {
            throw new IllegalArgumentException("The run gives " + results.size() + " SARIF results, more than the "
                    + MAX_RESULTS + " GitHub code scanning accepts in one file"
                    + (settings.mode() == Mode.OBJECT ? "; use one result per rule and document instead" : ""));
        }

        Map<String, Map<String, XmlLineLocator.Located>> lines = locateAll(results, settings.xmlBase());

        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"$schema\": ").append(str(SCHEMA)).append(",\n  \"version\": \"2.1.0\",\n  \"runs\": [{\n");
        sb.append("    \"tool\": {\"driver\": {\"name\": \"CimPal\", \"informationUri\": \"https://github.com/griddigit-ci/CimPal\"");
        if (!blank(settings.toolVersion())) {
            sb.append(", \"version\": ").append(str(settings.toolVersion()));
        }
        sb.append(", \"rules\": [");
        List<Rule> ruleList = new ArrayList<>(rules.values());
        for (int i = 0; i < ruleList.size(); i++) {
            Rule rule = ruleList.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("      {\"id\": ").append(str(rule.id()))
                    .append(", \"name\": ").append(str(cut(rule.name(), 255)))
                    .append(", \"shortDescription\": {\"text\": ").append(str(cut(rule.name(), 1024))).append("}");
            if (!blank(rule.description())) {
                sb.append(", \"fullDescription\": {\"text\": ").append(str(cut(rule.description(), 1024))).append("}");
            }
            if (webAddress(rule.shape())) {
                sb.append(", \"helpUri\": ").append(str(rule.shape()));
            }
            sb.append(", \"defaultConfiguration\": {\"level\": ").append(str(rule.level())).append("}")
                    .append(", \"properties\": {\"sourceShape\": ").append(str(rule.shape()))
                    .append(", \"constraintComponent\": ").append(str(rule.component())).append("}}");
        }
        if (incomplete) {
            sb.append(ruleList.isEmpty() ? "\n" : ",\n").append("      {\"id\": ").append(str(INCOMPLETE_RULE))
                    .append(", \"name\": \"Validation run incomplete\", \"shortDescription\": {\"text\": "
                            + "\"Part of the data or the shapes was not checked\"}, \"defaultConfiguration\": "
                            + "{\"level\": \"error\"}}");
        }
        sb.append(ruleList.isEmpty() && !incomplete ? "]}},\n" : "\n    ]}},\n");
        sb.append("    \"invocations\": [{\"executionSuccessful\": ").append(!incomplete).append("}],\n");
        sb.append("    \"automationDetails\": {\"id\": ").append(str(category(settings.category()) + "/")).append("},\n");
        sb.append("    \"properties\": {\"complete\": ").append(summary.complete())
                .append(", \"partial\": ").append(summary.partial())
                .append(", \"skippedDocuments\": ").append(summary.skipped().size())
                .append(", \"gaps\": ").append(summary.gaps().size()).append("},\n");
        sb.append("    \"results\": [");
        List<String> ruleKeys = new ArrayList<>(rules.keySet());
        for (int i = 0; i < results.size(); i++) {
            List<Placement> occurrences = results.get(i);
            Placement first = occurrences.getFirst();
            Rule rule = rules.get(ruleKey(first.finding().result()));
            sb.append(i == 0 ? "\n" : ",\n").append("      ")
                    .append(result(rule, ruleKeys.indexOf(rule.key()), occurrences, base, lines, settings.mode()));
        }
        if (incomplete) {
            sb.append(results.isEmpty() ? "\n" : ",\n").append("      ")
                    .append(incompleteResult(summary, ruleList.size(), anyDocument, base));
        }
        sb.append(results.isEmpty() && !incomplete ? "]\n" : "\n    ]\n");
        sb.append("  }]\n}\n");
        return sb.toString();
    }

    private static String result(Rule rule, int ruleIndex, List<Placement> occurrences, Path base,
                                 Map<String, Map<String, XmlLineLocator.Located>> lines, Mode mode) {
        InstanceHeader document = occurrences.getFirst().document();
        // The occurrence earliest in the file is the one the alert points at.
        List<Placement> sorted = new ArrayList<>(occurrences);
        sorted.sort(Comparator.comparingInt(p -> line(p, lines)));
        Placement shown = sorted.getFirst();
        int line = line(shown, lines);
        SHACLValidationResult r = shown.finding().result();

        List<String> text = new ArrayList<>();
        text.add(blank(r.getMessage()) ? rule.id() + " violated" : r.getMessage());
        if (!blank(r.getPath())) {
            text.add("Path: " + r.getPath());
        }
        if (mode == Mode.OBJECT && !blank(r.getValue())) {
            text.add("Value: " + r.getValue());
        }
        boolean aboutShapes = shown.finding().headers().isEmpty();
        if (aboutShapes) {
            text.add("This is about the shapes, not the data; it is shown on the first line of a document of group "
                    + shown.finding().group() + ".");
        } else {
            Set<String> objects = new LinkedHashSet<>();
            sorted.forEach(p -> objects.add(String.valueOf(p.finding().result().getFocusNode())));
            List<String> samples = new ArrayList<>(objects).subList(0, Math.min(SAMPLES, objects.size()));
            text.add((mode == Mode.RULE ? objects.size() + " object(s): " : "Object: ") + String.join(", ", samples)
                    + (objects.size() > SAMPLES ? ", ..." : ""));
        }
        if (document != null && document.entry() != null) {
            text.add("In archive entry " + document.entry() + ".");
        }

        String uri = document == null ? null : uri(base, document.file());
        String fingerprint = sha(rule.id() + "|" + (uri == null ? "" : uri) + "|" + (document == null ? "" : document.entry())
                + (mode == Mode.OBJECT ? "|" + identity(shown.finding()) : ""));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ruleId\": ").append(str(rule.id())).append(", \"ruleIndex\": ").append(ruleIndex)
                .append(", \"level\": ").append(str(level(r.getSeverity())))
                .append(", \"message\": {\"text\": ").append(str(String.join("\n", text))).append("}");
        if (uri != null) {
            sb.append(", \"locations\": [{\"physicalLocation\": {\"artifactLocation\": {\"uri\": ").append(str(uri))
                    .append(", \"uriBaseId\": \"%SRCROOT%\"}, \"region\": {\"startLine\": ").append(line)
                    .append(", \"endLine\": ").append(line).append("}}");
            if (!aboutShapes && shown.finding().focusIri() != null) {
                sb.append(", \"logicalLocations\": [{\"fullyQualifiedName\": ").append(str(shown.finding().focusIri()))
                        .append(", \"kind\": \"object\"}]");
            }
            sb.append("}]");
        }
        sb.append(", \"partialFingerprints\": {\"primaryLocationLineHash\": ").append(str(fingerprint + ":1")).append("}");
        sb.append(", \"occurrenceCount\": ").append(occurrences.size());
        sb.append(", \"properties\": {\"group\": ").append(str(shown.finding().group()))
                .append(", \"document\": ").append(str(document == null ? null : document.source())).append("}}");
        return sb.toString();
    }

    /** The result that says what the run did not check, so its alerts are not taken for fixed. */
    private static String incompleteResult(ProfileValidationSummary summary, int ruleIndex, InstanceHeader anyDocument,
                                           Path base) {
        List<String> text = new ArrayList<>();
        text.add("The validation run did not check everything; findings missing from this run are not fixed.");
        if (summary.partial()) {
            text.add("The result limit cut part of the validation short.");
        }
        summary.skipped().stream().limit(20).forEach(s -> text.add("Not validated: " + s.source() + " (" + s.reason() + ")"));
        if (summary.skipped().size() > 20) {
            text.add("... and " + (summary.skipped().size() - 20) + " more documents not validated.");
        }
        summary.gaps().stream().limit(20).forEach(g -> text.add("Gap: " + g));
        summary.warnings().stream().limit(20).forEach(w -> text.add("Warning: " + w));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ruleId\": ").append(str(INCOMPLETE_RULE)).append(", \"ruleIndex\": ").append(ruleIndex)
                .append(", \"level\": \"error\", \"message\": {\"text\": ").append(str(String.join("\n", text))).append("}");
        if (anyDocument != null) {
            sb.append(", \"locations\": [{\"physicalLocation\": {\"artifactLocation\": {\"uri\": ")
                    .append(str(uri(base, anyDocument.file()))).append(", \"uriBaseId\": \"%SRCROOT%\"}, "
                            + "\"region\": {\"startLine\": 1, \"endLine\": 1}}}]");
        }
        sb.append(", \"partialFingerprints\": {\"primaryLocationLineHash\": ").append(str(sha(INCOMPLETE_RULE) + ":1"))
                .append("}}");
        return sb.toString();
    }

    /** One pass per plain file over the objects wanted in it; archives are not looked into. */
    private static Map<String, Map<String, XmlLineLocator.Located>> locateAll(List<List<Placement>> results,
                                                                              String xmlBase) {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        Map<String, InstanceHeader> documents = new HashMap<>();
        for (List<Placement> occurrences : results) {
            for (Placement p : occurrences) {
                InstanceHeader d = p.document();
                if (d != null && d.entry() == null && p.finding().focusIri() != null && !p.finding().headers().isEmpty()) {
                    wanted.computeIfAbsent(d.id(), k -> new LinkedHashSet<>()).add(p.finding().focusIri());
                    documents.put(d.id(), d);
                }
            }
        }
        Map<String, Map<String, XmlLineLocator.Located>> lines = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : wanted.entrySet()) {
            try {
                lines.put(e.getKey(), XmlLineLocator.locate(documents.get(e.getKey()).file(), e.getValue(), xmlBase));
            } catch (IOException ex) {
                // A file the RDF parser read but this pass does not (a DOCTYPE, say): its findings
                // point at line 1. Only the precision of the location is lost.
                lines.put(e.getKey(), Map.of());
            }
        }
        return lines;
    }

    private static int line(Placement p, Map<String, Map<String, XmlLineLocator.Located>> lines) {
        if (p.document() == null || p.finding().focusIri() == null) {
            return 1;
        }
        XmlLineLocator.Located located = lines.getOrDefault(p.document().id(), Map.of()).get(p.finding().focusIri());
        return located == null ? 1 : located.lineOf(p.finding().pathIri());
    }

    /** The document's path relative to the base, with forward slashes, escaped as a URI. */
    private static String uri(Path base, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new IllegalArgumentException("A document is outside the SARIF base folder " + base.getFileName()
                    + "; give a base that contains the models folder");
        }
        String relative = base.relativize(absolute).toString().replace('\\', '/');
        try {
            return new URI(null, null, relative, null).getRawPath();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Not a usable path in SARIF: " + LogSanitizer.forLog(relative));
        }
    }

    private static boolean webAddress(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static String ruleKey(SHACLValidationResult r) {
        return r.getSourceShape() + "|" + r.getConstraintComponent();
    }

    private static String identity(Finding f) {
        SHACLValidationResult r = f.result();
        return (f.focusIri() != null ? f.focusIri() : r.getFocusNode()) + "|" + (f.pathIri() != null ? f.pathIri()
                : r.getPath()) + "|" + r.getValue() + "|" + r.getMessage();
    }

    static String level(String severity) {
        String s = severity == null ? "" : severity.toLowerCase(Locale.ROOT);
        return s.endsWith("violation") ? "error" : s.endsWith("warning") ? "warning" : "note";
    }

    private static String local(String value) {
        if (blank(value)) {
            return "shape";
        }
        String v = value.strip();
        return v.substring(Math.max(v.lastIndexOf('#'), Math.max(v.lastIndexOf('/'), v.lastIndexOf(':'))) + 1);
    }

    private static String component(String value) {
        String c = local(value);
        return c.endsWith("ConstraintComponent") && c.length() > "ConstraintComponent".length()
                ? c.substring(0, c.length() - "ConstraintComponent".length()) : c;
    }

    private static String category(String category) {
        String c = blank(category) ? "cimpal-profiles" : category.strip();
        return c.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String sha(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** A JSON string; control characters and lone surrogates are escaped, so the file always parses. */
    static String str(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    boolean loneSurrogate = Character.isSurrogate(c) && !(Character.isHighSurrogate(c)
                            && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1)));
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || loneSurrogate) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
