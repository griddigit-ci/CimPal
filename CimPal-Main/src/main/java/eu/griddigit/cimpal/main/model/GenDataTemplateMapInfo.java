package eu.griddigit.cimpal.main.model;

import eu.griddigit.cimpal.main.util.ExcelTools;

public class GenDataTemplateMapInfo {
    private String className;
    private String fullClassName;
    private String prop;
    private String multiplicity;
    private String datatype;
    private String tpe;
    private String clsDescr;

    public GenDataTemplateMapInfo(String className, String fullClassName,
                                  String prop, String multiplicity, String datatype, String tpe, String classDescription) {
        this.className = className;
        this.fullClassName = fullClassName;
        this.prop = prop;
        this.multiplicity = multiplicity;
        this.datatype = datatype;
        this.tpe = tpe;
        this.clsDescr = classDescription;
    }

    public String getSheetClassName() {
        // Use the intelligent truncation from ExcelTools
        return ExcelTools.getSheetNameFromClassName(className);
    }

    public String getClassName() {
        return className;
    }

    public String getFullClassName() {
        return fullClassName;
    }

    public String getProp() {
        return prop;
    }

    public String getMultiplicity() {
        return multiplicity;
    }

    public String getDatatype() {
        return datatype;
    }

    public String getTpe() {
        return tpe;
    }
    public String getClsDescr() {
        return clsDescr;
    }
}
