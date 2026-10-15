/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code --client-quotas} file and the limits it gives each client (DEP-13). */
class ClientQuotasTest {

    private static final ClientQuotas.Limits SERVER = new ClientQuotas.Limits(4, 2, 1000, 5000, 0);

    @TempDir
    Path tempDir;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();

    private ClientQuotas quotas(String json) throws Exception {
        return new ClientQuotas(ClientQuotas.read(Files.writeString(tempDir.resolve("quotas.json"), json)), SERVER, clock);
    }

    @Test
    void withoutAFileEveryClientGetsTheServersLimits() {
        ClientQuotas quotas = new ClientQuotas(ClientQuotas.Settings.NONE, SERVER, clock);

        assertThat(quotas.limits("anyone")).isEqualTo(SERVER);
        assertThat(quotas.acquireRequest("anyone")).isZero();
    }

    @Test
    void aClientsEntryWinsOverDefaultWhichWinsOverTheServer() throws Exception {
        ClientQuotas quotas = quotas("""
                {"_comment": "documentation",
                 "default": {"maxQueued": 1, "requestsPerMinute": 60},
                 "clients": {"etl": {"maxQueued": 8, "maxRunning": 1, "_note": "nightly"},
                             "big": {"maxRunning": 10, "maxUploadBytes": 99999}}}
                """);

        assertThat(quotas.limits("other")).isEqualTo(new ClientQuotas.Limits(1, 2, 1000, 5000, 60));
        assertThat(quotas.limits("etl")).isEqualTo(new ClientQuotas.Limits(8, 1, 1000, 5000, 60));
        // Above the server's own limits: lowered to them.
        assertThat(quotas.limits("big")).isEqualTo(new ClientQuotas.Limits(1, 2, 1000, 5000, 60));
        assertThat(quotas.namedClients()).containsExactlyInAnyOrder("etl", "big");
    }

    @Test
    void requestsPerMinuteIsATokenBucket() throws Exception {
        ClientQuotas quotas = quotas("{\"clients\": {\"a\": {\"requestsPerMinute\": 3}}}");

        for (int i = 0; i < 3; i++) {
            assertThat(quotas.acquireRequest("a")).isZero();
        }
        assertThat(quotas.acquireRequest("a")).isEqualTo(20);
        assertThat(quotas.acquireRequest("b")).isZero();
        clock.advance(Duration.ofSeconds(20));
        assertThat(quotas.acquireRequest("a")).isZero();
        assertThat(quotas.acquireRequest("a")).isPositive();
    }

    @Test
    void anInvalidFileIsRefused() {
        for (String json : new String[] {"[]", "{\"limits\": {}}", "{\"default\": {\"maxQueue\": 1}}",
                "{\"default\": {\"maxRunning\": 0}}", "{\"default\": {\"maxQueued\": 1.5}}",
                "{\"clients\": {\"bad name\": {}}}", "{\"clients\": []}", "{\"default\": {\"maxQueued\": \"2\"}}"}) {
            assertThatThrownBy(() -> quotas(json)).as(json).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--client-quotas");
        }
        assertThatThrownBy(() -> ClientQuotas.read(tempDir.resolve("missing.json")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("file not found");
    }
}
