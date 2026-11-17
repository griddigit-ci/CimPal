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
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code validate --workflow profiles} on the command line, through {@code mcp} and {@code run},
 * and its path checks. The data set is small and synthetic: one area with a device that has two
 * names (a cardinality finding) and a boundary, against two made-up profiles.
 */
class ValidateProfilesWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "http://example.org/data";
    private static final String CORE = "urn:example:profile:Core/1";

    @TempDir
    Path tempDir;

    private Path models;
    private Path library;
    private Path out;

    @BeforeEach
    void dataSet() throws IOException {
        models = tempDir.resolve("models");
        library = tempDir.resolve("lib");
        out = tempDir.resolve("out");
        write(library.resolve("SHACL/Core-Con-Simple.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <http://example.org/grid#> .
                ex:DeviceCard a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceCard-name .
                ex:DeviceCard-name sh:path ex:name ; sh:minCount 1 ; sh:maxCount 1 .
                """);
        write(library.resolve("PROF/Core.rdf"), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:prof="http://www.w3.org/ns/dx/prof/" xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="%s">
                    <rdf:type rdf:resource="http://www.w3.org/ns/dx/prof/Profile"/>
                    <prof:hasResource rdf:resource="%s/con"/>
                  </rdf:Description>
                  <rdf:Description rdf:about="%s/con">
                    <prof:hasRole rdf:resource="http://www.w3.org/ns/dx/prof/role/constraints"/>
                    <prof:hasArtifact>Core-Con-Simple.ttl</prof:hasArtifact>
                    <dcterms:conformsTo>http://www.w3.org/ns/shacl</dcterms:conformsTo>
                  </rdf:Description>
                </rdf:RDF>
                """.formatted(CORE, CORE, CORE));
        model("boundary/bd.xml", CORE, "<ex:Device rdf:ID=\"B1\"><ex:name>B1</ex:name></ex:Device>");
        model("areaA/a.xml", CORE, "<ex:Device rdf:ID=\"D2\"><ex:name>D2</ex:name><ex:name>D2bis</ex:name></ex:Device>");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void model(String relative, String profile, String body) throws IOException {
        write(models.resolve(relative), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:ex="http://example.org/grid#">
                  <md:FullModel rdf:about="urn:uuid:%s">
                    <md:Model.profile>%s</md:Model.profile>
                  </md:FullModel>
                  %s
                </rdf:RDF>
                """.formatted(relative.replace('/', '-'), profile, body));
    }

    private List<String> args(String... extra) {
        List<String> args = new ArrayList<>(List.of("validate", "--workflow", "profiles",
                "--models", models.toString(), "--profiles-library", library.toString(),
                "--boundary", "boundary", "--output", out.toString(), "--xml-base", BASE,
                "--datatype-map", "CGMES30NC25", "--workers", "1"));
        args.addAll(List.of(extra));
        return args;
    }

    private static CliRun.Result run(List<String> args) {
        return CliRun.run(args.toArray(String[]::new));
    }

    // ---- command line ------------------------------------------------------------------

    @Test
    void aRunWithFindingsPrintsOnlyTheJsonDocumentAndExitsWithViolations() throws Exception {
        CliRun.Result result = run(args("--format", "json", "--export-turtle"));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode summary = result.json();
        assertThat(CliSchemas.errors("validate.schema.json", summary)).as(summary.toString()).isEmpty();
        assertThat(summary.path("run").path("workflow").asText()).isEqualTo("profiles");
        assertThat(summary.path("complete").asBoolean()).isTrue();
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
        assertThat(summary.path("totals").path("total").asInt()).isEqualTo(2);
        assertThat(summary.path("totals").path("violations").asInt()).isEqualTo(1);
        assertThat(summary.path("results").path("violations").asInt()).isEqualTo(1);
        JsonNode areaA = summary.path("byGroup").get(1);
        assertThat(areaA.path("group").asText()).isEqualTo("cgmes-areaA");
        assertThat(areaA.path("reported").get(0).asText()).isEqualTo("a.xml");
        assertThat(areaA.path("context").get(0).asText()).isEqualTo("bd.xml");
        assertThat(summary.path("shapes").get(0).path("constraint").asText()).contains("MaxCount");
        assertThat(Path.of(summary.path("report").asText())).isRegularFile();
        assertThat(summary.path("turtleReports")).hasSize(2);
    }

    @Test
    void aCleanCompleteRunExitsZeroAndViolationsCanBeTreatedAsData() throws Exception {
        model("areaA/a.xml", CORE, "<ex:Device rdf:ID=\"D2\"><ex:name>D2</ex:name></ex:Device>");
        assertThat(run(args()).exitCode()).isEqualTo(ExitCode.OK);

        model("areaA/a.xml", CORE, "<ex:Device rdf:ID=\"D2\"><ex:name>D2</ex:name><ex:name>x</ex:name></ex:Device>");
        assertThat(run(args("--violations-exit-code", "0")).exitCode()).isEqualTo(ExitCode.OK);
    }

    @Test
    void anIncompleteRunExitsOneEvenWhenViolationsAreTreatedAsData() throws Exception {
        model("areaA/a.xml", CORE, "<ex:Device rdf:ID=\"D2\"><ex:name>D2</ex:name></ex:Device>");
        model("areaA/other.xml", "urn:example:profile:Unknown/9", "");

        CliRun.Result refused = run(args());
        assertThat(refused.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(refused.stderr()).contains("urn:example:profile:Unknown/9");

        CliRun.Result allowed = run(args("--allow-unmapped", "--violations-exit-code", "0", "--format", "json"));
        assertThat(allowed.exitCode()).as(allowed.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode summary = allowed.json();
        assertThat(summary.path("complete").asBoolean()).isFalse();
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
        assertThat(summary.path("skipped").get(0).path("document").asText()).isEqualTo("other.xml");
    }

    @Test
    void theDryRunShowsTheGroupsWithoutValidating() {
        CliRun.Result result = run(args("--dry-run"));

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(ExitCode.OK);
        assertThat(result.stdout()).contains("cgmes-boundary (boundary)").contains("cgmes-areaA")
                .contains("reported : a.xml").contains("context  : bd.xml").contains("shapes   : Core-Con-Simple.ttl");
        assertThat(out).doesNotExist();
    }

    @Test
    void optionsOfOtherWorkflowsAreRefusedAndProfileOptionsNeedTheProfilesWorkflow() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("m.csv"), "a,b,c\n");
        CliRun.Result mixed = run(args("--mapping-csv", csv.toString()));
        assertThat(mixed.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(mixed.stderr()).contains("--mapping-csv does not apply to the profiles workflow");

        CliRun.Result other = CliRun.run("validate", "--workflow", "combined", "--allow-unmapped",
                "--constraint-files", csv.toString(), "--data-files", csv.toString(), "--output", out.toString());
        assertThat(other.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(other.stderr()).contains("belong to the profiles workflow");

        CliRun.Result noLibrary = CliRun.run("validate", "--workflow", "profiles", "--models", models.toString(),
                "--output", out.toString());
        assertThat(noLibrary.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(noLibrary.stderr()).contains("--profiles-library is required");
    }

    @Test
    void aConfigFileCanGiveExplicitGroupsWithPathsRelativeToIt() throws Exception {
        ObjectNode config = JSON.createObjectNode().put("workflow", "profiles").put("modelsDir", "models")
                .put("outputDir", "out").put("xmlBase", BASE).put("workers", 1).put("format", "json");
        config.putArray("profilesLibrary").add("lib");
        ObjectNode group = config.putArray("groups").addObject().put("name", "only-a");
        group.putArray("reported").add("models/areaA");
        group.putArray("context").add("models/boundary");
        Path file = Files.writeString(tempDir.resolve("profiles.json"), JSON.writeValueAsString(config));

        CliRun.Result result = CliRun.run("validate", "--config", file.toString());

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode summary = result.json();
        assertThat(summary.path("byGroup")).hasSize(1);
        assertThat(summary.path("byGroup").get(0).path("group").asText()).isEqualTo("only-a");
        assertThat(summary.path("skipped").get(0).path("reason").asText()).isEqualTo("not reported in any group");

        group.put("name", "a/b");
        Files.writeString(file, JSON.writeValueAsString(config));
        CliRun.Result badName = CliRun.run("validate", "--config", file.toString());
        assertThat(badName.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(badName.stderr()).contains("\"groups\"");
    }

    // ---- serve, mcp and run: PathGuard ----------------------------------------------------

    @Test
    void explicitGroupsAreRefusedInRequestsAndTheLibraryIsAReadPath() throws Exception {
        PathPolicy policy = PathPolicy.builder().root(tempDir).build();
        ObjectNode request = JSON.createObjectNode().put("workflow", "profiles").put("modelsDir", models.toString());
        request.putArray("profilesLibrary").add(library.toString());

        assertThat(PathGuard.check("validate", request, policy, tempDir).path("profilesLibrary").get(0).asText())
                .isEqualTo(library.toRealPath().toString());

        ObjectNode withGroups = request.deepCopy();
        withGroups.putArray("groups").addObject().put("name", "g");
        assertThatThrownBy(() -> PathGuard.check("validate", withGroups, policy, tempDir))
                .isInstanceOf(PathNotAllowedException.class).hasMessageContaining("configuration file");

        for (String outside : List.of("\\\\host.invalid\\share\\lib", "//host.invalid/share/lib",
                tempDir.resolve("..").resolve("elsewhere").toString(), tempDir.toString() + "/../elsewhere\\lib")) {
            ObjectNode bad = request.deepCopy();
            bad.putArray("profilesLibrary").add(outside);
            assertThatThrownBy(() -> PathGuard.check("validate", bad, policy, tempDir)).as(outside)
                    .isInstanceOf(PathNotAllowedException.class);
        }
        // A common shape is a file name of the library or a path; a path outside the roots is refused.
        ObjectNode common = request.deepCopy();
        Path outsideShapes = Files.writeString(Files.createDirectories(tempDir.getParent().resolve(
                tempDir.getFileName() + "-outside")).resolve("x.ttl"), "");
        common.putArray("commonShapes").add("AllProfiles.ttl").add(outsideShapes.toString());
        assertThatThrownBy(() -> PathGuard.check("validate", common, policy, tempDir))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void theWorkflowRunsAsAnMcpToolCall() throws Exception {
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode arguments = call.putObject("params").put("name", "validate").putObject("arguments");
        arguments.put("workflow", "profiles").put("modelsDir", models.toString()).put("outputDir", out.toString())
                .put("xmlBase", BASE).put("workers", 1);
        arguments.putArray("profilesLibrary").add(library.toString());
        arguments.putArray("boundaryFolders").add("boundary");

        JsonNode result = mcp(JSON.writeValueAsString(call) + "\n", "--root", tempDir.toString());

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode summary = JSON.readTree(result.path("content").get(0).path("text").asText());
        assertThat(summary.path("run").path("workflow").asText()).isEqualTo("profiles");
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
        assertThat(summary.path("byGroup").get(1).path("context").get(0).asText()).isEqualTo("bd.xml");

        arguments.putArray("profilesLibrary").add(tempDir.getParent().resolve("elsewhere").toString());
        JsonNode refused = mcp(JSON.writeValueAsString(call) + "\n", "--root", tempDir.toString());
        assertThat(refused.path("isError").asBoolean()).isTrue();
    }

    @Test
    void theWorkflowRunsAsAPipelineStep() throws Exception {
        ObjectNode step = JSON.createObjectNode().put("id", "v").put("command", "validate")
                .put("workflow", "profiles").put("modelsDir", models.toString()).put("outputDir", out.toString())
                .put("xmlBase", BASE).put("workers", 1);
        step.putArray("profilesLibrary").add(library.toString());
        ObjectNode pipeline = JSON.createObjectNode().put("name", "p").put("stopOnViolations", false);
        pipeline.putArray("steps").add(step);
        Path file = Files.writeString(tempDir.resolve("pipeline.json"), JSON.writeValueAsString(pipeline));

        CliRun.Result result = CliRun.run("run", file.toString(), "--format", "json");

        assertThat(result.stderr()).doesNotContain("not allowed");
        assertThat(result.json().toString()).contains("\"exitCode\":1");
        assertThat(out).isDirectory();
    }

    @Test
    void explicitGroupsAreRefusedThroughMcpAndAsAPipelineStep() throws Exception {
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode arguments = call.putObject("params").put("name", "validate").putObject("arguments");
        arguments.put("workflow", "profiles").put("modelsDir", models.toString()).put("outputDir", out.toString());
        arguments.putArray("profilesLibrary").add(library.toString());
        arguments.putArray("groups").addObject().put("name", "g").putArray("reported").add(models.toString());

        JsonNode result = mcp(JSON.writeValueAsString(call) + "\n", "--root", tempDir.toString());
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.toString()).contains("configuration");

        ObjectNode step = arguments.deepCopy().put("id", "v").put("command", "validate");
        ObjectNode pipeline = JSON.createObjectNode().put("name", "p");
        pipeline.putArray("steps").add(step);
        Path file = Files.writeString(tempDir.resolve("pipeline.json"), JSON.writeValueAsString(pipeline));
        CliRun.Result run = CliRun.run("run", file.toString());
        assertThat(run.exitCode()).isNotEqualTo(ExitCode.OK);
        assertThat(run.stderr() + run.stdout()).contains("configuration file");
        assertThat(out).doesNotExist();
    }

    @Test
    void aNetworkPathAsCommonShapesIsRefusedAndBoundaryFoldersTouchNothing() throws Exception {
        PathPolicy policy = PathPolicy.builder().root(tempDir).build();
        ObjectNode request = JSON.createObjectNode().put("workflow", "profiles").put("modelsDir", models.toString());
        request.putArray("profilesLibrary").add(library.toString());
        request.putArray("commonShapes").add("\\\\host.invalid\\share\\x.ttl");
        assertThatThrownBy(() -> PathGuard.check("validate", request, policy, tempDir))
                .isInstanceOf(PathNotAllowedException.class);

        // Boundary folders are only compared with the documents' paths: an absolute or network
        // value matches nothing and is not looked up.
        CliRun.Result result = run(List.of("validate", "--workflow", "profiles", "--models", models.toString(),
                "--profiles-library", library.toString(), "--boundary", "\\\\host.invalid\\share,C:/elsewhere",
                "--output", out.toString(), "--xml-base", BASE, "--workers", "1", "--format", "json"));
        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(result.json().path("byGroup")).extracting(g -> g.path("group").asText())
                .containsExactly("cgmes-areaA", "cgmes-boundary");
    }

    @Test
    void textFromTheDataCannotForgeALogLine() throws Exception {
        // A document whose header declares a profile IRI with a line break in it.
        model("areaA/odd.xml", "urn:example:profile:Unknown/9&#10;[ERROR] forged", "");

        CliRun.Result result = run(args("--allow-unmapped"));

        assertThat(result.stderr().lines().filter(l -> l.startsWith("[ERROR] forged"))).isEmpty();
        CliRun.Result dryRun = run(args("--allow-unmapped", "--dry-run"));
        assertThat(dryRun.stdout().lines().filter(l -> l.startsWith("[ERROR] forged"))).isEmpty();
    }

    private static JsonNode mcp(String stdin, String... options) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(UTF_8)));
            System.setOut(new PrintStream(stdout, true, UTF_8));
            List<String> args = new ArrayList<>(List.of("mcp"));
            args.addAll(List.of(options));
            new CommandLine(new CimPalCli()).execute(args.toArray(String[]::new));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        return JSON.readTree(stdout.toString(UTF_8).lines().filter(l -> !l.isBlank()).findFirst().orElseThrow())
                .path("result");
    }
}
