/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code serve} log (DEP-6): text as before, or JSON lines. */
class ServeLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<JsonNode> lines(ByteArrayOutputStream bytes) {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isEmpty()) {
                nodes.add(JSON.readTree(line));
            }
        }
        return nodes;
    }

    @Test
    void textModeKeepsTheOldLinesAndHasNoInfoEvents() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            ServeLog log = ServeLog.text();
            log.info("job.started", "serve: job started");
            log.warn("request.rejected", "serve: 401 GET /commands - Missing or invalid bearer token", "status", 401);
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8))
                .isEqualTo("[WARN] serve: 401 GET /commands - Missing or invalid bearer token" + System.lineSeparator());
    }

    @Test
    void jsonFieldsAreTypedSanitisedAndNullsLeftOut() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServeLog log = ServeLog.json(new PrintStream(out, true, StandardCharsets.UTF_8), new JobApiTest.MovableClock());
        log.info("job.finished", "done\nforged line", "exitCode", 1, "durationMs", 42L, "jobId", null,
                "path", "/x\u001b[2J");

        JsonNode node = lines(out).getFirst();
        assertThat(node.path("ts").asString()).isEqualTo("2026-10-06T12:00:00Z");
        assertThat(node.path("level").asString()).isEqualTo("info");
        assertThat(node.path("event").asString()).isEqualTo("job.finished");
        assertThat(node.path("message").asString()).doesNotContain("\n");
        assertThat(node.path("exitCode").isInt()).isTrue();
        assertThat(node.path("durationMs").asLong()).isEqualTo(42);
        assertThat(node.has("jobId")).isFalse();
        assertThat(node.path("path").asString()).doesNotContain("\u001b");
    }

    @Test
    void wrappedStderrTurnsEveryLineIntoAnOutputEventWithTheRunningJob() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServeLog log = ServeLog.json(new PrintStream(out, true, StandardCharsets.UTF_8), new JobApiTest.MovableClock());
        PrintStream err = log.stderr(() -> "job-1");

        err.println("[INFO] Loading 2 model file(s)...");
        err.print("[ERROR] half ");
        err.println("a line");
        err.println("[main] WARN org.apache.jena.riot - Bad IRI");

        List<JsonNode> nodes = lines(out);
        assertThat(nodes).hasSize(3);
        assertThat(nodes).allSatisfy(n -> {
            assertThat(n.path("event").asString()).isEqualTo("output");
            assertThat(n.path("jobId").asString()).isEqualTo("job-1");
        });
        assertThat(nodes.get(0).path("level").asString()).isEqualTo("info");
        assertThat(nodes.get(1).path("level").asString()).isEqualTo("error");
        assertThat(nodes.get(1).path("message").asString()).isEqualTo("[ERROR] half a line");
        assertThat(nodes.get(2).path("level").asString()).isEqualTo("warn");
    }

    @Test
    void wordsLaterInALineDontRaiseItsLevel() {
        // Text from the input (IRIs, literals) can follow; only the line's start counts.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServeLog log = ServeLog.json(new PrintStream(out, true, StandardCharsets.UTF_8), new JobApiTest.MovableClock());
        PrintStream err = log.stderr(() -> null);
        err.println("[INFO] literal \"x ERROR y\" and \" WARN \"");
        err.println("[ERROR] real");

        List<JsonNode> nodes = lines(out);
        assertThat(nodes.get(0).path("level").asString()).isEqualTo("info");
        assertThat(nodes.get(1).path("level").asString()).isEqualTo("error");
    }

    @Test
    void serverThreadsAreNotAttributedToTheJobAndPartialLinesAreFlushedAtTheEnd() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ServeLog log = ServeLog.json(new PrintStream(out, true, StandardCharsets.UTF_8), new JobApiTest.MovableClock());
        PrintStream err = log.stderr(() -> "job-1");
        for (String name : new String[] {"HTTP-Dispatcher", "cimpal-serve-stop", "SIGTERM handler"}) {
            Thread t = new Thread(() -> err.println("from the server"), name);
            t.start();
            t.join();
        }
        err.print("[INFO] no newline yet");
        log.flushOutput();

        List<JsonNode> nodes = lines(out);
        assertThat(nodes).hasSize(4);
        assertThat(nodes.subList(0, 3)).allSatisfy(n -> assertThat(n.has("jobId")).isFalse());
        assertThat(nodes.get(3).path("message").asString()).isEqualTo("[INFO] no newline yet");
        assertThat(nodes.get(3).path("jobId").asString()).isEqualTo("job-1");
    }
}
