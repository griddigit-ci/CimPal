package eu.griddigit.cimpal.core.models;

import org.apache.jena.rdf.model.Model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RDFtoSHACLOptions {
    public enum SerializationFormat {
        TURTLE, RDFXML
    }

    public enum RdfsFormatShapes {
        RDFS_AUGMENTED_2019, RDFS_AUGMENTED_2020, CIMTOOL_MERGED_OWL
    }

    private final ArrayList<Model> rdfsModels;
    private final List<RdfsModelDefinition> rdfsModelDefinitions;

    private final SerializationFormat shaclOutputFormat;

    private final List<File> baseModelFiles1;
    private final List<File> baseModelFiles2;
    private final List<File> baseModelFiles3;

    private final boolean excludeMRID;
    private final boolean closedShapes;
    private final boolean splitDatatypes;
    private final boolean associationValueTypeOption;
    private final boolean associationValueTypeOptionSingle;
    private final boolean shapesOnAbstractOption;
    private final boolean exportInheritTree;
    private final boolean shaclURIDatatypeAsResource;
    private final boolean shaclSkipNcPropertyReference;
    private final boolean baseprofilesshaclglag;
    private final boolean baseprofilesshaclignorens;
    private final boolean baseprofilesshaclglag2nd;
    private final boolean baseprofilesshaclglag3rd;
    private final boolean shaclFlagInverse;
    private final boolean shaclFlagCount;
    private final boolean shaclFlagCountDefaultURI;
    private final boolean perProfile;

    private final RdfsFormatShapes rdfsFormatShapes;

    private final String shaclCommonPref;
    private final String shaclCommonURI;
    private final String cimsNamespace;
    private final String iOprefix; // the uri for the identified object related shapes
    private final String iOuri;

    private RDFtoSHACLOptions(Builder b) {
        this.rdfsModels = b.rdfsModels;
        this.rdfsModelDefinitions = b.rdfsModelDefinitions;
        this.shaclOutputFormat = b.shaclOutputFormat;
        this.baseModelFiles1 = b.baseModelFiles1;
        this.baseModelFiles2 = b.baseModelFiles2;
        this.baseModelFiles3 = b.baseModelFiles3;
        this.excludeMRID = b.excludeMRID;
        this.closedShapes = b.closedShapes;
        this.splitDatatypes = b.splitDatatypes;
        this.associationValueTypeOption = b.associationValueTypeOption;
        this.associationValueTypeOptionSingle = b.associationValueTypeOptionSingle;
        this.shapesOnAbstractOption = b.shapesOnAbstractOption;
        this.exportInheritTree = b.exportInheritTree;
        this.shaclURIDatatypeAsResource = b.shaclURIDatatypeAsResource;
        this.shaclSkipNcPropertyReference = b.shaclSkipNcPropertyReference;
        this.baseprofilesshaclglag = b.baseprofilesshaclglag;
        this.baseprofilesshaclignorens = b.baseprofilesshaclignorens;
        this.baseprofilesshaclglag2nd = b.baseprofilesshaclglag2nd;
        this.baseprofilesshaclglag3rd = b.baseprofilesshaclglag3rd;
        this.shaclFlagInverse = b.shaclFlagInverse;
        this.shaclFlagCount = b.shaclFlagCount;
        this.shaclFlagCountDefaultURI = b.shaclFlagCountDefaultURI;
        this.perProfile = b.perProfile;
        this.rdfsFormatShapes = b.rdfsFormatShapes;
        this.shaclCommonPref = b.shaclCommonPref;
        this.shaclCommonURI = b.shaclCommonURI;
        this.cimsNamespace = b.cimsNamespace;
        this.iOprefix = b.iOprefix;
        this.iOuri = b.iOuri;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ArrayList<Model> getRdfsModels() {
        return rdfsModels;
    }

    public List<RdfsModelDefinition> getRdfsModelDefinitions() {
        return rdfsModelDefinitions;
    }

    public SerializationFormat getShaclOutputFormat() {
        return shaclOutputFormat;
    }

    public List<File> getBaseModelFiles1() {
        return baseModelFiles1;
    }

    public List<File> getBaseModelFiles2() {
        return baseModelFiles2;
    }

    public List<File> getBaseModelFiles3() {
        return baseModelFiles3;
    }

    public boolean isExcludeMRID() {
        return excludeMRID;
    }

    public boolean isClosedShapes() {
        return closedShapes;
    }

    public boolean isSplitDatatypes() {
        return splitDatatypes;
    }

    public boolean isAssociationValueTypeOption() {
        return associationValueTypeOption;
    }

    public boolean isAssociationValueTypeOptionSingle() {
        return associationValueTypeOptionSingle;
    }

    public boolean isShapesOnAbstractOption() {
        return shapesOnAbstractOption;
    }

    public boolean isExportInheritTree() {
        return exportInheritTree;
    }

    public boolean isShaclURIDatatypeAsResource() {
        return shaclURIDatatypeAsResource;
    }

    public boolean isShaclSkipNcPropertyReference() {
        return shaclSkipNcPropertyReference;
    }

    public boolean isBaseprofilesshaclglag() {
        return baseprofilesshaclglag;
    }

    public boolean isBaseprofilesshaclignorens() {
        return baseprofilesshaclignorens;
    }

    public boolean isBaseprofilesshaclglag2nd() {
        return baseprofilesshaclglag2nd;
    }

    public boolean isBaseprofilesshaclglag3rd() {
        return baseprofilesshaclglag3rd;
    }

    public boolean isShaclFlagInverse() {
        return shaclFlagInverse;
    }

    public boolean isShaclFlagCount() {
        return shaclFlagCount;
    }

    public boolean isShaclFlagCountDefaultURI() {
        return shaclFlagCountDefaultURI;
    }

    public boolean isPerProfile() {
        return perProfile;
    }

    public RdfsFormatShapes getRdfsFormatShapes() {
        return rdfsFormatShapes;
    }

    public String getShaclCommonPref() {
        return shaclCommonPref;
    }

    public String getShaclCommonURI() {
        return shaclCommonURI;
    }

    public String getCimsNamespace() {
        return cimsNamespace;
    }

    public String getiOprefix() {
        return iOprefix;
    }

    public String getiOuri() {
        return iOuri;
    }

    // ============ the Builder ============

    public static class Builder {
        private ArrayList<Model> rdfsModels = null;
        private List<RdfsModelDefinition> rdfsModelDefinitions;

        private SerializationFormat shaclOutputFormat = SerializationFormat.TURTLE;

        private List<File> baseModelFiles1 = null;
        private List<File> baseModelFiles2 = null;
        private List<File> baseModelFiles3 = null;

        private boolean excludeMRID = false;
        private boolean closedShapes = false;
        private boolean splitDatatypes = false;
        private boolean associationValueTypeOption = false;
        private boolean associationValueTypeOptionSingle = false;
        private boolean shapesOnAbstractOption = false;
        private boolean exportInheritTree = false;
        private boolean shaclURIDatatypeAsResource = false;
        private boolean shaclSkipNcPropertyReference = false;
        private boolean baseprofilesshaclglag = false;
        private boolean baseprofilesshaclignorens = false;
        private boolean baseprofilesshaclglag2nd = false;
        private boolean baseprofilesshaclglag3rd = false;
        private boolean shaclFlagInverse = false;
        private boolean shaclFlagCount = false;
        private boolean shaclFlagCountDefaultURI = false;
        private boolean perProfile = false;

        private RdfsFormatShapes rdfsFormatShapes = null;

        private String shaclCommonPref = "";
        private String shaclCommonURI = "";
        private String cimsNamespace = "";
        private String iOprefix = ""; // the uri for the identified object related shapes
        private String iOuri = "";

        public Builder rdfsModels(ArrayList<Model> val) {
            rdfsModels = val;
            return this;
        }

        public Builder rdfsModelDefinitions(List<RdfsModelDefinition> val) {
            rdfsModelDefinitions = val;
            return this;
        }

        public Builder shaclOutputFormat(SerializationFormat val) {
            shaclOutputFormat = val;
            return this;
        }

        public Builder baseModelFiles1(List<File> val) {
            baseModelFiles1 = val;
            return this;
        }

        public Builder baseModelFiles2(List<File> val) {
            baseModelFiles2 = val;
            return this;
        }

        public Builder baseModelFiles3(List<File> val) {
            baseModelFiles3 = val;
            return this;
        }

        public Builder excludeMRID(boolean val) {
            excludeMRID = val;
            return this;
        }

        public Builder closedShapes(boolean val) {
            closedShapes = val;
            return this;
        }

        public Builder splitDatatypes(boolean val) {
            splitDatatypes = val;
            return this;
        }

        public Builder associationValueTypeOption(boolean val) {
            associationValueTypeOption = val;
            return this;
        }

        public Builder associationValueTypeOptionSingle(boolean val) {
            associationValueTypeOptionSingle = val;
            return this;
        }

        public Builder shapesOnAbstractOption(boolean val) {
            shapesOnAbstractOption = val;
            return this;
        }

        public Builder exportInheritTree(boolean val) {
            exportInheritTree = val;
            return this;
        }

        public Builder shaclURIDatatypeAsResource(boolean val) {
            shaclURIDatatypeAsResource = val;
            return this;
        }

        public Builder shaclSkipNcPropertyReference(boolean val) {
            shaclSkipNcPropertyReference = val;
            return this;
        }

        public Builder baseprofilesshaclglag(boolean val) {
            baseprofilesshaclglag = val;
            return this;
        }

        public Builder baseprofilesshaclignorens(boolean val) {
            baseprofilesshaclignorens = val;
            return this;
        }

        public Builder baseprofilesshaclglag2nd(boolean val) {
            baseprofilesshaclglag2nd = val;
            return this;
        }

        public Builder baseprofilesshaclglag3rd(boolean val) {
            baseprofilesshaclglag3rd = val;
            return this;
        }

        public Builder shaclFlagInverse(boolean val) {
            shaclFlagInverse = val;
            return this;
        }

        public Builder shaclFlagCount(boolean val) {
            shaclFlagCount = val;
            return this;
        }

        public Builder shaclFlagCountDefaultURI(boolean val) {
            shaclFlagCountDefaultURI = val;
            return this;
        }

        public Builder perProfile(boolean val) {
            perProfile = val;
            return this;
        }

        public Builder rdfsFormatShapes(RdfsFormatShapes val) {
            rdfsFormatShapes = val;
            return this;
        }

        public Builder shaclCommonPref(String val) {
            shaclCommonPref = val;
            return this;
        }

        public Builder shaclCommonURI(String val) {
            shaclCommonURI = val;
            return this;
        }

        public Builder cimsNamespace(String val) {
            cimsNamespace = val;
            return this;
        }

        public Builder iOprefix(String val) {
            iOprefix = val;
            return this;
        }

        public Builder iOuri(String val) {
            iOuri = val;
            return this;
        }

        public RDFtoSHACLOptions build() {
            if (rdfsModels == null || rdfsModels.isEmpty()) {
                throw new IllegalStateException("RDFS models list cannot be null or empty.");
            }
            if (rdfsModelDefinitions == null || rdfsModelDefinitions.isEmpty()) {
                throw new IllegalStateException("RDFS model definitions list cannot be null or empty.");
            }
            if (baseprofilesshaclglag && baseModelFiles1.isEmpty()) {
                throw new IllegalStateException("Base profile SHACL flag is set, but no base model files provided in list 1.");
            }
            if (baseprofilesshaclglag2nd && baseModelFiles2.isEmpty()) {
                throw new IllegalStateException("Base profile SHACL 2nd flag is set, but no base model files provided in list 2.");
            }
            if (baseprofilesshaclglag3rd && baseModelFiles3.isEmpty()) {
                throw new IllegalStateException("Base profile SHACL 3rd flag is set, but no base model files provided in list 3.");
            }
            if (iOprefix == null || iOprefix.isEmpty()) {
                throw new IllegalStateException("Identified Object prefix cannot be null or empty.");
            }
            if (iOuri == null || iOuri.isEmpty()) {
                throw new IllegalStateException("Identified Object URI cannot be null or empty.");
            }
            if (shaclFlagCount && !shaclFlagCountDefaultURI && shaclCommonURI.isEmpty() && shaclCommonPref.isEmpty()) {
                throw new IllegalStateException("Shacl common URI and prefix cannot be empty when shaclFlagCountDefaultURI is disabled.");
            }

            return new RDFtoSHACLOptions(this);
        }
    }
}
