/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an optional Python SHACL implementation without changing CimPal's mapping or report flow.
 * The resolved shapes and datatype-enhanced data graphs cross the process boundary as Turtle on
 * pipes; no XML, ZIP entry, RDF graph, or validation report is staged on local disk.
 *
 * <p>The interpreter is the configured one ({@link PythonExecutable}) or, without one, the
 * absolute {@code sys.executable} that {@code python} / {@code py -3} on {@code PATH} reports, so
 * the program that is checked is the program that runs (SEC-3, gap G9). The bundled worker script
 * is written to a private temporary folder that is removed after the run.
 */
final class PythonShaclValidator {
    private static final String WORKER_RESOURCE = "/python/cimpal_shacl_worker.py";
    private static final byte[] END_SHAPES = "#CIMPAL-END-SHAPES\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_DATA = "#CIMPAL-END-DATA\n".getBytes(StandardCharsets.UTF_8);

    /** Interpreters tried on {@code PATH} when none is configured. */
    private static final List<List<String>> PATH_CANDIDATES = List.of(List.of("python"), List.of("py", "-3"));

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    /** How long a PATH candidate may take to answer the probe before it is killed and skipped. */
    static final long PROBE_TIMEOUT_SECONDS = 10;

    /** Most stderr kept in memory while the worker runs; the oldest text is dropped first. */
    static final int STDERR_BUFFER_CHARS = 64 * 1024;

    /** Most stderr (its end, where a traceback names the error) quoted in an exception message. */
    static final int STDERR_IN_MESSAGE = 500;

    private PythonShaclValidator() { }

    record Outcome(List<SHACLValidationResult> results, boolean conforms, Model report) { }

    /** The interpreter to run and where it came from, for the log line. */
    record Executable(Path path, String source) { }

    /** Secrets of serve's environment that a Python child must not inherit (DEP-11 review). */
    static final java.util.Set<String> SECRET_ENV = java.util.Set.of("CIMPAL_API_TOKEN", "CIMPAL_JOB_STORE_PASSWORD",
            "GITHUB_TOKEN");

    /** {@code builder} with {@link #SECRET_ENV} removed from the child's environment. */
    static ProcessBuilder withoutSecrets(ProcessBuilder builder) {
        builder.environment().keySet().removeIf(SECRET_ENV::contains);
        return builder;
    }

    static Outcome validate(ValidationEngine engine, Model shapesModel, Model dataModel)
            throws IOException, InterruptedException {
        if (engine == ValidationEngine.APACHE_JENA) {
            throw new IllegalArgumentException("Apache Jena is not a Python validation engine");
        }
        // The Jena SERVICE switches don't reach the Python process (SEC-2, G3).
        SparqlServicePolicy.requireNoServiceInShapes(shapesModel);

        // Private folder for the worker script; Python processes also start in it, so a module
        // file in CimPal's working directory can't shadow the engine's package.
        Path workerDir = Files.createTempDirectory("cimpal-shacl-");
        Path worker = workerDir.resolve("cimpal_shacl_worker.py");
        try {
            Executable python = resolveExecutable(engine, System.getProperty(PythonExecutable.PROPERTY),
                    System.getenv(PythonExecutable.ENV), PATH_CANDIDATES, workerDir, searchDirs(System.getenv("PATH")));
            System.err.println("[ENGINE] " + engine.displayName() + " runs "
                    + LogSanitizer.forLog(python.path().toString()) + " (" + python.source() + ")");
            writeWorker(worker);
            Process process = withoutSecrets(new ProcessBuilder(List.of(python.path().toString(), "-u", worker.toString())))
                    .directory(workerDir.toFile()).start();
            try {
                return run(engine, process, shapesModel, dataModel);
            } finally {
                stop(process);
            }
        } finally {
            deleteQuietly(worker);
            deleteQuietly(workerDir);
        }
    }

