/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.query;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads named SPARQL queries from the query library and runs them against a
 * loaded Jena model, returning the neutral flat table
 * ({@link SparqlTools.QueryResults}). This is the only class besides the model
 * loader that is aware of Jena; the engine never sees it.
 *
 * <p>Query resolution order: if an external folder is configured and contains
 * {@code <name>.rq}, that file wins (queries editable without a rebuild);
 * otherwise the bundled classpath resource under {@code /SPARQL_query_library/}
 * is used.</p>
 */
public final class QueryRunner {

    private static final String RESOURCE_DIR = "/SPARQL_query_library/";
    private static final String EXTENSION = ".rq";
    /** The cim namespace the bundled queries are written against (CGMES 2.4.15 / cim16). */
    private static final String TEMPLATE_CIM_NS = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    /** Known CIM schema namespaces across CGMES versions (used to detect the dialect(s) in a model). */
    private static final List<String> KNOWN_CIM_NS = List.of(
            "http://iec.ch/TC57/2013/CIM-schema-cim16#",   // CGMES 2.4.15
            "http://iec.ch/TC57/CIM100#",                  // CGMES 3.0 (early)
            "https://cim.ucaiug.io/ns#");                  // CGMES 3.0 (ucaiug canonical)

    // Canonical query names (base names, no extension).
    public static final String MAP01_CLASS_INVENTORY = "Map01_ClassInventory";
    public static final String MAP02_TERMINAL_DETAILS = "Map02_TerminalDetails";
    public static final String MAP03_ACLINESEGMENTS = "Map03_ACLineSegments";
    public static final String MAP04_SUBSTATIONS = "Map04_Substations";
    public static final String MAP05_TRANSFORMER_ENDS = "Map05_TransformerEnds";
    public static final String MAP06_SWITCHES = "Map06_Switches";
    public static final String MAP07_BUSBARS = "Map07_Busbars";
    public static final String MAP08_INJECTIONS = "Map08_Injections";
    public static final String MAP09_CONTAINMENT_HEALTH = "Map09_ContainmentHealth";

    private final Path externalFolder;

    /**
     * @param externalQueryFolder absolute path to an external query folder, or
     *                            null to use only the bundled resources.
     */
    public QueryRunner(String externalQueryFolder) {
        this.externalFolder = (externalQueryFolder == null || externalQueryFolder.isBlank())
                ? null
                : Path.of(externalQueryFolder);
    }

    /**
     * Reads the text of a named query, external folder first then classpath.
     */
    public String loadQueryText(String queryName) {
        String fileName = queryName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? queryName
                : queryName + EXTENSION;

        if (externalFolder != null) {
            Path candidate = externalFolder.resolve(fileName);
            if (Files.isReadable(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed reading query " + candidate, e);
                }
            }
        }

        String resourcePath = RESOURCE_DIR + fileName;
        try (InputStream in = QueryRunner.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Query not found on classpath or external folder: " + fileName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading bundled query " + resourcePath, e);
        }
    }

    /**
     * Runs a named SELECT query against the model and returns the flat table.
     * Buffers the whole result set; for large models prefer {@link #runStreaming}.
     */
    public SparqlTools.QueryResults run(String queryName, Model model) {
        String template = loadQueryText(queryName);
        List<String> columns = new ArrayList<>();
        List<java.util.Map<String, String>> rows = new ArrayList<>();
        try {
            for (String ns : activeCimNamespaces(model)) {
                SparqlTools.QueryResults r = SparqlTools.executeSparqlQuery(withNamespace(template, ns), model);
                if (columns.isEmpty()) columns.addAll(r.columns);
                rows.addAll(r.rows);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Query '" + queryName + "' failed: " + e.getMessage(), e);
        }
        return new SparqlTools.QueryResults(columns, rows);
    }

    /**
     * Rewrite the template cim namespace to a concrete one. The core
     * class/property local names are shared across CGMES 2.4.15 and 3.0, so only
     * the namespace differs between dialects.
     */
    private static String withNamespace(String queryText, String ns) {
        return ns.equals(TEMPLATE_CIM_NS) ? queryText : queryText.replace(TEMPLATE_CIM_NS, ns);
    }

    /**
     * The cim schema namespaces actually used by the model. A union of CGMES
     * files can mix dialects (e.g. EQ in CIM100 and boundary in the ucaiug
     * namespace), and the combined model keeps only one {@code cim} prefix, so we
     * probe each known/declared namespace for a typed Terminal and query every
     * one that is present. Returns the template namespace if none is detected.
     */
    private static List<String> activeCimNamespaces(Model model) {
        Set<String> candidates = new LinkedHashSet<>(KNOWN_CIM_NS);
        model.getNsPrefixMap().forEach((prefix, uri) -> {
            String p = prefix.toLowerCase(Locale.ROOT);
            String u = uri.toLowerCase(Locale.ROOT);
            if (p.startsWith("cim") || u.contains("cim-schema") || u.contains("/cim100") || u.contains("cim.ucaiug")) {
                candidates.add(uri);
            }
        });
        List<String> active = new ArrayList<>();
        for (String ns : candidates) {
            if (model.contains(null, RDF.type, ResourceFactory.createResource(ns + "Terminal"))) {
                active.add(ns);
            }
        }
        return active.isEmpty() ? List.of(TEMPLATE_CIM_NS) : active;
    }

    /**
     * Runs a named SELECT query and streams each solution to the handler without
     * materializing the full result set. Preferred for large instance models.
     */
    public void runStreaming(String queryName, Model model, SparqlTools.RowHandler handler) {
        String template = loadQueryText(queryName);
        try {
            for (String ns : activeCimNamespaces(model)) {
                SparqlTools.executeSelectStreaming(withNamespace(template, ns), model, handler);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Query '" + queryName + "' failed: " + e.getMessage(), e);
        }
    }
}
