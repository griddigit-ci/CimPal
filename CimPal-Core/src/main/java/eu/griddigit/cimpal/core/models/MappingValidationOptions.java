/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import org.apache.jena.datatypes.RDFDatatype;

import java.nio.file.Path;
import java.util.Map;

/**
 * Options for a mapping-driven validation run with
 * {@link eu.griddigit.cimpal.core.utils.MappingValidator}: each row of the mapping CSV names the
 * model files to load and the SHACL file(s) to validate them against, and the run writes Excel
 * reports to {@code outputDir}.
 * <p>
 * With {@link Builder#timestamped(boolean)} the models input is instead grouped by input folder
 * and by the timestamp in each file's model header, and every mapping row is validated once per
 * timestamp. Build one with {@link #builder()}, or start from
 * {@link eu.griddigit.cimpal.core.presets.MappingValidationOptionsPresets} for CGMES data.
 */
public class MappingValidationOptions {

    private final Path mappingCsv;
    private final Path modelsInput;
    private final Path constraintsRoot;
    private final Path outputDir;
    private final boolean timestamped;
    private final Path previousComparisonCsv;
    private final DatatypeMapPreset datatypeMapPreset;
    private final Path datatypeMapFile;
    private final Map<String, RDFDatatype> datatypeMap;
    private final String xmlBase;
    private final ValidationEngine engine;
    private final int maxResultsPerConstraint;
    private final int threads;
    private final boolean exportTurtleReports;

