package eu.griddigit.cimpal.model;

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

    private static String shortenName(String input) {
        // Split the input string into words based on uppercase letters
        String[] words = input.split("(?=[A-Z])");

        StringBuilder shortName = new StringBuilder();

        for (String word : words) {
            if (word.length() >= 3) {
                shortName.append(word, 0, 3); // Take first three letters
            } else {
                shortName.append(word); // Take full word if less than 3 letters
            }
        }

        // Ensure the name is at most 31 characters long (Excel sheet name limit)
        return shortName.length() > 31 ? shortName.substring(0, 31) : shortName.toString();
    }

    public String getSheetClassName() {
        String output = className;
        if (output.length() > 30)
        {
            output = shortenName(output);
        }
        return output;
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
