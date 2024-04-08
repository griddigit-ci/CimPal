
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package common.customWriter;

import org.apache.jena.rdf.model.*;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * similar to {@link CustomBasic}. Prints the plain result with the rdf:Description replaced
 *
 */
public class CustomBasicPretty extends CustomBasic {

    @Override
    protected void writeRDFStatements
            (Model model, Resource subject, PrintWriter writer )
    {
        if(performedPleasingObjects.contains(subject)) { return; }

        Statement typeStatement = getType(subject);
        StmtIterator sIter = model.listStatements( subject, null, (RDFNode) null );
        boolean isDescription = typeStatement == null;

        if(isDescription) {
            if (this.sortRDF.equals("true")) {
                writeDescriptionHeader(subject, writer);
                //get list of all triples of the rdf:type and these need to be sorted by object
                Set<Map.Entry<String, Property>> entries = sortRDFprepare(model, subject,this.sortRDFprefix);
                for (Map.Entry<String, Property> entry : entries) {
                    StmtIterator stmtIter = model.listStatements(subject, entry.getValue(), (RDFNode) null);
                    while (stmtIter.hasNext()) {
                        Statement nextStatement = stmtIter.nextStatement();
                            writePredicate(nextStatement, writer);
                    }
                }
                writeDescriptionTrailer(subject, writer);
            }else {
                writeDescriptionHeader(subject, writer);
                while (sIter.hasNext()) writePredicate(sIter.nextStatement(), writer);
                writeDescriptionTrailer(subject, writer);
            }
        }
        else {

            if (this.sortRDF.equals("true")){
                writePrettyDescriptionHeader(subject, typeStatement, writer);
                //get list of all triples of the rdf:type and these need to be sorted by object
                Set<Map.Entry<String, Property>> entries = sortRDFprepare(model, subject,this.sortRDFprefix);
                for (Map.Entry<String, Property> entry : entries) {
                    StmtIterator stmtIter = model.listStatements(subject,entry.getValue(),(RDFNode) null);
                    while (stmtIter.hasNext()) {
                        Statement nextStatement = stmtIter.nextStatement();
                        if (!typeStatement.equals(nextStatement)){    //skip type statement
                            writePredicate(nextStatement, writer);
                        }
                    }
                }
            }else {
                writePrettyDescriptionHeader(subject, typeStatement, writer);
                while (sIter.hasNext())
                {
                    Statement nextStatement = sIter.nextStatement();
                    if(!typeStatement.equals(nextStatement))    //skip type statement
                    {
                        writePredicate(nextStatement, writer);
                    }
                }
            }
            writePrettyDescriptionTrailer( subject, typeStatement, writer );
        }
    }

    static Set<Map.Entry<String, Property>> sortRDFprepare(Model model, Resource subject, String sortRDFprefix) {
        Set<Statement> listStatements = model.listStatements(subject, null, (RDFNode) null).toSet();

        Map<String, Property> listPredicateMap = new TreeMap<>();
        for (Statement stmt : listStatements) {
            if (sortRDFprefix.equals("true")) {
                listPredicateMap.put(model.getNsURIPrefix(stmt.getPredicate().getNameSpace()) + ":" + stmt.getPredicate().getLocalName(), stmt.getPredicate());
            }else{
                listPredicateMap.put(stmt.getPredicate().getLocalName(), stmt.getPredicate());
            }
        }
        return listPredicateMap.entrySet();
    }

    protected void writePrettyDescriptionHeader( Resource subject, Statement stmt, PrintWriter writer)
    {
        Resource r = stmt.getResource();
        writer.print( space + "<" + startElementTag(r.getNameSpace(), r.getLocalName()) + " " );
        writeResourceId( subject, writer );
        writer.println( ">" );
    }

    protected void writePrettyDescriptionTrailer( Resource subject, Statement stmt, PrintWriter writer )
    {
        Resource r = stmt.getResource();
        writer.println( space + "</" + endElementTag(r.getNameSpace(), r.getLocalName()) + ">" );
    }
}
