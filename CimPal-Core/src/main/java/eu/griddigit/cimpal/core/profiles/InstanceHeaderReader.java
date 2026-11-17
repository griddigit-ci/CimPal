/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.shared.JenaException;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the header of instance documents: the profiles each declares and the models it depends on.
 *
 * <p>Every {@code md:Model.profile} value is a profile declaration. A {@code dcterms:conformsTo}
 * value is one when the catalog has it as a key or it starts with a profile namespace; a dataset
 * may also conform to things that are not profiles, and those are ignored. A document with
 * {@code md:Model.profile} is read as a CGMES model and its {@code conformsTo} values are not
 * used.
 *
 * <p>A document is parsed as a stream and only its header triples are kept, so a large model
 * costs time but no memory; a document with an implausible number of header statements is
 * refused.
 *
 * <p>The values of an {@link InstanceHeader} are text from the document: untrusted, and to be
 * treated as such before they reach a log, a report or an agent.
 */
public final class InstanceHeaderReader {

    /** A header has a handful of statements; a document with more than this many is refused. */
    static final int MAX_HEADER_STATEMENTS = 10_000;

    /** The namespace of the ENTSO-E application profiles, the default profile namespace. */
    public static final List<String> DEFAULT_PROFILE_NAMESPACES = List.of("https://ap.cim4.eu/");

    private static final String MD_NS = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
    private static final String MODEL_PROFILE = MD_NS + "Model.profile";
    private static final String MODEL_DEPENDENT_ON = MD_NS + "Model.DependentOn";
    // Some data writes the Dublin Core terms namespace with a '#'.
    private static final Set<String> CONFORMS_TO = Set.of("http://purl.org/dc/terms/conformsTo",
            "http://purl.org/dc/terms/#conformsTo");
    private static final Set<String> REQUIRES = Set.of("http://purl.org/dc/terms/requires",
            "http://purl.org/dc/terms/#requires");
    private static final Set<String> HEADER_TYPES = Set.of(MD_NS + "FullModel", MD_NS + "DifferenceModel",
            "http://www.w3.org/ns/dcat#Dataset");

    private final ProfileCatalog catalog;
    private final List<String> profileNamespaces;
    private final String xmlBase;

