/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file exchange of the {@code /v1} API (DEP-7): uploads, jobs that read them, and downloads
 * of their outputs, on a real {@link ServeServer} with the real per-job path policy
 * ({@link RootOptions#jobPolicy}, {@link ServeCommand#checkUploadPaths}) and a stub command.
 *
 * <p>Runs on every {@link TestStores job store} (DEP-11).
 */
@ParameterizedClass
@EnumSource(TestStores.class)
class FileExchangeApiTest {

    /** The job store the server runs on (DEP-11): every test runs on each. */
    @Parameter
    TestStores store;

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The routes this class covers in the OpenAPI document (and "POST /v1/jobs 404"). */
    static final Set<String> ROUTES = Set.of("/v1/uploads", "/v1/uploads/{uploadId}", "/v1/jobs/{jobId}/files",
            "/v1/jobs/{jobId}/files/{path}", "/v1/jobs/{jobId}/archive");
    static final String SUBMIT_UNKNOWN_UPLOAD = "POST /v1/jobs 404";

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FINISHED = new AtomicInteger();

    @TempDir
    Path tempDir;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private final CountDownLatch release = new CountDownLatch(1);
    private ServeServer server;
    private Path work;
    private Path shared;
    private Path library;
    /** The configs the stub command saw, per command run. */
    private final List<JsonNode> seenConfigs = new ArrayList<>();

    @AfterEach
    void stop() {
        FINISHED.incrementAndGet();
        release.countDown();
        if (server != null) {
            server.close();
        }
        if (server != null && server.jobsForTest() instanceof JdbcJobStore jdbc) {
            jdbc.close();
        }
    }

    // ---- set-up ------------------------------------------------------------------------------

    private ServeServer.Config.Builder config() throws Exception {
        work = tempDir.resolve("work");
        shared = Files.createDirectories(tempDir.resolve("shared"));
        library = Files.createDirectories(tempDir.resolve("library"));
        Files.writeString(shared.resolve("private.ttl"), "x");
        Files.writeString(library.resolve("shapes.ttl"), "# shapes");
        return ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN).maxBodyBytes(4096)
                .queueSize(4).requestTimeout(Duration.ofSeconds(30)).workRoot(work);
    }

    /** A stub "command" that writes two outputs into the job's out/ (and blocks when asked). */
    private ServeServer.CommandRunner writingRunner(boolean block) {
        return new ServeServer.CommandRunner() {
            @Override
            public ServeServer.CommandResult run(String command, Path configFile) throws Exception {
                synchronized (seenConfigs) {
                    seenConfigs.add(JSON.readTree(configFile.toFile()));
                }
                return new ServeServer.CommandResult(0, "{}");
            }

            @Override
            public ServeServer.CommandResult run(String command, Path configFile, WorkArea.JobWorkspace ws)
                    throws Exception {
                JsonNode config = JSON.readTree(configFile.toFile());
                synchronized (seenConfigs) {
                    seenConfigs.add(config);
                }
                if (block) {
                    release.await(30, TimeUnit.SECONDS);
                }
                Path out = Path.of(config.path("output").asString(ws.outDir().resolve("rows.csv").toString()));
                Files.createDirectories(out.getParent());
                Files.writeString(out, "o\nx\n");
                Files.createDirectories(ws.outDir().resolve("reports"));
                Files.writeString(ws.outDir().resolve("reports/Résumé ü.xlsx"), "xlsx-bytes");
                return new ServeServer.CommandResult(0, "{\"rows\":1}");
            }
        };
    }

    /** The real checks of serve: shared mode as given, upload mode with the per-job policy. */
    private ServeServer.RequestCheck realCheck() {
        RootOptions roots = new RootOptions();
        roots.roots.add(shared);
        roots.readRoots.add(library);
        PathPolicy sharedPolicy = roots.policy(List.of(), Path.of(work.toString()));
        return new ServeServer.RequestCheck() {
            @Override
            public byte[] check(String command, byte[] body) throws ServeServer.Rejected {
                return ServeCommand.checkPaths(sharedPolicy, shared, command, body);
            }

            @Override
            public byte[] check(String command, byte[] body, WorkArea.JobWorkspace ws) throws ServeServer.Rejected {
                return ServeCommand.checkUploadPaths(roots.jobPolicy(ws.uploadDir(), ws.outDir(), work), ws, command, body);
            }
        };
    }

    private void start(ServeServer.Config.Builder config, ServeServer.CommandRunner runner) throws Exception {
        Files.createDirectories(work);
        server = ServeServer.start(store.apply(config, clock).build(), runner, realCheck(), () -> { }, clock);
    }

    // ---- HTTP helpers ------------------------------------------------------------------------

    private HttpResponse<byte[]> call(String method, String template, String path, HttpRequest.BodyPublisher body,
                                      String contentType, boolean token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(20));
        if (token) {
            request.header("Authorization", "Bearer " + TOKEN);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        HttpResponse<byte[]> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        SEEN.add(method + " " + template + " " + response.statusCode());
        return response;
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static JsonNode json(HttpResponse<byte[]> response) {
        return JSON.readTree(response.body());
    }

    private HttpResponse<byte[]> upload(byte[] zip) throws Exception {
        return call("POST", "/v1/uploads", "/v1/uploads", HttpRequest.BodyPublishers.ofByteArray(zip),
                "application/zip", true);
    }

    private String uploadId(byte[] zip) throws Exception {
        HttpResponse<byte[]> response = upload(zip);
        assertThat(response.statusCode()).as(text(response)).isEqualTo(201);
        return json(response).path("uploadId").asString();
    }

    private HttpResponse<byte[]> submit(String body) throws Exception {
        return call("POST", "/v1/jobs", "/v1/jobs", HttpRequest.BodyPublishers.ofString(body), "application/json", true);
    }

    private HttpResponse<byte[]> jobGet(String id, String sub, String template) throws Exception {
        return call("GET", "/v1/jobs/{jobId}" + template, "/v1/jobs/" + id + sub, null, null, true);
    }

    private static byte[] zip(String... namesAndContents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Incompressible content, so the expansion ratio stays about 1. */
    private static byte[] randomZip(int bytes) throws Exception {
        byte[] content = new byte[bytes];
        new Random(7).nextBytes(content);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("random.bin"));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static void problem(HttpResponse<byte[]> response, int status) {
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(Problem.CONTENT_TYPE);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void waitForStatus(String jobId, String status) throws Exception {
        waitUntil(() -> {
            try {
                return json(jobGet(jobId, "", "")).path("status").asString().equals(status);
            } catch (Exception e) {
                return false;
            }
        });
    }

    private String sparqlOnUpload(String uploadId, String extraConfig) throws Exception {
        HttpResponse<byte[]> submitted = submit("{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId
                + "\",\"config\":{\"models\":[\"models/eq.ttl\"],\"query\":\"SELECT * WHERE {?s ?p ?o}\"" + extraConfig + "}}");
        assertThat(submitted.statusCode()).as(text(submitted)).isEqualTo(202);
        return json(submitted).path("jobId").asString();
    }

    // ---- uploads -----------------------------------------------------------------------------

    @Test
    void anUploadIsExtractedListedAndDeleted() throws Exception {
        start(config(), writingRunner(false));
        HttpResponse<byte[]> created = upload(zip("models/eq.ttl", "<urn:a> <urn:p> \"x\" .", "mapping.csv", "a;b"));

        assertThat(created.statusCode()).as(text(created)).isEqualTo(201);
        JsonNode upload = json(created);
        String id = upload.path("uploadId").asString();
        assertThat(created.headers().firstValue("Location")).hasValue("/v1/uploads/" + id);
        assertThat(upload.path("files").toString()).contains("models/eq.ttl").contains("mapping.csv");
        assertThat(upload.path("totalBytes").asLong()).isEqualTo(24);
        assertThat(upload.path("expiresAt").asString()).isEqualTo("2026-10-07T12:00:00Z");
        assertThat(Files.readString(work.resolve("uploads/" + id + "/models/eq.ttl"))).contains("urn:a");

        HttpResponse<byte[]> got = call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, true);
        assertThat(json(got).path("uploadId").asString()).isEqualTo(id);
        HttpResponse<byte[]> deleted = call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, true);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(work.resolve("uploads/" + id)).doesNotExist();
        problem(call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, true), 404);
        problem(call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, true), 404);
        problem(call("GET", "-", "/v1/uploads/not-a-uuid", null, null, true), 404);
    }

    @Test
    void onlyAZipBodyIsAcceptedAndOnlyOnTheUploadRoute() throws Exception {
        start(config(), writingRunner(false));
        byte[] zip = zip("a.ttl", "x");

        problem(call("POST", "/v1/uploads", "/v1/uploads", HttpRequest.BodyPublishers.ofString("{}"), "application/json", true), 415);
        problem(call("POST", "/v1/uploads", "/v1/uploads", HttpRequest.BodyPublishers.ofByteArray(zip), "text/plain", true), 415);
        problem(call("POST", "/v1/uploads", "/v1/uploads", HttpRequest.BodyPublishers.ofByteArray(zip), null, true), 415);
        // The SEC-1 rule still holds everywhere else.
        problem(call("POST", "/v1/jobs", "/v1/jobs", HttpRequest.BodyPublishers.ofByteArray(zip), "application/zip", true), 415);
        assertThat(call("POST", "-", "/sparql", HttpRequest.BodyPublishers.ofByteArray(zip), "application/zip", true)
                .statusCode()).isEqualTo(415);
        problem(call("GET", "-", "/v1/uploads", null, null, true), 405);
    }

    @Test
    void anUploadOverTheLimitIsRefusedAndNothingStaysOnDisk() throws Exception {
        start(config().maxUploadBytes(2048), writingRunner(false));
        byte[] big = randomZip(4000);

        problem(upload(big), 413); // Content-Length says too much
        HttpResponse<byte[]> chunked = call("POST", "/v1/uploads", "/v1/uploads",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)), "application/zip", true);
        assertThat(chunked.statusCode()).isIn(413, -1);
        if (chunked.statusCode() == 413) {
            problem(chunked, 413);
        }

        assertThat(server.workAreaForTest().incomingCount()).isZero();
        try (var dirs = Files.list(work.resolve("uploads"))) {
            assertThat(dirs.filter(p -> !p.getFileName().toString().equals(".incoming")).toList()).isEmpty();
        }
    }

    @Test
    void aBadArchiveIs400AndLeavesNothingBehind() throws Exception {
        start(config(), writingRunner(false));

        problem(upload("this is no zip".getBytes(StandardCharsets.UTF_8)), 400);
        HttpResponse<byte[]> slip = upload(zip("../escape.ttl", "x"));
        problem(slip, 400);
        assertThat(text(slip)).contains("Unsafe ZIP entry path");
        problem(upload(zip("CON", "x")), 400);

        assertThat(server.workAreaForTest().incomingCount()).isZero();
        assertThat(work.resolve("escape.ttl")).doesNotExist();
        assertThat(work.resolve("uploads/escape.ttl")).doesNotExist();
        try (var dirs = Files.list(work.resolve("uploads"))) {
            assertThat(dirs.filter(p -> !p.getFileName().toString().equals(".incoming")).toList()).isEmpty();
        }
    }

    @Test
    void theWorkAreaBudgetIsEnforced() throws Exception {
        start(config().workMaxBytes(ServeServer.MIN_WORK_MAX_BYTES), writingRunner(false));
        uploadId(randomZip(600_000));

        problem(upload(randomZip(600_000)), 507);
    }

    @Test
    void aRefusedUploadIsAnsweredWhileItsBodyIsStillComing() throws Exception {
        // The 507 is decided before the body is read. Closing the connection with megabytes still
        // unread resets it, and the client then loses the answer ("header parser received no bytes").
        start(config().workMaxBytes(ServeServer.MIN_WORK_MAX_BYTES), writingRunner(false));
        uploadId(randomZip(600_000));
        byte[] large = randomZip(4_000_000);

        for (int i = 0; i < 5; i++) {
            problem(upload(large), 507);
        }
    }

    @Test
    void everyFileRouteNeedsTheToken() throws Exception {
        start(config(), writingRunner(false));
        String id = UUID.randomUUID().toString();

        problem(call("POST", "/v1/uploads", "/v1/uploads", HttpRequest.BodyPublishers.ofByteArray(zip("a", "b")),
                "application/zip", false), 401);
        problem(call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, false), 401);
        problem(call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + id, null, null, false), 401);
        problem(call("GET", "/v1/jobs/{jobId}/files", "/v1/jobs/" + id + "/files", null, null, false), 401);
        problem(call("GET", "/v1/jobs/{jobId}/files/{path}", "/v1/jobs/" + id + "/files/a.csv", null, null, false), 401);
        problem(call("GET", "/v1/jobs/{jobId}/archive", "/v1/jobs/" + id + "/archive", null, null, false), 401);
    }

    // ---- jobs on uploads ---------------------------------------------------------------------

    @Test
    void aJobReadsItsUploadWritesItsOutFolderAndTheOutputsCanBeDownloaded() throws Exception {
        start(config(), writingRunner(false));
        String uploadId = uploadId(zip("models/eq.ttl", "<urn:a> <urn:p> \"x\" ."));
        String jobId = sparqlOnUpload(uploadId, ",\"output\":\"rows.csv\"");
        waitForStatus(jobId, "succeeded");

        JsonNode config = seenConfigs.getLast();
        Path uploadDir = work.resolve("uploads/" + uploadId).toRealPath();
        Path outDir = work.resolve("jobs/" + jobId + "/out").toRealPath();
        assertThat(config.path("models").get(0).asString()).isEqualTo(uploadDir.resolve("models/eq.ttl").toString());
        assertThat(config.path("output").asString()).isEqualTo(outDir.resolve("rows.csv").toString());

        JsonNode files = json(jobGet(jobId, "/files", "/files"));
        assertThat(files.path("files").toString()).contains("\"path\":\"rows.csv\"").contains("\"mediaType\":\"text/csv\"")
                .contains("reports/Résumé ü.xlsx");

        HttpResponse<byte[]> csv = jobGet(jobId, "/files/rows.csv", "/files/{path}");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(text(csv)).isEqualTo("o\nx\n");
        assertThat(csv.headers().firstValue("Content-Type")).hasValue("text/csv");
        assertThat(csv.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"rows.csv\"; filename*=UTF-8''rows.csv");
        assertThat(csv.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");

        HttpResponse<byte[]> xlsx = jobGet(jobId, "/files/reports/R%C3%A9sum%C3%A9%20%C3%BC.xlsx", "/files/{path}");
        assertThat(text(xlsx)).isEqualTo("xlsx-bytes");
        assertThat(xlsx.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"R_sum___.xlsx\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20%C3%BC.xlsx");

        HttpResponse<byte[]> archive = jobGet(jobId, "/archive", "/archive");
        assertThat(archive.statusCode()).isEqualTo(200);
        assertThat(archive.headers().firstValue("Content-Type")).hasValue("application/zip");
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive.body()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("rows.csv", "reports/Résumé ü.xlsx");
    }

    @Test
    void aMissingOutputFolderDefaultsToTheJobsOutFolder() throws Exception {
        start(config(), writingRunner(false));
        String uploadId = uploadId(zip("m.ttl", "x"));
        HttpResponse<byte[]> submitted = submit("{\"command\":\"validate\",\"inputUpload\":\"" + uploadId
                + "\",\"config\":{\"workflow\":\"combined\",\"dataFiles\":[\"m.ttl\"]}}");
        assertThat(submitted.statusCode()).as(text(submitted)).isEqualTo(202);
        waitForStatus(json(submitted).path("jobId").asString(), "succeeded");

        String outDir = work.resolve("jobs/" + json(submitted).path("jobId").asString() + "/out").toRealPath().toString();
        assertThat(seenConfigs.getLast().path("outputDir").asString()).isEqualTo(outDir);
    }

    @Test
    void anUploadJobMayReadOnlyItsUploadAndTheReadOnlyRootsAndWriteOnlyItsOutFolder() throws Exception {
        start(config(), writingRunner(false));
        String mine = uploadId(zip("models/eq.ttl", "x"));
        String theirs = uploadId(zip("secret.ttl", "x"));

        // A read-only root is fine: shared constraint libraries.
        assertThat(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\""
                + json(library.resolve("shapes.ttl").toString()) + "\"],\"query\":\"q\"}}").statusCode()).isEqualTo(202);
        // A read-write --root of the server, another upload, and the work area are not.
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\""
                + json(shared.resolve("private.ttl").toString()) + "\"],\"query\":\"q\"}}"), 403);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\"../"
                + theirs + "/secret.ttl\"],\"query\":\"q\"}}"), 403);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\""
                + json(work.resolve("uploads/" + theirs + "/secret.ttl").toString()) + "\"],\"query\":\"q\"}}"), 403);
        // Outputs: only under its own out/.
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\"models/eq.ttl\"],"
                + "\"query\":\"q\",\"output\":\"" + json(shared.resolve("out.csv").toString()) + "\"}}"), 403);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + mine + "\",\"config\":{\"models\":[\"models/eq.ttl\"],"
                + "\"query\":\"q\",\"output\":\"../../escape.csv\"}}"), 403);
        // A refused job leaves no workspace behind.
        try (var dirs = Files.list(work.resolve("jobs"))) {
            assertThat(dirs.count()).isEqualTo(1); // only the accepted job's
        }
    }

    /** A path as a JSON string body (backslashes on Windows). */
    private static String json(String path) {
        String quoted = JSON.writeValueAsString(path);
        return quoted.substring(1, quoted.length() - 1);
    }

    @Test
    void sharedModeIsUnchangedButCannotReachTheWorkArea() throws Exception {
        start(config(), writingRunner(false));
        String uploadId = uploadId(zip("secret.ttl", "x"));

        HttpResponse<byte[]> ok = submit("{\"command\":\"sparql\",\"config\":{\"models\":[\"private.ttl\"],\"query\":\"q\"}}");
        assertThat(ok.statusCode()).as(text(ok)).isEqualTo(202);
        waitForStatus(json(ok).path("jobId").asString(), "succeeded");
        assertThat(seenConfigs.getLast().path("models").get(0).asString())
                .isEqualTo(shared.resolve("private.ttl").toRealPath().toString());
        problem(jobGet(json(ok).path("jobId").asString(), "/files", "/files"), 404);

        Files.createDirectories(shared.resolve("sub"));
        problem(submit("{\"command\":\"sparql\",\"config\":{\"models\":[\""
                + json(work.resolve("uploads/" + uploadId + "/secret.ttl").toString()) + "\"],\"query\":\"q\"}}"), 403);
    }

    @Test
    void anUnknownUploadIs404AtSubmit() throws Exception {
        start(config(), writingRunner(false));
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + UUID.randomUUID() + "\",\"config\":{}}"), 404);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"nope\",\"config\":{}}"), 404);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":7,\"config\":{}}"), 400);
    }

    @Test
    void outputsAreOnlyAvailableOnceTheJobFinishedAndAnUploadInUseCannotBeDeleted() throws Exception {
        start(config(), writingRunner(true));
        String uploadId = uploadId(zip("models/eq.ttl", "x"));
        String jobId = sparqlOnUpload(uploadId, "");
        waitForStatus(jobId, "running");

        problem(jobGet(jobId, "/files", "/files"), 409);
        problem(jobGet(jobId, "/files/rows.csv", "/files/{path}"), 409);
        problem(jobGet(jobId, "/archive", "/archive"), 409);
        problem(call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + uploadId, null, null, true), 409);

        release.countDown();
        waitForStatus(jobId, "succeeded");
        assertThat(call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + uploadId, null, null, true).statusCode())
                .isEqualTo(204);
        assertThat(jobGet(jobId, "/files/rows.csv", "/files/{path}").statusCode()).as("outputs stay with the job")
                .isEqualTo(200);
    }

    @Test
    void downloadsCannotLeaveTheJobsOutFolder() throws Exception {
        start(config(), writingRunner(false));
        String uploadId = uploadId(zip("models/eq.ttl", "x"));
        String first = sparqlOnUpload(uploadId, "");
        waitForStatus(first, "succeeded");
        String second = sparqlOnUpload(uploadId, "");
        waitForStatus(second, "succeeded");

        for (String evil : List.of("/files/..%2F..%2F" + second + "%2Fout%2Frows.csv", "/files/..%2Frows.csv",
                "/files/%2e%2e/%2e%2e/" + second + "/out/rows.csv", "/files/reports%5C..%5Crows.csv",
                "/files/.", "/files/%2Fetc%2Fpasswd", "/files/reports", "/files/", "/files/rows.csv%00.txt",
                "/files/C:%2Fx", "/files/nothing.csv")) {
            HttpResponse<byte[]> response = jobGet(first, evil, "/files/{path}");
            assertThat(response.statusCode()).as(evil).isEqualTo(404);
        }
        problem(jobGet(UUID.randomUUID().toString(), "/files", "/files"), 404);
        problem(jobGet(UUID.randomUUID().toString(), "/archive", "/archive"), 404);
    }

    @Test
    void aSharedModeJobHasNoArchive() throws Exception {
        start(config(), writingRunner(false));
        HttpResponse<byte[]> ok = submit("{\"command\":\"sparql\",\"config\":{\"query\":\"q\"}}");
        waitForStatus(json(ok).path("jobId").asString(), "succeeded");
        problem(jobGet(json(ok).path("jobId").asString(), "/archive", "/archive"), 404);
        problem(jobGet(json(ok).path("jobId").asString(), "/files/x.csv", "/files/{path}"), 404);
    }

    // ---- retention ---------------------------------------------------------------------------

    @Test
    void uploadsExpireButNotWhileAJobUsesThemAndWorkspacesGoWithTheirJob() throws Exception {
        start(config().uploadTtl(Duration.ofHours(1)).jobTtl(Duration.ofHours(2)), writingRunner(true));
        String used = uploadId(zip("models/eq.ttl", "x"));
        String idle = uploadId(zip("other.ttl", "x"));
        String jobId = sparqlOnUpload(used, "");
        waitForStatus(jobId, "running");

        clock.advance(Duration.ofMinutes(61));
        problem(call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + idle, null, null, true), 404);
        assertThat(work.resolve("uploads/" + idle)).doesNotExist();
        assertThat(call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + used, null, null, true).statusCode())
                .as("in use: kept").isEqualTo(200);

        release.countDown();
        waitForStatus(jobId, "succeeded");
        clock.advance(Duration.ofSeconds(1));
        problem(call("GET", "/v1/uploads/{uploadId}", "/v1/uploads/" + used, null, null, true), 404);
        assertThat(work.resolve("jobs/" + jobId + "/out/rows.csv")).as("outputs live as long as the job").exists();

        clock.advance(Duration.ofHours(3));
        call("GET", "-", "/v1/jobs", null, null, true); // listing evicts expired jobs
        assertThat(work.resolve("jobs/" + jobId)).doesNotExist();
        assertThat(server.workAreaForTest().usedBytes()).isZero();
    }

    @Test
    void aStaleWorkAreaIsEmptiedAtStart() throws Exception {
        ServeServer.Config.Builder config = config();
        Files.createDirectories(work);
        Files.writeString(work.resolve(WorkArea.MARKER), "from an earlier run");
        Files.createDirectories(work.resolve("uploads/stale"));
        Files.writeString(work.resolve("uploads/stale/old.ttl"), "x");
        Files.createDirectories(work.resolve("jobs/stale/out"));
        Files.writeString(work.resolve("keep.txt"), "not ours to delete");

        start(config, writingRunner(false));

        assertThat(work.resolve("uploads/stale")).doesNotExist();
        assertThat(work.resolve("jobs/stale")).doesNotExist();
        assertThat(work.resolve("keep.txt")).exists();
    }

    @Test
    void aFolderWithoutTheMarkerIsRefusedAndLeftAlone() throws Exception {
        ServeServer.Config.Builder config = config();
        Files.createDirectories(work.resolve("jobs"));
        Files.writeString(work.resolve("jobs/report.xlsx"), "someone's file");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> start(config, writingRunner(false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a work area of serve");
        assertThat(work.resolve("jobs/report.xlsx")).hasContent("someone's file");
    }

    @Test
    void extractionCannotOvershootTheBudgetAndCostsPerEntry() throws Exception {
        start(config().workMaxBytes(ServeServer.MIN_WORK_MAX_BYTES), writingRunner(false));
        // 700 KB of zeros compress to about 1 KB: the declared length says nothing about the extract.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("zeros.bin"));
            zip.write(new byte[700_000]);
            zip.closeEntry();
        }
        assertThat(upload(bytes.toByteArray()).statusCode()).isEqualTo(201);
        HttpResponse<byte[]> second = upload(bytes.toByteArray());
        problem(second, 400);
        assertThat(text(second)).contains("size limit");
        assertThat(server.workAreaForTest().usedBytes()).isLessThanOrEqualTo(ServeServer.MIN_WORK_MAX_BYTES)
                .isEqualTo(700_000 + 4096);
    }

    @Test
    void uploadErrorsNeverShowServerPaths() throws Exception {
        start(config(), writingRunner(false));
        HttpResponse<byte[]> conflict = upload(zip("a", "1", "a/b", "2"));
        problem(conflict, 400);
        assertThat(text(conflict)).contains("a/b").doesNotContain(work.toString())
                .doesNotContain(work.toRealPath().toString()).doesNotContain(tempDir.getFileName().toString());
    }

    @Test
    void aDownloadNameTheFileSystemCannotExpressIs404() throws Exception {
        start(config(), writingRunner(false));
        String jobId = sparqlOnUpload(uploadId(zip("models/eq.ttl", "x")), "");
        waitForStatus(jobId, "succeeded");
        for (String odd : List.of("/files/a%3Fb.csv", "/files/a%2Ab.csv", "/files/a%3Cb%3E.csv", "/files/a%22b.csv",
                "/files/a%7Cb.csv")) {
            problem(jobGet(jobId, odd, "/files/{path}"), 404);
        }
    }

    @Test
    void aFailingCheckReleasesTheUpload() throws Exception {
        start(config(), writingRunner(false));
        String uploadId = uploadId(zip("models/eq.ttl", "x"));
        // The read-only root disappears after start: the job policy can't be built.
        eu.griddigit.cimpal.core.utils.ZipExtractor.deleteTree(library);

        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + uploadId + "\",\"config\":{\"query\":\"q\"}}"), 500);

        assertThat(call("DELETE", "/v1/uploads/{uploadId}", "/v1/uploads/" + uploadId, null, null, true).statusCode())
                .as("not pinned").isEqualTo(204);
        try (var dirs = Files.list(work.resolve("jobs"))) {
            assertThat(dirs.toList()).isEmpty();
        }
    }

    @Test
    void withoutAWorkRootTheFileRoutesAre404() throws Exception {
        server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN).build(),
                writingRunner(false), ServeServer.RequestCheck.NONE, () -> { }, clock);
        problem(upload(zip("a", "b")), 404);
        problem(submit("{\"command\":\"sparql\",\"inputUpload\":\"" + UUID.randomUUID() + "\",\"config\":{}}"), 404);
    }

    @Test
    void operatorLimitsAreChecked() throws Exception {
        for (ServeServer.Config.Builder bad : List.of(config().maxUploadBytes(ServeServer.MIN_UPLOAD_BYTES - 1),
                config().maxUploadBytes(ServeServer.MAX_UPLOAD_BYTES + 1),
                config().workMaxBytes(ServeServer.MIN_WORK_MAX_BYTES - 1), config().uploadTtl(Duration.ZERO),
                config().requestReadTime(Duration.ZERO), config().requestReadTime(Duration.ofHours(3)))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ServeServer.start(bad.build(), writingRunner(false)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- headers -----------------------------------------------------------------------------

    @Test
    void contentDispositionNeverCarriesQuotesOrLineBreaks() {
        String header = ServeServer.contentDisposition("a\"b\r\nX-Evil: 1;.csv");
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).startsWith("attachment; filename=\"a_b__X-Evil__1_.csv\"; filename*=UTF-8''");
        assertThat(header.substring(header.indexOf("filename*="))).doesNotContain("\"").doesNotContain(" ");
        assertThat(ServeServer.mediaType("report.XLSX"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(ServeServer.mediaType("page.html")).isEqualTo("application/octet-stream");
        assertThat(ServeServer.mediaType("noext")).isEqualTo("application/octet-stream");
    }

    // ---- the spec lists only what is tested --------------------------------------------------

    @AfterAll
    static void everyFileExchangeResponseInTheSpecIsExercised() throws Exception {
        long tests = java.util.Arrays.stream(FileExchangeApiTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        org.junit.jupiter.api.Assumptions.assumeTrue(FINISHED.get() == tests * TestStores.values().length, "not all tests of the class ran");
        JsonNode spec;
        try (InputStream in = FileExchangeApiTest.class.getResourceAsStream("/openapi/cimpal-v1.json")) {
            spec = JSON.readTree(in);
        }
        Set<String> expected = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (op.getKey().equals("parameters")) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> response : op.getValue().path("responses").properties()) {
                    String key = op.getKey().toUpperCase() + " " + path.getKey() + " " + response.getKey();
                    if ((ROUTES.contains(path.getKey()) || key.equals(SUBMIT_UNKNOWN_UPLOAD))
                            && !ClientIsolationTest.covers(key) && !IdempotencyTest.covers(key)) {
                        expected.add(key);
                    }
                }
            }
        }
        assertThat(SEEN).as("every file exchange response in the OpenAPI document is exercised").containsAll(expected);
    }
}
