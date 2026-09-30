/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JSON Schema setup that the CLI contract tests (TEST-4) build on: a schema loaded
 * from test fixtures accepts a conforming document and reports a non-conforming one.
 */
class JsonSchemaSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<Error> validate(String document) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Fixtures.read("cli-json", "summary.schema.json"), InputFormat.JSON);
        return schema.validate(JSON.readTree(document));
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
}
