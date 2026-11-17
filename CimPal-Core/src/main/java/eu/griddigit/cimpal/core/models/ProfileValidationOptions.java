/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.profiles.InstanceHeaderReader;
import eu.griddigit.cimpal.core.profiles.ProfileGroupPlanner;
import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import org.apache.jena.datatypes.RDFDatatype;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Options of a profile-driven validation ({@code ProfileValidator}): the instance documents of a
 * folder are validated against the shapes their declared profiles name in a profile library.
 *
 * <p>The defaults for the boundary shapes and the common shapes follow the naming of the ENTSO-E
 * application profiles library, where they work around things its descriptors do not say. Each can
 * be changed or switched off for another library.
 */
public final class ProfileValidationOptions {

    /** Shape files with this in their name apply to boundary data only. */
    public static final String DEFAULT_BOUNDARY_SHAPES_PATTERN = "EquipmentBoundary";
    /** Shapes of the library that no descriptor refers to, by file name. */
    public static final List<String> DEFAULT_COMMON_SHAPES =
            List.of("61970-600-1_AllProfiles-AP-Con-Complex-SHACL.ttl");

    private final Path modelsDir;
    private final List<Path> profileLibraries;
    private final Path outputDir;
    private final List<String> boundaryFolders;
    private final String boundaryShapesPattern;
    private final List<String> commonShapes;
    private final List<String> profileNamespaces;
    private final boolean allowUnmapped;
    private final List<ProfileGroupPlanner.GroupSpec> groups;
    private final DatatypeMapPreset datatypeMapPreset;
    private final Path datatypeMapFile;
    private final Map<String, RDFDatatype> datatypeMap;
    private final String xmlBase;
    private final ValidationEngine engine;
    private final int maxResultsPerConstraint;
    private final int workers;
    private final boolean exportTurtleReports;
    private final RunStats runStats;

