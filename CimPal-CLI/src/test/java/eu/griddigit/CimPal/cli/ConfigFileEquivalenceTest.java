/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A config file and the equivalent flags give the same run (TEST-4): the same exit code, the
 * same stdout and the same output files, once the run folder and timestamps are normalised. Keys
 * starting with {@code _} are documentation and change nothing (D6: unknown keys are ignored
 * silently, as before).
 */
class ConfigFileEquivalenceTest {

    static final List<String> COMMANDS = CliFixtures.CONFIG_COMMANDS;

    @TempDir
    Path flagsDir;

    @TempDir
    Path configDir;

    @TempDir
    Path commentedDir;

    /** What a run produced, with {@code dir} normalised away. */
    private record Outcome(int exitCode, String stdout, Map<String, String> outputs) {
    }

    private static Outcome outcome(CliFixtures.Scenario scenario, Path dir, CliRun.Result run) throws Exception {
        // The folder also appears JSON-escaped (C:\\Users\\...); validate names anonymous shapes by
        // their blank-node label; manifest names its nodes by random UUIDs.
        String escaped = dir.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        Normalizer normalizer = Normalizer.chain(value -> value.replace(escaped, "<BASE>"), Normalizer.paths(dir),
                Normalizer.timestamps(), Normalizer.blankNodeLabels(),
                Normalizer.regex("urn:uuid:[0-9a-fA-F-]{36}", "urn:uuid:normalized"),
                // ... and gives the catalog that UUID as its dct:identifier.
                Normalizer.regex("\"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"",
                        "\"normalized\""));
        Map<String, String> outputs = new java.util.TreeMap<>();
        for (String output : scenario.outputs()) {
            Path file = dir.resolve(output);
            assertThat(file).as("%s; stderr:%n%s", output, run.stderr()).isRegularFile();
            outputs.put(output, normalizer.apply(Files.readString(file)));
        }
        return new Outcome(run.exitCode(), normalizer.apply(run.stdout()), outputs);
    }

    private static void assertSameOutputs(Outcome expected, Outcome actual) {
        assertThat(actual.exitCode()).isEqualTo(expected.exitCode());
        assertThat(actual.stdout()).isEqualTo(expected.stdout());
        assertThat(actual.outputs().keySet()).isEqualTo(expected.outputs().keySet());
        for (var output : expected.outputs().entrySet()) {
            String name = output.getKey();
            Lang lang = name.endsWith(".ttl") ? Lang.TURTLE : name.endsWith(".xml") ? Lang.RDFXML : null;
            if (lang == null) {
                assertThat(actual.outputs().get(name)).as(name).isEqualTo(output.getValue());
            } else {
                Snapshots.assertIsomorphic(parse(output.getValue(), lang), parse(actual.outputs().get(name), lang));
            }
        }
    }

    private static Model parse(String text, Lang lang) {
        return RDFParser.fromString(text, lang).base("http://example.com/base").toModel();
    }

    /** {@code config} with a {@code _comment} and, for every key, an ignored {@code _<key>} holding another value. */
    private static ObjectNode withDocumentationKeys(ObjectNode config) {
        ObjectNode commented = config.objectNode();
        commented.put("_comment", "Documentation only: every key starting with _ is ignored.");
        List<String> keys = new ArrayList<>();
        config.properties().forEach(property -> keys.add(property.getKey()));
        for (String key : keys) {
            commented.put("_" + key, "ignored-" + key);
            commented.set(key, config.get(key));
        }
        return commented;
    }

    @ParameterizedTest
    @FieldSource("COMMANDS")
    void aConfigFileAndTheSameFlagsGiveTheSameRun(String command) throws Exception {
        CliFixtures.Scenario byFlags = CliFixtures.scenario(command, flagsDir);
        CliFixtures.Scenario byConfig = CliFixtures.scenario(command, configDir);

        Outcome flags = outcome(byFlags, flagsDir, CliRun.run(byFlags.args()));
        Outcome config = outcome(byConfig, configDir, CliRun.run(byConfig.configArgs(configDir)));

        assertThat(flags.exitCode()).as(flags.stdout()).isEqualTo(byFlags.exitCode());
        assertSameOutputs(flags, config);
    }

    @ParameterizedTest
    @FieldSource("COMMANDS")
    void keysStartingWithAnUnderscoreChangeNothing(String command) throws Exception {
        CliFixtures.Scenario plain = CliFixtures.scenario(command, configDir);
        CliFixtures.Scenario commented = CliFixtures.scenario(command, commentedDir);
        commented = new CliFixtures.Scenario(command, commented.flags(), withDocumentationKeys(commented.config()),
                commented.exitCode(), commented.outputs());

        Outcome expected = outcome(plain, configDir, CliRun.run(plain.configArgs(configDir)));
        Outcome actual = outcome(commented, commentedDir, CliRun.run(commented.configArgs(commentedDir)));

        assertThat(expected.exitCode()).as(expected.stdout()).isEqualTo(plain.exitCode());
        assertSameOutputs(expected, actual);
    }
}
