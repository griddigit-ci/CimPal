/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ValidateCommandTest {

    /** One ex:Thing with the ex:size that {@link TestModels#THING_SHAPES} requires. */
    private static final String CONFORMING_THING_MODEL = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
              <ex:Thing rdf:about="#_1"><ex:size>1</ex:size></ex:Thing>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    /**
     * The manual workflow tested SHACL rules against Conform / NonConform models, which is the
     * GUI's rule test now. A configuration that gives it everything it needed - the workflow, a
     * shapes file and a folder of models in the rule-test layout - ran before (exit 0, writing a
     * log into the models folder). Now it is bad input, refused before anything is read or written.
     */
    @Test
    void manualWorkflowConfigIsRefusedBeforeAnythingIsReadOrWritten() throws Exception {
        Files.writeString(tempDir.resolve("shapes.ttl"), TestModels.THING_SHAPES);
        Path model = Files.createDirectories(tempDir.resolve("models/ThingRule/Conform")).resolve("thing.xml");
        Files.writeString(model, CONFORMING_THING_MODEL);
        Path config = Files.writeString(tempDir.resolve("validate-manual.json"), """
                {
                  "workflow": "manual",
                  "shaclConstraintFiles": ["shapes.ttl"],
                  "modelsDir": "models",
                  "xmlBase": "%s"
                }
                """.formatted(TestModels.XML_BASE));

        int exitCode = new CommandLine(new CimPalCli()).execute("validate", "--config", config.toString());

        assertThat(exitCode).isEqualTo(ExitCode.INVALID_INPUT);
        try (Stream<Path> files = Files.walk(tempDir.resolve("models"))) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEqualTo(List.of(model));
        }
    }

    @Test
    void shaclFilesOptionIsGone() {
        CommandLine validate = new CommandLine(new CimPalCli()).getSubcommands().get("validate");

        assertThat(validate.getCommandSpec().findOption("--shacl-files")).isNull();
    }

    // ---- combined workflow -----------------------------------------------------------------

    /** A second constraint file: ex:Thing needs an ex:name too. */
    private static final String NAME_SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <urn:test:> .
            ex:NamedThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
                sh:property [ sh:path ex:name ; sh:minCount 1 ] .
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String rdfXml(String body) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                %s
                </rdf:RDF>
                """.formatted(body);
    }

    private Path write(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private Path zip(String relative, String entry, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static int validate(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "validate";
        System.arraycopy(args, 0, command, 1, args.length);
        return new CommandLine(new CimPalCli()).execute(command);
    }

    /** Thing _1 is described across both files; Thing _2 has neither a size nor a name. */
    private String[] combinedRunWithTwoFindings(String... extraArgs) throws Exception {
        write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        write("constraints/name.ttl", NAME_SHAPES);
        write("models/eq.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/><ex:Thing rdf:about=\"#_2\"/>"));
        write("models/ssh.xml", rdfXml("<rdf:Description rdf:about=\"#_1\"><ex:size>1</ex:size>"
                + "<ex:name>one</ex:name></rdf:Description>"));
        List<String> args = new java.util.ArrayList<>(List.of("--workflow", "combined",
                "--constraint-files", tempDir.resolve("constraints/shapes.ttl") + "," + tempDir.resolve("constraints/name.ttl"),
                "--data-files", tempDir.resolve("models/eq.xml") + "," + tempDir.resolve("models/ssh.xml"),
                "--output", tempDir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--workers", "1",
                "--summary-file", tempDir.resolve("summary.json").toString()));
        args.addAll(List.of(extraArgs));
        return args.toArray(String[]::new);
    }

    @Test
    void combinedWorkflowValidatesTheDataFilesAsOneDatasetAgainstEveryConstraintFile() throws Exception {
        int exitCode = validate(combinedRunWithTwoFindings());

        assertThat(exitCode).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode summary = JSON.readTree(tempDir.resolve("summary.json").toFile());
        assertThat(summary.path("run").path("workflow").asString()).isEqualTo("combined");
        assertThat(summary.path("run").path("inputs").path("dataFiles")).hasSize(2);
        // One row per constraint file, as the workbook has: each found one problem with Thing _2.
        assertThat(summary.path("totals").path("total").asInt()).isEqualTo(2);
        assertThat(summary.path("totals").path("violations").asInt()).isEqualTo(2);
        assertThat(summary.path("totals").path("conforming").asInt()).isZero();
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
        assertThat(summary.path("results").path("violations").asInt()).isEqualTo(2);
        List<String> constraintFiles = new java.util.ArrayList<>();
        for (JsonNode file : summary.path("byConstraintFile")) {
            constraintFiles.add(file.path("constraintFile").asString());
            assertThat(file.path("conforms").asBoolean()).isFalse();
        }
        assertThat(constraintFiles).containsExactly("name.ttl", "shapes.ttl");
        assertThat(summary.path("shapes")).hasSize(2)
                .allSatisfy(group -> assertThat(group.path("sampleFocusNodes").get(0).asString()).endsWith("_2"));
        Path report = Path.of(summary.path("report").asString());
        assertThat(report.getParent()).isEqualTo(tempDir.resolve("out"));
        assertThat(report.getFileName().toString()).matches("validation_report__\\d{8}_\\d{6}\\.xlsx");
        assertThat(report).isRegularFile();
        assertThat(summary.has("turtleReport")).isFalse();
    }

    @Test
    void combinedWorkflowReadsZipsNamedInAConfigRelativeToIt() throws Exception {
        zip("cfg/constraints.zip", "CGMES/shapes.ttl", TestModels.THING_SHAPES);
        zip("cfg/models/igm.zip", "eq.xml", CONFORMING_THING_MODEL);
        Path config = write("cfg/validate-combined.json", """
                {
                  "workflow": "combined",
                  "constraintFiles": ["constraints.zip"],
                  "dataFiles": ["models/igm.zip"],
                  "outputDir": "out",
                  "xmlBase": "%s",
                  "workers": 1,
                  "exportTurtle": true,
                  "summaryFile": "summary.json"
                }
                """.formatted(TestModels.XML_BASE));

        int exitCode = validate("--config", config.toString());

        assertThat(exitCode).isEqualTo(ExitCode.OK);
        JsonNode summary = JSON.readTree(tempDir.resolve("cfg/summary.json").toFile());
        assertThat(summary.path("totals").path("conforming").asInt()).isEqualTo(1);
        assertThat(summary.path("hasViolations").asBoolean()).isFalse();
        assertThat(summary.path("byConstraintFile").get(0).path("constraintFile").asString()).isEqualTo("shapes.ttl");
        assertThat(summary.path("byConstraintFile").get(0).path("conforms").asBoolean()).isTrue();
        Path report = Path.of(summary.path("report").asString());
        assertThat(report.getParent()).isEqualTo(tempDir.resolve("cfg/out"));
        // The Turtle report sits beside the workbook, under the same name.
        assertThat(Path.of(summary.path("turtleReport").asString()))
                .isEqualTo(report.resolveSibling(report.getFileName().toString().replace(".xlsx", ".ttl")))
                .isRegularFile();
    }

    @Test
    void combinedWorkflowNeedsItsConstraintAndDataFiles() throws Exception {
        Path shapes = write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        Path data = write("models/eq.xml", CONFORMING_THING_MODEL);
        String out = tempDir.resolve("out").toString();

        assertThat(validate("--workflow", "combined", "--data-files", data.toString(), "--output", out))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(validate("--workflow", "combined", "--constraint-files", shapes.toString(), "--output", out))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(validate("--workflow", "combined", "--constraint-files", shapes.toString(),
                "--data-files", data + "," + tempDir.resolve("models/missing.xml"), "--output", out))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(tempDir.resolve("out")).doesNotExist();
    }

    @Test
    void constraintsThatCannotBeReadAreBadInputEvenWhenFindingsMayExitWithZero() throws Exception {
        // One validation: if its shapes can't all be loaded, nothing is validated, and the run must
        // not end as a pass - not even with the violations exit code remapped to 0.
        Path shapes = write("constraints/shapes.ttl", TestModels.THING_SHAPES
                + "<urn:test:shapes> <http://www.w3.org/2002/07/owl#imports> <missing.ttl> .\n");
        Path data = write("models/eq.xml", CONFORMING_THING_MODEL);

        int exitCode = validate("--workflow", "combined", "--constraint-files", shapes.toString(),
                "--data-files", data.toString(), "--output", tempDir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--violations-exit-code", "0",
                "--summary-file", tempDir.resolve("summary.json").toString());

        assertThat(exitCode).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(tempDir.resolve("summary.json")).doesNotExist();
        assertThat(tempDir.resolve("out")).doesNotExist();
    }

    @Test
    void violationsExitCodeAppliesToCombinedFindings() throws Exception {
        int exitCode = validate(combinedRunWithTwoFindings("--violations-exit-code", "0"));

        assertThat(exitCode).isEqualTo(ExitCode.OK);
        JsonNode summary = JSON.readTree(tempDir.resolve("summary.json").toFile());
        assertThat(summary.path("hasViolations").asBoolean()).isTrue();
    }

    @Test
    void aCombinedRunThatChecksNothingIsBadInputNotAPass() throws Exception {
        // Shapes without a target, or data without a triple, "conform" without one check being made.
        Path untargeted = write("constraints/untargeted.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:S> a sh:NodeShape ; sh:property [ sh:path <urn:test:p> ; sh:minCount 1 ] .
                """);
        Path shapes = write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        Path data = write("models/eq.xml", CONFORMING_THING_MODEL);
        Path empty = write("models/empty.xml", rdfXml(""));
        String out = tempDir.resolve("out").toString();

        assertThat(validate("--workflow", "combined", "--constraint-files", untargeted.toString(),
                "--data-files", data.toString(), "--output", out, "--xml-base", TestModels.XML_BASE))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(validate("--workflow", "combined", "--constraint-files", shapes.toString(),
                "--data-files", empty.toString(), "--output", out, "--xml-base", TestModels.XML_BASE))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(tempDir.resolve("out")).doesNotExist();
    }

    @Test
    void aDataFileThatDoesNotParseIsBadInputNamedOnOneLine() throws Exception {
        Path shapes = write("constraints/shapes.ttl", TestModels.THING_SHAPES);
        Path broken = write("models/broken.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n<x");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try {
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exitCode = validate("--workflow", "combined", "--constraint-files", shapes.toString(),
                    "--data-files", broken.toString(), "--output", tempDir.resolve("out").toString(),
                    "--xml-base", TestModels.XML_BASE);
        } finally {
            System.setErr(originalErr);
        }

        assertThat(exitCode).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(tempDir.resolve("out")).doesNotExist();
        assertThat(stderr.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("[ERROR]")).toList())
                .singleElement().asString().contains("data file broken.xml");
    }

    @Test
    void aPathTheActivePolicyRefusesIsBadInput() throws Exception {
        // serve, mcp and run execute under a PathPolicy; Core refuses a file outside it, exit 2.
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path shapes = write("allowed/shapes.ttl", TestModels.THING_SHAPES);
        Path outside = write("outside/eq.xml", CONFORMING_THING_MODEL);
        PathPolicy policy = PathPolicy.builder().root(allowed).build();

        int exitCode = PathPolicy.runWith(policy, () -> validate("--workflow", "combined",
                "--constraint-files", shapes.toString(), "--data-files", outside.toString(),
                "--output", allowed.resolve("out").toString(), "--xml-base", TestModels.XML_BASE));

        assertThat(exitCode).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(allowed.resolve("out")).doesNotExist();
    }

    @Test
    void jsonStringsEscapeControlCharacters() {
        String value = "a\nb\tc\u0001\"d\\e";

        assertThat(JSON.readTree(ValidateCommand.jsonStr(value)).asString()).isEqualTo(value);
    }
}
