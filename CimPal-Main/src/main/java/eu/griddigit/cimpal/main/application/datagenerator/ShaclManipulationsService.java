package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.topbraid.shacl.vocabulary.SH;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShaclManipulationsService {

    public static Map<String,Object> getShaclConstraint(Boolean conform, Property property, Map<Property, Map> shaclConstraints) {
        Map<String,Object> shaclContraintResult = new HashMap<>();

        //shacl properties to considered and could be found in the Maps
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in
        //sh:equals, sh:disjoint

        //gets the 1st level structure
        Map mainMap=shaclConstraints.get(property);
        // gets the path content - is it direct, inverse or sequence
        // shaclConstraints.get(property).get("pathContent");
        //gets the content of the property shape where the keys are the shacl properties and the value is the object
        // shaclConstraints.get(property).get("propertyShapeContent");
        //gets the value
        // ((Map) shaclConstraints.get(property).get("propertyShapeContent")).get(SH.nodeKind);
        Map propertyShapeContent = (Map) mainMap.get("propertyShapeContent");

        if (conform){
            shaclContraintResult.put("conform",true);

        }else{
            shaclContraintResult.put("conform",false);
        }
        if (propertyShapeContent.containsKey(SH.datatype)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.datatype)).get(0);
            shaclContraintResult.put("datatype",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.nodeKind)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.nodeKind)).get(0);
            shaclContraintResult.put("nodeKind",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minCount)).get(0);
            shaclContraintResult.put("minCount",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxCount)).get(0);
            shaclContraintResult.put("maxCount",objectTemp);
        }

        if (propertyShapeContent.containsKey(SH.minExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minExclusive)).get(0);
            shaclContraintResult.put("minExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minInclusive)).get(0);
            shaclContraintResult.put("minInclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxExclusive)).get(0);
            shaclContraintResult.put("maxExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxInclusive)).get(0);
            shaclContraintResult.put("maxInclusive",objectTemp);
        }

        if (propertyShapeContent.containsKey(SH.minLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minLength)).get(0);
            shaclContraintResult.put("minLength",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxLength)).get(0);
            shaclContraintResult.put("maxLength",objectTemp);
        }

        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThan)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThan));
            shaclContraintResult.put("lessThan",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThanOrEquals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThanOrEquals));
            shaclContraintResult.put("lessThanOrEquals",objectTemp);
        }

        //can be a list
        if (propertyShapeContent.containsKey(SH.equals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.equals));
            shaclContraintResult.put("equals",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.disjoint)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.disjoint));
            shaclContraintResult.put("disjoint",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.in)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.in));
            shaclContraintResult.put("in",objectTemp);
        }



        return shaclContraintResult;
    }

}
