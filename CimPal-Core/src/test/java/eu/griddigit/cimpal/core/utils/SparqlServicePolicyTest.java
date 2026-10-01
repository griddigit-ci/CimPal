/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.testsupport.StubHttpServer;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.exec.http.Service;
import org.apache.jena.sparql.util.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPARQL SERVICE must never reach the network (SEC-2, gap G3). Finding recorded in
 * docs/plans/SEC-2.md: Jena 6.2 ARQ executes SERVICE by default, also to loopback, bypassing the
 * egress allowlist.
 */
class SparqlServicePolicyTest {

    private static final String EMPTY_RESULTS = "{\"head\":{\"vars\":[\"s\"]},\"results\":{\"bindings\":[]}}";

    private StubHttpServer stub;
    private Context saved;

    @BeforeEach
    void startStub() {
        saved = ARQ.getContext().copy();
        stub = StubHttpServer.start().serve("/sparql", EMPTY_RESULTS, "application/sparql-results+json");
    }

    @AfterEach
    void stopStub() {
        stub.close();
        // restore the global ARQ context for other tests in this JVM
        ARQ.getContext().clear();
        ARQ.getContext().putAll(saved);
    }

    private static Model model() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource("urn:a"), model.createProperty("urn:p"), "x");
        return model;
    }

    private String serviceQuery() {
        return "SELECT * WHERE { SERVICE <" + stub.uri("/sparql") + "> { ?s ?p ?o } }";
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } }",
            "SELECT * WHERE { ?a ?b ?c OPTIONAL { SERVICE SILENT <http://example.org/sparql> { ?s ?p ?o } } }",
            "SELECT * WHERE { { SELECT ?s WHERE { SERVICE ?endpoint { ?s ?p ?o } } } }",
            "ASK { FILTER EXISTS { SERVICE <http://example.org/sparql> { ?s ?p ?o } } }",
            "SELECT * WHERE { ?x ?y ?z MINUS { SERVICE <http://example.org/s> { ?x ?y ?z } } }",
            "SELECT * WHERE { BIND(EXISTS { SERVICE <http://example.org/s> { ?s ?p ?o } } AS ?b) }",
            "SELECT * WHERE { ?a ?b ?c FILTER NOT EXISTS { { SELECT ?a { SERVICE <http://e.org/s> { ?a ?b ?c } } } } }"
    })
    void queriesContainingServiceAreRefused(String text) {
        Query query = QueryFactory.create(text);

        assertThatThrownBy(() -> SparqlServicePolicy.requireNoService(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SERVICE");
    }

    @Test
    void queriesWithoutServiceAreAccepted() {
        SparqlServicePolicy.requireNoService(QueryFactory.create(
                "SELECT ?s WHERE { ?s ?p ?o FILTER(?o != \"SERVICE\") } LIMIT 1"));
    }

    @Test
    void sparqlToolsRefusesServiceAndTheStubSeesNothing() {
        assertThatThrownBy(() -> SparqlTools.executeSparqlQuery(serviceQuery(), model()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SERVICE");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void sparqlToolsExcelExportRefusesServiceAndTheStubSeesNothing(@org.junit.jupiter.api.io.TempDir Path tempDir) {
        File out = tempDir.resolve("out.xlsx").toFile();

        assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile(serviceQuery(), model(), out))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requests()).isEmpty();
        assertThat(out).doesNotExist();
    }

    @Test
    void globalSwitchStopsServiceInAnyQueryExecution() {
        SparqlServicePolicy.disableRemoteServiceGlobally();

        try (QueryExecution qe = QueryExecutionFactory.create(serviceQuery(), model())) {
            assertThatThrownBy(() -> ResultSetFormatter.consume(qe.execSelect()))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(stub.requests()).isEmpty();
        assertThat(ARQ.getContext().isFalse(Service.httpServiceAllowed)).isTrue();
    }
}
