package eu.griddigit.cimpal.core.utils;

import org.apache.jena.datatypes.RDFDatatype;

import java.io.IOException;
import java.util.Map;

/**
 * The datatype maps bundled with CimPal-Core. CIM RDF/XML carries its literals untyped, so a map
 * from property URI to datatype is applied while the data is parsed; without one every literal
 * is an {@code xsd:string} and datatype or value-range constraints on it cannot evaluate correctly.
 */
public enum DatatypeMapPreset {
    /** No datatype map: literals keep the datatype the parser gives them. */
    NONE("None", null),
    CGMES24_NC22("CGMES 2.4 / NC 2.2", "/CompleteDatatypeMap_CIM16_CGMES24_NC22.properties"),
    CGMES30_NC24("CGMES 3.0 / NC 2.4", "/CompleteDatatypeMap_CIM17_CGMES3_NC24.properties"),
    CGMES30_NC25("CGMES 3.0 / NC 2.5", "/CompleteDatatypeMap_CIM17_CGMES3_NC25.properties");

    private final String displayName;
    private final String resourcePath;

    DatatypeMapPreset(String displayName, String resourcePath) {
        this.displayName = displayName;
        this.resourcePath = resourcePath;
    }

    public String displayName() {
        return displayName;
    }

    /** Classpath location of the bundled map, or {@code null} for {@link #NONE}. */
    public String resourcePath() {
        return resourcePath;
    }

    /** Loads the bundled map; {@link #NONE} yields an empty map. */
    public Map<String, RDFDatatype> load() throws IOException {
        return resourcePath == null ? Map.of() : CompleteDatatypeMapLoader.loadFromResource(resourcePath);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
