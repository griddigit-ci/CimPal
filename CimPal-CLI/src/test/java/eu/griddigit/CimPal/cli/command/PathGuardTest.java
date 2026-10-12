/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Path checks for serve/mcp/run input (SEC-2, gap G2). */
class PathGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path root;
    private Path outside;
    private PathPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(tempDir.resolve("root"));
        outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(root.resolve("in.ttl"), "<urn:a> <urn:p> \"x\" .\n");
        Files.writeString(outside.resolve("secret.ttl"), "x");
        policy = PathPolicy.builder().root(root).build();
    }

    private ObjectNode json(String text) {
        return (ObjectNode) MAPPER.readTree(text);
    }

    private static String q(Path p) {
        return MAPPER.writeValueAsString(p.toString());
    }

    @Test
    void aJobOnAnUploadLooksUpRelativeValuesInItsUploadOnly() {
        // pom.xml exists in the working directory of this test (the module folder), outside the
        // roots: shared mode refuses it, a job on an upload never even looks there (DEP-7).
        assertThat(Files.exists(Path.of("pom.xml"))).isTrue();
        assertThatThrownBy(() -> PathGuard.check("sparql", json("{\"query\":\"pom.xml\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);

        ObjectNode checked = PathGuard.check("sparql", json("{\"query\":\"pom.xml\"}"), policy, root, true);
        assertThat(checked.path("query").asString()).as("not a file in the upload: kept as text").isEqualTo("pom.xml");
        ObjectNode inUpload = PathGuard.check("sparql", json("{\"query\":\"in.ttl\"}"), policy, root, true);
        assertThat(inUpload.path("query").asString()).isEqualTo(realPath(root.resolve("in.ttl")).toString());
    }

    @Test
    void outputsOfAJobOnAnUploadArePlacedInItsOutFolder() throws Exception {
        Path out = Files.createDirectories(root.resolve("out"));
        ObjectNode placed = PathGuard.placeOutputs("validate", json("{\"summaryFile\":\"s.json\",\"workflow\":\"mapping\"}"), out);

        assertThat(placed.path("summaryFile").asString()).isEqualTo(out.resolve("s.json").toString());
        assertThat(placed.path("outputDir").asString()).as("missing folder: out/ itself").isEqualTo(out.toString());
        ObjectNode absolute = PathGuard.placeOutputs("sparql", json("{\"output\":" + q(outside.resolve("x.csv")) + "}"), out);
        assertThat(absolute.path("output").asString()).as("left for the policy to refuse")
                .isEqualTo(outside.resolve("x.csv").toString());
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyFileOptionOfEveryExposedCommandIsInTheRegistry() {
        CommandLine cli = new CommandLine(new CimPalCli());
        for (String command : ServeServer.COMMANDS) {
            assertThat(PathGuard.FIELDS).as("registry entry for %s", command).containsKey(command);
            Set<String> registered = PathGuard.FIELDS.get(command).stream()
                    .map(PathGuard.Field::option).collect(Collectors.toSet());
            for (OptionSpec option : cli.getSubcommands().get(command).getCommandSpec().options()) {
                if (isFileOption(option) && !option.longestName().equals("--config")) {
                    assertThat(registered).as("%s %s is a file option", command, option.longestName())
                            .contains(option.longestName());
                }
            }
        }
    }

    private static boolean isFileOption(OptionSpec option) {
        Class<?> type = option.type();
        if (type == File.class || type == Path.class) {
            return true;
        }
        for (Class<?> aux : option.auxiliaryTypes()) {
            if (aux == File.class || aux == Path.class) {
                return true;
            }
        }
        return false;
    }

    @Test
    void relativePathsBecomeCheckedAbsolutePaths() throws Exception {
        ObjectNode checked = PathGuard.check("convert", json("{\"input\":\"in.ttl\",\"output\":\"out/x.ttl\"}"),
                policy, root);

        assertThat(Path.of(checked.get("input").asString())).isEqualTo(root.resolve("in.ttl").toRealPath());
        assertThat(Path.of(checked.get("output").asString()).isAbsolute()).isTrue();
    }

    @Test
    void traversalAndOutsidePathsAreRefused() {
        assertThatThrownBy(() -> PathGuard.check("convert",
                json("{\"input\":\"../outside/secret.ttl\",\"output\":\"x.ttl\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> PathGuard.check("convert",
                json("{\"input\":" + q(outside.resolve("secret.ttl")) + "}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> PathGuard.check("convert",
                json("{\"input\":\"in.ttl\",\"output\":" + q(outside.resolve("x.ttl")) + "}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void uncPathIsRefused() {
        assertThatThrownBy(() -> PathGuard.check("convert",
                json("{\"input\":\"\\\\\\\\attacker\\\\share\\\\x.ttl\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("UNC");
    }

    @Test
    void existingOutputNeedsOverwrite() {
        ObjectNode request = json("{\"input\":\"in.ttl\",\"output\":\"in.ttl\"}");

        assertThatThrownBy(() -> PathGuard.check("convert", request, policy, root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("overwrite");
        request.put(PathGuard.OVERWRITE_KEY, true);
        assertThat(PathGuard.check("convert", request, policy, root).get("output").asString()).isNotBlank();
    }

    @Test
    void existingOutputFolderIsFine() {
        PathGuard.check("rdfs2shacl", json("{\"rdfsFiles\":[\"in.ttl\"],\"outputDir\":\".\"}"), policy, root);
    }

    @Test
    void everyArrayElementIsChecked() {
        assertThatThrownBy(() -> PathGuard.check("compare-instances",
                json("{\"modelsA\":[\"in.ttl\"],\"modelsB\":[\"in.ttl\"," + q(outside.resolve("secret.ttl")) + "]}"),
                policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void combinedValidationFilesAreCheckedOneByOne() throws Exception {
        // validate --workflow combined reads every file listed; one outside the roots refuses the request.
        ObjectNode checked = PathGuard.check("validate",
                json("{\"workflow\":\"combined\",\"constraintFiles\":[\"in.ttl\"],\"dataFiles\":[\"in.ttl\"],"
                        + "\"outputDir\":\"out\"}"), policy, root);
        Path in = root.resolve("in.ttl").toRealPath();
        assertThat(Path.of(checked.get("constraintFiles").get(0).asString())).isEqualTo(in);
        assertThat(Path.of(checked.get("dataFiles").get(0).asString())).isEqualTo(in);

        for (String key : List.of("constraintFiles", "dataFiles")) {
            assertThatThrownBy(() -> PathGuard.check("validate",
                    json("{\"workflow\":\"combined\",\"" + key + "\":[\"in.ttl\"," + q(outside.resolve("secret.ttl"))
                            + "]}"), policy, root))
                    .as(key)
                    .isInstanceOf(PathNotAllowedException.class);
            // A string is one path to check, not a list to split: the command doesn't split it either.
            assertThatThrownBy(() -> PathGuard.check("validate",
                    json("{\"workflow\":\"combined\",\"" + key + "\":\"../outside/secret.ttl\"}"), policy, root))
                    .as(key)
                    .isInstanceOf(PathNotAllowedException.class);
        }
    }

    @Test
    void commaSeparatedListIsCheckedPerEntry() {
        String models = "in.ttl," + outside.resolve("secret.ttl");

        assertThatThrownBy(() -> PathGuard.check("sparql",
                json("{\"models\":" + MAPPER.writeValueAsString(models) + ",\"query\":\"SELECT * {}\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void inlineQueryIsLeftAloneButAQueryFileOutsideIsRefused() {
        ObjectNode inline = PathGuard.check("sparql",
                json("{\"models\":[\"in.ttl\"],\"query\":\"SELECT ?s WHERE { ?s ?p ?o }\"}"), policy, root);
        assertThat(inline.get("query").asString()).isEqualTo("SELECT ?s WHERE { ?s ?p ?o }");

        assertThatThrownBy(() -> PathGuard.check("sparql",
                json("{\"models\":[\"in.ttl\"],\"query\":" + q(outside.resolve("secret.ttl")) + "}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> PathGuard.check("validate",
                json("{\"datatypeMap\":\"\\\\\\\\attacker\\\\share\\\\map.properties\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void mixedSeparatorUncInPresetOrPathFieldsIsRefusedBeforeAnyProbe() {
        assertThatThrownBy(() -> PathGuard.check("sparql",
                json("{\"models\":[\"in.ttl\"],\"query\":\"\\\\/attacker.invalid/s/q.rq\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("UNC");
        assertThatThrownBy(() -> PathGuard.check("validate",
                json("{\"datatypeMap\":\"/\\\\attacker.invalid\\\\s\\\\map.properties\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void presetOrPathValueIsAlsoLookedUpInTheWorkingDirectory() {
        // The command resolves "query" against the working directory (here the CimPal-CLI
        // module), which is outside the root: an existing file there must not slip through.
        assertThatThrownBy(() -> PathGuard.check("sparql",
                json("{\"models\":[\"in.ttl\"],\"query\":\"pom.xml\"}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void commaIsOnlySplitForKeysTheCommandSplits() throws Exception {
        Files.writeString(root.resolve("a,b.ttl"), "<urn:a> <urn:p> \"x\" .\n");

        ObjectNode checked = PathGuard.check("convert", json("{\"input\":\"a,b.ttl\"}"), policy, root);

        assertThat(Path.of(checked.get("input").asString()).getFileName().toString()).isEqualTo("a,b.ttl");
    }

    @Test
    void presetNamesAreNotTreatedAsPaths() {
        ObjectNode checked = PathGuard.check("validate", json("{\"datatypeMap\":\"CGMES30NC25\"}"), policy, root);

        assertThat(checked.get("datatypeMap").asString()).isEqualTo("CGMES30NC25");
    }

    @Test
    void nonStringPathValueIsRefused() {
        assertThatThrownBy(() -> PathGuard.check("convert", json("{\"input\":{\"path\":\"in.ttl\"}}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void runStepConfigKeyIsCheckedToo() {
        assertThatThrownBy(() -> PathGuard.check("convert",
                json("{\"config\":" + q(outside.resolve("secret.ttl")) + "}"), policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void nonObjectRequestIsRefused() {
        JsonNode array = MAPPER.readTree("[1,2]");

        assertThatThrownBy(() -> PathGuard.check("convert", array, policy, root))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void registryKeysCoverEveryCommandThatServeExposes() {
        assertThat(PathGuard.FIELDS.keySet()).containsAll(List.copyOf(ServeServer.COMMANDS));
    }
}
