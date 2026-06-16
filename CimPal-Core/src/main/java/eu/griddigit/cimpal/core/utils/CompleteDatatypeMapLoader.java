package eu.griddigit.cimpal.core.utils;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class CompleteDatatypeMapLoader {
    private CompleteDatatypeMapLoader() {}

    /**
     * Loads CompleteDatatypeMap_CIM16.properties where values look like:
     * Datatype[http://www.w3.org/2001/XMLSchema#boolean -> class java.lang.Boolean]
     *
     * Returns map: predicateURI -> RDFDatatype
     */
    public static Map<String, RDFDatatype> loadFromResource(String resourcePath) throws IOException {
        try (InputStream in = CompleteDatatypeMapLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Datatype map resource not found: " + resourcePath);
            }

            Properties props = new Properties();
            props.load(in);

            Map<String, RDFDatatype> out = new HashMap<>();
            TypeMapper tm = TypeMapper.getInstance();

            for (String key : props.stringPropertyNames()) {
                String raw = props.getProperty(key);
                String dtUri = extractDatatypeUri(raw);
                if (dtUri == null || dtUri.isBlank()) continue;

                RDFDatatype dt = tm.getTypeByName(dtUri);
                if (dt == null) {
                    // If unknown datatype URI, skip (or log).
                    // System.err.println("[WARN] Unknown datatype URI: " + dtUri + " for key " + key);
                    continue;
                }
                out.put(key, dt);
            }

            return out;
        }

    }

    private static String extractDatatypeUri(String raw) {
        if (raw == null) return null;

        int lb = raw.indexOf('[');
        if (lb < 0) return null;

        int rb = raw.indexOf(']', lb + 1);

        String inside;
        if (rb > lb) {
            inside = raw.substring(lb + 1, rb).trim();
        } else {
            inside = raw.substring(lb + 1).trim();
        }

        int arrow = inside.indexOf("->");
        if (arrow >= 0) {
            inside = inside.substring(0, arrow).trim();
        }

        return inside.isBlank() ? null : inside;
    }
}