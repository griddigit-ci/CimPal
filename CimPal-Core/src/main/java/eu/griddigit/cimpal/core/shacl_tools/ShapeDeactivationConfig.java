/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.AtomicFiles;
import org.apache.jena.shared.PrefixMapping;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes a SHACL configuration file: an {@code owl:Ontology} that imports constraint files and
 * deactivates some of their shapes with {@code sh:deactivated true}, in the layout of a
 * hand-written configuration file. Each deactivated shape is preceded by a comment saying why.
 * <p>
 * Written as text rather than through a Jena writer, which cannot emit comments; the tests parse
 * the output back.
 */
public final class ShapeDeactivationConfig {

    /** A shape to switch off: its IRI, its {@code rdf:type}s and the reason, written as a comment. */
    public record Entry(String iri, List<String> types, String comment) {
        public Entry {
            Objects.requireNonNull(iri, "iri");
            types = types == null || types.isEmpty() ? List.of(SH.PropertyShape.getURI()) : List.copyOf(types);
            comment = comment == null ? "" : comment;
        }
    }

    /**
     * The configuration.
     *
     * @param ontologyIri the configuration's IRI; it is the {@code @base} and, followed by {@code #},
     *                    the namespace of {@code prefix}
     * @param prefix      prefix for {@code ontologyIri#}, for example {@code config}
     * @param title       {@code dcterms:title}
     * @param description {@code dcterms:description}, may be empty
     * @param version     {@code owl:versionInfo}, may be empty
     * @param imports     what {@code owl:imports} names, in order: file URIs or web URLs
     * @param entries     the shapes to deactivate
     * @param prefixes    prefixes for the shape IRIs, usually those of the files that declare them
     * @param issued      {@code dcterms:issued}, and the date of {@code dcterms:modified}; {@code null} for now
     * @param identifier  {@code dcterms:identifier}; {@code null} for a new {@code urn:uuid:}
     */
    public record Options(String ontologyIri, String prefix, String title, String description, String version,
                          List<String> imports, List<Entry> entries, Map<String, String> prefixes,
                          Instant issued, String identifier) {
        public Options {
            Objects.requireNonNull(ontologyIri, "ontologyIri");
            prefix = prefix == null || prefix.isBlank() ? "config" : prefix.trim();
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            version = version == null ? "" : version;
            imports = List.copyOf(imports);
            entries = List.copyOf(entries);
            prefixes = prefixes == null ? Map.of() : Map.copyOf(prefixes);
            issued = (issued == null ? Instant.now() : issued).truncatedTo(ChronoUnit.SECONDS);
            identifier = identifier == null || identifier.isBlank() ? "urn:uuid:" + UUID.randomUUID() : identifier;
        }
    }

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*");
    /** A conservative PN_LOCAL: what every Turtle reader accepts without escapes. */
    private static final Pattern LOCAL_NAME = Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_.\\-]*[A-Za-z0-9_\\-])?");
    /** Characters an IRI written between angle brackets must not contain. */
    private static final Pattern BAD_IRI_CHAR = Pattern.compile("[\\x00-\\x20<>\"{}|^`\\\\]");

    private ShapeDeactivationConfig() {
    }

    /**
     * Checks a value for {@code owl:imports}: an absolute IRI that Turtle can carry. Returns the
     * reason it cannot be used, or {@code null} when it can.
     */
    public static String importProblem(String iri) {
        if (iri == null || iri.isBlank()) {
            return "is empty";
        }
        String value = iri.trim();
        if (BAD_IRI_CHAR.matcher(value).find()) {
            return "contains a space or a character not allowed in an IRI";
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) {
                return "is not an absolute IRI (it needs a scheme such as https: or file:)";
            }
        } catch (URISyntaxException ex) {
            return "is not a valid IRI: " + ex.getReason();
        }
        return null;
    }

    /**
     * Whether CimPal's validation would download a web import: only URLs ending in an RDF file
     * extension are fetched; any other http(s) URI is taken for a vocabulary namespace and skipped,
     * so its shapes would be missing without an error.
     */
    public static boolean isFetchableWebImport(String iri) {
        String path = iri.trim().toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        return path.endsWith(".ttl") || path.endsWith(".rdf") || path.endsWith(".xml")
                || path.endsWith(".nt") || path.endsWith(".n3") || path.endsWith(".jsonld")
                || path.endsWith(".trig") || path.endsWith(".nq") || path.endsWith(".owl");
    }

    /** The configuration as Turtle. */
    public static String toTurtle(Options options) {
        String problem = importProblem(options.ontologyIri());
        if (problem != null) {
            throw new IllegalArgumentException("The configuration IRI " + problem + ": " + options.ontologyIri());
        }
        if (!PREFIX.matcher(options.prefix()).matches()) {
            throw new IllegalArgumentException("Not a valid prefix: " + options.prefix());
        }
        for (String iri : options.imports()) {
            String importProblem = importProblem(iri);
            if (importProblem != null) {
                throw new IllegalArgumentException("The import " + importProblem + ": " + iri);
            }
        }

        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixes.put("owl", "http://www.w3.org/2002/07/owl#");
        prefixes.put("xsd", "http://www.w3.org/2001/XMLSchema#");
        prefixes.put("dcterms", "http://purl.org/dc/terms/");
        prefixes.put("sh", SH.getURI());
        String ownNamespace = stripFragment(options.ontologyIri()) + "#";
        prefixes.put(options.prefix(), ownNamespace);
        options.prefixes().forEach((prefix, namespace) -> {
            if (PREFIX.matcher(prefix).matches() && importProblem(namespace) == null) {
                prefixes.putIfAbsent(prefix, namespace);
            }
        });
        PrefixMapping mapping = PrefixMapping.Factory.create().setNsPrefixes(prefixes);

        StringBuilder out = new StringBuilder();
        out.append("@base <").append(stripFragment(options.ontologyIri())).append("> .\n");
        prefixes.forEach((prefix, namespace) -> out.append("@prefix ").append(prefix).append(": <")
                .append(namespace).append("> .\n"));
        out.append('\n');

        out.append(options.prefix()).append(":Ontology\n");
        out.append("        a                    owl:Ontology ;\n");
        out.append("        dcterms:identifier   ").append(string(options.identifier())).append(" ;\n");
        out.append("        dcterms:issued       ").append(string(DateTimeFormatter.ISO_INSTANT.format(options.issued())))
                .append("^^xsd:dateTime ;\n");
        out.append("        dcterms:modified     ")
                .append(string(LocalDate.ofInstant(options.issued(), ZoneOffset.UTC).toString()))
                .append("^^xsd:date ;\n");
        if (!options.title().isBlank()) {
            out.append("        dcterms:title        ").append(string(options.title())).append("@en ;\n");
        }
        if (!options.description().isBlank()) {
            out.append("        dcterms:description  ").append(string(options.description())).append("@en ;\n");
        }
        if (!options.version().isBlank()) {
            out.append("        owl:versionInfo      ").append(string(options.version())).append(" ;\n");
        }
        List<String> imports = options.imports().stream().map(String::trim).distinct().toList();
        for (int i = 0; i < imports.size(); i++) {
            out.append("        owl:imports          <").append(imports.get(i)).append("> ;\n");
        }
        // Replace the last " ;" with " ." to close the description.
        out.setLength(out.length() - 3);
        out.append(" .\n");

        for (Entry entry : options.entries()) {
            out.append('\n');
            for (String line : entry.comment().split("\\R")) {
                if (!line.isBlank()) {
                    out.append("# ").append(line.strip()).append('\n');
                }
            }
            out.append(term(entry.iri(), mapping)).append('\n');
            List<String> types = new ArrayList<>();
            entry.types().forEach(type -> types.add(term(type, mapping)));
            out.append("        a               ").append(String.join(" , ", types)).append(" ;\n");
            out.append("        sh:deactivated  true .\n");
        }
        return out.toString();
    }

    /** Writes the configuration atomically, so a failed write leaves no half file. */
    public static void write(Options options, Path file) throws IOException {
        AtomicFiles.writeString(file, toTurtle(options));
    }

    /** An IRI as a prefixed name when one is safe, else between angle brackets. */
    private static String term(String iri, PrefixMapping mapping) {
        if (BAD_IRI_CHAR.matcher(iri).find()) {
            throw new IllegalArgumentException("Not an IRI Turtle can carry: " + iri);
        }
        String shortForm = mapping.shortForm(iri);
        if (!shortForm.equals(iri)) {
            int colon = shortForm.indexOf(':');
            String local = shortForm.substring(colon + 1);
            if (LOCAL_NAME.matcher(local).matches()) {
                return shortForm;
            }
        }
        return "<" + iri + ">";
    }

    private static String stripFragment(String iri) {
        String trimmed = iri.trim();
        int hash = trimmed.indexOf('#');
        return hash >= 0 ? trimmed.substring(0, hash) : trimmed;
    }

    private static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
