package eu.griddigit.cimpal.util;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static eu.griddigit.cimpal.core.ComparisonInstanceData.getRDFtype;
import static eu.griddigit.cimpal.core.ComparisonSHACLshapes.modelsABPrefMap;

public class CompareFactory {

    //compares two models
    public static ArrayList<Object> compareModels(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, LinkedList<String> skiplist){
        //iterate on the items found in the rdf file
        for (ResIterator i = modelA.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();
            if (!resItem.isAnon()){
                String classType = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();
                if (!skiplist.contains(classType)) {
                    compareResults = compareModelsDetail(compareResults, modelA, modelB, reverse, resItem);
                }
            }
        }
        return compareResults;
    }

    private static ArrayList<Object> compareModelsDetail(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse, Resource resItem){
        //compareResults= compareClass(compareResults, modelA, modelB, resItem, reverse);
        String rdfType = getRDFtype(modelA,resItem);
        if (modelB.contains(resItem.getRequiredProperty(RDF.type))) {// the statement is in the other model which means that the class is there. Then need to compare the properties of the class

            if (resItem.getLocalName().contains("Breaker")){
                int k=1;
            }
            if (reverse == 0) {// only in the first run as same classes need to be checked once
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class that has rdf:type
                    Statement resItemStmt = j.next();
                    //model1.listStatements(new SimpleSelector(model1.listStatements(new SimpleSelector(null, ResourceFactory.createProperty(http://iec.ch/TC57/61970-552/DifferenceModel/1#reverseDifferences), (RDFNode) null)).toList().get(0).getObject().asResource(), null, (RDFNode) null)).toList().get(1).getObject()
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
//                           if (resItemStmt.getPredicate().toString().endsWith("#path")){
//                               int k=1;
//                           }
                           if (resItemStmt.getObject().isAnon()) {// if it is a blank node
                               Map<String,String> resultBN=compareBlankNode(modelA, modelB, resItemStmt,reverse);
                               if (!resultBN.isEmpty()) {
                                   compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resultBN.get("modelA"), resultBN.get("modelB"),rdfType);
                               }
                           }else {
                               if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different
                                   if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class has same attribute in modelB, but the object is different as does not contain the complete statement
                                       if (resItemStmt.getPredicate().getLocalName().equals("comment")) {
                                           //if (resItemStmt.getPredicate().getLocalName().equals("UnitMultiplier.n")){

                                           //}
                                           if (!modelB.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(), (RDFNode) null).nextStatement().getObject().asLiteral().getString().equals(modelA.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(), (RDFNode) null).nextStatement().getObject().asLiteral().getString())) {
                                               compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(), rdfType);
                                           }
                                       } else {
                                           // need to check if this is literal and then compare the literal value if it is xsd float, integer or decimal
                                           if (resItemStmt.getObject().isLiteral()) {
                                               Literal objectLit = resItemStmt.getObject().asLiteral();
                                               Object litValue = objectLit.getValue();
                                               if (objectLit.getDatatype().getURI().equals(XSDDatatype.XSDdecimal.getURI()) || objectLit.getDatatype().getURI().equals(XSDDatatype.XSDinteger.getURI()) || objectLit.getDatatype().getURI().equals(XSDDatatype.XSDfloat.getURI()) || objectLit.getDatatype().getURI().equals(XSDDatatype.XSDdateTime.getURI()) || objectLit.getDatatype().getURI().equals(XSDDatatype.XSDdateTimeStamp.getURI())) {
                                                   if (!litValue.equals(modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().asLiteral().getValue())) {
                                                       compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(), rdfType);
                                                   }
                                               } else {//when it is not having datatype or if the datatype is not xsd float, integer or decimal
                                                   compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(), rdfType);
                                               }
                                           } else {// when it is not literal
                                               compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString(), rdfType);
                                           }
                                       }

                                   } else {//the class in model B does not contain that attribute => this attribute/association is a difference
                                       compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A",rdfType);
                                   }
                               }
                           }
                        }else {
                            if (modelB.contains(resItem,OWL2.oneOf)){
                                List<RDFNode> modelAlist= resItemStmt.getList().asJavaList();
                                List<RDFNode> modelBlist = modelB.getRequiredProperty(resItem,OWL2.oneOf).getList().asJavaList();
                                if (compareRDFlist(modelAlist,modelBlist)){
                                    compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), modelAlist.toString(), modelBlist.toString(),rdfType);
                                }
                            }else{
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getList().asJavaList().toString(), "N/A",rdfType);
                            }
                        }
                    }
                }
            } else {
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                            if (resItemStmt.getObject().isAnon()) {//if it is a blank node
                                Map<String, String> resultBN = compareBlankNode(modelA, modelB, resItemStmt, reverse);
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resultBN.get("modelA"), resultBN.get("modelB"),rdfType);
                            }else {
                                if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different
                                    if (!modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class does not have that attribute in modelB => this is difference
                                        compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A", resItemStmt.getObject().toString(),rdfType);
                                    }else {
                                        List<Statement> multiplePropertiesList = modelA.listStatements(resItemStmt.getSubject(), resItemStmt.getPredicate(),(RDFNode) null).toList();
                                        if (multiplePropertiesList.size() > 1) { //there is only one attribute
                                            for (Statement st : multiplePropertiesList){
                                                if (!modelB.contains(st)) {
                                                    compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A", resItemStmt.getObject().toString(),rdfType);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }else { //if the predicate is owl:oneOf
                            if (!modelB.contains(resItem, OWL2.oneOf)) {
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A", resItemStmt.getList().asJavaList().toString(),rdfType);
                            }
                        }
                    }
                }
            }

        }else{// the class is not in the modelB, which means the needs to be put in the list of changes including all the properties of that belong to this statement (the class)
            if (!resItem.getRequiredProperty(RDF.type).getObject().equals(OWL2.Thing)) {
                if (resItem.getNameSpace().equals("urn:uuid:")) {// the case where there is a diff on the FullModel class
                    if (reverse==0) {
                        compareResults = addResult(compareResults, resItem.getLocalName(), "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                "diff/new " + "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A",rdfType);
                    }else{
                        compareResults = addResult(compareResults, resItem.getLocalName(), "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                "N/A", "diff/new " + "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),rdfType);
                    }
                } else {
                    if (reverse==0) {
                        //TODO check this; added for the property instead of class
                        if (resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Property") || resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Class")) {
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "New class: " , "N/A",rdfType);
                        }else {
                            //compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                            //        "new class " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "New property: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getLocalName() + " in class: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A",rdfType);
                        }
                    }else{
//TODO check this; added for the property instead of class
                        if (resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Property") || resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Class")) {
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "N/A","New class: ",rdfType);
                        }else {
                            //compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                            //        "N/A","new class " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName());
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "N/A","New property: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getLocalName() + " in class: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),rdfType);
                        }
                    }
                }

                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                            if (reverse==0) {
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A",rdfType);
                            }else{
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A",  resItemStmt.getObject().toString(),rdfType);
                            }
                        }
                        //TODO something should be added for the attributes that are blank nodes. Write a method to have the content of the blank node as string
                    }
                }
            }
        }
        return compareResults;
    }

    //adds a line to the compareResults
    public static ArrayList<Object> addResult(ArrayList<Object> compareResults, String item, String property, String valueModelA, String valueModelB, String rdfType) {
        //item; property; value in model A; value in model B
        List<String> diffItem = new LinkedList<>();

        if (valueModelA!=null && valueModelB!=null) {
            if (!valueModelA.isEmpty() && !valueModelB.isEmpty()) {
                // replace namespaces with prefixes
                for (Map.Entry<String,String> entry : modelsABPrefMap.entrySet()){
                    if (valueModelA.contains(entry.getValue())){
                        valueModelA = valueModelA.replace(entry.getValue(), entry.getKey()+":");
                    }
                    if (valueModelB.contains(entry.getValue())){
                        valueModelB = valueModelB.replace(entry.getValue(), entry.getKey()+":");
                    }
                }

//                // add the type if not there
//                String newItem = "";
//                if (MainController.compareIDmodel1 == null) {
//                    if (!item.contains("|")) {
//                        //look in model 1 and model 2
//                        // get the type
//                        boolean foundModel1 = false;
//                        List<Statement> model1Types = MainController.compareIDmodel1.listStatements(null, RDF.type, (RDFNode) null).toList();
//                        for (Statement stmt : model1Types) {
//                            String res = stmt.getSubject().getLocalName();
//                            if (item.equals(res)) {
//                                newItem = res + " | " + stmt.getObject().asResource().getLocalName();
//                                foundModel1 = true;
//                                break;
//                            }
//                        }
//                        if (!foundModel1) {
//                            List<Statement> model2Types = MainController.compareIDmodel2.listStatements(null, RDF.type, (RDFNode) null).toList();
//                            for (Statement stmt : model2Types) {
//                                String res = stmt.getSubject().getLocalName();
//                                if (item.equals(res)) {
//                                    newItem = res + " | " + stmt.getObject().asResource().getLocalName();
//                                    break;
//                                }
//                            }
//                        }
//                    }
//                }else{
//                    newItem = item;
//                }

                diffItem.add(item);
                diffItem.add(rdfType);
                diffItem.add(property);
                diffItem.add(valueModelA);
                diffItem.add(valueModelB);
                compareResults.add(diffItem);
            }
        }
        return compareResults;
    }

    //compares the count of classes in 2 models
    public static ArrayList<Object> compareCountClasses(ArrayList<Object> compareResults, Model model1, Model model2) {


        Map<String, Integer> mapClassesModel1 = countClasses(model1);
        Map<String, Integer> mapClassesModel2 = countClasses(model2);

        String compRes;

        for (Map.Entry<String, Integer> entry : mapClassesModel1.entrySet()) {
            if (mapClassesModel2.containsKey(entry.getKey())) {
                if (entry.getValue().toString().equals(mapClassesModel2.get(entry.getKey()).toString())) {
                    compRes = "Same - Compare class count";
                } else {
                    compRes = "Different - Compare class count";
                }
                compareResults = addResult(compareResults, compRes, entry.getKey(), entry.getValue().toString(), mapClassesModel2.get(entry.getKey()).toString(), entry.getKey());
            } else {
                compareResults = addResult(compareResults, "Different - Compare class count", entry.getKey(), entry.getValue().toString(), "0", entry.getKey());
            }
        }
        for (Map.Entry<String, Integer> entry : mapClassesModel2.entrySet()){
            if (!mapClassesModel1.containsKey(entry.getKey())) {
                compareResults = addResult(compareResults, "Different - Compare class count", entry.getKey(), "0", entry.getValue().toString(),entry.getKey());
            }
        }

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

    //Compare RDFnode list - gives true if the lists are different
    private static boolean compareRDFlist(List<RDFNode> list1, List<RDFNode> list2) {
        boolean different = false;
        if (list1 != null && list2 != null) {
            if (list1.size() != list2.size()) {
                different = true;
            } else {
                for (RDFNode resItem : list1) {
                    if (!list2.contains(resItem)) {
                        different = true;
                        break;
                    }

                }
            }
        }else {
            different = true;
        }
        return different;
    }

    //Change namespaces of resources in a model
    public static Model renameNamespaceURIresources(Model model, String namespacePrefixToChange, String newNamespace) {

        //create the new model
        Model newModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String,String> prefixMap = model.getNsPrefixMap();
        newModel.setNsPrefixes(prefixMap);

        //update the prefix map with the new namespace
        String namespaceToChange = model.getNsPrefixURI(namespacePrefixToChange);
        newModel.setNsPrefix(namespacePrefixToChange,newNamespace);

        //replace the namespace part of the URI
        for (StmtIterator i = model.listStatements(); i.hasNext(); ) {
            Statement stmtItem = i.next();

            Resource subject = stmtItem.getSubject();
            Property predicate = stmtItem.getPredicate();
            RDFNode object = stmtItem.getObject();
            Resource subjectNew;
            Property predicateNew;
            RDFNode objectNew;

            if (subject.isURIResource()) {
                if (subject.getNameSpace().equals(namespaceToChange)){
                    subjectNew = ResourceFactory.createResource(newNamespace + subject.getLocalName());
                }else{
                    subjectNew=subject;
                }
            } else {
                subjectNew=subject;
            }
            if (predicate.isURIResource()) {
                if (predicate.getNameSpace().equals(namespaceToChange)) {
                    predicateNew = ResourceFactory.createProperty(newNamespace, predicate.getLocalName());
                }else{
                    predicateNew = predicate;
                }

            }else {
                predicateNew = predicate;
            }
            if (object.isURIResource()) {
                if (object.asResource().getNameSpace().equals(namespaceToChange)) {
                    objectNew = ResourceFactory.createProperty(newNamespace, object.asResource().getLocalName());
                }else{
                    objectNew = object;
                }
            } else{
                objectNew = object;
            }

            //add the statement to the new model
            newModel.add(subjectNew,predicateNew,objectNew);

        }
        return newModel;
    }

    //Get profile URI from header
    public static String getProfileURI(Model model) {
        String profilePrefixURI=null;
        if (model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).hasNext()){
            Resource headerRes=model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).next();
            profilePrefixURI = model.getNsURIPrefix(headerRes.getNameSpace());
        }
        return profilePrefixURI;
    }

    //Compare blank nodes structure
    public static Map<String,String> compareBlankNode(Model modelA, Model modelB, Statement stmt,int reverse) {
        Map<String,String> result = new HashMap<>();
        Map<Boolean,List<RDFNode>> isBNlist = isBlankNodeAlist(stmt);


        if (isBNlist.containsKey(true)){ // it is a list that needs to be compared

            //modelA list
            List<RDFNode> listModelA=isBNlist.get(true);

            if (reverse==0) {
                //look at modelB
                if (modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).hasNext()) {
                    //it means that modelB has same attribute where the blank node is
                    Map<Boolean, List<RDFNode>> isBNlistB = isBlankNodeAlist(modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).next());

                    List<RDFNode> listModelB = isBNlistB.get(true);
                    if (compareRDFlist(listModelA, listModelB)) {
                        // the lists are different and the result will need to be recorded
                        if (listModelA != null) {
                            result.put("modelA", listModelA.toString());
                        }else{
                            result.put("modelA", "list is null");
                        }
                        if (listModelB != null) {
                            result.put("modelB", listModelB.toString());
                        }else{
                            result.put("modelB", "list is null");
                        }
                    }

                } else { //the attribute is not in modelB - record difference


                    result.put("modelA",listModelA.toString());
                    result.put("modelB","N/A");

                }
            }else{ //reverse=1
                //check only the case when the other model does not have this attribute. Other check is done in reverse=0
                if (!modelB.listStatements(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null).hasNext()) {

                    result.put("modelA","N/A");
                    result.put("modelB",listModelA.toString());
                }

            }

        }else{ // more complex blank node
            // collects all statements that have the same subject; then properties and objects can be compared.
            //sparql select statements are appearing as object - string and the string can be compared.
            int k=1;
            //modelA.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)).toList();

//            if (reverse==0) {
//
//            }else{//reverse =1
//
//            }
        }
        return result;
    }

    //
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



}
