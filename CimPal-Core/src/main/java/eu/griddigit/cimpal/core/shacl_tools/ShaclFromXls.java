package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.ShaclTools;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.prefs.Preferences;

public class ShaclFromXls {

    /**
     * CLI-friendly overload: supply namespace fallback values directly instead of via Preferences.
     * Map keys: "prefixEU", "uriEU", "cimsNamespace", "CIMnamespace", "prefixOther", "uriOther"
     * (same keys used in the Preferences-based overload).
     */
    public static Model generateShaclFromXls(
            Map<String, String> namespaceFallbacks,
            ArrayList<Object> dataExcel,
            ArrayList<Object> configSheet,
            ArrayList<Object> shapeData,
            String nsURIprofilePrefix,
            String nsURIprofile) {

        Model shapeModel = JenaUtil.createDefaultModel();
        if (configSheet.isEmpty()) {
            shapeModel.setNsPrefix("sh", SH.NS);
            shapeModel.setNsPrefix("dash", DASH.NS);
            shapeModel.setNsPrefix(namespaceFallbacks.getOrDefault("prefixEU", ""),
                    namespaceFallbacks.getOrDefault("uriEU", ""));
            shapeModel.setNsPrefix("cims", namespaceFallbacks.getOrDefault("cimsNamespace", ""));
            shapeModel.setNsPrefix("rdf", RDF.uri);
            shapeModel.setNsPrefix("owl", OWL.NS);
            shapeModel.setNsPrefix("cim", namespaceFallbacks.getOrDefault("CIMnamespace", ""));
            shapeModel.setNsPrefix("xsd", XSD.NS);
            shapeModel.setNsPrefix("rdfs", RDFS.uri);
            String pOther = namespaceFallbacks.getOrDefault("prefixOther", "");
            String uOther = namespaceFallbacks.getOrDefault("uriOther", "");
            if (!pOther.isEmpty() && !uOther.isEmpty()) {
                shapeModel.setNsPrefix(pOther, uOther);
            }
        } else {
            for (int row = 1; row < configSheet.size(); row++) {
                String prefix = ((LinkedList<?>) configSheet.get(row)).get(0).toString();
                String uri = ((LinkedList<?>) configSheet.get(row)).get(1).toString();
                if (!prefix.isEmpty() && !uri.isEmpty()) {
                    shapeModel.setNsPrefix(prefix, uri);
                }
            }
        }

        shapeModel.setNsPrefix(nsURIprofilePrefix, nsURIprofile);

        String localNameGroup = "ValueConstraintsGroup";
        ArrayList<Object> groupFeatures = new ArrayList<>();
        for (int i = 0; i < 4; i++) groupFeatures.add("");
        groupFeatures.set(0, "ValueConstraints");
        groupFeatures.set(1, "This group of validation rules relate to value constraints validation of properties(attributes).");
        groupFeatures.set(2, "ValueConstraints");
        groupFeatures.set(3, 0);

        ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);
        boolean has = false;
        for (int row = 1; row < dataExcel.size(); row++) {
            String attributeName = ((LinkedList<?>) dataExcel.get(row)).get(8).toString().trim();
            String attributeNameNoNamespace = attributeName.split("#", 2)[1].trim();
            for (int classRDF = 0; classRDF < ((ArrayList<?>) shapeData.getFirst()).size(); classRDF++) {
                for (int attr = 1; attr < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).size(); attr++) {
                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).getFirst().equals("Attribute")) {
                        String className = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).get(2).toString();
                        String attributeNameRDFS = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).get(4).toString();

                        if (attributeNameNoNamespace.equals(attributeNameRDFS)) {
                            has = true;
                            Resource propertyShapeResource = ResourceFactory.createResource(nsURIprofile + ((LinkedList<?>) dataExcel.get(row)).get(7).toString());
                            if (!shapeModel.containsResource(propertyShapeResource)) {
                                Resource r = shapeModel.createResource(propertyShapeResource.toString());
                                r.addProperty(RDF.type, SH.PropertyShape);

                                RDFNode o1g = shapeModel.createResource(nsURIprofile + localNameGroup);
                                r.addProperty(SH.group, o1g);

                                RDFNode o1o = shapeModel.createTypedLiteral(row, XSDDatatype.XSDinteger.getURI());
                                r.addProperty(SH.order, o1o);

                                r.addProperty(SH.message, ((LinkedList<?>) dataExcel.get(row)).get(4).toString());

                                RDFNode o5 = shapeModel.createResource(attributeName);
                                r.addProperty(SH.path, o5);

                                r.addProperty(SH.name, ((LinkedList<?>) dataExcel.get(row)).get(2).toString());
                                r.addProperty(SH.description, ((LinkedList<?>) dataExcel.get(row)).get(3).toString());

                                RDFNode o8 = shapeModel.createResource(SH.NS + ((LinkedList<?>) dataExcel.get(row)).get(5).toString());
                                r.addProperty(SH.severity, o8);

                                String propertyConstraint1 = ((LinkedList<?>) dataExcel.get(row)).get(9).toString();
                                applyConstraint(shapeModel, r, propertyConstraint1, ((LinkedList<?>) dataExcel.get(row)).get(10));

                                if (((LinkedList<?>) dataExcel.get(row)).size() == 13) {
                                    String propertyConstraint2 = ((LinkedList<?>) dataExcel.get(row)).get(11).toString();
                                    applyConstraint(shapeModel, r, propertyConstraint2, ((LinkedList<?>) dataExcel.get(row)).get(12));
                                }
                            }

                            Resource nodeShapeResource = ResourceFactory.createResource(nsURIprofile + className);
                            if (!shapeModel.containsResource(nodeShapeResource)) {
                                String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).getFirst().toString();
                                ShaclTools.addNodeShape(shapeModel, nsURIprofile, className, classFullURI);
                            }
                            RDFNode o = shapeModel.createResource(propertyShapeResource.toString());
                            shapeModel.getResource(String.valueOf(nodeShapeResource)).addProperty(SH.property, o);
                        }
                    }
                }
            }
            if (!has)
                System.out.println("Warning: the attribute " + attributeName + " is not found in the RDFS model and therefore it is skipped.\n");
            has = false;
        }

        return shapeModel;
    }

    private static void applyConstraint(Model shapeModel, Resource r, String constraintType, Object value) {
        switch (constraintType) {
            case "minExclusive" -> r.addProperty(SH.minExclusive, shapeModel.createTypedLiteral(value, XSDDatatype.XSDfloat.getURI()));
            case "maxExclusive" -> r.addProperty(SH.maxExclusive, shapeModel.createTypedLiteral(value, XSDDatatype.XSDfloat.getURI()));
            case "maxInclusive" -> r.addProperty(SH.maxInclusive, shapeModel.createTypedLiteral(value, XSDDatatype.XSDfloat.getURI()));
            case "minInclusive" -> r.addProperty(SH.minInclusive, shapeModel.createTypedLiteral(value, XSDDatatype.XSDfloat.getURI()));
            case "maxLength"    -> r.addProperty(SH.maxLength,    shapeModel.createTypedLiteral(value, XSDDatatype.XSDinteger.getURI()));
            case "minLength"    -> r.addProperty(SH.minLength,    shapeModel.createTypedLiteral(value, XSDDatatype.XSDinteger.getURI()));
            case "equals", "disjoint", "lessThan", "lessThanOrEquals" -> {
                String[] parts = value.toString().split(":", 2);
                if (parts.length == 2) {
                    RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(parts[0]) + parts[1]);
                    Property prop = switch (constraintType) {
                        case "equals" -> SH.equals;
                        case "disjoint" -> SH.disjoint;
                        case "lessThan" -> SH.lessThan;
                        default -> SH.lessThanOrEquals;
                    };
                    r.addProperty(prop, eq);
                }
            }
        }
    }

    /**
     * GUI overload — delegates to {@link #generateShaclFromXls(Map, ArrayList, ArrayList, ArrayList, String, String)}.
     * Extracts the relevant Preferences keys into a Map so there is a single implementation of
     * the shape-generation logic. GUI behaviour is unchanged.
     */
    public static Model generateShaclFromXls(Preferences prefs,
                                             ArrayList<Object> dataExcel,
                                             ArrayList<Object> configSheet,
                                             ArrayList<Object> shapeData,
                                             String nsURIprofilePrefix,
                                             String nsURIprofile) {
        Map<String, String> fallbacks = new HashMap<>();
        fallbacks.put("prefixEU",      prefs.get("prefixEU",      ""));
        fallbacks.put("uriEU",         prefs.get("uriEU",         ""));
        fallbacks.put("cimsNamespace", prefs.get("cimsNamespace", ""));
        fallbacks.put("CIMnamespace",  prefs.get("CIMnamespace",  ""));
        fallbacks.put("prefixOther",   prefs.get("prefixOther",   ""));
        fallbacks.put("uriOther",      prefs.get("uriOther",      ""));
        return generateShaclFromXls(fallbacks, dataExcel, configSheet, shapeData, nsURIprofilePrefix, nsURIprofile);
    }
}
