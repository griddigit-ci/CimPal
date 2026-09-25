/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * Adapted for Core (no JavaFX) from eu.griddigit.cimpal.main.core.ShaclTools.
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Core utility for building the {@code shapeData} structure from an RDFS profile model.
 *
 * <p>The returned {@code shapeData} is an {@code ArrayList<Object>} whose first element is
 * another {@code ArrayList} (one entry per concrete class) used by
 * {@link ShaclFromXls#generateShaclFromXls} to look up class / attribute information.
 *
 * <p>Only concrete classes are processed (shapesOnAbstractOption == 0).
 * Base-profile inheritance is not supported in this Core version.
 */
public class ShapeDataBuilder {

    /**
     * Build shape data from an RDFS profile model.
     *
     * @param model       the RDFS profile model
     * @param rdfNs       CIMS namespace, e.g. {@code "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"}
     * @param concreteNs  URI that marks concrete classes, e.g. {@code "http://iec.ch/TC57/NonStandard/UML#concrete"}
     * @return shapeData structure (list-of-lists)
     */
    public static ArrayList<Object> constructShapeData(Model model, String rdfNs, String concreteNs) {

        String enumNS = "http://iec.ch/TC57/NonStandard/UML#enumeration";

        ArrayList<Object> rdfToShacl = new ArrayList<>();
        ArrayList<Object> shapeData = new ArrayList<>();

        for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDFS.Class); i.hasNext(); ) {
            Resource resItem = i.next();

            boolean descriptionStereotype = false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                if (k.next().toString().equals("Description")) { descriptionStereotype = true; break; }
            }

            boolean classConcrete = false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                if (k.next().toString().equals(concreteNs)) { classConcrete = true; break; }
            }

            boolean classIsEnum = false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                if (k.next().toString().equals(enumNS)) { classIsEnum = true; break; }
            }

            boolean classIsDatatype = false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                String s = k.next().toString();
                if (s.equals("Primitive") || s.equals("CIMDatatype") || s.equals("Compound")) {
                    classIsDatatype = true;
                    break;
                }
            }

            if (!classIsEnum && !classIsDatatype) {
                Resource classItem = resItem;
                ArrayList<Object> classData = new ArrayList<>();
                ArrayList<String> classMyData = new ArrayList<>();
                classMyData.add(resItem.toString());
                classMyData.add(resItem.getNameSpace());
                classMyData.add(resItem.getLocalName());
                classMyData.add(resItem.getRequiredProperty(RDFS.label).getObject().toString());
                classMyData.add(descriptionStereotype ? "Yes" : "No");
                classMyData.add(classConcrete ? "Yes" : "No");
                classMyData.add(classItem.hasProperty(RDFS.subClassOf)
                        ? classItem.getRequiredProperty(RDFS.subClassOf).getResource().toString()
                        : "No");
                classData.add(classMyData);

                // Only concrete classes; traverse hierarchy to collect inherited attributes
                if (classConcrete) {
                    int root = 0;
                    while (root == 0) {
                        classData = getLocalAttributesAssociations(classItem, model, classData, rdfNs);
                        if (classItem.hasProperty(RDFS.subClassOf)) {
                            classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource();
                        } else {
                            root = 1;
                        }
                    }
                }
                rdfToShacl.add(classData);
            }
        }

        // Add compound paths
        for (Object o : rdfToShacl) {
            for (int attr = 1; attr < ((ArrayList<?>) o).size(); attr++) {
                if (((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(0).equals("Attribute")
                        && ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(8).equals("Compound")) {
                    List<RDFNode> pathComp = new LinkedList<>();
                    RDFNode r = ResourceFactory.createResource(
                            ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(1).toString());
                    pathComp.add(r);
                    ((ArrayList) ((ArrayList<?>) o).get(attr)).add(pathComp);

                    for (int attrComp = 0; attrComp < ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).size(); attrComp++) {
                        List<RDFNode> pathComp1 = new LinkedList<>();
                        RDFNode r1 = ResourceFactory.createResource(
                                ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(1).toString());
                        pathComp1.add(r);
                        pathComp1.add(r1);
                        ((ArrayList) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).add(pathComp1);

                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(0).equals("Attribute")
                                && ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(8).equals("Compound")) {
                            for (int attrComp2 = 0; attrComp2 < ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(10)).size(); attrComp2++) {
                                List<RDFNode> pathComp2 = new LinkedList<>();
                                RDFNode r2 = ResourceFactory.createResource(
                                        ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).get(1).toString());
                                pathComp2.add(r);
                                pathComp2.add(r1);
                                pathComp2.add(r2);
                                ((ArrayList) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList) ((ArrayList) ((ArrayList) o).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).add(pathComp2);
                            }
                        }
                    }
                }
            }
        }

        shapeData.add(rdfToShacl);
        return shapeData;
    }

    // -----------------------------------------------------------------------
    // Private helpers (adapted from eu.griddigit.cimpal.main.core.ShaclTools)
    // -----------------------------------------------------------------------

    private static ArrayList<Object> getLocalAttributesAssociations(
            Resource resItem, Model model, ArrayList<Object> classData, String rdfNs) {

        for (ResIterator i = model.listResourcesWithProperty(RDFS.domain); i.hasNext(); ) {
            Resource resItemDomain = i.next();
            if (!resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())) {
                continue;
            }

            ArrayList<Object> classProperty = new ArrayList<>();
            int isAttr = 0;

            if (model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")); j.hasNext(); ) {
                    String used = j.next().toString();
                    classProperty.add("Association");
                    classProperty.add(used);   // "Yes" or "No"
                }
            } else {
                isAttr = 1;
                classProperty.add("Attribute");
            }

            classProperty.add(resItemDomain.toString());
            classProperty.add(resItemDomain.getNameSpace());
            classProperty.add(model.getNsURIPrefix(resItemDomain.getNameSpace()));
            classProperty.add(resItemDomain.getLocalName());

            for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                classProperty.add(resItemNode.toString().split("#M:", 2)[1]);
                if (isAttr == 1 && model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")).toList().size() > 1) {
                    break;
                }
            }

            if (isAttr == 1) {
                classProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]);
                classProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString());

                if (resItemDomain.hasProperty(RDFS.range)) {
                    classProperty.add("Enumeration");
                    ArrayList<Object> enumAttr = new ArrayList<>();
                    classProperty.add(resItemDomain.getProperty(RDFS.range).getObject().toString());
                    for (ResIterator ienum = model.listSubjects(); ienum.hasNext(); ) {
                        Resource resItemEnum = ienum.next();
                        if (resItemDomain.getProperty(RDFS.range).getObject().toString()
                                .equals(resItemEnum.getProperty(RDF.type).getObject().toString())) {
                            enumAttr.add(resItemEnum.toString());
                        }
                    }
                    classProperty.add(enumAttr);
                }

                for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    Resource resItemNew = model.getResource(resItemNode.toString());
                    String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2);
                    String rdfType = rdfTypeInit.length > 1 ? rdfTypeInit[1] : rdfTypeInit[0];

                    if (rdfType.equals("Class")) {
                        if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"), "Primitive")) {
                            classProperty.add("Primitive");
                            classProperty.add(resItemNode.toString());
                            classProperty.add(resItemNode.toString().split("#", 2)[1]);
                        }
                        if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"), "CIMDatatype")) {
                            classProperty.add("CIMDatatype");
                            Resource resItemDTvalue = model.getResource(resItemNew + ".value");
                            for (NodeIterator jdt = model.listObjectsOfProperty(resItemDTvalue, model.getProperty(rdfNs, "dataType")); jdt.hasNext(); ) {
                                RDFNode resItemNodedt = jdt.next();
                                classProperty.add(resItemNodedt.asResource().getLocalName());
                            }
                            classProperty.add(resItemNode.toString());
                            classProperty.add(resItemNode.toString().split("#", 2)[1]);
                        }
                        if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"), "Compound")) {
                            classProperty.add("Compound");
                            classProperty.add(resItemNew.toString());
                            ArrayList<Object> classCompound = new ArrayList<>();
                            classCompound = getAttributesOfCompound(resItemNew, model, classCompound, rdfNs);
                            classProperty.add(classCompound);
                        }
                    }
                }
            } else {
                // Association
                classProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]);
                classProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString());
                classProperty.add(resItemDomain.getRequiredProperty(RDFS.range).getObject().toString());
                classProperty.add(resItemDomain.getRequiredProperty(
                        ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#inverseRoleName"))
                        .getObject().toString());

                Resource classRange = resItemDomain.getRequiredProperty(RDFS.range).getObject().asResource();
                List<Resource> allSubclasses = getSubtypeClassesNlevel(classRange, model);

                if (allSubclasses.isEmpty()) {
                    Boolean concrete = classIsConcrete(classRange, model);
                    List<Resource> concreteSubclass = new LinkedList<>();
                    if (concrete) {
                        concreteSubclass.add(classRange);
                    } else {
                        concreteSubclass.add(ResourceFactory.createResource("http://abstract.eu"));
                    }
                    classProperty.add(concreteSubclass);
                } else {
                    List<Resource> allConcreteSubclasses = new LinkedList<>();
                    if (classIsConcrete(classRange, model)) {
                        allConcreteSubclasses.add(classRange);
                    }
                    for (Resource subclass : allSubclasses) {
                        if (classIsConcrete(subclass, model)) {
                            allConcreteSubclasses.add(subclass);
                        }
                    }
                    if (allConcreteSubclasses.isEmpty() && allSubclasses.size() == 1) {
                        allConcreteSubclasses.add(ResourceFactory.createResource("http://abstract.eu"));
                    }
                    if (!allConcreteSubclasses.isEmpty()) {
                        classProperty.add(allConcreteSubclasses);
                    }
                }
            }

            classData.add(classProperty);
        }
        return classData;
    }

    private static ArrayList<Object> getAttributesOfCompound(
            Resource resItem, Model model, ArrayList<Object> classCompound, String rdfNs) {

        for (ResIterator i = model.listResourcesWithProperty(RDFS.domain); i.hasNext(); ) {
            Resource resItemDomain = i.next();
            if (!resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())) {
                continue;
            }

            ArrayList<Object> compoundProperty = new ArrayList<>();
            int isAttr = 0;

            if (!model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                isAttr = 1;
                compoundProperty.add("Attribute");
                compoundProperty.add(resItemDomain.toString());
                compoundProperty.add(resItemDomain.getNameSpace());
                compoundProperty.add(model.getNsURIPrefix(resItemDomain.getNameSpace()));
                compoundProperty.add(resItemDomain.getLocalName());
            }

            if (isAttr == 1) {
                for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                    compoundProperty.add(j.next().toString().split("#M:", 2)[1]);
                }
                compoundProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]);
                compoundProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString());

                for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    Resource resItemNew = model.getResource(resItemNode.toString());
                    String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2);
                    String rdfType = rdfTypeInit.length > 1 ? rdfTypeInit[1] : rdfTypeInit[0];

                    if (rdfType.equals("Class")) {
                        if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"), "Primitive")) {
                            compoundProperty.add("Primitive");
                            compoundProperty.add(resItemNode.toString());
                            compoundProperty.add(resItemNode.toString().split("#", 2)[1]);
                        }
                        if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"), "Compound")) {
                            compoundProperty.add("Compound");
                            compoundProperty.add(resItemNew.toString());
                            ArrayList<Object> classCompoundNested = new ArrayList<>();
                            classCompoundNested = getAttributesOfCompound(resItemNew, model, classCompoundNested, rdfNs);
                            compoundProperty.add(classCompoundNested);
                        }
                    }
                }
                classCompound.add(compoundProperty);
            }
        }
        return classCompound;
    }

    private static List<Resource> getSubtypeClassesNlevel(Resource classRange, Model model) {
        List<Resource> subtypeClassesN = new LinkedList<>();
        List<Resource> subtypeClasses = getSubtypeClassesFirstLevel(classRange, model);
        subtypeClassesN.addAll(subtypeClasses);
        if (subtypeClasses.isEmpty()) return subtypeClassesN;

        int root = 0;
        while (root == 0) {
            List<Resource> tempList = new LinkedList<>();
            for (Resource cls : subtypeClasses) {
                List<Resource> subtypeClassesNew = getSubtypeClassesFirstLevel(cls, model);
                if (!subtypeClassesNew.isEmpty()) tempList.addAll(subtypeClassesNew);
            }
            if (tempList.isEmpty()) {
                root = 1;
            } else {
                subtypeClassesN.addAll(tempList);
                subtypeClasses = tempList;
            }
        }
        return subtypeClassesN;
    }

    private static List<Resource> getSubtypeClassesFirstLevel(Resource classRange, Model model) {
        List<Resource> subtypeClasses = new LinkedList<>();
        for (ResIterator i = model.listResourcesWithProperty(RDFS.subClassOf); i.hasNext(); ) {
            Resource res = i.next();
            if (classRange.toString().equals(res.getRequiredProperty(RDFS.subClassOf).getObject().toString())) {
                subtypeClasses.add(res);
            }
        }
        return subtypeClasses;
    }

    private static Boolean classIsConcrete(Resource resource, Model model) {
        RDFNode concreteNode = ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#", "concrete");
        Property cimsStereotype = ResourceFactory.createProperty(
                "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");
        return model.listStatements(resource, cimsStereotype, concreteNode).hasNext();
    }
}
