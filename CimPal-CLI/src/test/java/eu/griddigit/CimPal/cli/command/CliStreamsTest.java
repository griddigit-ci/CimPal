/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-thread stdout/stderr (DEP-8): concurrent commands' output never mixes. */
class CliStreamsTest {

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream processOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream processErr = new ByteArrayOutputStream();

    @BeforeEach
    void captureProcessStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(processOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(processErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void twoThreadsWritingAtOnceEachReachOnlyTheirOwnSink() throws Exception {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        CountDownLatch bothBound = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> CliStreams.bind(stream(a), null, "A", () -> {
                bothBound.countDown();
                bothBound.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 500; i++) {
                    System.out.println("a" + i);
                }
                return null;
            }));
            Future<?> fb = pool.submit(() -> CliStreams.bind(stream(b), null, "B", () -> {
                bothBound.countDown();
                bothBound.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 500; i++) {
                    System.out.println("b" + i);
                }
                return null;
            }));
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(text(a).lines()).hasSize(500).allMatch(l -> l.startsWith("a"));
        assertThat(text(b).lines()).hasSize(500).allMatch(l -> l.startsWith("b"));
        assertThat(text(processOut)).isEmpty();
    }

    @Test
    void threadsStartedInsideABindingInheritIt() throws Exception {
        ByteArrayOutputStream job = new ByteArrayOutputStream();
        String label = CliStreams.bind(stream(job), null, "job-1", () -> {
            ExecutorService pool = Executors.newFixedThreadPool(1); // as Core creates its pools per run
            try {
                return pool.submit(() -> {
                    System.out.println("from a pool thread");
                    return CliStreams.label();
                }).get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        });
        assertThat(label).isEqualTo("job-1");
        assertThat(text(job)).isEqualTo("from a pool thread" + System.lineSeparator());
    }

    @Test
    void aThreadLeftOverFromAnEndedBindingWritesToTheDefault() throws Exception {
        ByteArrayOutputStream job = new ByteArrayOutputStream();
        CountDownLatch ended = new CountDownLatch(1);
        Thread[] leftOver = new Thread[1];
        CliStreams.bind(stream(job), null, "job-1", () -> {
            leftOver[0] = new Thread(() -> {
                try {
                    ended.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("late");
            });
            leftOver[0].start();
            return null;
        });
        ended.countDown();
        leftOver[0].join(10_000);

        assertThat(text(job)).isEmpty();
        assertThat(text(processOut)).contains("late");
        assertThat(CliStreams.label()).isNull();
    }

    @Test
    void outToErrSendsStdoutToTheBoundStderrAndRestoresAfter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliStreams.bind(stream(out), stream(err), null, () -> {
            PrintStream json = CliStreams.currentOut();
            CliStreams.outToErr(() -> {
                System.out.println("progress");
                json.println("{}");
                return null;
            });
            System.out.println("after");
            return null;
        });
        assertThat(text(err)).isEqualTo("progress" + System.lineSeparator());
        assertThat(text(out)).isEqualTo("{}" + System.lineSeparator() + "after" + System.lineSeparator());
    }

    @Test
    void withoutABindingOutputGoesWhereItWentBefore() throws Exception {
        CliStreams.install();
        System.out.println("plain");
        System.err.println("plain err");
        assertThat(text(processOut)).isEqualTo("plain" + System.lineSeparator());
        assertThat(text(processErr)).isEqualTo("plain err" + System.lineSeparator());
        assertThat(CliStreams.currentOut()).as("never the routing stream itself").isNotSameAs(System.out);
    }

    @Test
    void stdoutPointedAtStderrStillRoutesAsStdout() throws Exception {
        // mcp protects its channel with System.setOut(System.err); a later run must still capture its stdout.
        CliStreams.install();
        System.setOut(System.err);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ByteArrayOutputStream jobErr = new ByteArrayOutputStream();
        CliStreams.bind(stream(captured), stream(jobErr), null, () -> {
            System.out.println("{\"rows\":[]}");
            System.err.println("progress");
            return null;
        });
        assertThat(text(captured)).isEqualTo("{\"rows\":[]}" + System.lineSeparator());
        assertThat(text(jobErr)).isEqualTo("progress" + System.lineSeparator());
        System.out.println("unbound");
        assertThat(text(processErr)).isEqualTo("unbound" + System.lineSeparator());
    }

    @Test
    void installingOnABoundThreadNeverMakesTheJobsStreamTheDefault() throws Exception {
        CliStreams.install();
        ByteArrayOutputStream jobErr = new ByteArrayOutputStream();
        CliStreams.bind(null, stream(jobErr), "job-1", () -> {
            System.setOut(System.err); // a routing stream of the other role, swapped in while bound
            CliStreams.install();
            return null;
        });
        System.out.println("unbound");
        assertThat(text(jobErr)).isEmpty();
        assertThat(text(processErr)).isEqualTo("unbound" + System.lineSeparator());
    }

    @Test
    void closingTheRoutingStreamDoesNotCloseTheTarget() throws Exception {
        CliStreams.install();
        System.out.close();
        System.out.println("still open");
        assertThat(text(processOut)).contains("still open");
    }
}
