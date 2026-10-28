/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress test of {@code serve --workers} (DEP-8): a real {@link ServeCommand} runs 24 mixed jobs
 * (combined and mapping validation, sparql, convert, SHACL compare, rdfs2shacl) with four
 * workers, and every job's result and output must equal those of the same jobs run one at a
 * time. Each job has its own inputs, named after it, so a log line naming another job's file
 * shows output that reached the wrong job. The compare jobs bind the same namespace to a
 * different prefix each, which a shared prefix map would mix up.
 */
class ConcurrencyIT {

    private static final String TOKEN = "concurrency-it-token-0123456789abcdef0123";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KINDS = List.of("combined", "mapping", "sparql", "convert", "compare", "rdfs2shacl");
    private static final int COPIES = 4;
    private static final Pattern JOB_TAG = Pattern.compile("job(\\d{2})");

    @TempDir
    Path tempDir;

    /** What one job produced: exit code, normalised result, its log, and its RDF output (or null). */
    private record Outcome(int exitCode, JsonNode result, List<String> log, Model rdf) {
    }

    @Test
    void fourWorkersGiveEveryJobTheSameResultAsOneAndKeepTheirLogsApart() throws Exception {
        Map<String, Outcome> serial = run(1);
        Map<String, Outcome> concurrent = run(4);

        assertThat(concurrent.keySet()).isEqualTo(serial.keySet()).hasSize(KINDS.size() * COPIES);
        for (String job : serial.keySet()) {
            Outcome one = serial.get(job);
            Outcome four = concurrent.get(job);
            assertThat(four.exitCode()).as(job + " exit code").isEqualTo(one.exitCode());
            assertThat(four.result()).as(job + " result").isEqualTo(one.result());
            if (one.rdf() != null) {
                assertThat(four.rdf().isIsomorphicWith(one.rdf())).as(job + " RDF output").isTrue();
            }
            String tag = job.substring(job.indexOf("job"), job.indexOf("job") + 5);
            for (String line : four.log()) {
                Matcher m = JOB_TAG.matcher(line);
                while (m.find()) {
                    assertThat(m.group()).as(job + " log line: " + line).isEqualTo(tag);
                }
            }
        }
        // The jobs did something: the validations found the missing ex:size, sparql found rows.
        assertThat(serial.get("combined-job00").exitCode()).isEqualTo(1);
        assertThat(serial.get("mapping-job04").exitCode()).isEqualTo(1);
        assertThat(serial.get("sparql-job08").result().path("rows")).hasSize(1);
        assertThat(serial.get("compare-job16").result().toString()).contains("p16:");
    }

