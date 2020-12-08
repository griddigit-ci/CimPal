/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */


package core;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

public class ComparisonInstanceData {

    public static ArrayList<Object> compareInstanceData(ArrayList<Object> compareResults,Model modelA, Model modelB, LinkedList options){

        LinkedList<String> skiplist = new LinkedList();

        if ((Integer) options.get(0) == 1) { //SV
            skiplist.add("SvVoltage");
            skiplist.add("SvInjection");
            skiplist.add("SvStatus");
            skiplist.add("SvPowerFlow");
            skiplist.add("SvSwitch");
            skiplist.add("TopologicalIsland");
            skiplist.add("DCTopologicalIsland");
            skiplist.add("SvShuntCompensatorSections");
            skiplist.add("SvTapStep");
        }
        if ((Integer) options.get(1) == 1) { //DL
            skiplist.add("DiagramObject");
            skiplist.add("DiagramObjectPoint");
            skiplist.add("DiagramObjectStyle");
            skiplist.add("VisibilityLayer");
            skiplist.add("DiagramStyle");
            skiplist.add("Diagram");
            skiplist.add("DiagramObjectGluePoint");
            skiplist.add("TextDiagramObject");
        }
        if ((Integer) options.get(4) == 1) { //TP
            skiplist.add("DCTopologicalNode");
            skiplist.add("TopologicalNode");
        }


        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = compareModels(compareResults, modelA, modelB, 0, skiplist);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResults = compareModels(compareResults, modelB, modelA, 1, skiplist);

        return compareResults;

    }

    public static ArrayList<Object> compareSolution(ArrayList<Object> compareResults,Model modelA, Model modelB,String xmlBase, List options){

        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = compareSVModels(compareResults, modelA, modelB, 0,xmlBase,options);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        //compareResults = compareSVModels(compareResults, modelB, modelA, 1,xmlBase,options);

        return compareResults;

    }

