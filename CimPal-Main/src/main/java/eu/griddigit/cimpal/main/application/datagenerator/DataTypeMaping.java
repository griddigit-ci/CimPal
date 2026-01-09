/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.Map;


public class DataTypeMaping {


    // adds a map property to an existing datatype map. Maps properties to xsd types
    public static Map<String, RDFDatatype> addDatatypeMapProperty(Map dataTypeMap, String propertyString, String datatype) {

            //map CIM datatypes to xsd datatypes
            switch (datatype) {
                case "http://www.w3.org/2001/XMLSchema#integer":
                case "http://www.w3.org/2001/XMLSchema#int":
                case "Integer":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDinteger);
                    break;
                case "http://www.w3.org/2001/XMLSchema#float":
                case "Float":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDfloat);
                    break;
                case "http://www.w3.org/2001/XMLSchema#string":
                case "String":
                case "StringFixedLanguage":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDstring);
                    break;
                case "http://www.w3.org/2001/XMLSchema#boolean":
                case "Boolean":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDboolean);
                    break;
                case "http://www.w3.org/2001/XMLSchema#date":
                case "Date":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdate);
                    break;
                case "http://www.w3.org/2001/XMLSchema#dateTime":
                case "DateTime":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdateTime);
                    break;
                case "http://www.w3.org/2001/XMLSchema#decimal":
                case "Decimal":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDdecimal);
                    break;
                case "http://www.w3.org/2001/XMLSchema#duration":
                case "Duration":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDduration);
                    break;
                case "http://www.w3.org/2001/XMLSchema#gMonthDay":
                case "MonthDay":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDgMonthDay);
                    break;
                case "http://www.w3.org/2001/XMLSchema#time":
                case "Time":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDtime);
                    break;
                case "http://www.w3.org/2001/XMLSchema#anyURI":
                case "URI":
                case "URL":
                case "IRI":
                case "StringIRI":
                    dataTypeMap.putIfAbsent(propertyString, XSDDatatype.XSDanyURI);
                    break;
            }
        return dataTypeMap;
    }


    //returns a structure that contains the attributes of a compound
    public static ArrayList<Object> getAttributesOfCompoundForMap(Resource resItem, Model model, ArrayList<Object> propertyStringDatatype, String rdfNs){





        for (ResIterator i=model.listResourcesWithProperty(RDFS.domain); i.hasNext();) {
            Resource resItemDomain = i.next();
            if (resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())){
                ArrayList<Object> compoundProperty = new ArrayList<>();
                int isAttr=0;
                if (!model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                    isAttr = 1;

                }

                if (isAttr==1) {


                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        Resource resItemNew = model.getResource(resItemNode.toString());
                        String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
                        String rdfType;
                        if (rdfTypeInit.length == 0) {
                            rdfType = rdfTypeInit[0];
                        } else {
                            rdfType = rdfTypeInit[1];
                        }

                        if (rdfType.equals("Class")) { // if it is a class
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Primitive")){

                                ((ArrayList) propertyStringDatatype.get(0)).add(resItemDomain.toString());
                                ((ArrayList) propertyStringDatatype.get(1)).add(resItemNode.asResource().getLocalName());

                            }

                            //If the datatypes is a Compound
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Compound")){
                                propertyStringDatatype = getAttributesOfCompoundForMap(resItemNew,model,propertyStringDatatype,rdfNs);


                            }
                        }
                    }
                }

            }
        }

        return propertyStringDatatype;

    }


}
