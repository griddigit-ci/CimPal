/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.system.StreamRDFWrapper;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads CIM instance files for ID mapping into one graph, with the CIM namespaces normalised.
 * <p>
 * Class and property IRIs of CGMES 2.4.15 ({@code cim16}) and of {@code https://cim.ucaiug.io/ns#}
 * are rewritten to the CGMES 3.0 namespace, and the ENTSO-E extension namespace to its CGMES 3.0
 * counterpart, so the bundled queries serve both versions and a union of files that mixes them
 * still joins. Only predicates and {@code rdf:type} objects are rewritten; data IRIs stay as read.
 * ZIP archives are read entry by entry within {@link ModelFactory}'s archive limits.
 */
final class CimModelLoader {

    static final String CIM = "http://iec.ch/TC57/CIM100#";
    static final String EU = "http://iec.ch/TC57/CIM100-European#";

    private static final Map<String, String> NAMESPACE_ALIASES = Map.of(
            "http://iec.ch/TC57/2013/CIM-schema-cim16#", CIM,
            "https://cim.ucaiug.io/ns#", CIM,
            "http://entsoe.eu/CIM/SchemaExtension/3/1#", EU);

    private CimModelLoader() {
    }

    /** Reads {@code files} (RDF files or ZIP archives of them) into a new graph. */
    static Graph load(List<Path> files, String xmlBase) throws IOException {
        Graph graph = GraphFactory.createDefaultGraph();
        for (Path file : files) {
            read(file, xmlBase, graph);
        }
        return graph;
    }

    /**
     * Reads one RDF file or ZIP archive into {@code target}. A ZIP's entries in other formats
     * (documentation, images) are skipped, but a ZIP without any RDF entry is an error.
     *
     * @throws IOException if the file is missing or does not parse, or is in an RDF syntax that is
     *                     not read here (see {@link #langOf})
     */
    static void read(Path file, String xmlBase, Graph target) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Model file not found: " + file);
        }
        StreamRDF sink = normalising(StreamRDFLib.graph(target));
        String ext = FilenameUtils.getExtension(file.getFileName().toString()).toLowerCase(Locale.ROOT);
        if ("zip".equals(ext)) {
            int[] read = {0};
            ModelFactory.forEachZipEntry(file, (name, in) -> {
                Lang lang = langOf(name);
                if (lang != null) {
                    parse(in, lang, xmlBase, sink, file + "!" + name);
                    read[0]++;
                }
            });
            if (read[0] == 0) {
                throw new IOException("No RDF/XML, Turtle or N-Triples file in " + file);
            }
        } else {
            Lang lang = langOf(file.getFileName().toString());
            try (InputStream in = Files.newInputStream(file)) {
                parse(in, lang == null ? Lang.RDFXML : lang, xmlBase, sink, file.toString());
            }
        }
    }

    private static void parse(InputStream in, Lang lang, String xmlBase, StreamRDF sink, String what)
            throws IOException {
        try {
            RDFParser.source(in).lang(lang).base(xmlBase).parse(sink);
        } catch (RuntimeException ex) {
            throw new IOException("Cannot parse " + what + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * RDF/XML for .xml, .rdf and .owl, Turtle for .ttl, N-Triples for .nt; null for a name that is
     * no RDF at all. Other RDF syntaxes are refused: JSON-LD in particular may fetch a remote
     * {@code @context}, which would be a request outside CimPal's egress gate.
     *
     * @throws IOException for an RDF syntax other than these three
     */
    static Lang langOf(String name) throws IOException {
        String ext = FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT);
        if (ext.equals("xml") || ext.equals("rdf")) {
            return Lang.RDFXML;
        }
        Lang lang = RDFLanguages.filenameToLang(name);
        if (lang == null) {
            return null;
        }
        if (lang.equals(Lang.RDFXML) || lang.equals(Lang.TURTLE) || lang.equals(Lang.NTRIPLES)) {
            return lang;
        }
        throw new IOException("Unsupported model format " + lang.getName() + " for " + name
                + "; use RDF/XML, Turtle or N-Triples");
    }

    /** A sink that rewrites aliased CIM namespaces in predicates and rdf:type objects. */
    static StreamRDF normalising(StreamRDF target) {
        return new StreamRDFWrapper(target) {
            @Override
            public void triple(Triple triple) {
                Node p = triple.getPredicate();
                Node np = canonical(p);
                Node o = triple.getObject();
                Node no = RDF.type.asNode().equals(p) ? canonical(o) : o;
                super.triple(np == p && no == o ? triple : Triple.create(triple.getSubject(), np, no));
            }

            @Override
            public void prefix(String prefix, String iri) {
                super.prefix(prefix, NAMESPACE_ALIASES.getOrDefault(iri, iri));
            }
        };
    }

    private static Node canonical(Node node) {
        if (!node.isURI()) {
            return node;
        }
        String iri = node.getURI();
        int cut = iri.lastIndexOf('#') + 1;
        if (cut <= 0) {
            return node;
        }
        String canonical = NAMESPACE_ALIASES.get(iri.substring(0, cut));
        return canonical == null ? node : NodeFactory.createURI(canonical + iri.substring(cut));
    }
}
