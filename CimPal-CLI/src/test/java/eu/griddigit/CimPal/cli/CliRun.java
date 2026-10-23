/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the CLI for a contract test (TEST-4) and captures its exit code, stdout and stderr:
 * in-process through {@link CimPalCli#inProcess()} as {@code run}, {@code serve} and {@code mcp} do,
 * or in a JVM of its own on the test classpath.
 */
public final class CliRun {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CliRun() {
    }

    /** What a run left behind. */
    public record Result(int exitCode, String stdout, String stderr) {

        /**
         * stdout as exactly one JSON document; anything before or after it (a progress line, a
         * second document) fails here.
         */
        public JsonNode json() {
            return JSON.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(stdout);
        }
    }

    /** Runs {@code args} in this JVM, with System.out and System.err captured. */
    public static Result run(String... args) {
        return runWithInput(null, args);
    }

    /** As {@link #run(String...)}, with {@code stdin} (may be null) as System.in. */
    public static synchronized Result runWithInput(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        InputStream origIn = System.in;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            System.setIn(new ByteArrayInputStream((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8)));
            exit = CimPalCli.inProcess().execute(args);
        } finally {
            // mcp points System.out at stderr for its own protection; put every stream back.
            System.setOut(origOut);
            System.setErr(origErr);
            System.setIn(origIn);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Runs {@code args} through {@link CimPalCli#main} in a new JVM on the test classpath, in
     * {@code workDir}, with {@code jvmOptions} and {@code stdin} (may be null).
     */
    public static Result fork(Path workDir, List<String> jvmOptions, String stdin, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmOptions);
        command.addAll(List.of("-cp", testClassPath(), CimPalCli.class.getName()));
        command.addAll(List.of(args));
        return process(workDir, command, stdin);
    }

    /** Runs {@code command} as a process in {@code workDir}; fails if it takes more than three minutes. */
    public static Result process(Path workDir, List<String> command, String stdin) throws Exception {
        Path io = Files.createTempDirectory(workDir, "cli-run-");
        Path stdout = io.resolve("stdout.txt");
        Path stderr = io.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Path input = Files.writeString(io.resolve("stdin.txt"), stdin == null ? "" : stdin);
        builder.redirectInput(input.toFile());
        Process process = builder.start();
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("still running after 3 minutes: " + command);
        }
        return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    /** The java launcher of the JVM running the build. */
    public static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    /** Surefire's full test classpath (java.class.path may be its manifest-only booter jar). */
    private static String testClassPath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }
}
