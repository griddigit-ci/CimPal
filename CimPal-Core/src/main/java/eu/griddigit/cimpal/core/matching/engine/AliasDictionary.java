/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Substation alias dictionary: maps every alias of a station (e.g. its 8-char
 * IGMG code and its full PF name) to a single canonical key, so names expressed
 * differently in the two models resolve to the same identity during matching.
 *
 * <p>Loaded from a CSV where each row lists the aliases of one station in any
 * order, separated by comma, semicolon or tab (e.g. {@code KNOKANUR,Knockanure}).
 * A header row is harmless. Names not present in the dictionary fall back to
 * their own normalised key, so the dictionary is purely additive.</p>
 */
public final class AliasDictionary {

    private final Map<String, String> toCanonical;

    private AliasDictionary(Map<String, String> toCanonical) {
        this.toCanonical = toCanonical;
    }

    public static AliasDictionary empty() {
        return new AliasDictionary(Map.of());
    }

    public static AliasDictionary fromCsv(Path csv) throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            List<String> keys = new ArrayList<>();
            for (String col : line.split("[;,\\t]")) {
                String k = Names.nameKey(col);
                if (k != null) keys.add(k);
            }
            if (keys.size() < 2) continue; // need at least two aliases to relate
            String canonical = keys.get(0);
            for (String k : keys) map.putIfAbsent(k, canonical);
        }
        return new AliasDictionary(map);
    }

    /** Canonical key for a raw name: the dictionary's canonical if known, else its own name key. */
    public String canonical(String rawName) {
        String k = Names.nameKey(rawName);
        if (k == null) return null;
        return toCanonical.getOrDefault(k, k);
    }

    public boolean isEmpty() {
        return toCanonical.isEmpty();
    }

    public int size() {
        return toCanonical.size();
    }
}
