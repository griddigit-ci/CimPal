/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.CliSchemas;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract of {@code compare-instances} (TEST-4): differences are exit 1, data in JSON, failures exit 3. */
class CompareInstancesCommandTest {

    @TempDir
    Path tempDir;

    private Path model(String name, String substationName) throws Exception {
        return Files.writeString(tempDir.resolve(name), TestModels.eq()
                .resource("Substation", "_sub1").literal("IdentifiedObject.name", substationName).toRdfXml());
    }

    private CliRun.Result compare(Path a, Path b, String... extra) {
        String[] args = new String[6 + extra.length];
        System.arraycopy(new String[] {"compare-instances", "--models-a", a.toString(), "--models-b", b.toString(),
                "--xml-base=" + TestModels.XML_BASE}, 0, args, 0, 6);
        System.arraycopy(extra, 0, args, 6, extra.length);
        return CliRun.run(args);
    }

    @Test
    void identicalModelsExitZero() throws Exception {
        CliRun.Result run = compare(model("a.xml", "North"), model("b.xml", "North"), "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(run.json().path("totalDifferences").asInt()).isZero();
    }

    @Test
    void aChangedValueIsADifferenceWithBothValues() throws Exception {
        CliRun.Result run = compare(model("a.xml", "North"), model("b.xml", "South"), "--format", "json");

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.VIOLATIONS);
        JsonNode json = run.json();
        assertThat(CliSchemas.errors("compare-instances.schema.json", json)).isEmpty();
        assertThat(json.path("differences")).anySatisfy(d -> {
            assertThat(d.path("valueA").asString()).contains("North");
            assertThat(d.path("valueB").asString()).contains("South");
        });
    }

    @Test
    void violationsExitCodeTurnsDifferencesIntoData() throws Exception {
        CliRun.Result run = compare(model("a.xml", "North"), model("b.xml", "South"),
                "--format", "json", "--violations-exit-code", "0");

        assertThat(run.exitCode()).isEqualTo(ExitCode.OK);
        assertThat(run.json().path("totalDifferences").asInt()).isPositive();
    }

    @Test
    void aMissingModelIsBadInput() throws Exception {
        CliRun.Result run = compare(model("a.xml", "North"), tempDir.resolve("missing.xml"), "--format", "json");

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).isEmpty();
    }

    @Test
    void anOutputThatCannotBeWrittenIsAnInternalError() throws Exception {
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = compare(model("a.xml", "North"), model("b.xml", "South"),
                "--output", blocker.resolve("diff.xlsx").toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }
}
