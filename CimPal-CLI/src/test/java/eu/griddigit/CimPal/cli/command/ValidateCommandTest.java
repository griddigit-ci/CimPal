/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
}
