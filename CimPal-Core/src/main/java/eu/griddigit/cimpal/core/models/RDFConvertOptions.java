package eu.griddigit.cimpal.core.models;

import org.apache.jena.riot.RDFFormat;

import java.io.File;
import java.util.List;

public class RDFConvertOptions {
    public enum RDFFormats {
        RDFXML, TURTLE, JSONLD
    }

    private final File sourceFile;
    private final List<File> modelUnionFiles;
    private final List<File> modelUnionDetailedFiles;
    private final RDFFormats sourceFormat;
    private final RDFFormats targetFormat;
    private final String xmlBase;
    private final RDFFormat rdfXmlFormat;
    private final String showXmlDeclaration;
    private final String showDoctypeDeclaration;
    private final String tabCharacter;
    private final String relativeURIs;
    private final boolean modelUnionFlag;
    private final boolean inheritanceOnly;
    private final boolean inheritanceList;
    private final boolean isInheritanceListConcrete;
    private final boolean addOwl;
    private final boolean modelUnionFlagDetailed;
    private final String sortRDF;
    private final String rdfSortOptions;
    private final boolean stripPrefixes;
    private final String convertInstanceData;
    private final boolean modelUnionFixPackage;

    // private constructor takes the Builder
    private RDFConvertOptions(Builder b) {
        this.sourceFile = b.sourceFile;
        this.modelUnionFiles = b.modelUnionFiles;
        this.modelUnionDetailedFiles = b.modelUnionDetailedFiles;
        this.sourceFormat = b.sourceFormat;
        this.targetFormat = b.targetFormat;
        this.xmlBase = b.xmlBase;
        this.rdfXmlFormat = b.rdfXmlFormat;
        this.showXmlDeclaration = b.showXmlDeclaration;
        this.showDoctypeDeclaration = b.showDoctypeDeclaration;
        this.tabCharacter = b.tabCharacter;
        this.relativeURIs = b.relativeURIs;
        this.modelUnionFlag = b.modelUnionFlag;
        this.inheritanceOnly = b.inheritanceOnly;
        this.inheritanceList = b.inheritanceList;
        this.isInheritanceListConcrete = b.isInheritanceListConcrete;
        this.addOwl = b.addOwl;
        this.modelUnionFlagDetailed = b.modelUnionFlagDetailed;
        this.sortRDF = b.sortRDF;
        this.rdfSortOptions = b.rdfSortOptions;
        this.stripPrefixes = b.stripPrefixes;
        this.convertInstanceData = b.convertInstanceData;
        this.modelUnionFixPackage = b.modelUnionFixPackage;
    }

