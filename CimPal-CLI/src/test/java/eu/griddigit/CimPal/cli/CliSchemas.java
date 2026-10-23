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
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * The JSON Schemas of the CLI's {@code --format json} documents (TEST-4), in
 * {@code src/test/resources/fixtures/cli-json/}: the published contract of that output
 * (docs/cli/README.md).
 */
public final class CliSchemas {

    /** The schema file of each JSON-capable command. */
    public static String schemaFile(String command) {
        return command + ".schema.json";
    }

    /** Every schema file in the folder. */
    public static final List<String> ALL = List.of("validate.schema.json", "sparql.schema.json",
            "compare.schema.json", "compare-instances.schema.json", "run.schema.json", "stats.schema.json",
            "summary.schema.json");

    private CliSchemas() {
    }

    /** The errors of {@code document} against {@code schemaFile}; empty when it conforms. */
    public static List<Error> errors(String schemaFile, JsonNode document) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Fixtures.read("cli-json", schemaFile), InputFormat.JSON);
        return schema.validate(document);
    }
}
