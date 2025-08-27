package eu.griddigit.cimpal.main.util;

public class PropertyHolder {
    private String property1;
    private String property2;
    private String property3;
    private String property4;
    private String property5;
    private String property6;

    // Constructor
    public PropertyHolder(String property1, String property2, String property3, String property4, String property5, String property6) {
        this.property1 = property1;
        this.property2 = property2;
        this.property3 = property3;
        this.property4 = property4;
        this.property5 = property5;
        this.property6 = property6;
    }

    // Getters and Setters
    public String getProperty1() { return property1; }
    public void setProperty1(String property1) { this.property1 = property1; }

    public String getProperty2() { return property2; }
    public void setProperty2(String property2) { this.property2 = property2; }

    public String getProperty3() { return property3; }
    public void setProperty3(String property3) { this.property3 = property3; }

    public String isProperty4() { return property4; }
    public void setProperty4(String property4) { this.property4 = property4; }

    public String getProperty5() { return property5; }
    public void setProperty5(String property5) { this.property5 = property5; }

    public String getProperty6() { return property6; }
    public void setProperty6(String property6) { this.property6 = property6; }
}