    private MappingValidationOptions(Builder b) {
        this.mappingCsv = b.mappingCsv;
        this.modelsInput = b.modelsInput;
        this.constraintsRoot = b.constraintsRoot;
        this.outputDir = b.outputDir;
        this.timestamped = b.timestamped;
        this.previousComparisonCsv = b.previousComparisonCsv;
        this.datatypeMapPreset = b.datatypeMapPreset;
        this.datatypeMapFile = b.datatypeMapFile;
        this.datatypeMap = b.datatypeMap;
        this.xmlBase = b.xmlBase;
        this.engine = b.engine;
        this.maxResultsPerConstraint = b.maxResultsPerConstraint;
        this.threads = b.threads;
        this.exportTurtleReports = b.exportTurtleReports;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getMappingCsv() {
        return mappingCsv;
    }

    public Path getModelsInput() {
        return modelsInput;
    }

    public Path getConstraintsRoot() {
        return constraintsRoot;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public boolean isTimestamped() {
        return timestamped;
    }

    public Path getPreviousComparisonCsv() {
        return previousComparisonCsv;
    }

    /** The bundled datatype map, or null when a map file or an explicit map was given instead. */
    public DatatypeMapPreset getDatatypeMapPreset() {
        return datatypeMapPreset;
    }

    public Path getDatatypeMapFile() {
        return datatypeMapFile;
    }

    public Map<String, RDFDatatype> getDatatypeMap() {
        return datatypeMap;
    }

    public String getXmlBase() {
        return xmlBase;
    }

    public ValidationEngine getEngine() {
        return engine;
    }

    public int getMaxResultsPerConstraint() {
        return maxResultsPerConstraint;
    }

    public int getThreads() {
        return threads;
    }

    public boolean isExportTurtleReports() {
        return exportTurtleReports;
    }

    // ============ the Builder ============

    public static class Builder {
        private Path mappingCsv = null;
        private Path modelsInput = null;
        private Path constraintsRoot = null;
        private Path outputDir = null;
        private boolean timestamped = false;
        private Path previousComparisonCsv = null;
        // At most one of the three datatype map sources is set; each setter clears the others.
        private DatatypeMapPreset datatypeMapPreset = DatatypeMapPreset.NONE;
        private Path datatypeMapFile = null;
        private Map<String, RDFDatatype> datatypeMap = null;
        private String xmlBase = null;
        private ValidationEngine engine = ValidationEngine.APACHE_JENA;
        private int maxResultsPerConstraint = 0;
        private int threads = 0;
        private boolean exportTurtleReports = false;

        /** The mapping CSV: one row per validation, columns {@code xml_inputs, ttl[, notes]}. */
        public Builder mappingCsv(Path mappingCsv) {
            this.mappingCsv = mappingCsv;
            return this;
        }

        /**
         * Folder the mapping's model paths are resolved against. The timestamped workflow also
         * accepts a ZIP archive, and treats each subfolder as a separate input group.
         */
        public Builder modelsInput(Path modelsInput) {
            this.modelsInput = modelsInput;
            return this;
        }

        /** Folder the mapping's SHACL file names, and their relative {@code owl:imports}, are resolved against. */
        public Builder constraintsRoot(Path constraintsRoot) {
            this.constraintsRoot = constraintsRoot;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        /** Runs the timestamped workflow instead of plain mapping validation. */
        public Builder timestamped(boolean timestamped) {
            this.timestamped = timestamped;
            return this;
        }

        /** Previous run's comparison CSV to report deltas against; timestamped workflow only. */
        public Builder previousComparisonCsv(Path previousComparisonCsv) {
            this.previousComparisonCsv = previousComparisonCsv;
            return this;
        }

        /** Uses a bundled datatype map; null or {@link DatatypeMapPreset#NONE} disables typing. */
        public Builder datatypeMap(DatatypeMapPreset preset) {
            this.datatypeMapPreset = preset == null ? DatatypeMapPreset.NONE : preset;
            this.datatypeMapFile = null;
            this.datatypeMap = null;
            return this;
        }

        /** Uses a datatype map {@code .properties} file in the format of the bundled ones. */
        public Builder datatypeMapFile(Path file) {
            if (file == null) {
                return datatypeMap(DatatypeMapPreset.NONE);
            }
            this.datatypeMapPreset = null;
            this.datatypeMapFile = file;
            this.datatypeMap = null;
            return this;
        }

        /** Uses an already-loaded datatype map: property URI to datatype. */
        public Builder datatypeMap(Map<String, RDFDatatype> map) {
            if (map == null) {
                return datatypeMap(DatatypeMapPreset.NONE);
            }
            this.datatypeMapPreset = null;
            this.datatypeMapFile = null;
            this.datatypeMap = Map.copyOf(map);
            return this;
        }

        /** Base URI every model file is parsed with, so relative references join up across files. */
        public Builder xmlBase(String xmlBase) {
            this.xmlBase = xmlBase;
            return this;
        }

        /** The SHACL engine; null selects the default, {@link ValidationEngine#APACHE_JENA}. */
        public Builder engine(ValidationEngine engine) {
            this.engine = engine == null ? ValidationEngine.APACHE_JENA : engine;
            return this;
        }

        /**
         * Stops collecting results for a source shape after this many; 0 (the default) keeps
         * them all. Limited validations are marked partial in the reports.
         */
        public Builder maxResultsPerConstraint(int max) {
            this.maxResultsPerConstraint = max;
            return this;
        }

        /** Parallel validation workers; 0 (the default) picks a CPU- and memory-aware count. */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /** Also writes each validation report as Turtle next to the Excel reports. */
        public Builder exportTurtleReports(boolean exportTurtleReports) {
            this.exportTurtleReports = exportTurtleReports;
            return this;
        }

        /**
         * Builds the immutable MappingValidationOptions, validating required fields.
         */
        public MappingValidationOptions build() {
            if (mappingCsv == null) {
                throw new IllegalStateException("mappingCsv must not be null");
            }
            if (modelsInput == null) {
                throw new IllegalStateException("modelsInput must not be null");
            }
            if (constraintsRoot == null) {
                throw new IllegalStateException("constraintsRoot must not be null");
            }
            if (outputDir == null) {
                throw new IllegalStateException("outputDir must not be null");
            }
            if (xmlBase == null || xmlBase.isBlank()) {
                throw new IllegalStateException("xmlBase must not be blank");
            }
            if (maxResultsPerConstraint < 0) {
                throw new IllegalStateException("maxResultsPerConstraint must be zero (no limit) or greater");
            }
            if (threads < 0) {
                throw new IllegalStateException("threads must be zero (automatic) or greater");
            }
            if (previousComparisonCsv != null && !timestamped) {
                throw new IllegalStateException("previousComparisonCsv is only used by the timestamped workflow");
            }
            return new MappingValidationOptions(this);
        }
    }
}
