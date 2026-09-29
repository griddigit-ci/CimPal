/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import java.util.ArrayList;
import java.util.List;

public class RDFCompareResult {
    private final List<RDFCompareResultEntry> entries = new ArrayList<>();

    public void addEntry(RDFCompareResultEntry entry) {
        entries.add(entry);
    }

    public List<RDFCompareResultEntry> getEntries() {
        return entries;
    }

    public void clearEntries() {
        entries.clear();
    }

    public boolean deleteEntry(RDFCompareResultEntry entry) {
        return entries.remove(entry);
    }

    public boolean hasDifference() {
        return entries.isEmpty();
    }

}
