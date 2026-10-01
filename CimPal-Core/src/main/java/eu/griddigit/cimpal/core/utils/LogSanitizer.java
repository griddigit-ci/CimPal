/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.util.regex.Pattern;

/**
 * Prepares untrusted values (URLs, request paths, file names, pipeline ids) for log lines.
 * Shared by Core and the CLI so there is one sanitiser to keep correct.
 */
public final class LogSanitizer {

    /** Longest value written to a log line; longer values are cut and end in {@code ...}. */
    public static final int MAX_LENGTH = 512;

    /**
     * Line terminators, including the Unicode ones some log viewers honour (NEL, LS, PS), and
     * every other control character (ESC sequences, NUL, tab).
     */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private LogSanitizer() {
    }

    /**
     * Neutralises characters that could forge an extra log record or drive a terminal, and bounds
     * the length. Each unsafe character becomes U+241E (SYMBOL FOR RECORD SEPARATOR): visible, and
     * it cannot start a new line.
     */
    public static String forLog(String value) {
        if (value == null) {
            return "null";
        }
        String s = UNSAFE.matcher(value).replaceAll("␞");
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) + "..." : s;
    }
}
