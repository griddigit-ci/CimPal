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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The secrets that sign each client's job callbacks (DEP-14, {@code --callback-secrets}): a file
 * such as a mounted secret, one {@code name:secret} line per client (the names of the token file,
 * {@code default} for a single shared token), lines starting with {@code #} ignored, each secret at
 * least 32 characters. Read once at start. A secret is never logged or put in a message; a client
 * without one can't ask for callbacks.
 */
final class CallbackSecrets {

    static final int MIN_SECRET_LENGTH = 32;
    static final int MAX_FILE_BYTES = 64 * 1024;

    /** No file: nobody gets callbacks. */
    static final CallbackSecrets NONE = new CallbackSecrets(Map.of());

    private final Map<String, byte[]> secrets;

    CallbackSecrets(Map<String, byte[]> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    /** The client's secret, or null. */
    byte[] secret(String client) {
        byte[] s = secrets.get(client);
        return s == null ? null : s.clone();
    }

    Set<String> clients() {
        return secrets.keySet();
    }

    /**
     * Reads {@code --callback-secrets}.
     *
     * @throws IllegalArgumentException when the file is missing, too large or not valid (never with a secret in it)
     */
    static CallbackSecrets read(Path file) {
        try {
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
            Map<String, byte[]> read = new HashMap<>();
            int lineNo = 0;
            for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
                lineNo++;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int colon = t.indexOf(':');
                String name = colon < 0 ? "" : t.substring(0, colon);
                String secret = colon < 0 ? "" : t.substring(colon + 1).strip();
                if (!TokenSource.Client.NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("line " + lineNo + ": expected name:secret");
                }
                if (secret.length() < MIN_SECRET_LENGTH) {
                    throw new IllegalArgumentException("line " + lineNo + ": a secret must be at least "
                            + MIN_SECRET_LENGTH + " characters long");
                }
                if (read.put(name, secret.getBytes(StandardCharsets.UTF_8)) != null) {
                    throw new IllegalArgumentException("line " + lineNo + ": a second secret for '" + name + "'");
                }
            }
            return new CallbackSecrets(read);
        } catch (IOException e) {
            throw new IllegalArgumentException("--callback-secrets " + file + ": " + e.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--callback-secrets " + file + ": " + e.getMessage());
        }
    }
}
