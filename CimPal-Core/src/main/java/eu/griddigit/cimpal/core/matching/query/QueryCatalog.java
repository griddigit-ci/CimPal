/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.query;

import eu.griddigit.cimpal.core.utils.SparqlTools;
import org.apache.jena.rdf.model.Model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the full Map01..Map09 query catalog against a model and returns the raw
 * flat tables keyed by query name. This is the last Jena-aware step; everything
 * downstream (Extractor, engine) works purely on the returned tables.
 */
public final class QueryCatalog {

    public static final List<String> ALL_QUERIES = List.of(
            QueryRunner.MAP01_CLASS_INVENTORY,
            QueryRunner.MAP02_TERMINAL_DETAILS,
            QueryRunner.MAP03_ACLINESEGMENTS,
            QueryRunner.MAP04_SUBSTATIONS,
            QueryRunner.MAP05_TRANSFORMER_ENDS,
            QueryRunner.MAP06_SWITCHES,
            QueryRunner.MAP07_BUSBARS,
            QueryRunner.MAP08_INJECTIONS,
            QueryRunner.MAP09_CONTAINMENT_HEALTH
    );

    private QueryCatalog() {
    }

    /**
     * Executes every catalog query against {@code model}.
     *
     * @return ordered map of queryName -&gt; results (Map02 etc. always present).
     */
    public static Map<String, SparqlTools.QueryResults> runAll(QueryRunner runner, Model model) {
        Map<String, SparqlTools.QueryResults> out = new LinkedHashMap<>();
        for (String q : ALL_QUERIES) {
            out.put(q, runner.run(q, model));
        }
        return out;
    }
}
