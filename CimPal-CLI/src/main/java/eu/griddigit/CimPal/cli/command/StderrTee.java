/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies what a running {@code /v1} job writes to {@code System.err} into the job's
 * {@link JobLog} (DEP-5), while everything still reaches the real stderr as before.
 *
 * <p>{@code System.err} is process-wide, so this is only sound because {@code serve} runs one
 * command at a time (its single worker; the process-wide {@code PathPolicy} requires that
 * anyway). Lines are assembled per thread, because a validation writes from its worker pools;
 * lines from the server's own HTTP threads ({@value #SERVER_THREAD_PREFIX}*, the dispatcher, the
 * stopper) stay out of the job's log. Each line passes through {@link LogSanitizer#forLog(String)}
 * before it is stored, so it is at most {@value LogSanitizer#MAX_LENGTH} characters.
 */
final class StderrTee extends OutputStream {

    static final String SERVER_THREAD_PREFIX = "cimpal-serve-http";
    /** The server's other threads: the HTTP dispatcher and the stopper of POST /shutdown. */
    private static final List<String> OTHER_SERVER_THREADS = List.of("HTTP-Dispatcher", "cimpal-serve-stop",
            "cimpal-serve-shutdown", "SIGTERM handler");
    /** Threads with an unfinished line at once; output of further threads still reaches stderr. */
    static final int MAX_THREADS = 256;

    /** Restores {@code System.err} and flushes partial lines into the log. */
    interface Capture extends AutoCloseable {
        @Override
        void close();
    }

    private final PrintStream original;
    private final JobLog log;
    private final Map<Thread, ByteArrayOutputStream> partial = new ConcurrentHashMap<>();
    /** Set on close: a stream saved while the tee was installed must not feed this job's log afterwards. */
    private volatile boolean closed;

    private StderrTee(PrintStream original, JobLog log) {
        this.original = original;
        this.log = log;
    }

    /** Starts copying {@code System.err} into {@code log}, until the returned capture is closed. */
    static Capture capture(JobLog log) {
        PrintStream original = System.err;
        StderrTee tee = new StderrTee(original, log);
        PrintStream stream = new PrintStream(tee, true, StandardCharsets.UTF_8);
        System.setErr(stream);
        return () -> {
            stream.flush();
            if (System.err == stream) {
                System.setErr(original);
            }
            tee.closed = true;
            tee.flushPartialLines();
        };
    }

    @Override
    public void write(int b) {
        original.write(b);
        collect(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        original.write(b, off, len);
        collect(b, off, len);
    }

    @Override
    public void flush() {
        original.flush();
    }

    /** A thread of the server itself (HTTP, dispatcher, stop, signal), not of a running command. */
    static boolean isServerThread(String name) {
        return name.startsWith(SERVER_THREAD_PREFIX) || OTHER_SERVER_THREADS.stream().anyMatch(name::startsWith);
    }

    private void collect(byte[] b, int off, int len) {
        if (closed) {
            return;
        }
        Thread thread = Thread.currentThread();
        String name = thread.getName();
        if (isServerThread(name)) {
            return;
        }
        ByteArrayOutputStream line = partial.get(thread);
        if (line == null) {
            if (partial.size() >= MAX_THREADS) {
                return;
            }
            line = partial.computeIfAbsent(thread, t -> new ByteArrayOutputStream());
        }
        synchronized (line) {
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    store(line);
                } else if (b[i] != '\r' && line.size() <= JobLog.MAX_LINE_CHARS * 4) {
                    line.write(b[i]);
                }
            }
        }
    }

    private void store(ByteArrayOutputStream line) {
        log.append(LogSanitizer.forLog(line.toString(StandardCharsets.UTF_8)));
        line.reset();
    }

    private void flushPartialLines() {
        for (ByteArrayOutputStream line : partial.values()) {
            synchronized (line) {
                if (line.size() > 0) {
                    store(line);
                }
            }
        }
        partial.clear();
    }
}