    /** Starts serve with {@code workers}, submits all jobs at once, and collects their outcomes. */
    private Map<String, Outcome> run(int workers) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("w" + workers));
        Map<String, ObjectNode> jobs = jobs(root);

        ServeCommand command = new ServeCommand();
        command.environment = Map.of(ServeSecurity.TOKEN_ENV, TOKEN);
        command.onTerm = onTerm -> false;
        CompletableFuture<ServeServer> started = new CompletableFuture<>();
        command.onStarted = started::complete;
        String[] args = {"--host", "127.0.0.1", "--port", "0", "--workers", Integer.toString(workers),
                "--queue-size", "32", "--root", root.toString(), "--no-file-exchange"};
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> new CommandLine(command).execute(args));
        ServeServer server = CompletableFuture.anyOf(started, exit).thenApply(x -> started.getNow(null))
                .get(30, TimeUnit.SECONDS);
        assertThat(server).as("serve started").isNotNull();

        Map<String, String> ids = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, ObjectNode> job : jobs.entrySet()) {
                String kind = job.getKey().substring(0, job.getKey().indexOf("-job"));
                ObjectNode body = JSON.createObjectNode().put("command", kind.equals("combined")
                        || kind.equals("mapping") ? "validate" : kind);
                body.set("config", job.getValue());
                HttpResponse<String> submitted = call(server, "POST", "/v1/jobs", body.toString());
                assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
                ids.put(job.getKey(), JSON.readTree(submitted.body()).path("jobId").asString());
            }
            Map<String, Outcome> outcomes = new LinkedHashMap<>();
            // serve reports real paths: on Windows the temp folder may be given in its 8.3 short form
            Normalizer normalizer = Normalizer.chain(Normalizer.paths(root.toRealPath()), Normalizer.paths(root),
                    Normalizer.timestamps());
            for (Map.Entry<String, String> id : ids.entrySet()) {
                JsonNode status = awaitFinished(server, id.getValue());
                assertThat(status.path("status").asString()).as(id.getKey() + ": " + status)
                        .isIn("succeeded", "failed");
                String body = call(server, "GET", "/v1/jobs/" + id.getValue() + "/result", null).body();
                JsonNode result;
                try {
                    result = JSON.readTree(body);
                } catch (RuntimeException notJson) {
                    result = JSON.getNodeFactory().stringNode(body); // convert and rdfs2shacl print text
                }
                List<String> log = new ArrayList<>();
                JSON.readTree(call(server, "GET", "/v1/jobs/" + id.getValue() + "/log?limit=1000", null).body())
                        .path("lines").forEach(l -> log.add(l.asString()));
                outcomes.put(id.getKey(), new Outcome(status.path("exitCode").asInt(-1), normalize(result, normalizer),
                        log, rdfOutput(id.getKey(), root, normalizer)));
            }
            return outcomes;
        } finally {
            call(server, "POST", "/shutdown", null);
            assertThat(exit.get(60, TimeUnit.SECONDS)).isZero();
        }
    }

    /** The 24 job configs, each with its own inputs and outputs under {@code root}. */
    private static Map<String, ObjectNode> jobs(Path root) throws Exception {
        Map<String, ObjectNode> jobs = new LinkedHashMap<>();
        int n = 0;
        for (String kind : KINDS) {
            for (int copy = 0; copy < COPIES; copy++, n++) {
                String tag = String.format("job%02d", n);
                Path in = Files.createDirectories(root.resolve("in").resolve(tag));
                Path out = root.resolve("out").resolve(tag);
                ObjectNode config = JSON.createObjectNode();
                switch (kind) {
                    case "combined" -> {
                        Path shapes = Files.writeString(in.resolve(tag + "-shapes.ttl"), TestModels.THING_SHAPES);
                        Path data = Files.writeString(in.resolve(tag + "-data.xml"), TestModels.VIOLATING_THING_MODEL);
                        config.put("workflow", "combined").put("outputDir", out.toString())
                                .put("xmlBase", TestModels.XML_BASE).put("workers", 2);
                        config.putArray("constraintFiles").add(shapes.toString());
                        config.putArray("dataFiles").add(data.toString());
                    }
                    case "mapping" -> {
                        Path models = Files.createDirectories(in.resolve("models"));
                        Path constraints = Files.createDirectories(in.resolve("constraints"));
                        Files.writeString(models.resolve(tag + "-data.xml"), TestModels.VIOLATING_THING_MODEL);
                        Files.writeString(constraints.resolve(tag + "-shapes.ttl"), TestModels.THING_SHAPES);
                        Path csv = Files.writeString(in.resolve(tag + "-mapping.csv"),
                                "xml_inputs,ttl,notes\n" + tag + "-data.xml," + tag + "-shapes.ttl,Thing check\n");
                        config.put("workflow", "mapping").put("mappingCsv", csv.toString())
                                .put("modelsDir", models.toString()).put("constraintsRoot", constraints.toString())
                                .put("outputDir", out.toString()).put("xmlBase", TestModels.XML_BASE).put("workers", 2);
                    }
                    case "sparql" -> {
                        Path eq = Files.writeString(in.resolve(tag + "-eq.xml"), eq(tag));
                        config.put("query", "SELECT ?name WHERE { ?s <" + TestModels.CIM_NS
                                + "IdentifiedObject.name> ?name } ORDER BY ?name").put("xmlBase", TestModels.XML_BASE);
                        config.putArray("models").add(eq.toString());
                    }
                    case "convert" -> {
                        Path eq = Files.writeString(in.resolve(tag + "-eq.xml"), eq(tag));
                        config.put("input", eq.toString()).put("output", out.resolve(tag + "-eq.ttl").toString())
                                .put("xmlBase", TestModels.XML_BASE);
                        Files.createDirectories(out);
                    }
                    case "compare" -> {
                        String prefix = "p" + tag.substring(3);
                        Path a = Files.writeString(in.resolve(tag + "-a.ttl"), shapes(prefix, "Thing"));
                        Path b = Files.writeString(in.resolve(tag + "-b.ttl"), shapes(prefix, "Other"));
                        config.put("fileA", a.toString()).put("fileB", b.toString()).put("compareType", "shacl");
                    }
                    case "rdfs2shacl" -> {
                        Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", in);
                        Path renamed = Files.move(rdfs, in.resolve(tag + "-equipment.rdf"));
                        config.put("outputDir", out.toString());
                        config.putArray("rdfsFiles").add(renamed.toString());
                    }
                    default -> throw new IllegalStateException(kind);
                }
                jobs.put(kind + "-" + tag, config);
            }
        }
        return jobs;
    }

    private static String eq(String tag) {
        return TestModels.eq().resource("Substation", "_" + tag).literal("IdentifiedObject.name", "Substation " + tag)
                .toRdfXml();
    }

    private static String shapes(String prefix, String targetClass) {
        return """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix %s: <urn:concurrency:> .
                <urn:shapes#S> a sh:NodeShape ; sh:targetClass %s:%s .
                """.formatted(prefix, prefix, targetClass);
    }

    /** The RDF a convert or rdfs2shacl job wrote (its literals normalised: rdfs2shacl stamps the date), or null. */
    private static Model rdfOutput(String job, Path root, Normalizer normalizer) throws Exception {
        String tag = job.substring(job.indexOf("job"));
        Path out = root.resolve("out").resolve(tag);
        if (job.startsWith("convert")) {
            return Snapshots.normalizeLiterals(RDFDataMgr.loadModel(out.resolve(tag + "-eq.ttl").toString()), normalizer);
        }
        if (job.startsWith("rdfs2shacl")) {
            return Snapshots.normalizeLiterals(RDFDataMgr.loadModel(out.resolve(tag + "-equipment.ttl").toString()),
                    normalizer);
        }
        return null;
    }

    /** Paths and timestamps normalised; durations, statistics and blank-node ids, which vary between runs, removed. */
    private static JsonNode normalize(JsonNode node, Normalizer normalizer) {
        if (node.isString()) {
            return JSON.getNodeFactory().stringNode(normalizer.apply(node.asString()));
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.forEach(element -> array.add(normalize(element, normalizer)));
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String key = field.getKey();
                // shapeId: the id of a blank-node property shape changes with every parse, not with concurrency
                if (key.equals("stats") || key.equals("shapeId") || key.endsWith("Ms") || key.toLowerCase().contains("duration")
                        || key.toLowerCase().contains("elapsed")) {
                    continue;
                }
                object.set(key, normalize(field.getValue(), normalizer));
            }
            return object;
        }
        return node;
    }

    private static JsonNode awaitFinished(ServeServer server, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (true) {
            JsonNode status = JSON.readTree(call(server, "GET", "/v1/jobs/" + id, null).body());
            String s = status.path("status").asString();
            if (!s.equals("queued") && !s.equals("running")) {
                return status;
            }
            assertThat(System.nanoTime()).as("job " + id + " finished within 5 minutes").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static HttpResponse<String> call(ServeServer server, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
