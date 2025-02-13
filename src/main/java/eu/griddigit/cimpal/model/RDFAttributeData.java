package eu.griddigit.cimpal.model;

public class RDFAttributeData {
    private String prefix;
    private String name;
    private String value;
    private String tpe;
    private String cardinality;

    public RDFAttributeData(String prefix, String name,String value, String tpe) {
        this.prefix = prefix;
        this.name = name;
        this.value = value;
        this.tpe = tpe;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return prefix + ":" + name;
    }

    public String getValue() {
        return value;
    }

    public String getTpe() {
        return tpe;
    }

    public String getCardinality() {
        return cardinality;
    }
}
