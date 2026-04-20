package eu.griddigit.cimpal.main.application.datagenerator.resources;

import org.apache.jena.rdf.model.Model;

import java.util.StringJoiner;

public class BaseInstanceModel {
    // indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
    private Model baseInstanceModel;
    private String fileName;
    private String datetime;
    private String process;
    private String tso;
    private String profile;
    private String version;

    public BaseInstanceModel(String fileName) {
        this.fileName = fileName;
        String[] originalNameInParts = fileName.split("_",5);
        datetime = originalNameInParts[0];
        process = originalNameInParts[1];
        tso = originalNameInParts[2];
        profile = originalNameInParts[3];
        version = originalNameInParts[4];
    }

    public BaseInstanceModel(String datetime, String process, String tso, String profile, String version) {
        this.datetime = datetime;
        this.process = process;
        this.tso = tso;
        this.profile = profile;
        this.version = version;
        StringJoiner joiner = new StringJoiner("_");
        joiner.add(datetime).add(process).add(tso).add(profile).add(version);
        this.fileName = joiner.toString();
    }

    public Model getBaseInstanceModel() {
        return baseInstanceModel;
    }

    public void setBaseInstanceModel(Model baseInstanceModel) {
        this.baseInstanceModel = baseInstanceModel;
    }

    public String getDatetime() {
        return datetime;
    }

    public String getProcess() {
        return process;
    }

    public String getTso() {
        return tso;
    }

    public String getProfile() {
        return profile;
    }

    public String getVersion() {
        return version;
    }

    public String getFileName() {
        return fileName;
    }
}
