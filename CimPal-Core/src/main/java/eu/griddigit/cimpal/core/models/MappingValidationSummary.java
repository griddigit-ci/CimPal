package eu.griddigit.cimpal.core.models;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a mapping-driven validation run: the workbooks it wrote, in the order they were
 * created, and how many validations conformed, reported violations, or failed to run.
 */
public record MappingValidationSummary(List<Path> reports, int conforming, int violations, int errors) {

    public MappingValidationSummary {
        reports = List.copyOf(reports);
    }

    public boolean hasViolations() {
        return violations > 0 || errors > 0;
    }

    public int totalRows() {
        return conforming + violations + errors;
    }
}
