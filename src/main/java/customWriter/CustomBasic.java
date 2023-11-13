
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package customWriter;

import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.Util;
import org.apache.jena.shared.PropertyNotFoundException;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFSyntax;

import java.io.PrintWriter;
import java.util.*;

/**
 * This is similar to {@link org.apache.jena.rdfxml.xmloutput.impl.Basic} some parts are copied.
 *
 */
public class CustomBasic extends CustomBaseXMLWriter {
    protected String space;
    protected Resource types[] = new Resource[]{};
    protected Map<Resource, Set<Resource>> pleasingTypeMap = new HashMap<>();
    protected Set<Resource> performedPleasingObjects = new HashSet<>();
    protected Set<Resource> aboutRules = new HashSet<>();
    protected boolean useAboutRules = false;
    //protected boolean instanceData = false;
    //protected  boolean sortRDF;

    protected Set<Resource> enumRules = new HashSet<>();
    protected boolean useEnumRules = false;
    //String showXmlBaseDeclaration = null;



    @Override
    Resource[] setTypes(Resource[] propValue) {
        Resource[] result = types;
        types = propValue;
        return result;
    }

    @Override
    Set<Resource> setAboutRules(Set<Resource> aboutRules) {
        useAboutRules = true;
        Set<Resource> result = this.aboutRules;
        this.aboutRules = aboutRules;

        if(this.aboutRules == null) { useAboutRules = false; }

        return result;
    }

    @Override
    Set<Resource> setEnumRules(Set<Resource> enumRules) {
        useEnumRules = true;
        Set<Resource> result = this.enumRules;
        this.enumRules = enumRules;

        if(this.enumRules == null) { useEnumRules = false; }

        return result;
    }

    @Override protected void writeBody
            (Model model, PrintWriter pw, String base, boolean inclXMLBase )
    {
        pleasingTypeMap = createPleasingTypeMap(model);
        performedPleasingObjects = new HashSet<>();

        setSpaceFromTabCount();
        writeRDFHeader( model, pw );
        writeRDFStatements( model, pw );
        writeRDFTrailer( pw, base );
        pw.flush();
    }

    private void setSpaceFromTabCount()
    {
        space = "";
        for (int i=0; i < tabSize; i += 1) space += " ";
    }

    protected void writeSpace( PrintWriter writer )
    { writer.print( space ); }

    private void writeRDFHeader(Model model, PrintWriter writer)
    {
        String xmlns = xmlnsDecl();
        writer.print( "<" + rdfEl( "RDF" ) + xmlns );
        if (null != xmlBase && !xmlBase.isEmpty() && this.showXmlBaseDeclaration.equals("true"))
            writer.print( "\n  xml:base=" + substitutedAttribute( xmlBase ) );
        writer.println( " > " );
    }

    protected void writeRDFStatements( Model model, PrintWriter writer )
    {
        writePleasingRDFStatements(model, writer);
        if (this.sortRDF.equals("true")){
            //get list of all triples of the rdf:type and these need to be sorted by object
            Set<Statement> listStatements = model.listStatements(new SimpleSelector(null, RDF.type, (RDFNode) null)).toSet();
            Map<String,RDFNode> listObjectsMap = new TreeMap<>();
            for (Statement stmt : listStatements) {
                if (sortRDFprefix.equals("true")) {
                    listObjectsMap.put(model.getNsURIPrefix(stmt.getObject().asResource().getNameSpace()) + ":" +stmt.getObject().asResource().getLocalName(),stmt.getObject());
                }else{
                    listObjectsMap.put(stmt.getObject().asResource().getLocalName(),stmt.getObject());
                }

            }
            Set<Map.Entry<String,RDFNode> > entries
                    = listObjectsMap.entrySet();
            for (Map.Entry<String, RDFNode> entry : entries) {

                StmtIterator stmtIter = model.listStatements(new SimpleSelector(null, null, entry.getValue()));
                while (stmtIter.hasNext()) {
                    Statement nextStatement = stmtIter.nextStatement();
                    //writePredicate(nextStatement, writer);
                    writeRDFStatements(model, nextStatement.getSubject(), writer);
                }
                //writeRDFStatements(model, entry.getValue(), writer);



            }
        }else{
            ResIterator rIter = model.listSubjects();
            while (rIter.hasNext()) writeRDFStatements( model, rIter.nextResource(), writer );
        }

    }

