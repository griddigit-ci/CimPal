/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.ArrayList;
import java.util.List;

/**
 * The progress lines of one job, bounded: a ring of the last {@code capacity} lines, each at
 * most {@link #MAX_LINE_CHARS} characters. Offsets count every line the job has written, so a
 * reader that polls with {@code nextOffset} never sees a line twice, and learns from
 * {@code truncated} when older lines were dropped.
 */
final class JobLog {

    static final int MAX_LINE_CHARS = 4000;

    /** A page of lines: {@code offset} is the first line's offset, {@code nextOffset} the one to ask for next. */
    record Slice(List<String> lines, long offset, long nextOffset, boolean truncated) {
    }

    private final String[] ring;
    private long total;
    private long chars;

    JobLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.ring = new String[capacity];
    }

    synchronized void append(String line) {
        String bounded = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "..." : line;
        int slot = (int) (total % ring.length);
        if (ring[slot] != null) {
            chars -= ring[slot].length();
        }
        ring[slot] = bounded;
        chars += bounded.length();
        total++;
    }

    /** Characters held in the ring now. */
    synchronized long chars() {
        return chars;
    }

    /** Up to {@code max} lines from {@code from} on; lines older than the ring are skipped. */
    synchronized Slice read(long from, int max) {
        long first = Math.max(0, total - ring.length);
        long start = Math.min(Math.max(from, first), total);
        long end = Math.min(total, start + Math.max(0, max));
        List<String> lines = new ArrayList<>((int) (end - start));
        for (long i = start; i < end; i++) {
            lines.add(ring[(int) (i % ring.length)]);
        }
        return new Slice(lines, start, end, from < first);
    }

    synchronized long size() {
        return total;
    }
}
