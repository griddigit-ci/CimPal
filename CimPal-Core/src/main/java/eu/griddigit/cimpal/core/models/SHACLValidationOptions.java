/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.Model;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Options for validating one dataset against a set of SHACL shapes with
 * {@link eu.griddigit.cimpal.core.utils.SHACLValidator}.
 * <p>
 * The data is the union of {@code dataFiles} and {@code dataModel}; the shapes are the union of
 * {@code shapeFiles} (with their {@code owl:imports} followed) and {@code shapesModel}. Build one
 * with {@link #builder()}, or start from
 * {@link eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets} for CGMES data.
 */
public class SHACLValidationOptions {

    private final List<Path> dataFiles;
    private final Model dataModel;
    private final List<Path> shapeFiles;
    private final Model shapesModel;
    private final Path constraintsRoot;
    private final DatatypeMapPreset datatypeMapPreset;
    private final Path datatypeMapFile;
    private final Map<String, RDFDatatype> datatypeMap;
    private final String xmlBase;
    private final ValidationEngine engine;
    private final int maxResultsPerConstraint;
    private final int workers;

    private SHACLValidationOptions(Builder b) {
        this.dataFiles = b.dataFiles;
        this.dataModel = b.dataModel;
        this.shapeFiles = b.shapeFiles;
        this.shapesModel = b.shapesModel;
        this.constraintsRoot = b.constraintsRoot;
        this.datatypeMapPreset = b.datatypeMapPreset;
        this.datatypeMapFile = b.datatypeMapFile;
        this.datatypeMap = b.datatypeMap;
        this.xmlBase = b.xmlBase;
        this.engine = b.engine;
        this.maxResultsPerConstraint = b.maxResultsPerConstraint;
        this.workers = b.workers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Path> getDataFiles() {
        return dataFiles;
    }

    public Model getDataModel() {
        return dataModel;
    }

    public List<Path> getShapeFiles() {
        return shapeFiles;
    }

    public Model getShapesModel() {
        return shapesModel;
    }

    /** Folder that relative {@code owl:imports} are also resolved against; null means the first shape file's folder. */
    public Path getConstraintsRoot() {
        return constraintsRoot;
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

    public int getWorkers() {
        return workers;
    }

    // ============ the Builder ============

    public static class Builder {
        private List<Path> dataFiles = List.of();
        private Model dataModel = null;
        private List<Path> shapeFiles = List.of();
        private Model shapesModel = null;
        private Path constraintsRoot = null;
        // At most one of the three datatype map sources is set; each setter clears the others.
        private DatatypeMapPreset datatypeMapPreset = DatatypeMapPreset.NONE;
        private Path datatypeMapFile = null;
        private Map<String, RDFDatatype> datatypeMap = null;
        private String xmlBase = null;
        private ValidationEngine engine = ValidationEngine.APACHE_JENA;
        private int maxResultsPerConstraint = 0;
        private int workers = 0;

        /**
         * Instance data files: RDF/XML ({@code .xml}, {@code .rdf}), ZIP archives of RDF/XML
         * files, or any other RDF syntax Jena recognises by extension ({@code .ttl},
         * {@code .jsonld}, ...). All files, and any named graphs in them, are unioned into one
         * data graph.
         */
        public Builder dataFiles(List<Path> files) {
            this.dataFiles = files == null ? List.of() : List.copyOf(files);
            return this;
        }

        public Builder dataFiles(Path... files) {
            return dataFiles(files == null ? null : Arrays.asList(files));
        }

        /**
         * An already-loaded data model, unioned with {@link #dataFiles}. It is never modified;
         * when a datatype map is set, its plain string literals are typed in a copy.
         */
        public Builder dataModel(Model model) {
            this.dataModel = model;
            return this;
        }

        /** SHACL shape files (any RDF syntax); their {@code owl:imports} are followed. */
        public Builder shapeFiles(List<Path> files) {
            this.shapeFiles = files == null ? List.of() : List.copyOf(files);
            return this;
        }

        public Builder shapeFiles(Path... files) {
            return shapeFiles(files == null ? null : Arrays.asList(files));
        }

        /** An already-loaded shapes model, used as is: its {@code owl:imports} are not followed. */
        public Builder shapesModel(Model model) {
            this.shapesModel = model;
            return this;
        }

        /** Extra folder to resolve relative {@code owl:imports} against. Defaults to the first shape file's folder. */
        public Builder constraintsRoot(Path folder) {
            this.constraintsRoot = folder;
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

        /**
         * Base URI the data files are parsed with. Required with {@link #dataFiles}: CIM RDF/XML
         * uses relative {@code rdf:about="#_id"} references, which only join up across files
         * when every file is parsed with the same base.
         */
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
         * them all. A limited run is reported as partial and never as conforming.
         */
        public Builder maxResultsPerConstraint(int max) {
            this.maxResultsPerConstraint = max;
            return this;
        }

        /**
         * Threads that validate independent target shapes in parallel (Apache Jena only);
         * 0 (the default) uses one fewer than the available processors.
         */
        public Builder workers(int workers) {
            this.workers = workers;
            return this;
        }

        /**
         * Builds the immutable SHACLValidationOptions, validating required fields.
         */
        public SHACLValidationOptions build() {
            if (dataFiles.isEmpty() && dataModel == null) {
                throw new IllegalStateException("dataFiles or dataModel must be provided");
            }
            if (shapeFiles.isEmpty() && shapesModel == null) {
                throw new IllegalStateException("shapeFiles or shapesModel must be provided");
            }
            if (!dataFiles.isEmpty() && (xmlBase == null || xmlBase.isBlank())) {
                throw new IllegalStateException("xmlBase must not be blank when dataFiles are provided");
            }
            if (maxResultsPerConstraint < 0) {
                throw new IllegalStateException("maxResultsPerConstraint must be zero (no limit) or greater");
            }
            if (workers < 0) {
                throw new IllegalStateException("workers must be zero (automatic) or greater");
            }
            return new SHACLValidationOptions(this);
        }
    }
}
