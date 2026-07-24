/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.LogicalLine;
import eu.griddigit.cimpal.core.matching.model.MatchingReport;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.MatchedEntry;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.UnmatchedEntry;

/**
 * Turns the matching result into the deliverable report: the four-column
 * Matched rows and the Unmatched residue for humans. For a logical line built
 * from several PF segments, one Matched row is emitted per PF segment, all
 * pointing at the same New_ID (so every source mRID appears).
 */
public final class ReportAssembler {

    private ReportAssembler() {
    }

    public static MatchingReport assemble(SubstationLineMatcher.Result r, TopologyMatcher.Result topo) {
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

        return report;
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
