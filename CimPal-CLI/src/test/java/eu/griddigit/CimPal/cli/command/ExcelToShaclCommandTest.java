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

/** The contract of {@code excel2shacl} (TEST-4): the shapes file, bad input, and exit 3. */
class ExcelToShaclCommandTest {

    @TempDir
    Path tempDir;

    private String[] args(Path output) {
        List<String> flags = new java.util.ArrayList<>(CliFixtures.scenario("excel2shacl", tempDir).flags());
        int i = flags.indexOf("--output");
        flags.set(i + 1, output.toString());
        flags.addFirst("excel2shacl");
        return flags.toArray(String[]::new);
    }

    @Test
    void theWorkbookConstraintBecomesAShape() {
        Path out = tempDir.resolve("shapes.ttl");

        CliRun.Result run = CliRun.run(args(out));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        Model shapes = RDFDataMgr.loadModel(out.toString());
        assertThat(shapes.listSubjectsWithProperty(SH.minInclusive).toList()).isNotEmpty();
    }

    @Test
    void aMissingWorkbookIsBadInputAndWritesNothing() {
        Path out = tempDir.resolve("shapes.ttl");
        String[] args = args(out);
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--excel-file")) {
                args[i + 1] = tempDir.resolve("missing.xlsx").toString();
            }
        }

        CliRun.Result run = CliRun.run(args);

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("missing.xlsx");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputThatCannotBeWrittenIsAnInternalError() throws Exception {
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = CliRun.run(args(blocker.resolve("shapes.ttl")));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }
}