    protected void writeRDFTrailer( PrintWriter writer, String base )
    { writer.println( "</" + rdfEl( "RDF" ) + ">" ); }

    protected void writePleasingRDFStatements(Model model, PrintWriter writer) {
        if(types == null) { return; }

        for (Resource type : types) {
            Set<Resource> bucket = pleasingTypeMap.get(type);
            if (bucket != null) {
                for (Resource r : bucket) {
                    writeRDFStatements(model, r, writer);
                    performedPleasingObjects.add(r);
                }
            }
        }
    }

    protected void writeRDFStatements
            (Model model, Resource subject, PrintWriter writer )
    {
        if(performedPleasingObjects.contains(subject)) { return; }

        writeDescriptionHeader(subject, writer);
        if (this.sortRDF.equals("true")){
            //get list of all triples of the rdf:type and these need to be sorted by object
            Set<Map.Entry<String, Property>> entries = CustomBasicPretty.sortRDFprepare(model, subject,this.sortRDFprefix);
            for (Map.Entry<String, Property> entry : entries) {
                StmtIterator stmtIter = model.listStatements(new SimpleSelector(subject,entry.getValue(),(RDFNode) null));
                while (stmtIter.hasNext()) {
                    Statement nextStatement = stmtIter.nextStatement();
                        writePredicate(nextStatement, writer);
                }
            }
        }else {
            StmtIterator sIter = model.listStatements(subject, null, (RDFNode) null);
            while (sIter.hasNext()) writePredicate(sIter.nextStatement(), writer);
        }
        writeDescriptionTrailer(subject, writer);
    }

    protected void writeDescriptionHeader( Resource subject, PrintWriter writer)
    {
        writer.print( space + "<" + rdfEl( "Description" ) + " " );
        writeResourceId( subject, writer );
        writer.println( ">" );
    }

    protected void writePredicate(Statement stmt, final PrintWriter writer)
    {
        final Property predicate = stmt.getPredicate();
        final RDFNode object = stmt.getObject();

        writer.print(space+space+
                "<"
                + startElementTag(
                predicate.getNameSpace(),
                predicate.getLocalName()));

        if (object instanceof Resource) {
            writer.print(" ");
            writeResourceReference(((Resource) object), writer);
            writer.println("/>");
        } else {
            writeLiteral((Literal) object, writer);
            writer.println(
                    "</"
                            + endElementTag(
                            predicate.getNameSpace(),
                            predicate.getLocalName())
                            + ">");
        }
    }

    @Override protected void unblockAll()
    { blockLiterals = false; }

    private boolean blockLiterals = false;

    @Override protected void blockRule( Resource r ) {
        if (r.equals( RDFSyntax.parseTypeLiteralPropertyElt )) {
            blockLiterals = true;
        } else
            logger.warn("Cannot block rule <"+r.getURI()+">");
    }

    protected void writeDescriptionTrailer( Resource subject, PrintWriter writer )
    { writer.println( space + "</" + rdfEl( "Description" ) + ">" ); }

    protected void writeResourceId( Resource r, PrintWriter writer )
    {
        if(useAboutRules)
        {
            writeAboutRuleResourceId(r, writer );
            return;
        }

        if (r.isAnon()) {
            writer.print(rdfAt("nodeID") + "=" + attributeQuoted(anonId(r)));
        } else {
            writer.print(
                    rdfAt("about")
                            + "="
                            + substitutedAttribute(relativize(r.getURI())));
        }
    }
    protected void writeAboutRuleResourceId( Resource r, PrintWriter writer )
    {
        Statement type = getType(r);

        if (r.isAnon()) {
            writer.print(rdfAt("nodeID") + "=" + attributeQuoted(anonId(r)));
        } else {
            boolean isAbout=false;
            String placeholder;
            String url;
            if (type!=null) {
                isAbout = aboutRules.contains(type.getObject());
                placeholder = isAbout ? "about" : "ID";
                url = relativize(r.getURI());
            }else {
                isAbout=true;
                placeholder = isAbout ? "about" : "ID";
                url = relativize(r.getURI());
            }

            if(!isAbout)
            {
                if (url.charAt(0) == '#') {
                    url = url.substring(1);//deletes the leading #
                }
            }
            if (instanceData.equals("true")){
                if (!url.contains("urn:uuid:") & url.startsWith("http")){
                    if (url.contains("#")) {
                        url = r.getLocalName();
                    }else{
                        url = r.toString();
                    }
                    if (isAbout){
                        url = "#"+r.getLocalName();
                    }
                }
            }

            writer.print(
                    rdfAt(placeholder)
                            + "="
                            + substitutedAttribute(url));
        }
    }

