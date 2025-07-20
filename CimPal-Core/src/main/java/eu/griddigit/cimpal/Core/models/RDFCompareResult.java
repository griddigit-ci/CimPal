package eu.griddigit.cimpal.Core.models;

import java.util.ArrayList;
import java.util.List;

public class RDFCompareResult {
    private final List<RDFCompareResultEntry> entries = new ArrayList<>();

    public void AddEntry(RDFCompareResultEntry entry) {
        entries.add(entry);
    }

    public List<RDFCompareResultEntry> GetEntries() {
        return entries;
    }

    public void ClearEntries() {
        entries.clear();
    }

    public boolean DeleteEntry(RDFCompareResultEntry entry) {
        return entries.remove(entry);
    }

    public boolean HasDifference() {
        return entries.isEmpty();
    }

}
