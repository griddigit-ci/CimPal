/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CimPalCliTest {

    private record Run(int exitCode, String out, String err) {
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new CimPalCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exitCode = cli.execute(args);
        return new Run(exitCode, out.toString(), err.toString());
    }

    @Test
    void helpExitsZeroAndListsSubcommands() {
        Run run = run("--help");

        assertThat(run.exitCode()).isEqualTo(ExitCode.OK);
        assertThat(run.out()).contains("validate", "sparql", "convert", "rdfs2shacl", "serve", "mcp");
    }

    @Test
    void inProcessCommandLineDoesNotExpandAtFiles(@TempDir Path tempDir) throws Exception {
        Path args = Files.writeString(tempDir.resolve("args.txt"), "--help");
        CommandLine expanding = new CommandLine(new CimPalCli());
        expanding.setOut(new PrintWriter(new StringWriter()));
        CommandLine inProcess = CimPalCli.inProcess();
        inProcess.setOut(new PrintWriter(new StringWriter()));
        inProcess.setErr(new PrintWriter(new StringWriter()));

        assertThat(expanding.execute("@" + args)).isEqualTo(ExitCode.OK);
        assertThat(inProcess.execute("@" + args)).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(CimPalCli.inProcess().isExpandAtFiles()).isFalse();
    }

    @Test
    void unknownOptionIsAUsageErrorNotACrash() {
        Run run = run("--no-such-option");

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.err()).contains("--no-such-option");
    }
}