    protected void writeResourceReference( Resource r, PrintWriter writer )
    {

        if (r.isAnon()) {
            writer.print(rdfAt("nodeID") + "=" + attributeQuoted(anonId(r)));
        } else {
            if (useEnumRules && !enumRules.isEmpty()){// here this part was added by Chavdar to have complete uri for enum not relative
                boolean isEnum = enumRules.contains(r);

                if(isEnum){
                    writer.print(
                            rdfAt("resource")
                                    + "="
                                    + substitutedAttribute(r.getURI()));
                }else{
                    if (r.getURI().contains("delete")) {
                        writer.print(
                                rdfAt("resource")
                                        + "="
                                        + substitutedAttribute("#_"+r.getURI().split("_",2)[1]));
                    }else {
                        writer.print(
                                rdfAt("resource")
                                        + "="
                                        + substitutedAttribute(relativize(r.getURI())));
                    }
                }
            }else {
                if (r.getURI().contains("delete")) {
                    writer.print(
                            rdfAt("resource")
                                    + "=#"
                                    + substitutedAttribute("#_"+r.getURI().split("_",2)[1]));
                }else{
                writer.print(
                        rdfAt("resource")
                                + "="
                                + substitutedAttribute(relativize(r.getURI())));
                }
            }
        }
    }

    protected void writeLiteral( Literal l, PrintWriter writer ) {
        String lang = l.getLanguage();
        String form = l.getLexicalForm();
        if (Util.isLangString(l)) {
            writer.print(" xml:lang=" + attributeQuoted( lang ));
        } else if (l.isWellFormedXML() && !blockLiterals) {
            // RDF XML Literals inline.
            writer.print(" " + rdfAt("parseType") + "=" + attributeQuoted( "Literal" )+">");
            writer.print( form );
            return ;
        } else {
            // Datatype (if not xsd:string and RDF 1.1)
            String dt = l.getDatatypeURI();
            if ( ! Util.isSimpleString(l) )
                writer.print( " " + rdfAt( "datatype" ) + "=" + substitutedAttribute( dt ) );
        }
        // Content.
        writer.print(">");
        writer.print( Util.substituteEntitiesInElementContent( form ) );
    }

    /**
     * @return A statement that is suitable for a typed node construction or
     *         null.
     */
    protected Statement getType(Resource r) {
        Statement rslt;
        try {
            if (r instanceof Statement) {
                rslt = ((Statement) r).getStatementProperty(RDF.type);
                if (rslt == null || (!rslt.getObject().equals(RDF.Statement)))
                    throw new IllegalArgumentException("Statement type problem for resource "+ r.getURI());
            } else {
                rslt = r.getRequiredProperty(RDF.type);
            }
        } catch (PropertyNotFoundException rdfe) {
            if (r instanceof Statement)
                throw new IllegalArgumentException("Statement type problem for resource "+ r.getURI());
            rslt = null;
        }
        if (rslt == null || isOKType(rslt.getObject()) == -1)
            return null;

        return rslt;
    }

    /**
     * @param n
     *            The value of some rdf:type (precondition).
     * @return The split point or -1.
     */

    protected int isOKType(RDFNode n) {

        if (!(n instanceof Resource))
            return -1;
        if (((Resource) n).isAnon())
            return -1;
        // Only allow resources with namespace and fragment ID
        String uri = ((Resource) n).getURI();

        int split = Util.splitNamespaceXML(uri);
        if (split == 0 || split == uri.length())
            return -1;

        return split;
    }

    protected Map<Resource, Set<Resource>> createPleasingTypeMap(Model model) {
        Map<Resource, Set<Resource>> buckets = new HashMap<>();

        if(types == null || types.length == 0) { return buckets; }

        for (Resource type : types) {
            buckets.put(type, new HashSet<>());
        }

        ResIterator rs = model.listSubjects();
        try
        {
            while (rs.hasNext()) {
                Resource r = rs.nextResource();
                Statement s = getType(r);
                if (s != null) {
                    Set<Resource> bucket = buckets.get(s.getObject());
                    if (bucket != null) {
                        bucket.add(r);
                    }
                }
            }
        } finally {
            rs.close();
        }

        return buckets;
    }

}
