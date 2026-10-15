/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which finished jobs the store drops when it is full (DEP-13: one client can't push out another's). */
class JobManagerTest {

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();

    private Job finishedJob(JobManager jobs, String owner, String result) {
        Job job = jobs.create(UUID.randomUUID(), "validate", null, owner);
        job.start(clock.instant());
        clock.advance(Duration.ofSeconds(1));
        job.finish(clock.instant(), 0, result, Long.MAX_VALUE);
        return job;
    }

    private static List<String> owners(JobManager jobs) {
        List<String> owners = new ArrayList<>();
        jobs.list(null, null, 1000).forEach(job -> owners.add(job.owner()));
        return owners;
    }

    @Test
    void beyondMaxJobsTheClientWithTheMostJobsLosesItsOldestFirst() {
        JobManager jobs = new JobManager(3, Duration.ofDays(1), Duration.ofHours(1), 10, Long.MAX_VALUE, clock);
        Job bobs = finishedJob(jobs, "bob", "{}");
        List<Job> alices = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            alices.add(finishedJob(jobs, "alice", "{}"));
        }

        jobs.evict();

        assertThat(jobs.get(bobs.id())).as("bob's older job survives alice's flood").isNotNull();
        assertThat(owners(jobs)).containsExactlyInAnyOrder("bob", "alice", "alice");
        assertThat(jobs.get(alices.get(4).id())).isNotNull();
        assertThat(jobs.get(alices.get(0).id())).isNull();
    }

    @Test
    void beyondTheStoreBytesTheClientHoldingTheMostLosesItsOldestFirst() {
        String large = "x".repeat(10_000);
        JobManager jobs = new JobManager(1000, Duration.ofDays(1), Duration.ofHours(1), 10, 50_000, clock);
        Job bobs = finishedJob(jobs, "bob", large);
        for (int i = 0; i < 3; i++) {
            finishedJob(jobs, "alice", large);
        }

        jobs.evict(); // 4 × 20 kB held, 50 kB allowed

        assertThat(jobs.get(bobs.id())).isNotNull();
        assertThat(owners(jobs)).containsExactlyInAnyOrder("bob", "alice");
    }

    @Test
    void withOneClientTheOldestGoFirstAndTheNewestIsKept() {
        JobManager jobs = new JobManager(1000, Duration.ofDays(1), Duration.ofHours(1), 10, 1, clock);
        finishedJob(jobs, "default", "{\"a\":1}");
        Job newest = finishedJob(jobs, "default", "{\"a\":2}");

        jobs.evict();

        assertThat(jobs.size()).isEqualTo(1);
        assertThat(jobs.get(newest.id())).isNotNull();
    }
}
