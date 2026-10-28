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
 * outcomes, quotas) or a client name from the token file (DEP-13), never request input, so their
 * cardinality is bounded by the token file.
 */
final class ServeMetrics {

    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /** Upper bounds of the job duration histogram, in seconds. */
    static final double[] DURATION_BUCKETS = {1, 10, 60, 300, 900, 1800, 3600, 7200};

    /** Keyed by client and status, joined with NUL (neither can contain one). */
    private final Map<String, AtomicLong> jobsFinished = new ConcurrentHashMap<>();
    /** Keyed by client, command and outcome. */
    private final Map<String, AtomicLong> requests = new ConcurrentHashMap<>();
    /** Keyed by client and quota. */
    private final Map<String, AtomicLong> quotaRefusals = new ConcurrentHashMap<>();
    /** Keyed by outcome. */
    private final Map<String, AtomicLong> callbacks = new ConcurrentHashMap<>();
    /**
     * Keyed by status class: 2xx … 5xx, but 429 and 503 on their own (a quota or a full queue, the
     * server working as meant), or none when no answer was sent (DEP-16).
     */
    private final Map<String, AtomicLong> responses = new ConcurrentHashMap<>();

    /** Upper bounds of the queue wait histogram, in seconds (DEP-16). */
    static final double[] WAIT_BUCKETS = {1, 10, 30, 60, 120, 300, 600, 1800, 3600};
    /** Upper bounds of the request duration histogram, in seconds (DEP-16). */
    static final double[] REQUEST_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.5, 1, 2.5, 5, 10};
    /** Request kinds: the API itself, file transfers, and synchronous commands. */
    static final String[] REQUEST_KINDS = {"api", "transfer", "command"};

    private final Histogram queueWait = new Histogram(WAIT_BUCKETS);
    private final Histogram[] requestDuration = {
            new Histogram(REQUEST_BUCKETS), new Histogram(REQUEST_BUCKETS), new Histogram(REQUEST_BUCKETS)};

    /** A cumulative histogram: counts per upper bound, a count and a sum. */
    private static final class Histogram {
        final double[] bounds;
        final AtomicLongArray buckets;
        final AtomicLong count = new AtomicLong();
        final DoubleAdder sum = new DoubleAdder();

        Histogram(double[] bounds) {
            this.bounds = bounds;
            this.buckets = new AtomicLongArray(bounds.length);
        }

        void observe(double value) {
            for (int i = 0; i < bounds.length; i++) {
                if (value <= bounds[i]) {
                    buckets.incrementAndGet(i);
                }
            }
            count.incrementAndGet();
            sum.add(value);
        }

        void render(StringBuilder sb, String metric, String labels) {
            String prefix = labels.isEmpty() ? "" : labels + ",";
            for (int i = 0; i < bounds.length; i++) {
                sb.append(metric).append("_bucket{").append(prefix).append("le=\"").append(number(bounds[i]))
                        .append("\"} ").append(buckets.get(i)).append('\n');
            }
            sb.append(metric).append("_bucket{").append(prefix).append("le=\"+Inf\"} ").append(count.get()).append('\n');
            String braces = labels.isEmpty() ? "" : "{" + labels + "}";
            sb.append(metric).append("_sum").append(braces).append(' ').append(sum.sum()).append('\n');
            sb.append(metric).append("_count").append(braces).append(' ').append(count.get()).append('\n');
        }
    }
    private final AtomicLongArray durationBuckets = new AtomicLongArray(DURATION_BUCKETS.length);
    private final AtomicLong durationCount = new AtomicLong();
    private final DoubleAdder durationSum = new DoubleAdder();

    /** A job of {@code client} reached a final status after running {@code seconds} (negative: it never ran). */
    void jobFinished(String client, JobStatus status, double seconds) {
        count(jobsFinished, client, status.apiName());
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
    void request(String client, String command, String outcome) {
        count(requests, client, command, outcome);
    }

    /** A request of {@code client} was refused by its {@code quota}: max_queued or requests_per_minute. */
    void quotaRefused(String client, String quota) {
        count(quotaRefusals, client, quota);
    }

    /** A callback attempt (DEP-14) ended: delivered, retry, failed or refused. */
    void callback(String outcome) {
        count(callbacks, outcome);
    }

    /**
     * A request of {@code kind} (one of {@link #REQUEST_KINDS}) ended with HTTP {@code status}
     * after {@code seconds}; the status is negative when no answer was sent (the connection broke).
     */
    void response(String kind, int status, double seconds) {
        String code = status == 429 || status == 503 ? Integer.toString(status)
                : status >= 100 && status < 600 ? (status / 100) + "xx" : "none";
        count(responses, code);
        for (int i = 0; i < REQUEST_KINDS.length; i++) {
            if (REQUEST_KINDS[i].equals(kind)) {
                requestDuration[i].observe(seconds);
            }
        }
    }

    /** A job started after waiting {@code seconds} in the queue. */
    void jobStarted(double seconds) {
        queueWait.observe(Math.max(0, seconds));
    }

    private static void count(Map<String, AtomicLong> counters, String... labels) {
        counters.computeIfAbsent(String.join("\u0000", labels), k -> new AtomicLong()).incrementAndGet();
    }

    /** One line per counter, sorted, with {@code names} as the labels of its key. */
    private static void counters(StringBuilder sb, String metric, Map<String, AtomicLong> counters, String... names) {
        for (Map.Entry<String, AtomicLong> e : new TreeMap<>(counters).entrySet()) {
            String[] values = e.getKey().split("\u0000", names.length);
            sb.append(metric).append('{');
            for (int i = 0; i < names.length; i++) {
                sb.append(i == 0 ? "" : ",").append(names[i]).append("=\"").append(escape(values[i])).append('"');
            }
            sb.append("} ").append(e.getValue().get()).append('\n');
        }
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
    String render(String version, Map<JobStatus, Long> jobsNow, int queueDepth, int running, int workers) {
        StringBuilder sb = new StringBuilder();
        help(sb, "cimpal_info", "gauge", "CimPal version.");
        sb.append("cimpal_info{version=\"").append(escape(version)).append("\"} 1\n");

        help(sb, "cimpal_jobs", "gauge", "Jobs in the store by status.");
        for (JobStatus status : JobStatus.values()) {
            sb.append("cimpal_jobs{status=\"").append(status.apiName()).append("\"} ")
                    .append(jobsNow.getOrDefault(status, 0L)).append('\n');
        }
        help(sb, "cimpal_jobs_finished_total", "counter", "Jobs that reached a final status since start, by client.");
        counters(sb, "cimpal_jobs_finished_total", jobsFinished, "client", "status");
        help(sb, "cimpal_job_duration_seconds", "histogram", "Run time of finished jobs.");
        for (int i = 0; i < DURATION_BUCKETS.length; i++) {
            sb.append("cimpal_job_duration_seconds_bucket{le=\"").append(number(DURATION_BUCKETS[i])).append("\"} ")
                    .append(durationBuckets.get(i)).append('\n');
        }
        sb.append("cimpal_job_duration_seconds_bucket{le=\"+Inf\"} ").append(durationCount.get()).append('\n');
        sb.append("cimpal_job_duration_seconds_sum ").append(durationSum.sum()).append('\n');
        sb.append("cimpal_job_duration_seconds_count ").append(durationCount.get()).append('\n');

        help(sb, "cimpal_requests_total", "counter", "Synchronous command requests by client, command and outcome.");
        counters(sb, "cimpal_requests_total", requests, "client", "command", "outcome");
        help(sb, "cimpal_quota_refused_total", "counter", "Requests refused with 429 by a client's quota.");
        counters(sb, "cimpal_quota_refused_total", quotaRefusals, "client", "quota");
        help(sb, "cimpal_callbacks_total", "counter", "Job callback attempts by outcome.");
        counters(sb, "cimpal_callbacks_total", callbacks, "outcome");
        help(sb, "cimpal_http_responses_total", "counter",
                "HTTP answers by status class, 429 and 503 apart (none: the connection broke first).");
        counters(sb, "cimpal_http_responses_total", responses, "code");
        help(sb, "cimpal_http_request_duration_seconds", "histogram",
                "Time to answer a request, by kind: api, transfer (uploads, downloads) or command (synchronous).");
        for (int i = 0; i < REQUEST_KINDS.length; i++) {
            requestDuration[i].render(sb, "cimpal_http_request_duration_seconds", "kind=\"" + REQUEST_KINDS[i] + "\"");
        }
        help(sb, "cimpal_job_queue_wait_seconds", "histogram", "Time jobs waited in the queue before they started.");
        queueWait.render(sb, "cimpal_job_queue_wait_seconds", "");

        help(sb, "cimpal_queue_depth", "gauge", "Commands and jobs waiting for a worker.");
        sb.append("cimpal_queue_depth ").append(queueDepth).append('\n');
        help(sb, "cimpal_busy", "gauge", "1 while a command runs.");
        sb.append("cimpal_busy ").append(running > 0 ? 1 : 0).append('\n');
        help(sb, "cimpal_running", "gauge", "Commands running now.");
        sb.append("cimpal_running ").append(running).append('\n');
        help(sb, "cimpal_workers", "gauge", "Commands that may run at once (--workers).");
        sb.append("cimpal_workers ").append(workers).append('\n');

        Runtime rt = Runtime.getRuntime();
        help(sb, "cimpal_jvm_heap_used_bytes", "gauge", "Heap in use.");
        sb.append("cimpal_jvm_heap_used_bytes ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        help(sb, "cimpal_jvm_heap_max_bytes", "gauge", "Largest heap the JVM will use.");
        sb.append("cimpal_jvm_heap_max_bytes ").append(rt.maxMemory()).append('\n');
        return sb.toString();
    }

    /** The job store's failures (DEP-11); always 0 for the in-memory store. */
    static String storeErrors(long errors) {
        StringBuilder sb = new StringBuilder();
        help(sb, "cimpal_job_store_errors_total", "counter", "Failed job store operations since start.");
        return sb.append("cimpal_job_store_errors_total ").append(errors).append('\n').toString();
    }

    /**
     * The gauges the alert rules compare against (DEP-16): the queue's size, whether a command
     * has run past its timeout, and the work area's use; {@code workMaxBytes} 0 means no work area.
     */
    static String capacity(int queueSize, boolean stalled, long workUsedBytes, long workMaxBytes) {
        StringBuilder sb = new StringBuilder();
        help(sb, "cimpal_queue_size", "gauge", "Commands and jobs that may wait for a worker (--queue-size).");
        sb.append("cimpal_queue_size ").append(queueSize).append('\n');
        help(sb, "cimpal_stalled", "gauge", "1 while a running command has exceeded its timeout.");
        sb.append("cimpal_stalled ").append(stalled ? 1 : 0).append('\n');
        if (workMaxBytes > 0) {
            help(sb, "cimpal_work_used_bytes", "gauge", "Disk space used by uploads and job outputs.");
            sb.append("cimpal_work_used_bytes ").append(workUsedBytes).append('\n');
            help(sb, "cimpal_work_max_bytes", "gauge", "Disk space allowed for uploads and job outputs (--work-max-bytes).");
            sb.append("cimpal_work_max_bytes ").append(workMaxBytes).append('\n');
        }
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
