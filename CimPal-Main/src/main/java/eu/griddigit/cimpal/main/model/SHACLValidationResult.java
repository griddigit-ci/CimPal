package eu.griddigit.cimpal.main.model;

public class SHACLValidationResult {
    private String focusNode;
    private String severity;
    private String message;
    private String value;
    private String path;

    public SHACLValidationResult(String focusNode, String severity, String message, String value, String path) {
        this.focusNode = focusNode;
        this.severity = severity;
        this.message = message;
        this.value = value;
        this.path = path;
    }

    // Getters
    public String getFocusNode() { return focusNode; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getValue() { return value; }
    public String getPath() { return path; }
}