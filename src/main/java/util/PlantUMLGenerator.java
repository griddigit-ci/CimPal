package util;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

public class PlantUMLGenerator {
    public static byte[] generateDiagram(String uml) throws Exception {
//        SourceStringReader reader = new SourceStringReader(uml);
//        try (OutputStream os = new ByteArrayOutputStream()) {
//            reader.generateImage(os);
//            return ((ByteArrayOutputStream) os).toByteArray();
//        }



        String styledUml = """
        @startuml
        skinparam backgroundColor #EEEBDC
        skinparam class {
            BackgroundColor PaleGreen
            ArrowColor SeaGreen
            BorderColor Black
        }
        """ + uml + """
        @enduml
        """;
        SourceStringReader reader = new SourceStringReader(styledUml);
        try (OutputStream os = new ByteArrayOutputStream()) {
            reader.generateImage(os);
            return ((ByteArrayOutputStream) os).toByteArray();
        }

//        private String generateDiagram(String uml) throws Exception {
//            SourceStringReader reader = new SourceStringReader(uml);
//            try (OutputStream os = new ByteArrayOutputStream()) {
//                String desc = reader.generateImage(os, new FileFormatOption(FileFormat.SVG));
//                return os.toString();
//            }
//        }


        //SourceStringReader reader = new SourceStringReader(uml);
//        try (OutputStream os = new ByteArrayOutputStream()) {
//            String desc = reader.generateImage(os, new FileFormatOption(FileFormat.SVG));
//            return os.toString();
//        }


    }

//    public static String generateDiagram(String uml) throws Exception {
//        SourceStringReader reader = new SourceStringReader(uml);
//        try (OutputStream os = new ByteArrayOutputStream()) {
//            String desc = reader.generateImage(os, new FileFormatOption(FileFormat.SVG));
//            return os.toString();
//        }
//    }
}