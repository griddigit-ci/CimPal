/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.MatchingReport;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.MatchedEntry;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.SubDiagRow;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.UnmatchedEntry;
import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.TerminalRow;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the matching results into the deliverable report:
 * Matched (Source_ID(IGMG) | Element_type | Matched_ID | Matched_name),
 * Unmatched, Statistics, and Substation_diagnostics. Match precedence for the
 * one-row-per-source-object rule: same-id &gt; substation &gt; line &gt; topology.
 */
public final class ReportAssembler {

    private ReportAssembler() {
    }

    public static MatchingReport assemble(SimpleIdMatcher.Result simple,
                                          SubstationLineMatcher.Result r, TopologyMatcher.Result topo,
                                          ModelTables source, ModelTables matched,
                                          List<LogicalLine> sourceLines, List<LogicalLine> matchedLines) {
        MatchingReport report = new MatchingReport();
        Set<String> emitted = new HashSet<>(); // by source mRID, enforces one row per source object

        // 1. Same-id + same-type matches (strongest, simplest)
        if (simple != null) {
            for (SimpleIdMatcher.IdMatch m : simple.matchesById().values()) {
                addMatched(report, emitted, Names.mrid(m.id()), m.type(), Names.mrid(m.id()), safe(m.name()), "");
            }
        }
        // 2. Substation structural matches (note flags voltage/name discrepancies)
        for (SubstationLineMatcher.SubMatch m : r.substationMatches()) {
            addMatched(report, emitted, Names.mrid(m.pf()), "Substation", Names.mrid(m.igms()), nameOrEmpty(r, m.igms()), safe(m.note()));
        }
        // 3. Line matches (one row per source member segment; note flags voltage discrepancies)
        for (SubstationLineMatcher.LineMatch lm : r.lineMatches()) {
            String matchedId = Names.mrid(lm.igms().id());
            String matchedName = safe(lm.igms().name());
            String note = safe(lm.note());
            for (String member : lm.pf().memberSegments()) {
                addMatched(report, emitted, Names.mrid(member), "ACLineSegment", matchedId, matchedName, note);
            }
        }
        // 4. Within-substation topology matches
        if (topo != null) {
            for (TopologyMatcher.ElementMatch m : topo.matches()) {
                addMatched(report, emitted, m.pfId(), m.type(), m.igmsId(), safe(m.igmsName()), "");
            }
        }

        // ---- Unmatched (side: IGMG = source, PF = SONI+EirGrid) ----
        for (SubstationLineMatcher.UnmatchedSub u : r.unmatchedSubstationsPf()) {
            report.unmatched().add(new UnmatchedEntry(Names.mrid(u.id()), "Substation", nameOrEmpty(r, u.id()), "IGMG", u.reason()));
        }
        for (SubstationLineMatcher.UnmatchedSub u : r.unmatchedSubstationsIgms()) {
            report.unmatched().add(new UnmatchedEntry(Names.mrid(u.id()), "Substation", nameOrEmpty(r, u.id()), "PF", u.reason()));
        }
        for (SubstationLineMatcher.UnmatchedLine u : r.unmatchedLinesPf()) {
            String reason = withContext(u.reason(), u.line());
            for (String member : u.line().memberSegments()) {
                report.unmatched().add(new UnmatchedEntry(Names.mrid(member), "ACLineSegment", nameOrEmpty(u.line()), "IGMG", reason));
            }
        }
        for (LogicalLine l : r.unmatchedLinesIgms()) {
            String reason = withContext("no matching source line", l);
            for (String member : l.memberSegments()) {
                report.unmatched().add(new UnmatchedEntry(Names.mrid(member), "ACLineSegment", nameOrEmpty(l), "PF", reason));
            }
        }
        if (topo != null) {
            for (TopologyMatcher.UnmatchedEl u : topo.unmatched()) {
                report.unmatched().add(new UnmatchedEntry(u.id(), u.type(), safe(u.name()), u.side(), u.reason()));
            }
        }

        // ---- Substation diagnostics (verify the logic) ----
        for (SubstationLineMatcher.SubDiag d : r.substationDiagnostics()) {
            report.substationDiagnostics().add(new SubDiagRow(
                    d.sourceId(), d.sourceName(), d.connections(), d.connectionsByVoltage(), d.transformers(),
                    d.status(), d.method(), d.matchedId(), d.matchedName(), d.matchedConnections(), d.candidateCount()));
        }

        buildStatistics(report, simple, r, topo, source, matched, sourceLines, matchedLines);
        return report;
    }

    private static void addMatched(MatchingReport report, Set<String> emitted,
                                   String sourceId, String type, String matchedId, String matchedName, String note) {
        if (sourceId == null || !emitted.add(sourceId)) return;
        report.matched().add(new MatchedEntry(sourceId, type, matchedId, matchedName, note));
    }

