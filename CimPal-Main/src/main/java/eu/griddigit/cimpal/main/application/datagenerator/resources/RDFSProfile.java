package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.InputStream;

public class RDFSProfile {
    private String name;
    private String[] pathToRDFSFiles;
    private InputStream[] rdfsInputStreams;
    private String baseNamespace;

    public RDFSProfile(String name, String[] pathToRDFSFiles,InputStream[] rdfsInputStreams, String baseNamespace) {
        this.name = name;
        this.pathToRDFSFiles = pathToRDFSFiles;
        this.baseNamespace = baseNamespace;
        this.rdfsInputStreams = rdfsInputStreams;
    }

    public String getBaseNamespace() {
        return baseNamespace;
    }

    public String getName() {
        return name;
    }

    public String[] getPathToRDFSFiles() {
        return pathToRDFSFiles;
    }

    public InputStream[] getRdfsInputStreams(){return rdfsInputStreams;}

    public void setPathToRDFSFiles(String[] pathToRDFSFiles) {
        this.pathToRDFSFiles = pathToRDFSFiles;
    }

    public void setBaseNamespace(String baseNamespace) {
        this.baseNamespace = baseNamespace;
    }
}
