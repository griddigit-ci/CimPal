/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The key store itself (DEP-14): reservations, take-over and their guards, in memory and in a database. */
@ParameterizedClass
@EnumSource(TestStores.class)
class IdempotencyStoreTest {

    @Parameter
    TestStores store;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private JobStore jobs;

    private IdempotencyStore keys() {
        if (jobs == null) {
            jobs = store == TestStores.MEMORY ? new MemoryJobStore(1000, java.time.Duration.ofHours(1),
                    java.time.Duration.ofHours(1), 10, Long.MAX_VALUE, clock)
                    : TestStores.h2(ServeServer.Config.builder().token("t".repeat(40)).build(), clock);
        }
        return jobs.idempotency();
    }

    @AfterEach
    void close() {
        if (jobs != null) {
            jobs.close();
        }
    }

    @Test
    void aReservationIsOnlyChangedByItsOwnRequest() {
        IdempotencyStore keys = keys();
        Instant now = clock.instant();
        UUID first = UUID.randomUUID();
        assertThat(keys.begin("a", "k", "fp", first, now)).isNull();
        IdempotencyStore.Entry seen = keys.begin("a", "k", "fp", UUID.randomUUID(), now);

        // A second request takes the key over (as after STALE); the first one's late answers must not touch it.
        UUID second = UUID.randomUUID();
        assertThat(keys.retake("a", "k", seen, "fp", second, now)).isTrue();
        assertThat(keys.retake("a", "k", seen, "fp", UUID.randomUUID(), now)).as("only once").isFalse();
        keys.complete("a", "k", first);
        keys.abandon("a", "k", first);

        IdempotencyStore.Entry now2 = keys.begin("a", "k", "fp", UUID.randomUUID(), now);
        assertThat(now2.resourceId()).isEqualTo(second);
        assertThat(now2.done()).isFalse();
        keys.complete("a", "k", second);
        assertThat(keys.begin("a", "k", "fp", UUID.randomUUID(), now).done()).isTrue();
    }

    @Test
    void keysArePerClient() {
        IdempotencyStore keys = keys();
        Instant now = clock.instant();
        assertThat(keys.begin("a", "k", "fp", UUID.randomUUID(), now)).isNull();
        assertThat(keys.begin("b", "k", "other", UUID.randomUUID(), now)).isNull();
    }

    @Test
    void oneClientFillingTheMemoryStoreDoesNotPushOutAnother() {
        org.junit.jupiter.api.Assumptions.assumeTrue(store == TestStores.MEMORY, "the cap is the memory store's");
        IdempotencyStore keys = keys();
        Instant now = clock.instant();
        UUID kept = UUID.randomUUID();
        keys.begin("b", "important", "fp", kept, now);
        keys.complete("b", "important", kept);
        for (int i = 0; i < IdempotencyStore.Memory.PER_CLIENT + 50; i++) {
            UUID id = UUID.randomUUID();
            keys.begin("a", "k" + i, "fp", id, now);
            keys.complete("a", "k" + i, id);
        }

        assertThat(keys.begin("b", "important", "fp", UUID.randomUUID(), now).resourceId()).isEqualTo(kept);
        assertThat(((IdempotencyStore.Memory) keys).size()).isLessThanOrEqualTo(IdempotencyStore.Memory.PER_CLIENT + 1);
    }

    @Test
    void aClientWhoseKeysAreAllUnfinishedIsRefusedNotAnother() {
        org.junit.jupiter.api.Assumptions.assumeTrue(store == TestStores.MEMORY, "the cap is the memory store's");
        IdempotencyStore keys = keys();
        Instant now = clock.instant();
        for (int i = 0; i < IdempotencyStore.Memory.PER_CLIENT; i++) {
            keys.begin("a", "k" + i, "fp", UUID.randomUUID(), now);
        }
        assertThatThrownBy(() -> keys.begin("a", "one-more", "fp", UUID.randomUUID(), now))
                .isInstanceOf(JobStore.Unavailable.class);
        assertThat(keys.begin("b", "k", "fp", UUID.randomUUID(), now)).isNull();
    }
}
