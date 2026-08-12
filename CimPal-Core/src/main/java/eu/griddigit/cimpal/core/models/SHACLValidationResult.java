package eu.griddigit.cimpal.core.models;

import java.util.Objects;

public class SHACLValidationResult {
    private String sourceShape;
    private String focusNode;
    private String severity;
    private String message;
    private String value;
    private String valueKind;
    private String path;
    private String constraintComponent;
    private  String details;
    private String description;
    private String order;
    private String name;
    private String group;

    /** Backward-compatible constructor: leaves the value node kind unknown. */
    public SHACLValidationResult(String sourceShape, String focusNode, String severity, String message, String value, String path,
                                 String constraintComponent,
                                 String details,
                                 String description,
                                 String order,
                                 String name,
                                 String group) {
        this(sourceShape, focusNode, severity, message, value, "", path,
                constraintComponent, details, description, order, name, group);
    }

    public SHACLValidationResult(String sourceShape, String focusNode, String severity, String message, String value,
                                 String valueKind,
                                 String path,
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
        this.valueKind = valueKind;
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

    /**
     * Node kind of sh:value, so a URI reference can be told apart from a string literal in the report.
     * Examples: "IRI", "BlankNode", "Literal^^xsd:integer", "Literal@en". Empty when there is no sh:value.
     */
    public String getValueKind() {
        return valueKind;
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

    /**
     * Value semantics. Two results that agree on every field are indistinguishable in any report,
     * so this lets callers drop exact duplicates. Jena re-evaluates a sh:sparql constraint declared
     * on a property shape once per value node, which makes an aggregate query emit one identical
     * result per value node.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SHACLValidationResult other)) return false;
        return Objects.equals(sourceShape, other.sourceShape)
                && Objects.equals(focusNode, other.focusNode)
                && Objects.equals(severity, other.severity)
                && Objects.equals(message, other.message)
                && Objects.equals(value, other.value)
                && Objects.equals(valueKind, other.valueKind)
                && Objects.equals(path, other.path)
                && Objects.equals(constraintComponent, other.constraintComponent)
                && Objects.equals(details, other.details)
                && Objects.equals(description, other.description)
                && Objects.equals(order, other.order)
                && Objects.equals(name, other.name)
                && Objects.equals(group, other.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceShape, focusNode, severity, message, value, valueKind, path,
                constraintComponent, details, description, order, name, group);
    }
}