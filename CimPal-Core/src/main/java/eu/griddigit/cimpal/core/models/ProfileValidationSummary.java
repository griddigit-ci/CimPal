/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.profiles.InstanceHeader;
import eu.griddigit.cimpal.core.profiles.ProfileGroupPlanner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a profile-driven validation.
 *
 * @param groups        one entry per group, in the order they ran
 * @param findings      the results that are reported, each with its group and the documents it
 *                      was found in
 * @param skipped       the documents that were not validated, and why
 * @param gaps          what the profile library promises but lacks, and declared model
 *                      dependencies that were not loaded
 * @param warnings      what may make the outcome misleading without stopping the run
 * @param complete      false when part of what should have been checked was not: a document was
 *                      skipped for its profile, shapes could not be loaded, or a constraint was
 *                      left out because it is broken. An incomplete run is never a clean one
 * @param partial       true when a result limit cut a validation short
 * @param workbook      the Excel report, or null when none was written
 * @param turtleReports the Turtle reports written, one per group
 */
public record ProfileValidationSummary(List<GroupResult> groups, List<Finding> findings,
                                       List<ProfileGroupPlanner.Skipped> skipped, List<String> gaps,
                                       List<String> warnings, boolean complete, boolean partial,
                                       Path workbook, List<Path> turtleReports) {

    /**
     * One group's part of the run.
     *
     * @param reported the documents whose findings are reported, by {@code InstanceHeader.source()}
     * @param context  the documents loaded with them
     * @param shapes   the names of the shape files used
     * @param findings        the number of findings reported for the group
     * @param contextFindings results met on context documents only: not reported here, since each
     *                        document is reported in its own group, but in the group's Turtle report
     * @param incomplete      true when part of what the group's shapes ask for was not checked
     * @param reportedDocuments the reported documents, with the file each is in
     */
    public record GroupResult(String name, List<String> reported, List<String> context, List<String> shapes,
                              int findings, int contextFindings, Map<String, Long> severities, boolean partial,
                              boolean incomplete, long millis, List<InstanceHeader> reportedDocuments) {
        public GroupResult {
            reportedDocuments = List.copyOf(reportedDocuments);
            reported = List.copyOf(reported);
            context = List.copyOf(context);
            shapes = List.copyOf(shapes);
            severities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(severities));
        }
    }

    /**
     * One reported result.
     *
     * @param group     the group it was found in
     * @param documents the reported documents that hold the focus node, by
     *                  {@code InstanceHeader.source()}; empty for a finding about the shapes
     *                  themselves, which belongs to no document
     * @param headers   the same documents, with the file each is in
     * @param focusIri  the focus node's full IRI, or null when it is not an IRI
     * @param pathIri   the result path's full IRI, or null when it is not a single property
     */
    public record Finding(String group, List<String> documents, SHACLValidationResult result,
                          List<InstanceHeader> headers, String focusIri, String pathIri) {
        public Finding {
            documents = List.copyOf(documents);
            headers = List.copyOf(headers);
        }

        /** A finding known only by document names. */
        public Finding(String group, List<String> documents, SHACLValidationResult result) {
            this(group, documents, result, List.of(), null, null);
        }
    }

    public ProfileValidationSummary {
        groups = List.copyOf(groups);
        findings = List.copyOf(findings);
        skipped = List.copyOf(skipped);
        gaps = List.copyOf(gaps);
        warnings = List.copyOf(warnings);
        turtleReports = List.copyOf(turtleReports);
    }

    /** True only when nothing was found and nothing was left unchecked. */
    public boolean conforms() {
        return findings.isEmpty() && complete && !partial;
    }

    /** Number of findings per severity, keyed by the severity's local name, for example {@code Violation}. */
    public Map<String, Long> countBySeverity() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Finding finding : findings) {
            counts.merge(severityName(finding.result().getSeverity()), 1L, Long::sum);
        }
        return counts;
    }

    /** The local name of a severity IRI or prefixed name. */
    public static String severityName(String severity) {
        String s = severity == null ? "" : severity;
        int cut = Math.max(s.lastIndexOf('#'), Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')));
        return s.substring(cut + 1);
    }
}
