package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.ShaclTools;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
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
import java.util.LinkedList;
import java.util.prefs.Preferences;

public class ShaclFromXls {

    public static Model generateShaclFromXls(Preferences prefs,
                                             ArrayList<Object> dataExcel,
                                             ArrayList<Object> configSheet, ArrayList<Object> shapeData,
                                             String nsURIprofilePrefix,
                                             String nsURIprofile) {

        //TODO: this can be made with many additional options e.g. what to be added and in which model to be added. Now it is primitive to solve a simple task
        //create a new Shapes model
        Model shapeModel = JenaUtil.createDefaultModel();
        //add the namespaces
        if (configSheet.isEmpty()) { // if the config sheet is empty use the default namespaces
            shapeModel.setNsPrefix("sh", SH.NS);
            shapeModel.setNsPrefix("dash", DASH.NS);
            shapeModel.setNsPrefix(prefs.get("prefixEU", ""), prefs.get("uriEU", ""));
            shapeModel.setNsPrefix("cims", prefs.get("cimsNamespace", ""));
            shapeModel.setNsPrefix("rdf", RDF.uri);
            shapeModel.setNsPrefix("owl", OWL.NS);
            shapeModel.setNsPrefix("cim", prefs.get("CIMnamespace", ""));
            shapeModel.setNsPrefix("xsd", XSD.NS);
            shapeModel.setNsPrefix("rdfs", RDFS.uri);
            if (!prefs.get("prefixOther", "").isEmpty() && !prefs.get("uriOther", "").isEmpty()) {
                shapeModel.setNsPrefix(prefs.get("prefixOther", ""), prefs.get("uriOther", ""));
            }
        } else {
            for (int row = 1; row < configSheet.size(); row++) { //loop on the rows in the xlsx
                String prefix = ((LinkedList<?>) configSheet.get(row)).get(0).toString();
                String uri = ((LinkedList<?>) configSheet.get(row)).get(1).toString();
                if (!prefix.isEmpty() && !uri.isEmpty()) {
                    shapeModel.setNsPrefix(prefix, uri);
                }
            }
        }

        shapeModel.setNsPrefix(nsURIprofilePrefix, nsURIprofile);

        //add the shapes in the model - i.e. check what is in the dataExcel and generate the Shapes out of it
        //adding the PropertyGroup
        String localNameGroup = "ValueConstraintsGroup";
        ArrayList<Object> groupFeatures = new ArrayList<>();
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */
        for (int i = 0; i < 4; i++) {
            groupFeatures.add("");
        }
        groupFeatures.set(0, "ValueConstraints");
        groupFeatures.set(1, "This group of validation rules relate to value constraints validation of properties(attributes).");
        groupFeatures.set(2, "ValueConstraints");
        groupFeatures.set(3, 0);

        ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);
        boolean has = false;
        for (int row = 1; row < dataExcel.size(); row++) { //loop on the rows in the xlsx
            //String localName = ((LinkedList) dataExcel.get(row)).get(6).toString();// here map to "NodeShape" column
            String attributeName = ((LinkedList<?>) dataExcel.get(row)).get(8).toString().trim(); //here map to "path" column
            String attributeNameNoNamespace = attributeName.split("#", 2)[1].trim();
            for (int classRDF = 0; classRDF < ((ArrayList<?>) shapeData.getFirst()).size(); classRDF++) { // this loops the concrete classes in the RDFS
                for (int attr = 1; attr < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).size(); attr++) { //this loops the attributes of a concrete class including the inherited
                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).getFirst().equals("Attribute")) { //this is when the property is an attribute
                        String className = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).get(2).toString(); // this is the name of the class
                        String attributeNameRDFS = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).get(attr)).get(4).toString(); // this is the localName of the attribute


                        if (attributeNameNoNamespace.equals(attributeNameRDFS)) {
                            has = true;
                            Resource propertyShapeResource = ResourceFactory.createResource(nsURIprofile + ((LinkedList<?>) dataExcel.get(row)).get(7).toString());
                            if (!shapeModel.containsResource(propertyShapeResource)) { // creates only if it is missing

                                //adding the PropertyShape
                                Resource r = shapeModel.createResource(propertyShapeResource.toString());
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //add the group
                                RDFNode o1g = shapeModel.createResource(nsURIprofile + localNameGroup);
                                r.addProperty(SH.group, o1g);

                                //add the order
                                RDFNode o1o = shapeModel.createTypedLiteral(row, XSDDatatype.XSDinteger.getURI());
                                r.addProperty(SH.order, o1o);

                                //add the message
                                r.addProperty(SH.message, ((LinkedList<?>) dataExcel.get(row)).get(4).toString());

                                // add the path
                                RDFNode o5 = shapeModel.createResource(attributeName);
                                r.addProperty(SH.path, o5);

                                //add the name
                                r.addProperty(SH.name, ((LinkedList<?>) dataExcel.get(row)).get(2).toString());

                                //add description
                                r.addProperty(SH.description, ((LinkedList<?>) dataExcel.get(row)).get(3).toString());

                                //add severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + ((LinkedList<?>) dataExcel.get(row)).get(5).toString());
                                r.addProperty(SH.severity, o8);

                                //add columns Constraint 1/Value 1

                                String propertyConstraint1 = ((LinkedList<?>) dataExcel.get(row)).get(9).toString();

                                switch (propertyConstraint1) {
                                    case "minExclusive" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minExclusive, constr1);
                                    }
                                    case "maxExclusive" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxExclusive, constr1);
                                    }
                                    case "maxInclusive" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.maxInclusive, constr1);
                                    }
                                    case "minInclusive" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDfloat.getURI());
                                        r.addProperty(SH.minInclusive, constr1);
                                    }
                                    case "maxLength" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.maxLength, constr1);
                                    }
                                    case "minLength" -> {
                                        RDFNode constr1 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(10), XSDDatatype.XSDinteger.getURI());
                                        r.addProperty(SH.minLength, constr1);
                                    }
                                    case "equals" -> {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.equals, eq);
                                    }
                                    case "disjoint" -> {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.disjoint, eq);
                                    }
                                    case "lessThan" -> {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.lessThan, eq);
                                    }
                                    case "lessThanOrEquals" -> {
                                        String prefix = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[0];
                                        String attribute = ((LinkedList<?>) dataExcel.get(row)).get(10).toString().split(":", 2)[1];
                                        RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                        r.addProperty(SH.lessThanOrEquals, eq);
                                    }
                                }

                                if (((LinkedList<?>) dataExcel.get(row)).size() == 13) {
                                    String propertyConstraint2 = ((LinkedList<?>) dataExcel.get(row)).get(11).toString();
                                    //add columns Constraint 2/Value 2
                                    switch (propertyConstraint2) {
                                        case "minExclusive" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minExclusive, constr2);
                                        }
                                        case "maxExclusive" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxExclusive, constr2);
                                        }
                                        case "maxInclusive" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.maxInclusive, constr2);
                                        }
                                        case "minInclusive" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDfloat.getURI());
                                            r.addProperty(SH.minInclusive, constr2);
                                        }
                                        case "maxLength" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.maxLength, constr2);
                                        }
                                        case "minLength" -> {
                                            RDFNode constr2 = shapeModel.createTypedLiteral(((LinkedList<?>) dataExcel.get(row)).get(12), XSDDatatype.XSDinteger.getURI());
                                            r.addProperty(SH.minLength, constr2);

                                        }
                                        case "equals" -> {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.equals, eq);
                                        }
                                        case "disjoint" -> {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.disjoint, eq);
                                        }
                                        case "lessThan" -> {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThan, eq);
                                        }
                                        case "lessThanOrEquals" -> {
                                            String prefix = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[0];
                                            String attribute = ((LinkedList<?>) dataExcel.get(row)).get(12).toString().split(":", 2)[1];
                                            RDFNode eq = shapeModel.createResource(shapeModel.getNsPrefixURI(prefix) + attribute);
                                            r.addProperty(SH.lessThanOrEquals, eq);
                                        }
                                    }
                                }
                            }

                            //Adding of the NodeShape if it is not existing
                            Resource nodeShapeResource = ResourceFactory.createResource(nsURIprofile + className);
                            if (!shapeModel.containsResource(nodeShapeResource)) { // creates only if it is missing
                                String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(classRDF)).getFirst()).getFirst().toString();
                                ShaclTools.addNodeShape(shapeModel, nsURIprofile, className, classFullURI);
                            }
                            //add the property to the NodeShape
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
}
