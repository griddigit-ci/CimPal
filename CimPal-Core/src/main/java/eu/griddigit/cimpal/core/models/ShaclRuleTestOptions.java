/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import org.apache.jena.datatypes.RDFDatatype;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Options for testing SHACL rules against a test suite with
 * {@link eu.griddigit.cimpal.core.utils.ShaclRuleTester}.
 * <p>
 * The suite folder holds one folder per rule, named after the rule's {@code sh:name}. Each rule
 * folder holds a {@code Conform} folder of models that must not trigger the rule and a
 * {@code NonConform} folder of models that must. A model is a ZIP archive whose {@code .xml}
 * files are read. Build the options with {@link #builder()}.
 */
public final class ShaclRuleTestOptions {

    private final List<Path> shapeFiles;
    private final Path suiteFolder;
    private final DatatypeMapPreset datatypeMapPreset;
    private final Path datatypeMapFile;
    private final Map<String, RDFDatatype> datatypeMap;
    private final String xmlBase;
    private final boolean excelReports;
    private final boolean turtleReports;
    private final int workers;

    private ShaclRuleTestOptions(Builder b) {
        this.shapeFiles = b.shapeFiles;
        this.suiteFolder = b.suiteFolder;
        this.datatypeMapPreset = b.datatypeMapPreset;
        this.datatypeMapFile = b.datatypeMapFile;
        this.datatypeMap = b.datatypeMap;
        this.xmlBase = b.xmlBase;
        this.excelReports = b.excelReports;
        this.turtleReports = b.turtleReports;
        this.workers = b.workers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Path> getShapeFiles() {
        return shapeFiles;
    }

    public Path getSuiteFolder() {
        return suiteFolder;
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

    public boolean isExcelReports() {
        return excelReports;
    }

    public boolean isTurtleReports() {
        return turtleReports;
    }

    public int getWorkers() {
        return workers;
    }

    // ============ the Builder ============

    public static class Builder {
        private List<Path> shapeFiles = List.of();
        private Path suiteFolder = null;
        // At most one of the three datatype map sources is set; each setter clears the others.
        private DatatypeMapPreset datatypeMapPreset = DatatypeMapPreset.NONE;
        private Path datatypeMapFile = null;
        private Map<String, RDFDatatype> datatypeMap = null;
        private String xmlBase = null;
        private boolean excelReports = true;
        private boolean turtleReports = false;
        private int workers = 0;

        /**
         * SHACL shape files ({@code .ttl}, {@code .rdf}) or ZIP archives of them, read as one
         * shapes graph; their {@code owl:imports} are followed.
         */
        public Builder shapeFiles(List<Path> files) {
            this.shapeFiles = files == null ? List.of() : List.copyOf(files);
            return this;
        }

        public Builder shapeFiles(Path... files) {
            return shapeFiles(files == null ? null : Arrays.asList(files));
        }

        /** The test suite: a folder per rule, each with a Conform and a NonConform folder of models. */
        public Builder suiteFolder(Path folder) {
            this.suiteFolder = folder;
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

        /** Base URI the models are parsed with. Required. */
        public Builder xmlBase(String xmlBase) {
            this.xmlBase = xmlBase;
            return this;
        }

        /** Writes each model's validation report beside it as {@code <model>_report.xlsx}. On by default. */
        public Builder excelReports(boolean write) {
            this.excelReports = write;
            return this;
        }

        /** Also writes each model's SHACL report graph beside it as {@code <model>_report.ttl}. */
        public Builder turtleReports(boolean write) {
            this.turtleReports = write;
            return this;
        }

        /**
         * Threads that validate in total. 0 (the default) validates as many models at once as
         * the Java heap allows and gives the remaining processors to their shapes.
         */
        public Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        /**
         * Builds the immutable ShaclRuleTestOptions, validating required fields.
         */
        public ShaclRuleTestOptions build() {
            if (shapeFiles.isEmpty()) {
                throw new IllegalStateException("shapeFiles must be provided");
            }
            if (suiteFolder == null) {
                throw new IllegalStateException("suiteFolder must be provided");
            }
            if (xmlBase == null || xmlBase.isBlank()) {
                throw new IllegalStateException("xmlBase must not be blank");
            }
            if (workers < 0) {
                throw new IllegalStateException("workers must be zero (automatic) or greater");
            }
            return new ShaclRuleTestOptions(this);
        }
    }
}
