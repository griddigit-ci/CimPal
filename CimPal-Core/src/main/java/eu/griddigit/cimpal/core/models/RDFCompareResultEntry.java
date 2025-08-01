package eu.griddigit.cimpal.core.models;

public class RDFCompareResultEntry {
    private String item;
    private String rdfType;
    private String property;
    private String valueModelA;
    private String valueModelB;

    public RDFCompareResultEntry(String item, String rdfType, String property, String valueModelA, String valueModelB) {
        this.item = item;
        this.rdfType = rdfType;
        this.property = property;
        this.valueModelA = valueModelA;
        this.valueModelB = valueModelB;
    }

    public RDFCompareResultEntry(String item, String property, String valueModelA, String valueModelB) {
        this.item = item;
        this.rdfType = "";
        this.property = property;
        this.valueModelA = valueModelA;
        this.valueModelB = valueModelB;
    }

    public String getItem() {
        return item;
    }

    public String getRdfType() {
        return rdfType;
    }

    public String getProperty() {
        return property;
    }

    public String getValueModelA() {
        return valueModelA;
    }

    public String getValueModelB() {
        return valueModelB;
    }
}
