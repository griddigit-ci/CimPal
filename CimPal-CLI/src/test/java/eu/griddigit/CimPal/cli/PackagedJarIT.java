/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test of the shaded JAR (TEST-4, D5): each command runs as {@code java -jar CimPal-CLI.jar}
 * in a process of its own, on the fixtures of {@link CliFixtures}, with the JDK running the
 * build. It runs in failsafe after the shade plugin, so {@code mvn verify} checks the JAR that is
 * released on both CI operating systems. A broken shade (e.g. Jena's service files not merged)
 * passes every in-process test and fails here.
 */
class PackagedJarIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final List<String> SCENARIOS;

    static {
        List<String> commands = new ArrayList<>(CliFixtures.CONFIG_COMMANDS);
        commands.add("run");
        SCENARIOS = List.copyOf(commands);
    }

    private static Path jar;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void findTheJar() {
        jar = Path.of(System.getProperty("cimpal.cliJar", "target/CimPal-CLI.jar")).toAbsolutePath();
        assertThat(jar).as("the shaded JAR (mvn package first)").isRegularFile();
    }

    private CliRun.Result java(String stdin, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(CliRun.javaExecutable(), "-jar", jar.toString()));
        command.addAll(List.of(args));
        return CliRun.process(tempDir, command, stdin);
    }

    @Test
    void helpAndVersion() throws Exception {
        CliRun.Result help = java(null, "--help");
        CliRun.Result version = java(null, "--version");

        assertThat(help.exitCode()).as(help.stderr()).isZero();
        assertThat(help.stdout()).contains("validate", "sparql", "compare-instances", "gen-instances", "serve", "mcp");
        assertThat(version.exitCode()).isZero();
        assertThat(version.stdout().strip()).isEqualTo("CimPal CLI " + System.getProperty("cimpal.expectedVersion"));
    }

    @ParameterizedTest
    @FieldSource("SCENARIOS")
    void eachCommandRunsFromTheJar(String command) throws Exception {
        CliFixtures.Scenario scenario = CliFixtures.scenario(command, tempDir);

        CliRun.Result run = java(null, scenario.args());

        assertThat(run.exitCode()).as("stdout:%n%s%nstderr:%n%s", run.stdout(), run.stderr())
                .isEqualTo(scenario.exitCode());
        for (String output : scenario.outputs()) {
            assertThat(tempDir.resolve(output)).as(output).isRegularFile().isNotEmptyFile();
        }
        if (CliFixtures.JSON_COMMANDS.contains(command)) {
            JsonNode document = run.json();
            assertThat(CliSchemas.errors(CliSchemas.schemaFile(command), document)).as(document.toString()).isEmpty();
        }
        assertThat(run.stderr()).doesNotContain("ClassNotFoundException").doesNotContain("NoClassDefFoundError");
    }

    @Test
    void aMissingInputIsExitTwo() throws Exception {
        CliRun.Result run = java(null, "sparql", "--format", "json");

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stdout()).isEmpty();
    }

    @Test
    void mcpAnswersOverStdio() throws Exception {
        CliFixtures.Scenario sparql = CliFixtures.scenario("sparql", tempDir);
        ObjectNode call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call");
        call.putObject("params").put("name", "sparql").set("arguments", sparql.config());
        String stdin = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """ + JSON.writeValueAsString(call) + "\n{not json\n";

        CliRun.Result run = java(stdin, "mcp", "--root", tempDir.toString());

        assertThat(run.exitCode()).as(run.stderr()).isZero();
        List<JsonNode> messages = run.stdout().lines().filter(l -> !l.isBlank()).map(JSON::readTree)
                .filter(m -> m.has("id")).toList();
        assertThat(messages).extracting(m -> m.get("id").toString()).containsExactly("1", "2", "3", "null");
        assertThat(messages.get(0).path("result").path("serverInfo").path("name").asString()).isEqualTo("CimPal");
        assertThat(messages.get(1).path("result").path("tools")).hasSize(10);
        JsonNode result = messages.get(2).path("result");
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(JSON.readTree(result.path("content").get(0).path("text").asString()).path("rows")).hasSize(2);
        assertThat(messages.get(3).path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void serveAnswersHealthAndACommandAndShutsDown() throws Exception {
        String token = "packaged-jar-it-token-0123456789abcdef";
        CliFixtures.Scenario sparql = CliFixtures.scenario("sparql", tempDir);
        ProcessBuilder builder = new ProcessBuilder(CliRun.javaExecutable(), "-jar", jar.toString(), "serve",
                "--port", "0", "--root", tempDir.toString())
                .directory(tempDir.toFile())
                .redirectError(tempDir.resolve("serve-stderr.txt").toFile());
        builder.environment().put("CIMPAL_API_TOKEN", token);
        builder.environment().remove("CIMPAL_API_TOKEN_FILE");
        Process serve = builder.start();
        try {
            int port = listeningPort(serve);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            URI base = URI.create("http://localhost:" + port);

            HttpResponse<String> health = http.send(HttpRequest.newBuilder(base.resolve("/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> query = http.send(HttpRequest.newBuilder(base.resolve("/sparql"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(sparql.config()))).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> shutdown = http.send(HttpRequest.newBuilder(base.resolve("/shutdown"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(query.statusCode()).as(query.body()).isEqualTo(200);
            assertThat(JSON.readTree(query.body()).path("rows")).hasSize(2);
            assertThat(shutdown.statusCode()).as(shutdown.body()).isLessThan(300);
            assertThat(serve.waitFor(60, TimeUnit.SECONDS)).as("serve stops after POST /shutdown").isTrue();
            assertThat(serve.exitValue()).as(Files.readString(tempDir.resolve("serve-stderr.txt"))).isZero();
        } finally {
            serve.destroyForcibly();
        }
    }

    /** Reads serve's stdout until it says where it listens; the rest is drained in the background. */
    private static int listeningPort(Process serve) throws Exception {
        Pattern listening = Pattern.compile("listening on http://[^:]+:(\\d+)");
        BufferedReader stdout = new BufferedReader(new InputStreamReader(serve.getInputStream(), StandardCharsets.UTF_8));
        CompletableFuture<Integer> port = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = stdout.readLine()) != null) {
                    Matcher matcher = listening.matcher(line);
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            throw new IllegalStateException("serve ended without listening");
        });
        int found = port.get(2, TimeUnit.MINUTES);
        Thread drain = new Thread(() -> {
            try {
                while (stdout.readLine() != null) {
                    // keep the pipe from filling up
                }
            } catch (java.io.IOException ignored) {
                // the process ended
            }
        });
        drain.setDaemon(true);
        drain.start();
        return found;
    }
}
