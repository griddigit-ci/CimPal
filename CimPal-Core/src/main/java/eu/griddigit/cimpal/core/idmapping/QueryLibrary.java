/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.utils.SparqlServicePolicy;
import org.apache.jena.graph.Graph;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The SPARQL queries the ID mapping reads models with: the bundled set under
 * {@code /idmapping/queries/}, or edited copies in a folder. A query missing from the folder is
 * taken from the bundled set. Queries are SELECT only and may not use {@code SERVICE}.
 */
final class QueryLibrary {

    static final String OBJECTS = "IdMap01_Objects.rq";
    static final String TERMINALS = "IdMap02_Terminals.rq";
    static final String CONTAINMENT = "IdMap03_Containment.rq";
    static final String VOLTAGE_LEVELS = "IdMap04_VoltageLevels.rq";
    static final String PARAMETERS = "IdMap05_EquipmentParameters.rq";
    static final String TRANSFORMER_ENDS = "IdMap06_TransformerEnds.rq";
    static final String DEPENDENTS = "IdMap07_Dependents.rq";
    static final String REFERENCES = "IdMap08_References.rq";

    static final List<String> ALL = List.of(OBJECTS, TERMINALS, CONTAINMENT, VOLTAGE_LEVELS, PARAMETERS,
            TRANSFORMER_ENDS, DEPENDENTS, REFERENCES);

    private static final String BUNDLED = "/idmapping/queries/";

    private final Path folder;

    QueryLibrary(Path folder) {
        this.folder = folder;
    }

    /** The text of query {@code name}. */
    String text(String name) {
        try {
            if (folder != null) {
                Path file = folder.resolve(name);
                if (Files.isRegularFile(file)) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
            }
            try (InputStream in = QueryLibrary.class.getResourceAsStream(BUNDLED + name)) {
                if (in == null) {
                    throw new IllegalStateException("Bundled query missing: " + name);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read query " + name, ex);
        }
    }

    /**
     * Runs query {@code name} on {@code graph} and hands each row to {@code rows} as it comes,
     * without buffering the result.
     *
     * @throws IllegalArgumentException if the query does not parse, is not a SELECT or uses SERVICE
     */
    void select(String name, Graph graph, Consumer<QuerySolution> rows) {
        Query query;
        try {
            query = QueryFactory.create(text(name));
        } catch (QueryParseException ex) {
            throw new IllegalArgumentException("Query " + name + " does not parse: " + ex.getMessage(), ex);
        }
        if (!query.isSelectType()) {
            throw new IllegalArgumentException("Query " + name + " must be a SELECT query");
        }
        SparqlServicePolicy.requireNoService(query);
        try (QueryExecution execution = QueryExecutionFactory.create(query, ModelFactory.createModelForGraph(graph))) {
            SparqlServicePolicy.disableIn(execution.getContext());
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                rows.accept(results.next());
            }
        }
    }
}
