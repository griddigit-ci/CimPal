package util;

import net.sourceforge.plantuml.SourceStringReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

public class PlantUMLGenerator {
    public static byte[] generateDiagram(String uml) throws Exception {
        SourceStringReader reader = new SourceStringReader(uml);
        try (OutputStream os = new ByteArrayOutputStream()) {
            reader.generateImage(os);
            return ((ByteArrayOutputStream) os).toByteArray();
        }
    }
}