/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code /v1} job (DEP-5). Every state change is a synchronized check-and-set, so a cancel
 * and the worker starting the job can't both win; readers take a {@link #snapshot()} and never
 * wait for the worker, which doesn't hold this lock while the command runs.
 */
final class Job {

    /** A consistent view of the job for the API. */
    record Snapshot(UUID id, String command, String label, JobStatus status, Instant createdAt,
                    Instant startedAt, Instant finishedAt, Integer exitCode, String error,
                    boolean finished, boolean hasResult, boolean resultDropped) {

        /** Null until the command ended; exit 1 means violations or differences found. */
        Boolean hasViolations() {
            return exitCode == null ? null : exitCode == 1;
        }
    }

    private final UUID id;
    private final String command;
    private final String label;
    private final Instant createdAt;
    private final JobLog log;

    private JobStatus status = JobStatus.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer exitCode;
    private String result;
    private boolean resultDropped;
    private String error;

    Job(UUID id, String command, String label, Instant createdAt, int logLines) {
        this.id = id;
        this.command = command;
        this.label = label;
        this.createdAt = createdAt;
        this.log = new JobLog(logLines);
    }

    UUID id() {
        return id;
    }

    String command() {
        return command;
    }

    Instant createdAt() {
        return createdAt;
    }

    JobLog log() {
        return log;
    }

    /** Queued → running. False when it was cancelled first; then it must not run. */
    synchronized boolean start(Instant now) {
        if (status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.RUNNING;
        startedAt = now;
        return true;
    }

    /** Queued → cancelled. False when it already started or ended. */
    synchronized boolean cancel(Instant now, String reason) {
        if (status != JobStatus.QUEUED) {
            return false;
        }
        status = JobStatus.CANCELLED;
        finishedAt = now;
        error = reason;
        return true;
    }

    /** Running → timed out, when it has run longer than {@code timeout}. */
    synchronized boolean checkTimeout(Instant now, Duration timeout) {
        if (status == JobStatus.RUNNING && startedAt != null
                && Duration.between(startedAt, now).compareTo(timeout) > 0) {
            status = JobStatus.TIMED_OUT;
            error = "Running longer than --job-timeout (" + timeout + "); not interrupted, a later result is kept";
            return true;
        }
        return false;
    }

    /**
     * The command returned. Exit 0 and 1 are results ({@code succeeded}), 2 and 3 failures; a job
     * that already timed out keeps that status. A result over {@code maxResultBytes} isn't kept.
     */
    synchronized void finish(Instant now, int exitCode, String stdout, long maxResultBytes) {
        this.exitCode = exitCode;
        this.finishedAt = now;
        String body = stdout == null ? "" : stdout.trim();
        if (utf8Length(body) > maxResultBytes) {
            resultDropped = true;
            result = null;
        } else {
            result = body;
        }
        if (status == JobStatus.RUNNING) {
            status = exitCode <= 1 ? JobStatus.SUCCEEDED : JobStatus.FAILED;
            if (exitCode > 1) {
                error = exitCode == 2 ? "Bad input (exit code 2); see the result" : "Internal error (exit code " + exitCode + ")";
            }
        }
    }

    /** The command threw instead of returning an exit code. */
    synchronized void fail(Instant now, String message) {
        finishedAt = now;
        error = message;
        if (status == JobStatus.RUNNING || status == JobStatus.TIMED_OUT) {
            status = JobStatus.FAILED;
        }
    }

    /** UTF-8 length without encoding a copy of a possibly large result. */
    static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n++;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** Roughly the heap this job's result and log hold (two bytes per character, the worst case). */
    synchronized long retainedBytes() {
        return 2L * ((result == null ? 0 : result.length()) + log.chars());
    }

    /** The command's JSON output, or null when there is none (yet). */
    synchronized String result() {
        return result;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(id, command, label, status, createdAt, startedAt, finishedAt, exitCode, error,
                finishedAt != null, result != null, resultDropped);
    }
}
