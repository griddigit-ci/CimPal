/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.MatchingReport;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.MatchedEntry;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.UnmatchedEntry;
import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.TerminalRow;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns the matching result into the deliverable report: the four-column
 * Matched rows, the Unmatched residue for humans, and a diagnostics
 * (Statistics) sheet. For a logical line built from several PF segments, one
 * Matched row is emitted per PF segment, all pointing at the same New_ID.
 */
public final class ReportAssembler {

    private ReportAssembler() {
    }

    public static MatchingReport assemble(SubstationLineMatcher.Result r, TopologyMatcher.Result topo,
                                          ModelTables pfTables, ModelTables igmsTables,
                                          List<LogicalLine> pfLines, List<LogicalLine> igmsLines) {
        MatchingReport report = new MatchingReport();

        // ---- Matched / Unmatched: within-substation topology (transformers, busbars, injections) ----
        if (topo != null) {
            for (TopologyMatcher.ElementMatch m : topo.matches()) {
                report.matched().add(new MatchedEntry(m.pfId(), m.type(), m.igmsId(),
                        m.igmsName() == null ? "" : m.igmsName()));
            }
            for (TopologyMatcher.UnmatchedEl u : topo.unmatched()) {
                report.unmatched().add(new UnmatchedEntry(u.id(), u.type(),
                        u.name() == null ? "" : u.name(), u.side(), u.reason()));
            }
        }

        // ---- Matched: substations ----
        for (SubstationLineMatcher.SubMatch m : r.substationMatches()) {
            report.matched().add(new MatchedEntry(
                    Names.mrid(m.pf()), "Substation",
                    Names.mrid(m.igms()), nameOrEmpty(r, m.igms())));
        }

        // ---- Matched: lines (one row per PF member segment) ----
        for (SubstationLineMatcher.LineMatch lm : r.lineMatches()) {
            LogicalLine pf = lm.pf();
            String newId = Names.mrid(lm.igms().id());
            String newSecondName = lm.igms().name() == null ? "" : lm.igms().name();
            for (String member : pf.memberSegments()) {
                report.matched().add(new MatchedEntry(
                        Names.mrid(member), "ACLineSegment", newId, newSecondName));
            }
        }

        // ---- Unmatched: substations ----
        for (SubstationLineMatcher.UnmatchedSub u : r.unmatchedSubstationsPf()) {
            report.unmatched().add(new UnmatchedEntry(
                    Names.mrid(u.id()), "Substation", nameOrEmpty(r, u.id()), "PF", u.reason()));
        }
        for (SubstationLineMatcher.UnmatchedSub u : r.unmatchedSubstationsIgms()) {
            report.unmatched().add(new UnmatchedEntry(
                    Names.mrid(u.id()), "Substation", nameOrEmpty(r, u.id()), "IGMS", u.reason()));
        }

        // ---- Unmatched: lines (one row per member segment) ----
        for (SubstationLineMatcher.UnmatchedLine u : r.unmatchedLinesPf()) {
            String reason = withContext(u.reason(), u.line());
            for (String member : u.line().memberSegments()) {
                report.unmatched().add(new UnmatchedEntry(
                        Names.mrid(member), "ACLineSegment", nameOrEmpty(u.line()), "PF", reason));
            }
        }
        for (LogicalLine l : r.unmatchedLinesIgms()) {
            String reason = withContext("no matching PF line", l);
            for (String member : l.memberSegments()) {
                report.unmatched().add(new UnmatchedEntry(
                        Names.mrid(member), "ACLineSegment", nameOrEmpty(l), "IGMS", reason));
            }
        }

        buildStatistics(report, r, topo, pfTables, igmsTables, pfLines, igmsLines);
        return report;
    }

    private static void buildStatistics(MatchingReport report, SubstationLineMatcher.Result r,
                                        TopologyMatcher.Result topo, ModelTables pf, ModelTables igms,
                                        List<LogicalLine> pfLines, List<LogicalLine> igmsLines) {
        report.addStat("PF substations", String.valueOf(distinctSubstations(pf)));
        report.addStat("IGMS substations", String.valueOf(distinctSubstations(igms)));
        report.addStat("Substations matched", String.valueOf(r.substationMatches().size()));
        report.addStat("Substations unmatched (PF)", String.valueOf(r.unmatchedSubstationsPf().size()));
        report.addStat("Substations unmatched (IGMS)", String.valueOf(r.unmatchedSubstationsIgms().size()));

        report.addStat("PF logical lines", String.valueOf(pfLines.size()));
        report.addStat("IGMS logical lines", String.valueOf(igmsLines.size()));
        report.addStat("Lines matched", String.valueOf(r.lineMatches().size()));
        report.addStat("Lines unmatched (PF)", String.valueOf(r.unmatchedLinesPf().size()));
        report.addStat("Lines unmatched (IGMS)", String.valueOf(r.unmatchedLinesIgms().size()));

        // PF line unmatched reason breakdown (the key diagnostic for the next bottleneck)
        long boundary = 0, endpointsUnmatched = 0, noCandidate = 0, parallel = 0;
        for (SubstationLineMatcher.UnmatchedLine u : r.unmatchedLinesPf()) {
            String why = u.reason();
            if (why.startsWith("boundary")) boundary++;
            else if (why.startsWith("one or more endpoint")) endpointsUnmatched++;
            else if (why.startsWith("no IGMS line")) noCandidate++;
            else parallel++;
        }
        report.addStat("  PF lines - boundary/dangling (unresolved endpoints)", String.valueOf(boundary));
        report.addStat("  PF lines - endpoint substation unmatched", String.valueOf(endpointsUnmatched));
        report.addStat("  PF lines - no IGMS line at matched substations/voltage", String.valueOf(noCandidate));
        report.addStat("  PF lines - parallel/ambiguous", String.valueOf(parallel));

        if (topo != null) {
            report.addStat("Within-substation elements matched (transformers/busbars/injections)",
                    String.valueOf(topo.matches().size()));
        }

        report.addStat("PF connectivity-node containment", pct(containment(pf)));
        report.addStat("IGMS connectivity-node containment", pct(containment(igms)));
    }

    private static int distinctSubstations(ModelTables t) {
        return (int) t.substations().stream().map(s -> s.substation()).filter(Objects::nonNull).distinct().count();
    }

    /** Share of connectivity nodes (seen on terminals) that resolve to a substation. */
    private static double containment(ModelTables t) {
        java.util.Set<String> all = new java.util.HashSet<>();
        java.util.Set<String> resolved = new java.util.HashSet<>();
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

    private static String nameOrEmpty(SubstationLineMatcher.Result r, String subId) {
        String n = r.substationNames().get(subId);
        return n == null ? "" : n;
    }

    private static String nameOrEmpty(LogicalLine l) {
        return l.name() == null ? "" : l.name();
    }

    private static String withContext(String reason, LogicalLine l) {
        StringBuilder sb = new StringBuilder(reason);
        sb.append(" [nomV=").append(l.nomV())
          .append(", segments=").append(l.segmentCount()).append("]");
        if (!l.notes().isEmpty()) sb.append(" notes: ").append(String.join(" | ", l.notes()));
        return sb.toString();
    }
}
