/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * The counters behind {@code GET /metrics} (DEP-6, {@code --metrics}), written as Prometheus text
 * format 0.0.4 by hand. Labels only ever hold values from fixed sets (job statuses, command names,
 * outcomes), never request input.
 */
final class ServeMetrics {

    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /** Upper bounds of the job duration histogram, in seconds. */
    static final double[] DURATION_BUCKETS = {1, 10, 60, 300, 900, 1800, 3600, 7200};

    private final Map<JobStatus, AtomicLong> jobsFinished = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requests = new ConcurrentHashMap<>();
    private final AtomicLongArray durationBuckets = new AtomicLongArray(DURATION_BUCKETS.length);
    private final AtomicLong durationCount = new AtomicLong();
    private final DoubleAdder durationSum = new DoubleAdder();

    /** A job reached a final status after running {@code seconds} (negative: it never ran). */
    void jobFinished(JobStatus status, double seconds) {
        jobsFinished.computeIfAbsent(status, s -> new AtomicLong()).incrementAndGet();
        if (seconds >= 0) {
            for (int i = 0; i < DURATION_BUCKETS.length; i++) {
                if (seconds <= DURATION_BUCKETS[i]) {
                    durationBuckets.incrementAndGet(i);
                }
            }
            durationCount.incrementAndGet();
            durationSum.add(seconds);
        }
    }

    /** A synchronous command request ended: {@code outcome} is ok, violations, bad_input, error, timeout or busy. */
    void request(String command, String outcome) {
        requests.computeIfAbsent(command + "\u0000" + outcome, k -> new AtomicLong()).incrementAndGet();
    }

    /** Synchronous outcome label for an exit code. */
    static String outcome(int exitCode) {
        return switch (exitCode) {
            case 0 -> "ok";
            case 1 -> "violations";
            case 2 -> "bad_input";
            default -> "error";
        };
    }

    /**
     * The exposition text. {@code jobsNow} counts the stored jobs by status; the gauges are read
     * when this is called.
     */
    String render(String version, Map<JobStatus, Long> jobsNow, int queueDepth, boolean busy) {
        StringBuilder sb = new StringBuilder();
        help(sb, "cimpal_info", "gauge", "CimPal version.");
        sb.append("cimpal_info{version=\"").append(escape(version)).append("\"} 1\n");

        help(sb, "cimpal_jobs", "gauge", "Jobs in the store by status.");
        for (JobStatus status : JobStatus.values()) {
            sb.append("cimpal_jobs{status=\"").append(status.apiName()).append("\"} ")
                    .append(jobsNow.getOrDefault(status, 0L)).append('\n');
        }
        help(sb, "cimpal_jobs_finished_total", "counter", "Jobs that reached a final status since start.");
        for (JobStatus status : JobStatus.values()) {
            if (status == JobStatus.QUEUED || status == JobStatus.RUNNING) {
                continue;
            }
            AtomicLong n = jobsFinished.get(status);
            sb.append("cimpal_jobs_finished_total{status=\"").append(status.apiName()).append("\"} ")
                    .append(n == null ? 0 : n.get()).append('\n');
        }
        help(sb, "cimpal_job_duration_seconds", "histogram", "Run time of finished jobs.");
        for (int i = 0; i < DURATION_BUCKETS.length; i++) {
            sb.append("cimpal_job_duration_seconds_bucket{le=\"").append(number(DURATION_BUCKETS[i])).append("\"} ")
                    .append(durationBuckets.get(i)).append('\n');
        }
        sb.append("cimpal_job_duration_seconds_bucket{le=\"+Inf\"} ").append(durationCount.get()).append('\n');
        sb.append("cimpal_job_duration_seconds_sum ").append(durationSum.sum()).append('\n');
        sb.append("cimpal_job_duration_seconds_count ").append(durationCount.get()).append('\n');

        help(sb, "cimpal_requests_total", "counter", "Synchronous command requests by command and outcome.");
        for (Map.Entry<String, AtomicLong> e : new TreeMap<>(requests).entrySet()) {
            String[] parts = e.getKey().split("\u0000", 2);
            sb.append("cimpal_requests_total{command=\"").append(escape(parts[0])).append("\",outcome=\"")
                    .append(escape(parts[1])).append("\"} ").append(e.getValue().get()).append('\n');
        }

        help(sb, "cimpal_queue_depth", "gauge", "Commands and jobs waiting for the worker.");
        sb.append("cimpal_queue_depth ").append(queueDepth).append('\n');
        help(sb, "cimpal_busy", "gauge", "1 while a command runs.");
        sb.append("cimpal_busy ").append(busy ? 1 : 0).append('\n');

        Runtime rt = Runtime.getRuntime();
        help(sb, "cimpal_jvm_heap_used_bytes", "gauge", "Heap in use.");
        sb.append("cimpal_jvm_heap_used_bytes ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        help(sb, "cimpal_jvm_heap_max_bytes", "gauge", "Largest heap the JVM will use.");
        sb.append("cimpal_jvm_heap_max_bytes ").append(rt.maxMemory()).append('\n');
        return sb.toString();
    }

    private static void help(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String number(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /** Label value escaping: backslash, double quote and newline. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
