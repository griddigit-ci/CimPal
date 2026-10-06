/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the JDK honours the request-read limit {@code serve} relies on (SEC-1, slow-client
 * defence), and that the limit doesn't cut off a command that runs longer than it.
 *
 * <p>Runs only in its own surefire execution ({@code serve-read-timeout} in the CLI pom), started
 * with {@code -Dsun.net.httpserver.maxReqTime=2}: the JDK reads that setting once per JVM.
 */
@EnabledIfSystemProperty(named = ServeServer.MAX_REQ_TIME_PROPERTY, matches = "2")
class ServeServerReadTimeoutTest {

    private static final String TOKEN = "test-token-0123456789abcdef0123456789abcdef";

    private ServeServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private ServeServer start(ServeServer.CommandRunner runner) throws Exception {
        server = ServeServer.start(ServeServer.Config.builder()
                .host("127.0.0.1").port(0).token(TOKEN).requestTimeout(Duration.ofSeconds(30)).build(), runner);
        return server;
    }

    @Test
    void stalledConnectionIsClosedAfterTheReadLimit() throws Exception {
        start((command, file) -> new ServeServer.CommandResult(0, "{}"));
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: 127.0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(15_000);
            long startNanos = System.nanoTime();
            InputStream in = socket.getInputStream();
            int read;
            try {
                read = in.read();
            } catch (SocketTimeoutException e) {
                throw new AssertionError("stalled connection was still open after 15 s");
            } catch (java.io.IOException e) {
                read = -1; // reset by the server also counts as closed
            }
            long seconds = Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();

            assertThat(read).as("server closed the connection").isEqualTo(-1);
            assertThat(seconds).isLessThan(15);
        }
    }

    @Test
    void commandRunningLongerThanTheReadLimitStillGetsItsResponse() throws Exception {
        start((command, file) -> {
            Thread.sleep(4_000); // twice the 2 s read limit
            return new ServeServer.CommandResult(0, "{\"done\":true}");
        });

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/validate"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"done\":true}");
    }
}
