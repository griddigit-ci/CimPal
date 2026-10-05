/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Resource statistics of one run (DEP-2): invariants only, never wall-clock values. */
class RunStatsTest {

    @Test
    void snapshotReportsTheJvmAndItsLimits() {
        RunStatsSnapshot s = RunStats.start().stop();

        assertThat(s.wallMs()).isGreaterThanOrEqualTo(0);
        assertThat(s.maxHeapBytes()).isEqualTo(Runtime.getRuntime().maxMemory());
        assertThat(s.availableProcessors()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(s.peakHeapBytes()).isPositive();
        assertThat(s.gcMs()).isGreaterThanOrEqualTo(0);
        assertThat(s.cpuMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(s.javaVersion()).isEqualTo(Runtime.version().toString());
        assertThat(s.os()).isNotBlank();
        assertThat(s.triplesLoaded()).isZero();
        assertThat(s.inputBytes()).isZero();
    }

    @Test
    void peakHeapCoversWhatTheRunAllocated() {
        RunStats stats = RunStats.start();
        byte[][] held = new byte[16][];
        for (int i = 0; i < held.length; i++) {
            held[i] = new byte[1 << 20];
        }
        RunStatsSnapshot s = stats.stop();

        assertThat(held[15]).hasSize(1 << 20);
        assertThat(s.peakHeapBytes()).isGreaterThanOrEqualTo(16L << 20);
    }

    @Test
    void phasesAreKeptInOrderAndAddUpToAtMostTheWallTime() throws Exception {
        RunStats stats = RunStats.start();
        try (RunStats.Phase ignored = stats.phase("load")) {
            Thread.sleep(5);
        }
        try (RunStats.Phase ignored = stats.phase("validate")) {
            Thread.sleep(5);
        }
        try (RunStats.Phase ignored = stats.phase("load")) {
            Thread.sleep(5);
        }
        RunStatsSnapshot s = stats.stop();

        assertThat(s.phasesMs()).containsOnlyKeys("load", "validate");
        assertThat(s.phasesMs().keySet()).containsExactly("load", "validate");
        long sum = s.phasesMs().values().stream().mapToLong(Long::longValue).sum();
        assertThat(sum).isLessThanOrEqualTo(s.wallMs());
    }

    @Test
    void closingAPhaseTwiceCountsItOnce() throws Exception {
        RunStats stats = RunStats.start();
        RunStats.Phase phase = stats.phase("output");
        Thread.sleep(5);
        phase.close();
        long first = stats.stop().phasesMs().get("output");
        Thread.sleep(5);
        phase.close();

        assertThat(stats.stop().phasesMs().get("output")).isEqualTo(first);
    }

    @Test
    void countersAddUpAcrossWorkerThreads() throws Exception {
        RunStats stats = RunStats.start();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        stats.addTriples(3);
                        stats.addInputBytes(10);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        RunStatsSnapshot s = stats.stop();

        assertThat(s.triplesLoaded()).isEqualTo(24_000);
        assertThat(s.inputBytes()).isEqualTo(80_000);
    }

    @Test
    void negativeCountsAreRejected() {
        RunStats stats = RunStats.start();

        assertThatThrownBy(() -> stats.addTriples(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.addInputBytes(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void peakRssIsReadFromVmHwm(@TempDir Path dir) throws IOException {
        Path status = dir.resolve("status");
        Files.writeString(status, """
                Name:\tjava
                VmPeak:\t 9000000 kB
                VmHWM:\t  123456 kB
                VmRSS:\t  100000 kB
                """, StandardCharsets.UTF_8);

        assertThat(RunStats.readPeakRss(status)).isEqualTo(123_456L * 1024);
    }

    @Test
    void peakRssIsNullWhenItCannotBeRead(@TempDir Path dir) throws IOException {
        Path garbled = dir.resolve("garbled");
        Files.writeString(garbled, "VmHWM:\tlots kB\n", StandardCharsets.UTF_8);
        Path withoutHwm = dir.resolve("without");
        Files.writeString(withoutHwm, "Name:\tjava\n", StandardCharsets.UTF_8);

        assertThat(RunStats.readPeakRss(dir.resolve("missing"))).isNull();
        assertThat(RunStats.readPeakRss(garbled)).isNull();
        assertThat(RunStats.readPeakRss(withoutHwm)).isNull();
    }
}
