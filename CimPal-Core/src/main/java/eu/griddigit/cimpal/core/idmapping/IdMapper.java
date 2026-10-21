/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import eu.griddigit.cimpal.core.idmapping.GridData.ObjectInfo;
import eu.griddigit.cimpal.core.idmapping.GridData.TerminalRec;
import eu.griddigit.cimpal.core.idmapping.IdMappingResult.Statistic;
import eu.griddigit.cimpal.core.idmapping.IdMappingResult.TypeCount;
import eu.griddigit.cimpal.core.idmapping.Network.Category;
import eu.griddigit.cimpal.core.idmapping.Network.Equipment;
import eu.griddigit.cimpal.core.idmapping.Network.NodeInfo;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Maps the IDs of one CIM/CGMES model (model 1) onto another model of the same grid (model 2), for example
 * two EQ files onto one merged EQ, and reports what it could not map.
 * <p>
 * The models are read only through the SPARQL query library ({@link QueryLibrary}). Matching then
 * runs in this order, each step precision-first (a unique answer on both sides, or nothing):
 * <ol>
 *   <li>objects with the same ID and class;</li>
 *   <li>substations with the same name;</li>
 *   <li>lines between substations, as chains of series elements through line-interior nodes, and
 *       substations from the lines and their connections ({@link SubstationLineMatcher});</li>
 *   <li>inside each matched substation, equipment and nodes by their place in the topology
 *       ({@link TopologyMatcher});</li>
 *   <li>terminals, nodes, transformer ends, containers and dependent objects that follow
 *       ({@link DerivedMatcher}).</li>
 * </ol>
 * Steps 3 and 4 repeat while they find more.
 *
 * <pre>{@code
 * IdMappingResult result = new IdMapper(IdMappingOptions.builder()
 *         .model1File(soniEq).model1File(eirgridEq).model2File(mergedEq).commonFiles(List.of(boundary))
 *         .build()).run();
 * result.writeExcel(Path.of("id_mapping.xlsx"));
 * }</pre>
 */
public final class IdMapper {

    private final IdMappingOptions options;
    private final Consumer<String> log;

    public IdMapper(IdMappingOptions options) {
        this(options, message -> { });
    }

    /** @param log receives progress messages */
    public IdMapper(IdMappingOptions options, Consumer<String> log) {
        this.options = Objects.requireNonNull(options);
        this.log = log == null ? message -> { } : log;
    }

    /**
     * @throws IOException              when a model file cannot be read or parsed
     * @throws IllegalArgumentException when a query in the query folder is invalid
     */
    public IdMappingResult run() throws IOException {
        return report(match());
    }

    /** Loads both models and matches them; the state the report is built from. */
    MatchContext match() throws IOException {
        QueryLibrary queries = new QueryLibrary(options.getQueryFolder());
        Names names = options.getNameAliasFile() == null ? Names.none() : Names.load(options.getNameAliasFile());

        Graph common = CimModelLoader.load(options.getCommonFiles(), options.getXmlBase());
        Set<String> shared = new HashSet<>();
        common.find().forEachRemaining(t -> {
            if (t.getSubject().isURI()) {
                shared.add(t.getSubject().getURI());
            }
        });
        GridData model1Data = extract("model 1", options.getModel1Files(), common, shared, queries);
        GridData model2Data = extract("model 2", options.getModel2Files(), common, shared, queries);
        common.close();

        Network model1 = new Network(Side.MODEL1, model1Data, options.isCollapsePassThroughSubstations(), options.isGroupStations());
        Network model2 = new Network(Side.MODEL2, model2Data, options.isCollapsePassThroughSubstations(), options.isGroupStations());
        log.accept("model 1: " + model1.substations.size() + " substations, " + model1.lines.size() + " lines, "
                + model1.equipment.size() + " equipment; new: " + model2.substations.size() + " substations, "
                + model2.lines.size() + " lines, " + model2.equipment.size() + " equipment");

        MatchContext ctx = new MatchContext(model1, model2, options, names);
        ctx.topology = new TopologyMatcher(ctx);
        SubstationLineMatcher lines = new SubstationLineMatcher(ctx);
        DerivedMatcher derived = new DerivedMatcher(ctx);

        if (options.isMatchSameIds()) {
            log.accept("Same IDs: " + derived.sameIds() + " objects");
            lines.linesFromMatchedMembers();
        }
        if (options.isUseNames()) {
            log.accept("Substations by name: " + lines.seedByName());
        }
        int round = 0;
        while (true) {
            round++;
            boolean changed = false;
            while (lines.structuralPass()) {
                changed = true;
            }
            for (Map.Entry<String, String> e : List.copyOf(ctx.matches.model1ToModel2().entrySet())) {
                if (model1.substations.contains(e.getKey()) && model2.substations.contains(e.getValue())) {
                    changed |= ctx.topology.matchSubstation(e.getKey(), e.getValue());
                }
            }
            changed |= lines.linesFromMatchedMembers();
            log.accept("Round " + round + ": " + ctx.matches.size() + " matched, "
                    + ctx.lineModel1ToModel2.size() + " lines");
            if (changed) {
                continue;
            }
            if (lines.matchByParameters(true) || lines.matchByParameters(false)) {
                continue;
            }
            if (!ctx.topology.weakLineEnds) {
                // last resort: line ends whose position is only partly confirmed
                ctx.topology.weakLineEnds = true;
                continue;
            }
            break;
        }
        ctx.promote();
        lines.recordFinalReasons();
        derived.run();
        log.accept("Matched " + ctx.matches.size() + " objects");
        return ctx;
    }

