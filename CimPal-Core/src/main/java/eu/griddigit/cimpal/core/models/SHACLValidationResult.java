package eu.griddigit.cimpal.core.models;

public class SHACLValidationResult {
    private String sourceShape;
    private String focusNode;
    private String severity;
    private String message;
    private String value;
    private String path;
    private String constraintComponent;
    private  String details;
    private String description;
    private String order;
    private String name;
    private String group;

    public SHACLValidationResult(String sourceShape, String focusNode, String severity, String message, String value, String path,
                                 String constraintComponent,
                                 String details,
                                 String description,
                                 String order,
                                 String name,
                                 String group) {
        this.sourceShape = sourceShape;
        this.focusNode = focusNode;
        this.severity = severity;
        this.message = message;
        this.value = value;
        this.path = path;
        this.constraintComponent = constraintComponent;
        this.details = details;
        this.description = description;
        this.order = order;
        this.name = name;
        this.group = group;

    }

    // Getters
    public String getSourceShape() {
        return sourceShape;
    }

    public String getFocusNode() {
        return focusNode;
    }

    public String getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getValue() {
        return value;
    }

    public String getPath() {
        return path;
    }

    public String getConstraintComponent() {return constraintComponent;}

    public String getDetails() { return details; }

    public String getDescription() { return description; }
    public String getOrder() { return order; }
    public String getName() { return name; }
    public String getGroup() { return group; }
}