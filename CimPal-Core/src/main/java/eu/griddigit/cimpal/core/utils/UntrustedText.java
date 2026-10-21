/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.util.regex.Pattern;

/**
 * Prepares values that come from input data (IRIs, literals, messages, file contents) for an AI
 * agent's context, such as an MCP tool result (SEC-3, gap G5). A model may hold text written to
 * look like an instruction; this bounds how much of it reaches the agent and keeps it from
 * forging extra lines. It doesn't make the text trustworthy: the caller still labels it as data.
 */
public final class UntrustedText {

    /** Longest string value an MCP tool result carries; longer values are cut and end in {@code ...}. */
    public static final int AGENT_MAX_LENGTH = 300;

    /**
     * Wider than {@link LogSanitizer}'s class: also C1 controls (U+0080-U+009F, e.g. the 8-bit CSI)
     * and invisible format characters (bidi overrides, zero-width characters, the tag characters
     * used to hide text from a human reader while a model still reads it), variation selectors
     * (U+FE00-FE0F, U+E0100-E01EF) and the Hangul fillers (U+115F, U+1160, U+3164, U+FFA0), which
     * are invisible too.
     */
    private static final Pattern UNSAFE_FOR_AGENT = Pattern.compile(
            "[\\p{Cc}\\p{Cf}\\u2028\\u2029\\uFE00-\\uFE0F\\x{E0100}-\\x{E01EF}\\u115F\\u1160\\u3164\\uFFA0]");

    private UntrustedText() {
    }

    /**
     * Replaces control characters, line terminators (also NEL, LS and PS) and invisible format
     * characters with U+241E, as {@link LogSanitizer#forLog(String)} does for its narrower set,
     * and cuts the value after {@code maxLength} chars, never between the two halves of a
     * surrogate pair. Null stays null.
     */
    public static String forAgent(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String s = UNSAFE_FOR_AGENT.matcher(LogSanitizer.neutralise(value)).replaceAll("␞");
        if (s.length() <= maxLength) {
            return s;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "...";
    }
}
