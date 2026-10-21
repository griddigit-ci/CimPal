/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Options for mapping the IDs of one CIM/CGMES model onto another with {@link IdMapper}.
 * <p>
 * The <em>model 1</em> side is the model whose IDs are mapped (its IDs fill the {@code Model1_ID} column),
 * the <em>model 2</em> side is the model the IDs are looked up in ({@code Model2_ID}). Each side is the
 * union of its files, for example two EQ files on one side and one merged EQ on the other.
 * {@code commonFiles} (boundary set, common data with the BaseVoltages) are loaded into both sides
 * and are not mapped themselves; their objects have the same IDs on both sides and anchor the
 * matching.
 */
public final class IdMappingOptions {

    /** Which name of the matched model 2 object goes into the {@code Model2_2nd_name} column. */
    public enum SecondName { NAME, SHORT_NAME, DESCRIPTION }

    private final List<Path> model1Files;
    private final List<Path> model2Files;
    private final List<Path> commonFiles;
    private final String xmlBase;
    private final Path queryFolder;
    private final Path nameAliasFile;
    private final boolean useNames;
    private final boolean matchSameIds;
    private final boolean collapsePassThroughSubstations;
    private final boolean groupStations;
    private final boolean includeTerminals;
    private final boolean includeNodes;
    private final boolean includeDependents;
    private final boolean includeOthers;
    private final double parameterTolerance;
    private final double voltageTolerance;
    private final SecondName secondName;

