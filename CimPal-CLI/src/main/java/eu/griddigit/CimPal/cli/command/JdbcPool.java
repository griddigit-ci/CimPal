/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A small fixed pool of JDBC connections for the job store (DEP-11): at most {@code size} open at
 * once, idle ones checked with {@link Connection#isValid} before they are handed out again, and a
 * connection that failed is closed rather than returned. No pool library: a handful of workers
 * and request threads is all {@code serve} has.
 *
 * <p>The password is kept only to open connections; it is never logged or put in a message.
 */
final class JdbcPool implements AutoCloseable {

    /** One borrowed connection; {@link #close()} returns it (or discards it after {@link #broken()}). */
    final class Lease implements AutoCloseable {
        final Connection connection;
        private boolean broken;
        private boolean closed;

        private Lease(Connection connection) {
            this.connection = connection;
        }

        /** The connection failed: close it instead of reusing it. */
        void broken() {
            broken = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            giveBack(connection, broken);
        }
    }

    static final Duration BORROW_TIMEOUT = Duration.ofSeconds(10);
    private static final int VALIDATE_SECONDS = 2;

    private final String url;
    private final Properties properties;
    private final BlockingQueue<Connection> idle;
    private final Semaphore open;
    private volatile boolean closed;

    /**
     * @param password null when the URL's server needs none (or authenticates otherwise)
     */
    JdbcPool(String url, String user, String password, int size) {
        this.url = Objects.requireNonNull(url, "url");
        if (size < 1) {
            throw new IllegalArgumentException("The pool needs at least one connection.");
        }
        this.properties = new Properties();
        // pgjdbc waits forever by default; a partition without a reset must end in an error.
        if (url.startsWith("jdbc:postgresql:")) {
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("connecttimeout=")) {
                properties.setProperty("connectTimeout", "10");
            }
            if (!lower.contains("sockettimeout=")) {
                properties.setProperty("socketTimeout", "60");
            }
            if (!lower.contains("logintimeout=")) {
                properties.setProperty("loginTimeout", "10");
            }
        }
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        this.idle = new ArrayBlockingQueue<>(size);
        this.open = new Semaphore(size);
    }

    /**
     * A connection, waiting up to {@link #BORROW_TIMEOUT} for a free one.
     *
     * @throws SQLException when none can be had or opened (the message never holds the password)
     */
    Lease borrow() throws SQLException {
        if (closed) {
            throw new SQLException("The job store is closed.");
        }
        Connection c = idle.poll();
        while (c != null) {
            if (valid(c)) {
                return new Lease(c);
            }
            discard(c);
            c = idle.poll();
        }
        long deadline = System.nanoTime() + BORROW_TIMEOUT.toNanos();
        try {
            // A new connection while fewer than size are open; otherwise wait for one to come back
            // (a returned connection goes to the idle queue and keeps its permit).
            while (!open.tryAcquire()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SQLException("No database connection free within " + BORROW_TIMEOUT);
                }
                c = idle.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (c != null) {
                    if (valid(c)) {
                        return new Lease(c);
                    }
                    discard(c); // frees its permit for a fresh connection
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a database connection");
        }
        try {
            Connection fresh = DriverManager.getConnection(url, properties);
            fresh.setAutoCommit(true);
            return new Lease(fresh);
        } catch (SQLException | RuntimeException e) {
            open.release();
            throw e;
        }
    }

    private static boolean valid(Connection c) {
        try {
            return c.isValid(VALIDATE_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private void giveBack(Connection c, boolean broken) {
        boolean reuse = !broken && !closed;
        if (reuse) {
            try {
                if (!c.getAutoCommit()) {
                    c.rollback(); // a transaction left open by a failed caller
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                reuse = false;
            }
        }
        if (reuse && idle.offer(c)) {
            return;
        }
        discard(c);
    }

    private void discard(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // already broken
        } finally {
            open.release();
        }
    }

    @Override
    public void close() {
        closed = true;
        Connection c;
        while ((c = idle.poll()) != null) {
            discard(c);
        }
    }
}
