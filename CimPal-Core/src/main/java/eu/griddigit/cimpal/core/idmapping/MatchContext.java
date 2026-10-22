/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * State shared by the matching phases: both networks, the matches, the matched logical lines, and
 * the comparisons of voltages and line parameters.
 * <p>
 * Endpoint keys (a substation IRI, or {@code open:} plus a node IRI) are compared on the model 2 side:
 * a model 1 key is translated through the matches with {@link #mapKey}; a model 2 key that is not matched
 * yet compares as unknown.
 */
final class MatchContext {

    static final String UNKNOWN = "?";

    final Network model1;
    final Network model2;
    final IdMappingOptions options;
    final Names names;
    final Matches matches = new Matches();
    final Map<LogicalLine, LogicalLine> lineModel1ToModel2 = new HashMap<>();
    final Map<LogicalLine, LogicalLine> lineModel2ToModel1 = new HashMap<>();
    /** Matched model 1 lines whose own evidence is sure (one-to-one or by name, same voltage). */
    private final Set<LogicalLine> structuralLines = new HashSet<>();
    private final Map<String, List<LogicalLine>> model1LinesAt = new HashMap<>();
    private final Map<String, List<LogicalLine>> model2LinesAt = new HashMap<>();
    /** Set by {@link IdMapper}; aligns the elements of two matched lines. */
    TopologyMatcher topology;

    MatchContext(Network model1, Network model2, IdMappingOptions options, Names names) {
        this.model1 = model1;
        this.model2 = model2;
        this.options = options;
        this.names = names;
        index(model1, model1LinesAt);
        index(model2, model2LinesAt);
    }

    private static void index(Network net, Map<String, List<LogicalLine>> at) {
        for (LogicalLine line : net.lines) {
            for (String key : new HashSet<>(line.endpointKeys())) {
                at.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
            }
        }
    }

    /** The logical lines with an end at a station (or open-end key). */
    List<LogicalLine> linesAt(Side side, String key) {
        return (side == Side.MODEL1 ? model1LinesAt : model2LinesAt).getOrDefault(key, List.of());
    }

    Network net(Side side) {
        return side == Side.MODEL1 ? model1 : model2;
    }

    /** The model 2 key a model 1 endpoint key stands for, or null while it is unmatched. */
    String mapKey(String model1Key) {
        if (model1Key.startsWith(LogicalLine.OPEN)) {
            String node = matches.toModel2(model1Key.substring(LogicalLine.OPEN.length()));
            return node == null ? null : LogicalLine.OPEN + node;
        }
        return matches.toModel2(model1Key);
    }

    boolean model2KeyMatched(String model2Key) {
        String iri = model2Key.startsWith(LogicalLine.OPEN) ? model2Key.substring(LogicalLine.OPEN.length()) : model2Key;
        return matches.toModel1(iri) != null;
    }

    /** A key in the shared identity space: mapped model 1 keys and matched model 2 keys as themselves, else "?". */
    String sharedKey(Side side, String key) {
        if (side == Side.MODEL1) {
            String mapped = mapKey(key);
            return mapped == null ? UNKNOWN : mapped;
        }
        return model2KeyMatched(key) ? key : UNKNOWN;
    }

    /**
     * Records that two logical lines are the same and matches their elements; returns false when
     * either line is already matched elsewhere. {@code structural}: the line evidence itself is
     * sure; the line is then certain when its ends are, now or after {@link #promote()}.
     */
    boolean matchLines(LogicalLine p, LogicalLine n, String method, String note, boolean structural) {
        if (lineModel1ToModel2.containsKey(p) || lineModel2ToModel1.containsKey(n)) {
            return lineModel1ToModel2.get(p) == n;
        }
        lineModel1ToModel2.put(p, n);
        lineModel2ToModel1.put(n, p);
        if (structural) {
            structuralLines.add(p);
        }
        topology.alignLine(p, n, method, note, lineCertain(p), structural);
        return true;
    }

    /** Whether a matched model 1 line is certain: sure evidence and certain ends. */
    boolean lineCertain(LogicalLine p) {
        return structuralLines.contains(p) && endsCertain(p);
    }

    /** Whether every end of the model 1 line is matched with certainty (station or open-end node). */
    boolean endsCertain(LogicalLine p) {
        for (String key : p.endpointKeys()) {
            String iri = key.startsWith(LogicalLine.OPEN) ? key.substring(LogicalLine.OPEN.length()) : key;
            if (!matches.isCertain(iri)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Upgrades probable matches that the finished matching confirms, until nothing changes: a
     * station whose lines all match one to one and whose inside is at least 90 % confirmed position
     * by position; then every match that was sure in itself and waited for its station or line.
     */
    void promote() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> e : List.copyOf(matches.model1ToModel2().entrySet())) {
                if (model1.substations.contains(e.getKey()) && !matches.isCertain(e.getKey())
                        && stationConfirmed(e.getKey(), e.getValue())) {
                    matches.promote(e.getKey());
                    changed = true;
                }
            }
            for (Map.Entry<String, Object> e : List.copyOf(matches.pendingOnContext().entrySet())) {
                Object context = e.getValue();
                boolean ready = context instanceof String station ? matches.isCertain(station)
                        : context instanceof LogicalLine line && lineCertain(line);
                if (ready) {
                    matches.promote(e.getKey());
                    changed = true;
                }
            }
        }
    }

    private boolean stationConfirmed(String model1Station, String model2Station) {
        List<LogicalLine> model1Lines = linesAt(Side.MODEL1, model1Station);
        List<LogicalLine> model2Lines = linesAt(Side.MODEL2, model2Station);
        if (model1Lines.isEmpty() || model1Lines.size() != model2Lines.size()) {
            return false;
        }
        for (LogicalLine p : model1Lines) {
            LogicalLine n = lineModel1ToModel2.get(p);
            if (n == null || !model2Lines.contains(n) || !structuralLines.contains(p)) {
                return false;
            }
        }
        Set<String> inside = new LinkedHashSet<>();
        for (String node : model1.nodesBySubstation.getOrDefault(model1Station, List.of())) {
            inside.add(node);
            for (GridData.TerminalRec t : model1.node(node).terminals) {
                inside.add(t.equipment());
            }
        }
        int confirmed = 0;
        for (String iri : inside) {
            if (matches.isCertain(iri) || matches.pendingOnContext().containsKey(iri)) {
                confirmed++;
            }
        }
        return inside.size() >= 3 && confirmed >= 0.9 * inside.size();
    }

    boolean voltagesAgree(Double a, Double b) {
        return a == null || b == null || Math.abs(a - b) <= options.getVoltageTolerance();
    }

    /**
     * Whether the line parameters both lines have agree within the relative tolerance, with at
     * least {@code minCompared} of them present on both sides.
     */
    boolean parametersAgree(LogicalLine p, LogicalLine n, int minCompared) {
        int compared = 0;
        double[][] pairs = {pair(p.r, n.r), pair(p.x, n.x), pair(p.bch, n.bch), pair(p.length, n.length)};
        for (double[] v : pairs) {
            if (v == null) {
                continue;
            }
            if (!close(v[0], v[1])) {
                return false;
            }
            if (v[0] != 0 || v[1] != 0) {
                compared++;
            }
        }
        return compared >= minCompared;
    }

    /** What differs between two matched lines, for the report's note; null when nothing does. */
    String lineDifferences(LogicalLine p, LogicalLine n) {
        List<String> parts = new ArrayList<>();
        if (!voltagesAgree(p.nominalVoltage, n.nominalVoltage)) {
            parts.add("voltage differs (model 1 " + fmt(p.nominalVoltage) + " kV vs model 2 " + fmt(n.nominalVoltage) + " kV)");
        }
        String[] labels = {"r", "x", "bch", "length"};
        Double[][] values = {{p.r, n.r}, {p.x, n.x}, {p.bch, n.bch}, {p.length, n.length}};
        List<String> params = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (values[i][0] != null && values[i][1] != null && !close(values[i][0], values[i][1])) {
                params.add(labels[i] + " model 1 " + fmt(values[i][0]) + " vs model 2 " + fmt(values[i][1]));
            }
        }
        if (!params.isEmpty()) {
            parts.add("parameters differ (" + String.join(", ", params) + ")");
        }
        if (p.branchCount != n.branchCount) {
            parts.add("sections model 1 " + p.branchCount + " vs model 2 " + n.branchCount);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private boolean close(double a, double b) {
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return scale < 1e-12 || Math.abs(a - b) <= options.getParameterTolerance() * scale;
    }

    private static double[] pair(Double a, Double b) {
        return a == null || b == null ? null : new double[]{a, b};
    }

    static String fmt(Double v) {
        if (v == null) {
            return "?";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf(v.longValue());
        }
        return String.format(Locale.ROOT, "%.6g", v);
    }

    static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "; " + b;
    }
}
