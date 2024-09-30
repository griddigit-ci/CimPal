package core;


public class QAReport {
    private String created;
    private String schemeVersion;
    private String serviceProvider;
    private String rslVersion;
    private IGM igm;

    public void setCreated(String created) { this.created = created; }

    public void setSchemeVersion(String schemeVersion) { this.schemeVersion = schemeVersion; }

    public void setServiceProvider(String serviceProvider) { this.serviceProvider = serviceProvider; }

    public void setRslVersion(String rslVersion) { this.rslVersion = rslVersion; }

    public void setIgm(IGM igm) { this.igm = igm; }
}


