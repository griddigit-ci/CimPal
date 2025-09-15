package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.*;
import java.util.*;

public class RDFConverter {
    public Model convertedModel;
    public Model modelInheritance;
    public RDFConvertOptions options;

    public RDFConverter(RDFConvertOptions options) {
        this.convertedModel = null;
        this.modelInheritance = null;
        this.options = options;
    }

    //RDF conversion
    public void convert() throws IOException {

        File sourceFile = options.getSourceFile();
        List<File> modelUnionFiles = options.getModelUnionFiles();
        List<File> modelUnionDetailedFiles = options.getModelUnionDetailedFiles();
        String sourceFormat = options.getSourceFormat();
        String xmlBase = options.getXmlBase();
        boolean modelUnionFlag = options.isModelUnionFlag();
        boolean inheritanceOnly = options.isInheritanceOnly();
        boolean inheritanceList = options.isInheritanceList();
        boolean inheritanceListConcrete = options.isInheritanceListConcrete();
        boolean addOwl = options.isAddOwl();
        boolean modelUnionFlagDetailed = options.isModelUnionFlagDetailed();
        boolean stripPrefixes = options.isStripPrefixes();
        boolean modelUnionFixPackage = options.isModelUnionFixPackage();

        Lang rdfSourceFormat = switch (sourceFormat) {
            case "RDF XML (.rdf or .xml)" -> Lang.RDFXML;
            case "RDF Turtle (.ttl)" -> Lang.TURTLE;
            case "JSON-LD (.jsonld)" -> Lang.JSONLD;
            default -> throw new IllegalStateException("Unexpected value: " + sourceFormat);
        };
        List<File> modelFiles = new LinkedList<File>();
        if (!modelUnionFlagDetailed) {
            if (!modelUnionFlag && sourceFile != null) {
                modelFiles.add(sourceFile);
            } else {
                modelFiles = modelUnionFiles;
            }
        }

        Model model;

        if (modelUnionFlagDetailed) {
            if (!modelUnionDetailedFiles.isEmpty())
                modelFiles.addAll(modelUnionDetailedFiles);

            model = ModelFactory.createDefaultModel();
            Model modelOrig = ModelFactory.createDefaultModel();
            Map<String, String> prefixMap = model.getNsPrefixMap();
            int count = 1;
            for (File modelFile : modelFiles) {
                Model modelPart = ModelFactory.createDefaultModel();
                InputStream inputStream = new FileInputStream(modelFile.toString());
                RDFDataMgr.read(modelPart, inputStream, xmlBase, rdfSourceFormat);
                prefixMap.putAll(modelPart.getNsPrefixMap());
                model.add(modelPart);
                if (count == 1) {
                    modelOrig = modelPart;
                }
                count = count + 1;
            }
            model.setNsPrefixes(prefixMap);

            List<Statement> stmtToDeleteClass = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory"), (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                if (stmt.getObject().asResource().getLocalName().equals("Package_LTDSnotDefined")) {
                    //delete all classes
                    List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    stmtToDeleteClass.addAll(stdelete);
                    //check if the class is an enumeration
                    if (model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")).hasNext()) {
                        for (Statement stmpProp : stdelete) {
                            List<Statement> stdeleteProp = model.listStatements(null, RDF.type, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp);
                            for (Statement stmpPropEn : stdeleteProp) {
                                List<Statement> stdeletePropEn = model.listStatements(stmpProp.getSubject(), null, (RDFNode) null).toList();
                                stmtToDeleteClass.addAll(stdeletePropEn);
                            }
                        }
                    } else {
                        //delete all attributes and associations with domain of the deleted classes
                        for (Statement stmpProp : stdelete) {
                            List<Statement> stdeleteProp = model.listStatements(null, RDFS.domain, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp);
                            for (Statement stmpProp1 : stdeleteProp) {
                                if (stmpProp1.getSubject().getLocalName().split("\\.", 2)[0].equals(stmpProp.getSubject().getLocalName())) {
                                    List<Statement> stdeleteProp1 = model.listStatements(stmpProp1.getSubject(), null, (RDFNode) null).toList();
                                    stmtToDeleteClass.addAll(stdeleteProp1);
                                }
                            }

                            //delete all attributes and associations with range of the deleted classes
                            List<Statement> stdeleteProp1 = model.listStatements(null, RDFS.range, stmpProp.getSubject()).toList();
                            stmtToDeleteClass.addAll(stdeleteProp1);
                            for (Statement stmpProp2 : stdeleteProp1) {
                                List<Statement> stdeleteProp2 = model.listStatements(stmpProp2.getSubject(), null, (RDFNode) null).toList();
                                stmtToDeleteClass.addAll(stdeleteProp2);
                            }
                        }
                    }
                }
            }

