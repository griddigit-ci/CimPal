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

    private final boolean conforms;
    private final boolean partial;
    private final List<SHACLValidationResult> results;
    private final Model reportModel;
    private final List<String> warnings;
    private final String datasetName;
    private final String dataSources;
    private final String shapeSources;
    private final int maxResultsPerConstraint;

    public SHACLValidationReport(boolean conforms,
                                 boolean partial,
                                 List<SHACLValidationResult> results,
                                 Model reportModel,
                                 List<String> warnings,
                                 String datasetName,
                                 String dataSources,
                                 String shapeSources,
                                 int maxResultsPerConstraint) {
        this.conforms = conforms;
        this.partial = partial;
        this.results = List.copyOf(results);
        this.reportModel = reportModel;
        this.warnings = List.copyOf(warnings);
        this.datasetName = datasetName;
        this.dataSources = dataSources;
        this.shapeSources = shapeSources;
        this.maxResultsPerConstraint = maxResultsPerConstraint;
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

    /** Writes the results as a CimPal validation workbook, the same layout mapping runs produce. */
    public void writeExcel(Path file) throws IOException {
        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            writer.appendValidation(ValidationExcelWriter.CaseFolder.UNKNOWN, datasetName, dataSources, "",
                    shapeSources, results, conforms, datasetName, partial, maxResultsPerConstraint);
            writer.saveAs(file);
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
