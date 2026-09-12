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

/**
 * Runs an optional Python SHACL implementation without changing CimPal's mapping or report flow.
 * The resolved shapes and datatype-enhanced data graphs cross the process boundary as Turtle on
 * pipes; no XML, ZIP entry, RDF graph, or validation report is staged on local disk.
 */
final class PythonShaclValidator {
    private static final String WORKER_RESOURCE = "/python/cimpal_shacl_worker.py";
    private static final byte[] END_SHAPES = "#CIMPAL-END-SHAPES\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_DATA = "#CIMPAL-END-DATA\n".getBytes(StandardCharsets.UTF_8);

    private PythonShaclValidator() { }

    record Outcome(List<SHACLValidationResult> results, boolean conforms) { }

    static Outcome validate(ValidationEngine engine, Model shapesModel, Model dataModel)
            throws IOException, InterruptedException {
        if (engine == ValidationEngine.APACHE_JENA) {
            throw new IllegalArgumentException("Apache Jena is not a Python validation engine");
        }

        List<String> command = new ArrayList<>(pythonCommand(engine));
        command.add("-u");
        command.add(extractWorker().toString());
        Process process = new ProcessBuilder(command).start();

        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().start(() -> copy(process.getErrorStream(), stderr));
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((engine.name() + "\n").getBytes(StandardCharsets.UTF_8));
            RDFDataMgr.write(stdin, shapesModel, RDFFormat.TURTLE_BLOCKS);
            stdin.write(END_SHAPES);
            RDFDataMgr.write(stdin, dataModel, RDFFormat.TURTLE_BLOCKS);
            stdin.write(END_DATA);
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
                try {
                    stderrReader.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                throw new IOException("Python " + engine.displayName()
                        + " returned no report header (exit=" + exit + "). stderr: " + stderr
                        + (firstLine == null ? "" : " stdout: " + firstLine));
            }
            conforms = Boolean.parseBoolean(firstLine.substring("#CIMPAL-CONFORMS=".length()));
            // The remaining Turtle is a standard SHACL ValidationReport.  The first line was a
            // protocol comment, so it cannot be part of RDF and is deliberately consumed above.
            RDFDataMgr.read(report, new ReaderInputStream(stdout), Lang.TURTLE);
        }

        int exit = process.waitFor();
        try {
            stderrReader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (exit != 0) {
            throw new IOException("Python " + engine.displayName() + " exited with code " + exit
                    + ": " + stderr);
        }
        return new Outcome(ShaclTools.extractSHACLValidationResults(report, shapesModel), conforms);
    }

    private static List<String> pythonCommand(ValidationEngine engine) throws IOException, InterruptedException {
        String configured = System.getProperty("cimpal.python.executable", "").trim();
        if (!configured.isEmpty()) {
            return List.of(configured);
        }
        String requiredModule = engine == ValidationEngine.RUST_SHACL ? "shacl" : "pyshacl";
        for (List<String> candidate : List.of(List.of("python"), List.of("py", "-3"))) {
            Process p;
            try {
                List<String> check = new ArrayList<>(candidate);
                check.add("-c");
                check.add("import " + requiredModule);
                p = new ProcessBuilder(check).start();
            } catch (IOException ignored) {
                continue;
            }
            if (p.waitFor() == 0) {
                return candidate;
            }
        }
        throw new IOException("No Python interpreter with the required '" + requiredModule
                + "' package was found for " + engine.displayName() + ". Install it with: "
                + "py -3 -m pip install \"pyshacl[oxigraph]\" shacl, or set "
                + "-Dcimpal.python.executable=C:\\path\\to\\python.exe");
    }

    private static Path extractWorker() throws IOException {
        try (InputStream in = PythonShaclValidator.class.getResourceAsStream(WORKER_RESOURCE)) {
            if (in == null) throw new IOException("Bundled Python SHACL worker is missing");
            Path worker = Files.createTempFile("cimpal-shacl-worker-", ".py");
            Files.copy(in, worker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            worker.toFile().deleteOnExit();
            return worker;
        }
    }

    private static void copy(InputStream stream, StringBuilder destination) {
        try (stream; BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) destination.append(line).append(System.lineSeparator());
        } catch (IOException ignored) {
            // The owning process reports a non-zero exit and includes what it collected.
        }
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
