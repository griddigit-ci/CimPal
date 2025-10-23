package eu.griddigit.cimpal.core.models;

public class RdfsModelDefinition {
    private String modelName;
    private String nsPrefix;
    private String nsUri;
    private String baseUri;
    private String owlImport;

    public RdfsModelDefinition(
            String modelName,
            String nsPrefix,
            String nsUri,
            String baseUri,
            String owlImport
    ) {
        this.modelName = modelName;
        this.nsPrefix = nsPrefix;
        this.nsUri = nsUri;
        this.baseUri = baseUri;
        this.owlImport = owlImport;
    }

    public String getModelName() {
        return modelName;
    }

    public String getNsPrefix() {
        return nsPrefix;
    }

    public String getNsUri() {
        return nsUri;
    }

    public String getBaseUri() {
        return baseUri;
    }

    public String getOwlImport() {
        return owlImport;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setNsPrefix(String nsPrefix) {
        this.nsPrefix = nsPrefix;
    }

    public void setNsUri(String nsUri) {
        this.nsUri = nsUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }

    public void setOwlImport(String owlImport) {
        this.owlImport = owlImport;
    }
}
