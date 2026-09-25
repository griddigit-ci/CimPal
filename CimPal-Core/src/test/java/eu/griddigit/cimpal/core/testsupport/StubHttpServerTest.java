/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class StubHttpServerTest {

    @Test
    void servesRegisteredBodyAndRecordsRequests() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start()) {
            stub.serve("/shapes.ttl", TestModels.THING_SHAPES, "text/turtle");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(stub.uri("/shapes.ttl"))
                    .header("X-Probe", "1").build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> missing = client.send(HttpRequest.newBuilder(stub.uri("/other")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(stub.uri("/x").getHost()).isEqualTo("127.0.0.1");
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo(TestModels.THING_SHAPES);
            assertThat(ok.headers().firstValue("content-type")).contains("text/turtle");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(stub.requests()).extracting(StubHttpServer.Request::path)
                    .containsExactly("/shapes.ttl", "/other");
            assertThat(stub.requests().getFirst().headers()).containsKey("x-probe");
        }
    }
}
