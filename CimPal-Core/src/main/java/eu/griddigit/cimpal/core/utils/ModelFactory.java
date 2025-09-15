package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ModelFactory {

    public static class InheritanceResult {
        public final Model processedModel;
        public final Model inheritanceModel;
        public InheritanceResult(Model processedModel, Model inheritanceModel) {
            this.processedModel = processedModel;
            this.inheritanceModel = inheritanceModel;
        }
    }

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

    public static InheritanceResult generateInheritanceModels(
            Model model, boolean inheritanceList, boolean inheritanceListConcrete) {

        Model processed = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        processed.setNsPrefixes(model.getNsPrefixMap());

        Model inheritance = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        inheritance.setNsPrefixes(model.getNsPrefixMap());
        inheritance.setNsPrefix("owl", OWL2.NS);

        if (!inheritanceList) {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) processed.add(stmt);
            });
        } else {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) {
                    processed.add(stmt);
                    if (stmt.getPredicate().equals(RDF.type)) {
                        inheritanceStructure(stmt.getSubject(),
                                stmt.getSubject(),
                                inheritance,
                                model,
                                inheritanceListConcrete);
                    }
                }
            });
        }

        return new InheritanceResult(processed, inheritance);
    }

    private static boolean isInheritanceStmt(Statement stmt) {
        Property p = stmt.getPredicate();
        return p.equals(RDF.type)
                || p.equals(RDFS.subClassOf)
                || p.equals(RDFS.subPropertyOf)
                || p.equals(RDFS.domain)
                || p.equals(RDFS.range);
    }

    private static Model inheritanceStructure(
            Resource root, Resource current,
            Model inheritance, Model fullModel,
            boolean onlyConcrete) {

        ResIterator subs = fullModel.listSubjectsWithProperty(RDFS.subClassOf, current);
        while (subs.hasNext()) {
            Resource sub = subs.next();
            if (!onlyConcrete || isConcrete(sub, fullModel)) {
                inheritance.add(root, OWL2.members, sub);
                inheritance.add(root, RDF.type, OWL2.Class);
            }
            inheritanceStructure(root, sub, inheritance, fullModel, onlyConcrete);
        }
        return inheritance;
    }

    private static boolean isConcrete(Resource cls, Model model) {
        Property stereo = ResourceFactory
                .createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");
        NodeIterator it = model.listObjectsOfProperty(cls, stereo);
        while (it.hasNext()) {
            RDFNode n = it.next();
            if (n.isResource()
                    && n.asResource().getURI()
                    .equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                return true;
            }
        }
        return false;
    }

    public static Model LoadSHACLSHACL() {
        Model shaclModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        InputStream inputStream = ModelFactory.class.getResourceAsStream("/shacl-shacl/shacl-shaclFixed.ttl");

        if (inputStream != null) {
            RDFDataMgr.read(shaclModel, inputStream, "", Lang.TURTLE);
        } else {
            try {
                throw new FileNotFoundException("File not found for shacl validation.");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return shaclModel;
    }

}