    //compares two models (XML instance data)
    private static ArrayList<Object> compareSVModels(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, String xmlBase,List options){

        LinkedList<String> svList = new LinkedList();
        svList.add("SvVoltage");
        svList.add("SvInjection");
        svList.add("SvStatus");
        svList.add("SvPowerFlow");
        svList.add("SvSwitch");
        svList.add("TopologicalIsland");
        svList.add("DCTopologicalIsland");
        svList.add("SvShuntCompensatorSections");
        svList.add("SvTapStep");
        svList.add("CsConverter");
        svList.add("VsConverter");

        LinkedList<String> CsConverterList = new LinkedList();
        CsConverterList.add("ACDCConverter.idc");
        CsConverterList.add("ACDCConverter.poleLossP");
        CsConverterList.add("ACDCConverter.uc");
        CsConverterList.add("ACDCConverter.udc");
        CsConverterList.add("CsConverter.alpha");
        CsConverterList.add("CsConverter.gamma");

        LinkedList<String> VsConverterList = new LinkedList();
        VsConverterList.add("ACDCConverter.idc");
        VsConverterList.add("ACDCConverter.poleLossP");
        VsConverterList.add("ACDCConverter.uc");
        VsConverterList.add("ACDCConverter.udc");
        VsConverterList.add("VsConverter.delta");
        VsConverterList.add("VsConverter.uv");


        for (ResIterator i = modelA.listSubjectsWithProperty(RDF.type); i.hasNext(); ) {
            Resource resItem = i.next();
            String className = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();

            if (svList.contains(className)){
                if (reverse == 0) {

                    if (className.equals("SvVoltage")) {

                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode")).getObject(); //this gives the object of the association in modelA
                        Float vObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.v")).getObject().asLiteral().getFloat();
                        Float angleObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.angle")).getObject().asLiteral().getFloat();

                        if (options.get(5).equals(1)) {//ConnectivityNodes are used for the comparison. This is for CGMES v3 where the models are build with ConnectivityNodes

                            ArrayList cnListModelA = (ArrayList) modelA.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "ConnectivityNode.TopologicalNode"), assocObject).toList();

                            for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode")); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubItSv = subIt.next();
                                RDFNode assocObjectTN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode")).getObject();
                                ArrayList cnListModelB = (ArrayList) modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "ConnectivityNode.TopologicalNode"), assocObjectTN).toList();
                                int foundCN = 0;
                                for (Object listItem : cnListModelA) {
                                    if (cnListModelB.contains(listItem)) {
                                        foundCN = 1;
                                        break;
                                    }
                                }

                                if (foundCN == 1) {
                                    Float vObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.v")).getObject().asLiteral().getFloat();
                                    Float angleObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.angle")).getObject().asLiteral().getFloat();
                                    if (!vObject.equals(vObjectModelBCN)) {
                                        compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.v", vObject.toString(), vObjectModelBCN.toString());
                                        //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                    }
                                    if (!angleObject.equals(angleObjectModelBCN)) {
                                        compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.angle", angleObject.toString(), angleObjectModelBCN.toString());
                                        //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                    }
                                    break;

                                }
                            }

                        } else { // TopologicalNodes are used for the comparison

                            for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Float vObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.v")).getObject().asLiteral().getFloat();
                                Float angleObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.angle")).getObject().asLiteral().getFloat();
                                if (!vObject.equals(vObjectModelB)) {
                                    compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.v", vObject.toString(), vObjectModelB.toString());
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                                if (!angleObject.equals(angleObjectModelB)) {
                                    compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.angle", angleObject.toString(), angleObjectModelB.toString());
                                    //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                }
                            }
                        }

                    } else if (className.equals("SvPowerFlow")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal")).getObject(); //this gives the object of the association in modelA
                        Float pObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.p")).getObject().asLiteral().getFloat();
                        Float qObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.q")).getObject().asLiteral().getFloat();
                        for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                            Resource resItemSubIt = subIt.next();

                            Float pObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.p")).getObject().asLiteral().getFloat();
                            Float qObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.q")).getObject().asLiteral().getFloat();
                            if (!pObject.equals(pObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvPowerFlow.p", pObject.toString(), pObjectModelB.toString());
                                //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                            }
                            if (!qObject.equals(qObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvPowerFlow.q", qObject.toString(), qObjectModelB.toString());
                                //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                            }
                        }
                    } else if (className.equals("SvInjection")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode")).getObject(); //this gives the object of the association in modelA
                        Float pInjectionObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.pInjection")).getObject().asLiteral().getFloat();
                        Float qInjectionObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.qInjection")).getObject().asLiteral().getFloat();

                        if (options.get(5).equals(1)) {//ConnectivityNodes are used for the comparison. This is for CGMES v3 where the models are build with ConnectivityNodes

                            ArrayList cnListModelA = (ArrayList) modelA.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "ConnectivityNode.TopologicalNode"), assocObject).toList();

                            for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode")); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubItSv = subIt.next();
                                RDFNode assocObjectTN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode")).getObject();
                                ArrayList cnListModelB = (ArrayList) modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "ConnectivityNode.TopologicalNode"), assocObjectTN).toList();
                                int foundCN = 0;
                                for (Object listItem : cnListModelA) {
                                    if (cnListModelB.contains(listItem)) {
                                        foundCN = 1;
                                        break;
                                    }
                                }

                                if (foundCN == 1) {
                                    Float pInjectionObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.pInjection")).getObject().asLiteral().getFloat();
                                    Float qInjectionObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.qInjection")).getObject().asLiteral().getFloat();
                                    if (!pInjectionObject.equals(pInjectionObjectModelBCN)) {
                                        compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.pInjection", pInjectionObject.toString(), pInjectionObjectModelBCN.toString());
                                        //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                    }
                                    if (!qInjectionObject.equals(qInjectionObjectModelBCN)) {
                                        compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.qInjection", qInjectionObject.toString(), qInjectionObjectModelBCN.toString());
                                        //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                    }
                                    break;

                                }
                            }

                        } else { // TopologicalNodes are used for the comparison

                            for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Float pInjectionObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.pInjection")).getObject().asLiteral().getFloat();
                                Float qInjectionObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvInjection.qInjection")).getObject().asLiteral().getFloat();
                                if (!pInjectionObject.equals(pInjectionObjectModelB)) {
                                    compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.pInjection", pInjectionObject.toString(), pInjectionObjectModelB.toString());
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                                if (!qInjectionObject.equals(qInjectionObjectModelB)) {
                                    compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.qInjection", qInjectionObject.toString(), qInjectionObjectModelB.toString());
                                    //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                }
                            }
                        }


                    } else if (className.equals("SvSwitch")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.Switch")).getObject(); //this gives the object of the association in modelA
                        Boolean openObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.open")).getObject().asLiteral().getBoolean();
                        for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.Switch"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                            Resource resItemSubIt = subIt.next();

                            Boolean openObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.open")).getObject().asLiteral().getBoolean();
                            if (!openObject.equals(openObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvSwitch.open", openObject.toString(), openObjectModelB.toString());
                                //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                            }
                        }
                    } else if (className.equals("SvStatus")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment")).getObject(); //this gives the object of the association in modelA
                        Boolean inServiceObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.inService")).getObject().asLiteral().getBoolean();
                        for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                            Resource resItemSubIt = subIt.next();

                            Boolean inServiceObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvStatus.inService")).getObject().asLiteral().getBoolean();
                            if (!inServiceObject.equals(inServiceObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvStatus.inService", inServiceObject.toString(), inServiceObjectModelB.toString());
                                //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                            }
                        }
                    } else if (className.equals("SvShuntCompensatorSections")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator")).getObject(); //this gives the object of the association in modelA
                        Float sectionsObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.sections")).getObject().asLiteral().getFloat();
                        for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                            Resource resItemSubIt = subIt.next();

                            Float sectionsObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.sections")).getObject().asLiteral().getFloat();
                            if (!sectionsObject.equals(sectionsObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvShuntCompensatorSections.sections", sectionsObject.toString(), sectionsObjectModelB.toString());
                                //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                            }
                        }
                    } else if (className.equals("SvTapStep")) {
                        RDFNode assocObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger")).getObject(); //this gives the object of the association in modelA
                        Float positionObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.position")).getObject().asLiteral().getFloat();
                        for (ResIterator subIt = modelB.listResourcesWithProperty(ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger"), assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                            Resource resItemSubIt = subIt.next();

                            Float positionObjectModelB = modelB.getRequiredProperty(resItemSubIt, ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.position")).getObject().asLiteral().getFloat();
                            if (!positionObject.equals(positionObjectModelB)) {
                                compareResults = addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvTapStep.position", positionObject.toString(), positionObjectModelB.toString());
                                //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                            }
                        }
                    } else if (className.equals("TopologicalIsland")) {
                        // even if the ID of the topologicalIsland is different which might be OK the references need to be the same - so this is to be checked here

                    } else if (className.equals("DCTopologicalIsland")) {
                        // even if the ID of the topologicalIsland is different which might be OK the references need to be the same - so this is to be checked here

                    } else if (className.equals("CsConverter") || className.equals("VsConverter")) {

                        for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                            Statement resItemStmt = j.next();
                            String propertyName = resItemStmt.getPredicate().getLocalName();
                            if ((className.equals("CsConverter") && CsConverterList.contains(propertyName)) ||
                                    (className.equals("VsConverter") && VsConverterList.contains(propertyName)) ) {
                                Float objectModelA = resItemStmt.getObject().asLiteral().getFloat();
                                if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class has same attribute in modelB, but the object is different as does not contain the complete statement
                                    Float objectModelB = modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().asLiteral().getFloat();
                                    if (!objectModelA.equals(objectModelB)) {
                                        compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + propertyName, objectModelA.toString(), objectModelB.toString());
                                    }
                                }
                            }
                        }


                        if (className.equals("CsConverter")) {
                            Float idcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.idc")).getObject().asLiteral().getFloat();
                            Float poleLossPObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.poleLossP")).getObject().asLiteral().getFloat();
                            Float ucObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.uc")).getObject().asLiteral().getFloat();
                            Float udcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.udc")).getObject().asLiteral().getFloat();
                            Float alphaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "CsConverter.alpha")).getObject().asLiteral().getFloat();
                            Float gammaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "CsConverter.gamma")).getObject().asLiteral().getFloat();

                        }else  {
                            Float idcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.idc")).getObject().asLiteral().getFloat();
                            Float poleLossPObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.poleLossP")).getObject().asLiteral().getFloat();
                            Float ucObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.uc")).getObject().asLiteral().getFloat();
                            Float udcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.udc")).getObject().asLiteral().getFloat();
                            Float deltaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "VsConverter.delta")).getObject().asLiteral().getFloat();
                            Float uvObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "VsConverter.uv")).getObject().asLiteral().getFloat();

                        }
                    }
                }
            }
        }
        return compareResults;
    }

    private static ArrayList<Object> compareModelsDetail(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, Resource resItem){
        //compareResults= compareClass(compareResults, modelA, modelB, resItem, reverse);
        if (modelB.contains(resItem.getRequiredProperty(RDF.type))) {// the statement is in the other model which means that the class is there. Then need to compare the properties of the class

            if (reverse == 0) {// only in the first run as same classes need to be checked once
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different

                            if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class has same attribute in modelB, but the object is different as does not contain the complete statement
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString());

                            } else {//the class in model B does not contain that attribute => this attribute/association is a difference

                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A");
                            }

                        }
                    }
                }
            } else {
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different
                            if (!modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class does not have that attribute in modelB => this is difference

                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A");

                            }
                        }
                    }
                }
            }

        }else{// the class is not in the modelB, which means the needs to be put in the list of changes including all the properties of the belong to this statement (the class)
            if (resItem.getNameSpace().equals("urn:uuid:")) {// the case where there is a diff on the FullModel class
                compareResults = addResult(compareResults, resItem.getLocalName(), "md:"+resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                        "diff/new "+"md:"+resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
            }else{
                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace())+":"+resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                        "new class "+modelA.getNsURIPrefix(resItem.getNameSpace())+":"+resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
            }

            for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                Statement resItemStmt = j.next();
                if (!resItemStmt.getPredicate().equals(RDF.type)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A");
                }
            }
        }

        return compareResults;

    }

    //compares two models (XML instance data)
    private static ArrayList<Object> compareModels(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, LinkedList skiplist){
        //iterate on the items found in the rdf file
        for (ResIterator i = modelA.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();
            String classType = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
            if (!skiplist.contains(classType)) {
                compareResults = compareModelsDetail(compareResults, modelA, modelB, reverse, resItem);
            }

        }
        return compareResults;
    }


    //adds a line to the compareResults
    private static ArrayList<Object> addResult(ArrayList<Object> compareResults, String item, String property, String valueModelA, String valueModelB) {
        //item; property; value in model A; value in model B
        List<String> diffItem = new LinkedList<>();

        diffItem.add(item);
        diffItem.add(property);
        diffItem.add(valueModelA);
        diffItem.add(valueModelB);
        compareResults.add(diffItem);

        return compareResults;
    }

    //Counts classes in a model
    private static Map<String, Integer> countClasses(Model model) {
        Map<String,Integer> mapClasses = new HashMap<>();
        for (NodeIterator i = model.listObjectsOfProperty(RDF.type); i.hasNext(); ) {
            RDFNode resItem = i.next();
            //get class name
            String className = model.getNsURIPrefix(resItem.asResource().getNameSpace())+":"+resItem.asResource().getLocalName();
            mapClasses.put(className,model.listSubjectsWithProperty(RDF.type,resItem).toList().size());
        }
        return mapClasses;
    }

    //compares the count of classes in 2 models
    public static ArrayList<Object> compareCountClasses(ArrayList<Object> compareResults, Model model1, Model model2) {


        Map<String, Integer> mapClassesModel1=ComparisonInstanceData.countClasses(model1);
        Map<String, Integer> mapClassesModel2=ComparisonInstanceData.countClasses(model2);

        String compRes;

        for (Map.Entry<String, Integer> entry : mapClassesModel1.entrySet())
            if (mapClassesModel2.containsKey(entry.getKey())) {
                if (entry.getValue().toString().equals(mapClassesModel2.get(entry.getKey()).toString())){
                    compRes="Same - Compare class count";
                }else{
                    compRes="Different - Compare class count";
                }
                compareResults = addResult(compareResults, compRes, entry.getKey(), entry.getValue().toString(), mapClassesModel2.get(entry.getKey()).toString());
            }else{
                compareResults = addResult(compareResults, "Different - Compare class count", entry.getKey(), entry.getValue().toString(), "0");
            };

        for (Map.Entry<String, Integer> entry : mapClassesModel2.entrySet())
            if (!mapClassesModel1.containsKey(entry.getKey())) {
                compareResults = addResult(compareResults, "Different - Compare class count", entry.getKey(), "0", entry.getValue().toString());
            };


        return compareResults;
    }
}


































