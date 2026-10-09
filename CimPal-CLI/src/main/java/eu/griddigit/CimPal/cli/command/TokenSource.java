/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Where {@code serve} gets the bearer tokens it accepts (SEC-1, DEP-6): one fixed token (generated
 * per start, or from {@code CIMPAL_API_TOKEN}), or a read-only token file such as a mounted
 * Kubernetes or Docker secret ({@link FileTokens}).
 */
interface TokenSource {

    /** True when {@code authorization} is {@code Bearer <token>} for an accepted token. */
    boolean matches(String authorization);

    /** False while no token can be accepted (a broken token file); {@code /ready} then answers 503. */
    default boolean usable() {
        return true;
    }

    /** One token that never changes. */
    static TokenSource fixed(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A token is required.");
        }
        return authorization -> ServeSecurity.tokenMatches(token, authorization);
    }

    /**
     * Tokens read from a regular file that {@code serve} never writes or deletes: one or two
     * non-blank lines (two while a token is being rotated), lines starting with {@code #} ignored,
     * each at least {@link ServeSecurity#MIN_ENV_TOKEN_LENGTH} characters, the file at most
     * {@value #MAX_FILE_BYTES} bytes.
     *
     * <p>The file is read again on a request once {@code reloadInterval} has passed, and the
     * tokens change when its content does. Revoking never fails open:
     * <ul>
     *   <li>A file with no tokens (empty, or only comments) revokes all of them.</li>
     *   <li>A file that can't be read or isn't valid keeps the tokens read last until the next
     *       check, which bridges a secret being replaced. If it is still broken then, every token is
     *       refused (and {@link #usable()} turns false) until the file is fixed. Each failed check
     *       is logged.</li>
     * </ul>
     * At start, a missing, unreadable or token-less file is fatal.
     */
    final class FileTokens implements TokenSource {

        static final int MAX_FILE_BYTES = 4096;
        static final int MAX_TOKENS = 2;

        private final Path file;
        private final Duration reloadInterval;
        private final InstantSource clock;
        private final Consumer<String> warn;
        /** Held while one request thread checks the file; the others keep using the current tokens. */
        private final ReentrantLock reloading = new ReentrantLock();

        private volatile List<String> tokens;
        private volatile Instant checkedAt;
        private byte[] digest;
        /** Whether the previous check failed too; then this one refuses everything. */
        private boolean failing;

        /**
         * Reads the file now.
         *
         * @param warn receives warnings (never a token); e.g. a world-readable file or a failed reload
         * @throws IllegalArgumentException when the file is missing, too large or holds no valid token
         */
        FileTokens(Path file, Duration reloadInterval, InstantSource clock, Consumer<String> warn) {
            this.file = Objects.requireNonNull(file, "file");
            this.reloadInterval = Objects.requireNonNull(reloadInterval, "reloadInterval");
            this.clock = Objects.requireNonNull(clock, "clock");
            this.warn = Objects.requireNonNull(warn, "warn");
            if (reloadInterval.isNegative() || reloadInterval.isZero()) {
                throw new IllegalArgumentException("--token-reload must be positive.");
            }
            try {
                byte[] content = read();
                List<String> read = parse(content);
                if (read.isEmpty()) {
                    throw new IllegalArgumentException("no token in the file");
                }
                tokens = read;
                digest = sha256(content);
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Token file " + file + ": " + e.getMessage());
            }
            checkedAt = clock.instant();
            warnIfReadableByOthers();
        }

        @Override
        public boolean matches(String authorization) {
            List<String> current = current();
            boolean match = false;
            // Every token is compared, so the time taken doesn't tell which one matched.
            for (String token : current) {
                match |= ServeSecurity.tokenMatches(token, authorization);
            }
            return match;
        }

        @Override
        public boolean usable() {
            return !current().isEmpty();
        }

        /** How many tokens are accepted now (tests, the startup banner). */
        int count() {
            return current().size();
        }

        private List<String> current() {
            Instant now = clock.instant();
            if (Duration.between(checkedAt, now).compareTo(reloadInterval) >= 0 && reloading.tryLock()) {
                try {
                    if (Duration.between(checkedAt, now).compareTo(reloadInterval) >= 0) {
                        check();
                        checkedAt = now;
                    }
                } finally {
                    reloading.unlock();
                }
            }
            return tokens;
        }

        /** Under {@link #reloading}. */
        private void check() {
            try {
                byte[] content = read();
                byte[] d = sha256(content);
                if (!MessageDigest.isEqual(d, digest)) {
                    List<String> read = parse(content);
                    if (read.isEmpty()) {
                        warn.accept("The token file " + file + " holds no token any more; every request is refused.");
                    }
                    tokens = read;
                    digest = d;
                }
                failing = false;
            } catch (IOException | IllegalArgumentException e) {
                if (failing) {
                    if (!tokens.isEmpty()) {
                        tokens = List.of();
                        digest = null;
                    }
                    warn.accept("The token file " + file + " is still unusable (" + e.getMessage()
                            + "); every request is refused until it is fixed.");
                } else {
                    failing = true;
                    warn.accept("Could not reload the token file " + file + " (" + e.getMessage()
                            + "); the tokens read before stay in force until the next check.");
                }
            }
        }

        /** The file's bytes, at most {@value #MAX_FILE_BYTES}; only a regular file (links are followed). */
        private byte[] read() throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException(Files.exists(file) ? "not a regular file" : "file not found");
            }
            byte[] content;
            try (InputStream in = Files.newInputStream(file)) {
                content = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (content.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("larger than " + MAX_FILE_BYTES + " bytes");
            }
            return content;
        }

        /** The tokens in {@code content}; empty when there are none. Never puts a token in a message. */
        private static List<String> parse(byte[] content) {
            List<String> read = new ArrayList<>();
            for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (t.length() < ServeSecurity.MIN_ENV_TOKEN_LENGTH) {
                    throw new IllegalArgumentException("every token must be at least "
                            + ServeSecurity.MIN_ENV_TOKEN_LENGTH + " characters long");
                }
                read.add(t);
            }
            if (read.size() > MAX_TOKENS) {
                throw new IllegalArgumentException("expected one or two tokens, one per line, found " + read.size());
            }
            return List.copyOf(read);
        }

        private static byte[] sha256(byte[] content) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(content);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }

        private void warnIfReadableByOthers() {
            try {
                if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                    // Group read is how Kubernetes shares a secret with fsGroup; any user is too wide.
                    if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                        warn.accept("The token file " + file + " can be read by any user; restrict it (chmod 600, "
                                + "or defaultMode 0440 with fsGroup for a Kubernetes secret).");
                    }
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort: the warning is advice, not a check.
            }
        }
    }
}
