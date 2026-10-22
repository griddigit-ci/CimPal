/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-master tests (TEST-3, sparql group) for {@link SparqlTools}: SELECT results as the
 * {@code sparql} command and the GUI get them, and the Excel export. Inputs are built in code;
 * snapshots are in {@code snapshots/sparql/}. Every query has an ORDER BY, so row order is part
 * of the result. SERVICE refusal is covered by {@code SparqlServicePolicyTest}.
 */
class SparqlToolsTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("sparql");
    private static final String CIM16_NS = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

    private static final String TERMINALS_QUERY = """
            PREFIX cim: <%s>
            SELECT ?terminal ?name ?sequence ?equipment
            WHERE {
              ?terminal a cim:Terminal ;
                        cim:IdentifiedObject.name ?name ;
                        cim:Terminal.ConductingEquipment ?equipment .
              OPTIONAL { ?terminal cim:ACDCTerminal.sequenceNumber ?sequence }
            }
            ORDER BY ?name
            """;

    /** Literals of several datatypes, a language tag, a blank node and an unbound variable. */
    private static final String TYPED_TURTLE = """
            @prefix ex:  <urn:test:> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            ex:a ex:value "1.50"^^xsd:decimal ; ex:flag true ; ex:count 3 ;
                 ex:when "2026-01-01T00:00:00Z"^^xsd:dateTime ; ex:label "Alpha"@en ;
                 ex:node [ ex:x 1 ] .
            ex:b ex:value "2.5e0"^^xsd:double ; ex:label "Beta" .
            """;

    private static final String TYPED_QUERY = """
            PREFIX ex: <urn:test:>
            SELECT ?s ?value ?flag ?count ?when ?label ?node
            WHERE {
              ?s ex:value ?value ; ex:label ?label .
              OPTIONAL { ?s ex:flag ?flag }
              OPTIONAL { ?s ex:count ?count }
              OPTIONAL { ?s ex:when ?when }
              OPTIONAL { ?s ex:node ?node }
            }
            ORDER BY ?s
            """;

    @TempDir
    Path tempDir;

    /** CGMES 3.0 EQ data read with the document's own base: subjects stay full IRIs, classes use the cim: prefix. */
    @Test
    void goldenSelectOnCgmes30() throws Exception {
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(TERMINALS_QUERY.formatted(TestModels.CIM_NS), eq().toModel());

        assertThat(results.columns).containsExactly("terminal", "name", "sequence", "equipment");
        assertThat(results.rows).extracting(row -> row.get("name")).containsExactly("T1", "T2", "T3");
        SNAPSHOTS.assertTextEquals("select-cgmes30.txt", text(results));
    }

    /**
     * CGMES 2.4.15 data loaded with {@link SparqlTools#loadModelFromXML}, which reads it against
     * the CIM16 schema URI: rdf:ID objects land in the cim namespace and are shown as {@code cim:_t1}.
     */
    @Test
    void goldenSelectOnCgmes24LoadedFromXml() throws Exception {
        File file = Files.writeString(tempDir.resolve("eq24.xml"), eq24Xml()).toFile();

        Model model = SparqlTools.loadModelFromXML(file);
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(TERMINALS_QUERY.formatted(CIM16_NS), model);

        assertThat(results.rows).extracting(row -> row.get("terminal")).containsExactly("cim:_t1", "cim:_t2", "cim:_t3");
        SNAPSHOTS.assertTextEquals("select-cgmes24.txt", text(results));
    }

    /** How each kind of node becomes a cell: literal values, prefixed IRIs, blank-node labels, "" when unbound. */
    @Test
    void goldenSelectValueRendering() throws Exception {
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(TYPED_QUERY, Snapshots.turtle(TYPED_TURTLE));

        Map<String, String> b = results.rows.get(1);
        assertThat(b).containsEntry("s", "ex:b").containsEntry("flag", "").containsEntry("node", "");
        SNAPSHOTS.assertTextEquals("select-value-rendering.txt", Normalizer.blankNodeLabels().apply(text(results)));
    }

    @Test
    void emptyResultKeepsTheColumns() throws Exception {
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(
                TERMINALS_QUERY.formatted("urn:test:nothing#"), eq().toModel());

        assertThat(results.rows).isEmpty();
        SNAPSHOTS.assertTextEquals("select-empty.txt", text(results));
    }

    @Test
    void goldenExcelExportOfQueryResults() throws Exception {
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(TERMINALS_QUERY.formatted(TestModels.CIM_NS), eq().toModel());
        File out = tempDir.resolve("results.xlsx").toFile();

        SparqlTools.exportResultsToExcel(results, out);

        SNAPSHOTS.assertExcelEquals("select-cgmes30-export", out.toPath());
    }

    /** The one-step export from a query file gives the same cells as executing and then exporting. */
    @Test
    void goldenExcelExportFromQueryFile() throws Exception {
        Path query = Files.writeString(tempDir.resolve("terminals.rq"), TERMINALS_QUERY.formatted(TestModels.CIM_NS));
        File out = tempDir.resolve("direct.xlsx").toFile();

        SparqlTools.executeSparqlQueryToExcelFile(query.toFile(), eq().toModel(), out);

        SNAPSHOTS.assertExcelEquals("select-cgmes30-export", out.toPath());
    }

    @Test
    void goldenExcelExportOfValueRendering() throws Exception {
        File out = tempDir.resolve("typed.xlsx").toFile();

        SparqlTools.executeSparqlQueryToExcelFile(TYPED_QUERY, Snapshots.turtle(TYPED_TURTLE), out);

        SNAPSHOTS.assertExcelEquals("select-value-rendering-export", out.toPath(), Normalizer.blankNodeLabels());
    }

    @Test
    void onlySelectQueriesAreAccepted() {
        Model model = eq().toModel();
        File out = tempDir.resolve("never.xlsx").toFile();

        for (String query : List.of("ASK { ?s ?p ?o }", "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", "DESCRIBE <urn:x>")) {
            assertThatThrownBy(() -> SparqlTools.executeSparqlQuery(query, model))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SELECT");
            assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile(query, model, out))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SELECT");
        }
        assertThat(out).doesNotExist();
    }

    @Test
    void emptyQueryOrModelIsRefused() {
        Model model = eq().toModel();
        File out = tempDir.resolve("never.xlsx").toFile();

        assertThatThrownBy(() -> SparqlTools.executeSparqlQuery("  ", model)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SparqlTools.executeSparqlQuery(null, model)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SparqlTools.executeSparqlQuery("SELECT * { ?s ?p ?o }", ModelFactory.createDefaultModel()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Model");
        assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile("", model, out)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile("SELECT * { ?s ?p ?o }", null, out))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile((File) null, model, out))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Query file");
    }

    @Test
    void malformedQueryIsAParseError() {
        Model model = eq().toModel();

        assertThatThrownBy(() -> SparqlTools.executeSparqlQuery("SELECT ?s WHERE { ?s ", model))
                .isInstanceOf(QueryParseException.class);
        assertThatThrownBy(() -> SparqlTools.executeSparqlQueryToExcelFile("SELEKT ?s {}", model, tempDir.resolve("x.xlsx").toFile()))
                .isInstanceOf(QueryParseException.class);
    }

    @Test
    void loadModelFromXmlTakesOnlyXmlFiles() throws Exception {
        File turtle = Files.writeString(tempDir.resolve("data.ttl"), "<urn:a> <urn:b> <urn:c> .").toFile();
        File upper = Files.writeString(tempDir.resolve("DATA.XML"), eq24Xml()).toFile();

        assertThatThrownBy(() -> SparqlTools.loadModelFromXML(turtle))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(".xml");
        assertThat(SparqlTools.loadModelFromXML(upper).size()).isEqualTo(TestModels.parseRdfXml(eq24Xml()).size());
    }

    /** A CGMES 3.0 EQ file with three terminals (one without a sequence number) on one line. */
    private static TestModels.CgmesModelBuilder eq() {
        return TestModels.eq().withoutXmlBase()
                .resource("Terminal", "_t2").literal("IdentifiedObject.name", "T2").reference("Terminal.ConductingEquipment", "_line1")
                .literal("ACDCTerminal.sequenceNumber", "2")
                .resource("ACLineSegment", "_line1").literal("IdentifiedObject.name", "Line 1")
                .resource("Terminal", "_t1").literal("IdentifiedObject.name", "T1").reference("Terminal.ConductingEquipment", "_line1")
                .literal("ACDCTerminal.sequenceNumber", "1")
                .resource("Terminal", "_t3").literal("IdentifiedObject.name", "T3").reference("Terminal.ConductingEquipment", "_line1");
    }

    /** The same objects as a CGMES 2.4.15 EQ file. */
    private static String eq24Xml() {
        return eq().toRdfXml().replace(TestModels.CIM_NS, CIM16_NS)
                .replace(TestModels.EQ_PROFILE, "http://entsoe.eu/CIM/EquipmentCore/3/1");
    }

    /** Columns, then one tab-separated line per row, in result order. */
    private static String text(SparqlTools.QueryResults results) {
        StringBuilder out = new StringBuilder(String.join("\t", results.columns)).append('\n');
        for (Map<String, String> row : results.rows) {
            out.append(String.join("\t", results.columns.stream().map(row::get).toList())).append('\n');
        }
        return out.toString();
    }
}
