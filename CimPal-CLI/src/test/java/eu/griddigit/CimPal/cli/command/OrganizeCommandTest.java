/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliFixtures;
import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.topbraid.shacl.vocabulary.SH;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract of {@code organize} (TEST-4): the files of the template, bad input, and exit 3. */
class OrganizeCommandTest {

    @TempDir
    Path tempDir;

    private String[] args(String option, String value) {
        List<String> flags = new java.util.ArrayList<>(CliFixtures.scenario("organize", tempDir).flags());
        int i = flags.indexOf(option);
        flags.set(i + 1, value);
        flags.addFirst("organize");
        return flags.toArray(String[]::new);
    }

    @Test
    void eachConstraintGoesIntoItsTemplateFile() {
        Path out = tempDir.resolve("organized");

        CliRun.Result run = CliRun.run(args("--output-dir", out.toString()));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        Model r = RDFDataMgr.loadModel(out.resolve("line/r.ttl").toString());
        Model x = RDFDataMgr.loadModel(out.resolve("line/x.ttl").toString());
        assertThat(r.listObjectsOfProperty(SH.name).toList()).extracting(n -> n.asLiteral().getString())
                .contains("Line.r-range").doesNotContain("Line.x-range");
        assertThat(x.listObjectsOfProperty(SH.name).toList()).extracting(n -> n.asLiteral().getString())
                .contains("Line.x-range").doesNotContain("Line.r-range");
    }

    @Test
    void aMissingTemplateIsBadInputAndWritesNothing() {
        Path out = tempDir.resolve("organized");
        String[] args = args("--template-xlsx", tempDir.resolve("missing.xlsx").toString());

        CliRun.Result run = CliRun.run(args);

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("missing.xlsx");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputFolderThatIsAFileIsAnInternalError() throws Exception {
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = CliRun.run(args("--output-dir", blocker.resolve("organized").toString()));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }
}
