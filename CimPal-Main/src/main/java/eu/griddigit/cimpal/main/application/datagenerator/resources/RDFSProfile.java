package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.InputStream;

public class RDFSProfile {
    private String name;
    private String[] pathToRDFSFiles;
    private InputStream[] rdfsInputStreams;
    private String baseNamespace;
    private final ProfileFamily family;

    public RDFSProfile(String name, String[] pathToRDFSFiles,InputStream[] rdfsInputStreams, String baseNamespace) {
        this(name, pathToRDFSFiles, rdfsInputStreams, baseNamespace, ProfileFamily.CGMES);
    }

    public RDFSProfile(String name, String[] pathToRDFSFiles, InputStream[] rdfsInputStreams, String baseNamespace, ProfileFamily family) {
        this.name = name;
        this.pathToRDFSFiles = pathToRDFSFiles;
        this.baseNamespace = baseNamespace;
        this.rdfsInputStreams = rdfsInputStreams;
        this.family = family;
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

    public ProfileFamily getFamily() {
        return family;
    }

    public boolean isNcp() {
        return family == ProfileFamily.NCP;
    }

    public void setPathToRDFSFiles(String[] pathToRDFSFiles) {
        this.pathToRDFSFiles = pathToRDFSFiles;
    }

    public void setBaseNamespace(String baseNamespace) {
        this.baseNamespace = baseNamespace;
    }
}
