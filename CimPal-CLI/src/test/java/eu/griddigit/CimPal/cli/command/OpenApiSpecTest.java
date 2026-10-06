/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code /v1} OpenAPI document (DEP-5): valid OpenAPI 3.1, exactly the routes the server
 * implements, and per command the same config schema that {@code mcp} offers.
 *
 * <p>The command config schemas are copied from {@link CommandSchemas}. After changing a schema
 * there, run this test with {@code -Dopenapi.update=true} to write them into the document, then
 * review the diff.
 */
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("src/main/resources/openapi/cimpal-v1.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    static String configSchemaName(String command) {
        StringBuilder sb = new StringBuilder();
        for (String part : command.split("-")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.append("Config").toString();
    }

    @Test
    void theDocumentIsValidOpenApi31() throws Exception {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser()
                .readContents(Files.readString(SPEC, StandardCharsets.UTF_8), null, options);

        assertThat(result.getMessages()).as("parser messages").isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getOpenAPI().getOpenapi()).isEqualTo("3.1.0");
    }

    @Test
    void theDocumentListsExactlyTheImplementedRoutes() throws Exception {
        JsonNode spec = JSON.readTree(SPEC.toFile());
        Set<String> documented = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!op.getKey().equals("parameters")) {
                    documented.add(op.getKey().toUpperCase() + " " + path.getKey());
                }
            }
        }

        assertThat(documented).containsExactlyInAnyOrderElementsOf(ServeServer.V1_ROUTES);
    }

    @Test
    void theOnlyRoutesWithoutTokenAreHealthAndTheSpec() throws Exception {
        JsonNode spec = JSON.readTree(SPEC.toFile());
        Set<String> open = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                JsonNode security = op.getValue().path("security");
                if (security.isArray() && security.isEmpty()) {
                    open.add(op.getKey().toUpperCase() + " " + path.getKey());
                }
            }
        }

        assertThat(open).containsExactlyInAnyOrder("GET /v1/health", "GET /v1/openapi.json");
        assertThat(spec.path("security").get(0).has("bearerAuth")).isTrue();
    }

    @Test
    void theCommandConfigSchemasAreTheMcpToolSchemas() throws Exception {
        ObjectNode spec = (ObjectNode) JSON.readTree(SPEC.toFile());
        ObjectNode schemas = (ObjectNode) spec.path("components").path("schemas");
        boolean update = Boolean.getBoolean("openapi.update");

        for (CommandSchemas.Entry entry : CommandSchemas.all()) {
            String name = configSchemaName(entry.command());
            ObjectNode expected = entry.inputSchema();
            expected.put("description", entry.description());
            if (update) {
                schemas.set(name, expected);
            } else {
                assertThat(schemas.get(name)).as("components.schemas.%s (run with -Dopenapi.update=true)", name)
                        .isEqualTo(expected);
            }
        }
        assertThat(spec.path("components").path("schemas").path("JobRequest").path("properties")
                .path("command").path("enum")).hasSize(ServeServer.COMMANDS.size());

        if (update) {
            ObjectMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
            Files.writeString(SPEC, pretty.writeValueAsString(spec).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        }
    }
}