    private IdMappingOptions(Builder b) {
        this.model1Files = List.copyOf(b.model1Files);
        this.model2Files = List.copyOf(b.model2Files);
        this.commonFiles = List.copyOf(b.commonFiles);
        this.xmlBase = b.xmlBase;
        this.queryFolder = b.queryFolder;
        this.nameAliasFile = b.nameAliasFile;
        this.useNames = b.useNames;
        this.matchSameIds = b.matchSameIds;
        this.collapsePassThroughSubstations = b.collapsePassThroughSubstations;
        this.groupStations = b.groupStations;
        this.includeTerminals = b.includeTerminals;
        this.includeNodes = b.includeNodes;
        this.includeDependents = b.includeDependents;
        this.includeOthers = b.includeOthers;
        this.parameterTolerance = b.parameterTolerance;
        this.voltageTolerance = b.voltageTolerance;
        this.secondName = b.secondName;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Files of the model whose IDs are mapped (the {@code Model1_ID} side). */
    public List<Path> getModel1Files() {
        return model1Files;
    }

    /** Files of the model the IDs are mapped to (the {@code Model2_ID} side). */
    public List<Path> getModel2Files() {
        return model2Files;
    }

    /** Files loaded into both sides and not mapped (boundary set, common data). */
    public List<Path> getCommonFiles() {
        return commonFiles;
    }

    public String getXmlBase() {
        return xmlBase;
    }

    /** Folder with edited copies of the bundled queries; null uses the bundled ones. */
    public Path getQueryFolder() {
        return queryFolder;
    }

    /** CSV of substation name aliases (one station per row); null for none. */
    public Path getNameAliasFile() {
        return nameAliasFile;
    }

    /** Whether equal, unique substation names seed the matching. */
    public boolean isUseNames() {
        return useNames;
    }

    /** Whether objects with the same ID and class on both sides are matched first. */
    public boolean isMatchSameIds() {
        return matchSameIds;
    }

    /**
     * Whether substations that only join line sections (no busbar, transformer or other
     * equipment) are treated as part of the line, so a line split by such a junction on one side
     * still matches the unsplit line on the other.
     */
    public boolean isCollapsePassThroughSubstations() {
        return collapsePassThroughSubstations;
    }

    /**
     * Whether substations linked by a transformer or a switch are treated as one station, so a
     * station kept as one substation in one model and as several (one per voltage level) in the
     * other still matches.
     */
    public boolean isGroupStations() {
        return groupStations;
    }

    public boolean isIncludeTerminals() {
        return includeTerminals;
    }

    /** Whether ConnectivityNodes (or TopologicalNodes) are reported. */
    public boolean isIncludeNodes() {
        return includeNodes;
    }

    /** Whether dependent objects (tap changers, limits, controls, units, ...) are mapped. */
    public boolean isIncludeDependents() {
        return includeDependents;
    }

    /**
     * Whether objects outside the topology (limit types, regions, load groups, ...) are mapped and
     * reported: through the matched objects that refer to them, else when class and name are
     * unique on both sides.
     */
    public boolean isIncludeOthers() {
        return includeOthers;
    }

    /** Relative tolerance for r, x, bch and length when comparing lines (0.05 = 5 %). */
    public double getParameterTolerance() {
        return parameterTolerance;
    }

    /** Absolute tolerance in kV when comparing nominal voltages. */
    public double getVoltageTolerance() {
        return voltageTolerance;
    }

    public SecondName getSecondName() {
        return secondName;
    }

    public static final class Builder {
        private final List<Path> model1Files = new ArrayList<>();
        private final List<Path> model2Files = new ArrayList<>();
        private final List<Path> commonFiles = new ArrayList<>();
        private String xmlBase = "http://iec.ch/TC57/CIM100";
        private Path queryFolder;
        private Path nameAliasFile;
        private boolean useNames = true;
        private boolean matchSameIds = true;
        private boolean collapsePassThroughSubstations = true;
        private boolean groupStations = true;
        private boolean includeTerminals = true;
        private boolean includeNodes = true;
        private boolean includeDependents = true;
        private boolean includeOthers = true;
        private double parameterTolerance = 0.05;
        private double voltageTolerance = 1.0;
        private SecondName secondName = SecondName.NAME;

        private Builder() {
        }

        public Builder model1Files(List<Path> files) {
            model1Files.addAll(files);
            return this;
        }

        public Builder model1File(Path file) {
            model1Files.add(file);
            return this;
        }

        public Builder model2Files(List<Path> files) {
            model2Files.addAll(files);
            return this;
        }

        public Builder model2File(Path file) {
            model2Files.add(file);
            return this;
        }

        public Builder commonFiles(List<Path> files) {
            commonFiles.addAll(files);
            return this;
        }

        public Builder xmlBase(String xmlBase) {
            this.xmlBase = xmlBase;
            return this;
        }

        public Builder queryFolder(Path queryFolder) {
            this.queryFolder = queryFolder;
            return this;
        }

        public Builder nameAliasFile(Path nameAliasFile) {
            this.nameAliasFile = nameAliasFile;
            return this;
        }

        public Builder useNames(boolean useNames) {
            this.useNames = useNames;
            return this;
        }

        public Builder matchSameIds(boolean matchSameIds) {
            this.matchSameIds = matchSameIds;
            return this;
        }

        public Builder collapsePassThroughSubstations(boolean collapse) {
            this.collapsePassThroughSubstations = collapse;
            return this;
        }

        public Builder groupStations(boolean groupStations) {
            this.groupStations = groupStations;
            return this;
        }

        public Builder includeTerminals(boolean includeTerminals) {
            this.includeTerminals = includeTerminals;
            return this;
        }

        public Builder includeNodes(boolean includeNodes) {
            this.includeNodes = includeNodes;
            return this;
        }

        public Builder includeDependents(boolean includeDependents) {
            this.includeDependents = includeDependents;
            return this;
        }

        public Builder includeOthers(boolean includeOthers) {
            this.includeOthers = includeOthers;
            return this;
        }

        public Builder parameterTolerance(double parameterTolerance) {
            this.parameterTolerance = parameterTolerance;
            return this;
        }

        public Builder voltageTolerance(double voltageTolerance) {
            this.voltageTolerance = voltageTolerance;
            return this;
        }

        public Builder secondName(SecondName secondName) {
            this.secondName = secondName;
            return this;
        }

        /**
         * @throws IllegalArgumentException when a side has no files or a tolerance is negative
         */
        public IdMappingOptions build() {
            if (model1Files.isEmpty()) {
                throw new IllegalArgumentException("No model 1 files given");
            }
            if (model2Files.isEmpty()) {
                throw new IllegalArgumentException("No model 2 files given");
            }
            if (!(parameterTolerance >= 0) || !(voltageTolerance >= 0)) {
                throw new IllegalArgumentException("Tolerances must be zero or positive");
            }
            if (secondName == null) {
                secondName = SecondName.NAME;
            }
            return new IdMappingOptions(this);
        }
    }
}
