/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.List;

/**
 * The RDFS serialisation information that instance data writers need: per profile, an
 * {@code <profile>#RdfAbout} class whose {@code owl:members} are the classes serialised with
 * rdf:about (stereotype "Description"), and an {@code <profile>#RdfEnum} class whose members are the
 * enumeration values. This is the format of the {@code *_RDFSSerialisation.ttl} resources.
 * GUI: Tools ▸ RDFS Profiles ▸ Generate information from RDFS; CLI: {@code rdfs-serialisation}.
 *
 * <p>The profile IRI and keyword come from the owl:Ontology header (owl:versionIRI, dcat:keyword;
 * RDFS 2020) or from the entsoeURI* / shortName cims:isFixed values (CGMES 2.4 RDFS 2019).
 */
public final class RdfsSerialisation {

    private static final String CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
    private static final Property STEREOTYPE = ResourceFactory.createProperty(CIMS, "stereotype");
    private static final Property IS_FIXED = ResourceFactory.createProperty(CIMS, "isFixed");
    private static final Resource ENUMERATION = ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#enumeration");
    private static final List<String> CGMES24_URI_LABELS = List.of("entsoeURI", "entsoeURIcore", "entsoeURIoperation",
            "entsoeURIshortCircuit");

    /** A profile's IRI (without '#') and its short name (null when it has none). */
    record Profile(String uri, String shortName) {
    }

    private RdfsSerialisation() {
    }

    /**
     * Builds the serialisation model for the given profiles.
     *
     * @param profiles each a profile's name (for messages) and its RDFS
     * @throws IllegalArgumentException when a profile has no header to take its IRI from
     */
    public static Model build(List<java.util.Map.Entry<String, Model>> profiles) {
        Model out = ModelFactory.createDefaultModel();
        out.setNsPrefix("owl", OWL2.NS);
        out.setNsPrefix("rdfs", RDFS.uri);
        for (java.util.Map.Entry<String, Model> entry : profiles) {
            Model m = entry.getValue();
            Profile profile = profile(m);
            if (profile == null) {
                throw new IllegalArgumentException(entry.getKey() + ": no profile header (owl:Ontology with owl:versionIRI, "
                        + "or a CGMES 2.4 entsoeURI label), so the profile IRI is unknown");
            }
            // both come from the RDFS as text and end up in the Turtle, so they are checked first
            if (!isSafeAbsoluteIri(profile.uri())) {
                throw new IllegalArgumentException(entry.getKey() + ": the profile IRI is not a valid absolute IRI: "
                        + UntrustedText.forAgent(profile.uri(), LogSanitizer.MAX_LENGTH));
            }
            if (profile.shortName() != null && !PREFIX_NAME.matcher(profile.shortName()).matches()) {
                throw new IllegalArgumentException(entry.getKey() + ": the profile keyword can't be a namespace prefix: "
                        + UntrustedText.forAgent(profile.shortName(), LogSanitizer.MAX_LENGTH));
            }
            String profileNs = profile.uri() + "#";
            if (profile.shortName() != null) out.setNsPrefix(profile.shortName().toLowerCase(java.util.Locale.ROOT), profileNs);
            copyPrefix(m, out, "cim");
            for (String p : List.of("entsoe", "eu", "nc")) {
                if (copyPrefix(m, out, p)) break;
            }

            Resource rdfAbout = out.createResource(profileNs + "RdfAbout");
            List<Resource> described = m.listSubjectsWithProperty(STEREOTYPE, "Description").toList();
            if (!described.isEmpty()) {
                out.add(rdfAbout, RDF.type, RDFS.Class);
                described.forEach(item -> out.add(rdfAbout, OWL2.members, item));
            }

            Resource rdfEnum = out.createResource(profileNs + "RdfEnum");
            for (Resource enumClass : m.listSubjectsWithProperty(STEREOTYPE, ENUMERATION).toList()) {
                out.add(rdfEnum, RDF.type, RDFS.Class);
                m.listSubjectsWithProperty(RDF.type, enumClass).forEachRemaining(value -> out.add(rdfEnum, OWL2.members, value));
            }
        }
        return out;
    }

    /** The profile of one RDFS model, or null when it has no header. */
    static Profile profile(Model m) {
        Resource ontology = m.listSubjectsWithProperty(RDF.type, OWL2.Ontology).nextOptional().orElse(null);
        if (ontology != null) {
            Statement version = ontology.getProperty(OWL2.versionIRI);
            if (version == null) return null;
            Statement keyword = ontology.getProperty(DCAT.keyword);
            return new Profile(text(version.getObject()), keyword == null ? null : text(keyword.getObject()));
        }
        for (String label : CGMES24_URI_LABELS) {
            Resource uriItem = m.listSubjectsWithProperty(RDFS.label, m.createLiteral(label, "en")).nextOptional().orElse(null);
            if (uriItem == null) continue;
            Statement uri = uriItem.getProperty(IS_FIXED);
            Statement domain = uriItem.getProperty(RDFS.domain);
            if (uri == null) return null;
            String shortName = null;
            for (Resource nameItem : m.listSubjectsWithProperty(RDFS.label, m.createLiteral("shortName", "en")).toList()) {
                Statement nameDomain = nameItem.getProperty(RDFS.domain);
                Statement fixed = nameItem.getProperty(IS_FIXED);
                if (domain != null && nameDomain != null && fixed != null && nameDomain.getObject().equals(domain.getObject())) {
                    shortName = text(fixed.getObject());
                }
            }
            return new Profile(text(uri.getObject()), shortName);
        }
        return null;
    }

    /** A profile keyword usable as a Turtle prefix (ASCII NCName). */
    private static final java.util.regex.Pattern PREFIX_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    /** An absolute IRI without the characters Turtle can't write inside {@code <...>}. */
    static boolean isSafeAbsoluteIri(String iri) {
        for (char c : iri.toCharArray()) {
            if (c <= 0x20 || "<>\"{}|^`\\".indexOf(c) >= 0) return false;
        }
        try {
            return new java.net.URI(iri).isAbsolute();
        } catch (java.net.URISyntaxException ex) {
            return false;
        }
    }

    private static boolean copyPrefix(Model from, Model to, String prefix) {
        String uri = from.getNsPrefixURI(prefix);
        if (uri == null) return false;
        to.setNsPrefix(prefix, uri);
        return true;
    }

    private static String text(RDFNode node) {
        return node.isLiteral() ? node.asLiteral().getLexicalForm() : node.toString();
    }
}
