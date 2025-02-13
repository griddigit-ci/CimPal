/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */


package eu.griddigit.cimpal.core;

import eu.griddigit.cimpal.gui.GUIhelper;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.util.Precision;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import eu.griddigit.cimpal.util.CompareFactory;

import java.util.*;

public class ComparisonInstanceData {

    public static ArrayList<Object> resultsPF;
    public static HashMap<String,Integer> totalsSvClasses;

    public static ArrayList<Object> compareInstanceData(ArrayList<Object> compareResults,Model modelA, Model modelB, LinkedList<Integer> options){

        LinkedList<String> skiplist = new LinkedList<>();

        if (options.get(0) == 1) { //SV
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
        if (options.get(1) == 1) { //DL
            skiplist.add("DiagramObject");
            skiplist.add("DiagramObjectPoint");
            skiplist.add("DiagramObjectStyle");
            skiplist.add("VisibilityLayer");
            skiplist.add("DiagramStyle");
            skiplist.add("Diagram");
            skiplist.add("DiagramObjectGluePoint");
            skiplist.add("TextDiagramObject");
        }
        if (options.get(4) == 1) { //TP
            skiplist.add("DCTopologicalNode");
            skiplist.add("TopologicalNode");
        }


        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = CompareFactory.compareModels(compareResults, modelA, modelB, 0, skiplist);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResults = CompareFactory.compareModels(compareResults, modelB, modelA, 1, skiplist);

        return compareResults;

    }

    public static ArrayList<Object> compareSolution(ArrayList<Object> compareResults,Model modelA, Model modelB,String xmlBase, LinkedList<Integer> options){

        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = compareSVModels(compareResults, modelA, modelB, 0,xmlBase,options);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        //compareResults = compareSVModels(compareResults, modelB, modelA, 1,xmlBase,options);

        return compareResults;

    }

    //compares two models (XML instance data)
    private static ArrayList<Object> compareSVModels(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, String xmlBase,LinkedList<Integer> options){

        LinkedList<String> svList = new LinkedList<>();
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

        LinkedList<String> CsConverterList = new LinkedList<>();
        CsConverterList.add("ACDCConverter.idc");
        CsConverterList.add("ACDCConverter.poleLossP");
        CsConverterList.add("ACDCConverter.uc");
        CsConverterList.add("ACDCConverter.udc");
        CsConverterList.add("CsConverter.alpha");
        CsConverterList.add("CsConverter.gamma");

        LinkedList<String> VsConverterList = new LinkedList<>();
        VsConverterList.add("ACDCConverter.idc");
        VsConverterList.add("ACDCConverter.poleLossP");
        VsConverterList.add("ACDCConverter.uc");
        VsConverterList.add("ACDCConverter.udc");
        VsConverterList.add("VsConverter.delta");
        VsConverterList.add("VsConverter.uv");

        resultsPF= new ArrayList<>(Collections.nCopies(63, 0));
        List<Double> svVoltageVA = new LinkedList<>(); // SvVoltage.v Model A
        List<Double> svVoltageAngleA= new LinkedList<>(); // SvVoltage.angle Model A
        List<Double> svVoltageVB= new LinkedList<>(); // SvVoltage.v Model B
        List<Double> svVoltageAngleB= new LinkedList<>(); // SvVoltage.angle Model B
        List<Double> svVoltageVdiff= new LinkedList<>(); // SvVoltage.v Difference
        List<Double> svVoltageAnglediff= new LinkedList<>(); // SvVoltage.angle Difference

        List<Double> svPowerFlowPA= new LinkedList<>(); // SvPowerFlow.p Model A
        List<Double> svPowerFlowPB= new LinkedList<>(); // SvPowerFlow.p Model B
        List<Double> svPowerFlowPdiff= new LinkedList<>(); // SvPowerFlow.p Difference
        List<Double> svPowerFlowQA= new LinkedList<>(); // SvPowerFlow.q Model A
        List<Double> svPowerFlowQB= new LinkedList<>(); // SvPowerFlow.q Model B
        List<Double> svPowerFlowQdiff= new LinkedList<>(); // SvPowerFlow.q Difference

        List<Double> SvInjectionPA= new LinkedList<>(); // SvInjection.pInjection Model A
        List<Double> SvInjectionPB= new LinkedList<>(); // SvInjection.pInjection Model B
        List<Double> SvInjectionPdiff= new LinkedList<>(); // SvInjection.pInjection Difference
        List<Double> SvInjectionQA= new LinkedList<>(); // SvInjection.qInjection Model A
        List<Double> SvInjectionQB= new LinkedList<>(); // SvInjection.qInjection Model B
        List<Double> SvInjectionQdiff= new LinkedList<>(); // SvInjection.qInjection Difference

        List<Boolean> SvSwitchOpenA= new LinkedList<>(); // SvSwitch.open Model A
        List<Boolean> SvSwitchOpenB= new LinkedList<>(); // SvSwitch.open Model B
        List<Integer> SvSwitchOpendiff= new LinkedList<>(); // SvSwitch.open Difference

        List<Boolean> SvStatusInServiceA= new LinkedList<>(); // SvStatus.inService Model A
        List<Boolean> SvStatusInServiceB= new LinkedList<>(); // SvStatus.inService Model B
        List<Integer> SvStatusInServiceDiff= new LinkedList<>(); // SvStatus.inService Difference


        List<Double> SvShuntCompensatorSectionsSectionsA= new LinkedList<>(); // SvShuntCompensatorSections.sections Model A
        List<Double> SvShuntCompensatorSectionsSectionsB= new LinkedList<>(); // SvShuntCompensatorSections.sections Model B
        List<Double> SvShuntCompensatorSectionsSectionsDiff= new LinkedList<>(); // SvShuntCompensatorSections.sections Difference

        List<Double> SvTapStepPositionA= new LinkedList<>(); // SvTapStep.position Model A
        List<Double> SvTapStepPositionB= new LinkedList<>(); // SvTapStep.position Model B
        List<Double> SvTapStepPositionDiff= new LinkedList<>(); // SvTapStep.position Difference

        List<Double> idcObjectA= new LinkedList<>(); // ACDCConverter.idc Model A
        List<Double> idcObjectB= new LinkedList<>(); // ACDCConverter.idc Model B
        List<Double> idcObjectDiff= new LinkedList<>(); // ACDCConverter.idc Difference

        List<Double> poleLossPObjectA= new LinkedList<>(); // ACDCConverter.poleLossP Model A
        List<Double> poleLossPObjectB= new LinkedList<>(); // ACDCConverter.poleLossP Model B
        List<Double> poleLossPObjectDiff= new LinkedList<>(); // ACDCConverter.poleLossP Difference

        List<Double> ucObjectA= new LinkedList<>(); // ACDCConverter.uc Model A
        List<Double> ucObjectB= new LinkedList<>(); // ACDCConverter.uc Model B
        List<Double> ucObjectDiff= new LinkedList<>(); // ACDCConverter.uc Difference

        List<Double> udcObjectA= new LinkedList<>(); // ACDCConverter.udc Model A
        List<Double> udcObjectB= new LinkedList<>(); // ACDCConverter.udc Model B
        List<Double> udcObjectDiff= new LinkedList<>(); // ACDCConverter.udc Difference

        List<Double> alphaObjectA= new LinkedList<>(); // CsConverter.alpha Model A
        List<Double> alphaObjectB= new LinkedList<>(); // CsConverter.alpha Model B
        List<Double> alphaObjectDiff= new LinkedList<>(); // CsConverter.alpha Difference

        List<Double> gammaObjectA= new LinkedList<>(); // CsConverter.gamma Model A
        List<Double> gammaObjectB= new LinkedList<>(); // CsConverter.gamma Model B
        List<Double> gammaObjectDiff= new LinkedList<>(); // CsConverter.gamma Difference

        List<Double> deltaObjectA= new LinkedList<>(); // VsConverter.delta Model A
        List<Double> deltaObjectB= new LinkedList<>(); // VsConverter.delta Model B
        List<Double> deltaObjectDiff= new LinkedList<>(); // VsConverter.delta Difference

        List<Double> uvObjectA= new LinkedList<>(); // VsConverter.uv Model A
        List<Double> uvObjectB= new LinkedList<>(); // VsConverter.uv Model B
        List<Double> uvObjectDiff= new LinkedList<>(); // VsConverter.uv Difference

        List<Integer> acTopolIslandA= new LinkedList<>(); //  topologicalislands in Model A
        List<Integer> acTopolIslandTNnoA= new LinkedList<>(); // number of topological nodes per topologicalisland in Model A
        List<Integer> acTopolIslandB= new LinkedList<>(); //  topologicalislands in Model B
        List<Integer> acTopolIslandTNnoB= new LinkedList<>(); // number of topological nodes per topologicalisland in Model B

        List<Integer> dcTopolIslandA= new LinkedList<>(); //  topologicalislands in Model A
        List<Integer> dcTopolIslandTNnoA= new LinkedList<>(); // number of topological nodes per topologicalisland in Model A
        List<Integer> dcTopolIslandB= new LinkedList<>(); //  topologicalislands in Model B
        List<Integer> dcTopolIslandTNnoB= new LinkedList<>(); // number of topological nodes per topologicalisland in Model B

        int totalSvVoltage=0;
        int totalSvPowerFlow=0;
        int totalSvInjection=0;
        int totalSvSwitch=0;
        int totalSvStatus=0;
        int totalSvShuntCompensatorSections =0;
        int totalSvTapStep=0;
        int totalTopologicalIsland=0;
        int totalDCTopologicalIsland=0;
        int totalCsConverter=0;
        int totalVsConverter=0;
        totalsSvClasses = new HashMap<>();



        for (ResIterator i = modelA.listSubjectsWithProperty(RDF.type); i.hasNext(); ) {
            Resource resItem = i.next();
            String className = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();

            if (svList.contains(className)){
                if (reverse == 0) {

                    Property propertyCNTN=ResourceFactory.createProperty(xmlBase + "#", "ConnectivityNode.TopologicalNode");

                    switch (className) {
                        case "SvVoltage": {
                            totalSvVoltage=totalSvVoltage+1;
                            Property propertySvVTN = ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.TopologicalNode");
                            Property propertySvVv = ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.v");
                            Property propertySvVangle = ResourceFactory.createProperty(xmlBase + "#", "SvVoltage.angle");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvVTN).getObject(); //this gives the object of the association in modelA

                            Double vObject = modelA.getRequiredProperty(resItem, propertySvVv).getObject().asLiteral().getDouble();
                            Double angleObject = modelA.getRequiredProperty(resItem, propertySvVangle).getObject().asLiteral().getDouble();

                            if (options.get(5).equals(1)) {//ConnectivityNodes are used for the comparison. This is for CGMES v3 where the models are build with ConnectivityNodes

                                ArrayList<?> cnListModelA = (ArrayList<?>) modelA.listResourcesWithProperty(propertyCNTN, assocObject).toList();

                                for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvVTN); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                    Resource resItemSubItSv = subIt.next();
                                    RDFNode assocObjectTN = modelB.getRequiredProperty(resItemSubItSv, propertySvVTN).getObject();
                                    ArrayList<?> cnListModelB = (ArrayList<?>) modelB.listResourcesWithProperty(propertyCNTN, assocObjectTN).toList();
                                    int foundCN = 0;
                                    for (Object listItem : cnListModelA) {
                                        if (cnListModelB.contains(listItem)) {
                                            foundCN = 1;
                                            break;
                                        }
                                    }

                                    if (foundCN == 1) {

                                        Double vObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, propertySvVv).getObject().asLiteral().getDouble();
                                        Double angleObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, propertySvVangle).getObject().asLiteral().getDouble();
                                        if (!vObject.equals(vObjectModelBCN)) {
                                            String rdfType = getRDFtype(modelA,assocObject.asResource());
                                            compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.v", vObject.toString(), vObjectModelBCN.toString(),rdfType);
                                            svVoltageVA.add(vObject);
                                            svVoltageVB.add(vObjectModelBCN);
                                            svVoltageVdiff.add(Math.abs(vObject - vObjectModelBCN));
                                            //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                        }
                                        if (!angleObject.equals(angleObjectModelBCN)) {
                                            String rdfType = getRDFtype(modelA,assocObject.asResource());
                                            compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.angle", angleObject.toString(), angleObjectModelBCN.toString(),rdfType);
                                            svVoltageAngleA.add(angleObject);
                                            svVoltageAngleB.add(angleObjectModelBCN);
                                            svVoltageAnglediff.add(Math.sqrt((angleObject - angleObjectModelBCN) * (angleObject - angleObjectModelBCN)));
                                            //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                        }
                                        break;

                                    }
                                }

                            } else { // TopologicalNodes are used for the comparison

                                for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvVTN, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                    Resource resItemSubIt = subIt.next();

                                    Double vObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvVv).getObject().asLiteral().getDouble();
                                    Double angleObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvVangle).getObject().asLiteral().getDouble();
                                    if (!vObject.equals(vObjectModelB)) {
                                        String rdfType = getRDFtype(modelA,assocObject.asResource());
                                        compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.v", vObject.toString(), vObjectModelB.toString(),rdfType);
                                        svVoltageVA.add(vObject);
                                        svVoltageVB.add(vObjectModelB);
                                        svVoltageVdiff.add(Math.abs(vObject - vObjectModelB));
                                        //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                    }
                                    if (!angleObject.equals(angleObjectModelB)) {
                                        String rdfType = getRDFtype(modelA,assocObject.asResource());
                                        compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvVoltage.angle", angleObject.toString(), angleObjectModelB.toString(),rdfType);
                                        svVoltageAngleA.add(angleObject);
                                        svVoltageAngleB.add(angleObjectModelB);
                                        svVoltageAnglediff.add(Math.sqrt((angleObject - angleObjectModelB) * (angleObject - angleObjectModelB)));
                                        //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                    }
                                }
                            }

                            break;
                        }
                        case "SvPowerFlow": {

                            totalSvPowerFlow=totalSvPowerFlow+1;
                            Property propertySvPFt = ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.Terminal");
                            Property propertySvPFp = ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.p");
                            Property propertySvPFq = ResourceFactory.createProperty(xmlBase + "#", "SvPowerFlow.q");
                            Property propertySvPFtCondEQ = ResourceFactory.createProperty(xmlBase + "#", "Terminal.ConductingEquipment");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvPFt).getObject(); //this gives the object of the association in modelA
                            String condEQtype = null;
                            if (modelA.listObjectsOfProperty(assocObject.asResource(),propertySvPFtCondEQ).hasNext()){
                                RDFNode condEQ=modelA.listObjectsOfProperty(assocObject.asResource(),propertySvPFtCondEQ).next();
                                 for (NodeIterator condEQtypeIter=modelA.listObjectsOfProperty(condEQ.asResource(),RDF.type); condEQtypeIter.hasNext(); ) {
                                     RDFNode condEQtypeIterNode = condEQtypeIter.next();
                                     if (!condEQtypeIterNode.asResource().getLocalName().equals("Equipment")){
                                         condEQtype=condEQtypeIterNode.asResource().getLocalName();
                                     }

                                 }
                                
                            }

                            Double pObject = modelA.getRequiredProperty(resItem, propertySvPFp).getObject().asLiteral().getDouble();
                            Double qObject = modelA.getRequiredProperty(resItem, propertySvPFq).getObject().asLiteral().getDouble();
                            for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvPFt, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Double pObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvPFp).getObject().asLiteral().getDouble();
                                Double qObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvPFq).getObject().asLiteral().getDouble();
                                if (!pObject.equals(pObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName()+" | "+condEQtype, "cim:SvPowerFlow.p", pObject.toString(), pObjectModelB.toString(),rdfType);
                                    svPowerFlowPA.add(pObject);
                                    svPowerFlowPB.add(pObjectModelB);
                                    svPowerFlowPdiff.add(Math.sqrt((pObject - pObjectModelB) * (pObject - pObjectModelB)));
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                                if (!qObject.equals(qObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName()+" | "+condEQtype, "cim:SvPowerFlow.q", qObject.toString(), qObjectModelB.toString(),rdfType);
                                    svPowerFlowQA.add(qObject);
                                    svPowerFlowQB.add(qObjectModelB);
                                    svPowerFlowQdiff.add(Math.sqrt((qObject - qObjectModelB) * (qObject - qObjectModelB)));
                                    //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                }
                            }
                            break;
                        }
                        case "SvInjection": {
                            totalSvInjection=totalSvInjection+1;

                            Property propertySvInTN = ResourceFactory.createProperty(xmlBase + "#", "SvInjection.TopologicalNode");
                            Property propertySvInp = ResourceFactory.createProperty(xmlBase + "#", "SvInjection.pInjection");
                            Property propertySvInq = ResourceFactory.createProperty(xmlBase + "#", "SvInjection.qInjection");


                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvInTN).getObject(); //this gives the object of the association in modelA

