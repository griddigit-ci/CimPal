/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.kgcl;

import eu.griddigit.cimpal.core.models.KgclOptions;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies an {@link RDFCompareResult} into a list of {@link KgclChange} objects.
 * <p>
 * The comparison result is a flat list of {@code (item, rdfType, property, valueModelA,
 * valueModelB)} rows where the <em>kind</em> of change is implicit in the value columns: a
 * placeholder ({@code "-"} for the RDFS comparators, {@code "N/A"} for the SHACL comparator) on one
 * side means "present on the other side only"; two differing real values mean a changed value.
 * <p>
 * This converter groups entries by item and applies the mapping documented in the plan. Because
 * classes, packages, attributes and associations are all distinct subjects (hence distinct items),
 * a group is either entirely one-sided (a whole-node creation/deletion) or contains changed/added
 * facets of an item present in both models.
 */
public class KgclConverter {

    /**
     * Converts a comparison result into KGCL changes honoring the direction in {@code options}
     * (which model is treated as the "before" state).
     */
    public List<KgclChange> convert(RDFCompareResult result, KgclOptions options) {
        List<KgclChange> changes = new ArrayList<>();
        if (result == null) {
            return changes;
        }
        boolean aToB = options.getDirection() == KgclOptions.Direction.A_TO_B;

        // group entries by item, preserving first-seen order
        Map<String, List<RDFCompareResultEntry>> byItem = new LinkedHashMap<>();
        for (RDFCompareResultEntry entry : result.getEntries()) {
            byItem.computeIfAbsent(entry.getItem() == null ? "" : entry.getItem(), k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<String, List<RDFCompareResultEntry>> group : byItem.entrySet()) {
            String item = group.getKey();
            List<RDFCompareResultEntry> entries = group.getValue();

            if (isWholeNode(entries, aToB, true)) {
                // every facet present only in the "after" model -> node created
                changes.add(new KgclChange(KgclChangeType.NODE_CREATION, item, null, null, findLabel(entries, aToB)));
            } else if (isWholeNode(entries, aToB, false)) {
                // every facet present only in the "before" model -> node deleted
                changes.add(new KgclChange(KgclChangeType.NODE_DELETION, item, null, null, null));
            } else {
                for (RDFCompareResultEntry entry : entries) {
                    changes.add(toChange(item, entry, aToB));
                }
            }
        }
        return changes;
    }

    /**
     * True if every entry of an item is one-sided in the same direction <em>and</em> the item
     * carries a (one-sided) label: {@code creation==true} checks that all facets are present only in
     * the "after" model; {@code false} checks the "before" model. Such a group represents a
     * whole-node creation/deletion.
     * <p>
     * The label requirement is what distinguishes a brand-new/removed node (the comparators always
     * report {@code rdfs:label} for an item that exists in only one model) from a single facet that
     * was merely added to or removed from an item present in both models — the latter must stay a
     * per-facet annotation change, not a spurious node creation/deletion.
     */
    private static boolean isWholeNode(List<RDFCompareResultEntry> entries, boolean aToB, boolean creation) {
        if (entries.isEmpty()) {
            return false;
        }
        boolean hasLabel = false;
        for (RDFCompareResultEntry entry : entries) {
            String before = before(entry, aToB);
            String after = after(entry, aToB);
            boolean oneSided = creation
                    ? (isPlaceholder(before) && !isPlaceholder(after))
                    : (!isPlaceholder(before) && isPlaceholder(after));
            if (!oneSided) {
                return false;
            }
            if (isProperty(entry.getProperty(), "label")) {
                hasLabel = true;
            }
        }
        return hasLabel;
    }

    /**
     * Maps a single differing/added/removed facet to a KGCL change. {@code oldValue}/{@code
     * newValue} are left null when the corresponding side is a placeholder (facet added/removed).
     */
    private static KgclChange toChange(String item, RDFCompareResultEntry entry, boolean aToB) {
        String before = before(entry, aToB);
        String after = after(entry, aToB);
        String oldValue = isPlaceholder(before) ? null : lexical(before);
        String newValue = isPlaceholder(after) ? null : lexical(after);
        String property = entry.getProperty();

        KgclChangeType type;
        if (isProperty(property, "label")) {
            type = KgclChangeType.NODE_RENAME;
        } else if (isProperty(property, "comment")) {
            type = KgclChangeType.NODE_DEFINITION_CHANGE;
        } else if (isProperty(property, "subclassof")) {
            type = KgclChangeType.NODE_MOVE;
        } else {
            type = KgclChangeType.NODE_ANNOTATION_CHANGE;
        }
        return new KgclChange(type, item, property, oldValue, newValue);
    }

    /** Finds the "after"-side label value among an item's entries, used to name a created node. */
    private static String findLabel(List<RDFCompareResultEntry> entries, boolean aToB) {
        for (RDFCompareResultEntry entry : entries) {
            if (isProperty(entry.getProperty(), "label")) {
                String after = after(entry, aToB);
                return isPlaceholder(after) ? null : lexical(after);
            }
        }
        return null;
    }

    private static String before(RDFCompareResultEntry entry, boolean aToB) {
        return aToB ? entry.getValueModelA() : entry.getValueModelB();
    }

    private static String after(RDFCompareResultEntry entry, boolean aToB) {
        return aToB ? entry.getValueModelB() : entry.getValueModelA();
    }

    /**
     * Matches an RDF literal in its serialized form: {@code "lexical"}, {@code "lexical"@lang} or
     * {@code "lexical"^^datatype}. Group 1 is the lexical value. {@code DOTALL} so multi-line
     * literals (e.g. definitions) are handled.
     */
    private static final Pattern LITERAL = Pattern.compile("^\"(.*)\"(?:@[A-Za-z0-9-]+|\\^\\^\\S+)?$", Pattern.DOTALL);

    /**
     * Reduces a comparer value to its lexical form. The "Universal" (SHACL) comparator records
     * values as Jena RDF-term serializations (e.g. {@code "validation"@en},
     * {@code "2025-01-04"^^xsd:date}); KGCL wants the bare lexical value. Values that are not
     * quoted literals (plain strings, IRIs, prefixed names, identifiers) are returned unchanged.
     */
    private static String lexical(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '"') {
            var matcher = LITERAL.matcher(v);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return value;
    }

    /** A value column is a placeholder when it is empty, {@code "-"} or {@code "N/A"}. */
    private static boolean isPlaceholder(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        return v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("N/A");
    }

    /** Matches a comparer property string (e.g. {@code "rdfs:label"}) by its local name. */
    private static boolean isProperty(String property, String localName) {
        if (property == null) {
            return false;
        }
        return property.toLowerCase().endsWith(localName.toLowerCase());
    }
}
