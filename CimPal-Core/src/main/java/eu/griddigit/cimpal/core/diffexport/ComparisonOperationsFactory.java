/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.diffexport;

import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a raw {@link RDFCompareResult} into a list of {@link ComparisonOperationRow}, classifying
 * each difference as an {@link Operation} with Model A treated as the older/base version.
 * <p>
 * This is the single source of truth for "what changed" shared by the on-screen result table, the
 * Excel export and the CSV export. Unlike the KGCL converter it works per entry (one row per
 * difference, matching the tabular exports) and keeps the raw comparer values — no node grouping and
 * no lexical normalization.
 */
public final class ComparisonOperationsFactory {

    private ComparisonOperationsFactory() {
    }

    /**
     * Classifies each comparison entry: value only in A → {@link Operation#REMOVE}; value only in B
     * → {@link Operation#ADD}; differing values on both sides → {@link Operation#UPDATE}. The
     * placeholder side ({@code -}/{@code N/A}/empty) is blanked to {@code ""}.
     */
    public static List<ComparisonOperationRow> fromResult(RDFCompareResult result) {
        List<ComparisonOperationRow> rows = new ArrayList<>();
        if (result == null) {
            return rows;
        }
        for (RDFCompareResultEntry entry : result.getEntries()) {
            boolean hasA = !isPlaceholder(entry.getValueModelA());
            boolean hasB = !isPlaceholder(entry.getValueModelB());

            Operation operation;
            if (hasA && hasB) {
                operation = Operation.UPDATE;
            } else if (hasB) {
                operation = Operation.ADD;
            } else if (hasA) {
                operation = Operation.REMOVE;
            } else {
                // both sides empty/placeholder: nothing meaningful to report
                continue;
            }

            rows.add(new ComparisonOperationRow(
                    entry.getItem(),
                    entry.getRdfType(),
                    entry.getProperty(),
                    hasA ? entry.getValueModelA() : "",
                    hasB ? entry.getValueModelB() : "",
                    operation));
        }
        return rows;
    }

    /** A value column is a placeholder when it is null, empty, {@code "-"} or {@code "N/A"}. */
    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        return v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("N/A");
    }
}
