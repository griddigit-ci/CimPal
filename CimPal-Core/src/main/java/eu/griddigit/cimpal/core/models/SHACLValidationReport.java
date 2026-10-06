/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.utils.ValidationExcelWriter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a {@link eu.griddigit.cimpal.core.utils.SHACLValidator} run.
 * <p>
 * {@link #getResults()} holds the findings enriched with their source shape's name, group,
 * order and description. {@link #getReportModel()} is the standard {@code sh:ValidationReport}
 * graph produced by the engine. It can hold more findings than {@link #getResults()}, which
 * drops exact duplicates and applies the per-constraint result limit.
 */
public class SHACLValidationReport {

    /**
     * The results whose source shape {@code constraintFile} declares; none when all its shapes
     * passed. What a shape finds counts under the file that declares it, even when another file
     * added the constraint that found it.
     */
    public record ConstraintFileResults(String constraintFile, List<SHACLValidationResult> results) {
        public ConstraintFileResults {
            results = List.copyOf(results);
        }
    }

    private final boolean conforms;
    private final boolean partial;
    private final List<SHACLValidationResult> results;
    private final Model reportModel;
    private final List<String> warnings;
    private final String datasetName;
    private final String dataSources;
    private final String shapeSources;
    private final int maxResultsPerConstraint;
    private final List<ConstraintFileResults> resultsByConstraintFile;
    private final boolean brokenDownByConstraintFile;

    public SHACLValidationReport(boolean conforms,
                                 boolean partial,
                                 List<SHACLValidationResult> results,
                                 Model reportModel,
                                 List<String> warnings,
                                 String datasetName,
                                 String dataSources,
                                 String shapeSources,
                                 int maxResultsPerConstraint) {
        this(conforms, partial, results, reportModel, warnings, datasetName, dataSources, shapeSources,
                maxResultsPerConstraint, List.of());
    }

    /**
     * @param resultsByConstraintFile {@code results} broken down by the constraint file that
     *                                declares each one's source shape, or empty when they cannot
     *                                be; the report then holds them as one entry for
     *                                {@code shapeSources}
     */
    public SHACLValidationReport(boolean conforms,
                                 boolean partial,
                                 List<SHACLValidationResult> results,
                                 Model reportModel,
                                 List<String> warnings,
                                 String datasetName,
                                 String dataSources,
                                 String shapeSources,
                                 int maxResultsPerConstraint,
                                 List<ConstraintFileResults> resultsByConstraintFile) {
        this.conforms = conforms;
        this.partial = partial;
        this.results = List.copyOf(results);
        this.reportModel = reportModel;
        this.warnings = List.copyOf(warnings);
        this.datasetName = datasetName;
        this.dataSources = dataSources;
        this.shapeSources = shapeSources;
        this.maxResultsPerConstraint = maxResultsPerConstraint;
        this.brokenDownByConstraintFile = !resultsByConstraintFile.isEmpty();
        this.resultsByConstraintFile = brokenDownByConstraintFile
                ? List.copyOf(resultsByConstraintFile)
                : List.of(new ConstraintFileResults(shapeSources, this.results));
    }

    /** True when the data conforms to every shape. A partial validation never conforms. */
    public boolean conforms() {
        return conforms;
    }

    /** True when a per-constraint result limit cut the validation short. */
    public boolean isPartial() {
        return partial;
    }

    public List<SHACLValidationResult> getResults() {
        return results;
    }

    public Model getReportModel() {
        return reportModel;
    }

    /**
     * Problems with the inputs that did not stop the validation but may make its outcome
     * misleading, for example {@code owl:imports} that could not be resolved.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /** Name identifying the validated data in reports, derived from the data file names. */
    public String getDatasetName() {
        return datasetName;
    }

    /**
     * Number of results per severity, keyed by the severity's local name, for example
     * {@code Violation}, {@code Warning} and {@code Info}.
     */
    public Map<String, Long> countBySeverity() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SHACLValidationResult result : results) {
            counts.merge(localName(result.getSeverity()), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * {@link #getResults()} broken down by the constraint file that declares each result's source
     * shape, ordered by file name. Every file that declares an active shape has an entry. When the
     * results could not be broken down, a single entry holds them all.
     */
    public List<ConstraintFileResults> getResultsByConstraintFile() {
        return resultsByConstraintFile;
    }

    /**
     * Writes the results as a CimPal validation workbook, the same layout mapping runs produce,
     * with one validation row per constraint file as a mapping run has one per mapping row.
     */
    public void writeExcel(Path file) throws IOException {
        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            appendTo(writer);
            writer.saveAs(file);
        }
    }

    /**
     * As {@link #writeExcel(Path)}, into {@code outputDir} under the name mapping runs use,
     * {@code validation_report__<yyyyMMdd_HHmmss>.xlsx}.
     *
     * @return the workbook written
     */
    public Path writeExcelTo(Path outputDir) throws IOException {
        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            appendTo(writer);
            return writer.saveTo(outputDir);
        }
    }

    private void appendTo(ValidationExcelWriter writer) {
        for (ConstraintFileResults file : resultsByConstraintFile) {
            // A constraint file conforms when none of its shapes produced a result, unless the run
            // was cut short (nothing can be said about the checks that did not run), or the engine
            // found the data non-conforming without a result to show for it (a Python engine's
            // report may lack them): then which file failed is unknown.
            boolean fileConforms = conforms || (!partial && file.results().isEmpty() && !results.isEmpty());
            String chartName = brokenDownByConstraintFile ? file.constraintFile() : datasetName;
            writer.appendValidation(ValidationExcelWriter.CaseFolder.UNKNOWN, datasetName, dataSources, "",
                    file.constraintFile(), file.results(), fileConforms, chartName, partial, maxResultsPerConstraint);
        }
    }

    /** Writes {@link #getReportModel()} as Turtle. */
    public void writeTurtle(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            RDFDataMgr.write(out, reportModel, RDFFormat.TURTLE_PRETTY);
        }
    }

    private static String localName(String severity) {
        String s = severity == null ? "" : severity;
        int cut = Math.max(s.lastIndexOf('#'), Math.max(s.lastIndexOf('/'), s.lastIndexOf(':')));
        return s.substring(cut + 1);
    }
}
