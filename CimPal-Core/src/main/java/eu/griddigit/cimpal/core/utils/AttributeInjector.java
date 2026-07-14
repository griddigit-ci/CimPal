package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AttributeInjector {
    public static Model injectAttributes(Model targetModel, Map<String, Map<String,String>> classAttributesMap) {

        for (Map.Entry<String, Map<String ,String >> classEntry : classAttributesMap.entrySet()) {
            String classFullName = classEntry.getKey();
            Map<String,String> attributeValueMap = classEntry.getValue();

            List<Statement> classStatements = targetModel.listStatements(null, RDF.type, ResourceFactory.createProperty(classFullName)).toList();

            for (Statement classStatement : classStatements) {
                Resource subject = classStatement.getSubject();
                for (Map.Entry<String,String> attributeEntry : attributeValueMap.entrySet()) {
                    String attributeFullName = attributeEntry.getKey();
                    String attributeValue = attributeEntry.getValue();

                    // Check that the attribute is
                    if (!targetModel.contains(subject, ResourceFactory.createProperty(attributeFullName))) {
                        Property property = ResourceFactory.createProperty(attributeFullName);
                        Literal literalValue = targetModel.createLiteral(attributeValue);

                        // Add the new statement to the model
                        targetModel.add(subject, property, literalValue);
                    }
                }
            }

        }

        return targetModel;
    }

    public static Map<String ,Map<String,String>> getClassAttributesMap(ArrayList<Object> xlsData, Map<String,String> nsPrefixMap) {

        Map<String ,Map<String,String>> classAttributesMap = new HashMap<>();

        for (Object row : xlsData) {
            if (row instanceof List<?> rowList) {
                if (rowList.size() >= 3) {
                    String classFullName = rowList.get(0).toString();
                    if (classFullName.split(":").length == 2) {
                        // Change the prefix of the class with the namespace
                        String prefix = classFullName.split(":")[0];
                        if (nsPrefixMap.containsKey(prefix)) {
                            classFullName = classFullName.replace(prefix + ":", nsPrefixMap.get(prefix));
                        }
                    }

                    String attributeFullName = rowList.get(1).toString();
                    if (attributeFullName.split(":").length == 2) {
                        // Change the prefix of the attribute with the namespace
                        String prefix = attributeFullName.split(":")[0];
                        if (nsPrefixMap.containsKey(prefix)) {
                            attributeFullName = attributeFullName.replace(prefix + ":", nsPrefixMap.get(prefix));
                        }
                    }

                    String attributeValue = rowList.get(2).toString();

                    // Get or create the attribute map for the class
                    Map<String,String> attributeMap = classAttributesMap.getOrDefault(classFullName, new HashMap<String,String>());
                    attributeMap.put(attributeFullName, attributeValue);
                    classAttributesMap.put(classFullName, attributeMap);
                }
            }
        }

        return classAttributesMap;
    }

    public static String getModelHeader(Model model){
        if (model.contains(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/ns/dcat#Dataset"))){
            return "http://www.w3.org/ns/dcat#Dataset";
        }
        else if (model.contains(null, ResourceFactory.createProperty("http://www.w3.org/ns/dcat#DifferenceSet"))){
            return "http://www.w3.org/ns/dcat#DifferenceSet";
        }
        else if (model.contains(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"))){
            return "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel";
        }
        else if (model.contains(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel"))){
            return "http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel";
        }
        else {
            return null;
        }
    }


}
