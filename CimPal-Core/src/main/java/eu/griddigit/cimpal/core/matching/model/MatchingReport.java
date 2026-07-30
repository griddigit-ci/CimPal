/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a matching run.
 *
 * <ul>
 *   <li>{@link MatchedEntry}: the deliverable columns
 *       (Source_ID(IGMG), Element_type, Matched_ID, Matched_name).</li>
 *   <li>{@link UnmatchedEntry}: everything not confidently matched, for human review.</li>
 *   <li>{@link StatEntry}: the Statistics diagnostics sheet.</li>
 *   <li>{@link SubDiagRow}: per-source-substation detail (Substation_diagnostics sheet).</li>
 * </ul>
 */
public final class MatchingReport {

    /**
     * One confirmed mapping row: Source_ID (IGMG) -&gt; Matched_ID (SONI+EirGrid).
     * {@code note} flags a value discrepancy (e.g. differing voltage or name) on
     * an otherwise-confirmed match; empty for a clean match.
     */
    public record MatchedEntry(
            String sourceId,
            String elementType,
            String matchedId,
            String matchedName,
            String note
    ) {
    }

    /** One element that needs human processing (unmatched or ambiguous). */
    public record UnmatchedEntry(
            String id,
            String elementType,
            String name,
            String side,      // "IGMG" (source) or "PF" (SONI+EirGrid)
            String reason
    ) {
    }

    /** One diagnostic metric on the Statistics sheet. */
    public record StatEntry(String metric, String value) {
    }

    /** One row of the Substation_diagnostics sheet. */
    public record SubDiagRow(
            String sourceId, String sourceName,
            int connections, String connectionsByVoltage, String transformers,
            String status, String method,
            String matchedId, String matchedName, String matchedConnections,
            int candidateCount
    ) {
    }

    private final List<MatchedEntry> matched = new ArrayList<>();
    private final List<UnmatchedEntry> unmatched = new ArrayList<>();
    private final List<StatEntry> statistics = new ArrayList<>();
    private final List<SubDiagRow> substationDiagnostics = new ArrayList<>();

    public List<MatchedEntry> matched() {
        return matched;
    }

    public List<UnmatchedEntry> unmatched() {
        return unmatched;
    }

    public List<StatEntry> statistics() {
        return statistics;
    }

    public List<SubDiagRow> substationDiagnostics() {
        return substationDiagnostics;
    }

    public void addStat(String metric, String value) {
        statistics.add(new StatEntry(metric, value));
    }
}
