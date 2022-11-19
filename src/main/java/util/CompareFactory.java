package util;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

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
        if (modelB.contains(resItem.getRequiredProperty(RDF.type))) {// the statement is in the other model which means that the class is there. Then need to compare the properties of the class

            if (reverse == 0) {// only in the first run as same classes need to be checked once
                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class that has rdf:type
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {

                           if (resItemStmt.getObject().isAnon()) {// if it is a blank node
                               Map<String,String> resultBN=compareBlankNode(modelA, modelB, resItemStmt,reverse);
                               compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resultBN.get("modelA"), resultBN.get("modelB"));
                           }else {
                               if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different

                                   if (modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class has same attribute in modelB, but the object is different as does not contain the complete statement
                                       compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), modelB.getRequiredProperty(resItemStmt.getSubject(), resItemStmt.getPredicate()).getObject().toString());

                                   } else {//the class in model B does not contain that attribute => this attribute/association is a difference

                                       compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A");
                                   }

                               }
                           }
                        }else {
                            if (modelB.contains(resItem,OWL2.oneOf)){
                                List<RDFNode> modelAlist= resItemStmt.getList().asJavaList();
                                List<RDFNode> modelBlist = modelB.getRequiredProperty(resItem,OWL2.oneOf).getList().asJavaList();
                                if (compareRDFlist(modelAlist,modelBlist)){
                                    compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), modelAlist.toString(), modelBlist.toString());
                                }

                            }else{
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getList().asJavaList().toString(), "N/A");
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
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resultBN.get("modelA"), resultBN.get("modelB"));
                            }else {
                                if (!modelB.contains(resItemStmt)) {// does not contain the statement, i.e. the attribute/association; then the value needs to be compared as maybe it is either missing or just the value is different
                                    if (!modelB.contains(resItemStmt.getSubject(), resItemStmt.getPredicate())) {// the class does not have that attribute in modelB => this is difference

                                        compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A", resItemStmt.getObject().toString());

                                    }
                                }
                            }
                        }else { //if the predicate is owl:oneOf
                            if (!modelB.contains(resItem, OWL2.oneOf)) {
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A", resItemStmt.getList().asJavaList().toString());
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
                                "diff/new " + "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
                    }else{
                        compareResults = addResult(compareResults, resItem.getLocalName(), "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                "N/A", "diff/new " + "md:" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName());
                    }
                } else {
                    if (reverse==0) {
                        //TODO check this; added for the property instead of class
                        if (resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Property") || resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Class")) {
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "New class: " , "N/A");
                        }else {
                            //compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                            //        "new class " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "New property: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getLocalName() + " in class: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(), "N/A");
                        }
                    }else{
//TODO check this; added for the property instead of class
                        if (resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Property") || resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName().equals("Class")) {
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "N/A","New class: " );
                        }else {
                            //compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                            //        "N/A","new class " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName());
                            compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName(),
                                    "N/A","New property: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getLocalName() + " in class: " + modelA.getNsURIPrefix(resItem.getNameSpace()) + ":" + resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName());
                        }
                    }
                }

                for (StmtIterator j = resItem.listProperties(); j.hasNext(); ) { //iterates on the properties of the class
                    Statement resItemStmt = j.next();
                    if (!resItemStmt.getPredicate().equals(RDF.type)) {
                        if (!resItemStmt.getPredicate().equals(OWL2.oneOf)) {
                            if (reverse==0) {
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), resItemStmt.getObject().toString(), "N/A");
                            }else{
                                compareResults = addResult(compareResults, resItem.getLocalName(), modelA.getNsURIPrefix(resItemStmt.getPredicate().getNameSpace()) + ":" + resItemStmt.getPredicate().getLocalName(), "N/A",  resItemStmt.getObject().toString());
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
    public static ArrayList<Object> addResult(ArrayList<Object> compareResults, String item, String property, String valueModelA, String valueModelB) {
        //item; property; value in model A; value in model B
        List<String> diffItem = new LinkedList<>();

        diffItem.add(item);
        diffItem.add(property);
        diffItem.add(valueModelA);
        diffItem.add(valueModelB);
        compareResults.add(diffItem);

        return compareResults;
    }

    //compares the count of classes in 2 models
    public static ArrayList<Object> compareCountClasses(ArrayList<Object> compareResults, Model model1, Model model2) {


        Map<String, Integer> mapClassesModel1= countClasses(model1);
        Map<String, Integer> mapClassesModel2=countClasses(model2);

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
        if (list1.size()!=list2.size()){
            different=true;
        }else {
            for (RDFNode resItem : list1) {
                if (!list2.contains(resItem)) {
                    different = true;
                }

            }
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
            Resource subjectNew = null;
            Property predicateNew =null;
            RDFNode objectNew = null;

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
                if (modelB.listStatements(new SimpleSelector(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null)).hasNext()) {
                    //it means that modelB has same attribute where the blank node is
                    Map<Boolean, List<RDFNode>> isBNlistB = isBlankNodeAlist(modelB.listStatements(new SimpleSelector(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null)).next());

                    List<RDFNode> listModelB = isBNlistB.get(true);
                    if (!compareRDFlist(listModelA, listModelB)) {
                        // the lists are different and the result will need to be recorded

                        result.put("modelA",listModelA.toString());
                        result.put("modelB",listModelB.toString());
                    }

                } else { //the attribute is not in modelB - record difference


                    result.put("modelA",listModelA.toString());
                    result.put("modelB","N/A");

                }
            }else{ //reverse=1
                //check only the case when the other model does not have this attribute. Other check is done in reverse=0
                if (!modelB.listStatements(new SimpleSelector(stmt.getSubject(), stmt.getPredicate(), (RDFNode) null)).hasNext()) {

                    result.put("modelA","N/A");
                    result.put("modelB",listModelA.toString());
                }

            }

        }else{ // more complex blank node
            // collects all statements that have the same subject; then properties and objects can be compared.
            //sparql select statements are appearing as object - string and the string can be compared.
            int k=1;
            //modelA.listStatements(new SimpleSelector(stmt.getObject().asResource(),(Property) null,(RDFNode) null)).toList();

            if (reverse==0) {

            }else{//reverse =1

            }
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
