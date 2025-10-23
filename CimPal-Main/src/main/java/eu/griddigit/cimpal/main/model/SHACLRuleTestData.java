package eu.griddigit.cimpal.main.model;

import org.apache.jena.shacl.ValidationReport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SHACLRuleTestData {
    private String ruleName;
    private String conformFolderPath;
    private String nonConformFolderPath;

    private List<File> conformFiles;
    private List<File> nonConformFiles;

    private Map<String, ValidationReport> conformReports;
    private Map<String, ValidationReport> nonConformReports;

    public SHACLRuleTestData(String ruleName) {
        this.ruleName = ruleName;
        this.conformFolderPath = null;
        this.nonConformFolderPath = null;
        this.conformFiles = new ArrayList<>();
        this.nonConformFiles = new ArrayList<>();
        this.conformReports = new HashMap<>();
        this.nonConformReports = new HashMap<>();
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getConformFolderPath() {
        return conformFolderPath;
    }

    public String getNonConformFolderPath() {
        return nonConformFolderPath;
    }

    public List<File> getConformFiles() {
        return conformFiles;
    }

    public List<File> getNonConformFiles() {
        return nonConformFiles;
    }

    public Map<String, ValidationReport> getConformReports() {
        return conformReports;
    }

    public Map<String, ValidationReport> getNonConformReports() {
        return nonConformReports;
    }

    public void addFile(String category, File modelFile) {
        if ("Conform".equalsIgnoreCase(category)) {
            conformFiles.add(modelFile);
            if (conformFolderPath == null) {
                conformFolderPath = modelFile.getParent();
            }
        } else if ("NonConform".equalsIgnoreCase(category)) {
            nonConformFiles.add(modelFile);
            if (nonConformFolderPath == null) {
                nonConformFolderPath = modelFile.getParent();
            }
        } else {
            System.out.println("Unrecognized category: " + category);
        }
    }

    public void addReport(String fileName, ValidationReport report, Boolean isConform) {
        if (isConform)
            conformReports.putIfAbsent(fileName, report);
        else
            nonConformReports.putIfAbsent(fileName, report);
    }


}