            for (StmtIterator i = model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("LTDSnotDefined", "en")); i.hasNext(); ) {
                Statement stmt = i.next();
                List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                stmtToDeleteClass.addAll(stdelete);
            }

            model.remove(stmtToDeleteClass);

            List<Statement> stmtToDeleteProperty = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype"), (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                if (stmt.getObject().toString().equals("LTDSnotDefined")) {
                    List<Statement> stdelete = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    stmtToDeleteProperty.addAll(stdelete);
                }
            }

            //delete double multiplicity
            for (StmtIterator k = model.listStatements(null, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity"), (RDFNode) null); k.hasNext(); ) {
                Statement stmt = k.next();
                List<Statement> multi = model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity"), (RDFNode) null).toList();
                if (multi.size() > 1) {
                    for (Statement stmtM : multi) {
                        if (modelOrig.contains(stmtM)) {
                            stmtToDeleteProperty.add(stmtM);
                        }
                    }
                }
            }

            model.remove(stmtToDeleteProperty);

        } else {
            // load all models
            model = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(modelFiles, xmlBase, rdfSourceFormat, false);
        }


        //in case only inheritance related structure should be converted
        if (inheritanceOnly) {
            var res =
                    eu.griddigit.cimpal.core.utils.ModelFactory.generateInheritanceModels(model,
                            inheritanceList,
                            inheritanceListConcrete);
            model = res.processedModel;
            modelInheritance = res.inheritanceModel;
        }

        List<Statement> stmttoadd = new LinkedList<>();
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        if (addOwl) {
            for (ResIterator i = model.listSubjectsWithProperty(RDF.type); i.hasNext(); ) {
                Resource resItem = i.next();
                //String className = resItem.getRequiredProperty(RDF.type).getObject().asResource().getLocalName();

                RDFNode obje = resItem.getRequiredProperty(RDF.type).getObject();
                if (obje.equals(RDFS.Class)) {
                    stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.Class));
                } else if (obje.equals(RDF.Property)) {
                    stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.ObjectProperty));
                }

                for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                    RDFNode resItemNodeDescr = k.next();
                    if (resItemNodeDescr.toString().equals("enum")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.NamedIndividual));
                        break;
                    }
                    if (resItemNodeDescr.toString().equals("CIMDatatype")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.DatatypeProperty));
                        break;
                    }
                    if (resItemNodeDescr.toString().equals("Primitive")) {
                        stmttoadd.add(ResourceFactory.createStatement(resItem, RDF.type, OWL2.DatatypeProperty));
                        break;
                    }
                }
            }
        }

        model.add(stmttoadd);

        if (modelUnionFlagDetailed) {
            //ensure one ontology class
            if (modelUnionDetailedFiles.size() >= 2) {
                List<Statement> stmtToDeleteOntology = new LinkedList<>();
                int maxstmt = 0;
                Resource maxstmpst = null;
                for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")); i.hasNext(); ) {
                    Statement stmt = i.next();
                    List<Statement> stdeletep = model.listStatements(stmt.getSubject(), null, (RDFNode) null).toList();
                    int maxstmt0 = stdeletep.size();
                    if (maxstmt0 >= maxstmt) {
                        maxstmt = maxstmt0;
                        maxstmpst = stmt.getSubject();
                    }
                    stmtToDeleteOntology.addAll(stdeletep);
                }
                model.remove(stmtToDeleteOntology);


                List<Statement> stmtToAddOntology = new LinkedList<>();
                for (Statement stmtadd : stmtToDeleteOntology) {
                    if (stmtadd.getSubject().equals(maxstmpst)) {
                        stmtToAddOntology.add(stmtadd);
                    }
                }
                model.add(stmtToAddOntology);
            }
        }

        //optimise prefixes, strip unused prefixes
        if (stripPrefixes) {
            Map<String, String> modelPrefMap = model.getNsPrefixMap();
            LinkedList<String> uniqueNamespacesList = new LinkedList<>();
            for (StmtIterator ns = model.listStatements(); ns.hasNext(); ) {
                Statement stmtNS = ns.next();
                if (!uniqueNamespacesList.contains(stmtNS.getSubject().getNameSpace())) {
                    uniqueNamespacesList.add(stmtNS.getSubject().getNameSpace());
                }
                if (!uniqueNamespacesList.contains(stmtNS.getPredicate().getNameSpace())) {
                    uniqueNamespacesList.add(stmtNS.getPredicate().getNameSpace());
                }
                if (stmtNS.getObject().isResource()) {
                    if (!uniqueNamespacesList.contains(stmtNS.getObject().asResource().getNameSpace())) {
                        uniqueNamespacesList.add(stmtNS.getObject().asResource().getNameSpace());
                    }
                }
            }
            LinkedList<Map.Entry<String, String>> entryToRemove = new LinkedList<>();
            for (Map.Entry<String, String> entry : modelPrefMap.entrySet()) {
                //String key = entry.getKey();
                String value = entry.getValue();

                // Check if either the key or value is present in uniqueNamespacesList
                if (!uniqueNamespacesList.contains(value)) {
                    entryToRemove.add(entry);
                }
            }
            for (Map.Entry<String, String> entryTR : entryToRemove) {
                model.removeNsPrefix(entryTR.getKey());
            }
        }

        if (modelUnionFixPackage) {
            //add package statements
            String packageName = "";
            String packageURI = "";
            for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")); i.hasNext(); ) {
                Statement stmt = i.next();
                for (StmtIterator k = model.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                    Statement stmtP = k.next();
                    if (stmtP.getPredicate().equals(DCAT.keyword)) {
                        packageName = stmtP.getObject().asLiteral().getString();
                        packageURI = stmtP.getSubject().getNameSpace();
                    }
                }
            }
            //delete existing packages
            List<Statement> stmtToDeleteOldPackage = new LinkedList<>();
            for (StmtIterator i = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")); i.hasNext(); ) {
                Statement stmt = i.next();
                for (StmtIterator k = model.listStatements(stmt.getSubject(), null, (RDFNode) null); k.hasNext(); ) {
                    Statement stmtP = k.next();
                    stmtToDeleteOldPackage.add(stmtP);
                }
            }
            model.remove(stmtToDeleteOldPackage);

            //add the new package
            Resource packageRes = ResourceFactory.createResource(packageURI + "Package_" + packageName + "Profile");
            model.add(ResourceFactory.createStatement(packageRes, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#ClassCategory")));
            model.add(ResourceFactory.createStatement(packageRes, RDFS.comment, ResourceFactory.createPlainLiteral("This is a package for the " + packageName + " profile.")));
            model.add(ResourceFactory.createStatement(packageRes, RDFS.label, ResourceFactory.createLangLiteral(packageName + "Profile", "en")));

            //replace cims:belongsToCategory
            List<Statement> stmtToAddPackage = new LinkedList<>();
            List<Statement> stmtToDeletePackage = new LinkedList<>();
            Property belongsToCategory = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory");
            for (StmtIterator i = model.listStatements(null, belongsToCategory, (RDFNode) null); i.hasNext(); ) {
                Statement stmt = i.next();
                stmtToDeletePackage.add(stmt);
                stmtToAddPackage.add(ResourceFactory.createStatement(stmt.getSubject(), belongsToCategory, ResourceFactory.createProperty(packageRes.toString())));
            }
            model.remove(stmtToDeletePackage);
            model.add(stmtToAddPackage);
        }

        this.convertedModel = model;

    }

    public void writeConvertedModel(OutputStream outputStream) throws IOException {
        if (convertedModel == null) {
            throw new IllegalStateException("Converted model is null. Please run convert() method first.");
        }

        String targetFormat = options.getTargetFormat();
        RDFFormat rdfFormat = options.getRdfFormat();
        String xmlBase = options.getXmlBase();
        String showXmlDeclaration = options.getShowXmlDeclaration();
        String showDoctypeDeclaration = options.getShowDoctypeDeclaration();
        String tab = options.getTabCharacter();
        String relativeURIs = options.getRelativeURIs();
        String convertInstanceData = options.getConvertInstanceData();
        String sortRDF = options.getSortRDF();
        String rdfSortOptions = options.getRdfSortOptions();

        switch (targetFormat) {
            case "RDF XML (.rdf or .xml)" -> {
                //register custom format
                CustomRDFFormat.RegisterCustomFormatWriters();
                String showXmlEncoding = "true"; //saveProperties.get("showXmlEncoding").toString();
                boolean putHeaderOnTop = true; //(boolean) saveProperties.get("putHeaderOnTop");
                String headerClassResource = null;
                if (convertedModel.listStatements(null, RDF.type, ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")).hasNext()) {
                    headerClassResource = "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"; //saveProperties.get("headerClassResource").toString();
                } else if (convertedModel.listStatements(null, RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
                    headerClassResource = "http://www.w3.org/2002/07/owl#Ontology";
                }

                boolean useAboutRules = false;//(boolean) saveProperties.get("useAboutRules");   //switch to trigger file chooser and adding the property
                boolean useEnumRules = false;//(boolean) saveProperties.get("useEnumRules");   //switch to trigger special treatment when Enum is reference
                Set<Resource> rdfAboutList = null; //(Set<Resource>) saveProperties.get("rdfAboutList");
                Set<Resource> rdfEnumList = null;//(Set<Resource>) saveProperties.get("rdfEnumList");
                try {
                    if (rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY || rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN) {
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("showXmlDeclaration", showXmlDeclaration);
                        properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                        properties.put("showXmlEncoding", showXmlEncoding); // works only with the custom format
                        //properties.put("blockRules", "daml:collection,parseTypeLiteralPropertyElt,"
                        //        +"parseTypeResourcePropertyElt,parseTypeCollectionPropertyElt"
                        //        +"sectionReification,sectionListExpand,idAttr,propertyAttr"); //???? not sure
                        if (putHeaderOnTop) {
                            properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});
                        }
                        properties.put("xmlbase", xmlBase);
                        properties.put("tab", tab);
                        properties.put("relativeURIs", relativeURIs);
                        properties.put("instanceData", convertInstanceData);
                        properties.put("sortRDF", sortRDF);
                        properties.put("sortRDFprefix", rdfSortOptions);
                        properties.put("showXmlBaseDeclaration", "true");

                        if (useAboutRules) {
                            properties.put("aboutRules", rdfAboutList);
                        }

                        if (useEnumRules) {
                            properties.put("enumRules", rdfEnumList);
                        }

                        // Put a properties object into the Context.
                        Context cxt = new Context();
                        cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                        RDFWriter.create()
                                .base(xmlBase)
                                .format(rdfFormat)
                                .context(cxt)
                                .source(convertedModel)
                                .output(outputStream);

                    } else {
                        try (outputStream) {
                            Map<String, Object> properties = new HashMap<>();
                            properties.put("showXmlDeclaration", showXmlDeclaration);
                            properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                            //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                            properties.put("xmlbase", xmlBase);
                            properties.put("tab", tab);
                            //properties.put("prettyTypes",new Resource[] {ResourceFactory.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel")});
                            properties.put("relativeURIs", relativeURIs);

                            // Put a properties object into the Context.
                            Context cxt = new Context();
                            cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                            RDFWriter.create()
                                    .base(xmlBase)
                                    .format(rdfFormat)
                                    .context(cxt)
                                    .source(convertedModel)
                                    .output(outputStream);
                        }
                    }
                } finally {
                    outputStream.flush();
                    outputStream.close();
                }
            }
            case "RDF Turtle (.ttl)" -> {
                try (outputStream) {
                    convertedModel.write(outputStream, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
                }

            }
            case "JSON-LD (.jsonld)" -> {
                try (outputStream) {
                    convertedModel.write(outputStream, RDFFormat.JSONLD.getLang().getLabel().toUpperCase(), xmlBase);
                }

            }
        }
    }

    public void writeInheritanceModel(OutputStream outputStream) throws IOException {
        boolean inheritanceList = options.isInheritanceList();
        if (!inheritanceList) {
            throw new IllegalStateException("Inheritance model is not available. Please run convert() method with inheritanceList option first.");
        }
        if (modelInheritance == null) {
            throw new IllegalStateException("Model inheritance is null. Please run convert() method first.");
        }

        String xmlBase = options.getXmlBase();

        try (outputStream) {
            modelInheritance.write(outputStream, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), xmlBase);
        }
    }
}
