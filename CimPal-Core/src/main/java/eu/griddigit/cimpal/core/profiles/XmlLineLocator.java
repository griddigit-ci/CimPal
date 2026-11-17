/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.PathPolicy;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Finds the lines of RDF/XML objects in their file, so a finding can point at the object, and at
 * the violated property inside it, rather than at the whole file.
 *
 * <p>One streaming pass per file, whatever the number of objects wanted. An object is an element
 * with {@code rdf:ID} or {@code rdf:about}, resolved against the document's {@code xml:base} or the
 * given base IRI as the RDF parser resolves it; the first definition in the file wins. A property
 * is a child element of that object. External entities and DTDs are not processed.
 *
 * <p>A line is the line on which the parser reports the element's start tag to end; for the usual
 * one-tag-per-line CIM/XML that is the element's line.
 */
public final class XmlLineLocator {

    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** Where an object is defined, and the first line of each of its properties. */
    public record Located(int line, Map<String, Integer> propertyLines) {
        public Located {
            propertyLines = Map.copyOf(propertyLines);
        }

        /** The line of {@code property} inside the object, or the object's own line. */
        public int lineOf(String property) {
            return property == null ? line : propertyLines.getOrDefault(property, line);
        }
    }

    private XmlLineLocator() {
    }

    /**
     * Locates {@code objects} (full IRIs) in {@code file}.
     *
     * @param xmlBase base IRI for relative {@code rdf:ID} / {@code rdf:about} values, used when
     *                the document has no {@code xml:base}
     * @return the objects found, by IRI; an object that is not defined in the file is absent
     * @throws IOException if the file cannot be read or is not well-formed XML
     */
    public static Map<String, Located> locate(Path given, Collection<String> objects, String xmlBase) throws IOException {
        Path file = PathPolicy.checkReadIfActive(given);
        Set<String> wanted = Set.copyOf(objects);
        Map<String, Located> found = new HashMap<>();
        if (wanted.isEmpty()) {
            return found;
        }
        // The JDK's own parser, whatever else is on the path: the properties below mean what they say for it.
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                String base = xmlBase;
                // For each open element: the object it defines (wanted ones only), or null.
                Deque<String> open = new ArrayDeque<>();
                Map<String, Integer> lines = new HashMap<>();
                Map<String, Map<String, Integer>> properties = new HashMap<>();
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (open.isEmpty()) {
                            String declared = reader.getAttributeValue(XMLConstants.XML_NS_URI, "base");
                            if (declared != null && !declared.isBlank()) {
                                base = declared;
                            }
                        }
                        int line = reader.getLocation().getLineNumber();
                        String parent = open.peek();
                        if (parent != null && !parent.isEmpty()) {
                            String property = reader.getNamespaceURI() == null ? reader.getLocalName()
                                    : reader.getNamespaceURI() + reader.getLocalName();
                            properties.computeIfAbsent(parent, p -> new HashMap<>()).putIfAbsent(property, line);
                        }
                        String iri = definedObject(reader, base);
                        if (iri != null && wanted.contains(iri) && !lines.containsKey(iri)) {
                            lines.put(iri, line);
                            open.push(iri);
                        } else {
                            open.push("");
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        open.pop();
                    }
                }
                lines.forEach((iri, line) -> found.put(iri, new Located(line, properties.getOrDefault(iri, Map.of()))));
            } finally {
                reader.close();
            }
        } catch (XMLStreamException ex) {
            // The position only: the message may quote the file.
            throw new IOException("Could not read the lines of " + file.getFileName()
                    + (ex.getLocation() == null ? "" : " (line " + ex.getLocation().getLineNumber() + ")"), ex);
        }
        return found;
    }

    /** The IRI the element defines through {@code rdf:ID} or {@code rdf:about}, or null. */
    private static String definedObject(XMLStreamReader reader, String base) {
        String id = reader.getAttributeValue(RDF_NS, "ID");
        if (id != null) {
            return stripFragment(base) + "#" + id;
        }
        String about = reader.getAttributeValue(RDF_NS, "about");
        if (about == null) {
            return null;
        }
        if (about.startsWith("#")) {
            return stripFragment(base) + about;
        }
        try {
            URI uri = new URI(about);
            return uri.isAbsolute() || base == null ? about : new URI(base).resolve(uri).toString();
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return about;
        }
    }

    private static String stripFragment(String base) {
        if (base == null) {
            return "";
        }
        int hash = base.indexOf('#');
        return hash < 0 ? base : base.substring(0, hash);
    }
}
