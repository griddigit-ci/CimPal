/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token lifecycle of the {@code serve} command itself (SEC-1, gap G1). */
class ServeCommandTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream origOut;
    private PrintStream origErr;

    @BeforeEach
    void captureOutput() {
        origOut = System.out;
        origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    private record Running(ServeServer server, CompletableFuture<Integer> exit) {
    }

    private Running startInBackground(Map<String, String> env, String... args) throws Exception {
        ServeCommand command = new ServeCommand();
        command.environment = env;
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        String[] all = withWorkRoot(args);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(all));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);
        assertThat(server).as("server started; output: %s", output()).isNotNull();
        return new Running(server, exit);
    }

    private int runToExit(Map<String, String> env, String... args) {
        ServeCommand command = new ServeCommand();
        command.environment = env;
        command.onTerm = onTerm -> false;
        return new CommandLine(command).execute(withWorkRoot(args));
    }

    /** Every test server gets its work area under @TempDir, never in the working directory. */
    private String[] withWorkRoot(String... args) {
        if (java.util.Arrays.asList(args).contains("--work-root")) {
            return args;
        }
        String[] all = java.util.Arrays.copyOf(args, args.length + 2);
        all[args.length] = "--work-root";
        all[args.length + 1] = tempDir.resolve("work").toString();
        return all;
    }

    private static HttpResponse<String> shutdown(ServeServer server, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/shutdown"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * TEST-4 security review: serve created a missing --root (as the parent of its default work
     * folder) and wrote its token file before any root was checked, so a mistyped root or an
     * unmounted volume started a server on an empty folder. mcp and run refuse it.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plain", "mixed-separators", "traversal", "unc"})
    void aRootThatDoesNotExistIsBadInputAndNothingIsCreated(String spelling) throws Exception {
        String missing = switch (spelling) {
            case "plain" -> tempDir.resolve("no-such-root").toString();
            case "mixed-separators" -> tempDir.toString().replace('\\', '/') + "/no-such\\root";
            case "traversal" -> tempDir.resolve("x").resolve("..").resolve("no-such-root").toString();
            default -> {
                // A UNC root is only probed on Windows (through localhost, never another host).
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        System.getProperty("os.name", "").toLowerCase().startsWith("windows"), "Windows only");
                yield "\\\\localhost\\cimpal-no-such-share\\data";
            }
        };
        Path tokenFile = tempDir.resolve("serve.token");
        ServeCommand command = new ServeCommand();
        command.environment = Map.of();
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        // No --work-root: the default work folder lies in the first root.
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(
                "--host", "127.0.0.1", "--port", "0", "--token-file", tokenFile.toString(), "--root", missing));
        CompletableFuture.anyOf(started, exit).get(30, TimeUnit.SECONDS);
        if (started.isDone()) {
            shutdown(started.get(), Files.readString(tokenFile));
            exit.get(10, TimeUnit.SECONDS);
        }

        assertThat(started).as("serve must not start; output: %s", output()).isNotDone();
        assertThat(exit.get()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("Root does not exist");
        try (java.util.stream.Stream<Path> created = Files.list(tempDir)) {
            assertThat(created.toList()).as("nothing created, no token file").isEmpty();
        }
    }

    @Test
    void writesTheTokenFileNeverPrintsTheTokenAndRemovesTheFileOnShutdown() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tokenFile.toString());
        String token = Files.readString(tokenFile);

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(tokenFile).doesNotExist();
        assertThat(output()).contains(tokenFile.toAbsolutePath().toString()).doesNotContain(token);
    }

    @Test
    void environmentTokenIsUsedAndNoFileIsWritten() throws Exception {
        String token = "env-token-0123456789abcdef0123456789";
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--token-file", tokenFile.toString());

        assertThat(tokenFile).doesNotExist();
        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(output()).doesNotContain(token);
    }

    @Test
    void whileServeRunsAPathCheckOnAThreadWithoutAPolicyIsRefused() throws Exception {
        // DEP-8: e.g. a pool thread that didn't inherit a command's policy must not be unrestricted.
        String token = "env-token-0123456789abcdef0123456789";
        Path file = Files.writeString(tempDir.resolve("data.xml"), "<x/>");
        assertThat(eu.griddigit.cimpal.core.utils.PathPolicy.checkReadIfActive(file)).isEqualTo(file);

        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--root", tempDir.toString());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> eu.griddigit.cimpal.core.utils.PathPolicy.checkReadIfActive(file))
                    .isInstanceOf(eu.griddigit.cimpal.core.utils.PathNotAllowedException.class);
        } finally {
            assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
            assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        }
        assertThat(eu.griddigit.cimpal.core.utils.PathPolicy.checkReadIfActive(file)).as("released when serve stops")
                .isEqualTo(file);
    }

    @Test
    void shortEnvironmentTokenIsRefused() {
        int exit = runToExit(Map.of(ServeSecurity.TOKEN_ENV, "short"), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tempDir.resolve("serve.token").toString());

        assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains(ServeSecurity.TOKEN_ENV).doesNotContain("short\n");
    }

    @Test
    void bindFailureIsExit2AndRemovesTheTokenFile() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        try (ServerSocket occupied = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int exit = runToExit(Map.of(), "--host", "127.0.0.1", "--port", Integer.toString(occupied.getLocalPort()),
                    "--token-file", tokenFile.toString());

            assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        }
        assertThat(tokenFile).doesNotExist();
    }

    @Test
    void nonLoopbackHostWithoutAllowRemoteIsExit2AndRemovesTheTokenFile() {
        Path tokenFile = tempDir.resolve("serve.token");
        int exit = runToExit(Map.of(), "--host", "0.0.0.0", "--port", "0", "--token-file", tokenFile.toString());

        assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("--allow-remote");
        assertThat(tokenFile).doesNotExist();
    }

    @Test
    void tokenFileOverwrittenByAnotherInstanceIsLeftInPlace() throws Exception {
        Path tokenFile = tempDir.resolve("serve.token");
        Running running = startInBackground(Map.of(), "--host", "127.0.0.1", "--port", "0",
                "--token-file", tokenFile.toString());
        String token = Files.readString(tokenFile);
        Files.writeString(tokenFile, "token-of-a-second-instance");

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
        assertThat(Files.readString(tokenFile)).isEqualTo("token-of-a-second-instance");
    }

    private static HttpResponse<String> v1(ServeServer server, String token, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRealJobRunsThroughTheJobApiAndPathsAreCheckedAtSubmit() throws Exception {
        // DEP-5 end to end: the real serve command, path policy and sparql command.
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path model = Files.writeString(root.resolve("m.ttl"), "<urn:x:a> <urn:x:p> \"x\" .\n");
        Path outside = Files.writeString(tempDir.resolve("outside.ttl"), "<urn:x:a> <urn:x:p> \"y\" .\n");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--root", root.toString());
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        String query = "SELECT ?o WHERE { ?s ?p ?o }";

        HttpResponse<String> refused = v1(running.server(), token, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"config\":{\"models\":[" + json.writeValueAsString(outside.toString())
                        + "],\"query\":\"" + query + "\"}}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
        assertThat(running.server().jobsForTest().size()).as("a refused job is never queued").isZero();

        HttpResponse<String> submitted = v1(running.server(), token, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"label\":\"e2e\",\"config\":{\"models\":["
                        + json.writeValueAsString(model.toString()) + "],\"query\":\"" + query + "\"}}");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = json.readTree(submitted.body()).path("jobId").asString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String status;
        do {
            Thread.sleep(50);
            status = json.readTree(v1(running.server(), token, "GET", "/v1/jobs/" + id, null).body())
                    .path("status").asString();
        } while (!status.equals("succeeded") && !status.equals("failed") && System.nanoTime() < deadline);

        assertThat(status).as(output()).isEqualTo("succeeded");
        tools.jackson.databind.JsonNode result = json.readTree(v1(running.server(), token, "GET",
                "/v1/jobs/" + id + "/result", null).body());
        assertThat(result.path("rows")).hasSize(1);
        tools.jackson.databind.JsonNode log = json.readTree(v1(running.server(), token, "GET",
                "/v1/jobs/" + id + "/log", null).body());
        assertThat(log.path("lines").toString()).contains("Query returned 1 row");

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
    }

    // ---- service deployment (DEP-6) ---------------------------------------------------------

    private static final String FILE_TOKEN_A = "file-token-a-0123456789abcdef0123456789";
    private static final String FILE_TOKEN_B = "file-token-b-0123456789abcdef0123456789";

    private static int status(ServeServer server, String token, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void aTokenFileFromTheEnvironmentIsReadNeverWrittenOrDeleted() throws Exception {
        Path secret = Files.writeString(tempDir.resolve("secret"), FILE_TOKEN_A + "\n" + FILE_TOKEN_B + "\n");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_FILE_ENV, secret.toString()),
                "--host", "127.0.0.1", "--port", "0", "--metrics");

        assertThat(status(running.server(), FILE_TOKEN_A, "/commands")).isEqualTo(200);
        assertThat(status(running.server(), FILE_TOKEN_B, "/metrics")).isEqualTo(200);
        assertThat(status(running.server(), "x" + FILE_TOKEN_A, "/commands")).isEqualTo(401);
        assertThat(shutdown(running.server(), FILE_TOKEN_A).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);

        assertThat(Files.readString(secret)).isEqualTo(FILE_TOKEN_A + "\n" + FILE_TOKEN_B + "\n");
        assertThat(output()).contains("read from").doesNotContain(FILE_TOKEN_A).doesNotContain(FILE_TOKEN_B);
    }

    @Test
    void conflictingTokenSourcesRefuseToStart() throws Exception {
        Path secret = Files.writeString(tempDir.resolve("secret"), FILE_TOKEN_A + "\n");
        String envToken = "env-token-0123456789abcdef0123456789";

        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, envToken), "--port", "0",
                "--token-from-file", secret.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_FILE_ENV, secret.toString()), "--port", "0",
                "--token-from-file", secret.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(), "--port", "0", "--token-from-file", secret.toString(),
                "--token-file", tempDir.resolve("serve.token").toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(), "--port", "0", "--token-from-file",
                tempDir.resolve("missing").toString())).isEqualTo(ExitCode.INVALID_INPUT);

        assertThat(output()).contains("not both").contains("can't be combined").doesNotContain(envToken)
                .doesNotContain(FILE_TOKEN_A);
        assertThat(secret).exists();
        assertThat(tempDir.resolve("serve.token")).doesNotExist();
    }

    @Test
    void badDeploymentOptionsAreExit2() {
        String token = "env-token-0123456789abcdef0123456789";
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--allowed-host", "https://cimpal.example.com")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--log-format", "xml")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0",
                "--shutdown-grace", "PT0S")).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("--allowed-host").contains("--log-format").contains("--shutdown-grace");
    }

    @Test
    void jsonLogFormatWritesOnlyJsonLinesOnStderrAndNothingOnStdout() throws Exception {
        String token = "env-token-0123456789abcdef0123456789";
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--log-format", "json", "--allowed-host", "cimpal.example.com");
        assertThat(status(running.server(), "wrong-" + token, "/commands")).isEqualTo(401);
        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);

        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        String stderr = err.toString(StandardCharsets.UTF_8);
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        java.util.List<String> events = new java.util.ArrayList<>();
        for (String line : stderr.split("\\R")) {
            events.add(json.readTree(line).path("event").asString());
        }
        assertThat(events).contains("server.started", "request.rejected", "server.stopping", "server.stopped");
        assertThat(stderr).contains("cimpal.example.com").doesNotContain(token);
    }

    // ---- file exchange (DEP-7) ---------------------------------------------------------------

    @Test
    void aRealJobRunsOnAnUploadAndItsOutputIsDownloaded() throws Exception {
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path work = tempDir.resolve("work");
        Running running = startInBackground(Map.of(ServeSecurity.TOKEN_ENV, token), "--host", "127.0.0.1",
                "--port", "0", "--root", root.toString(), "--work-root", work.toString());
        String base = "http://127.0.0.1:" + running.server().port();
        tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(zip)) {
            out.putNextEntry(new java.util.zip.ZipEntry("models/m.ttl"));
            out.write("<http://example.org/a> <http://example.org/p> \"x\" .\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        HttpResponse<String> uploaded = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/uploads"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(zip.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        String uploadId = json.readTree(uploaded.body()).path("uploadId").asString();

        HttpResponse<String> submitted = v1(running.server(), token, "POST", "/v1/jobs",
                "{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\",\"config\":{\"models\":[\"models/m.ttl\"],"
                        + "\"query\":\"SELECT ?o WHERE { ?s ?p ?o }\",\"summaryFile\":\"result.json\"}}");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String id = json.readTree(submitted.body()).path("jobId").asString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String status;
        do {
            Thread.sleep(50);
            status = json.readTree(v1(running.server(), token, "GET", "/v1/jobs/" + id, null).body()).path("status").asString();
        } while (!status.equals("succeeded") && !status.equals("failed") && System.nanoTime() < deadline);
        assertThat(status).as(output()).isEqualTo("succeeded");

        HttpResponse<String> files = v1(running.server(), token, "GET", "/v1/jobs/" + id + "/files", null);
        assertThat(files.body()).contains("\"path\":\"result.json\"");
        HttpResponse<String> result = v1(running.server(), token, "GET", "/v1/jobs/" + id + "/files/result.json", null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("rows")).hasSize(1);
        // The work area is outside every shared path: a shared-mode job can't reach the upload.
        HttpResponse<String> sneaky = v1(running.server(), token, "POST", "/v1/jobs", "{\"command\":\"sparql\",\"config\":"
                + "{\"models\":[" + json.writeValueAsString(work.resolve("uploads/" + uploadId + "/models/m.ttl").toString())
                + "],\"query\":\"SELECT * WHERE {?s ?p ?o}\"}}");
        assertThat(sneaky.statusCode()).as(sneaky.body()).isIn(403);

        assertThat(shutdown(running.server(), token).statusCode()).isEqualTo(200);
        assertThat(running.exit().get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
    }

    @Test
    void aWorkRootThatIsOrContainsAnAllowedFolderIsRefused() throws Exception {
        // serve --root D:\data --work-root D:\data would open every upload to shared requests.
        String token = "job-token-0123456789abcdef0123456789";
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Path library = Files.createDirectories(tempDir.resolve("lib/shapes"));

        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0", "--root", data.toString(),
                "--work-root", data.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0", "--root", data.toString(),
                "--read-root", library.toString(), "--work-root", tempDir.resolve("lib").toString()))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0", "--root", data.toString(),
                "--work-root", tempDir.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("would open other callers' uploads");
    }

    @Test
    void aForeignFolderIsNeverEmptiedAsAWorkArea() throws Exception {
        // A mistyped --work-root pointing at a folder of the user's.
        String token = "job-token-0123456789abcdef0123456789";
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Path documents = Files.createDirectories(tempDir.resolve("documents/jobs"));
        Files.writeString(documents.resolve("important.txt"), "keep");

        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0", "--root", data.toString(),
                "--work-root", tempDir.resolve("documents").toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(documents.resolve("important.txt")).hasContent("keep");
        assertThat(output()).contains("not a work area of serve");
    }

    @Test
    void aDefaultWorkFolderThatCannotBeCreatedTurnsFileExchangeOffInsteadOfStopping() throws Exception {
        // A container with a read-only /data (shared-volume mode, as docs/cli/docker.md runs it):
        // serve must still start. A file in the way stands in for the read-only folder.
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("readonly-data"));
        Files.writeString(root.resolve(".cimpal-work"), "in the way");
        ServeCommand command = new ServeCommand();
        command.environment = Map.of(ServeSecurity.TOKEN_ENV, token);
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(
                "--host", "127.0.0.1", "--port", "0", "--root", root.toString()));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);

        assertThat(server).as(output()).isNotNull();
        assertThat(server.workAreaForTest()).isNull();
        assertThat(output()).contains("File exchange is off");
        assertThat(shutdown(server, token).statusCode()).isEqualTo(200);
        assertThat(exit.get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);

        // An explicit --work-root that can't be used still stops serve.
        assertThat(runToExit(Map.of(ServeSecurity.TOKEN_ENV, token), "--port", "0", "--root", root.toString(),
                "--work-root", root.resolve(".cimpal-work").toString())).isEqualTo(ExitCode.INVALID_INPUT);
    }

    @Test
    void fileExchangeCanBeTurnedOff() throws Exception {
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("root"));
        ServeCommand command = new ServeCommand();
        command.environment = Map.of(ServeSecurity.TOKEN_ENV, token);
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(
                "--host", "127.0.0.1", "--port", "0", "--root", root.toString(), "--no-file-exchange"));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);
        assertThat(server).as(output()).isNotNull();

        assertThat(server.workAreaForTest()).isNull();
        assertThat(root.resolve(".cimpal-work")).doesNotExist();
        assertThat(v1(server, token, "GET", "/v1/uploads/" + java.util.UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(shutdown(server, token).statusCode()).isEqualTo(200);
        assertThat(exit.get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
    }

    @Test
    void theDefaultWorkAreaIsInTheFirstRootAndOutsideEverySharedPath() throws Exception {
        String token = "job-token-0123456789abcdef0123456789";
        Path root = Files.createDirectories(tempDir.resolve("root"));
        ServeCommand command = new ServeCommand();
        command.environment = Map.of(ServeSecurity.TOKEN_ENV, token);
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(
                "--host", "127.0.0.1", "--port", "0", "--root", root.toString()));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);
        assertThat(server).as(output()).isNotNull();

        assertThat(server.workAreaForTest().root()).isEqualTo(root.resolve(".cimpal-work").toRealPath());
        assertThat(shutdown(server, token).statusCode()).isEqualTo(200);
        assertThat(exit.get(10, TimeUnit.SECONDS)).isEqualTo(ExitCode.OK);
    }

    // ---- --job-store (DEP-11) -----------------------------------------------------------------

    private static final String DB_SECRET = "s3cret-db-password-0123456789";

    @Test
    void theInMemoryStoreIsTheDefaultAndRefusesDatabaseOptions() {
        assertThat(ServeCommand.jobStoreSettings(null, null, null, null, null, 1, false, Map.of())).isNull();
        assertThat(runToExit(Map.of(), "--port", "0", "--job-store-url", "jdbc:postgresql://db/cimpal"))
                .isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("need --job-store jdbc");
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("redis", null, null, null, null, 1, false, Map.of()))
                .hasMessageContaining("memory or jdbc");
    }

    @Test
    void aJdbcStoreNeedsAPostgresUrlWithoutThePassword() {
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", null, null, null, null, 1, false, Map.of()))
                .hasMessageContaining("--job-store-url");
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'x'", null,
                null, null, 1, false, Map.of())).hasMessageContaining("PostgreSQL");
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/c?user=u&password="
                + DB_SECRET, null, null, null, 1, false, Map.of()))
                .hasMessageContaining("must not hold the password").hasMessageNotContaining(DB_SECRET);
    }

    @Test
    void thePasswordComesFromAFileOrTheEnvironmentNeverBoth() throws Exception {
        Path file = Files.writeString(tempDir.resolve("db-password"), DB_SECRET + System.lineSeparator());
        ServeCommand.StoreSettings fromFile = ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/cimpal?ssl=true",
                "cimpal", file, null, 2, false, Map.of());
        assertThat(fromFile.password()).isEqualTo(DB_SECRET);
        assertThat(fromFile.pool()).isEqualTo(6);
        assertThat(fromFile.displayUrl()).isEqualTo("jdbc:postgresql://db/cimpal");
        assertThat(fromFile.toString()).doesNotContain(DB_SECRET);

        ServeCommand.StoreSettings fromEnv = ServeCommand.jobStoreSettings("JDBC", null, null, null, 3, 1, true,
                Map.of(ServeCommand.STORE_URL_ENV, "jdbc:postgresql://db/cimpal", ServeCommand.STORE_USER_ENV, "svc",
                        ServeCommand.STORE_PASSWORD_ENV, DB_SECRET));
        assertThat(fromEnv.user()).isEqualTo("svc");
        assertThat(fromEnv.password()).isEqualTo(DB_SECRET);
        assertThat(fromEnv.pool()).isEqualTo(3);

        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/c", null, file, null, 1,
                false, Map.of(ServeCommand.STORE_PASSWORD_ENV, DB_SECRET))).hasMessageContaining("not both");
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/c", null, null, 0, 1,
                false, Map.of())).hasMessageContaining("--job-store-pool");
    }

    @Test
    void anUnreachableDatabaseStopsTheStartWithoutShowingThePassword() throws Exception {
        Path file = Files.writeString(tempDir.resolve("db-password"), DB_SECRET);
        // Port 1 on loopback: nothing listens, the connection is refused at once.
        int exit = runToExit(Map.of(), "--port", "0", "--job-store", "jdbc",
                "--job-store-url", "jdbc:postgresql://127.0.0.1:1/cimpal?connectTimeout=2",
                "--job-store-user", "cimpal", "--job-store-password-file", file.toString());

        assertThat(exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("Cannot open the job store").doesNotContain(DB_SECRET);
    }

    @Test
    void urlParametersThatLoadClassesWriteFilesOrHoldSecretsAreRefused() {
        for (String parameter : new String[] {"sslfactory=org.postgresql.ssl.NonValidatingFactory",
                "socketFactory=x.Y", "sslpassword=" + DB_SECRET, "loggerFile=/tmp/x.log",
                "authenticationPluginClassName=x.Y", "sslhostnameverifier=x.Y"}) {
            assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/c?ssl=true&" + parameter,
                    null, null, null, 1, false, Map.of())).as(parameter).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(DB_SECRET);
        }
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://u:" + DB_SECRET + "@db/c",
                null, null, null, 1, false, Map.of())).hasMessageContaining("user information")
                .hasMessageNotContaining(DB_SECRET);
        assertThat(ServeCommand.jobStoreSettings("jdbc", "jdbc:postgresql://db/c?sslmode=verify-full&ApplicationName=cimpal",
                null, null, null, 1, false, Map.of())).isNotNull();
    }

    /** SEC-3 (G9): a relative interpreter, given by the flag or the system property, stops serve at start. */
    @Test
    void aRelativePythonExecutableIsBadInputAtStart() {
        assertThat(runToExit(Map.of(), "--port", "0", "--python-executable", "python"))
                .as(output()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(output()).contains("absolute path");

        System.setProperty("cimpal.python.executable", "py");
        try {
            assertThat(runToExit(Map.of(), "--port", "0")).as(output()).isEqualTo(ExitCode.INVALID_INPUT);
        } finally {
            System.clearProperty("cimpal.python.executable");
        }
    }

    @Test
    void aDatabaseSetUpInTheEnvironmentButNotChosenIsRefused() {
        assertThatThrownBy(() -> ServeCommand.jobStoreSettings("memory", null, null, null, null, 1, false,
                Map.of(ServeCommand.STORE_URL_ENV, "jdbc:postgresql://db/c"))).hasMessageContaining("CIMPAL_JOB_STORE=jdbc");
    }
}
