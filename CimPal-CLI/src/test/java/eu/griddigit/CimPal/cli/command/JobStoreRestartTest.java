/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jobs survive a restart with a durable store (DEP-11): one server stops while a job runs and two
 * wait; another starts on the same database and work root. The running job ends as interrupted
 * (D-18), the queued ones run, and every earlier result and output can still be fetched. The
 * database is H2 in PostgreSQL mode; {@code JdbcJobStorePostgresIT} repeats this on PostgreSQL.
 */
class JobStoreRestartTest {

    static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    /** Holds the job that "runs while the server dies"; released only when the test ends. */
    private final CountDownLatch never = new CountDownLatch(1);
    private final List<ServeServer> servers = new ArrayList<>();
    private final List<JobStore> stores = new ArrayList<>();

    @AfterEach
    void stop() {
        never.countDown();
        servers.forEach(ServeServer::close);
        stores.forEach(JobStore::close);
    }

    /** Writes a report for a job with an upload; blocks forever when the config says {"block": true}. */
    private ServeServer.CommandRunner runner() {
        return new ServeServer.CommandRunner() {
            @Override
            public ServeServer.CommandResult run(String command, Path configFile) throws Exception {
                if (JSON.readTree(configFile.toFile()).path("block").asBoolean(false)) {
                    never.await(60, TimeUnit.SECONDS);
                }
                System.err.println("[INFO] ran " + command);
                return new ServeServer.CommandResult(0, "{\"ran\":\"" + command + "\"}");
            }

            @Override
            public ServeServer.CommandResult run(String command, Path configFile, WorkArea.JobWorkspace ws)
                    throws Exception {
                Files.writeString(ws.outDir().resolve("report.txt"), "report of " + command);
                return run(command, configFile);
            }
        };
    }

    /** A server on {@code store} and the shared work root. */
    ServeServer start(JobStore store) throws Exception {
        ServeServer server = ServeServer.start(ServeServer.Config.builder().host("127.0.0.1").port(0).token(TOKEN)
                        .maxBodyBytes(4096).queueSize(4).workers(1).requestTimeout(Duration.ofSeconds(30))
                        .workRoot(tempDir.resolve("work")).jobStore(store).build(),
                runner(), ServeServer.RequestCheck.NONE, () -> { }, clock);
        servers.add(server);
        stores.add(store);
        return server;
    }

    /** A new, empty database for one test: H2 here, a PostgreSQL schema in {@code JobStoreRestartPostgresIT}. */
    String newDatabase() {
        return TestStores.h2Url();
    }

    /** A store on {@code url}, as a new process would open it. */
    JdbcJobStore open(String url) {
        return TestStores.h2(url, ServeServer.Config.builder().token(TOKEN).build(), clock);
    }