    // static entry-point for the builder
    public static Builder builder() {
        return new Builder();
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public List<File> getModelUnionFiles() {
        return modelUnionFiles;
    }

    public List<File> getModelUnionDetailedFiles() {
        return modelUnionDetailedFiles;
    }

    public RDFFormats getSourceFormat() {
        return sourceFormat;
    }

    public RDFFormats getTargetFormat() {
        return targetFormat;
    }

    public String getXmlBase() {
        return xmlBase;
    }

    public RDFFormat getRdfXmlFormat() {
        return rdfXmlFormat;
    }

    public String getShowXmlDeclaration() {
        return showXmlDeclaration;
    }

    public String getShowDoctypeDeclaration() {
        return showDoctypeDeclaration;
    }

    public String getTabCharacter() {
        return tabCharacter;
    }

    public String getRelativeURIs() {
        return relativeURIs;
    }

    public boolean isModelUnionFlag() {
        return modelUnionFlag;
    }

    public boolean isInheritanceOnly() {
        return inheritanceOnly;
    }

    public boolean isInheritanceList() {
        return inheritanceList;
    }

    public boolean isInheritanceListConcrete() {
        return isInheritanceListConcrete;
    }

    public boolean isAddOwl() {
        return addOwl;
    }

    public boolean isModelUnionFlagDetailed() {
        return modelUnionFlagDetailed;
    }

    public String getSortRDF() {
        return sortRDF;
    }

    public String getRdfSortOptions() {
        return rdfSortOptions;
    }

    public boolean isStripPrefixes() {
        return stripPrefixes;
    }

    public String getConvertInstanceData() {
        return convertInstanceData;
    }

    public boolean isModelUnionFixPackage() {
        return modelUnionFixPackage;
    }


    // ============ the Builder ============

    public static class Builder {
        // same fields, but non-final, with defaults
        private File sourceFile = null;
        private List<File> modelUnionFiles = null;
        private List<File> modelUnionDetailedFiles = null;
        private RDFFormats sourceFormat = null;
        private RDFFormats targetFormat = null;
        private String xmlBase = null;
        private RDFFormat rdfXmlFormat = null;
        private String showXmlDeclaration = null;
        private String showDoctypeDeclaration = null;
        private String tabCharacter = null;
        private String relativeURIs = null;
        private boolean modelUnionFlag = false;
        private boolean inheritanceOnly = false;
        private boolean inheritanceList = false;
        private boolean isInheritanceListConcrete = false;
        private boolean addOwl = false;
        private boolean modelUnionFlagDetailed = false;
        private String sortRDF = null;
        private String rdfSortOptions = null;
        private boolean stripPrefixes = false;
        private String convertInstanceData = null;
        private boolean modelUnionFixPackage = false;

        public Builder sourceFile(File sourceFile) {
            this.sourceFile = sourceFile;
            return this;
        }

        public Builder modelUnionFiles(List<File> modelUnionFiles) {
            this.modelUnionFiles = modelUnionFiles;
            return this;
        }

        public Builder modelUnionDetailedFiles(List<File> modelUnionDetailedFiles) {
            this.modelUnionDetailedFiles = modelUnionDetailedFiles;
            return this;
        }

        public Builder sourceFormat(RDFFormats sourceFormat) {
            this.sourceFormat = sourceFormat;
            return this;
        }

        public Builder targetFormat(RDFFormats targetFormat) {
            this.targetFormat = targetFormat;
            return this;
        }

        // the rest are optional
        public Builder xmlBase(String xmlBase) {
            this.xmlBase = xmlBase;
            return this;
        }

        public Builder rdfXmlFormat(RDFFormat rdfXmlFormat) {
            this.rdfXmlFormat = rdfXmlFormat;
            return this;
        }

        public Builder showXmlDeclaration(String flag) {
            this.showXmlDeclaration = flag;
            return this;
        }

        public Builder showDoctypeDeclaration(String flag) {
            this.showDoctypeDeclaration = flag;
            return this;
        }

        public Builder tabCharacter(String tabCharacter) {
            this.tabCharacter = tabCharacter;
            return this;
        }

        public Builder relativeURIs(String relativeURIs) {
            this.relativeURIs = relativeURIs;
            return this;
        }

        public Builder modelUnionFlag(boolean on) {
            this.modelUnionFlag = on;
            return this;
        }

        public Builder inheritanceOnly(boolean on) {
            this.inheritanceOnly = on;
            return this;
        }

        public Builder inheritanceList(boolean on) {
            this.inheritanceList = on;
            return this;
        }

        public Builder inheritanceListConcrete(boolean on) {
            this.isInheritanceListConcrete = on;
            return this;
        }

        public Builder addOwl(boolean on) {
            this.addOwl = on;
            return this;
        }

        public Builder modelUnionFlagDetailed(boolean on) {
            this.modelUnionFlagDetailed = on;
            return this;
        }

        public Builder sortRDF(String sortRDF) {
            this.sortRDF = sortRDF;
            return this;
        }

        public Builder rdfSortOptions(String opts) {
            this.rdfSortOptions = opts;
            return this;
        }

        public Builder stripPrefixes(boolean on) {
            this.stripPrefixes = on;
            return this;
        }

        public Builder convertInstanceData(String dataOpt) {
            this.convertInstanceData = dataOpt;
            return this;
        }

        public Builder modelUnionFixPackage(boolean on) {
            this.modelUnionFixPackage = on;
            return this;
        }

        /**
         * Builds the immutable RDFConvertOptions, validating required fields
         */
        public RDFConvertOptions build() {
            if (sourceFormat == null) {
                throw new IllegalStateException("sourceFormat must not be null");
            }
            if (sourceFile == null && !modelUnionFlag) {
                throw new IllegalStateException("sourceFile must not be null");
            }
            if (xmlBase == null) {
                throw new IllegalStateException("xmlBase must not be null");
            }
            if (targetFormat == null) {
                throw new IllegalStateException("targetFormat must not be null");
            }
            if (targetFormat == RDFFormats.RDFXML && rdfXmlFormat == null) {
                throw new IllegalStateException("rdfXmlFormat must not be null when targetFormat is RDFXML");
            }
            if (modelUnionFlagDetailed) {
                if (modelUnionDetailedFiles == null || modelUnionDetailedFiles.isEmpty()) {
                    throw new IllegalStateException("modelUnionDetailedFiles must not be null or empty when modelUnionFlagDetailed is true");
                }
                if (modelUnionFlag) {
                    if (modelUnionFiles == null || modelUnionFiles.isEmpty()) {
                        throw new IllegalStateException("modelUnionFiles must not be null or empty when modelUnionFlag is true");
                    }
                }
            }

            // settings for saving RDF

            return new RDFConvertOptions(this);
        }
    }
}

