/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.InstantSource;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The log of {@code serve} (DEP-6): {@code --log-format text} keeps the familiar
 * {@code [WARN] serve: ...} lines on stderr; {@code json} writes one JSON object per line.
 *
 * <p>JSON fields: {@code ts} (ISO-8601 UTC), {@code level} ({@code info}, {@code warn},
 * {@code error}), {@code event}, {@code message}, and where they apply {@code jobId},
 * {@code command}, {@code status}, {@code exitCode}, {@code durationMs}, {@code remote},
 * {@code method}, {@code path}. Every string passes through {@link LogSanitizer#forLog(String)}.
 * Callers never pass a token or an {@code Authorization} header.
 *
 * <p>In JSON mode everything else written to stderr (the commands' {@code [INFO]} lines, library
 * warnings) is wrapped by {@link #stderr(Supplier)} into {@code "event":"output"} objects, so the
 * stream stays one JSON object per line. Events at level info are only written in JSON mode, so
 * the text output stays as it was.
 */
final class ServeLog {

    enum Format { TEXT, JSON }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Format format;
    /** JSON mode: where the lines go, bypassing a wrapped {@code System.err}. */
    private final PrintStream out;
    private final InstantSource clock;

    private ServeLog(Format format, PrintStream out, InstantSource clock) {
        this.format = format;
        this.out = out;
        this.clock = clock;
    }

    /** Text lines on the current {@code System.err}, as before DEP-6. */
    static ServeLog text() {
        return new ServeLog(Format.TEXT, null, InstantSource.system());
    }

    /** JSON lines on {@code out}. */
    static ServeLog json(PrintStream out, InstantSource clock) {
        return new ServeLog(Format.JSON, out, clock);
    }

    Format format() {
        return format;
    }

    /** Written only in JSON mode; the text log has no info events. */
    void info(String event, String message, Object... fields) {
        if (format == Format.JSON) {
            write("info", event, message, fields);
        }
    }

    void warn(String event, String message, Object... fields) {
        write("warn", event, message, fields);
    }

    void error(String event, String message, Object... fields) {
        write("error", event, message, fields);
    }

    /**
     * {@code fields} are name/value pairs; null values are left out.
     */
    private void write(String level, String event, String message, Object... fields) {
        if (format == Format.TEXT) {
            System.err.println("[" + level.toUpperCase(Locale.ROOT) + "] " + message);
            return;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("ts", clock.instant().toString());
        node.put("level", level);
        node.put("event", event);
        node.put("message", LogSanitizer.forLog(message));
        for (int i = 0; i + 1 < fields.length; i += 2) {
            Object value = fields[i + 1];
            String name = String.valueOf(fields[i]);
            if (value == null) {
                continue;
            }
            if (value instanceof Integer n) {
                node.put(name, n);
            } else if (value instanceof Long n) {
                node.put(name, n);
            } else if (value instanceof Boolean b) {
                node.put(name, b);
            } else {
                node.put(name, LogSanitizer.forLog(String.valueOf(value)));
            }
        }
        line(JSON.writeValueAsString(node));
    }

    private void line(String json) {
        synchronized (out) {
            out.println(json);
            out.flush();
        }
    }

    /**
     * JSON mode: a stream to install as {@code System.err} that turns each line into an
     * {@code output} event. {@code jobId} names the job running now (null when none), for lines the
     * command writes. Lines are assembled per thread, at most {@value #MAX_LINE_BYTES} bytes each.
     */
    PrintStream stderr(Supplier<String> jobId) {
        if (format != Format.JSON) {
            throw new IllegalStateException("only in JSON mode");
        }
        LineWrapper wrapper = new LineWrapper(jobId);
        this.wrapper = wrapper;
        return new PrintStream(wrapper, true, StandardCharsets.UTF_8);
    }

    /** JSON mode, at the end: writes lines still missing their newline. */
    void flushOutput() {
        LineWrapper w = wrapper;
        if (w != null) {
            w.emitPartialLines();
        }
    }

    private volatile LineWrapper wrapper;

    /**
     * The level of a command's line, from its start only: {@code [WARN] }/{@code [ERROR] } (CimPal)
     * or {@code [thread] WARN }/{@code ERROR } (library loggers). Words later in the line may come
     * from the input and must not raise it.
     */
    private static final Pattern LEVEL = Pattern.compile("^(?:\\[(WARN|ERROR)]|\\[[^\\]]{1,64}] (WARN|ERROR) )");

    static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_THREADS = 256;

    private final class LineWrapper extends OutputStream {
        private final Supplier<String> jobId;
        private final Map<Thread, ByteArrayOutputStream> partial = new ConcurrentHashMap<>();

        LineWrapper(Supplier<String> jobId) {
            this.jobId = jobId;
        }

        @Override
        public void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            Thread thread = Thread.currentThread();
            ByteArrayOutputStream line = partial.get(thread);
            if (line == null) {
                if (partial.size() >= MAX_THREADS) {
                    partial.keySet().removeIf(t -> !t.isAlive());
                    if (partial.size() >= MAX_THREADS) {
                        ServeLog.this.write("info", "output", new String(b, off, len, StandardCharsets.UTF_8).strip(),
                                "jobId", attribution());
                        return; // Too many threads mid-line at once: written as it comes.
                    }
                }
                line = partial.computeIfAbsent(thread, t -> new ByteArrayOutputStream());
            }
            synchronized (line) {
                for (int i = off; i < off + len; i++) {
                    if (b[i] == '\n') {
                        emit(line);
                    } else if (b[i] != '\r' && line.size() < MAX_LINE_BYTES) {
                        line.write(b[i]);
                    }
                }
            }
        }

        private void emit(ByteArrayOutputStream bytes) {
            String text = bytes.toString(StandardCharsets.UTF_8);
            bytes.reset();
            Matcher m = LEVEL.matcher(text);
            String level = !m.find() ? "info"
                    : "WARN".equals(m.group(1)) || "WARN".equals(m.group(2)) ? "warn" : "error";
            ServeLog.this.write(level, "output", text, "jobId", attribution());
        }

        /** The running job, for lines of the command's threads; never for the server's own. */
        private String attribution() {
            return StderrTee.isServerThread(Thread.currentThread().getName()) ? null : jobId.get();
        }

        void emitPartialLines() {
            for (ByteArrayOutputStream line : partial.values()) {
                synchronized (line) {
                    if (line.size() > 0) {
                        emit(line);
                    }
                }
            }
        }
    }
}
