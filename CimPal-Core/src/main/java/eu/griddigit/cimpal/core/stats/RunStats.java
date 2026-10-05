/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.stats;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects the resource use of one run (DEP-2, {@code --stats}): wall time per phase, process CPU
 * time, peak heap, GC time, peak RSS, and the triples and bytes loaded. Instances are thread-safe,
 * so validation workers can add to the counters in parallel.
 *
 * <p>{@link #start()} resets the peak usage of the JVM's heap pools, which is process-wide state:
 * two runs measured at the same time in one JVM would see each other's peaks. The CLI runs one
 * command per JVM, and {@code serve} one at a time.
 */
public final class RunStats {

    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    private final long startNanos;
    private final Long startCpuNanos;
    private final long startGcMs;
    private final Map<String, Long> phaseNanos = new LinkedHashMap<>();
    private final LongAdder triples = new LongAdder();
    private final LongAdder inputBytes = new LongAdder();

    private RunStats() {
        for (MemoryPoolMXBean pool : heapPools()) {
            pool.resetPeakUsage();
        }
        startGcMs = gcMillis();
        startCpuNanos = processCpuNanos();
        startNanos = System.nanoTime();
    }

    /** Starts measuring now. */
    public static RunStats start() {
        return new RunStats();
    }

    /**
     * Opens a named phase; closing it adds its wall time to that phase. A name used again adds to
     * the same entry. Use with try-with-resources.
     */
    public Phase phase(String name) {
        return new Phase(name, System.nanoTime());
    }

    /** Adds triples parsed from instance files. */
    public void addTriples(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("Triple count must not be negative: " + count);
        }
        triples.add(count);
    }

    /** Adds bytes of instance files read. */
    public void addInputBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Byte count must not be negative: " + bytes);
        }
        inputBytes.add(bytes);
    }

    /** Takes a snapshot of the run so far. Can be called more than once. */
    public RunStatsSnapshot stop() {
        long wallNanos = System.nanoTime() - startNanos;
        Long cpuNanos = processCpuNanos();
        long peakHeap = 0;
        for (MemoryPoolMXBean pool : heapPools()) {
            if (pool.getPeakUsage() != null) {
                peakHeap += pool.getPeakUsage().getUsed();
            }
        }
        Map<String, Long> phasesMs = new LinkedHashMap<>();
        synchronized (phaseNanos) {
            phaseNanos.forEach((name, nanos) -> phasesMs.put(name, nanos / 1_000_000));
        }
        return new RunStatsSnapshot(
                wallNanos / 1_000_000,
                phasesMs,
                cpuNanos != null && startCpuNanos != null ? (cpuNanos - startCpuNanos) / 1_000_000 : null,
                peakHeap,
                Runtime.getRuntime().maxMemory(),
                readPeakRss(PROC_STATUS),
                Math.max(0, gcMillis() - startGcMs),
                Runtime.getRuntime().availableProcessors(),
                inputBytes.sum(),
                triples.sum(),
                Runtime.version().toString(),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"));
    }

    /**
     * Peak resident set size from a Linux {@code /proc/<pid>/status} file ({@code VmHWM}, in kB),
     * or {@code null} where there is no such file or it can't be parsed.
     */
    static Long readPeakRss(Path statusFile) {
        if (!Files.isRegularFile(statusFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmHWM:")) {
                    String[] parts = line.substring("VmHWM:".length()).trim().split("\\s+");
                    if (parts.length == 2 && parts[1].equals("kB")) {
                        return Long.parseLong(parts[0]) * 1024;
                    }
                    return null;
                }
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    private static Long processCpuNanos() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long nanos = sun.getProcessCpuTime();
            return nanos >= 0 ? nanos : null;
        }
        return null;
    }

    /** A running phase; see {@link #phase(String)}. */
    public final class Phase implements AutoCloseable {
        private final String name;
        private final long startedNanos;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Phase(String name, long startedNanos) {
            this.name = name;
            this.startedNanos = startedNanos;
            synchronized (phaseNanos) {
                phaseNanos.putIfAbsent(name, 0L);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                long elapsed = System.nanoTime() - startedNanos;
                synchronized (phaseNanos) {
                    phaseNanos.merge(name, elapsed, Long::sum);
                }
            }
        }
    }
}
