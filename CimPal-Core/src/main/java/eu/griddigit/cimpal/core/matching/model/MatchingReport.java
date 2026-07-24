/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a matching run: the confirmed mappings and the residue for
 * humans. Deliberately minimal - two sheets' worth of data.
 *
 * <ul>
 *   <li>{@link MatchedEntry}: the four deliverable columns
 *       (PF_ID, Element_type, New_ID, New_2nd_name).</li>
 *   <li>{@link UnmatchedEntry}: everything not confidently matched - present on
 *       only one side, or with more than one candidate - for human processing.</li>
 * </ul>
 */
public final class MatchingReport {

    /** One confirmed mapping row: PF_ID -&gt; New_ID (+ the IGMS secondary name). */
    public record MatchedEntry(
            String pfId,
            String elementType,
            String newId,
            String newSecondName
    ) {
    }

    /** One element that needs human processing (unmatched or ambiguous). */
    public record UnmatchedEntry(
            String id,
            String elementType,
            String name,
            String side,      // "PF" or "IGMS"
            String reason
    ) {
    }

    private final List<MatchedEntry> matched = new ArrayList<>();
    private final List<UnmatchedEntry> unmatched = new ArrayList<>();

    public List<MatchedEntry> matched() {
        return matched;
    }

    public List<UnmatchedEntry> unmatched() {
        return unmatched;
    }
}
