/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.query;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import org.apache.jena.rdf.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
        String queryText = resolveNamespace(loadQueryText(queryName), model);
        try {
            return SparqlTools.executeSparqlQuery(queryText, model);
        } catch (Exception e) {
            throw new IllegalStateException("Query '" + queryName + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Makes the bundled cim16 queries dialect-agnostic: if the model declares a
     * different {@code cim} namespace (e.g. CGMES 3.0 / CIM100), rewrite the
     * template namespace to the model's. The core class/property local names are
     * shared across CGMES 2.4.15 and 3.0, so only the namespace differs.
     */
    private static String resolveNamespace(String queryText, Model model) {
        String ns = model.getNsPrefixURI("cim");
        if (ns != null && !ns.equals(TEMPLATE_CIM_NS)) {
            return queryText.replace(TEMPLATE_CIM_NS, ns);
        }
        return queryText;
    }

    /**
     * Runs a named SELECT query and streams each solution to the handler without
     * materializing the full result set. Preferred for large instance models.
     */
    public void runStreaming(String queryName, Model model, SparqlTools.RowHandler handler) {
        String queryText = resolveNamespace(loadQueryText(queryName), model);
        try {
            SparqlTools.executeSelectStreaming(queryText, model, handler);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Query '" + queryName + "' failed: " + e.getMessage(), e);
        }
    }
}
