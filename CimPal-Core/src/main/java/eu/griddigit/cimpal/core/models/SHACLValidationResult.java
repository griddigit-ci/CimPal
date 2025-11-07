package eu.griddigit.cimpal.core.models;

public class SHACLValidationResult {
    private String sourceShape;
    private String focusNode;
    private String severity;
    private String message;
    private String value;
    private String path;

    public SHACLValidationResult(String sourceShape, String focusNode, String severity, String message, String value, String path) {
        this.sourceShape = sourceShape;
        this.focusNode = focusNode;
        this.severity = severity;
        this.message = message;
        this.value = value;
        this.path = path;
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
}