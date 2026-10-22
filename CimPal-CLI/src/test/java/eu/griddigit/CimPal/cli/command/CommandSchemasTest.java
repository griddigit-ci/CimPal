/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommandSchemas} is what mcp's tools/list and the job API's OpenAPI document offer, so it
 * must list every config key a command reads (DOC-2: some were accepted but not advertised). The
 * keys a command reads are the string literals in its {@code loadConfig} method.
 */
class CommandSchemasTest {

    private static final Path SOURCES = Path.of("src/main/java/eu/griddigit/CimPal/cli/command");

    /** CLI command → its source file. */
    private static final Map<String, String> SOURCE_FILES = Map.ofEntries(
            Map.entry("validate", "ValidateCommand.java"),
            Map.entry("sparql", "SparqlCommand.java"),
            Map.entry("compare", "CompareCommand.java"),
            Map.entry("compare-instances", "CompareInstancesCommand.java"),
            Map.entry("convert", "ConvertCommand.java"),
            Map.entry("rdfs2shacl", "RdfsToShaclCommand.java"),
            Map.entry("organize", "OrganizeCommand.java"),
            Map.entry("excel2shacl", "ExcelToShaclCommand.java"),
            Map.entry("gen-instances", "GenInstancesCommand.java"),
            Map.entry("manifest", "ManifestCommand.java"));

    private static final Pattern LOAD_CONFIG = Pattern.compile("\\w+ loadConfig\\(.*?\\r?\\n    }\\r?\\n", Pattern.DOTALL);
    private static final Pattern KEY = Pattern.compile("\"([a-z][A-Za-z0-9]+)\"");

    @Test
    void everyCommandOfTheSchemasHasItsSourceHere() {
        Set<String> commands = new TreeSet<>();
        CommandSchemas.all().forEach(e -> commands.add(e.command()));
        assertThat(commands).isEqualTo(new TreeSet<>(SOURCE_FILES.keySet()));
    }

    @Test
    void theSchemaListsEveryConfigKeyTheCommandReads() throws Exception {
        for (CommandSchemas.Entry entry : CommandSchemas.all()) {
            String source = Files.readString(SOURCES.resolve(SOURCE_FILES.get(entry.command())));
            Matcher method = LOAD_CONFIG.matcher(source);
            assertThat(method.find()).as(entry.command() + " has a loadConfig method").isTrue();
            Set<String> read = new TreeSet<>();
            Matcher key = KEY.matcher(method.group());
            while (key.find()) {
                read.add(key.group(1));
            }
            if (PathGuard.writesFiles(entry.command())) {
                read.add(PathGuard.OVERWRITE_KEY); // PathGuard reads it for the command
            }
            Set<String> listed = new TreeSet<>();
            entry.inputSchema().path("properties").propertyNames().forEach(listed::add);

            assertThat(listed).as(entry.command() + ": keys in the schema").isEqualTo(read);
        }
    }
}
