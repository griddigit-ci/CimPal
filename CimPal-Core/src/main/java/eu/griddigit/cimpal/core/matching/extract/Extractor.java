/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.extract;

import eu.griddigit.cimpal.core.matching.model.*;
import eu.griddigit.cimpal.core.matching.query.QueryRunner;
import eu.griddigit.cimpal.core.utils.SparqlTools.QueryResults;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns SPARQL result rows into typed, side-tagged {@link ModelTables}. Pure:
 * depends only on plain values (a {@code var -> value} function) and the model
 * records - no Jena, no SPARQL. This is the reusability boundary; the same
 * ModelTables could be produced from CSV inputs.
 *
 * <p>Each row builder takes a {@code Function<String,String>} accessor so the
 * same mapping logic serves both the buffered path ({@link #build}, backed by a
 * {@link QueryResults} map) and the streaming path (backed by a live SPARQL
 * solution) used for large models.</p>
 */
public final class Extractor {

    private Extractor() {
    }

    /**
     * Buffered path: builds tables from already-materialised query results.
     * Convenient for tests/CSV; for large models prefer the streaming loader.
     *
     * @param side        which model this is
     * @param sourceLabel provenance label written into every row's source field
     * @param results     output of QueryCatalog.runAll for this model
     */
    public static ModelTables build(Side side, String sourceLabel, Map<String, QueryResults> results) {
        return new ModelTables(
                side,
                mapEach(results.get(QueryRunner.MAP02_TERMINAL_DETAILS), g -> terminalRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP03_ACLINESEGMENTS), g -> acLineSegmentRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP04_SUBSTATIONS), g -> substationRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP05_TRANSFORMER_ENDS), g -> transformerEndRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP06_SWITCHES), g -> switchRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP07_BUSBARS), g -> busbarRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP08_INJECTIONS), g -> injectionRow(g, sourceLabel)),
                mapEach(results.get(QueryRunner.MAP09_CONTAINMENT_HEALTH), g -> connectivityNodeRow(g, sourceLabel))
        );
    }

    private static <T> List<T> mapEach(QueryResults qr, Function<Function<String, String>, T> builder) {
        List<T> out = new ArrayList<>();
        if (qr == null) return out;
        for (Map<String, String> row : qr.rows) {
            out.add(builder.apply(row::get));
        }
        return out;
    }

    // ---- per-row builders (shared by buffered and streaming paths) ----

    public static TerminalRow terminalRow(Function<String, String> g, String src) {
        return new TerminalRow(
                s(g, "terminal"), i(g, "seq"), s(g, "eq"), s(g, "eqType"), s(g, "eqName"),
                s(g, "cn"), s(g, "cnName"), s(g, "container"), s(g, "containerType"),
                s(g, "substation"), s(g, "substationName"), d(g, "nomV"), src);
    }

    public static AcLineSegmentRow acLineSegmentRow(Function<String, String> g, String src) {
        return new AcLineSegmentRow(
                s(g, "acls"), s(g, "name"), d(g, "r"), d(g, "x"), d(g, "bch"), d(g, "gch"),
                d(g, "length"), d(g, "nomV"), s(g, "lineContainer"), s(g, "lineName"), src);
    }

    public static SubstationRow substationRow(Function<String, String> g, String src) {
        return new SubstationRow(
                s(g, "substation"), s(g, "substationName"), s(g, "region"), s(g, "regionName"),
                s(g, "vl"), s(g, "vlName"), d(g, "nomV"), src);
    }

    public static TransformerEndRow transformerEndRow(Function<String, String> g, String src) {
        return new TransformerEndRow(
                s(g, "trafo"), s(g, "trafoName"), s(g, "end"), i(g, "endNum"),
                d(g, "ratedU"), d(g, "ratedS"), s(g, "terminal"), s(g, "substation"), d(g, "nomV"), src);
    }

    public static SwitchRow switchRow(Function<String, String> g, String src) {
        return new SwitchRow(
                s(g, "switch"), s(g, "switchType"), s(g, "switchName"), b(g, "normalOpen"),
                s(g, "container"), s(g, "containerType"), s(g, "substation"), src);
    }

    public static BusbarRow busbarRow(Function<String, String> g, String src) {
        return new BusbarRow(
                s(g, "busbar"), s(g, "busbarName"), s(g, "container"), s(g, "substation"), d(g, "nomV"), src);
    }

    public static InjectionRow injectionRow(Function<String, String> g, String src) {
        return new InjectionRow(
                s(g, "inj"), s(g, "injType"), s(g, "injName"), d(g, "ratedS"), d(g, "ratedU"),
                d(g, "nomU"), s(g, "container"), s(g, "substation"), d(g, "nomV"), src);
    }

    public static ConnectivityNodeRow connectivityNodeRow(Function<String, String> g, String src) {
        return new ConnectivityNodeRow(
                s(g, "cn"), s(g, "cnName"), s(g, "container"), s(g, "containerType"), s(g, "substation"), src);
    }

    // ---- parsing helpers: empty string -> null ----

    private static String s(Function<String, String> g, String key) {
        String v = g.apply(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static Double d(Function<String, String> g, String key) {
        String v = s(g, key);
        if (v == null) return null;
        try {
            return Double.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer i(Function<String, String> g, String key) {
        String v = s(g, key);
        if (v == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean b(Function<String, String> g, String key) {
        String v = s(g, key);
        if (v == null) return null;
        return Boolean.valueOf(v.trim());
    }
}
