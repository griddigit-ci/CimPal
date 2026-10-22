/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matching by position in a small graph that holds both models' parts side by side, for example
 * the equipment and nodes of one matched substation pair.
 * <p>
 * Every element starts with a label: anchors (elements already matched) get a label shared with
 * their counterpart, the others their class. Each round, an element's colour becomes its previous
 * colour plus the sorted colours of its neighbours, so after k rounds the colour describes the
 * element's surroundings k steps deep, measured from the anchors. When exactly one model 1 and one new
 * element share a colour, they are in the same place and match. The deepest agreement is taken
 * first, so matches that the whole neighbourhood confirms come before those where the models
 * differ a few steps away. Symmetric elements (two identical bays) share colours and stay
 * unmatched unless their names tell them apart.
 */
final class Refinement {

    /** Maximum number of refinement rounds; local graphs stabilise long before. */
    private static final int MAX_DEPTH = 64;

    record Pair(String model1, String model2, int depth, int stableDepth, boolean byName) {
    }

    private final List<String> iris = new ArrayList<>();
    private final List<Side> sides = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final List<Boolean> anchors = new ArrayList<>();
    private final List<Boolean> shallowOk = new ArrayList<>();
    private final List<String> nameKeys = new ArrayList<>();
    private final List<List<long[]>> adjacency = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    private final Map<String, Integer> edgeLabels = new HashMap<>();
    private List<int[]> levels;

    /**
     * Adds an element once per side; returns its index.
     *
     * @param anchor    already matched: it guides the others and is never paired itself
     * @param shallowOk whether being the only unmatched element of its label is enough to match it
     */
    int add(Side side, String iri, String label, boolean anchor, boolean shallowOk, String nameKey) {
        String key = side.name() + '|' + iri;
        Integer existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        int i = iris.size();
        iris.add(iri);
        sides.add(side);
        labels.add(label);
        anchors.add(anchor);
        this.shallowOk.add(shallowOk);
        nameKeys.add(nameKey);
        adjacency.add(new ArrayList<>());
        index.put(key, i);
        return i;
    }

    Integer find(Side side, String iri) {
        return index.get(side.name() + '|' + iri);
    }

    /** Connects two elements; the edge label tells apart, for example, the windings of a transformer. */
    void link(int a, int b, String edgeLabel) {
        int label = edgeLabels.computeIfAbsent(edgeLabel, k -> edgeLabels.size());
        adjacency.get(a).add(new long[]{b, label});
        adjacency.get(b).add(new long[]{a, label});
    }

    int size() {
        return iris.size();
    }

    /**
     * Finds the pairs to match next: those unique at the deepest level where any exist, or, when
     * none are unique, pairs whose names are unique within a shared colour. With
     * {@code confirmedOnly}, only pairs whose whole surroundings agree.
     */
    List<Pair> next(boolean confirmedOnly) {
        refine();
        int stable = levels.size() - 1;
        int lowest = confirmedOnly ? stable : 0;
        for (int k = stable; k >= lowest; k--) {
            List<Pair> pairs = uniqueAt(k, stable);
            if (!pairs.isEmpty()) {
                return pairs;
            }
        }
        for (int k = stable; k >= Math.max(lowest, Math.min(1, stable)); k--) {
            List<Pair> pairs = byNameAt(k, stable);
            if (!pairs.isEmpty()) {
                return pairs;
            }
        }
        return List.of();
    }

    /**
     * Whether this graph pins {@code model1} and {@code model2} down as a pair: at the stable level they
     * share a colour that no other unmatched element has, or within their colour their names are
     * equal and unique on both sides.
     */
    boolean confirms(String model1, String model2) {
        Integer i = find(Side.MODEL1, model1);
        Integer j = find(Side.MODEL2, model2);
        if (i == null || j == null) {
            return false;
        }
        if (levels == null) {
            refine();
        }
        int[] colours = levels.get(levels.size() - 1);
        if (colours[i] != colours[j]) {
            return false;
        }
        int others = 0;
        Map<String, Integer> model1Names = new HashMap<>();
        Map<String, Integer> model2Names = new HashMap<>();
        for (int k = 0; k < size(); k++) {
            if (anchors.get(k) || colours[k] != colours[i]) {
                continue;
            }
            if (k != i && k != j) {
                others++;
            }
            String name = nameKeys.get(k);
            if (name != null) {
                (sides.get(k) == Side.MODEL1 ? model1Names : model2Names).merge(name, 1, Integer::sum);
            }
        }
        if (others == 0) {
            return true;
        }
        String a = nameKeys.get(i);
        return a != null && a.equals(nameKeys.get(j)) && model1Names.get(a) == 1 && model2Names.get(a) == 1;
    }

