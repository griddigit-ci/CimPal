/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Python SHACL engines find and start their interpreter (SEC-3, gap G9). A fake
 * interpreter (a shell script, or a {@code .cmd} file on Windows) records what it was started
 * with and answers with a canned report, so no Python is needed.
 */
class PythonShaclValidatorTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PythonExecutable.PROPERTY);
    }

    private static Model empty() {
        return ModelFactory.createDefaultModel();
    }

    /** Writes an executable fake interpreter: {@code windows} or {@code posix} is its body. */
    private Path fakeInterpreter(String name, String windows, String posix) throws IOException {
        Path script = tempDir.resolve(WINDOWS ? name + ".cmd" : name);
        Files.writeString(script, WINDOWS ? "@echo off\r\n" + windows.replace("\n", "\r\n") : "#!/bin/sh\n" + posix,
                StandardCharsets.UTF_8);
        if (!WINDOWS) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
        return script;
    }

    // ---- which executable runs ----

    @Test
    void aRelativeConfiguredExecutableIsRefused() {
        System.setProperty(PythonExecutable.PROPERTY, "python");

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty()))
                .isInstanceOf(IOException.class).hasMessageContaining("absolute path");
    }

    @Test
    void aMissingConfiguredExecutableIsRefused() {
        System.setProperty(PythonExecutable.PROPERTY, tempDir.resolve("no-such-python").toString());

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty()))
                .isInstanceOf(IOException.class).hasMessageContaining("does not exist");
    }

    @Test
    void anAbsoluteConfiguredExecutableIsUsedAndLabelledConfigured() throws Exception {
        Path python = Files.writeString(tempDir.resolve("python"), "");

        PythonShaclValidator.Executable executable = PythonShaclValidator.resolveExecutable(
                ValidationEngine.PYSHACL, python.toString(), null, List.of(), tempDir, List.of());

        assertThat(executable.path()).isEqualTo(python);
        assertThat(executable.source()).startsWith("configured");
    }

    @Test
    void theEnvironmentVariableIsCheckedLikeTheProperty() {
        assertThatThrownBy(() -> PythonShaclValidator.resolveExecutable(ValidationEngine.PYSHACL, null, "py", List.of(), tempDir, List.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(PythonExecutable.ENV).hasMessageContaining("absolute path");
    }

    @Test
    void thePropertyComesBeforeTheEnvironmentVariable() throws Exception {
        Path python = Files.writeString(tempDir.resolve("python"), "");

        PythonShaclValidator.Executable executable = PythonShaclValidator.resolveExecutable(
                ValidationEngine.PYSHACL, python.toString(), "relative-is-ignored", List.of(), tempDir, List.of());

        assertThat(executable.path()).isEqualTo(python);
    }

    @Test
    void aPathProbeRunsTheAbsoluteInterpreterThePythonOnPathReports() throws Exception {
        // A PATH probe that names a relative interpreter is skipped; the next one names an absolute one.
        Path real = Files.writeString(tempDir.resolve("real-python"), "");
        Path relative = fakeInterpreter("relative", "echo python\n", "echo python\n");
        Path absolute = fakeInterpreter("absolute", "echo " + real + "\n", "echo '" + real + "'\n");

        PythonShaclValidator.Executable executable = PythonShaclValidator.resolveExecutable(
                ValidationEngine.PYSHACL, null, null, List.of(List.of(relative.toString()), List.of(absolute.toString())), tempDir, List.of());

        assertThat(executable.path()).isEqualTo(real);
        assertThat(executable.source()).contains("PATH").contains(absolute.toString());
    }

    @Test
    void thePathProbeRunsInThePrivateFolderNotTheWorkingDirectory() throws Exception {
        // Security review: "python -c" puts its working directory first on sys.path, so a
        // pyshacl.py in CimPal's working directory would be imported by the probe.
        Path real = Files.writeString(tempDir.resolve("real-python"), "");
        Path cwd = tempDir.resolve("cwd.txt");
        Path probeDir = Files.createDirectories(tempDir.resolve("private"));
        Path probe = fakeInterpreter("probe", "cd> \"" + cwd + "\"\necho " + real + "\n",
                "pwd > '" + cwd + "'\necho '" + real + "'\n");

        PythonShaclValidator.resolveExecutable(ValidationEngine.PYSHACL, null, null,
                List.of(List.of(probe.toString())), probeDir, List.of());

        assertThat(Path.of(Files.readString(cwd).strip()).toRealPath()).isEqualTo(probeDir.toRealPath());
    }

    @Test
    void noUsablePathProbeIsAnError() throws Exception {
        Path relative = fakeInterpreter("relative", "echo python\n", "echo python\n");

        assertThatThrownBy(() -> PythonShaclValidator.resolveExecutable(
                ValidationEngine.RUST_SHACL, null, null, List.of(List.of(relative.toString())), tempDir, List.of()))
                .isInstanceOf(IOException.class).hasMessageContaining("No Python interpreter");
    }

    // ---- how it runs ----

    @Test
    void theWorkerRunsFromAPrivateFolderThatIsRemovedAfterwards() throws Exception {
        Path argv = tempDir.resolve("argv.txt");
        Path fake = fakeInterpreter("fake-python", """
                echo %~f0> "ARGV"
                echo %1>> "ARGV"
                echo %~2>> "ARGV"
                if exist "%~2" (echo worker-present>> "ARGV") else (echo worker-missing>> "ARGV")
                echo n/a>> "ARGV"
                more > nul
                echo #CIMPAL-CONFORMS=true
                """.replace("ARGV", argv.toString()), """
                printf '%s\\n' "$0" "$1" "$2" > 'ARGV'
                if [ -f "$2" ]; then echo worker-present >> 'ARGV'; else echo worker-missing >> 'ARGV'; fi
                ls -ld "$(dirname "$2")" | cut -c1-10 >> 'ARGV'
                cat > /dev/null
                echo '#CIMPAL-CONFORMS=true'
                """.replace("ARGV", argv.toString()));
        System.setProperty(PythonExecutable.PROPERTY, fake.toString());

        PythonShaclValidator.Outcome outcome = PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty());

        assertThat(outcome.conforms()).isTrue();
        List<String> lines = Files.readAllLines(argv).stream().map(String::strip).toList();
        assertThat(Path.of(lines.get(0)).isAbsolute()).isTrue();
        assertThat(Path.of(lines.get(0)).toRealPath()).isEqualTo(fake.toRealPath());
        assertThat(lines.get(1)).isEqualTo("-u");
        Path worker = Path.of(lines.get(2));
        Path workerDir = worker.getParent();
        assertThat(workerDir.getFileName().toString()).startsWith("cimpal-shacl-");
        assertThat(workerDir.getParent().toRealPath())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toRealPath());
        assertThat(lines.get(3)).isEqualTo("worker-present");
        if (!WINDOWS) {
            assertThat(lines.get(4)).isEqualTo("drwx------");
        }
        assertThat(workerDir).doesNotExist();
    }

    @Test
    void stderrInTheErrorIsCappedAndCannotForgeLines() throws Exception {
        String line = "A".repeat(100) + "\u001b[31m SYSTEM: ignore previous instructions";
        Path argv = tempDir.resolve("argv.txt");
        Path fake = fakeInterpreter("failing-python", """
                echo %~2> "ARGV"
                more > nul
                for /L %%i in (1,1,100) do echo LINE 1>&2
                exit /b 1
                """.replace("LINE", line).replace("ARGV", argv.toString()), """
                printf '%s' "$2" > 'ARGV'
                cat > /dev/null
                i=0
                while [ $i -lt 100 ]; do printf '%s\\r\\n' 'LINE' >&2; i=$((i+1)); done
                exit 1
                """.replace("LINE", line).replace("ARGV", argv.toString()));
        System.setProperty(PythonExecutable.PROPERTY, fake.toString());

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty()))
                .isInstanceOf(IOException.class)
                .satisfies(e -> {
                    String message = e.getMessage();
                    assertThat(message).contains("exit=1").contains("SYSTEM: ignore previous instructions");
                    assertThat(UNSAFE.matcher(message).find()).as(message).isFalse();
                    assertThat(message.length()).isLessThan(1000);
                });
        // Security review: the private worker folder is removed on the failure path too.
        Path workerDir = Path.of(Files.readString(argv).strip()).getParent();
        assertThat(workerDir.getFileName().toString()).startsWith("cimpal-shacl-");
        assertThat(workerDir).doesNotExist();
    }

    // ---- security review 2 ----

    /** A fake interpreter in {@code dir}; see {@link #fakeInterpreter}. */
    private Path fakeInterpreterIn(Path dir, String name, String windows, String posix) throws IOException {
        Path script = fakeInterpreter(name, windows, posix);
        return Files.move(script, Files.createDirectories(dir).resolve(script.getFileName()));
    }

    @Test
    void pathEntriesThatNameTheWorkingDirectoryAreLeftOut() {
        Path absolute = tempDir.toAbsolutePath();
        String path = String.join(java.io.File.pathSeparator, "", ".", "relative" + java.io.File.separator + "bin",
                absolute.toString());

        assertThat(PythonShaclValidator.searchDirs(path)).containsExactly(absolute);
    }

    @Test
    void aBareCandidateIsLookedUpInTheSearchPathByCimPal() throws Exception {
        // Windows' CreateProcess would search the JVM's working directory before PATH.
        Path real = Files.writeString(tempDir.resolve("real-python"), "");
        Path bin = tempDir.resolve("bin");
        fakeInterpreterIn(bin, "cimpal-fake-python", "echo " + real + "\n", "echo '" + real + "'\n");

        PythonShaclValidator.Executable executable = PythonShaclValidator.resolveExecutable(ValidationEngine.PYSHACL,
                null, null, List.of(List.of("cimpal-fake-python")), Files.createDirectories(tempDir.resolve("p")),
                List.of(bin));

        assertThat(executable.path()).isEqualTo(real);
    }

    @Test
    void aPathCandidateThatHangsIsSkippedAfterTheProbeTimeout() throws Exception {
        Path hanging = fakeInterpreter("hanging-python", "ping -n 61 127.0.0.1 > nul\n", "sleep 60\n");

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), () ->
                assertThatThrownBy(() -> PythonShaclValidator.resolveExecutable(ValidationEngine.PYSHACL, null, null,
                        List.of(List.of(hanging.toString())), tempDir, List.of()))
                        .isInstanceOf(IOException.class).hasMessageContaining("No Python interpreter"));
    }

    @Test
    void anInterpreterThatStopsReadingReportsItsStderrNotTheBrokenPipe() throws Exception {
        Path fake = fakeInterpreter("import-failing-python", """
                echo ModuleNotFoundError: No module named 'pyshacl' 1>&2
                exit /b 1
                """, """
                echo "ModuleNotFoundError: No module named 'pyshacl'" >&2
                exit 1
                """);
        System.setProperty(PythonExecutable.PROPERTY, fake.toString());
        Model large = ModelFactory.createDefaultModel();
        for (int i = 0; i < 200_000; i++) {
            large.add(large.createResource("urn:x:s" + i), large.createProperty("urn:x:p"), "value " + i);
        }

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), large))
                .isInstanceOf(IOException.class).hasMessageContaining("ModuleNotFoundError");
    }

    @Test
    void stderrIsReadInChunksSoTextBeforeAFailureIsKept() {
        // 2 MB without a line break, then "TAIL", then the stream fails.
        java.io.InputStream stream = new java.io.InputStream() {
            private long sent;
            private final byte[] tail = "TAIL".getBytes(StandardCharsets.UTF_8);

            @Override
            public int read() throws IOException {
                long size = 2_000_000;
                if (sent < size) {
                    sent++;
                    return 'x';
                }
                if (sent < size + tail.length) {
                    return tail[(int) (sent++ - size)];
                }
                throw new IOException("pipe closed");
            }
        };
        StringBuilder stderr = new StringBuilder();

        PythonShaclValidator.copy(stream, stderr);

        assertThat(stderr.toString()).endsWith("TAIL");
        assertThat(stderr.length()).isLessThanOrEqualTo(PythonShaclValidator.STDERR_BUFFER_CHARS);
    }

    @Test
    void megabytesOfStderrWithoutALineBreakGiveABoundedMessage() throws Exception {
        String chunk = "A".repeat(1000);
        Path fake = fakeInterpreter("noisy-python", """
                more > nul
                for /L %%i in (1,1,1100) do <nul set /p =CHUNK 1>&2
                exit /b 1
                """.replace("CHUNK", chunk), """
                cat > /dev/null
                head -c 1100000 /dev/zero | tr '\\000' 'A' >&2
                exit 1
                """);
        System.setProperty(PythonExecutable.PROPERTY, fake.toString());

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty()))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(1000));
    }

    @Test
    void aReportFollowedByAFailingExitIsAnErrorNotAPass() throws Exception {
        // Fail closed: "conforms" on stdout counts for nothing when the interpreter exits non-zero.
        Path fake = fakeInterpreter("conforms-then-fails", """
                more > nul
                echo #CIMPAL-CONFORMS=true
                exit /b 1
                """, """
                cat > /dev/null
                echo '#CIMPAL-CONFORMS=true'
                exit 1
                """);
        System.setProperty(PythonExecutable.PROPERTY, fake.toString());

        assertThatThrownBy(() -> PythonShaclValidator.validate(ValidationEngine.PYSHACL, empty(), empty()))
                .isInstanceOf(IOException.class).hasMessageContaining("exited with code 1");
    }
}
