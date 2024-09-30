package core;


import java.util.List;

public class IGM {
    private String created;
    private String scenarioTime;
    private String tso;
    private String version;
    private String processType;
    private String qualityIndicator;
    private List<String> resources;

    public void setCreated(String created) { this.created = created; }

    public void setScenarioTime(String scenarioTime) { this.scenarioTime = scenarioTime; }

    public void setTso(String tso) { this.tso = tso; }

    public void setVersion(String version) { this.version = version; }

    public void setProcessType(String processType) { this.processType = processType; }

    public void setQualityIndicator(String qualityIndicator) { this.qualityIndicator = qualityIndicator; }

    public void setResources(List<String> resources) { this.resources = resources; }
}
