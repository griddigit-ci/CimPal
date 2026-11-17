/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.Finding;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The outcome of a profile-driven validation as Markdown, for a CI run summary
 * ({@code $GITHUB_STEP_SUMMARY}) or a pull request comment: groups, rules with their counts,
 * skipped documents, gaps and warnings. Text from the data or the shapes is escaped, so it can
 * neither break a table nor inject markup.
 */
public final class ProfileSummaryMarkdown {

    private ProfileSummaryMarkdown() {
    }

    public static String toMarkdown(ProfileValidationSummary summary) {
        StringBuilder md = new StringBuilder();
        md.append("# CimPal profile validation\n\n");
        md.append(summary.conforms() ? "**Result: conforms.**"
                : !summary.complete() ? "**Result: incomplete.** Part of the data or the shapes was not checked; see below."
                : "**Result: findings.**");
        if (summary.partial()) {
            md.append(" The result limit cut part of the validation short.");
        }
        md.append("\n\n| group | documents | errors | warnings | notes | context findings | seconds |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (ProfileValidationSummary.GroupResult g : summary.groups()) {
            md.append("| `").append(code(g.name())).append('`').append(g.incomplete() ? " (incomplete)" : "")
                    .append(" | ").append(g.reported().size())
                    .append(" | ").append(g.severities().getOrDefault("Violation", 0L))
                    .append(" | ").append(g.severities().getOrDefault("Warning", 0L))
                    .append(" | ").append(g.severities().getOrDefault("Info", 0L))
                    .append(" | ").append(g.contextFindings())
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", g.millis() / 1000.0)).append(" |\n");
        }

        // shape / component -> count and level
        Map<String, long[]> rules = new TreeMap<>();
        Map<String, String> levels = new TreeMap<>();
        for (Finding f : summary.findings()) {
            SHACLValidationResult r = f.result();
            String rule = local(r.getSourceShape()) + " / " + local(r.getConstraintComponent());
            rules.computeIfAbsent(rule, k -> new long[1])[0] += Math.max(1, f.documents().size());
            levels.putIfAbsent(rule, ProfileSarifWriter.level(r.getSeverity()));
        }
        if (!rules.isEmpty()) {
            md.append("\n## Rules\n\n| rule | level | occurrences |\n|---|---|---:|\n");
            rules.forEach((rule, count) -> md.append("| `").append(code(rule)).append("` | ")
                    .append(levels.get(rule)).append(" | ").append(count[0]).append(" |\n"));
        }
        if (!summary.skipped().isEmpty()) {
            md.append("\n## Documents not validated\n\n| document | reason |\n|---|---|\n");
            summary.skipped().forEach(s -> md.append("| `").append(code(s.source())).append("` | `")
                    .append(code(s.reason())).append("` |\n"));
        }
        if (!summary.gaps().isEmpty()) {
            md.append("\n## Profile library and dependency gaps\n\n");
            summary.gaps().forEach(g -> md.append("- `").append(code(g)).append("`\n"));
        }
        if (!summary.warnings().isEmpty()) {
            md.append("\n## Warnings\n\n");
            summary.warnings().forEach(w -> md.append("- `").append(code(w)).append("`\n"));
        }
        md.append("\nShapes come from the profiles each document declares. Each group's documents are validated"
                + " together, with their context documents, except `sh:minCount`, `sh:maxCount` and `sh:closed`,"
                + " which are evaluated per document. Findings on context documents are reported in their own"
                + " group.\n");
        return md.toString();
    }

    /**
     * Text for inside a code span, where links, mentions and markup are not rendered: one line, no
     * backtick to close the span, and the pipe escaped, which a table needs even there.
     */
    static String code(String text) {
        return LogSanitizer.forLog(text == null ? "" : text).replace("`", "'").replace("|", "\\|");
    }

    /** One line, no table or markup syntax. */
    static String cell(String text) {
        return LogSanitizer.forLog(text == null ? "" : text)
                .replace("\\", "\\\\").replace("|", "\\|").replace("`", "'")
                .replace("<", "&lt;").replace(">", "&gt;").replace("[", "\\[").replace("]", "\\]");
    }

    private static String local(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        return value.substring(Math.max(value.lastIndexOf('#'), Math.max(value.lastIndexOf('/'), value.lastIndexOf(':'))) + 1);
    }
}
