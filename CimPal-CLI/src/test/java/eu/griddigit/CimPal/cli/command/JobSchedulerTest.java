/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The order in which queued jobs run when clients have a {@code maxRunning} (DEP-13). */
class JobSchedulerTest {

    private static final byte[] BODY = "{}".getBytes();

    private int next = 0;

    private Job job(String owner) {
        return new Job(UUID.randomUUID(), "validate", null, owner, Instant.EPOCH.plusSeconds(next++), 10);
    }

    @Test
    void withoutLimitsItIsFirstComeFirstServed() {
        JobScheduler scheduler = new JobScheduler(owner -> 64);
        Job a1 = job("a");
        Job b1 = job("b");
        Job a2 = job("a");
        scheduler.add(a1, BODY);
        scheduler.add(b1, BODY);
        scheduler.add(a2, BODY);

        assertThat(scheduler.next().job()).isSameAs(a1);
        assertThat(scheduler.next().job()).isSameAs(b1);
        assertThat(scheduler.next().job()).isSameAs(a2);
        assertThat(scheduler.next()).isNull();
        assertThat(scheduler.parkedForTest()).isZero();
    }

    @Test
    void aClientAtItsLimitIsOvertakenAndAParkedTurnWakesWhenItsJobEnds() {
        JobScheduler scheduler = new JobScheduler(Map.of("a", 1, "b", 1)::get);
        Job a1 = job("a");
        Job a2 = job("a");
        Job a3 = job("a");
        Job b1 = job("b");
        for (Job job : new Job[] {a1, a2, a3, b1}) {
            scheduler.add(job, BODY);
        }

        assertThat(scheduler.next().job()).isSameAs(a1);
        assertThat(scheduler.next().job()).isSameAs(b1); // a2 and a3 wait for a1
        assertThat(scheduler.next()).isNull(); // a turn parks
        assertThat(scheduler.next()).isNull();
        assertThat(scheduler.parkedForTest()).isEqualTo(2);
        assertThat(scheduler.unfinished("a")).isEqualTo(3);

        assertThat(scheduler.finished("b")).isFalse(); // nothing of b's waits; a is still at its limit
        assertThat(scheduler.finished("a")).isTrue(); // one parked turn may run a2 now
        assertThat(scheduler.parkedForTest()).isEqualTo(1);
        assertThat(scheduler.next().job()).isSameAs(a2);
        assertThat(scheduler.finished("a")).isTrue();
        assertThat(scheduler.next().job()).isSameAs(a3);
        assertThat(scheduler.unfinished("a")).isEqualTo(1);
    }

    @Test
    void cancellingTheLastQueuedJobClearsParkedTurns() {
        JobScheduler scheduler = new JobScheduler(owner -> 1);
        Job a1 = job("a");
        Job a2 = job("a");
        scheduler.add(a1, BODY);
        scheduler.add(a2, BODY);
        scheduler.next();
        assertThat(scheduler.next()).isNull();

        assertThat(scheduler.remove(a2.id())).isTrue();
        assertThat(scheduler.remove(a2.id())).isFalse();
        assertThat(scheduler.parkedForTest()).isZero();
        assertThat(scheduler.finished("a")).isFalse();
        assertThat(scheduler.unfinished("a")).isZero();
    }
}
