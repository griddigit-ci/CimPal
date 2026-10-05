/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.stats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resource use of one run, as taken by {@link RunStats#stop()}. Fields that the platform can't
 * provide are {@code null}.
 *
 * @param wallMs              wall time since {@link RunStats#start()}
 * @param phasesMs            wall time per named phase, in the order the phases were first opened
 * @param cpuMs               CPU time of the whole process during the run (all threads); CPU ÷ wall
 *                            is about the number of cores actually used
 * @param peakHeapBytes       sum of each heap pool's own peak usage during the run. The pools peak
 *                            at different moments, so this is an upper bound and can exceed
 *                            {@code maxHeapBytes}; the smallest heap that works is measured by
 *                            running with a given {@code -Xmx} (scripts/bench)
 * @param maxHeapBytes        the heap limit the run had ({@code Runtime.maxMemory()})
 * @param peakRssBytes        peak resident set size of the process (Linux {@code VmHWM}); a
 *                            process-lifetime peak, so it includes start-up
 * @param gcMs                time spent in garbage collection during the run
 * @param availableProcessors processors the JVM may use (honours container limits)
 * @param inputBytes          bytes of instance files read
 * @param triplesLoaded       triples parsed from instance files
 * @param javaVersion         {@code Runtime.version()}
 * @param os                  operating system name and architecture
 */
public record RunStatsSnapshot(
        long wallMs,
        Map<String, Long> phasesMs,
        Long cpuMs,
        long peakHeapBytes,
        long maxHeapBytes,
        Long peakRssBytes,
        long gcMs,
        int availableProcessors,
        long inputBytes,
        long triplesLoaded,
        String javaVersion,
        String os) {

    public RunStatsSnapshot {
        // A copy that keeps the order (Map.copyOf would not).
        phasesMs = Collections.unmodifiableMap(new LinkedHashMap<>(phasesMs));
    }
}
