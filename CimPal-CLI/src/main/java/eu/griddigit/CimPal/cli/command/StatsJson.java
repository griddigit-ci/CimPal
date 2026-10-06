/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.stats.RunStatsSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * {@code --stats} (DEP-2): how the JSON-capable commands report a run's resource use. In
 * {@code --format json} on stdout the statistics are the last field, {@code "stats"}, of the one
 * JSON object, so {@code serve} and {@code mcp} still return a single JSON value; otherwise they
 * are one {@code [STATS] {...}} line on stderr. Field names are fixed by
 * {@code src/test/resources/fixtures/cli-json/stats.schema.json}.
 */
final class StatsJson {

    private StatsJson() {
    }

    /** A started collector when {@code enabled}, otherwise {@code null}. */
    static RunStats startIf(Boolean enabled) {
        return Boolean.TRUE.equals(enabled) ? RunStats.start() : null;
    }

    /** A phase of {@code stats}, or a no-op when it is {@code null}. */
    static Span phase(RunStats stats, String name) {
        return stats == null ? () -> { } : stats.phase(name)::close;
    }

    /** A phase for try-with-resources, without a checked exception on close. */
    interface Span extends AutoCloseable {
        @Override
        void close();
    }

    /** Adds the triples of a loaded model and the size of the files it came from. */
    static void countLoaded(RunStats stats, long triples, Collection<Path> files) {
        if (stats == null) {
            return;
        }
        stats.addTriples(triples);
        for (Path file : files) {
            try {
                if (Files.isRegularFile(file)) {
                    stats.addInputBytes(Files.size(file));
                }
            } catch (IOException e) {
                // A file that can't be sized is not counted; statistics never fail a run.
            }
        }
    }

    /**
     * The text to put before the closing brace of a JSON object: {@code ,"stats":{...}} with the
     * given line prefix, or the empty string when {@code stats} is {@code null}.
     */
    static String field(RunStats stats, String newlineAndIndent) {
        return stats == null ? "" : "," + newlineAndIndent + "\"stats\": " + object(stats.stop());
    }

    /** Prints {@code [STATS] {...}} on stderr, when {@code stats} is not {@code null}. */
    static void toStderr(RunStats stats) {
        if (stats != null) {
            System.err.println("[STATS] " + object(stats.stop()));
        }
    }

    /** The snapshot as a one-line JSON object. */
    static String object(RunStatsSnapshot s) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"wallMs\": ").append(s.wallMs());
        sb.append(", \"phases\": {");
        boolean first = true;
        for (Map.Entry<String, Long> phase : s.phasesMs().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(str(phase.getKey())).append(": ").append(phase.getValue());
            first = false;
        }
        sb.append("}");
        sb.append(", \"cpuMs\": ").append(s.cpuMs());
        sb.append(", \"peakHeapBytes\": ").append(s.peakHeapBytes());
        sb.append(", \"maxHeapBytes\": ").append(s.maxHeapBytes());
        sb.append(", \"peakRssBytes\": ").append(s.peakRssBytes());
        sb.append(", \"gcMs\": ").append(s.gcMs());
        sb.append(", \"availableProcessors\": ").append(s.availableProcessors());
        sb.append(", \"inputBytes\": ").append(s.inputBytes());
        sb.append(", \"triplesLoaded\": ").append(s.triplesLoaded());
        sb.append(", \"javaVersion\": ").append(str(s.javaVersion()));
        sb.append(", \"os\": ").append(str(s.os()));
        sb.append("}");
        return sb.toString();
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
