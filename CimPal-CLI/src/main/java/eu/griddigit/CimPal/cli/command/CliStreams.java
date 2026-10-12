/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Per-thread {@code System.out}/{@code System.err} (DEP-8). The commands write to
 * {@code System.out} and {@code System.err} directly (and so does Core), and {@code serve} used to
 * capture that by swapping the process-wide streams, which only works while one command runs at a
 * time. Instead, {@link #install()} puts a routing stream in place of each, once, and
 * {@link #bind} directs the current thread, and every thread it starts, to its own streams until
 * the action returns. A thread without a binding, or one whose binding has ended (a pool thread
 * left over from a finished run), writes to the stream that was installed before.
 *
 * <p>Each call on a routing stream ({@code println}, {@code write}, ...) goes to one target as a
 * whole, so lines of different jobs don't interleave within a call.
 */
final class CliStreams {

    private CliStreams() {
    }

    /** One thread's streams, inherited by the threads it starts; closed when its action returns. */
    private static final class Binding {
        final PrintStream out;
        final PrintStream err;
        final String label;
        volatile boolean closed;

        Binding(PrintStream out, PrintStream err, String label) {
            this.out = out;
            this.err = err;
            this.label = label;
        }
    }

    private static final InheritableThreadLocal<Binding> BINDING = new InheritableThreadLocal<>();

    /**
     * Puts routing streams in place of {@code System.out} and {@code System.err}, wrapping the
     * current ones as the defaults. Does nothing for a stream that already routes, so a test (or
     * {@code serve --log-format json}) that set its own stream gets it wrapped on the next call.
     */
    static synchronized void install() {
        // By role, not only by type: after System.setOut(System.err) (mcp does that), System.out is
        // the routing stream for stderr, and a bound thread's stdout would go to its stderr.
        if (!(System.out instanceof Routed r && r.out)) {
            System.setOut(routing(System.out, true));
        }
        if (!(System.err instanceof Routed r && !r.out)) {
            System.setErr(routing(System.err, false));
        }
    }

    /** A routing stream whose default is {@code fallback}, for a caller that installs it itself. */
    static PrintStream routing(PrintStream fallback, boolean out) {
        // The routing stream's own default, never the calling thread's binding: a job's buffer
        // must not become the default of every other thread.
        return new Routed(fallback instanceof Routed r ? r.fallback : fallback, out);
    }

    /**
     * Runs {@code action} with this thread's (and its new threads') output going to {@code out}
     * and {@code err}; null keeps what applies now. {@code label} names the job, for the JSON log
     * (null keeps the current one).
     */
    static <T> T bind(PrintStream out, PrintStream err, String label, Callable<T> action) throws Exception {
        install();
        Binding previous = BINDING.get();
        Binding binding = new Binding(out == null ? currentOut() : unwrap(out),
                err == null ? currentErr() : unwrap(err), label == null ? label() : label);
        BINDING.set(binding);
        try {
            return action.call();
        } finally {
            binding.closed = true;
            if (previous == null) {
                BINDING.remove();
            } else {
                BINDING.set(previous);
            }
        }
    }

    /** Runs {@code action} with its {@code System.out} going to stderr (the {@code --format json} commands). */
    static <T> T outToErr(Callable<T> action) throws Exception {
        return bind(currentErr(), null, null, action);
    }

    /** Where {@code System.out} goes on this thread now: never a routing stream. */
    static PrintStream currentOut() {
        Binding b = active();
        return b != null ? b.out : unwrap(System.out);
    }

    /** Where {@code System.err} goes on this thread now: never a routing stream. */
    static PrintStream currentErr() {
        Binding b = active();
        return b != null ? b.err : unwrap(System.err);
    }

    /** The label of this thread's binding (the job id under {@code serve}), or null. */
    static String label() {
        Binding b = active();
        return b == null ? null : b.label;
    }

    private static Binding active() {
        Binding b = BINDING.get();
        return b == null || b.closed ? null : b;
    }

    private static PrintStream unwrap(PrintStream stream) {
        return stream instanceof Routed r ? r.target() : stream;
    }

    /** Sends every call to the current thread's binding, or to its default. */
    private static final class Routed extends PrintStream {
        private final PrintStream fallback;
        private final boolean out;

        Routed(PrintStream fallback, boolean out) {
            super(OutputStream.nullOutputStream(), true, fallback.charset());
            this.fallback = fallback;
            this.out = out;
        }

        PrintStream target() {
            Binding b = active();
            return b == null ? fallback : out ? b.out : b.err;
        }

        @Override public void flush() { target().flush(); }
        /** Never closes the target: it belongs to the process or to a binding. */
        @Override public void close() { flush(); }
        @Override public boolean checkError() { return target().checkError(); }
        @Override public void write(int b) { target().write(b); }
        @Override public void write(byte[] buf, int off, int len) { target().write(buf, off, len); }
        @Override public void write(byte[] buf) { target().write(buf, 0, buf.length); }
        @Override public void writeBytes(byte[] buf) { target().write(buf, 0, buf.length); }
        @Override public void print(boolean b) { target().print(b); }
        @Override public void print(char c) { target().print(c); }
        @Override public void print(int i) { target().print(i); }
        @Override public void print(long l) { target().print(l); }
        @Override public void print(float f) { target().print(f); }
        @Override public void print(double d) { target().print(d); }
        @Override public void print(char[] s) { target().print(s); }
        @Override public void print(String s) { target().print(s); }
        @Override public void print(Object obj) { target().print(obj); }
        @Override public void println() { target().println(); }
        @Override public void println(boolean x) { target().println(x); }
        @Override public void println(char x) { target().println(x); }
        @Override public void println(int x) { target().println(x); }
        @Override public void println(long x) { target().println(x); }
        @Override public void println(float x) { target().println(x); }
        @Override public void println(double x) { target().println(x); }
        @Override public void println(char[] x) { target().println(x); }
        @Override public void println(String x) { target().println(x); }
        @Override public void println(Object x) { target().println(x); }
        @Override public PrintStream printf(String format, Object... args) { target().printf(format, args); return this; }
        @Override public PrintStream printf(Locale l, String format, Object... args) { target().printf(l, format, args); return this; }
        @Override public PrintStream format(String format, Object... args) { target().format(format, args); return this; }
        @Override public PrintStream format(Locale l, String format, Object... args) { target().format(l, format, args); return this; }
        @Override public PrintStream append(CharSequence csq) { target().append(csq); return this; }
        @Override public PrintStream append(CharSequence csq, int start, int end) { target().append(csq, start, end); return this; }
        @Override public PrintStream append(char c) { target().append(c); return this; }
        @Override public Charset charset() { return target().charset(); }
    }
}
