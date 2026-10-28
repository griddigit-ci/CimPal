/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Name comparison: names are compared by a key (lower case, letters and digits only, single
 * spaces), optionally through an alias table that makes different names of one station equal,
 * e.g. a station code in one model and its full name in the other.
 */
final class Names {

    private final Map<String, String> aliases;

    private Names(Map<String, String> aliases) {
        this.aliases = aliases;
    }

    static Names none() {
        return new Names(Map.of());
    }

    /**
     * Reads an alias table: one station per line, its names separated by comma, semicolon or tab;
     * the first name of a line stands for all of them. Blank lines and lines starting with
     * {@code #} are skipped. A header line does no harm.
     */
    static Names load(Path csv) throws IOException {
        Map<String, String> map = new HashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            String canonical = null;
            for (String cell : line.split("[,;\t]")) {
                String key = key(cell.replace("\"", ""));
                if (key == null) {
                    continue;
                }
                if (canonical == null) {
                    canonical = key;
                }
                map.putIfAbsent(key, canonical);
            }
        }
        return new Names(map);
    }

    /** The comparison key of a name, through the alias table; null for a blank name. */
    String canonical(String name) {
        String key = key(name);
        return key == null ? null : aliases.getOrDefault(key, key);
    }

    int size() {
        return aliases.size();
    }

    /**
     * Whether two names plausibly name the same station: equal keys (through the aliases), or one
     * an abbreviation of the other, e.g. {@code AGANYGAL} and {@code Agannygal}, {@code BLYLMFRD}
     * and {@code Ballylumford 110}: the letters of the shorter appear in order in the longer, the
     * first letters agree and the shorter has at least three letters. Digits and spaces are ignored.
     * Used to grade a match found by structure, never to make one.
     */
    boolean similar(String a, String b) {
        String ka = canonical(a);
        String kb = canonical(b);
        if (ka == null || kb == null) {
            return false;
        }
        if (ka.equals(kb)) {
            return true;
        }
        String la = ka.replaceAll("[^\\p{L}]", "");
        String lb = kb.replaceAll("[^\\p{L}]", "");
        String shorter = la.length() <= lb.length() ? la : lb;
        String longer = la.length() <= lb.length() ? lb : la;
        if (shorter.length() < 3 || shorter.charAt(0) != longer.charAt(0)) {
            return false;
        }
        int at = 0;
        for (int i = 0; i < longer.length() && at < shorter.length(); i++) {
            if (longer.charAt(i) == shorter.charAt(at)) {
                at++;
            }
        }
        return at == shorter.length();
    }

    /** Lower case, every run of characters other than letters and digits as one space; null if empty. */
    static String key(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return key.isEmpty() ? null : key;
    }
}
