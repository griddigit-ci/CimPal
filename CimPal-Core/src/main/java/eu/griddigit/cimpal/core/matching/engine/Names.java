/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure string helpers for the engine: CIM local-name extraction, mRID
 * derivation for the report, and token-based name similarity (used only as a
 * tie-breaker, never as a gate).
 */
public final class Names {

    private Names() {
    }

    /** Local name of a class/resource string like "cim:ACLineSegment" or a full IRI. */
    public static String localName(String s) {
        if (s == null) return null;
        int hash = s.lastIndexOf('#');
        if (hash >= 0) return s.substring(hash + 1);
        int colon = s.lastIndexOf(':');
        if (colon >= 0) return s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) return s.substring(slash + 1);
        return s;
    }

    /**
     * Best-effort mRID for the report. CGMES data resources come back as
     * "cim:_uuid" / "#_uuid" / full-IRI#_uuid; strip everything up to the mRID.
     * Leaves the string unchanged when no recognisable pattern is found.
     */
    public static String mrid(String id) {
        if (id == null) return null;
        String ln = localName(id);
        // typical form "_<uuid>"; drop a single leading underscore for readability
        if (ln.startsWith("_")) {
            return ln.substring(1);
        }
        return ln;
    }

    /**
     * Token-set similarity in [0,1]. Normalises case, strips common voltage
     * suffixes and station words, and returns Jaccard overlap of the token sets.
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> inter = new LinkedHashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new LinkedHashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static final Set<String> STOPWORDS = Set.of(
            "station", "substation", "sub", "kv", "line", "ac", "the");

    private static Set<String> tokens(String name) {
        Set<String> out = new LinkedHashSet<>();
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[0-9]+(kv)?", " ")   // strip voltage numbers / suffixes
                .replaceAll("[^a-z]+", " ");
        for (String t : Arrays.asList(cleaned.trim().split("\\s+"))) {
            if (!t.isBlank() && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
