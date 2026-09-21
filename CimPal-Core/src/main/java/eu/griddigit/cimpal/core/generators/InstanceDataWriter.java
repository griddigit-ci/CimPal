/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.generators;

import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Serialises a Jena {@link Model} to an RDF/XML stream using the CimPal custom format.
 *
 * <p>This class contains the pure serialisation logic extracted from
 * {@code InstanceDataFactory.saveInstanceData}. It has no dependency on JavaFX, file dialogs,
 * or ZIP handling — those remain in the Main module.
 *
 * <p>The caller is responsible for opening and closing the {@link OutputStream}.
 */
public class InstanceDataWriter {

    /**
     * Write {@code model} to {@code out} using the serialisation settings in {@code saveProperties}.
     *
     * <p>The stream is NOT closed by this method; the caller must close it (preferably via
     * try-with-resources).
     *
     * <p>Required / recognised keys in {@code saveProperties}:
     * <ul>
     *   <li>{@code rdfFormat}          — {@link RDFFormat}; defaults to
     *       {@code CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY}
     *   <li>{@code xmlBase}            — String; base URI
     *   <li>{@code showXmlDeclaration} — String "true"/"false"
     *   <li>{@code showDoctypeDeclaration} — String "true"/"false"
     *   <li>{@code showXmlEncoding}    — String "true"/"false"
     *   <li>{@code showXmlBaseDeclaration} — String "true"/"false"
     *   <li>{@code tab}                — String tab width, e.g. "2"
     *   <li>{@code relativeURIs}       — String e.g. "same-document"
     *   <li>{@code instanceData}       — String "true"/"false"
     *   <li>{@code sortRDF}            — boolean or String "true"/"false"
     *   <li>{@code sortRDFprefix}      — boolean or String "true"/"false"
     *   <li>{@code useAboutRules}      — boolean; if true, {@code rdfAboutList} is applied
     *   <li>{@code rdfAboutList}       — {@code Set<Resource>}
     *   <li>{@code useEnumRules}       — boolean; if true, {@code rdfEnumList} is applied
     *   <li>{@code rdfEnumList}        — {@code Set<Resource>}
     *   <li>{@code putHeaderOnTop}     — boolean; if true, header class appears first
     *   <li>{@code headerClassResource} — String URI
     *   <li>{@code prettyTypes}        — {@code Resource[]} or {@code Collection<Resource>}
     * </ul>
     *
     * @param model          the Jena model to serialise
     * @param saveProperties serialisation properties (see above)
     * @param out            the target stream; must be open; not closed by this method
     * @throws IOException if writing fails
     */
    public static void write(Model model, Map<String, Object> saveProperties, OutputStream out)
            throws IOException {
        CustomRDFFormat.RegisterCustomFormatWriters();

        RDFFormat rdfFormat = (RDFFormat) saveProperties.getOrDefault(
                "rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);

        String xmlBase              = getStr(saveProperties, "xmlBase",              "");
        String showXmlDeclaration   = getStr(saveProperties, "showXmlDeclaration",   "true");
        String showDoctypeDeclaration = getStr(saveProperties, "showDoctypeDeclaration", "false");
        String showXmlEncoding      = getStr(saveProperties, "showXmlEncoding",      "true");
        String showXmlBaseDeclaration = getStr(saveProperties, "showXmlBaseDeclaration", "false");
        String tab                  = getStr(saveProperties, "tab",                  "2");
        String relativeURIs         = getStr(saveProperties, "relativeURIs",         "same-document");
        String instanceData         = getStr(saveProperties, "instanceData",         "false");
        String sortRDF              = getStr(saveProperties, "sortRDF",              "false");
        String sortRDFprefix        = getStr(saveProperties, "sortRDFprefix",        "false");

        boolean useAboutRules  = getBool(saveProperties, "useAboutRules",  false);
        boolean useEnumRules   = getBool(saveProperties, "useEnumRules",   false);
        boolean putHeaderOnTop = getBool(saveProperties, "putHeaderOnTop", false);

        String headerClassResource = getStr(saveProperties, "headerClassResource", "");

        @SuppressWarnings("unchecked")
        Set<Resource> rdfAboutList = (Set<Resource>) saveProperties.get("rdfAboutList");
        @SuppressWarnings("unchecked")
        Set<Resource> rdfEnumList  = (Set<Resource>) saveProperties.get("rdfEnumList");

        // Resolve prettyTypes
        Resource[] prettyTypes = null;
        Object prettyTypesObj = saveProperties.get("prettyTypes");
        if (prettyTypesObj instanceof Resource[] arr && arr.length > 0) {
            prettyTypes = arr;
        } else if (prettyTypesObj instanceof Collection<?> col && !col.isEmpty()) {
            prettyTypes = col.stream()
                    .filter(Resource.class::isInstance)
                    .map(Resource.class::cast)
                    .toArray(Resource[]::new);
        }

        if ((prettyTypes == null || prettyTypes.length == 0)
                && headerClassResource != null && !headerClassResource.isBlank()) {
            prettyTypes = new Resource[]{ResourceFactory.createResource(headerClassResource)};
        }

        if (putHeaderOnTop && (prettyTypes == null || prettyTypes.length == 0)) {
            prettyTypes = buildPrettyTypesFallback(model, headerClassResource);
        }

        // Write
        if (rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY
                || rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN) {

            Map<String, Object> properties = new HashMap<>();
            properties.put("showXmlDeclaration",    showXmlDeclaration);
            properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
            properties.put("showXmlEncoding",        showXmlEncoding);
            properties.put("showXmlBaseDeclaration", showXmlBaseDeclaration);
            properties.put("xmlbase",   xmlBase);   // note lowercase 'b' — required by CustomRDFFormat
            properties.put("tab",       tab);
            properties.put("relativeURIs", relativeURIs);
            properties.put("instanceData",  instanceData);
            properties.put("sortRDF",       sortRDF);
            properties.put("sortRDFprefix", sortRDFprefix);

            if (putHeaderOnTop && prettyTypes != null && prettyTypes.length > 0) {
                properties.put("prettyTypes", prettyTypes);
            }

            if (useAboutRules && rdfAboutList != null) {
                properties.put("aboutRules", rdfAboutList);
            }
            if (useEnumRules && rdfEnumList != null) {
                properties.put("enumRules", rdfEnumList);
            }

            Context cxt = new Context();
            cxt.set(SysRIOT.sysRdfWriterProperties, properties);

            org.apache.jena.riot.RDFWriter.create()
                    .base(xmlBase)
                    .format(rdfFormat)
                    .context(cxt)
                    .source(model)
                    .output(out);

        } else {
            model.write(out, rdfFormat.getLang().getLabel().toUpperCase(), xmlBase);
        }
    }

    /**
     * Convenience overload: open a file at {@code outputPath}, write, then close.
     *
     * @param model          the Jena model to serialise
     * @param saveProperties serialisation properties (see {@link #write})
     * @param outputPath     destination file path; created if absent, overwritten if present
     * @throws IOException if I/O fails
     */
    public static void writeToPath(Model model, Map<String, Object> saveProperties, Path outputPath)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            write(model, saveProperties, out);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getStr(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static Resource[] buildPrettyTypesFallback(Model model, String headerClassResource) {
        LinkedHashSet<Resource> orderedTypes = new LinkedHashSet<>();
        if (headerClassResource != null && !headerClassResource.isBlank()) {
            orderedTypes.add(ResourceFactory.createResource(headerClassResource));
        }
        if (model != null) {
            StmtIterator typeStatements = model.listStatements(null, RDF.type, (RDFNode) null);
            while (typeStatements.hasNext()) {
                Statement stmt = typeStatements.nextStatement();
                RDFNode obj = stmt.getObject();
                if (obj.isResource()) {
                    orderedTypes.add(obj.asResource());
                }
            }
        }
        return orderedTypes.toArray(Resource[]::new);
    }
}
