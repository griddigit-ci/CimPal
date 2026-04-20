package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.InputStream;
import java.util.List;

// For handling multiple files in import.
public class UnzippedFiles {
    private final List<InputStream> inputStreamList;
    private final List<String> fileNames;
    public boolean isSingleZip;

    public UnzippedFiles(List<InputStream> inputStreamList, List<String> fileNames, boolean isSingleZip){
        this.inputStreamList = inputStreamList;
        this.fileNames = fileNames;
        this.isSingleZip = isSingleZip;
    }

    public List<InputStream> getInputStreamList() {return inputStreamList;}
    public List<String> fileNames() {return fileNames;}
}
