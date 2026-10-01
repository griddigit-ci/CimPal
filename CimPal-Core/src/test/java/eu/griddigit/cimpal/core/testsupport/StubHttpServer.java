/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local HTTP stub for tests that involve remote fetches (e.g. {@code owl:imports}). Binds to
 * {@code 127.0.0.1} on an ephemeral port, serves registered bodies (404 for anything else) and
 * records every request, so a test can assert both what was served and that nothing was fetched.
 *
 * <pre>{@code
 * try (StubHttpServer stub = StubHttpServer.start()) {
 *     stub.serve("/shapes.ttl", TestModels.THING_SHAPES, "text/turtle");
 *     URI uri = stub.uri("/shapes.ttl");
 *     ...
 *     assertEquals(0, stub.requests().size());
 * }
 * }</pre>
 */
public final class StubHttpServer implements AutoCloseable {

    /** One recorded request. Header names are lower-case. */
    public record Request(String method, String path, Map<String, List<String>> headers) {
    }

    private record Response(int status, byte[] body, String contentType) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    public static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubHttpServer stub = new StubHttpServer(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start stub HTTP server", e);
        }
    }

    public StubHttpServer serve(String path, String body, String contentType) {
        return serve(path, 200, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    public StubHttpServer serve(String path, int status, byte[] body, String contentType) {
        responses.put(path, new Response(status, body, contentType));
        return this;
    }

    /** Serves {@code fixtures/<feature>/<file>} from the test classpath at {@code path}. */
    public StubHttpServer serveFixture(String path, String feature, String file, String contentType) {
        return serve(path, 200, Fixtures.bytes(feature, file), contentType);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Absolute {@code http://127.0.0.1:<port><path>} URI. */
    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port() + path);
    }

    /** Requests received so far, in arrival order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values)));
        String path = exchange.getRequestURI().getRawPath();
        requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(headers)));

        Response response = responses.getOrDefault(path,
                new Response(404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain"));
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(response.status(), head ? -1 : response.body().length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body());
            }
        }
        exchange.close();
    }
}
