package eu.griddigit.cimpal.core.utils;

/** SHACL validators available to mapping-driven validation. */
public enum ValidationEngine {
    APACHE_JENA("Apache Jena"),
    /**
     * Uses RDFLib's SPARQL implementation. It is retained for comparison testing; its output
     * and throughput must be checked against Jena for each shape collection.
     */
    PYSHACL("pySHACL (RDFLib — experimental)"),
    /**
     * Oxigraph accelerates some SPARQL workloads, but pySHACL's Oxigraph adapter rejects
     * valid-in-Jena Coreso shape graphs and is materially slower for the current data sets.
     */
    PYSHACL_OXIGRAPH("pySHACL + Oxigraph — experimental"),
    /**
     * The PyPI binding is fast for its supported Core subset, but its current SPARQL parser
     * rejects valid expressions used by CimPal's QoCDC shape sets. Keep it available for
     * compatibility experiments, not as an implied replacement for Jena or pySHACL.
     */
    RUST_SHACL("SHACL (Rust Python binding — experimental)");

    private final String displayName;

    ValidationEngine(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