    /**
     * @param catalog           the profiles that are known; its keys are profile declarations
     * @param profileNamespaces IRI prefixes that mark a {@code dcterms:conformsTo} value as a
     *                          profile even when the catalog does not have it (so it is reported as
     *                          unmapped rather than ignored); may be empty
     * @param xmlBase           base IRI for relative IRIs in the documents, as for validation
     */
    public InstanceHeaderReader(ProfileCatalog catalog, List<String> profileNamespaces, String xmlBase) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.profileNamespaces = List.copyOf(profileNamespaces);
        if (xmlBase == null || xmlBase.isBlank()) {
            throw new IllegalArgumentException("xmlBase must not be blank");
        }
        this.xmlBase = xmlBase;
    }

    /**
     * Reads the header of {@code file}, or of every {@code .xml} document in it when it is a ZIP
     * archive (nested archives included), in the archive's order.
     *
     * @throws IOException if the file cannot be read or parsed, or is an archive without a
     *                     {@code .xml} document
     */
    public List<InstanceHeader> read(Path given) throws IOException {
        // serve/mcp/run: checked before anything touches the file, and read as checked.
        Path file = PathPolicy.checkReadIfActive(given);
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("Data file not found: " + LogSanitizer.forLog(given.getFileName().toString()));
        }
        String name = file.getFileName().toString();
        List<InstanceHeader> headers = new ArrayList<>();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            ModelFactory.forEachZipEntry(file, (entry, in) -> {
                if (entry.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    headers.add(parse(file, entry, in, Lang.RDFXML));
                }
            });
            if (headers.isEmpty()) {
                // As in validation: an archive of the wrong kind must not pass as "nothing to check".
                throw new IOException("No .xml files found in archive: " + LogSanitizer.forLog(name));
            }
            return headers;
        }
        try (InputStream in = Files.newInputStream(file)) {
            headers.add(parse(file, null, in, lang(name)));
        }
        return headers;
    }

    /**
     * The syntax by file extension, from a short list of syntaxes that fetch nothing while being
     * parsed. JSON-LD is not among them: its parser loads a remote {@code @context}.
     */
    private static Lang lang(String fileName) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xml") || lower.endsWith(".rdf")) {
            return Lang.RDFXML;
        }
        if (lower.endsWith(".ttl")) {
            return Lang.TURTLE;
        }
        if (lower.endsWith(".nt")) {
            return Lang.NTRIPLES;
        }
        throw new IOException("Not an instance data file (.xml, .rdf, .ttl, .nt or .zip): " + LogSanitizer.forLog(fileName));
    }

    private InstanceHeader parse(Path file, String entry, InputStream in, Lang lang) throws IOException {
        HeaderSink sink = new HeaderSink();
        try {
            RDFParser.source(in).lang(lang).base(xmlBase).parse(sink);
        } catch (JenaException ex) {
            String source = file.getFileName() + (entry == null ? "" : "/" + entry);
            // The parser's message quotes text of the file.
            throw new IOException("Could not read the data file " + LogSanitizer.forLog(source) + ": "
                    + LogSanitizer.forLog(String.valueOf(ex.getMessage())), ex);
        }
        return sink.header(file, entry);
    }

    private boolean isProfile(String conformsTo) {
        return catalog.isKey(conformsTo) || profileNamespaces.stream().anyMatch(conformsTo::startsWith);
    }

    /** One header statement: its subject (IRI or blank node label) and its value. */
    private record Statement(String subject, String value) {
    }

    /** Keeps the header statements of a document and drops everything else. */
    private final class HeaderSink extends StreamRDFBase {
        private final Set<String> headerNodes = new LinkedHashSet<>();
        private final List<Statement> modelProfiles = new ArrayList<>();
        private final List<Statement> conformsTo = new ArrayList<>();
        private final List<Statement> dependsOn = new ArrayList<>();
        private int kept;

        @Override
        public void quad(Quad quad) {
            triple(quad.asTriple());
        }

        @Override
        public void triple(Triple triple) {
            Node predicate = triple.getPredicate();
            if (!predicate.isURI()) {
                return;
            }
            String p = predicate.getURI();
            Node object = triple.getObject();
            if (p.equals(RDF.type.getURI())) {
                if (object.isURI() && HEADER_TYPES.contains(object.getURI()) && headerNodes.add(name(triple.getSubject()))) {
                    count();
                }
                return;
            }
            List<Statement> target = p.equals(MODEL_PROFILE) ? modelProfiles
                    : p.equals(MODEL_DEPENDENT_ON) || REQUIRES.contains(p) ? dependsOn
                    : CONFORMS_TO.contains(p) ? conformsTo
                    : null;
            if (target == null) {
                return;
            }
            String value = object.isURI() ? object.getURI()
                    : object.isLiteral() ? object.getLiteralLexicalForm().strip()
                    : null;
            if (value != null && !value.isEmpty()) {
                target.add(new Statement(name(triple.getSubject()), value));
                count();
            }
        }

        /** Bounds what a hostile document can make the reader keep. */
        private void count() {
            if (++kept > MAX_HEADER_STATEMENTS) {
                throw new JenaException("more than " + MAX_HEADER_STATEMENTS + " header statements");
            }
        }

        private static String name(Node subject) {
            return subject.isURI() ? subject.getURI() : "_:" + subject.getBlankNodeLabel();
        }

        InstanceHeader header(Path file, String entry) {
            // With a typed header node, only its statements count: conformsTo and requires are
            // general terms that other resources of a document may carry too.
            Set<String> profiles = new TreeSet<>(values(modelProfiles));
            InstanceHeader.Kind kind = InstanceHeader.Kind.CGMES;
            if (profiles.isEmpty()) {
                values(conformsTo).stream().filter(InstanceHeaderReader.this::isProfile).forEach(profiles::add);
                kind = profiles.isEmpty() ? InstanceHeader.Kind.NONE : InstanceHeader.Kind.NC;
            }
            String modelIri = headerNodes.stream().filter(n -> !n.startsWith("_:")).findFirst()
                    .or(() -> modelProfiles.stream().map(Statement::subject).filter(n -> !n.startsWith("_:")).findFirst())
                    .orElse(null);
            return new InstanceHeader(file, entry, kind, new ArrayList<>(profiles), modelIri,
                    new ArrayList<>(new TreeSet<>(values(dependsOn))));
        }

        private List<String> values(List<Statement> statements) {
            return statements.stream()
                    .filter(s -> headerNodes.isEmpty() || headerNodes.contains(s.subject()))
                    .map(Statement::value)
                    .toList();
        }
    }
}