    /**
     * The unmatched elements of the other side that share {@code iri}'s colour at the deepest level
     * where any do (at least one round deep, unless the labels alone are already stable); empty when none.
     */
    List<String> candidates(Side side, String iri) {
        Integer i = find(side, iri);
        if (i == null || levels == null) {
            return List.of();
        }
        int stable = levels.size() - 1;
        for (int k = stable; k >= Math.min(1, stable); k--) {
            int colour = levels.get(k)[i];
            List<String> found = new ArrayList<>();
            for (int j = 0; j < size(); j++) {
                if (sides.get(j) != side && !anchors.get(j) && levels.get(k)[j] == colour) {
                    found.add(iris.get(j));
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private void refine() {
        levels = new ArrayList<>();
        Map<String, Integer> dictionary = new HashMap<>();
        int[] current = new int[size()];
        for (int i = 0; i < size(); i++) {
            current[i] = dictionary.computeIfAbsent(labels.get(i), k -> dictionary.size());
        }
        levels.add(current);
        int distinct = dictionary.size();
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            Map<String, Integer> next = new HashMap<>();
            int[] refined = new int[size()];
            for (int i = 0; i < size(); i++) {
                List<long[]> neighbours = adjacency.get(i);
                long[] tokens = new long[neighbours.size()];
                for (int n = 0; n < tokens.length; n++) {
                    long[] edge = neighbours.get(n);
                    tokens[n] = (edge[1] << 32) | current[(int) edge[0]];
                }
                Arrays.sort(tokens);
                StringBuilder signature = new StringBuilder().append(current[i]).append('(');
                for (long token : tokens) {
                    signature.append(token).append(',');
                }
                refined[i] = next.computeIfAbsent(signature.toString(), k -> next.size());
            }
            if (next.size() == distinct) {
                break;
            }
            distinct = next.size();
            levels.add(refined);
            current = refined;
        }
    }

    private List<Pair> uniqueAt(int k, int stable) {
        Map<Integer, int[]> groups = new LinkedHashMap<>();
        int[] colours = levels.get(k);
        for (int i = 0; i < size(); i++) {
            if (anchors.get(i) || (k == 0 && stable > 0 && !shallowOk.get(i))) {
                continue;
            }
            int[] g = groups.computeIfAbsent(colours[i], c -> new int[]{0, -1, 0, -1});
            if (sides.get(i) == Side.MODEL1) {
                g[0]++;
                g[1] = i;
            } else {
                g[2]++;
                g[3] = i;
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (int[] g : groups.values()) {
            if (g[0] == 1 && g[2] == 1 && (k == stable || !namesContradict(g[1], g[3]))) {
                pairs.add(new Pair(iris.get(g[1]), iris.get(g[3]), k, stable, false));
            }
        }
        return pairs;
    }

    /**
     * Whether a pair that the surroundings only partly confirm looks like a swap: the names differ
     * and one of them belongs to another unmatched element with the same label on the other side.
     * Two models with different naming conventions never trip this, as their names do not meet.
     */
    private boolean namesContradict(int model1, int model2) {
        String a = nameKeys.get(model1);
        String b = nameKeys.get(model2);
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        for (int i = 0; i < size(); i++) {
            if (anchors.get(i) || i == model1 || i == model2 || !labels.get(i).equals(labels.get(model1))) {
                continue;
            }
            String name = nameKeys.get(i);
            if ((sides.get(i) == Side.MODEL2 && a.equals(name)) || (sides.get(i) == Side.MODEL1 && b.equals(name))) {
                return true;
            }
        }
        return false;
    }

    private List<Pair> byNameAt(int k, int stable) {
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        int[] colours = levels.get(k);
        for (int i = 0; i < size(); i++) {
            if (!anchors.get(i)) {
                groups.computeIfAbsent(colours[i], c -> new ArrayList<>()).add(i);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (List<Integer> group : groups.values()) {
            Map<String, List<Integer>> model1ByName = new HashMap<>();
            Map<String, List<Integer>> model2ByName = new HashMap<>();
            for (int i : group) {
                String name = nameKeys.get(i);
                if (name != null) {
                    (sides.get(i) == Side.MODEL1 ? model1ByName : model2ByName).computeIfAbsent(name, n -> new ArrayList<>()).add(i);
                }
            }
            for (Map.Entry<String, List<Integer>> e : model1ByName.entrySet()) {
                List<Integer> other = model2ByName.get(e.getKey());
                if (e.getValue().size() == 1 && other != null && other.size() == 1) {
                    pairs.add(new Pair(iris.get(e.getValue().get(0)), iris.get(other.get(0)), k, stable, true));
                }
            }
        }
        return pairs;
    }
}
