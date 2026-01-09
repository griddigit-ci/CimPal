package eu.griddigit.cimpal.main.application.datagenerator;

import application.taskControllers.WizardContext;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ModelManipulationFactory {

    public static String cnNewUUIDconnect;
    public static String cnNewUUIDconnectTemp;
    private static Map<String,Model> baseInstanceModelMapOriginal;


        //Regenerate rdf:ID and mrid, if there. This is working for an IGM - i.e. one MAS
    public static Map<String,Model> regenerateRDFIDmodule(Map<String, Model> instanceModel, List<String> skipList, String cnIDwhenMultiply)  {

        Map<String, Model> modifiedInstanceDataMap= new HashMap<>();
        Map<String,String> idMap = new HashMap<>(); //old id, new id

        //Map<String,Model> instanceModel= loadDataMap.get("baseInstanceModelMap");

        //create the mapping and generate new IDs
        for (Map.Entry<String, Model> entry : instanceModel.entrySet()) {
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")){
                List<Resource> rdfidList = entry.getValue().listSubjectsWithProperty(RDF.type).toList();
                for (Resource rdfid : rdfidList) {
                    if (skipList.isEmpty()) {
                        //if (rdfid.getLocalName().startsWith("urn:uuid:")) {
                        if (rdfid.toString().startsWith("urn:uuid:")) {
                            idMap.putIfAbsent(rdfid.toString(), "urn:uuid:" + UUID.randomUUID());
                        } else {
                            //if (rdfid.getLocalName().startsWith("urn:uuid:")) {
                            //    idMap.putIfAbsent(rdfid.getLocalName(), "urn:uuid:" + UUID.randomUUID());
                           // } else {
                                idMap.putIfAbsent(rdfid.getLocalName(), "_" + UUID.randomUUID());
                            //}
                        }
                    }else{
                        if (skipList.contains(entry.getValue().getRequiredProperty(rdfid,RDF.type).getObject().asResource().getLocalName())) {
                            idMap.putIfAbsent(rdfid.getLocalName(), rdfid.getLocalName());
                        }else{
                            UUID newUUID = UUID.randomUUID();
                            idMap.putIfAbsent(rdfid.getLocalName(), "_" + newUUID);
                            if (!cnIDwhenMultiply.isEmpty()) {
                                if (rdfid.getLocalName().equals(cnIDwhenMultiply) || rdfid.getLocalName().equals("_"+cnNewUUIDconnectTemp)) {
                                    cnNewUUIDconnect = String.valueOf(newUUID);
                                    cnNewUUIDconnectTemp= String.valueOf(newUUID);
                                }
                            }
                        }
                    }
                }

            }
        }

        //apply new IDs
        for (Map.Entry<String, Model> entry : instanceModel.entrySet()) {
            List<Statement> stmtToRemove=new ArrayList<>();
            List<Statement> stmtToAdd=new ArrayList<>();
            if (!entry.getKey().equals("unionModel") && !entry.getKey().equals("modelUnionWithoutHeader")){
                Model newModel = entry.getValue();
                for (StmtIterator stmt= newModel.listStatements(); stmt.hasNext();) {
                    Statement stmtItem= stmt.next();
                    Resource newSubject=stmtItem.getSubject();
                    RDFNode newObject=stmtItem.getObject();
                    if (idMap.containsKey(stmtItem.getSubject().getLocalName())){
                        newSubject=ResourceFactory.createResource(stmtItem.getSubject().getNameSpace()+idMap.get(stmtItem.getSubject().getLocalName()));
                    }
                    if (stmtItem.getObject().isResource()) {
                        if (idMap.containsKey(stmtItem.getObject().asResource().getLocalName())) {
                            newObject=ResourceFactory.createProperty(stmtItem.getObject().asResource().getNameSpace()+idMap.get(stmtItem.getObject().asResource().getLocalName()));
                        }
                    }else{
                        if (idMap.containsKey(stmtItem.getObject().toString())) {
                            if (stmtItem.getPredicate().getLocalName().equals("IdentifiedObject.mRID")) {
                                newObject = ResourceFactory.createProperty( idMap.get(stmtItem.getObject().toString()).split("_", 2)[1]);
                            } else {
                                if (!stmtItem.getPredicate().getLocalName().equals("IdentifiedObject.name") && !stmtItem.getPredicate().getLocalName().equals("IdentifiedObject.description")) {
                                    newObject = ResourceFactory.createProperty(idMap.get(stmtItem.getObject().toString()));
                                }
                            }
                        }
                    }
                    stmtToRemove.add(stmtItem);
                    //GuiHelper.appendTextToOutputWindow(newSubject+" @ "+stmtItem.getPredicate()+" @ "+newObject, true);
                    stmtToAdd.add(ResourceFactory.createStatement(newSubject, stmtItem.getPredicate(),newObject));
                }
                newModel.remove(stmtToRemove);
                newModel.add(stmtToAdd);
                modifiedInstanceDataMap.put(entry.getKey(), newModel);

            }
        }

        return modifiedInstanceDataMap;
    }

    public static Set<Resource> LoadRDFAbout(String xmlBase) throws FileNotFoundException {
        Set<Resource> rdfAboutList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }
        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase+"RdfAbout")){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    rdfAboutList.add(ResourceFactory.createResource(o_i.toString()));

                }
            }
        }
        return rdfAboutList;
    }
    public static Set<Resource> LoadRDFEnum(String xmlBase) throws FileNotFoundException {
        Set<Resource> RdfEnumList = new HashSet<>();
        Model model = ModelFactory.createDefaultModel();
        InputStream inputStream = null;
        if (xmlBase.equals("http://iec.ch/TC57/CIM100")){
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v3.0.0_RDFSSerialisation.ttl");
        } else if (xmlBase.equals("http://iec.ch/TC57/2013/CIM-schema-cim16")) {
            inputStream = InstanceDataFactory.class.getResourceAsStream("/serialization/CGMES_v2.4.15_RDFSSerialisation.ttl");
        }
        if (inputStream != null) {
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.TURTLE);
        }
        else {
            throw new FileNotFoundException("File not found for serialization.");
        }

        for (StmtIterator it = model.listStatements(null,RDF.type, RDFS.Class); it.hasNext(); ) {
            Statement stmt = it.next();
            if (stmt.getSubject() == ResourceFactory.createResource(xmlBase+"RdfEnum")){
                for (NodeIterator iter = model.listObjectsOfProperty(stmt.getSubject(), OWL2.members); iter.hasNext(); ) {
                    RDFNode o_i = iter.next();
                    RdfEnumList.add(ResourceFactory.createResource(o_i.toString()));
                }
            }
        }
        return RdfEnumList;
    }

    //prepare bad value
    public static Statement createBadValue(Boolean conform, String datatypeType,String datatypeValueString,Object datatypeValuesEnum,
                                           Object concreteValue,Map<String,Object> shaclContraintResult,Property property, Resource classRes)  {

        Statement statement=null;
        String attributeValue=null;
        List<RDFNode> inValues = new ArrayList<>();

        switch (datatypeType) {
            case "Primitive":
            case "CIMDatatype": {
                switch (datatypeValueString) {
                    case "Integer":
                        if (concreteValue==null) {
                            Map<String,Integer> valuesInt = new HashMap<>();
                            if (shaclContraintResult!=null) {
                                if (shaclContraintResult.containsKey("minExclusive")) {
                                    valuesInt.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getInt());
                                }
                                if (shaclContraintResult.containsKey("minInclusive")) {
                                    valuesInt.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getInt());
                                }
                                if (shaclContraintResult.containsKey("maxExclusive")) {
                                    valuesInt.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getInt());
                                }
                                if (shaclContraintResult.containsKey("maxInclusive")) {
                                    valuesInt.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getInt());
                                }
                                if (!shaclContraintResult.containsKey("minExclusive") && !shaclContraintResult.containsKey("minInclusive")){
                                    valuesInt.put("minInclusive",0);
                                }
                                if (!shaclContraintResult.containsKey("maxExclusive") && !shaclContraintResult.containsKey("maxInclusive")){
                                    valuesInt.put("maxInclusive",999);
                                }
                                if (shaclContraintResult.containsKey("in")) {
                                    inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                }
                            }else{
                                valuesInt.put("minInclusive",0);
                                valuesInt.put("maxInclusive",999);
                            }

                            attributeValue = InstanceDataFactory.generateAttributeValueInteger(conform, valuesInt, inValues);

                        }else{
                            attributeValue=concreteValue.toString();
                        }
                        break;
                    case "Float":
                        Map<String,Float> valuesFL = new HashMap<>();
                        if (shaclContraintResult!=null) {
                            if (shaclContraintResult.containsKey("minExclusive")) {
                                valuesFL.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getFloat());
                            }
                            if (shaclContraintResult.containsKey("minInclusive")) {
                                valuesFL.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getFloat());
                            }
                            if (shaclContraintResult.containsKey("maxExclusive")) {
                                valuesFL.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getFloat());
                            }
                            if (shaclContraintResult.containsKey("maxInclusive")) {
                                valuesFL.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getFloat());
                            }
                            if (shaclContraintResult.containsKey("in")) {
                                inValues = (List<RDFNode>) shaclContraintResult.get("in");
                            }
                        }else{
                            valuesFL.put("minInclusive", (float) 0.1);
                            valuesFL.put("maxInclusive", (float) 999.1);
                        }
                        attributeValue = InstanceDataFactory.generateAttributeValueFloat(conform,valuesFL);
                        break;

                    case "String":
                    case "StringFixedLanguage":
                        Map<String,Integer> valuesST = new HashMap<>();
                        if (property.getLocalName().equals("IdentifiedObject.mRID")){
                            attributeValue=classRes.getLocalName().split("_",2)[1];
                        }else {
                            if (shaclContraintResult != null) {
                                if (shaclContraintResult.containsKey("minLength")) {
                                    valuesST.put("minLength", ((RDFNode) shaclContraintResult.get("minLength")).asLiteral().getInt());
                                }
                                if (shaclContraintResult.containsKey("maxLength")) {
                                    valuesST.put("maxLength", ((RDFNode) shaclContraintResult.get("maxLength")).asLiteral().getInt());
                                }
                                if (shaclContraintResult.containsKey("in")) {
                                    inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                }
                            } else {
                                valuesST.put("minLength", 1);
                                valuesST.put("maxLength", 10);
                            }
                            attributeValue = InstanceDataFactory.generateAttributeValueString(conform,valuesST,inValues);
                        }
                        break;

                    case "Boolean":
                        if (property.getLocalName().equals("ACDCTerminal.connected")){
                            attributeValue="true";
                        }else {
                            attributeValue = InstanceDataFactory.generateAttributeValueBoolean(conform);
                        }
                        if(!conform){
                            attributeValue="truee";
                        }
                        break;

                    case "Date":
                        long aDay = TimeUnit.DAYS.toMillis(1);
                        long now = new Date().getTime();
                        Date startInclusive = new Date(now + aDay * 2 ); // starts 2 days from now
                        Date endExclusive = new Date(now + aDay * 365 * 2); //ends 2 years from now
                        attributeValue = InstanceDataFactory.generateAttributeValueDate(conform, startInclusive, endExclusive);
                        break;

                    case "DateTime":
                        long aDay1 = TimeUnit.DAYS.toMillis(1);
                        long now1 = new Date().getTime();
                        Date startInclusive1 = new Date(now1 + aDay1 * 2 ); // starts 2 days from now
                        Date endExclusive1 = new Date(now1 + aDay1 * 365 * 2); //ends 2 years from now
                        attributeValue = InstanceDataFactory.generateAttributeValueDateTime(conform, startInclusive1, endExclusive1);
                        break;

                    case "Decimal":
                        attributeValue = InstanceDataFactory.generateAttributeValueDecimal(conform);
                        break;

                    case "Duration":
                        if (conform) {
                            attributeValue="P5Y2M10DT15H";
                        }else{
                            attributeValue= RandomStringUtils.randomAlphabetic(10);
                        }
                        break;

                    case "MonthDay":
                        long aDay2 = TimeUnit.DAYS.toMillis(1);
                        long now2 = new Date().getTime();
                        Date startInclusive2 = new Date(now2 + aDay2 * 2 ); // starts 2 days from now
                        Date endExclusive2 = new Date(now2 + aDay2 * 365 * 2); //ends 2 years from now
                        attributeValue = InstanceDataFactory.generateAttributeValueMonthDay(conform, startInclusive2, endExclusive2);
                        break;

                    case "Time":
                        long aDay3 = TimeUnit.DAYS.toMillis(1);
                        long now3 = new Date().getTime();
                        Date startInclusive3 = new Date(now3 + aDay3 * 2 ); // starts 2 days from now
                        Date endExclusive3 = new Date(now3 + aDay3 * 365 * 2); //ends 2 years from now
                        attributeValue = InstanceDataFactory.generateAttributeValueTime(conform, startInclusive3, endExclusive3);
                        break;

                    case "URI":
                    case "URL":
                    case "IRI":
                    case "StringIRI":
                        if (conform) {
                            if (shaclContraintResult != null) {
                                if (shaclContraintResult.containsKey("in")) {
                                    inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                    attributeValue=inValues.get(0).toString();
                                }else{
                                    attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                                }

                            }else {
                                attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                            }
                        }else{
                            attributeValue=RandomStringUtils.randomAlphabetic(10);
                        }
                        break;
                }
                break;
            }
            case "Compound": {
                //TODO compound at later stage. This is to do a good and bad compound when attributes are generated.
            }
            case "Enumeration": {
                Map<String, Integer> values = new HashMap<>();
                values.put("minInclusive", 0);
                if (shaclContraintResult != null) {
                    if (shaclContraintResult.containsKey("in")) {
                        List<RDFNode> objectTempIn = (List<RDFNode>) shaclContraintResult.get("in");
                        if (conform) {
                            values.put("maxInclusive", objectTempIn.size() - 1);
                            int randomInt = Integer.parseInt(InstanceDataFactory.generateAttributeValueInteger(true, values, inValues));
                            attributeValue = objectTempIn.get(randomInt).toString();
                        } else {
                            if (datatypeValuesEnum != null) {
                                boolean noValue = true;
                                //TODO - see if this below can be used somehow. For now the totally fake enum is fine here.
                                /*for (Object item : (ArrayList) datatypeValuesEnum) {
                                    if (!objectTempIn.contains(ResourceFactory.createResource(item.toString()))) {
                                        attributeValue = item.toString();
                                        noValue = false;
                                    }
                                }*/
                                if (noValue) {
                                    attributeValue = objectTempIn.get(0).toString() + "nonConform";
                                }
                            }
                        }
                    }
                } else {
                    if (datatypeValuesEnum != null) {
                        values.put("maxInclusive", ((ArrayList) datatypeValuesEnum).size() - 1);
                        int randomInt = Integer.parseInt(InstanceDataFactory.generateAttributeValueInteger(true, values, new ArrayList<>()));
                        if (conform) {
                            attributeValue = ((ArrayList) datatypeValuesEnum).get(randomInt).toString();
                        } else {
                            //TODO see if the non conform can be clever e.g. to select from other list;
                            attributeValue = ((ArrayList) datatypeValuesEnum).get(randomInt).toString() + "nonConform";
                        }
                    }
                }
                break;
            }
        }

        if (attributeValue!=null && datatypeValuesEnum==null) {
            statement = InstanceDataFactory.addGenerategAttributePlainLiteralToStatement(attributeValue, classRes, property);
        }else if (datatypeValuesEnum!=null){
            statement = InstanceDataFactory.addEnumerationToStatement(attributeValue, classRes, property);
        }else {
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Error] The class.attribute: "+property.getLocalName()+" did not get value.",true);
        }

        return statement;
    }

}

