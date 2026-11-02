/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.generators.InstanceDataWriter;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns reference data into "common data": every subject typed with a {@code cim} or {@code nc}
 * class is copied with its properties (except dcat, dcterms and skos ones), and every IRI is reduced
 * to a document-local {@code #_<local name>} id, so the result can be serialised as CIM/XML instance
 * data. GUI: Tools ▸ Reference Data ▸ Convert Reference data to Common data; CLI: {@code ref2common}.
 *
 * <p>The {@code cim} and {@code nc} namespaces are taken from the input's prefixes.
 */
public final class CommonDataConverter {

    /** The internal base of the converted ids; it is not written (relative ids, no xml:base). */
    static final String ID_BASE = "http://delete.eu#";

    private CommonDataConverter() {
    }

    /**
     * Converts the (union of the) reference data.
     *
     * @throws IllegalArgumentException when the input declares neither a {@code cim} nor an {@code nc}
     *                                  prefix, or no subject is typed with a class in them
     */
    public static Model convert(Model referenceData) {
        Map<String, String> prefixes = referenceData.getNsPrefixMap();
        String cim = prefixes.get("cim");
        String nc = prefixes.get("nc");
        if (cim == null && nc == null) {
            throw new IllegalArgumentException("The reference data declares neither a 'cim' nor an 'nc' prefix, "
                    + "so no class can be recognised");
        }
        Set<String> skipped = new java.util.HashSet<>();
        for (String p : new String[]{"dcat", "dcterms", "skos"}) {
            if (prefixes.get(p) != null) skipped.add(prefixes.get(p));
        }

        Model out = ModelFactory.createDefaultModel();
        out.setNsPrefixes(prefixes);
        for (Statement typing : referenceData.listStatements(null, RDF.type, (RDFNode) null).toList()) {
            if (!typing.getObject().isURIResource() || !typing.getSubject().isURIResource()) continue;
            Resource type = typing.getObject().asResource();
            String typeNs = type.getNameSpace();
            if (!typeNs.equals(cim) && !typeNs.equals(nc)) continue;

            Resource subject = out.createResource(localId(typing.getSubject()));
            out.add(subject, RDF.type, type);
            for (Statement st : typing.getSubject().listProperties().toList()) {
                Property p = st.getPredicate();
                if (p.equals(RDF.type) || skipped.contains(p.getNameSpace())) continue;
                RDFNode o = st.getObject();
                if (o.isURIResource()) {
                    out.add(subject, p, out.createResource(localId(o.asResource())));
                } else if (o.isLiteral()) {
                    out.add(subject, p, o);
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No subject is typed with a 'cim' or 'nc' class, so there is nothing to convert");
        }
        return out;
    }

    private static String localId(Resource r) {
        return ID_BASE + "_" + r.getLocalName();
    }

    /** Writes the common data as sorted CIM/XML instance data, header first, with relative ids. */
    public static void write(Model commonData, OutputStream out) throws IOException {
        Map<String, Object> p = new HashMap<>();
        p.put("showXmlDeclaration", "true");
        p.put("showDoctypeDeclaration", "false");
        p.put("tab", "2");
        p.put("relativeURIs", "same-document");
        p.put("showXmlEncoding", "true");
        p.put("xmlBase", ID_BASE);
        p.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        p.put("instanceData", "true");
        p.put("showXmlBaseDeclaration", "false");
        p.put("sortRDF", "true");
        p.put("sortRDFprefix", "false");
        p.put("putHeaderOnTop", true);
        p.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        InstanceDataWriter.write(commonData, p, out);
    }
}