    private static void buildStatistics(MatchingReport report, SimpleIdMatcher.Result simple,
                                        SubstationLineMatcher.Result r, TopologyMatcher.Result topo,
                                        ModelTables source, ModelTables matched,
                                        List<LogicalLine> sourceLines, List<LogicalLine> matchedLines) {
        inventory(report, "IGMG (source)", source, sourceLines);
        inventory(report, "PF (SONI+EirGrid)", matched, matchedLines);

        report.addStat("--- matching results ---", "");
        report.addStat("Same-id + same-type matches", String.valueOf(simple == null ? 0 : simple.matchesById().size()));
        report.addStat("IGMG substations", String.valueOf(distinctSubstations(source)));
        report.addStat("PF substations", String.valueOf(distinctSubstations(matched)));
        report.addStat("Substations matched", String.valueOf(r.substationMatches().size()));
        report.addStat("Substations unmatched (IGMG)", String.valueOf(r.unmatchedSubstationsPf().size()));
        report.addStat("Substations unmatched (PF)", String.valueOf(r.unmatchedSubstationsIgms().size()));
        report.addStat("Lines matched", String.valueOf(r.lineMatches().size()));
        report.addStat("Lines unmatched (IGMG)", String.valueOf(r.unmatchedLinesPf().size()));
        report.addStat("Lines unmatched (PF)", String.valueOf(r.unmatchedLinesIgms().size()));

        long boundary = 0, endpointsUnmatched = 0, noCandidate = 0, parallel = 0;
        for (SubstationLineMatcher.UnmatchedLine u : r.unmatchedLinesPf()) {
            String why = u.reason();
            if (why.startsWith("boundary")) boundary++;
            else if (why.startsWith("one or more endpoint")) endpointsUnmatched++;
            else if (why.startsWith("no IGMS line") || why.startsWith("no IGMG") || why.startsWith("no ") || why.startsWith("unresolved")) noCandidate++;
            else parallel++;
        }
        report.addStat("  lines - boundary/dangling (unresolved endpoints)", String.valueOf(boundary));
        report.addStat("  lines - endpoint substation unmatched", String.valueOf(endpointsUnmatched));
        report.addStat("  lines - no counterpart at matched substations/voltage", String.valueOf(noCandidate));
        report.addStat("  lines - parallel/ambiguous", String.valueOf(parallel));

        if (topo != null) {
            report.addStat("Within-substation elements matched", String.valueOf(topo.matches().size()));
        }
        report.addStat("IGMG connectivity-node containment", pct(containment(source)));
        report.addStat("PF connectivity-node containment", pct(containment(matched)));
    }

    private static void inventory(MatchingReport report, String side, ModelTables t, List<LogicalLine> lines) {
        Set<String> terminals = new HashSet<>();
        Set<String> cns = new HashSet<>();
        Map<String, Set<String>> byClass = new java.util.TreeMap<>();
        for (TerminalRow tr : t.terminals()) {
            if (tr.terminal() != null) terminals.add(tr.terminal());
            if (tr.cn() != null) cns.add(tr.cn());
            if (tr.eq() != null && tr.eqType() != null) {
                byClass.computeIfAbsent(Names.localName(tr.eqType()), k -> new HashSet<>()).add(tr.eq());
            }
        }
        report.addStat(side + " query inventory ---", "");
        report.addStat(side + " Terminals", String.valueOf(terminals.size()));
        report.addStat(side + " ConnectivityNodes", String.valueOf(cns.size()));
        report.addStat(side + " Substations (cim:Substation)", String.valueOf(distinctSubstations(t)));
        report.addStat(side + " PowerTransformers", String.valueOf(distinctTransformers(t)));
        report.addStat(side + " Logical lines (after chain collapse)", String.valueOf(lines.size()));
        byClass.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .forEach(en -> report.addStat(side + " conducting equipment: " + en.getKey(),
                        String.valueOf(en.getValue().size())));
    }

    private static String nameOrEmpty(SubstationLineMatcher.Result r, String subId) {
        String n = r.substationNames().get(subId);
        return n == null ? "" : n;
    }

    private static String nameOrEmpty(LogicalLine l) {
        return l.name() == null ? "" : l.name();
    }

    private static String withContext(String reason, LogicalLine l) {
        StringBuilder sb = new StringBuilder(reason);
        sb.append(" [nomV=").append(l.nomV()).append(", segments=").append(l.segmentCount()).append("]");
        if (!l.notes().isEmpty()) sb.append(" notes: ").append(String.join(" | ", l.notes()));
        return sb.toString();
    }

    private static int distinctSubstations(ModelTables t) {
        return (int) t.substations().stream().map(s -> s.substation()).filter(Objects::nonNull).distinct().count();
    }

    private static int distinctTransformers(ModelTables t) {
        return (int) t.transformerEnds().stream().map(e -> e.trafo()).filter(Objects::nonNull).distinct().count();
    }

    private static double containment(ModelTables t) {
        Set<String> all = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        for (TerminalRow tr : t.terminals()) {
            if (tr.cn() == null) continue;
            all.add(tr.cn());
            if (tr.substation() != null) resolved.add(tr.cn());
        }
        return all.isEmpty() ? 1.0 : (double) resolved.size() / all.size();
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100.0);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