    private ProfileValidationOptions(Builder b) {
        this.modelsDir = b.modelsDir;
        this.profileLibraries = List.copyOf(b.profileLibraries);
        this.outputDir = b.outputDir;
        this.boundaryFolders = List.copyOf(b.boundaryFolders);
        this.boundaryShapesPattern = b.boundaryShapesPattern;
        this.commonShapes = b.commonShapes == null ? null : List.copyOf(b.commonShapes);
        this.profileNamespaces = List.copyOf(b.profileNamespaces);
        this.allowUnmapped = b.allowUnmapped;
        this.groups = List.copyOf(b.groups);
        this.datatypeMapPreset = b.datatypeMapPreset;
        this.datatypeMapFile = b.datatypeMapFile;
        this.datatypeMap = b.datatypeMap;
        this.xmlBase = b.xmlBase;
        this.engine = b.engine;
        this.maxResultsPerConstraint = b.maxResultsPerConstraint;
        this.workers = b.workers;
        this.exportTurtleReports = b.exportTurtleReports;
        this.runStats = b.runStats;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getModelsDir() {
        return modelsDir;
    }

    public List<Path> getProfileLibraries() {
        return profileLibraries;
    }

    /** Where the reports are written, or null to write none. */
    public Path getOutputDir() {
        return outputDir;
    }

    public List<String> getBoundaryFolders() {
        return boundaryFolders;
    }

    public String getBoundaryShapesPattern() {
        return boundaryShapesPattern;
    }

    /** The common shapes as given, or null for {@link #DEFAULT_COMMON_SHAPES} where the library has them. */
    public List<String> getCommonShapes() {
        return commonShapes;
    }

    public List<String> getProfileNamespaces() {
        return profileNamespaces;
    }

    public boolean isAllowUnmapped() {
        return allowUnmapped;
    }

    public List<ProfileGroupPlanner.GroupSpec> getGroups() {
        return groups;
    }

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

    public int getWorkers() {
        return workers;
    }

    public boolean isExportTurtleReports() {
        return exportTurtleReports;
    }

    /** The collector the run reports its resource use to, or null when none is kept. */
    public RunStats getRunStats() {
        return runStats;
    }

    public static final class Builder {
        private Path modelsDir;
        private List<Path> profileLibraries = List.of();
        private Path outputDir;
        private List<String> boundaryFolders = List.of();
        private String boundaryShapesPattern = DEFAULT_BOUNDARY_SHAPES_PATTERN;
        private List<String> commonShapes;
        private List<String> profileNamespaces = InstanceHeaderReader.DEFAULT_PROFILE_NAMESPACES;
        private boolean allowUnmapped;
        private List<ProfileGroupPlanner.GroupSpec> groups = List.of();
        // At most one of the three datatype map sources is set; each setter clears the others.
        private DatatypeMapPreset datatypeMapPreset = DatatypeMapPreset.NONE;
        private Path datatypeMapFile;
        private Map<String, RDFDatatype> datatypeMap;
        private String xmlBase;
        private ValidationEngine engine = ValidationEngine.APACHE_JENA;
        private int maxResultsPerConstraint;
        private int workers;
        private boolean exportTurtleReports;
        private RunStats runStats;

        /** The folder that holds the instance documents ({@code .xml}, or {@code .zip} archives of them), at any depth. */
        public Builder modelsDir(Path folder) {
            this.modelsDir = folder;
            return this;
        }

        /** The folders that hold the DX-PROF descriptors and the SHACL files they name. */
        public Builder profileLibraries(List<Path> folders) {
            this.profileLibraries = folders == null ? List.of() : folders;
            return this;
        }

        public Builder profileLibraries(Path... folders) {
            return profileLibraries(List.of(folders));
        }

        /** Where the Excel report, and the Turtle reports if asked for, are written; null writes none. */
        public Builder outputDir(Path folder) {
            this.outputDir = folder;
            return this;
        }

        /** Folders of boundary data, relative to the models folder. */
        public Builder boundaryFolders(List<String> folders) {
            this.boundaryFolders = folders == null ? List.of() : folders;
            return this;
        }

        /** Shape files whose name contains this apply to boundary groups only; empty applies every shape everywhere. */
        public Builder boundaryShapesPattern(String pattern) {
            this.boundaryShapesPattern = pattern == null ? "" : pattern;
            return this;
        }

        /**
         * Shapes added to every group that reports a CGMES document: each entry is the name of a
         * file of a profile library, or the path of a file. An empty list adds none.
         */
        public Builder commonShapes(List<String> namesOrPaths) {
            this.commonShapes = namesOrPaths;
            return this;
        }

        /** IRI prefixes that mark a {@code dcterms:conformsTo} value as a profile. */
        public Builder profileNamespaces(List<String> prefixes) {
            this.profileNamespaces = prefixes == null ? List.of() : prefixes;
            return this;
        }

        /**
         * True to skip a document whose profile is unknown or lacks shapes, and report the run as
         * incomplete; false (the default) to refuse the run.
         */
        public Builder allowUnmapped(boolean allow) {
            this.allowUnmapped = allow;
            return this;
        }

        /** Explicit groups, replacing the grouping by folder. */
        public Builder groups(List<ProfileGroupPlanner.GroupSpec> groups) {
            this.groups = groups == null ? List.of() : groups;
            return this;
        }

        public Builder datatypeMap(DatatypeMapPreset preset) {
            this.datatypeMapPreset = preset == null ? DatatypeMapPreset.NONE : preset;
            this.datatypeMapFile = null;
            this.datatypeMap = null;
            return this;
        }

        public Builder datatypeMapFile(Path file) {
            this.datatypeMapFile = file;
            this.datatypeMapPreset = DatatypeMapPreset.NONE;
            this.datatypeMap = null;
            return this;
        }

        public Builder datatypeMap(Map<String, RDFDatatype> map) {
            this.datatypeMap = map;
            this.datatypeMapPreset = DatatypeMapPreset.NONE;
            this.datatypeMapFile = null;
            return this;
        }

        /** Base IRI for relative IRIs in the instance documents. */
        public Builder xmlBase(String xmlBase) {
            this.xmlBase = xmlBase;
            return this;
        }

        public Builder engine(ValidationEngine engine) {
            this.engine = engine == null ? ValidationEngine.APACHE_JENA : engine;
            return this;
        }

        /**
         * Limits the results of each constraint; zero means no limit. The limit applies to each
         * validation pass before its results are filtered, so with a limit a constraint's results
         * for a group may be fewer than the limit, or missing. Such a run is reported as partial.
         */
        public Builder maxResultsPerConstraint(int max) {
            this.maxResultsPerConstraint = max;
            return this;
        }

        public Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        /** True to also write one Turtle validation report per group. */
        public Builder exportTurtleReports(boolean export) {
            this.exportTurtleReports = export;
            return this;
        }

        public Builder runStats(RunStats runStats) {
            this.runStats = runStats;
            return this;
        }

        public ProfileValidationOptions build() {
            if (modelsDir == null) {
                throw new IllegalStateException("modelsDir must be provided");
            }
            if (profileLibraries.isEmpty()) {
                throw new IllegalStateException("at least one profile library must be provided");
            }
            if (xmlBase == null || xmlBase.isBlank()) {
                throw new IllegalStateException("xmlBase must not be blank");
            }
            if (maxResultsPerConstraint < 0) {
                throw new IllegalStateException("maxResultsPerConstraint must be zero (no limit) or greater");
            }
            if (workers < 0) {
                throw new IllegalStateException("workers must be zero (automatic) or greater");
            }
            return new ProfileValidationOptions(this);
        }
    }
}
