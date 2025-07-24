package eu.griddigit.cimpal.Core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ModelFactory {
    //Loads one or many models
    public static Model modelLoad(List<File> files, String xmlBase, Lang rdfSourceFormat, Boolean considerCimDiff) throws FileNotFoundException {
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        for (Object file : files) {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file.toString());
            RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());
            if (considerCimDiff) {
                String cim2URI = prefixMap.get("cim");
                if (!cim2URI.isEmpty()) {
                    model.removeNsPrefix("cim");
                    prefixMap.remove("cim");
                    String cim2Pref = switch (cim2URI) {
                        case "http://iec.ch/TC57/2013/CIM-schema-cim16#",
                             "https://iec.ch/TC57/2013/CIM-schema-cim16#" -> "cim16";
                        case "http://iec.ch/TC57/CIM100#", "https://iec.ch/TC57/CIM100#" -> "cim17";
                        case "http://cim.ucaiug.io/ns#", "https://cim.ucaiug.io/ns#" -> "cim18";
                        default -> throw new IllegalStateException("Unexpected value: " + cim2URI);
                    };
                    model.setNsPrefix(cim2Pref, cim2URI);
                    prefixMap.putIfAbsent(cim2Pref, cim2URI);
                }
            }
            modelUnion.add(model);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }
}