    /** Ends {@code process} if it still runs, without letting an interrupt skip the clean-up. */
    private static void stop(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("[WARN] Could not delete " + LogSanitizer.forLog(path.toString()) + ": "
                    + LogSanitizer.forLog(e.getMessage()));
        }
    }

    private static Outcome run(ValidationEngine engine, Process process, Model shapesModel, Model dataModel)
            throws IOException, InterruptedException {
        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), stderr));
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((engine.name() + "\n").getBytes(StandardCharsets.UTF_8));
            RDFDataMgr.write(stdin, shapesModel, RDFFormat.TURTLE_BLOCKS);
            stdin.write(END_SHAPES);
            RDFDataMgr.write(stdin, dataModel, RDFFormat.TURTLE_BLOCKS);
            stdin.write(END_DATA);
        } catch (IOException | RuntimeException e) {
            if (e instanceof RuntimeException && !(e.getCause() instanceof IOException)) {
                throw (RuntimeException) e; // not a pipe failure (Jena wraps those in a RuntimeIOException)
            }
            // Python stopped reading, e.g. an import failed: its stderr says why, the broken pipe doesn't.
            int exit = process.waitFor(10, TimeUnit.SECONDS) ? process.exitValue() : -1;
            stop(process);
            join(stderrReader);
            throw new IOException("Python " + engine.displayName() + " stopped reading its input (exit=" + exit
                    + "). stderr: " + stderrForMessage(stderr), e);
        }

        Model report = ModelFactory.createDefaultModel();
        boolean conforms;
        try (BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String firstLine = stdout.readLine();
            if (firstLine == null || !firstLine.startsWith("#CIMPAL-CONFORMS=")) {
                // A missing interpreter module used to be reported only as a missing protocol
                // line, which concealed the useful Python traceback on stderr. The process has
                // closed stdout at this point, so waiting here cannot block a running report.
                int exit = process.waitFor();
                join(stderrReader);
                throw new IOException("Python " + engine.displayName()
                        + " returned no report header (exit=" + exit + "). stderr: " + stderrForMessage(stderr)
                        + (firstLine == null ? "" : " stdout: " + LogSanitizer.forLog(firstLine)));
            }
            conforms = Boolean.parseBoolean(firstLine.substring("#CIMPAL-CONFORMS=".length()));
            // The remaining Turtle is a standard SHACL ValidationReport.  The first line was a
            // protocol comment, so it cannot be part of RDF and is deliberately consumed above.
            RDFDataMgr.read(report, new ReaderInputStream(stdout), Lang.TURTLE);
        }

        int exit = process.waitFor();
        join(stderrReader);
        if (exit != 0) {
            throw new IOException("Python " + engine.displayName() + " exited with code " + exit
                    + ": " + stderrForMessage(stderr));
        }
        return new Outcome(ShaclTools.extractSHACLValidationResults(report, shapesModel), conforms, report);
    }

    private static void join(Thread reader) throws InterruptedException {
        try {
            reader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * The interpreter to run: {@code property}, else {@code env} (each must be an absolute path
     * of an existing file), else the absolute {@code sys.executable} reported by the first of
     * {@code candidates} that can import the engine's module, probed with {@code probeDir} as
     * working directory. A candidate's bare program name is looked up in {@code searchPath} here,
     * not by the operating system (Windows would search CimPal's working directory first).
     */
    static Executable resolveExecutable(ValidationEngine engine, String property, String env,
                                        List<List<String>> candidates, Path probeDir, List<Path> searchPath)
            throws IOException, InterruptedException {
        try {
            if (property != null && !property.isBlank()) {
                return new Executable(PythonExecutable.check(property, "-D" + PythonExecutable.PROPERTY),
                        "configured by -D" + PythonExecutable.PROPERTY);
            }
            if (env != null && !env.isBlank()) {
                return new Executable(PythonExecutable.check(env, PythonExecutable.ENV),
                        "configured by " + PythonExecutable.ENV);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        String requiredModule = engine == ValidationEngine.RUST_SHACL ? "shacl" : "pyshacl";
        for (List<String> candidate : candidates) {
            Path program = findProgram(candidate.getFirst(), searchPath);
            if (program == null) {
                continue;
            }
            List<String> command = new ArrayList<>(candidate);
            command.set(0, program.toString());
            Path reported = probe(command, requiredModule, probeDir);
            if (reported != null) {
                return new Executable(reported, "found on PATH via '" + String.join(" ", candidate) + "'");
            }
        }
        throw new IOException("No Python interpreter with the required '" + requiredModule
                + "' package was found for " + engine.displayName() + ". Install it with: "
                + "py -3 -m pip install \"pyshacl[oxigraph]\" shacl, or name the interpreter with "
                + "--python-executable, " + PythonExecutable.ENV + " or -D" + PythonExecutable.PROPERTY
                + " (an absolute path, e.g. C:\\path\\to\\python.exe)");
    }

    /**
     * Runs {@code candidate -c "import <module>, sys; print(sys.executable)"} and returns the
     * absolute interpreter path it prints, or null when it can't run, lacks the module, or names
     * no absolute existing file.
     */
    private static Path probe(List<String> candidate, String module, Path directory) throws InterruptedException {
        List<String> check = new ArrayList<>(candidate);
        check.add("-c");
        check.add("import " + module + ", sys; print(sys.executable)");
        Process p;
        try {
            // "-c" puts the working directory first on sys.path: probe from the private folder.
            p = withoutSecrets(new ProcessBuilder(check)).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
            return null;
        }
        // Read on another thread so a candidate that hangs can't block validation.
        java.util.concurrent.CompletableFuture<String> firstLine = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine.complete(out.readLine());
                out.transferTo(java.io.Writer.nullWriter());
            } catch (IOException e) {
                firstLine.complete(null);
            }
        });
        if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            return null;
        }
        String line;
        try {
            line = firstLine.get(1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            line = null;
        }
        if (p.exitValue() != 0 || line == null || line.isBlank()) {
            return null;
        }
        try {
            return PythonExecutable.check(line, "sys.executable");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void writeWorker(Path worker) throws IOException {
        try (InputStream in = PythonShaclValidator.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (in == null) throw new IOException("Bundled Python SHACL worker is missing");
            Files.copy(in, worker);
        }
    }

    /** The end of {@code stderr}, on one line and at most about {@link #STDERR_IN_MESSAGE} chars. */
    static String stderrForMessage(CharSequence stderr) {
        String s;
        synchronized (stderr) {
            s = stderr.toString().strip();
        }
        if (s.length() <= STDERR_IN_MESSAGE) {
            return LogSanitizer.forLog(s);
        }
        int start = s.length() - STDERR_IN_MESSAGE;
        if (Character.isLowSurrogate(s.charAt(start))) {
            start++;
        }
        return "..." + LogSanitizer.forLog(s.substring(start));
    }

    /**
     * Copies {@code stream} into {@code destination}, keeping only its last
     * {@link #STDERR_BUFFER_CHARS} chars. It reads fixed-size chunks, not lines, so a process that
     * writes megabytes without a line break can't grow memory, and text read before a failure is kept.
     */
    static void copy(InputStream stream, StringBuilder destination) {
        try (stream; java.io.Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] chunk = new char[8192];
            int n;
            while ((n = reader.read(chunk)) != -1) {
                synchronized (destination) {
                    destination.append(chunk, 0, n);
                    if (destination.length() > STDERR_BUFFER_CHARS) {
                        destination.delete(0, destination.length() - STDERR_BUFFER_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
            // The owning process reports a non-zero exit and includes what it collected.
        }
    }

    /**
     * The absolute directories of a {@code PATH} value. Empty, {@code .} and other relative
     * entries are left out: they would name CimPal's working directory.
     */
    static List<Path> searchDirs(String pathValue) {
        List<Path> dirs = new ArrayList<>();
        if (pathValue == null) {
            return dirs;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String e = entry.strip();
            if (e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"")) {
                e = e.substring(1, e.length() - 1);
            }
            if (e.isEmpty() || PathPolicy.isUncOrDevice(e)) {
                continue;
            }
            try {
                Path dir = Path.of(e);
                if (dir.isAbsolute()) {
                    dirs.add(dir.normalize());
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // not a usable PATH entry
            }
        }
        return dirs;
    }

    /**
     * {@code program} itself when it is an absolute path of a file, else the first file of that
     * name in {@code searchPath} (on Windows with each {@code PATHEXT} extension), or null.
     */
    static Path findProgram(String program, List<Path> searchPath) {
        Path given = Path.of(program);
        if (given.isAbsolute()) {
            return Files.isRegularFile(given) ? given : null;
        }
        if (given.getNameCount() != 1) {
            return null; // a relative path with folders would again depend on the working directory
        }
        List<String> names = new ArrayList<>();
        if (WINDOWS) {
            String pathExt = System.getenv("PATHEXT");
            for (String ext : (pathExt == null || pathExt.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathExt).split(";")) {
                if (!ext.isBlank()) {
                    names.add(program + ext.strip().toLowerCase(java.util.Locale.ROOT));
                }
            }
        } else {
            names.add(program);
        }
        for (Path dir : searchPath) {
            for (String name : names) {
                Path file = dir.resolve(name);
                if (Files.isRegularFile(file) && (WINDOWS || Files.isExecutable(file))) {
                    return file;
                }
            }
        }
        return null;
    }

    /** Turns the buffered text reader back into a byte stream for Jena's streaming parser. */
    private static final class ReaderInputStream extends InputStream {
        private final BufferedReader reader;
        private byte[] current = new byte[0];
        private int offset;

        private ReaderInputStream(BufferedReader reader) { this.reader = reader; }

        @Override public int read() throws IOException {
            if (offset >= current.length) {
                String line = reader.readLine();
                if (line == null) return -1;
                current = (line + "\n").getBytes(StandardCharsets.UTF_8);
                offset = 0;
            }
            return current[offset++] & 0xff;
        }
    }
}
