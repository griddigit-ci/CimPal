package eu.griddigit.cimpal.main.application.datagenerator;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConstraintsFactory {

    //Check if property has a constraint - shacl shape
    public static Map<String,Map> hasConstraintCheck(Model shaclModel, String classFullURI, Property property) {

        Map<String,Map> propertyConstraints = new HashMap<>();

        Map<String,List<RDFNode>> pathContent= new HashMap<>();
        Map<Property,List<RDFNode>> propertyShapeContent=new HashMap<>();

        //propertyType can be
        // Association for association
        // Primitive, CIMDatatype, Compound, Enumeration for attribute

        //find where the class is target class in NodeShape
        for (ResIterator ns=shaclModel.listSubjectsWithProperty(SH.targetClass,ResourceFactory.createProperty(classFullURI)); ns.hasNext();){
            Resource nsRes=ns.next();
            if (shaclModel.contains(nsRes, RDF.type,SH.NodeShape)){
                //find all property shapes that relate to that NodeShape
                for (NodeIterator shProperty=shaclModel.listObjectsOfProperty(nsRes,SH.property); shProperty.hasNext();){
                    RDFNode psNode=shProperty.next();
                    if (shaclModel.contains(psNode.asResource(), RDF.type,SH.PropertyShape)){
                        //examine the path to check for sequence path, inverse path or direct path.
                        pathContent=examineSHpath(shaclModel,psNode);

                        //here check if the property that comes from the method is found in the sh:path
                        if (pathContent.containsKey("directPath")){
                            if (pathContent.get("directPath").get(0).equals(property)){
                                propertyShapeContent= getPropertiesOfPropertyShape(shaclModel, psNode,propertyShapeContent);
                            }

                        }else if (pathContent.containsKey("sequencePath")){
                            if (pathContent.get("sequencePath").get(0).equals(property) && pathContent.get("sequencePath").get(1).equals(RDF.type)){
                                //the case when the sequence path is for the valueType for the associations.
                                propertyShapeContent= getPropertiesOfPropertyShape(shaclModel, psNode,propertyShapeContent);
                            }

                        }else if (pathContent.containsKey("inversePath")){
                            if (pathContent.get("inversePath").get(0).equals(property)){
                                propertyShapeContent= getPropertiesOfPropertyShape(shaclModel, psNode,propertyShapeContent);
                            }

                        }

                    }
                }
            }
        }



        // for the property shapes check which are value constraints and which have association relationship like sh:in
        // interesting are sh:in, the counts, min max inclusive exclusive, also length datatype?

        propertyConstraints.put("pathContent",pathContent);
        propertyConstraints.put("propertyShapeContent",propertyShapeContent);

        return propertyConstraints;
    }

    //Compare blank nodes structure
    public static Map<String,List<RDFNode>> getBlankNodeContent(Model model, Statement stmt) {
        Map<String,List<RDFNode>> result = new HashMap<>();
        Map<Boolean, List<RDFNode>> isBNlist = isBlankNodeAlist(stmt);


        if (isBNlist.containsKey(true)){ // it is a list that needs to be processed

            List<RDFNode> listModel=isBNlist.get(true);

            result.put("sequencePath",listModel);
        }else{ // more complex blank node
            if (model.listStatements(stmt.getObject().asResource(),(Property) null,(RDFNode) null).toList().size()==1) {
                if (model.contains(stmt.getObject().asResource(),SH.inversePath)) {
                    List<RDFNode> inversePathValue=new ArrayList<>();
                    inversePathValue.add(model.getRequiredProperty(stmt.getObject().asResource(), SH.inversePath).getObject());
                    result.put("inversePath", inversePathValue);
                }else{
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Error] Please check: Not supported complex sh:path, which is not sh:inversePath found on the PropertyShape: "+model.getNsURIPrefix(stmt.getObject().asResource().getNameSpace())+":"+stmt.getObject().asResource().getLocalName(),true);
                }
            }else{
                GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Error] Please check:Not supported complex sh:path found on the PropertyShape: "+model.getNsURIPrefix(stmt.getObject().asResource().getNameSpace())+":"+stmt.getObject().asResource().getLocalName(),true);
            }
        }
        return result;
    }

    //checks if the blank node is a list
    public static Map<Boolean,List<RDFNode>> isBlankNodeAlist(Statement stmt) {
        Map<Boolean,List<RDFNode>> isBNlist=new HashMap<>();
        List<RDFNode> list = null;
        boolean isList;
        try {
            list = stmt.getList().asJavaList();
            isList=true;
        } catch (Exception e) {
            //e.printStackTrace();
            isList=false;
        }

        isBNlist.put(isList,list);
        return isBNlist;
    }

    //examine sh:path
    public static Map<String,List<RDFNode>> examineSHpath(Model shaclModel,RDFNode psNode) {
        Map<String,List<RDFNode>> pathContent=new HashMap<>();

        if (shaclModel.listObjectsOfProperty(psNode.asResource(), SH.path).hasNext()) {
            RDFNode pathObjectTemp = shaclModel.getRequiredProperty(psNode.asResource(), SH.path).getObject();
            if (pathObjectTemp.isAnon()) { //if it is a blank node
                Statement bnStmt = shaclModel.listStatements(psNode.asResource(), SH.path, pathObjectTemp).next();
                pathContent = getBlankNodeContent(shaclModel, bnStmt); //this brings inverse path and sequence path
            } else {//the case of direct path
                List<RDFNode> directPathValue = new ArrayList<>();
                directPathValue.add(pathObjectTemp);
                pathContent.put("directPath", directPathValue);
            }
        }
        return pathContent;
    }

    //get properties of PropertyShape
    public static Map<Property,List<RDFNode>> getPropertiesOfPropertyShape(Model shaclModel,RDFNode psNode, Map<Property,List<RDFNode>> propertyShapeContent) {
        //shacl properties to consider
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in

        for (StmtIterator listStmt = shaclModel.listStatements(psNode.asResource(),(Property) null, (RDFNode) null); listStmt.hasNext();){
            Statement stmt = listStmt.next();
            Property prop = stmt.getPredicate();
            propertyShapeContent = putInMapPropertiesOfPropertyShape(prop, propertyShapeContent, stmt);


        }

        return propertyShapeContent;
    }

    public static Map<Property,List<RDFNode>> putInMapPropertiesOfPropertyShape(Property prop, Map<Property,List<RDFNode>> propertyShapeContent, Statement stmt) {
        //shacl properties to consider
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in
        //sh:equals, sh:disjoint

        List<RDFNode> listTemp = new ArrayList<>();

        if (InstanceDataFactory.supportedProperties.contains(prop)){
            if (prop.equals(SH.in)){
                Map<Boolean, List<RDFNode>> isBNlist = isBlankNodeAlist(stmt);
                if (isBNlist.containsKey(true)) { // it is a list that needs to be processed
                    listTemp = isBNlist.get(true);
                }
            }else {
                RDFNode objectTemp = stmt.getObject();
                listTemp.add(objectTemp);
            }

            if (propertyShapeContent.containsKey(prop)){ // there is same type of constraint already existing. Therefore need to compare and get the most restrictive
                List<RDFNode> previousList = propertyShapeContent.get(prop);
                if (prop.equals(SH.datatype)) {
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] Please check-sh:datatype is different in different constraints: xsd:"+previousList.get(0).asResource().getLocalName()+" and xsd:"+listTemp.get(0).asResource().getLocalName(),true);
                    }
                }else if (prop.equals(SH.nodeKind)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] Please check-sh:nodeKind is different in different constraints: xsd:"+previousList.get(0).asResource().getLocalName()+" and xsd:"+listTemp.get(0).asResource().getLocalName(),true);
                    }
                }else if (prop.equals(SH.minCount)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getInt()>previousList.get(0).asLiteral().getInt()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.maxCount)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getInt()<previousList.get(0).asLiteral().getInt()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.minExclusive)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getFloat()>previousList.get(0).asLiteral().getFloat()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.maxExclusive)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getFloat()<previousList.get(0).asLiteral().getFloat()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.minInclusive)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getFloat()>previousList.get(0).asLiteral().getFloat()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.maxInclusive)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getFloat()<previousList.get(0).asLiteral().getFloat()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.minLength)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getInt()>previousList.get(0).asLiteral().getInt()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.maxLength)){
                    if (!previousList.get(0).equals(listTemp.get(0))) {
                        if (listTemp.get(0).asLiteral().getInt()<previousList.get(0).asLiteral().getInt()){
                            propertyShapeContent.replace(prop,listTemp);
                        }
                    }
                }else if (prop.equals(SH.lessThan)){
                    if (!previousList.contains(listTemp.get(0))) {
                        List<RDFNode> newList=previousList;
                        newList.add(listTemp.get(0));
                        propertyShapeContent.replace(prop,newList);
                    }
                }else if (prop.equals(SH.lessThanOrEquals)){
                    if (!previousList.contains(listTemp.get(0))) {
                        List<RDFNode> newList=previousList;
                        newList.add(listTemp.get(0));
                        propertyShapeContent.replace(prop,newList);
                    }
                }else if (prop.equals(SH.equals)){
                    if (!previousList.contains(listTemp.get(0))) {
                        List<RDFNode> newList=previousList;
                        newList.add(listTemp.get(0));
                        propertyShapeContent.replace(prop,newList);
                    }
                }else if (prop.equals(SH.disjoint)){
                    if (!previousList.contains(listTemp.get(0))) {
                        List<RDFNode> newList=previousList;
                        newList.add(listTemp.get(0));
                        propertyShapeContent.replace(prop,newList);
                    }
                }else if (prop.equals(SH.in)) {
                    Map<String,Object> compareResults = compareRDFlist(previousList,listTemp);// list1,list2
                    if((Integer) compareResults.get("sizeList1")>(Integer) compareResults.get("sizeList2")){
                        //need to check if list 2 is fully contained in list1
                        if (compareResults.containsKey("DiffSizeButNotInList1")){
                            //case 01 - diff size and list 2 is smaller list but not a constrained list
                            //list1 = item1, item2, item3
                            //list2 = item1, item4
                            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] Please check: sh:in SHACL list is different in different constraints and it does not seem to be a restriction",true);
                        }else if (compareResults.containsKey("DiffSizeButNotInList2") && !compareResults.containsKey("DiffSizeButNotInList1")){
                            //case 1 - diff size and list 2 is a constrained list
                            //list1 = item1, item2, item3
                            //list2 = item1, item2
                            propertyShapeContent.replace(prop,listTemp);
                        }

                    }else if ((Integer) compareResults.get("sizeList1")<(Integer) compareResults.get("sizeList2")){

                        if (compareResults.containsKey("DiffSizeButNotInList2")){
                            //case 02 - diff size and list 1 is smaller list but not a constrained list
                            //list2 = item1, item2, item3
                            //list1 = item1, item4
                            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] Please check: sh:in SHACL list is different in different constraints and it does not seem to be a restriction",true);
                        }else if (!compareResults.containsKey("DiffSizeButNotInList2") && compareResults.containsKey("DiffSizeButNotInList1")){
                            //case2 - diff size and list 1 is a constrained list - in that case do nothing - keep the old list
                            //list2 = item1, item2, item3
                            //list1 = item1, item2
                        }
                    }else if ((Boolean) compareResults.get("sameBySize")){
                        //case3 - same size and diff content
                        //list1 = item1, item2, item3
                        //list2 = item1, item2, item4
                        if (!((Boolean) compareResults.get("SameSizeNoDiff"))){
                            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] Please check: sh:in SHACL list has same size but different content and it does not seem to be a restriction",true);
                        }
                        //case4 - same size and same content - in that case do nothing - keep the old list
                        //list1 = item1, item2, item3
                        //list2 = item1, item2, item3
                    }
                }
            }else{ //create, as this is the first time
                propertyShapeContent.put(prop,listTemp);
            }

        }

        return propertyShapeContent;
    }

    public static List<RDFNode> supportedPropertiesInit() {
        //shacl properties to consider
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in
        //sh:equals, sh:disjoint

        List<RDFNode> supportedProperties = new ArrayList<>();
        supportedProperties.add(SH.datatype);
        supportedProperties.add(SH.nodeKind);
        supportedProperties.add(SH.minCount);
        supportedProperties.add(SH.maxCount);
        supportedProperties.add(SH.minExclusive);
        supportedProperties.add(SH.minInclusive);
        supportedProperties.add(SH.maxExclusive);
        supportedProperties.add(SH.maxInclusive);
        supportedProperties.add(SH.minLength);
        supportedProperties.add(SH.maxLength);
        supportedProperties.add(SH.lessThan);
        supportedProperties.add(SH.lessThanOrEquals);
        supportedProperties.add(SH.in);
        supportedProperties.add(SH.equals);
        supportedProperties.add(SH.disjoint);

        return supportedProperties;
    }

    //Compare RDFnode list - gives true if the lists are different
    private static Map<String,Object> compareRDFlist(List<RDFNode> list1, List<RDFNode> list2) {
        Map<String,Object> compareResult = new HashMap<>();
        List<RDFNode> notInList2 = new ArrayList<>();
        List<RDFNode> notInList1 = new ArrayList<>();
        boolean different = false;
        compareResult.put("sizeList1",list1.size());
        compareResult.put("sizeList2",list2.size());
        if (list1.size()!=list2.size()){

            compareResult.put("sameBySize",false);
            for (RDFNode resItem : list1) {
                if (!list2.contains(resItem)) {
                    different = true;
                    notInList2.add(resItem);
                }
            }
            if (different) {
                compareResult.put("DiffSizeButNotInList2", notInList2);
            }
            boolean diff2=false;
            for (RDFNode resItem : list2) {
                if (!list1.contains(resItem)) {
                    diff2 = true;
                    notInList1.add(resItem);
                }
            }
            if (diff2) {
                compareResult.put("DiffSizeButNotInList1", notInList1);
            }
        }else {
            for (RDFNode resItem : list1) {
                if (!list2.contains(resItem)) {
                    different = true;
                    notInList2.add(resItem);

                }

            }
            compareResult.put("sameBySize",true);
            if (different) {
                compareResult.put("SameSizeButNotInList2", notInList2);
                compareResult.put("SameSizeNoDiff", false);
            } else{
                compareResult.put("SameSizeNoDiff", true);
            }
        }
        return compareResult;
    }

    public static Map<Property,Map> getConstraints(Model shaclModel, ArrayList<Object> profileData) {

        Map<Property,Map> shaclConstraints = new HashMap<>();
        Property property = null;
        Map<String, Map> propertyConstraints = null;

        for (int cl = 0; cl < ((ArrayList) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(0)).get(0).toString();

            for (int atas = 1; atas < ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).size(); atas++) {
                // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                String propertyFullURI = null;
                if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                        propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                        property = ResourceFactory.createProperty(propertyFullURI);
                        /*if (propertyFullURI.contains("ConnectivityNode.ConnectivityNodeContainer")){
                            int k=1;
                        }*/

                        //Association info for target class - only for concrete classes in the profile
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size() > 10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile

                            //checks if the property has constraints
                            propertyConstraints = ConstraintsFactory.hasConstraintCheck(shaclModel, classFullURI, property);
                        }
                    }
                } else if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                    //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                    //String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(4).toString();
                    propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                    property = ResourceFactory.createProperty(propertyFullURI);

                    //checks if the property has constraints
                    propertyConstraints = ConstraintsFactory.hasConstraintCheck(shaclModel, classFullURI, property);
                }
                shaclConstraints.put(ResourceFactory.createProperty(classFullURI+"propertyFullURI"+propertyFullURI),propertyConstraints);
            }
        }

        return shaclConstraints;
    }
}