                            Double pInjectionObject = modelA.getRequiredProperty(resItem, propertySvInp).getObject().asLiteral().getDouble();
                            Double qInjectionObject = modelA.getRequiredProperty(resItem, propertySvInq).getObject().asLiteral().getDouble();

                            if (options.get(5).equals(1)) {//ConnectivityNodes are used for the comparison. This is for CGMES v3 where the models are build with ConnectivityNodes

                                ArrayList<Resource> cnListModelA = (ArrayList<Resource>) modelA.listResourcesWithProperty(propertyCNTN, assocObject).toList();

                                for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvInTN); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                    Resource resItemSubItSv = subIt.next();
                                    RDFNode assocObjectTN = modelB.getRequiredProperty(resItemSubItSv, propertySvInTN).getObject();
                                    ArrayList<Resource> cnListModelB = (ArrayList<Resource>) modelB.listResourcesWithProperty(propertyCNTN, assocObjectTN).toList();
                                    int foundCN = 0;
                                    for (Resource listItem : cnListModelA) {
                                        if (cnListModelB.contains(listItem)) {
                                            foundCN = 1;
                                            break;
                                        }
                                    }

                                    if (foundCN == 1) {
                                        Double pInjectionObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, propertySvInp).getObject().asLiteral().getDouble();
                                        Double qInjectionObjectModelBCN = modelB.getRequiredProperty(resItemSubItSv, propertySvInq).getObject().asLiteral().getDouble();
                                        if (!pInjectionObject.equals(pInjectionObjectModelBCN)) {
                                            String rdfType = getRDFtype(modelA,assocObject.asResource());
                                            compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.pInjection", pInjectionObject.toString(), pInjectionObjectModelBCN.toString(),rdfType);
                                            SvInjectionPA.add(pInjectionObject);
                                            SvInjectionPB.add(pInjectionObjectModelBCN);
                                            SvInjectionPdiff.add(Math.sqrt((pInjectionObject - pInjectionObjectModelBCN) * (pInjectionObject - pInjectionObjectModelBCN)));
                                            //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                        }
                                        if (!qInjectionObject.equals(qInjectionObjectModelBCN)) {
                                            String rdfType = getRDFtype(modelA,assocObject.asResource());
                                            compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.qInjection", qInjectionObject.toString(), qInjectionObjectModelBCN.toString(),rdfType);
                                            SvInjectionQA.add(qInjectionObject);
                                            SvInjectionQB.add(qInjectionObjectModelBCN);
                                            SvInjectionQdiff.add(Math.sqrt((qInjectionObject - qInjectionObjectModelBCN) * (qInjectionObject - qInjectionObjectModelBCN)));
                                            //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                        }
                                        break;

                                    }
                                }

                            } else { // TopologicalNodes are used for the comparison

                                for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvInTN, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                    Resource resItemSubIt = subIt.next();

                                    Double pInjectionObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvInp).getObject().asLiteral().getDouble();
                                    Double qInjectionObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvInq).getObject().asLiteral().getDouble();
                                    if (!pInjectionObject.equals(pInjectionObjectModelB)) {
                                        String rdfType = getRDFtype(modelA,assocObject.asResource());
                                        compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.pInjection", pInjectionObject.toString(), pInjectionObjectModelB.toString(),rdfType);
                                        SvInjectionPA.add(pInjectionObject);
                                        SvInjectionPB.add(pInjectionObjectModelB);
                                        SvInjectionPdiff.add(Math.sqrt((pInjectionObject - pInjectionObjectModelB) * (pInjectionObject - pInjectionObjectModelB)));
                                        //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                    }
                                    if (!qInjectionObject.equals(qInjectionObjectModelB)) {
                                        String rdfType = getRDFtype(modelA,assocObject.asResource());
                                        compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvInjection.qInjection", qInjectionObject.toString(), qInjectionObjectModelB.toString(),rdfType);
                                        SvInjectionQA.add(qInjectionObject);
                                        SvInjectionQB.add(qInjectionObjectModelB);
                                        SvInjectionQdiff.add(Math.sqrt((qInjectionObject - qInjectionObjectModelB) * (qInjectionObject - qInjectionObjectModelB)));
                                        //System.out.println(angleObject.asLiteral().getFloat() - angleObjectModelB.asLiteral().getFloat());
                                    }
                                }
                            }


                            break;
                        }
                        case "SvSwitch": {
                            totalSvSwitch=totalSvSwitch+1;

                            Property propertySvSwSw = ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.Switch");
                            Property propertySvSwo = ResourceFactory.createProperty(xmlBase + "#", "SvSwitch.open");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvSwSw).getObject(); //this gives the object of the association in modelA

                            Boolean openObject = modelA.getRequiredProperty(resItem, propertySvSwo).getObject().asLiteral().getBoolean();
                            for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvSwSw, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Boolean openObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvSwo).getObject().asLiteral().getBoolean();
                                if (!openObject.equals(openObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvSwitch.open", openObject.toString(), openObjectModelB.toString(),rdfType);
                                    SvSwitchOpenA.add(openObject);
                                    SvSwitchOpenB.add(openObjectModelB);
                                    SvSwitchOpendiff.add(1);
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                            }
                            break;
                        }
                        case "SvStatus": {
                            totalSvStatus=totalSvStatus+1;
                            Property propertySvStCE = ResourceFactory.createProperty(xmlBase + "#", "SvStatus.ConductingEquipment");
                            Property propertySvStInS = ResourceFactory.createProperty(xmlBase + "#", "SvStatus.inService");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvStCE).getObject(); //this gives the object of the association in modelA

                            Boolean inServiceObject = modelA.getRequiredProperty(resItem, propertySvStInS).getObject().asLiteral().getBoolean();
                            for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvStCE, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Boolean inServiceObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvStInS).getObject().asLiteral().getBoolean();
                                if (!inServiceObject.equals(inServiceObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvStatus.inService", inServiceObject.toString(), inServiceObjectModelB.toString(),rdfType);
                                    SvStatusInServiceA.add(inServiceObject);
                                    SvStatusInServiceB.add(inServiceObjectModelB);
                                    SvStatusInServiceDiff.add(1);
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                            }
                            break;
                        }
                        case "SvShuntCompensatorSections": {
                            totalSvShuntCompensatorSections=totalSvShuntCompensatorSections+1;

                            Property propertySvShSC = ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.ShuntCompensator");
                            Property propertySvShS = ResourceFactory.createProperty(xmlBase + "#", "SvShuntCompensatorSections.sections");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvShSC).getObject(); //this gives the object of the association in modelA

                            Double sectionsObject = modelA.getRequiredProperty(resItem, propertySvShS).getObject().asLiteral().getDouble();
                            for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvShSC, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Double sectionsObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvShS).getObject().asLiteral().getDouble();
                                if (!sectionsObject.equals(sectionsObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvShuntCompensatorSections.sections", sectionsObject.toString(), sectionsObjectModelB.toString(),rdfType);

                                    SvShuntCompensatorSectionsSectionsA.add(sectionsObject);
                                    SvShuntCompensatorSectionsSectionsB.add(sectionsObjectModelB);
                                    SvShuntCompensatorSectionsSectionsDiff.add(Math.sqrt((sectionsObject - sectionsObjectModelB) * (sectionsObject - sectionsObjectModelB)));
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                            }
                            break;
                        }
                        case "SvTapStep": {
                            totalSvTapStep=totalSvTapStep+1;

                            Property propertySvTsTC = ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.TapChanger");
                            Property propertySvTsP = ResourceFactory.createProperty(xmlBase + "#", "SvTapStep.position");

                            RDFNode assocObject = modelA.getRequiredProperty(resItem, propertySvTsTC).getObject(); //this gives the object of the association in modelA

                            Double positionObject = modelA.getRequiredProperty(resItem, propertySvTsP).getObject().asLiteral().getDouble();
                            for (ResIterator subIt = modelB.listResourcesWithProperty(propertySvTsTC, assocObject); subIt.hasNext(); ) { //resource iterator on the subjects that have that association; then that subject would have an object that is equal to modelA and then compare .angle and .v
                                Resource resItemSubIt = subIt.next();

                                Double positionObjectModelB = modelB.getRequiredProperty(resItemSubIt, propertySvTsP).getObject().asLiteral().getDouble();
                                if (!positionObject.equals(positionObjectModelB)) {
                                    String rdfType = getRDFtype(modelA,assocObject.asResource());
                                    compareResults = CompareFactory.addResult(compareResults, assocObject.asResource().getLocalName(), "cim:SvTapStep.position", positionObject.toString(), positionObjectModelB.toString(),rdfType);
                                    SvTapStepPositionA.add(positionObject);
                                    SvTapStepPositionB.add(positionObjectModelB);
                                    SvTapStepPositionDiff.add(Math.sqrt((positionObject - positionObjectModelB) * (positionObject - positionObjectModelB)));
                                    //System.out.println(vObject.asLiteral().getFloat() - vObjectModelB.asLiteral().getFloat());
                                }
                            }
                            break;
                        }
                        case "TopologicalIsland":
                            totalTopologicalIsland=totalTopologicalIsland+1;
                            Property AssocTITN = ResourceFactory.createProperty(xmlBase + "#", "TopologicalIsland.TopologicalNodes");

                            acTopolIslandA.add(1);//  topologicalisland in Model A

                            acTopolIslandTNnoA.add(resItem.listProperties(AssocTITN).toList().size());// number of topological nodes per topologicalisland in Model A


                            break;
                        case "DCTopologicalIsland":
                            totalDCTopologicalIsland=totalDCTopologicalIsland+1;

                            Property DCAssocTITN = ResourceFactory.createProperty(xmlBase + "#", "DCTopologicalIsland.DCTopologicalNodes");

                            dcTopolIslandA.add(1);// dc topologicalisland in Model A

                            dcTopolIslandTNnoA.add(resItem.listProperties(DCAssocTITN).toList().size());// number of topological nodes per dc topologicalisland in Model A


                            break;
                        case "CsConverter":
                        case "VsConverter":

                            if (className.equals("CsConverter")){
                                totalCsConverter=totalCsConverter+1;
                            }else{
                                totalVsConverter=totalVsConverter+1;
                            }
                            for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                                Statement resItemStmt = j.next();
                                String propertyName = resItemStmt.getPredicate().getLocalName();
                                if ((className.equals("CsConverter") && CsConverterList.contains(propertyName)) ||
                                        (className.equals("VsConverter") && VsConverterList.contains(propertyName))) {

                                /*Double idcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.idc")).getObject().asLiteral().getDouble();
                                Double poleLossPObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.poleLossP")).getObject().asLiteral().getDouble();
                                Double ucObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.uc")).getObject().asLiteral().getDouble();
                                Double udcObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "ACDCConverter.udc")).getObject().asLiteral().getDouble();

                                if (className.equals("CsConverter")) {
                                    Double alphaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "CsConverter.alpha")).getObject().asLiteral().getDouble();
                                    Double gammaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "CsConverter.gamma")).getObject().asLiteral().getDouble();

                                }else  {
                                    Double deltaObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "VsConverter.delta")).getObject().asLiteral().getDouble();
                                    Double uvObject = modelA.getRequiredProperty(resItem, ResourceFactory.createProperty(xmlBase + "#", "VsConverter.uv")).getObject().asLiteral().getDouble();

                                }*/


                                    Double objectModelA = resItemStmt.getObject().asLiteral().getDouble();
                                    if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class has same attribute in modelB, but the object is different as does not contain the complete statement
                                        Double objectModelB = modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().asLiteral().getDouble();
                                        if (!objectModelA.equals(objectModelB)) {
                                            String rdfType = getRDFtype(modelA,resItem);
                                            compareResults = CompareFactory.addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + propertyName, objectModelA.toString(), objectModelB.toString(),rdfType);

                                            Double diff = Math.sqrt((objectModelA - objectModelB) * (objectModelA - objectModelB));
                                            // here fill in the lists
                                            switch (propertyName) {
                                                case "ACDCConverter.idc":
                                                    idcObjectA.add(objectModelA);
                                                    idcObjectB.add(objectModelB);
                                                    idcObjectDiff.add(diff);
                                                    break;
                                                case "ACDCConverter.poleLossP":
                                                    poleLossPObjectA.add(objectModelA);
                                                    poleLossPObjectB.add(objectModelB);
                                                    poleLossPObjectDiff.add(diff);

                                                    break;
                                                case "ACDCConverter.uc":
                                                    ucObjectA.add(objectModelA);
                                                    ucObjectB.add(objectModelB);
                                                    ucObjectDiff.add(diff);

                                                    break;
                                                case "ACDCConverter.udc":
                                                    udcObjectA.add(objectModelA);
                                                    udcObjectB.add(objectModelB);
                                                    udcObjectDiff.add(diff);

                                                    break;
                                                case "CsConverter.alpha":
                                                    alphaObjectA.add(objectModelA);
                                                    alphaObjectB.add(objectModelB);
                                                    alphaObjectDiff.add(diff);

                                                    break;
                                                case "CsConverter.gamma":
                                                    gammaObjectA.add(objectModelA);
                                                    gammaObjectB.add(objectModelB);
                                                    gammaObjectDiff.add(diff);

                                                    break;
                                                case "VsConverter.delta":
                                                    deltaObjectA.add(objectModelA);
                                                    deltaObjectB.add(objectModelB);
                                                    deltaObjectDiff.add(diff);

                                                    break;
                                                case "VsConverter.uv":
                                                    uvObjectA.add(objectModelA);
                                                    uvObjectB.add(objectModelB);
                                                    uvObjectDiff.add(diff);

                                                    break;
                                            }
                                        }
                                    }
                                }
                            }


                            break;
                    }
                }
            }
        }

        for (ResIterator i = modelB.listSubjectsWithProperty(RDF.type); i.hasNext(); ) {
            Resource resItem = i.next();
            String className = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
            if (className.equals("TopologicalIsland")) {
                Property AssocTITN=ResourceFactory.createProperty(xmlBase + "#", "TopologicalIsland.TopologicalNodes");

                acTopolIslandB.add(1);//  topologicalisland in Model B
                acTopolIslandTNnoB.add(resItem.listProperties(AssocTITN).toList().size());// number of topological nodes per topologicalisland in Model B

            } else if (className.equals("DCTopologicalIsland")) {

                Property DCAssocTITN = ResourceFactory.createProperty(xmlBase + "#", "DCTopologicalIsland.DCTopologicalNodes");

                dcTopolIslandB.add(1);// dc topologicalisland in Model B
                dcTopolIslandTNnoB.add(resItem.listProperties(DCAssocTITN).toList().size());// number of topological nodes per dc topologicalisland in Model B
            }
        }
        resultsPF.set(1,svVoltageVA);
        resultsPF.set(2,svVoltageAngleA);
        resultsPF.set(3,svVoltageVB);
        resultsPF.set(4,svVoltageAngleB);
        resultsPF.set(5,svVoltageVdiff);
        resultsPF.set(6,svVoltageAnglediff);

        resultsPF.set(7,svPowerFlowPA);
        resultsPF.set(8,svPowerFlowPB);
        resultsPF.set(9,svPowerFlowPdiff);
        resultsPF.set(10,svPowerFlowQA);
        resultsPF.set(11,svPowerFlowQB);
        resultsPF.set(12,svPowerFlowQdiff);

        resultsPF.set(13,SvInjectionPA);
        resultsPF.set(14,SvInjectionPB);
        resultsPF.set(15,SvInjectionPdiff);
        resultsPF.set(16,SvInjectionQA);
        resultsPF.set(17,SvInjectionQB);
        resultsPF.set(18,SvInjectionQdiff);

        resultsPF.set(19,SvSwitchOpenA);
        resultsPF.set(20,SvSwitchOpenB);
        resultsPF.set(21,SvSwitchOpendiff);

        resultsPF.set(22,SvStatusInServiceA);
        resultsPF.set(23,SvStatusInServiceB);
        resultsPF.set(24,SvStatusInServiceDiff);

        resultsPF.set(25,SvShuntCompensatorSectionsSectionsA);
        resultsPF.set(26,SvShuntCompensatorSectionsSectionsB);
        resultsPF.set(27,SvShuntCompensatorSectionsSectionsDiff);

        resultsPF.set(28,SvTapStepPositionA);
        resultsPF.set(29,SvTapStepPositionB);
        resultsPF.set(30,SvTapStepPositionDiff);

        resultsPF.set(31,idcObjectA);
        resultsPF.set(32,idcObjectB);
        resultsPF.set(33,idcObjectDiff);

        resultsPF.set(34,poleLossPObjectA);
        resultsPF.set(35,poleLossPObjectB);
        resultsPF.set(36,poleLossPObjectDiff);

        resultsPF.set(37,ucObjectA);
        resultsPF.set(38,ucObjectB);
        resultsPF.set(39,ucObjectDiff);

        resultsPF.set(40,udcObjectA);
        resultsPF.set(41,udcObjectB);
        resultsPF.set(42,udcObjectDiff);

        resultsPF.set(43,alphaObjectA);
        resultsPF.set(44,alphaObjectB);
        resultsPF.set(45,alphaObjectDiff);

        resultsPF.set(46,gammaObjectA);
        resultsPF.set(47,gammaObjectB);
        resultsPF.set(48,gammaObjectDiff);

        resultsPF.set(49,deltaObjectA);
        resultsPF.set(50,deltaObjectB);
        resultsPF.set(51,deltaObjectDiff);

        resultsPF.set(52,uvObjectA);
        resultsPF.set(53,uvObjectB);
        resultsPF.set(54,uvObjectDiff);

        resultsPF.set(55,acTopolIslandA);
        resultsPF.set(56,acTopolIslandTNnoA);
        resultsPF.set(57,acTopolIslandB);
        resultsPF.set(58,acTopolIslandTNnoB);

        resultsPF.set(59,dcTopolIslandA);
        resultsPF.set(60,dcTopolIslandTNnoA);
        resultsPF.set(61,dcTopolIslandB);
        resultsPF.set(62,dcTopolIslandTNnoB);

        totalsSvClasses.put("SvVoltage",totalSvVoltage);
        totalsSvClasses.put("SvPowerFlow",totalSvPowerFlow);
        totalsSvClasses.put("SvInjection",totalSvInjection);
        totalsSvClasses.put("SvSwitch",totalSvSwitch);
        totalsSvClasses.put("SvStatus",totalSvStatus);
        totalsSvClasses.put("SvShuntCompensatorSections",totalSvShuntCompensatorSections);
        totalsSvClasses.put("SvTapStep",totalSvTapStep);
        totalsSvClasses.put("TopologicalIsland",totalTopologicalIsland);
        totalsSvClasses.put("DCTopologicalIsland",totalDCTopologicalIsland);
        totalsSvClasses.put("CsConverter",totalCsConverter);
        totalsSvClasses.put("VsConverter",totalVsConverter);

        return compareResults;
    }


    //Create solution overview
    public static List<String> solutionOverview() {

        List<String> solutionOverviewResult = new LinkedList<>();
        //process SvVoltage
        HashMap<String,Object> vMag=solutionResultElements(resultsPF,5,2);
        solutionOverviewResult.add("There are "+ vMag.get("size") +" nodes with different voltage magnitude out of "+totalsSvClasses.get("SvVoltage")+". The maximum difference is "+ vMag.get("max")+". The average difference is "+vMag.get("mean")+"." );

        HashMap<String,Object> vAngle=solutionResultElements(resultsPF,6,2);
        solutionOverviewResult.add("There are "+ vAngle.get("size") +" nodes with different voltage angle out of "+totalsSvClasses.get("SvVoltage")+". The maximum difference is "+ vAngle.get("max")+". The average difference is "
                +vAngle.get("mean")+"." );

        //process SvPowerFlow
        HashMap<String,Object> svPFp=solutionResultElements(resultsPF,9,2);
        solutionOverviewResult.add("There are "+ svPFp.get("size") +" nodes with different active power out of "+totalsSvClasses.get("SvPowerFlow")+". The maximum difference is "+ svPFp.get("max")+". The average difference is "+svPFp.get("mean")+"." );

        HashMap<String,Object> svPFq=solutionResultElements(resultsPF,12,2);
        solutionOverviewResult.add("There are "+ svPFq.get("size") +" nodes with different reactive power out of "+totalsSvClasses.get("SvPowerFlow")+". The maximum difference is "+ svPFq.get("max")+". The average difference is "+svPFq.get("mean")+"." );

        //process SvInjection
        HashMap<String,Object> svInjp=solutionResultElements(resultsPF,15,2);
        solutionOverviewResult.add("There are "+ svInjp.get("size") +" nodes with different active power/SvInjection out of "+totalsSvClasses.get("SvInjection")+". The maximum difference is "+ svInjp.get("max")+". The average difference is "+svInjp.get("mean")+"." );

        HashMap<String,Object> svInjq=solutionResultElements(resultsPF,18,2);
        solutionOverviewResult.add("There are "+ svInjq.get("size") +" nodes with different reactive power/SvInjection out of "+totalsSvClasses.get("SvInjection")+". The maximum difference is "+ svInjq.get("max")+". The average difference is "+svInjq.get("mean")+"." );


        //process SvSwitch
        HashMap<String,Object> svSWs=solutionResultElements(resultsPF,21,2);
        solutionOverviewResult.add("There are "+ svSWs.get("size") +" switches with different state out of "+totalsSvClasses.get("SvSwitch")+"." );


        //process SvStatus
        HashMap<String,Object> svSt=solutionResultElements(resultsPF,24,2);
        solutionOverviewResult.add("There are "+ svSt.get("size") +" equipments with different status (SvStatus) out of "+totalsSvClasses.get("SvStatus")+"." );

        //process SvShuntCompensatorSections
        HashMap<String,Object> svStS=solutionResultElements(resultsPF,27,2);
        solutionOverviewResult.add("There are "+ svStS.get("size") +" shunt compensators with different sections (SvShuntCompensatorSections) out of "+totalsSvClasses.get("SvShuntCompensatorSections")+". The maximum difference is "+ svStS.get("max")+". The average difference is "+svStS.get("mean")+"."  );


        //process SvTapStep
        HashMap<String,Object> svStepP=solutionResultElements(resultsPF,30,2);
        solutionOverviewResult.add("There are "+ svStepP.get("size") +" tap changers with different position (SvTapStep) out of "+totalsSvClasses.get("SvTapStep")+". The maximum difference is "+ svStepP.get("max")+". The average difference is "+svStepP.get("mean")+"."  );


        //process TopologicalIsland
        //TODO

        //process DCTopologicalIsland
        //TODO

        //process CsConverter
        //TODO

        //process VsConverter
        //TODO





        //print the result in the output window
        for (String strValue : solutionOverviewResult) {
            GUIhelper.appendTextToOutputWindow(strValue,true);
        }
        return solutionOverviewResult;
    }

    //Give size, max and mean for the List
    public static HashMap<String, Object> solutionResultElements(ArrayList<Object> results, int arrayIndex, int precisionRoundScale) {
        HashMap<String, Object> resultElements = new HashMap<>();

        if (results == null || results.isEmpty() || arrayIndex < 0 || arrayIndex >= results.size()) {
            resultElements.put("size", 0);
            resultElements.put("max", "N/A");
            resultElements.put("mean", "N/A");
            return resultElements;
        }

        List<Double> valuesT = ((List) results.get(arrayIndex));
        List<Double> values = new LinkedList<>();
        for (Double val : valuesT){
            values.add(val.doubleValue());
        }
        if (!values.isEmpty()) {
            Double maxValue = Collections.max(values);
            Mean mean = new Mean();
            double[] primitiveValues = ArrayUtils.toPrimitive(values.toArray(new Double[0]));

            resultElements.put("size", values.size());
            resultElements.put("max", Precision.round(maxValue, precisionRoundScale));
            resultElements.put("mean", Precision.round(mean.evaluate(primitiveValues), precisionRoundScale));
        } else {
            resultElements.put("size", 0);
            resultElements.put("max", "N/A");
            resultElements.put("mean", "N/A");
        }

        return resultElements;
    }

    //Give size, max and mean for the List
    public static String getRDFtype(Model model, Resource resource) {
        Statement rdfType = model.getRequiredProperty(resource,RDF.type);
        Resource rdfTypeRes = rdfType.getObject().asResource();
        String rdfTypeNS = rdfTypeRes.getNameSpace();
        String rdfTypeLN = rdfTypeRes.getLocalName();

        return model.getNsURIPrefix(rdfTypeNS)+":"+rdfTypeLN;
    }

}


