    private GridData extract(String label, List<Path> files, Graph common, Set<String> shared, QueryLibrary queries)
            throws IOException {
        log.accept("Loading " + label + " model (" + files.size() + " file(s))");
        Graph graph = CimModelLoader.load(files, options.getXmlBase());
        GraphUtil.addInto(graph, common);
        log.accept("Querying " + label + " model (" + graph.size() + " triples)");
        GridData data = GridData.extract(graph, queries, shared);
        graph.close();
        return data;
    }

    // ---- report ---------------------------------------------------------------------------------

    private IdMappingResult report(MatchContext ctx) {
        Matches m = ctx.matches;
        List<MatchedRow> matched = new ArrayList<>();
        List<UnmatchedRow> unmatched = new ArrayList<>();
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, Integer> methods = new HashMap<>();
        List<ObjectInfo> model1Objects = sorted(ctx.model1);
        List<ObjectInfo> model2Objects = sorted(ctx.model2);
        // A class the other model does not have at all (e.g. measurements) cannot be matched: it is
        // counted in the Summary, not listed object by object in Unmatched.
        Set<String> model1Types = new HashSet<>();
        model1Objects.forEach(i -> model1Types.add(i.type()));
        Set<String> model2Types = new HashSet<>();
        model2Objects.forEach(i -> model2Types.add(i.type()));
        Map<String, String> reportedSubstations = new HashMap<>();

        for (ObjectInfo info : model1Objects) {
            int[] c = counts.computeIfAbsent(info.type(), t -> new int[8]);
            c[0]++;
            String other = stationPart(ctx, info, m.toModel2(info.iri()));
            if (other != null && "Substation".equals(info.type())) {
                reportedSubstations.put(other, info.iri());
            }
            if (other != null) {
                ObjectInfo o = ctx.model2.info(other);
                String model2Type = o == null ? ctx.model2.type(other) : o.type();
                boolean certain = m.isCertain(info.iri());
                matched.add(new MatchedRow(info.id(), info.type(), GridData.localId(other), secondName(ctx, o),
                        certain ? MatchedRow.CERTAIN : MatchedRow.PROBABLE, info.name(),
                        Objects.equals(model2Type, info.type()) ? null : model2Type,
                        m.method(info.iri()), m.note(info.iri())));
                methods.merge(m.method(info.iri()), 1, Integer::sum);
                c[certain ? 1 : 2]++;
            } else {
                c[3]++;
                if (model2Types.contains(info.type())) {
                    unmatched.add(unmatchedRow(ctx, Side.MODEL1, info));
                }
            }
        }
        for (ObjectInfo info : model2Objects) {
            int[] c = counts.computeIfAbsent(info.type(), t -> new int[8]);
            c[4]++;
            String model1 = "Substation".equals(info.type()) ? reportedSubstations.get(info.iri()) : m.toModel1(info.iri());
            if (model1 != null) {
                c[m.isCertain(model1) ? 5 : 6]++;
            } else {
                c[7]++;
                if (model1Types.contains(info.type())) {
                    unmatched.add(unmatchedRow(ctx, Side.MODEL2, info));
                }
            }
        }
        List<TypeCount> typeCounts = new ArrayList<>();
        counts.forEach((type, c) -> typeCounts.add(new TypeCount(type, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])));
        return new IdMappingResult(matched, unmatched, typeCounts, methods, statistics(ctx), inputs());
    }

    /**
     * For a model 1 substation matched to a station model 2 keeps as several substations, the
     * part to report: the one whose name equals, or else abbreviates, the model 1 name; else the largest.
     */
    private static String stationPart(MatchContext ctx, ObjectInfo info, String other) {
        if (other == null || !"Substation".equals(info.type())) {
            return other;
        }
        List<String> parts = ctx.model2.stationMembers.getOrDefault(other, List.of(other));
        if (parts.size() < 2) {
            return other;
        }
        String key = ctx.names.canonical(info.name());
        for (String part : parts) {
            if (key != null && key.equals(ctx.names.canonical(ctx.model2.name(part)))) {
                return part;
            }
        }
        for (String part : parts) {
            if (ctx.names.similar(info.name(), ctx.model2.name(part))) {
                return part;
            }
        }
        return other;
    }

    /** The reported objects of one side in report order: by category, class, substation and name. */
    private List<ObjectInfo> sorted(Network net) {
        List<ObjectInfo> list = new ArrayList<>();
        for (ObjectInfo info : net.data.objects.values()) {
            if (reported(net, info)) {
                list.add(info);
            }
        }
        Comparator<ObjectInfo> order = Comparator
                .comparing((ObjectInfo i) -> net.category(i.iri()))
                .thenComparing(ObjectInfo::type)
                .thenComparing(i -> nvl(net.name(substationOf(net, i.iri()))))
                .thenComparing(i -> nvl(i.name()))
                .thenComparing(ObjectInfo::id);
        list.sort(order);
        return list;
    }

    private boolean reported(Network net, ObjectInfo info) {
        if (net.data.shared.contains(info.iri()) || info.type() == null) {
            return false;
        }
        return switch (net.category(info.iri())) {
            case TERMINAL -> options.isIncludeTerminals();
            case NODE -> options.isIncludeNodes();
            case DEPENDENT -> options.isIncludeDependents();
            case OTHER -> options.isIncludeOthers();
            default -> true;
        };
    }

    /**
     * The model 2 object's name for Model2_2nd_name. A line section often has a name like "A" or "1"
     * that only means something next to its line, so a section in a Line container gets the
     * Line's name, with its own name in brackets when that differs.
     */
    private String secondName(MatchContext ctx, ObjectInfo o) {
        if (o == null) {
            return null;
        }
        String own = switch (options.getSecondName()) {
            case SHORT_NAME -> o.shortName();
            case DESCRIPTION -> o.description();
            default -> o.name();
        };
        if (options.getSecondName() != IdMappingOptions.SecondName.NAME) {
            return own;
        }
        var c = ctx.model2.data.containment.get(o.iri());
        String line = c != null && "Line".equals(c.containerType()) ? ctx.model2.name(c.container()) : null;
        if (line == null) {
            return own;
        }
        return own == null || Objects.equals(Names.key(own), Names.key(line)) ? line : line + " (" + own + ")";
    }

    private UnmatchedRow unmatchedRow(MatchContext ctx, Side side, ObjectInfo info) {
        Network net = ctx.net(side);
        String sub = substationOf(net, info.iri());
        String reason = ctx.matches.reason(side, info.iri());
        if (sub != null && !ctx.matches.matched(side, sub) && net.substations.contains(sub)) {
            reason = "substation not matched";
        } else if (reason == null) {
            reason = defaultReason(ctx, side, info);
        }
        List<String> candidates = ctx.matches.candidates(side, info.iri());
        Network other = ctx.net(side == Side.MODEL1 ? Side.MODEL2 : Side.MODEL1);
        List<String> shown = new ArrayList<>();
        for (String c : candidates.subList(0, Math.min(5, candidates.size()))) {
            String name = other.name(c);
            shown.add(GridData.localId(c) + (name == null ? "" : " (" + name + ")"));
        }
        if (candidates.size() > 5) {
            shown.add("+" + (candidates.size() - 5) + " more");
        }
        return new UnmatchedRow(side, info.id(), info.type(), info.name(), net.name(sub), reason,
                String.join("; ", shown));
    }

    private static String defaultReason(MatchContext ctx, Side side, ObjectInfo info) {
        Network net = ctx.net(side);
        Category category = net.category(info.iri());
        return switch (category) {
            case EQUIPMENT, NODE -> net.lineOfMember.containsKey(info.iri()) ? "line not matched"
                    : substationOf(net, info.iri()) == null ? "not in a substation; no line or same-ID match"
                    : "no counterpart in the matched substation";
            case TERMINAL -> {
                TerminalRec t = net.terminals.get(info.iri());
                yield t != null && !ctx.matches.matched(side, t.equipment()) ? "equipment not matched"
                        : "could not be paired with a terminal of the matched equipment";
            }
            case TRANSFORMER_END -> "transformer not matched, or its windings could not be paired";
            case CONTAINER -> {
                String station = net.station(info.iri());
                if ("Substation".equals(info.type()) && net.stationMembers.getOrDefault(station, List.of()).size() > 1) {
                    yield "part of one station with " + String.join(" + ", net.stationNames(station))
                            + (ctx.matches.matched(side, station) ? "; the station is matched as a whole" : "");
                }
                yield "Substation".equals(info.type()) && net.passThrough.contains(info.iri())
                        ? "line junction (pass-through substation); its contents are matched with the line"
                        : "no counterpart holding most of the same matched objects";
            }
            case DEPENDENT -> "owner not matched";
            case OTHER -> "no unique match by class and name";
        };
    }

    /** The substation an object is in: equipment, node, terminal, voltage level or bay; else null. */
    private static String substationOf(Network net, String iri) {
        Equipment eq = net.equipment.get(iri);
        if (eq != null) {
            if (eq.substation != null) {
                return eq.substation;
            }
            for (TerminalRec t : eq.terminals) {
                String s = net.substationOf(t);
                if (s != null) {
                    return s;
                }
            }
            return null;
        }
        NodeInfo node = net.nodes.get(iri);
        if (node != null) {
            return node.substation;
        }
        TerminalRec t = net.terminals.get(iri);
        if (t != null) {
            return substationOf(net, t.equipment());
        }
        var c = net.data.containment.get(iri);
        if (c != null && c.substation() != null) {
            return c.substation();
        }
        var vl = net.data.voltageLevels.get(iri);
        return vl == null ? null : vl.substation();
    }

    private List<Statistic> statistics(MatchContext ctx) {
        List<Statistic> stats = new ArrayList<>();
        Network model1 = ctx.model1;
        Network model2 = ctx.model2;
        stats.add(stat("Substations (matching)", model1.substations.size(), model2.substations.size()));
        stats.add(stat("Pass-through substations (line junctions)", model1.passThrough.size(), model2.passThrough.size()));
        stats.add(stat("Stations grouping 2+ substations", groupedStations(model1), groupedStations(model2)));
        stats.add(stat("Logical lines", model1.lines.size(), model2.lines.size()));
        stats.add(stat("Logical lines of 2+ sections", model1.lines.stream().filter(l -> l.branchCount > 1).count(),
                model2.lines.stream().filter(l -> l.branchCount > 1).count()));
        stats.add(stat("Logical lines with an open end", openEnded(model1), openEnded(model2)));
        stats.add(stat("Logical lines matched", ctx.lineModel1ToModel2.size(), ctx.lineModel2ToModel1.size()));
        stats.add(stat("Conducting equipment", model1.equipment.size(), model2.equipment.size()));
        stats.add(stat("Nodes", model1.nodes.size(), model2.nodes.size()));
        stats.add(stat("Nodes outside substations", model1.nodesWithoutSubstation, model2.nodesWithoutSubstation));
        stats.add(stat("Terminals", model1.terminals.size(), model2.terminals.size()));
        stats.add(stat("Objects read", model1.data.objects.size(), model2.data.objects.size()));
        stats.add(stat("Objects from common files", model1.data.shared.size(), model2.data.shared.size()));
        return stats;
    }

    private static long groupedStations(Network net) {
        return net.stationMembers.values().stream().filter(m -> m.size() > 1).count();
    }

    private static long openEnded(Network net) {
        return net.lines.stream().filter(l -> l.endpoints.stream().anyMatch(e -> e.key().startsWith(LogicalLine.OPEN)))
                .count();
    }

    private static Statistic stat(String label, long model1, long model2) {
        return new Statistic(label, String.valueOf(model1), String.valueOf(model2));
    }

    private List<Statistic> inputs() {
        List<Statistic> list = new ArrayList<>();
        options.getModel1Files().forEach(f -> list.add(new Statistic("Model 1 file", fileName(f), null)));
        options.getModel2Files().forEach(f -> list.add(new Statistic("Model 2 file", fileName(f), null)));
        options.getCommonFiles().forEach(f -> list.add(new Statistic("Common file", fileName(f), null)));
        if (options.getNameAliasFile() != null) {
            list.add(new Statistic("Name aliases", fileName(options.getNameAliasFile()), null));
        }
        if (options.getQueryFolder() != null) {
            list.add(new Statistic("Query folder", options.getQueryFolder().toString(), null));
        }
        list.add(new Statistic("Match same IDs", String.valueOf(options.isMatchSameIds()), null));
        list.add(new Statistic("Use substation names", String.valueOf(options.isUseNames()), null));
        list.add(new Statistic("Collapse pass-through substations",
                String.valueOf(options.isCollapsePassThroughSubstations()), null));
        list.add(new Statistic("Group substations into stations", String.valueOf(options.isGroupStations()), null));
        list.add(new Statistic("Parameter tolerance", String.valueOf(options.getParameterTolerance()), null));
        list.add(new Statistic("Voltage tolerance (kV)", String.valueOf(options.getVoltageTolerance()), null));
        list.add(new Statistic("Model2_2nd_name from", options.getSecondName().name(), null));
        return list;
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
