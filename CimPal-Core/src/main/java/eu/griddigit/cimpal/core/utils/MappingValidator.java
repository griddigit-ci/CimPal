/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import org.apache.jena.datatypes.RDFDatatype;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a mapping-driven validation, plain or timestamped, as the SHACL Validation tab does:
 * <pre>{@code
 * MappingValidationSummary summary = new MappingValidator(
 *         MappingValidationOptionsPresets.cgmes30()
 *                 .mappingCsv(mapping)
 *                 .modelsInput(modelsFolder)
 *                 .constraintsRoot(constraintsFolder)
 *                 .outputDir(reportsFolder)
 *                 .build())
 *         .validate();
 * }</pre>
 */
public class MappingValidator {

    private final MappingValidationOptions options;

    public MappingValidator(MappingValidationOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public MappingValidationOptions getOptions() {
        return options;
    }

    /**
     * Validates every mapping row and writes the reports to the output folder.
     *
     * @throws IOException if the mapping, the models input or the output folder cannot be used.
     *                     A row that fails to validate is counted in
     *                     {@link MappingValidationSummary#errors()} and reported in the
     *                     workbook instead.
     */
    public MappingValidationSummary validate() throws IOException {
        Map<String, RDFDatatype> dataTypeMap = CompleteDatatypeMapLoader.resolve(
                options.getDatatypeMapPreset(), options.getDatatypeMapFile(), options.getDatatypeMap());

        if (options.isTimestamped()) {
            ValidationTools.ValidationTimestampedRunSummary run = ValidationTools.validateByTimestampedMapping(
                    options.getMappingCsv(),
                    options.getModelsInput(),
                    options.getConstraintsRoot(),
                    options.getOutputDir(),
                    options.getThreads(),
                    dataTypeMap,
                    options.getXmlBase(),
                    options.getPreviousComparisonCsv(),
                    options.getMaxResultsPerConstraint(),
                    options.getEngine(),
                    options.isExportTurtleReports());
            return new MappingValidationSummary(run.reports(), run.conforming(), run.violations(), run.errors());
        }

        ValidationTools.ValidationRunSummary run = ValidationTools.validateByMapping(
                options.getMappingCsv(),
                options.getModelsInput(),
                options.getConstraintsRoot(),
                options.getOutputDir(),
                options.getThreads(),
                dataTypeMap,
                options.getXmlBase(),
                options.getMaxResultsPerConstraint(),
                options.getEngine(),
                options.isExportTurtleReports());
        return new MappingValidationSummary(List.of(run.reportPath()), run.conforming(), run.violations(), run.errors());
    }
}