    static HttpResponse<byte[]> send(ServeServer server, String method, String path, byte[] body, String type)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + TOKEN);
        if (type != null) {
            request.header("Content-Type", type);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static JsonNode json(HttpResponse<byte[]> response) {
        return JSON.readTree(response.body());
    }

    static String submit(ServeServer server, String body) throws Exception {
        HttpResponse<byte[]> r = send(server, "POST", "/v1/jobs", body.getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(r.statusCode()).as(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(202);
        return json(r).path("jobId").asString();
    }

    static String status(ServeServer server, String id) throws Exception {
        return json(send(server, "GET", "/v1/jobs/" + id, null, null)).path("status").asString();
    }

    static void waitForStatus(ServeServer server, String id, String status) throws Exception {
        waitUntil(() -> {
            try {
                return status(server, id).equals(status);
            } catch (Exception e) {
                return false;
            }
        });
    }

    static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 20 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    static byte[] zip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("models/eq.ttl"));
            out.write("<urn:a> <urn:p> \"x\" .".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void jobsSurviveARestart() throws Exception {
        String url = newDatabase();
        restart(() -> open(url), () -> open(url));
    }

    /** The scenario, for any pair of stores on one database (H2 here, PostgreSQL in the IT). */
    void restart(java.util.concurrent.Callable<JobStore> first, java.util.concurrent.Callable<JobStore> second)
            throws Exception {
        ServeServer before = start(first.call());
        HttpResponse<byte[]> uploaded = send(before, "POST", "/v1/uploads", zip(), "application/zip");
        assertThat(uploaded.statusCode()).isEqualTo(201);
        String upload = json(uploaded).path("uploadId").asString();
        String done = submit(before, "{\"command\":\"sparql\",\"inputUpload\":\"" + upload + "\"}");
        waitForStatus(before, done, "succeeded");
        String running = submit(before, "{\"command\":\"validate\",\"config\":{\"block\":true}}");
        waitForStatus(before, running, "running");
        String queuedWithUpload = submit(before, "{\"command\":\"sparql\",\"inputUpload\":\"" + upload + "\"}");
        String queued = submit(before, "{\"command\":\"convert\"}");

        before.close(); // the running job is cut off: its command never returns to this server

        ServeServer after = start(second.call());

        assertThat(status(after, running)).isEqualTo("failed");
        assertThat(json(send(after, "GET", "/v1/jobs/" + running, null, null)).path("error").asString())
                .startsWith("Interrupted");
        waitForStatus(after, queuedWithUpload, "succeeded");
        waitForStatus(after, queued, "succeeded");
        assertThat(new String(send(after, "GET", "/v1/jobs/" + queued + "/result", null, null).body(),
                StandardCharsets.UTF_8)).isEqualTo("{\"ran\":\"convert\"}");
        assertThat(json(send(after, "GET", "/v1/jobs/" + queued + "/log", null, null)).path("lines").toString())
                .contains("ran convert");
        // Everything from before the restart is still there.
        assertThat(new String(send(after, "GET", "/v1/jobs/" + done + "/result", null, null).body(),
                StandardCharsets.UTF_8)).isEqualTo("{\"ran\":\"sparql\"}");
        HttpResponse<byte[]> report = send(after, "GET", "/v1/jobs/" + done + "/files/report.txt", null, null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(new String(report.body(), StandardCharsets.UTF_8)).isEqualTo("report of sparql");
        assertThat(send(after, "GET", "/v1/uploads/" + upload, null, null).statusCode()).isEqualTo(200);
        assertThat(json(send(after, "GET", "/v1/jobs", null, null)).path("jobs")).hasSize(4);
        // The work area was kept and counts what it holds.
        assertThat(after.workAreaForTest().usedBytes()).isPositive();
    }

    @Test
    void anUploadWhoseFolderVanishedIsForgottenAndItsQueuedJobCancelled() throws Exception {
        String url = newDatabase();
        ServeServer before = start(open(url));
        String upload = json(send(before, "POST", "/v1/uploads", zip(), "application/zip")).path("uploadId").asString();
        String running = submit(before, "{\"command\":\"validate\",\"config\":{\"block\":true}}");
        waitForStatus(before, running, "running");
        String queued = submit(before, "{\"command\":\"sparql\",\"inputUpload\":\"" + upload + "\"}");
        before.close();
        ZipExtractorHelper.delete(tempDir.resolve("work/uploads").resolve(upload));

        ServeServer after = start(open(url));

        assertThat(status(after, queued)).isEqualTo("cancelled");
        assertThat(json(send(after, "GET", "/v1/jobs/" + queued, null, null)).path("error").asString())
                .contains("upload is gone");
        assertThat(send(after, "GET", "/v1/uploads/" + upload, null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void foldersNoJobKnowsAreDeletedAtStart() throws Exception {
        String url = newDatabase();
        Path stray = Files.createDirectories(tempDir.resolve("work/jobs/" + java.util.UUID.randomUUID() + "/out"));
        Files.createDirectories(tempDir.resolve("work"));
        Files.writeString(tempDir.resolve("work").resolve(WorkArea.MARKER), "x");

        start(open(url));

        assertThat(stray.getParent()).doesNotExist();
    }

    /** Deletes a folder tree (the work area's own helper is in Core). */
    static final class ZipExtractorHelper {
        static void delete(Path dir) {
            eu.griddigit.cimpal.core.utils.ZipExtractor.deleteTree(dir);
        }
    }

    @Test
    void aQueuedRowWithAnUnknownCommandIsCancelledNotRun() throws Exception {
        String url = newDatabase();
        JdbcJobStore store = open(url);
        java.util.UUID id = java.util.UUID.randomUUID();
        store.create(id, "run", null, "default", "{}".getBytes(StandardCharsets.UTF_8), null); // as a forged row
        store.close();

        ServeServer after = start(open(url));

        assertThat(status(after, id.toString())).isEqualTo("cancelled");
        assertThat(json(send(after, "GET", "/v1/jobs/" + id, null, null)).path("error").asString())
                .contains("not a command");
    }

    @Test
    void aLinkPlantedInTheWorkAreaIsRemovedNotFollowed() throws Exception {
        String url = newDatabase();
        ServeServer before = start(open(url));
        String upload = json(send(before, "POST", "/v1/uploads", zip(), "application/zip")).path("uploadId").asString();
        String running = submit(before, "{\"command\":\"validate\",\"config\":{\"block\":true}}");
        waitForStatus(before, running, "running");
        String queued = submit(before, "{\"command\":\"sparql\",\"inputUpload\":\"" + upload + "\"}");
        before.close();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path jobDir = tempDir.resolve("work/jobs").resolve(queued);
        ZipExtractorHelper.delete(jobDir);
        try {
            Files.createSymbolicLink(jobDir, outside);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links can't be created here: " + e.getClass().getSimpleName());
        }

        ServeServer after = start(open(url));
        waitForStatus(after, queued, "succeeded");

        assertThat(Files.isSymbolicLink(jobDir)).isFalse();
        try (var files = Files.list(outside)) {
            assertThat(files.toList()).as("nothing written through the link").isEmpty();
        }
    }
}
