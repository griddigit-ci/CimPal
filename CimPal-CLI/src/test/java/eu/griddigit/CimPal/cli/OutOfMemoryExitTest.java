/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEP-2 (R2): a run that runs out of memory ends with exit code 3 and one clear line on stderr,
 * never with exit 1 ("violations found") or a hang. The validation runs in a forked JVM with a
 * 16 MB heap against a model that needs far more.
 */
class OutOfMemoryExitTest {

    @TempDir
    Path tempDir;

    @Test
    void aValidationThatRunsOutOfMemoryExitsWithThree() throws Exception {
        Path models = Files.createDirectories(tempDir.resolve("models"));
        writeLargeModel(models.resolve("data.xml"), 100_000);
        Path constraints = Files.createDirectories(tempDir.resolve("constraints"));
        Files.writeString(constraints.resolve("shapes.ttl"), TestModels.THING_SHAPES);
        Path mapping = tempDir.resolve("mapping.csv");
        Files.writeString(mapping, "xml_inputs,ttl,notes\ndata.xml,shapes.ttl,large\n");

        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-Xmx16m", "-cp", testClassPath(),
                CimPalCli.class.getName(), "validate", "--workflow", "mapping",
                "--mapping-csv", mapping.toString(), "--models", models.toString(),
                "--constraints-root", constraints.toString(), "--output", tempDir.resolve("out").toString(),
                "--xml-base", TestModels.XML_BASE, "--workers", "1", "--format", "json"));
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        boolean finished = process.waitFor(3, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }

        String err = Files.readString(stderr);
        assertThat(finished).as("the JVM ends instead of hanging").isTrue();
        assertThat(process.exitValue()).as("exit code; stderr:%n%s", err).isEqualTo(3);
        assertThat(err).contains("[ERROR] Out of memory").contains("-Xmx").contains("docs/guide/sizing.md");
        assertThat(Files.readString(stdout)).as("stdout carries no partial JSON").isEmpty();
    }

    /** {@code count} {@code ex:Thing}s with a name each: about 2 triples per thing. */
    private static void writeLargeModel(Path file, int count) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:ex=\"urn:test:\">\n");
            for (int i = 0; i < count; i++) {
                w.write("  <ex:Thing rdf:about=\"#_" + i + "\"><ex:name>thing number " + i + "</ex:name></ex:Thing>\n");
            }
            w.write("</rdf:RDF>\n");
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Surefire's full test classpath (java.class.path may be its manifest-only booter jar). */
    private static String testClassPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }
}
