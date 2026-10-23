/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import com.networknt.schema.Error;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JSON Schema setup that the CLI contract tests (TEST-4) build on: each schema loads,
 * accepts a conforming document and reports a non-conforming one.
 */
class JsonSchemaSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> SCHEMAS = CliSchemas.ALL;

    private static List<Error> validate(String document) {
        return CliSchemas.errors("summary.schema.json", JSON.readTree(document));
    }

    @Test
    void conformingDocumentHasNoErrors() {
        assertThat(validate("""
                {"command": "validate", "exitCode": 1, "violations": 3}
                """)).isEmpty();
    }

    @Test
    void wrongTypeAndMissingFieldAreReported() {
        assertThat(validate("""
                {"command": "validate", "exitCode": "one"}
                """)).extracting(Error::toString)
                .anySatisfy(message -> assertThat(message).contains("exitCode"))
                .anySatisfy(message -> assertThat(message).contains("violations"));
    }

    @ParameterizedTest
    @FieldSource("SCHEMAS")
    void everySchemaLoadsAndRefusesAnEmptyObjectAndAnArray(String schemaFile) {
        assertThat(CliSchemas.errors(schemaFile, JSON.readTree("{}"))).as(schemaFile).isNotEmpty();
        assertThat(CliSchemas.errors(schemaFile, JSON.readTree("[]"))).as(schemaFile).isNotEmpty();
    }

    @Test
    void theSparqlSchemaAcceptsRowsAndRefusesANonTextValue() {
        assertThat(CliSchemas.errors("sparql.schema.json", JSON.readTree("""
                {"columns": ["s"], "rows": [{"s": "urn:x"}]}
                """))).isEmpty();
        assertThat(CliSchemas.errors("sparql.schema.json", JSON.readTree("""
                {"columns": ["s"], "rows": [{"s": 1}]}
                """))).isNotEmpty();
    }
}
