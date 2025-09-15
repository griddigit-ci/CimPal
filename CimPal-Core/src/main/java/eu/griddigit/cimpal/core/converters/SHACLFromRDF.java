package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.models.RDFtoSHACLOptions;
import eu.griddigit.cimpal.core.models.RdfsModelDefinition;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.enhanced.EnhGraph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.*;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;

import static org.topbraid.shacl.vocabulary.SH.path;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

public class SHACLFromRDF {
    private final RDFtoSHACLOptions options;

    private List<Model> shapeModels;
    private List<Model> shapeModelDTs;
    private List<Model> inheritanceModels;
    private List<ValidationReport> validationReports;
    private List<Statement> rdfsHeaderStatements;
    private Map<String, Map<String, RDFDatatype>> dataTypeMapFromShapesPerProfile;

    public SHACLFromRDF(RDFtoSHACLOptions options) {
        this.options = options;
        shapeModels = new ArrayList<>();
        shapeModelDTs = new ArrayList<>();
        inheritanceModels = new ArrayList<>();
        validationReports = new ArrayList<>();
        dataTypeMapFromShapesPerProfile = new HashMap<>();
    }

    public RDFtoSHACLOptions getOptions() {
        return options;
    }

    public List<Model> getShapeModels() {
        return shapeModels;
    }

    public List<Model> getShapeModelDTs() {
        return shapeModelDTs;
    }

    public List<Model> getInheritanceModels() {
        return inheritanceModels;
    }

    public List<ValidationReport> getValidationReports() {
        return validationReports;
    }

    public List<Statement> getRdfsHeaderStatements() {
        return rdfsHeaderStatements;
    }

    public Map<String, Map<String, RDFDatatype>> getDataTypeMapFromShapesPerProfile() {
        return dataTypeMapFromShapesPerProfile;
    }

    public void convert() throws IOException {

        //baseprofilesshaclglag = 0;
        //baseprofilesshaclglag2nd = 0;
        //baseprofilesshaclglag3rd = 0;
        //baseprofilesshaclignorens = 0;
        Map<String, Model> baseTier1Map = new HashMap<>();
        Map<String, Model> baseTier2Map = new HashMap<>();
        Map<String, Model> baseTier3Map = new HashMap<>();
        Map<String, String> baseTier1nsMap = new HashMap<>();
        Map<String, String> baseTier2nsMap = new HashMap<>();
        Map<String, String> baseTier3nsMap = new HashMap<>();
        if (options.isBaseprofilesshaclglag()) { // load base profiles if the checkbox is selected
            //baseprofilesshaclglag = 1;

            baseTier1Map = loadBaseModel(options.getBaseModelFiles1());
            baseTier1nsMap = getBaseModelNS(baseTier1Map.get("unionmodelbaseprofilesshacl"));

            if (options.isBaseprofilesshaclglag2nd()) {
                baseTier2Map = loadBaseModel(options.getBaseModelFiles2());
                baseTier2nsMap = getBaseModelNS(baseTier2Map.get("unionmodelbaseprofilesshacl"));

                if (baseTier2nsMap.get("cimPref").equals("cim16") || baseTier2nsMap.get("cimPref").equals("cim17") || baseTier2nsMap.get("cimPref").equals("cim18")) {
                    baseTier1Map.replace("unionmodelbaseprofilesshacl", baseTier1Map.get("unionmodelbaseprofilesshacl").add(baseTier2Map.get("unionmodelbaseprofilesshacl")));
                    baseTier1Map.replace("unionmodelbaseprofilesshaclinheritance", baseTier1Map.get("unionmodelbaseprofilesshaclinheritance").add(baseTier2Map.get("unionmodelbaseprofilesshaclinheritance")));
                    baseTier1Map.replace("unionmodelbaseprofilesshaclinheritanceonly", baseTier1Map.get("unionmodelbaseprofilesshaclinheritanceonly").add(baseTier2Map.get("unionmodelbaseprofilesshaclinheritanceonly")));
                }

                if (options.isBaseprofilesshaclglag3rd()) {
                    baseTier3Map = loadBaseModel(options.getBaseModelFiles3());
                    baseTier3nsMap = getBaseModelNS(baseTier3Map.get("unionmodelbaseprofilesshacl"));

                    if (baseTier3nsMap.get("cimPref").equals("cim16") || baseTier3nsMap.get("cimPref").equals("cim17") || baseTier3nsMap.get("cimPref").equals("cim18")) {
                        baseTier1Map.replace("unionmodelbaseprofilesshacl", baseTier1Map.get("unionmodelbaseprofilesshacl").add(baseTier3Map.get("unionmodelbaseprofilesshacl")));
                        baseTier1Map.replace("unionmodelbaseprofilesshaclinheritance", baseTier1Map.get("unionmodelbaseprofilesshaclinheritance").add(baseTier3Map.get("unionmodelbaseprofilesshaclinheritance")));
                        baseTier1Map.replace("unionmodelbaseprofilesshaclinheritanceonly", baseTier1Map.get("unionmodelbaseprofilesshaclinheritanceonly").add(baseTier3Map.get("unionmodelbaseprofilesshaclinheritanceonly")));
                    }
                }
            }
        }

        int m = 0;
        for (Model model : options.getRdfsModels()) {
            //here the preparation starts
            Map<String, RDFDatatype> dataTypeMapFromShapes = new HashMap<>();

            //Model model = (Model) this.models.get(m);
            String rdfNs = options.getCimsNamespace();
            String rdfCase = "";
            RDFtoSHACLOptions.RdfsFormatShapes rdfsFormatShapes = options.getRdfsFormatShapes();
            if (rdfsFormatShapes == RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2019) {
                rdfCase = "RDFS2019";
            } else if (rdfsFormatShapes == RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2020) {
                rdfCase = "RDFS2020";
                //this option is adding header to the SHACL that is generated.
            } else if (rdfsFormatShapes == RDFtoSHACLOptions.RdfsFormatShapes.CIMTOOL_MERGED_OWL) {
                rdfCase = "CIMToolOWL";
            }

            if (!options.isBaseprofilesshaclglag()) {//when there is no base the same model is added as base so that the other logic works
                var inheritanceResult = eu.griddigit.cimpal.core.utils.ModelFactory.generateInheritanceModels(model, true, true);

                baseTier1Map.put("unionmodelbaseprofilesshacl", model);
                baseTier1Map.put("unionmodelbaseprofilesshaclinheritance", inheritanceResult.processedModel);
                baseTier1Map.put("unionmodelbaseprofilesshaclinheritanceonly", inheritanceResult.inheritanceModel); // this contains the inheritance of the classes under OWL2.members

                baseTier1nsMap = getBaseModelNS(baseTier1Map.get("unionmodelbaseprofilesshacl"));
            }


            switch (rdfCase) {

                case "RDFS2020" -> {

                    //Extract RDFS header information
                    if (model.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
                        Resource hearerTypeRes = model.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).next();
                        rdfsHeaderStatements = model.listStatements(hearerTypeRes, null, (Property) null).toList();
                    }

                    //ArrayList<Object> shapeData = constructShapeData(model, rdfNs, concreteNs);

                    //shapeDatas.add(shapeData); // shapeDatas stores the shaclData for all profiles

                    //here the preparation ends
                    List<RdfsModelDefinition> rdfsModelDefinition = options.getRdfsModelDefinitions();

                    String nsPrefixprofile = rdfsModelDefinition.get(m).getNsPrefix(); // ((ArrayList) this.modelsNames.get(m)).get(1).toString(); // this is the prefix of the the profile

                    String nsURIprofile = rdfsModelDefinition.get(m).getNsUri(); //((ArrayList) this.modelsNames.get(m)).get(2).toString(); //this the namespace of the the profile

                    String baseURI = rdfsModelDefinition.get(m).getBaseUri();
                    //}
                    String owlImport = rdfsModelDefinition.get(m).getOwlImport();
                    //generate the shape model
                    //Model shapeModel = createShapesModelFromProfile(model, nsPrefixprofile, nsURIprofile, shapeData);
                    Model shapeModel = createShapesModelFromRDFS(model, nsPrefixprofile, nsURIprofile, baseTier1Map, baseTier2Map, baseTier3Map,
                            baseTier1nsMap, baseTier2nsMap, baseTier3nsMap);

                    shapeModels.add(shapeModel);
                    //add the owl:imports
                    shapeModel = addOWLimports(shapeModel, baseURI, owlImport);

                    //add header
                    if (model.listSubjectsWithProperty(RDF.type, ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()) {
                        shapeModel = addSHACLheader(shapeModel, baseURI);
                    }

                    if (options.isBaseprofilesshaclglag()) {
                        shapeModel.setNsPrefix(baseTier1nsMap.get("cimPref"), baseTier1nsMap.get("cimURI"));
                    }

                    if (options.isBaseprofilesshaclglag2nd()) {
                        shapeModel.setNsPrefix(baseTier2nsMap.get("cimPref"), baseTier2nsMap.get("cimURI"));
                    }

                    if (options.isBaseprofilesshaclglag3rd()) {
                        shapeModel.setNsPrefix(baseTier3nsMap.get("cimPref"), baseTier3nsMap.get("cimURI"));
                    }

                    // shapeModels.add(shapeModel); todo if needed store the shape models
                    // shapeModelsNames.add(RDFSmodelsNames.get(m));
                    //optimise prefixes, strip unused prefixes
                    //if (stripPrefixes){
                    Map<String, String> modelPrefMap = shapeModel.getNsPrefixMap();
                    LinkedList<String> uniqueNamespacesList = new LinkedList<>();
                    for (StmtIterator ns = shapeModel.listStatements(); ns.hasNext(); ) {
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
                        shapeModel.removeNsPrefix(entryTR.getKey());
                    }


                    // Create a new HashMap to store the unique key-value pairs
                    HashMap<String, String> uniqueMap = new HashMap<>();

                    // Create a Set to track unique values
                    Set<String> uniqueValues = new HashSet<>();

                    //Iterate through the original HashMap
                    Map<String, String> origPrefMap = shapeModel.getNsPrefixMap();
                    for (Map.Entry<String, String> entry : origPrefMap.entrySet()) {
                        if (uniqueValues.add(entry.getValue())) {
                            // If the value was added to the set, it means it's unique
                            uniqueMap.put(entry.getKey(), entry.getValue());
                        }
                    }
                    //TODO check if there is cim just to avoid that cim was deleted instead of cim16,cim17 or cim18 in cases where the namespace was the same
                    shapeModel.clearNsPrefixMap();
                    shapeModel.setNsPrefixes(uniqueMap);
                    //}


                    if (options.isSplitDatatypes()) { // need to split the datatypes in a separate ttl
                        //
                        Model shapeModelDT = ModelFactory.createModelForGraph(GraphMemFactory.createDefaultGraph());
                        shapeModelDTs.add(shapeModelDT);

                        shapeModelDT.setNsPrefixes(shapeModel.getNsPrefixMap());
                        List<Statement> datatypeStatements = new LinkedList<>();
                        List<Statement> datatypeStatementsNS = new LinkedList<>();
                        for (StmtIterator stmt = shapeModel.listStatements(null, RDF.type, SH.PropertyShape); stmt.hasNext(); ) {
                            Statement stmtDT = stmt.next();
//                            if (stmtDT.getSubject().getLocalName().contains("name-datatype")){
//                                int l = 1;
//                            }
                            if (stmtDT.getSubject().getLocalName().endsWith("-datatype")) {
                                datatypeStatements.addAll(shapeModel.listStatements(stmtDT.getSubject(), null, (RDFNode) null).toList());
                                //get the NodeShape but only the property that refer to this property shape
                                List<Resource> nodeShapeList = shapeModel.listSubjectsWithProperty(SH.property, stmtDT.getSubject()).toList();
                                for (Resource nodeShape : nodeShapeList) {
                                    for (StmtIterator stmtNS = shapeModel.listStatements(nodeShape, null, (RDFNode) null); stmtNS.hasNext(); ) {
                                        Statement stmtNSDT = stmtNS.next();
                                        if (!stmtNSDT.getPredicate().getLocalName().equals("property")) {
                                            datatypeStatementsNS.add(stmtNSDT);
                                        }
                                    }
                                    datatypeStatements.add(shapeModel.listStatements(nodeShape, SH.property, stmtDT.getSubject()).next());
                                }
                                //get the group
                                Resource datatypeGroup = shapeModel.getRequiredProperty(stmtDT.getSubject(), SH.group).getObject().asResource();
                                if (!datatypeStatements.contains(ResourceFactory.createStatement(datatypeGroup, RDF.type, SH.PropertyGroup))) {
                                    datatypeStatements.addAll(shapeModel.listStatements(datatypeGroup, null, (RDFNode) null).toList());
                                }
                            }
                        }

                        shapeModelDT.add(datatypeStatementsNS);
                        shapeModelDT.add(datatypeStatements);
                        shapeModel.remove(datatypeStatements);


                    }

                    //this is used for the printing of the complete map in option "All profiles in one map"
                    // todo store datatypes to make the map for all profiles
//                    for (String key : dataTypeMapFromShapes.keySet()) {
//                        dataTypeMapFromShapesPerProfile.putIfAbsent(key, dataTypeMapFromShapes.get(key));
//                    }
                    //saves the datatypes map .properties file for each profile. The base name is the same as the shacl file name given by the user

                    // todo make a function to save the datatype map
//                    if (options.isPerProfile()) {
//                        Properties properties = new Properties();
//
//                        for (String key : dataTypeMapFromShapes.keySet()) {
//                            properties.put(key, dataTypeMapFromShapes.get(key).toString());
//                        }
//                        String fileName = FilenameUtils.getBaseName(String.valueOf(savedFile));
//                        properties.store(new FileOutputStream(savedFile.getParent() + "\\" + fileName + ".properties"), null);
//
//                    }

                    //if (baseTier1Map.get("unionmodelbaseprofilesshaclinheritance") == null && rdfsToShaclGuiMapBool.get("exportInheritTree")) {
                    if (options.isExportInheritTree()) {
                        Model modelInh = eu.griddigit.cimpal.core.utils.ModelFactory.generateInheritanceModels(model, true, true).processedModel;
                        inheritanceModels.add(modelInh);

                        //leave only subclassof and property of
                        List<Statement> stmtToRemove = new LinkedList<>();
                        for (ResIterator it = modelInh.listSubjects(); it.hasNext(); ) {
                            Resource res1 = it.next();
//                            if (res1.getLocalName().contains("TextDiagramObject")){
//                                int st = 1;
//                            }
                            if (!modelInh.listStatements(null, RDFS.subClassOf, res1).hasNext() && !modelInh.listStatements(null, RDFS.subPropertyOf, res1).hasNext() && !modelInh.listStatements(res1, RDFS.subClassOf, (RDFNode) null).hasNext() && !modelInh.listStatements(res1, RDFS.subPropertyOf, (RDFNode) null).hasNext()) {
                                stmtToRemove.addAll(modelInh.listStatements(res1, null, (RDFNode) null).toList());
                            }
                        }
                        modelInh.remove(stmtToRemove);
                    }

                }

            }
            m = m + 1;
        }


//        if (options.isExportInheritTree() && baseTier1Map.get("unionmodelbaseprofilesshaclinheritance") != null) {
//            Model inheritanceModel = baseTier1Map.get("unionmodelbaseprofilesshaclinheritance");
//            inheritanceModels.add(inheritanceModel);
//        }


    }

    public void saveShapeModel(Path outputDir) throws IOException {
        if (!shapeModels.isEmpty()) {
            String ext = ".ttl"; // default
            if (options.getShaclOutputFormat().equals(RDFtoSHACLOptions.SerializationFormat.RDFXML)) {
                ext = ".rdf";
            }
            for (Model shapeModel : shapeModels) {
                Path savePath = outputDir.resolve(options.getRdfsModelDefinitions().get(shapeModels.indexOf(shapeModel)).getModelName() + ext);
                try (OutputStream outputStream = new FileOutputStream(savePath.toFile(), true)) {
                    saveShapeModel(outputStream, shapeModel, options.getRdfsModelDefinitions().get(shapeModels.indexOf(shapeModel)).getBaseUri());
                }
            }
        } else {
            throw new NullPointerException("There are no shape model to save. Please run the convert method first.");
        }
    }

    public void saveShapeModelDT(Path outputDir) throws IOException {
        if (!shapeModelDTs.isEmpty()) {
            String ext = ".ttl"; // default
            if (options.getShaclOutputFormat().equals(RDFtoSHACLOptions.SerializationFormat.RDFXML)) {
                ext = ".rdf";
            }
            for (Model shapeModelDT : shapeModelDTs) {
                Path savePath = outputDir.resolve("datatype-" + options.getRdfsModelDefinitions().get(shapeModelDTs.indexOf(shapeModelDT)).getModelName() + ext);
                try (OutputStream outputStream = new FileOutputStream(savePath.toFile(), true)) {
                    saveShapeModel(outputStream, shapeModelDT, options.getRdfsModelDefinitions().get(shapeModelDTs.indexOf(shapeModelDT)).getBaseUri());
                }
            }
        } else {
            throw new NullPointerException("There are no datatype shape model to save. The option to split datatypes was not selected during conversion.");
        }
    }

    public void saveInheritanceModel(Path outputDir) throws IOException {
        if (!inheritanceModels.isEmpty()) {
            for (Model inheritanceModel : inheritanceModels) {
                Path savePath = outputDir.resolve("inheritance-" + options.getRdfsModelDefinitions().get(inheritanceModels.indexOf(inheritanceModel)).getModelName() + ".ttl");
                try (OutputStream outputStream = new FileOutputStream(savePath.toFile(), true)) {
                    saveShapeModel(outputStream, inheritanceModel, "");
                }
            }
        } else {
            throw new NullPointerException("There are no inheritance model to save. Please ensure that the option to export the inheritance tree was selected during conversion and that the base profile contains inheritance information.");
        }
    }

    public void saveProfilesMap(File outputFile) throws IOException {
        if (outputFile != null) {
            if (dataTypeMapFromShapesPerProfile.isEmpty()) {
                throw new NullPointerException("The datatype map is empty.");
            }
            Properties properties = new Properties();

            for (String key : dataTypeMapFromShapesPerProfile.keySet()) {
                properties.put(key, dataTypeMapFromShapesPerProfile.get(key).toString());
            }
            properties.store(new FileOutputStream(outputFile.toString()), null);
        } else {
            throw new NullPointerException("Output file not exists.");
        }
    }

    public void validateShapeModels() {
        Model shaclRefModel = eu.griddigit.cimpal.core.utils.ModelFactory.LoadSHACLSHACL();
        for (Model shapeModel : shapeModels) {
            ValidationReport report = ShaclValidator.get().validate(shaclRefModel.getGraph(), shapeModel.getGraph());
            validationReports.add(report);
        }
    }

    private void saveShapeModel(OutputStream outputStream, Model shapeModel, String baseURI) {
        switch (options.getShaclOutputFormat()) {
            case RDFXML -> {
                Map<String, Object> properties = new HashMap<>();
                properties.put("showXmlDeclaration", "true");
                properties.put("showDoctypeDeclaration", "false");
                //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                properties.put("xmlbase", baseURI);
                properties.put("tab", "2");
                properties.put("relativeURIs", "same-document");
                properties.put("prettyTypes", new Resource[]{OWL2.Ontology});
                //properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});


                // Put a property object into the Context.
                Context cxt = new Context();
                cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                RDFWriter.create()
                        .base(baseURI)
                        .format(RDFFormat.RDFXML_PRETTY) //.RDFXML_PLAIN
                        .context(cxt)
                        .source(shapeModel)
                        .output(outputStream);
            }
            case TURTLE -> {
                RDFWriter.create()
                        .base(baseURI)
                        .set(RIOT.symTurtleOmitBase, false)
                        .set(RIOT.symTurtleIndentStyle, "wide")
                        .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                        .lang(Lang.TURTLE)
                        .source(shapeModel)
                        .output(outputStream);
            }
            case null, default -> {
                throw new IllegalStateException("Unexpected value: " + options.getShaclOutputFormat());
            }
        }
    }

    private Map<String, Model> loadBaseModel(List<File> basefiles) throws FileNotFoundException {

        Map<String, Model> baseTierMap = new HashMap<>();

        if (basefiles != null) {
            Model basemodel = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoad(basefiles, "", Lang.RDFXML, true);
            var inheritanceResult = eu.griddigit.cimpal.core.utils.ModelFactory.generateInheritanceModels(basemodel, true, true);
            baseTierMap.put("unionmodelbaseprofilesshacl", basemodel);
            baseTierMap.put("unionmodelbaseprofilesshaclinheritance", inheritanceResult.processedModel);
            baseTierMap.put("unionmodelbaseprofilesshaclinheritanceonly", inheritanceResult.inheritanceModel); // this contains the inheritance of the classes under OWL2.members

        }

        return baseTierMap;
    }

    private Map<String, String> getBaseModelNS(Model model) {

        Map<String, String> baseTierMapNS = new HashMap<>();

        String uri = "";
        String pref = "";
        if (model.getNsPrefixURI("cim16") != null) {
            uri = model.getNsPrefixURI("cim16");
            pref = "cim16";
        } else if (model.getNsPrefixURI("cim17") != null) {
            uri = model.getNsPrefixURI("cim17");
            pref = "cim17";
        } else if (model.getNsPrefixURI("cim18") != null) {
            uri = model.getNsPrefixURI("cim18");
            pref = "cim18";
        }

        baseTierMapNS.put("cimURI", uri);
        baseTierMapNS.put("cimPref", pref);

        return baseTierMapNS;
    }

    private Model createShapesModelFromRDFS(Model model, String nsPrefixprofile, String nsURIprofile, Map<String, Model> baseTier1Map, Map<String, Model> baseTier2Map, Map<String, Model> baseTier3Map,
                                            Map<String, String> baseTier1nsMap, Map<String, String> baseTier2nsMap, Map<String, String> baseTier3nsMap) {


        RDFNode literalNO = ResourceFactory.createPlainLiteral("No");
        RDFNode literalYes = ResourceFactory.createPlainLiteral("Yes");
        Property assocUsed = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed");
        Property multiplicity = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity");
        Property dataType = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType");
        Property inverseRoleName = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName");
        //initial setup of the shape model
        //creates shape model. This is per profile.
        Model shapeModel = ModelFactory.createModelForGraph(GraphMemFactory.createDefaultGraph());
        shapeModel.setNsPrefix("rdf", RDF.getURI());
        shapeModel.setNsPrefix("rdfs", RDFS.getURI());
        shapeModel.setNsPrefix("owl", OWL.getURI());
        shapeModel.setNsPrefix("xsd", XSD.getURI());
        shapeModel.setNsPrefix("dcat", DCAT.getURI());
        shapeModel.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        shapeModel.setNsPrefix("dm", "http://iec.ch/TC57/61970-552/DifferenceModel/1#");
        shapeModel.setNsPrefixes(model.getNsPrefixMap());
        if (options.isBaseprofilesshaclglag()) {
            shapeModel.setNsPrefixes(baseTier1Map.get("unionmodelbaseprofilesshacl").getNsPrefixMap());
        }
        //add the namespace of the profile
        shapeModel.setNsPrefix(nsPrefixprofile, nsURIprofile);
        //add the additional two namespaces
        shapeModel.setNsPrefix("sh", SH.NS);
        shapeModel.setNsPrefix("dash", DASH.NS);
        shapeModel.setNsPrefix("dcterms", DCTerms.NS);

        shapeModel.setNsPrefix(options.getiOprefix(), options.getiOuri()); // the uri for the identified object related shapes

        //adding PropertyGroup-s
        String localNameGroup = "CardinalityGroup";
        ArrayList<Object> groupFeatures = new ArrayList<>();
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */
        for (int i = 0; i < 4; i++) {
            groupFeatures.add("");
        }
        groupFeatures.set(0, "Cardinality");
        groupFeatures.set(1, "This group of validation rules relate to cardinality validation of properties (attributes and associations).");
        groupFeatures.set(2, "Cardinality");
        groupFeatures.set(3, 0);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for IdentifiedObject
        groupFeatures.set(0, "CardinalityIO");
        groupFeatures.set(1, "This group of validation rules relate to cardinality validation of properties (attributes and associations).");
        groupFeatures.set(2, "CardinalityIO");
        groupFeatures.set(3, 0);

        shapeModel = addPropertyGroup(shapeModel, options.getiOuri(), localNameGroup, groupFeatures);
        //for Datatypes group
        localNameGroup = "DatatypesGroup";
        groupFeatures.set(0, "Datatypes");
        groupFeatures.set(1, "This group of validation rules relate to validation of datatypes.");
        groupFeatures.set(2, "Datatypes");
        groupFeatures.set(3, 1);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for Inverse associations group
        localNameGroup = "InverseAssociationsGroup";
        groupFeatures.set(0, "InverseAssociations");
        groupFeatures.set(1, "This group of validation rules relate to validation of inverse associations presence.");
        groupFeatures.set(2, "InverseAssociations");
        groupFeatures.set(3, 1);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for Profile Classes group
        localNameGroup = "ProfileClassesGroup";
        groupFeatures.set(0, "ProfileClasses");
        groupFeatures.set(1, "This group of validation rules relate to validation of Profile Classes.");
        groupFeatures.set(2, "ProfileClasses");
        groupFeatures.set(3, 1);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for IdentifiedObject
        localNameGroup = "DatatypesGroupIO";
        groupFeatures.set(0, "DatatypesIO");
        groupFeatures.set(1, "This group of validation rules relate to validation of datatypes.");
        groupFeatures.set(2, "DatatypesIO");
        groupFeatures.set(3, 1);

        shapeModel = addPropertyGroup(shapeModel, options.getiOuri(), localNameGroup, groupFeatures);

        //for Associations group
        localNameGroup = "AssociationsGroup";
        groupFeatures.set(0, "Associations");
        groupFeatures.set(1, "This group of validation rules relate to validation of target classes of associations.");
        groupFeatures.set(2, "Associations");
        groupFeatures.set(3, 2);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for Profile Properties group
        localNameGroup = "ProfilePropertiesGroup";
        groupFeatures.set(0, "ProfileProperties");
        groupFeatures.set(1, "This group of validation rules relate to validation if a property is part of a Profile.");
        groupFeatures.set(2, "ProfileProperties");
        groupFeatures.set(3, 1);

        shapeModel = addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        // Adding node shape and property shape to check if we have additional classes that are not defined in the profile
        shapeModel = addNodeShapeProfileClass(shapeModel, nsURIprofile, "AllowedClasses-node", RDF.type.getURI());

        //create the property shape
        RDFNode o = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
        Resource nodeShapeResourceClass = shapeModel.getResource(nsURIprofile + "AllowedClasses-node");
        nodeShapeResourceClass.addProperty(SH.property, o);

        //adding the properties for the PropertyShape
        Resource r = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
        r.addProperty(RDF.type, SH.PropertyShape);
        r.addProperty(SH.name, "ClassNotInProfile");
        r.addProperty(SH.description, "Checks if the dataset contains classes which are not defined in the profile to which this dataset conforms to.");
        RDFNode o8 = shapeModel.createResource(SH.NS + "Info");
        r.addProperty(SH.severity, o8);
        r.addProperty(SH.message, "This class is not part of the profile to which this dataset conforms to.");
        RDFNode o5 = shapeModel.createResource(RDF.type.getURI());
        r.addProperty(path, o5);
        RDFNode o1o = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
        r.addProperty(SH.order, o1o);
        RDFNode o1g = shapeModel.createResource(nsURIprofile + "ProfileClassesGroup");
        r.addProperty(SH.group, o1g);


        //need to get all concrete classes in the profile plus concrete classes in base profiles of abstract classes in the profile
        //situations
        //concrete class - include all and check for other concrete in base
        //abstract class with Description stereotype which has attributes or associations with No side direction - include all concrete from base (the SSI profile case)
        //abstract class without Description stereotype which has associations with No side direction - include all concrete from base

        //sh.in
        LinkedList<RDFNode> enumClass = new LinkedList<>();
        //String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        //RDFNode concreteNode = ResourceFactory.createProperty(concreteNs,"concrete");
        //RDFNode descriptionStereotype = ResourceFactory.createPlainLiteral("Description");
        //Property cimsStereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#","stereotype");

        for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDFS.Class); i.hasNext(); ) {
            Resource resItem = i.next();
            if (classIsNotEnumOrDatatype(resItem, model)) {
                //check if the class is concrete
                if (classIsConcrete(resItem, model)) {
                    if (!enumClass.contains(shapeModel.createResource(resItem.getURI()))) {
                        enumClass.add(shapeModel.createResource(resItem.getURI()));
                    }
                    //check for concrete subclasses in the base profile and add them to the enumClass

                    LinkedList<Resource> concreteClassesBase = getConcreteSubclassesFromBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                    for (Resource res : concreteClassesBase) {
                        if (!enumClass.contains(shapeModel.createResource(res.getURI()))) {
                            enumClass.add(shapeModel.createResource(res.getURI()));
                        }
                    }
                } else { // if abstract
                    // check if the abstract class has description stereotype
                    if (classIsDescription(resItem, model)) {
                        //check if the class has attributes or associations with No side direction
                        //check for concrete subclasses in the base profile and add them to the enumClass
                        if (classHasAttribute(resItem, model) || classIsRangeForInverseAssociationEnd(resItem, model)) {
                            LinkedList<Resource> concreteClassesBase = getConcreteSubclassesFromBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                            for (Resource res : concreteClassesBase) {
                                if (!enumClass.contains(shapeModel.createResource(res.getURI()))) {
                                    enumClass.add(shapeModel.createResource(res.getURI()));
                                }
                            }
                        }
                    } else { // it does not have description stereotype
                        //check if the class has associations with No side direction
                        //check for concrete subclasses in the base profile and add them to the enumClass
                        if (classIsRangeForInverseAssociationEnd(resItem, model)) {
                            LinkedList<Resource> concreteClassesBase = getConcreteSubclassesFromBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                            for (Resource res : concreteClassesBase) {
                                if (!enumClass.contains(shapeModel.createResource(res.getURI()))) {
                                    enumClass.add(shapeModel.createResource(res.getURI()));
                                }
                            }
                        }
                    }
                }
            }
        }

        // add FullModel, DifferenceModel and Dataset
        enumClass.add(shapeModel.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"));
        enumClass.add(shapeModel.createResource("http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel"));
        enumClass.add(shapeModel.createResource(DCAT.Dataset.toString()));

        RDFList enumRDFlist = shapeModel.createList(enumClass.iterator());
        r.addProperty(SH.in, enumRDFlist);


        // Add shapes for counting classes
        if (options.isShaclFlagCount()) {
            //for Profile Classes Count group
            localNameGroup = "ClassesCountGroup";
            groupFeatures.set(0, "ClassesCount");
            groupFeatures.set(1, "This group of validation rules relate to count of classes.");
            groupFeatures.set(2, "ClassesCount");
            groupFeatures.set(3, 1);

            String commonNSuri = nsURIprofile;

            if (!options.isShaclFlagCountDefaultURI()) {
                commonNSuri = options.getShaclCommonURI();
                shapeModel.setNsPrefix(options.getShaclCommonPref(), commonNSuri);
            }
            shapeModel = addPropertyGroup(shapeModel, commonNSuri, localNameGroup, groupFeatures);

            //add NodeShape
            shapeModel = addNodeShapeProfileClassCount(shapeModel, commonNSuri, "ClassCount-node", commonNSuri + "ClassCount");

            //create the property shape
            RDFNode pc = shapeModel.createResource(commonNSuri + "ClassCount-property");
            Resource nodeShapeResourceClasspc = shapeModel.getResource(commonNSuri + "ClassCount-node");
            nodeShapeResourceClasspc.addProperty(SH.property, pc);

            //add PropertyShape
            Resource rc = shapeModel.createResource(commonNSuri + "ClassCount-property");
            rc.addProperty(RDF.type, SH.PropertyShape);
            rc.addProperty(SH.name, "ClassCount");
            rc.addProperty(SH.description, "Counts instance of classes present in the data graph.");
            RDFNode o8pc = shapeModel.createResource(SH.NS + "Info");
            rc.addProperty(SH.severity, o8pc);
            RDFNode o5pc = shapeModel.createResource(RDF.type.getURI());
            rc.addProperty(path, o5pc);
            RDFNode o1opc = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
            rc.addProperty(SH.order, o1opc);
            RDFNode o1gpc = shapeModel.createResource(commonNSuri + "ClassesCountGroup");
            rc.addProperty(SH.group, o1gpc);

            //add SPARQL constraint
            RDFNode spc = shapeModel.createResource(commonNSuri + "ClassCount-propertySparql");
            rc.addProperty(SH.sparql, spc);

            Resource ps = shapeModel.createResource(commonNSuri + "ClassCount-propertySparql");
            ps.addProperty(RDF.type, SH.SPARQLConstraint);
            ps.addProperty(SH.message, "The class {?class} appears {?value} times in the data graph.");
            ps.addProperty(SH.select, """
                    \s
                                SELECT $this ?class (COUNT(?instance) AS ?value)
                                WHERE {
                                        ?instance rdf:type ?class .
                                       }
                                GROUP BY $this ?class
                                \s""");


            //declare prefixes
            Resource commonRes = shapeModel.createResource(commonNSuri);
            shapeModel.add(ResourceFactory.createStatement(commonRes, RDF.type, OWL2.Ontology));
            shapeModel.add(ResourceFactory.createStatement(commonRes, OWL2.imports, ResourceFactory.createResource(SH.getURI())));

            //List<RDFNode> prefixesList = new ArrayList<>();
            Resource resbn = ResourceFactory.createResource();
            Statement stmtbn0 = ResourceFactory.createStatement(resbn, RDF.type, SH.PrefixDeclaration);
            Statement stmtbn1 = ResourceFactory.createStatement(resbn, SH.prefix, ResourceFactory.createPlainLiteral("rdf"));
            Statement stmtbn2 = ResourceFactory.createStatement(resbn, SH.namespace, ResourceFactory.createPlainLiteral(RDF.getURI()));
            shapeModel.add(stmtbn0);
            shapeModel.add(stmtbn1);
            shapeModel.add(stmtbn2);
            commonRes.addProperty(SH.declare, resbn);

            //prefixesList.add(resbn);
            //RDFList prefixesListRDF = shapeModel.createList(prefixesList.iterator());
            //ps.addProperty(SH.prefixes, prefixesListRDF);
            ps.addProperty(SH.prefixes, commonRes);

        }

        if (options.isShapesOnAbstractOption()) { // the option when shacl validation uses inheritance and the shapes are on abstract classes
            int cardGroupOrder = 0;
            int assocGroupOrder = 0;
            int inverseassocGroupOrder = 0;
            int datatypeGroupOrder = 0;
            int propertyGroupOrder = 0;
            for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDFS.Class); i.hasNext(); ) {
                Resource resItem = i.next();
//                if (resItem.getLocalName().contains("AngleDegrees")){
//                    int k =1;
//                }
                if (classIsNotEnumOrDatatype(resItem, model)) {
                    //check if the class is concrete
                    boolean classDescripStereo = classIsDescription(resItem, model);
                    //boolean isConcreteInBase = isClassConcreteInBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                    boolean isConcrete = classIsConcrete(resItem, model);
//                    if (!isConcrete){
//                        int deb = 1;
//                    }
                    //if (isConcrete && isConcreteInBase) {
                    //add the NodeShape
                    String localName = resItem.getLocalName();
                    String classFullURI = resItem.getURI();
                    //add NodeShape for the CIM class
                    shapeModel = addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);

                    //get all local and inherited properties
                    List<Statement> localInheritProperties = new LinkedList<>();

                    int root = 0;
                    Resource classItem = resItem;
                    while (root == 0) {
                        if (model.listStatements(null, RDFS.domain, classItem).hasNext()) {
                            localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
//                                if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
//                                    classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
//                                } else {
                            root = 1;
//                                }
                        } else {
                            root = 1;
                        }
                    }


                    if (options.isClosedShapes() && !localInheritProperties.isEmpty()) {

                        //exclude inverse associations
                        List<Statement> localInheritPropertiesNoInverse = new LinkedList<>();
                        for (Statement stmtP : localInheritProperties) {
                            if (!model.listStatements(stmtP.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#AssociationUsed"), ResourceFactory.createPlainLiteral("No")).hasNext()) {
                                localInheritPropertiesNoInverse.add(stmtP);
                            }
                        }

                        if (!localInheritPropertiesNoInverse.isEmpty()) {
                            // Adding node shape and property shape to check if we have additional properties that are not defined in the profile
                            shapeModel = addNodeShape(shapeModel, nsURIprofile, resItem.getLocalName() + "-AllowedProperties", resItem.getURI());

                            //create the property shape
                            //RDFNode o = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                            Resource nodeShapeResourceClassProp = shapeModel.getResource(nsURIprofile + resItem.getLocalName() + "-AllowedProperties");

                            for (Statement stmtP : localInheritPropertiesNoInverse) {
                                Resource resbn = ResourceFactory.createResource();
                                Statement stmtbnP = ResourceFactory.createStatement(resbn, path, ResourceFactory.createProperty(stmtP.getSubject().getURI()));
                                nodeShapeResourceClassProp.addProperty(SH.property, asProperty(resbn));
                                shapeModel.add(stmtbnP);
                            }


                            //adding the properties for the PropertyShape
                            //Resource r = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                            //r.addProperty(RDF.type, SH.PropertyShape);
                            nodeShapeResourceClassProp.addProperty(SH.name, "PropertyNotInProfile");
                            nodeShapeResourceClassProp.addProperty(SH.description, "Checks if the dataset contains properties which are not defined in the profile to which this dataset conforms to.");
                            RDFNode o8p = shapeModel.createResource(SH.NS + "Info");
                            nodeShapeResourceClassProp.addProperty(SH.severity, o8p);
                            nodeShapeResourceClassProp.addProperty(SH.message, "This property is not part of the profile to which this dataset conforms to.");
                            if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.order, (RDFNode) null).hasNext()) {
                                RDFNode o1op = shapeModel.createTypedLiteral(propertyGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                nodeShapeResourceClassProp.addProperty(SH.order, o1op);
                                propertyGroupOrder = propertyGroupOrder + 1;
                            }
                            RDFNode o1gp = shapeModel.createResource(nsURIprofile + "ProfilePropertiesGroup");
                            nodeShapeResourceClassProp.addProperty(SH.group, o1gp);
                            RDFNode o1closed = shapeModel.createTypedLiteral("true", "http://www.w3.org/2001/XMLSchema#boolean");
                            nodeShapeResourceClassProp.addProperty(SH.closed, o1closed);
                            if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.ignoredProperties, (RDFNode) null).hasNext()) {
                                //nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, ignorePropListRDFlist);
                                nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, shapeModel.createList(new RDFNode[]{RDF.type}));
                            }
                        }
                    }

                    for (Statement stmt : localInheritProperties) { // loop on the local and inherited properties
                        ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                        /*
                         * propertyNodeFeatures structure
                         * 0 - type of check: cardinality, datatype, associationValueType
                         * 1 - message
                         * 2 - name
                         * 3 - description
                         * 4 - severity
                         * 5 - cardinality
                         * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                         * in case of enumeration 6 is set to Enumeration
                         * in case of compound 6 is set to Compound
                         * 7 - is a list of uri of the enumeration attributes
                         * 8 - order
                         * 9 - group
                         * 10 - the inverse role name in case of association - the inverse end
                         * 11 - the list of concrete classes for association - the value type at the used end
                         * 12 - classFullURI for the targetClass of the NodeShape
                         * 13 - the uri of the compound class to be used in sh:class
                         * 14 - path for the attributes of the compound
                         */
                        for (int ii = 0; ii < 14; ii++) {
                            propertyNodeFeatures.add("");
                        }

                        if (model.listStatements(stmt.getSubject(), assocUsed, (RDFNode) null).hasNext()) { // it is an association
                            if (model.listStatements(stmt.getSubject(), assocUsed, literalYes).hasNext()) { // the association direction exchanged
                                //Cardinality check
                                propertyNodeFeatures.set(0, "cardinality");
                                String cardinality = "";
                                if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                    cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                }
                                String localNameAssoc = stmt.getSubject().getLocalName();
                                propertyNodeFeatures.set(5, cardinality);
                                Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                propertyNodeFeatures.set(1, "Association with cardinality violation at the used direction.");
                                propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                                propertyNodeFeatures.set(4, "Violation");
                                propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                cardGroupOrder = cardGroupOrder + 1;
                                propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                                String propertyFullURI = stmt.getSubject().getURI();

                                shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                //Association check for target class
                                if (options.isShaclSkipNcPropertyReference() && localNameAssoc.contains(".PropertyReference")) {
                                    continue;
                                }

                                propertyNodeFeatures.set(0, "associationValueType");

                                //Check if there is a self association on model header and if this is the case then do not include sh:in and the sh:path needs to be different
                                String assocDomain = model.listStatements(stmt.getSubject(), RDFS.domain, (RDFNode) null).next().getObject().toString();
                                String assocRange = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().toString();
                                Resource assocRangeRes = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().asResource();

                                if (assocDomain.equals(assocRange) && assocDomain.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model")) { // this is the case when it is a header
                                    propertyNodeFeatures.set(1, "Not correct serialisation (rdf:resource is expected).");
                                    propertyNodeFeatures.set(2, localNameAssoc + "-nodeKind");
                                    propertyNodeFeatures.set(3, "This constraint validates the node kind of the association at the used direction.");
                                } else {//any other case
                                    propertyNodeFeatures.set(1, "Not correct target class.");
                                    propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                    propertyNodeFeatures.set(3, "This constraint validates the value of the association at the used direction.");
                                }

                                propertyNodeFeatures.set(4, "Violation");
                                propertyNodeFeatures.set(8, assocGroupOrder + 1); // this is the order
                                assocGroupOrder = assocGroupOrder + 1;
                                propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                                List<Resource> concreteClasses = new LinkedList<>();

                                LinkedList<Resource> subclassResources = new LinkedList<>();
                                //if (classIsConcrete(assocRangeRes, model)) {
                                subclassResources.add(assocRangeRes);
                                //}
//                                    subclassResources = getAllSubclassesResource(model, assocRangeRes, subclassResources);
                                //if (classIsConcrete(res, model)) {
                                //}
                                concreteClasses.addAll(subclassResources);
//
//                                    concreteClasses.addAll(getConcreteSubclassesFromBase(assocRangeRes, baseTier1Map, baseTier2Map, baseTier3Map));
//                                    if (concreteClasses.isEmpty()) {
//                                        concreteClasses.add(assocRangeRes);
//                                        System.out.println("WARNING: The class " + assocRange + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
//                                    }

                                LinkedHashSet<Resource> concreteClasseshashSet = new LinkedHashSet<>(concreteClasses);
                                Iterator<Resource> itr = concreteClasseshashSet.iterator();
                                LinkedList<Resource> concreteClassesList = new LinkedList<>();
                                while (itr.hasNext()) {
                                    concreteClassesList.add(itr.next());
                                }
//                                        if (shapesOnAbstractOption == 1){
//                                            concreteClassesList.add(assocRangeRes);
//                                        }else {
//                                            while (itr.hasNext()) {
//                                                concreteClassesList.add(itr.next());
//                                            }
//                                        }
                                propertyNodeFeatures.set(10, concreteClassesList);
                                propertyNodeFeatures.set(11, classFullURI);

                                shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                            } else { // this is for cardinality of inverse associations
                                //Add node shape and property shape for the presence of the inverse association
                                String invAssocURI = stmt.getSubject().getURI();
                                String localNameInvAssoc = stmt.getSubject().getLocalName();
                                shapeModel = addNodeShapeProfileClass(shapeModel, nsURIprofile, localNameInvAssoc + "-inverseNodePresent", invAssocURI);
                                //create the property shape
                                RDFNode oi = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                Resource nodeShapeResourceClassInv = shapeModel.getResource(nsURIprofile + localNameInvAssoc + "-inverseNodePresent");
                                nodeShapeResourceClassInv.addProperty(SH.property, oi);

                                //adding the properties for the PropertyShape
                                Resource ri = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                ri.addProperty(RDF.type, SH.PropertyShape);
                                ri.addProperty(SH.name, "InverseAssociationPresent");
                                ri.addProperty(SH.description, "Inverse associations shall not be instantiated.");
                                RDFNode o8i = shapeModel.createResource(SH.NS + "Violation");
                                ri.addProperty(SH.severity, o8i);
                                ri.addProperty(SH.message, "Inverse association is present.");
                                RDFNode o5i = shapeModel.createResource(invAssocURI);
                                ri.addProperty(path, o5i);
                                RDFNode o1oi = shapeModel.createTypedLiteral(inverseassocGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                inverseassocGroupOrder = inverseassocGroupOrder + 1;
                                if (!shapeModel.listStatements(ri, SH.order, (RDFNode) null).hasNext()) {
                                    ri.addProperty(SH.order, o1oi);
                                }
                                RDFNode o1gi = shapeModel.createResource(nsURIprofile + "InverseAssociationsGroup");
                                ri.addProperty(SH.group, o1gi);
                                RDFNode o4i = shapeModel.createTypedLiteral(0, "http://www.w3.org/2001/XMLSchema#integer");
                                ri.addProperty(SH.maxCount, o4i);

                                if (options.isShaclFlagInverse()) {
                                    propertyNodeFeatures.set(0, "cardinality");
                                    String cardinality = "";
                                    if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                        cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                    }
                                    String localNameAssoc = stmt.getSubject().getLocalName();
                                    propertyNodeFeatures.set(5, cardinality);
                                    Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                    propertyNodeFeatures.set(1, "Association with cardinality violation at the inverse direction.");
                                    propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                    propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the inverse direction.");
                                    propertyNodeFeatures.set(4, "Violation");
                                    propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                    cardGroupOrder = cardGroupOrder + 1;
                                    propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
                                    Set<String> inverseAssocSet = new LinkedHashSet<>();
                                    Property assocProp = ResourceFactory.createProperty(model.listStatements(stmt.getSubject(), inverseRoleName, (RDFNode) null).next().getObject().toString());
                                    String assocPropName = assocProp.getLocalName();
                                    String assocPropNS = assocProp.getNameSpace();
                                    if (options.isBaseprofilesshaclglag()) {
                                        inverseAssocSet.add(assocProp.toString());
                                        //add the 3 cim namespaces if it is cim namespace
                                        if (assocPropNS.equals("http://iec.ch/TC57/CIM100#") || assocPropNS.equals("http://iec.ch/TC57/2013/CIM-schema-cim16#") || assocPropNS.equals("https://cim.ucaiug.io/ns#")) {
                                            inverseAssocSet.add("http://iec.ch/TC57/CIM100#" + assocPropName);
                                            inverseAssocSet.add("http://iec.ch/TC57/2013/CIM-schema-cim16#" + assocPropName);
                                            inverseAssocSet.add("https://cim.ucaiug.io/ns#" + assocPropName);
                                        }
                                    } else {
                                        inverseAssocSet.add(assocProp.toString());
                                    }
                                    propertyNodeFeatures.set(10, inverseAssocSet); // this is a list of role names
                                    //propertyNodeFeatures.set(10, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString()); // this is the inverse role name
                                    propertyNodeFeatures.set(11, "Inverse");

                                    String propertyFullURI = stmt.getSubject().getURI();

                                    shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                                }
                            }
                        } else {//if it is an attribute


                            Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                            String localNameAttr = stmt.getSubject().getLocalName();
                            String propertyFullURI = stmt.getSubject().getURI();

//                                if (localNameAttr.equals("Location.mainAddress")){
//                                    int k1=1;
//                                }
                            //need to check if the class has any subclasses in the base profiles, if it has then the property shape should appear on the subclasses
                            if (options.isExcludeMRID()) { // user selects that mRID should be skipped for description classes
                                if (classDescripStereo) { //the class is stereotyped with description
                                    if (!localNameAttr.equals("IdentifiedObject.mRID")) { //the attribute is not mRID
                                        shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                        cardGroupOrder = cardGroupOrder + 1;
                                        datatypeGroupOrder = datatypeGroupOrder + 1;
                                    }
                                } else {
                                    shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                    cardGroupOrder = cardGroupOrder + 1;
                                    datatypeGroupOrder = datatypeGroupOrder + 1;
                                }
                            } else {
                                shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                cardGroupOrder = cardGroupOrder + 1;
                                datatypeGroupOrder = datatypeGroupOrder + 1;
                            }

                            //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                            // for each attribute of the compound
                            if (model.listStatements(stmt.getSubject(), dataType, (RDFNode) null).hasNext()) {
                                Resource datatypeRes = model.getRequiredProperty(stmt.getSubject(), dataType).getObject().asResource();
                                if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()) {
                                    shapeModel = addPropertyNodeForAttributeCompoundNew(shapeModel, propertyNodeFeatures, model, nsURIprofile, nodeShapeResource, stmt.getSubject(), datatypeRes);
                                }
                            }
                        }
                    }
                }
            }
        } else { // this is for the concrete classes and tracing the base models
            int cardGroupOrder = 0;
            int assocGroupOrder = 0;
            int inverseassocGroupOrder = 0;
            int datatypeGroupOrder = 0;
            int propertyGroupOrder = 0;
            for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDFS.Class); i.hasNext(); ) {
                Resource resItem = i.next();
//                if (resItem.getLocalName().contains("Terminal")){
//                    int k =1;
//                }
                if (classIsNotEnumOrDatatype(resItem, model)) {
                    //check if the class is concrete
                    boolean classDescripStereo = classIsDescription(resItem, model);
                    boolean isConcreteInBase = isClassConcreteInBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                    boolean existsInBase = existsInBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                    boolean isConcrete = classIsConcrete(resItem, model);
                    if ((isConcrete && isConcreteInBase && existsInBase) || (isConcrete && !existsInBase)) {
                        //add the NodeShape
                        String localName = resItem.getLocalName();
                        String classFullURI = resItem.getURI();
                        //add NodeShape for the CIM class
                        shapeModel = addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);

                        //get all local and inherited properties
                        List<Statement> localInheritProperties = new LinkedList<>();

//                        int root = 0;
//                        Resource classItem = resItem;
//                        while (root == 0) {
//                            if(model.listStatements(null, RDFS.domain, classItem).hasNext()) {
//                                localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
//                                if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
//                                    classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
//                                } else {
//                                    root = 1;
//                                }
//                            }else{
//                                root = 1;
//                            }
//                        }

                        int root = 0;
                        Resource classItem = resItem;
                        while (root == 0) {
                            // the resource of the subClassOf
                            if (model.listStatements(null, RDFS.domain, classItem).hasNext()) {
                                localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
                            }
                            if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                                classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                            } else {
                                root = 1;
                            }
                        }

                        if (options.isClosedShapes() && !localInheritProperties.isEmpty()) {
                            //exclude inverse associations
                            List<Statement> localInheritPropertiesNoInverse = new LinkedList<>();
                            for (Statement stmtP : localInheritProperties) {
                                if (!model.listStatements(stmtP.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#AssociationUsed"), ResourceFactory.createPlainLiteral("No")).hasNext()) {
                                    localInheritPropertiesNoInverse.add(stmtP);
                                }
                            }

                            if (!localInheritPropertiesNoInverse.isEmpty()) {
                                // Adding node shape and property shape to check if we have additional properties that are not defined in the profile
                                shapeModel = addNodeShape(shapeModel, nsURIprofile, resItem.getLocalName() + "-AllowedProperties", resItem.getURI());

                                //create the property shape
                                //RDFNode o = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                                Resource nodeShapeResourceClassProp = shapeModel.getResource(nsURIprofile + resItem.getLocalName() + "-AllowedProperties");

                                for (Statement stmtP : localInheritPropertiesNoInverse) {
                                    Resource resbn = ResourceFactory.createResource();
                                    Statement stmtbnP = ResourceFactory.createStatement(resbn, path, ResourceFactory.createProperty(stmtP.getSubject().getURI()));
                                    nodeShapeResourceClassProp.addProperty(SH.property, asProperty(resbn));
                                    shapeModel.add(stmtbnP);
                                }


                                //adding the properties for the PropertyShape
                                //Resource r = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                                //r.addProperty(RDF.type, SH.PropertyShape);
                                nodeShapeResourceClassProp.addProperty(SH.name, "PropertyNotInProfile");
                                nodeShapeResourceClassProp.addProperty(SH.description, "Checks if the dataset contains properties which are not defined in the profile to which this dataset conforms to.");
                                RDFNode o8p = shapeModel.createResource(SH.NS + "Info");
                                nodeShapeResourceClassProp.addProperty(SH.severity, o8p);
                                nodeShapeResourceClassProp.addProperty(SH.message, "This property is not part of the profile to which this dataset conforms to.");
                                if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.order, (RDFNode) null).hasNext()) {
                                    RDFNode o1op = shapeModel.createTypedLiteral(propertyGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                    nodeShapeResourceClassProp.addProperty(SH.order, o1op);
                                    propertyGroupOrder = propertyGroupOrder + 1;
                                }
                                RDFNode o1gp = shapeModel.createResource(nsURIprofile + "ProfilePropertiesGroup");
                                nodeShapeResourceClassProp.addProperty(SH.group, o1gp);
                                RDFNode o1closed = shapeModel.createTypedLiteral("true", "http://www.w3.org/2001/XMLSchema#boolean");
                                nodeShapeResourceClassProp.addProperty(SH.closed, o1closed);
                                if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.ignoredProperties, (RDFNode) null).hasNext()) {
                                    //nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, ignorePropListRDFlist);
                                    nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, shapeModel.createList(new RDFNode[]{RDF.type}));
                                }
                            }
                        }

                        for (Statement stmt : localInheritProperties) { // loop on the local and inherited properties
                            ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                            /*
                             * propertyNodeFeatures structure
                             * 0 - type of check: cardinality, datatype, associationValueType
                             * 1 - message
                             * 2 - name
                             * 3 - description
                             * 4 - severity
                             * 5 - cardinality
                             * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                             * in case of enumeration 6 is set to Enumeration
                             * in case of compound 6 is set to Compound
                             * 7 - is a list of uri of the enumeration attributes
                             * 8 - order
                             * 9 - group
                             * 10 - the inverse role name in case of association - the inverse end
                             * 11 - the list of concrete classes for association - the value type at the used end
                             * 12 - classFullURI for the targetClass of the NodeShape
                             * 13 - the uri of the compound class to be used in sh:class
                             * 14 - path for the attributes of the compound
                             */
                            for (int ii = 0; ii < 14; ii++) {
                                propertyNodeFeatures.add("");
                            }

                            if (model.listStatements(stmt.getSubject(), assocUsed, (RDFNode) null).hasNext()) { // it is an association
                                if (model.listStatements(stmt.getSubject(), assocUsed, literalYes).hasNext()) { // the association direction exchanged
                                    //Cardinality check
                                    propertyNodeFeatures.set(0, "cardinality");
                                    String cardinality = "";
                                    if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                        cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                    }
                                    String localNameAssoc = stmt.getSubject().getLocalName();
                                    propertyNodeFeatures.set(5, cardinality);
                                    Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                    propertyNodeFeatures.set(1, "Association with cardinality violation at the used direction.");
                                    propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                    propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                                    propertyNodeFeatures.set(4, "Violation");
                                    propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                    cardGroupOrder = cardGroupOrder + 1;
                                    propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                                    String propertyFullURI = stmt.getSubject().getURI();

                                    shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                    //Association check for target class
                                    if (options.isShaclSkipNcPropertyReference() && localNameAssoc.contains(".PropertyReference")) {
                                        continue;
                                    }

                                    propertyNodeFeatures.set(0, "associationValueType");

                                    //Check if there is a self association on model header and if this is the case then do not include sh:in and the sh:path needs to be different
                                    String assocDomain = model.listStatements(stmt.getSubject(), RDFS.domain, (RDFNode) null).next().getObject().toString();
                                    String assocRange = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().toString();
                                    Resource assocRangeRes = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().asResource();

                                    if (assocDomain.equals(assocRange) && assocDomain.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model")) { // this is the case when it is a header
                                        propertyNodeFeatures.set(1, "Not correct serialisation (rdf:resource is expected).");
                                        propertyNodeFeatures.set(2, localNameAssoc + "-nodeKind");
                                        propertyNodeFeatures.set(3, "This constraint validates the node kind of the association at the used direction.");
                                    } else {//any other case
                                        propertyNodeFeatures.set(1, "Not correct target class.");
                                        propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                        propertyNodeFeatures.set(3, "This constraint validates the value of the association at the used direction.");
                                    }

                                    propertyNodeFeatures.set(4, "Violation");
                                    propertyNodeFeatures.set(8, assocGroupOrder + 1); // this is the order
                                    assocGroupOrder = assocGroupOrder + 1;
                                    propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                                    List<Resource> concreteClasses = new LinkedList<>();

                                    LinkedList<Resource> subclassResources = new LinkedList<>();
                                    if (classIsConcrete(assocRangeRes, model)) {
                                        subclassResources.add(assocRangeRes);
                                    }
                                    subclassResources = getAllSubclassesResource(model, assocRangeRes, subclassResources);
                                    for (Resource res : subclassResources) {
                                        if (classIsConcrete(res, model)) {
                                            concreteClasses.add(res);
                                        }
                                    }

                                    concreteClasses.addAll(getConcreteSubclassesFromBase(assocRangeRes, baseTier1Map, baseTier2Map, baseTier3Map));
                                    if (concreteClasses.isEmpty()) {
                                        concreteClasses.add(assocRangeRes);
                                        System.out.println("WARNING: The class " + assocRange + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                    }

                                    LinkedHashSet<Resource> concreteClasseshashSet = new LinkedHashSet<>(concreteClasses);
                                    Iterator<Resource> itr = concreteClasseshashSet.iterator();
                                    LinkedList<Resource> concreteClassesList = new LinkedList<>();
                                    while (itr.hasNext()) {
                                        concreteClassesList.add(itr.next());
                                    }
//                                        if (shapesOnAbstractOption == 1){
//                                            concreteClassesList.add(assocRangeRes);
//                                        }else {
//                                            while (itr.hasNext()) {
//                                                concreteClassesList.add(itr.next());
//                                            }
//                                        }
                                    propertyNodeFeatures.set(10, concreteClassesList);
                                    propertyNodeFeatures.set(11, classFullURI);

                                    shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                } else { // this is for cardinality of inverse associations
                                    //Add node shape and property shape for the presence of the inverse association
                                    String invAssocURI = stmt.getSubject().getURI();
                                    String localNameInvAssoc = stmt.getSubject().getLocalName();
                                    shapeModel = addNodeShapeProfileClass(shapeModel, nsURIprofile, localNameInvAssoc + "-inverseNodePresent", invAssocURI);
                                    //create the property shape
                                    RDFNode oi = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                    Resource nodeShapeResourceClassInv = shapeModel.getResource(nsURIprofile + localNameInvAssoc + "-inverseNodePresent");
                                    nodeShapeResourceClassInv.addProperty(SH.property, oi);

                                    //adding the properties for the PropertyShape
                                    Resource ri = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                    ri.addProperty(RDF.type, SH.PropertyShape);
                                    ri.addProperty(SH.name, "InverseAssociationPresent");
                                    ri.addProperty(SH.description, "Inverse associations shall not be instantiated.");
                                    RDFNode o8i = shapeModel.createResource(SH.NS + "Violation");
                                    ri.addProperty(SH.severity, o8i);
                                    ri.addProperty(SH.message, "Inverse association is present.");
                                    RDFNode o5i = shapeModel.createResource(invAssocURI);
                                    ri.addProperty(path, o5i);
                                    RDFNode o1oi = shapeModel.createTypedLiteral(inverseassocGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                    inverseassocGroupOrder = inverseassocGroupOrder + 1;
                                    if (!shapeModel.listStatements(ri, SH.order, (RDFNode) null).hasNext()) {
                                        ri.addProperty(SH.order, o1oi);
                                    }
                                    RDFNode o1gi = shapeModel.createResource(nsURIprofile + "InverseAssociationsGroup");
                                    ri.addProperty(SH.group, o1gi);
                                    RDFNode o4i = shapeModel.createTypedLiteral(0, "http://www.w3.org/2001/XMLSchema#integer");
                                    ri.addProperty(SH.maxCount, o4i);

                                    if (options.isShaclFlagInverse()) {
                                        propertyNodeFeatures.set(0, "cardinality");
                                        String cardinality = "";
                                        if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                            cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                        }
                                        String localNameAssoc = stmt.getSubject().getLocalName();
                                        propertyNodeFeatures.set(5, cardinality);
                                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                        propertyNodeFeatures.set(1, "Association with cardinality violation at the inverse direction.");
                                        propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the inverse direction.");
                                        propertyNodeFeatures.set(4, "Violation");
                                        propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                        cardGroupOrder = cardGroupOrder + 1;
                                        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
                                        Set<String> inverseAssocSet = new LinkedHashSet<>();
                                        Property assocProp = ResourceFactory.createProperty(model.listStatements(stmt.getSubject(), inverseRoleName, (RDFNode) null).next().getObject().toString());
                                        String assocPropName = assocProp.getLocalName();
                                        String assocPropNS = assocProp.getNameSpace();
                                        if (options.isBaseprofilesshaclglag()) {
                                            inverseAssocSet.add(assocProp.toString());
                                            //add the 3 cim namespaces if it is cim namespace
                                            if (assocPropNS.equals("http://iec.ch/TC57/CIM100#") || assocPropNS.equals("http://iec.ch/TC57/2013/CIM-schema-cim16#") || assocPropNS.equals("https://cim.ucaiug.io/ns#")) {
                                                inverseAssocSet.add("http://iec.ch/TC57/CIM100#" + assocPropName);
                                                inverseAssocSet.add("http://iec.ch/TC57/2013/CIM-schema-cim16#" + assocPropName);
                                                inverseAssocSet.add("https://cim.ucaiug.io/ns#" + assocPropName);
                                            }
                                        } else {
                                            inverseAssocSet.add(assocProp.toString());
                                        }
                                        propertyNodeFeatures.set(10, inverseAssocSet); // this is a list of role names
                                        //propertyNodeFeatures.set(10, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString()); // this is the inverse role name
                                        propertyNodeFeatures.set(11, "Inverse");

                                        String propertyFullURI = stmt.getSubject().getURI();

                                        shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                                    }
                                }
                            } else {//if it is an attribute


                                Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                String localNameAttr = stmt.getSubject().getLocalName();
                                String propertyFullURI = stmt.getSubject().getURI();

//                                if (localNameAttr.equals("Location.mainAddress")){
//                                    int k1=1;
//                                }
                                //need to check if the class has any subclasses in the base profiles, if it has then the property shape should appear on the subclasses
                                if (options.isExcludeMRID()) { // user selects that mRID should be skipped for description classes
                                    if (classDescripStereo) { //the class is stereotyped with description
                                        if (!localNameAttr.equals("IdentifiedObject.mRID")) { //the attribute is not mRID
                                            shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                            cardGroupOrder = cardGroupOrder + 1;
                                            datatypeGroupOrder = datatypeGroupOrder + 1;
                                        }
                                    } else {
                                        shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                        cardGroupOrder = cardGroupOrder + 1;
                                        datatypeGroupOrder = datatypeGroupOrder + 1;
                                    }
                                } else {
                                    shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                    cardGroupOrder = cardGroupOrder + 1;
                                    datatypeGroupOrder = datatypeGroupOrder + 1;
                                }

                                //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                                // for each attribute of the compound
                                if (model.listStatements(stmt.getSubject(), dataType, (RDFNode) null).hasNext()) {
                                    Resource datatypeRes = model.getRequiredProperty(stmt.getSubject(), dataType).getObject().asResource();
                                    if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()) {
                                        shapeModel = addPropertyNodeForAttributeCompoundNew(shapeModel, propertyNodeFeatures, model, nsURIprofile, nodeShapeResource, stmt.getSubject(), datatypeRes);
                                    }
                                }
                            }
                        }
                    } else if ((!isConcrete && classDescripStereo) || (isConcrete && classDescripStereo && !isConcreteInBase)) { //class is abstract, has attributes or associations and has description stereotype; then check for concrete classes in base profiles and crete shapes for them

                        //check if the abstract class has properties
                        if (model.listStatements(null, RDFS.domain, resItem).hasNext()) {

//                            if (resItem.getLocalName().equals("FunctionBlock")){
//                                int fb=1;
//                            }
                            // get concrete classes from base profile
                            LinkedList<Resource> concreteClassesBase = getConcreteSubclassesFromBase(resItem, baseTier1Map, baseTier2Map, baseTier3Map);
                            for (Resource resBaseItem : concreteClassesBase) {

                                //add the NodeShape
                                String localName = resBaseItem.getLocalName();
                                String classFullURI = resBaseItem.getURI();
                                //add NodeShape for the CIM class
                                shapeModel = addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);

                                //get all local and inherited properties
                                List<Statement> localInheritProperties = new LinkedList<>();

//                                int root = 0;
//                                Resource classItem = resItem;
//                                while (root == 0) {
//                                    if (model.listStatements(null, RDFS.domain, classItem).hasNext()) {
//                                        localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
//                                        if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
//                                            classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
//                                        } else {
//                                            root = 1;
//                                        }
//                                    } else {
//                                        root = 1;
//                                    }
//                                }

                                int root = 0;
                                Resource classItem = resItem;
                                while (root == 0) {
                                    // the resource of the subClassOf
                                    if (model.listStatements(null, RDFS.domain, classItem).hasNext()) {
                                        localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
                                    }
                                    if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                                        classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                                    } else {
                                        root = 1;
                                    }
                                }

                                if (options.isClosedShapes() && !localInheritProperties.isEmpty()) {
                                    //exclude inverse associations
                                    List<Statement> localInheritPropertiesNoInverse = new LinkedList<>();
                                    for (Statement stmtP : localInheritProperties) {
                                        if (!model.listStatements(stmtP.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#AssociationUsed"), ResourceFactory.createPlainLiteral("No")).hasNext()) {
                                            localInheritPropertiesNoInverse.add(stmtP);
                                        }
                                    }

                                    if (!localInheritPropertiesNoInverse.isEmpty()) {
                                        // Adding node shape and property shape to check if we have additional properties that are not defined in the profile
                                        shapeModel = addNodeShape(shapeModel, nsURIprofile, resBaseItem.getLocalName() + "-AllowedProperties", resBaseItem.getURI());

                                        //create the property shape
                                        //RDFNode o = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                                        Resource nodeShapeResourceClassProp = shapeModel.getResource(nsURIprofile + resBaseItem.getLocalName() + "-AllowedProperties");

                                        for (Statement stmtP : localInheritProperties) {
                                            Resource resbn = ResourceFactory.createResource();
                                            Statement stmtbnP = ResourceFactory.createStatement(resbn, path, ResourceFactory.createProperty(stmtP.getSubject().getURI()));
                                            nodeShapeResourceClassProp.addProperty(SH.property, asProperty(resbn));
                                            shapeModel.add(stmtbnP);
                                        }


                                        //adding the properties for the PropertyShape
                                        //Resource r = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
                                        //r.addProperty(RDF.type, SH.PropertyShape);
                                        nodeShapeResourceClassProp.addProperty(SH.name, "PropertyNotInProfile");
                                        nodeShapeResourceClassProp.addProperty(SH.description, "Checks if the dataset contains properties which are not defined in the profile to which this dataset conforms to.");
                                        RDFNode o8p = shapeModel.createResource(SH.NS + "Info");
                                        nodeShapeResourceClassProp.addProperty(SH.severity, o8p);
                                        nodeShapeResourceClassProp.addProperty(SH.message, "This property is not part of the profile to which this dataset conforms to.");
                                        if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.order, (RDFNode) null).hasNext()) {
                                            RDFNode o1op = shapeModel.createTypedLiteral(propertyGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                            nodeShapeResourceClassProp.addProperty(SH.order, o1op);
                                            propertyGroupOrder = propertyGroupOrder + 1;
                                        }
                                        RDFNode o1gp = shapeModel.createResource(nsURIprofile + "ProfilePropertiesGroup");
                                        nodeShapeResourceClassProp.addProperty(SH.group, o1gp);
                                        RDFNode o1closed = shapeModel.createTypedLiteral("true", "http://www.w3.org/2001/XMLSchema#boolean");
                                        nodeShapeResourceClassProp.addProperty(SH.closed, o1closed);
                                        if (!shapeModel.listStatements(nodeShapeResourceClassProp, SH.ignoredProperties, (RDFNode) null).hasNext()) {
                                            //nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, ignorePropListRDFlist);
                                            nodeShapeResourceClassProp.addProperty(SH.ignoredProperties, shapeModel.createList(new RDFNode[]{RDF.type}));
                                        }
                                    }
                                }

                                for (Statement stmt : localInheritProperties) { // loop on the local and inherited properties
                                    ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                                    /*
                                     * propertyNodeFeatures structure
                                     * 0 - type of check: cardinality, datatype, associationValueType
                                     * 1 - message
                                     * 2 - name
                                     * 3 - description
                                     * 4 - severity
                                     * 5 - cardinality
                                     * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                                     * in case of enumeration 6 is set to Enumeration
                                     * in case of compound 6 is set to Compound
                                     * 7 - is a list of uri of the enumeration attributes
                                     * 8 - order
                                     * 9 - group
                                     * 10 - the inverse role name in case of association - the inverse end
                                     * 11 - the list of concrete classes for association - the value type at the used end
                                     * 12 - classFullURI for the targetClass of the NodeShape
                                     * 13 - the uri of the compound class to be used in sh:class
                                     * 14 - path for the attributes of the compound
                                     */
                                    for (int ii = 0; ii < 14; ii++) {
                                        propertyNodeFeatures.add("");
                                    }

                                    if (model.listStatements(stmt.getSubject(), assocUsed, (RDFNode) null).hasNext()) { // it is an association
                                        if (model.listStatements(stmt.getSubject(), assocUsed, literalYes).hasNext()) { // the association direction exchanged
                                            //Cardinality check
                                            propertyNodeFeatures.set(0, "cardinality");
                                            String cardinality = "";
                                            if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                                cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                            }
                                            String localNameAssoc = stmt.getSubject().getLocalName();
                                            propertyNodeFeatures.set(5, cardinality);
                                            Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                            propertyNodeFeatures.set(1, "Association with cardinality violation at the used direction.");
                                            propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                            propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                                            propertyNodeFeatures.set(4, "Violation");
                                            propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                            cardGroupOrder = cardGroupOrder + 1;
                                            propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                                            String propertyFullURI = stmt.getSubject().getURI();

                                            shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                            //Association check for target class
                                            if (options.isShaclSkipNcPropertyReference() && localNameAssoc.contains(".PropertyReference")) {
                                                continue;
                                            }

                                            propertyNodeFeatures.set(0, "associationValueType");

                                            //Check if there is a self association on model header and if this is the case then do not include sh:in and the sh:path needs to be different
                                            String assocDomain = model.listStatements(stmt.getSubject(), RDFS.domain, (RDFNode) null).next().getObject().toString();
                                            String assocRange = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().toString();
                                            Resource assocRangeRes = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().asResource();

                                            if (assocDomain.equals(assocRange) && assocDomain.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model")) { // this is the case when it is a header
                                                propertyNodeFeatures.set(1, "Not correct serialisation (rdf:resource is expected).");
                                                propertyNodeFeatures.set(2, localNameAssoc + "-nodeKind");
                                                propertyNodeFeatures.set(3, "This constraint validates the node kind of the association at the used direction.");
                                            } else {//any other case
                                                propertyNodeFeatures.set(1, "Not correct target class.");
                                                propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                                propertyNodeFeatures.set(3, "This constraint validates the value of the association at the used direction.");
                                            }

                                            propertyNodeFeatures.set(4, "Violation");
                                            propertyNodeFeatures.set(8, assocGroupOrder + 1); // this is the order
                                            assocGroupOrder = assocGroupOrder + 1;
                                            propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                                            List<Resource> concreteClasses = new LinkedList<>();

                                            LinkedList<Resource> subclassResources = new LinkedList<>();
                                            if (classIsConcrete(assocRangeRes, model)) {
                                                subclassResources.add(assocRangeRes);
                                            }
                                            subclassResources = getAllSubclassesResource(model, assocRangeRes, subclassResources);
                                            for (Resource res : subclassResources) {
                                                if (classIsConcrete(res, model)) {
                                                    concreteClasses.add(res);
                                                }
                                            }

                                            concreteClasses.addAll(getConcreteSubclassesFromBase(assocRangeRes, baseTier1Map, baseTier2Map, baseTier3Map));
                                            if (concreteClasses.isEmpty()) {
                                                concreteClasses.add(assocRangeRes);
                                                System.out.println("WARNING: The class " + assocRange + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                            }

                                            LinkedHashSet<Resource> concreteClasseshashSet = new LinkedHashSet<>(concreteClasses);
                                            Iterator<Resource> itr = concreteClasseshashSet.iterator();
                                            LinkedList<Resource> concreteClassesList = new LinkedList<>();
                                            while (itr.hasNext()) {
                                                concreteClassesList.add(itr.next());
                                            }
//                                        if (shapesOnAbstractOption == 1){
//                                            concreteClassesList.add(assocRangeRes);
//                                        }else {
//                                            while (itr.hasNext()) {
//                                                concreteClassesList.add(itr.next());
//                                            }
//                                        }
                                            propertyNodeFeatures.set(10, concreteClassesList);
                                            propertyNodeFeatures.set(11, classFullURI);

                                            shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                        } else { // this is for cardinality of inverse associations
                                            //Add node shape and property shape for the presence of the inverse association
                                            String invAssocURI = stmt.getSubject().getURI();
                                            String localNameInvAssoc = stmt.getSubject().getLocalName();
                                            shapeModel = addNodeShapeProfileClass(shapeModel, nsURIprofile, localNameInvAssoc + "-inverseNodePresent", invAssocURI);
                                            //create the property shape
                                            RDFNode oi = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                            Resource nodeShapeResourceClassInv = shapeModel.getResource(nsURIprofile + localNameInvAssoc + "-inverseNodePresent");
                                            nodeShapeResourceClassInv.addProperty(SH.property, oi);

                                            //adding the properties for the PropertyShape
                                            Resource ri = shapeModel.createResource(nsURIprofile + localNameInvAssoc + "-propertyInverse");
                                            ri.addProperty(RDF.type, SH.PropertyShape);
                                            ri.addProperty(SH.name, "InverseAssociationPresent");
                                            ri.addProperty(SH.description, "Inverse associations shall not be instantiated.");
                                            RDFNode o8i = shapeModel.createResource(SH.NS + "Violation");
                                            ri.addProperty(SH.severity, o8i);
                                            ri.addProperty(SH.message, "Inverse association is present.");
                                            RDFNode o5i = shapeModel.createResource(invAssocURI);
                                            ri.addProperty(path, o5i);
                                            RDFNode o1oi = shapeModel.createTypedLiteral(inverseassocGroupOrder + 1, "http://www.w3.org/2001/XMLSchema#integer");
                                            inverseassocGroupOrder = inverseassocGroupOrder + 1;
                                            if (!shapeModel.listStatements(ri, SH.order, (RDFNode) null).hasNext()) {
                                                ri.addProperty(SH.order, o1oi);
                                            }
                                            RDFNode o1gi = shapeModel.createResource(nsURIprofile + "InverseAssociationsGroup");
                                            ri.addProperty(SH.group, o1gi);
                                            RDFNode o4i = shapeModel.createTypedLiteral(0, "http://www.w3.org/2001/XMLSchema#integer");
                                            ri.addProperty(SH.maxCount, o4i);

                                            if (options.isShaclFlagInverse()) {
                                                propertyNodeFeatures.set(0, "cardinality");
                                                String cardinality = "";
                                                if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                                    cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                                }
                                                String localNameAssoc = stmt.getSubject().getLocalName();
                                                propertyNodeFeatures.set(5, cardinality);
                                                Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                                propertyNodeFeatures.set(1, "Association with cardinality violation at the inverse direction.");
                                                propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                                propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the inverse direction.");
                                                propertyNodeFeatures.set(4, "Violation");
                                                propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
                                                cardGroupOrder = cardGroupOrder + 1;
                                                propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
                                                Set<String> inverseAssocSet = new LinkedHashSet<>();
                                                Property assocProp = ResourceFactory.createProperty(model.listStatements(stmt.getSubject(), inverseRoleName, (RDFNode) null).next().getObject().toString());
                                                String assocPropName = assocProp.getLocalName();
                                                String assocPropNS = assocProp.getNameSpace();
                                                if (options.isBaseprofilesshaclglag()) {
                                                    inverseAssocSet.add(assocProp.toString());
                                                    //add the 3 cim namespaces if it is cim namespace
                                                    if (assocPropNS.equals("http://iec.ch/TC57/CIM100#") || assocPropNS.equals("http://iec.ch/TC57/2013/CIM-schema-cim16#") || assocPropNS.equals("https://cim.ucaiug.io/ns#")) {
                                                        inverseAssocSet.add("http://iec.ch/TC57/CIM100#" + assocPropName);
                                                        inverseAssocSet.add("http://iec.ch/TC57/2013/CIM-schema-cim16#" + assocPropName);
                                                        inverseAssocSet.add("https://cim.ucaiug.io/ns#" + assocPropName);
                                                    }
                                                } else {
                                                    inverseAssocSet.add(assocProp.toString());
                                                }
                                                propertyNodeFeatures.set(10, inverseAssocSet); // this is a list of role names
                                                //propertyNodeFeatures.set(10, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString()); // this is the inverse role name
                                                propertyNodeFeatures.set(11, "Inverse");

                                                String propertyFullURI = stmt.getSubject().getURI();

                                                shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                                            }
                                        }
                                    } else {//if it is an attribute


                                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                        String localNameAttr = stmt.getSubject().getLocalName();
                                        String propertyFullURI = stmt.getSubject().getURI();

//                                if (localNameAttr.equals("Location.mainAddress")){
//                                    int k1=1;
//                                }
                                        //need to check if the class has any subclasses in the base profiles, if it has then the property shape should appear on the subclasses
                                        if (options.isExcludeMRID()) { // user selects that mRID should be skipped for description classes
                                            if (classDescripStereo) { //the class is stereotyped with description
                                                if (!localNameAttr.equals("IdentifiedObject.mRID")) { //the attribute is not mRID
                                                    shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                                    cardGroupOrder = cardGroupOrder + 1;
                                                    datatypeGroupOrder = datatypeGroupOrder + 1;
                                                }
                                            } else {
                                                shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                                cardGroupOrder = cardGroupOrder + 1;
                                                datatypeGroupOrder = datatypeGroupOrder + 1;
                                            }
                                        } else {
                                            shapeModel = addPropertyNodeForAttributeSingleNew(shapeModel, propertyNodeFeatures, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI, cardGroupOrder, datatypeGroupOrder, model, stmt, multiplicity);
                                            cardGroupOrder = cardGroupOrder + 1;
                                            datatypeGroupOrder = datatypeGroupOrder + 1;
                                        }

                                        //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                                        // for each attribute of the compound
                                        if (model.listStatements(stmt.getSubject(), dataType, (RDFNode) null).hasNext()) {
                                            Resource datatypeRes = model.getRequiredProperty(stmt.getSubject(), dataType).getObject().asResource();
                                            if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()) {
                                                shapeModel = addPropertyNodeForAttributeCompoundNew(shapeModel, propertyNodeFeatures, model, nsURIprofile, nodeShapeResource, stmt.getSubject(), datatypeRes);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        //Clean NodeShapes that do not have sh:property
        List<Statement> statementsToDelete = new LinkedList<>();
        for (StmtIterator i = shapeModel.listStatements(null, RDF.type, SH.NodeShape); i.hasNext(); ) {
            Statement stmtNodeShape = i.next();
            if (!shapeModel.listStatements(stmtNodeShape.getSubject(), SH.property, (RDFNode) null).hasNext()) {
                for (StmtIterator j = shapeModel.listStatements(stmtNodeShape.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                    Statement stmtNodeShapeDelete = j.next();
                    statementsToDelete.add(stmtNodeShapeDelete);
                }
            }
        }
        shapeModel.remove(statementsToDelete);

        //cleam rdf list that are not referenced
        List<Statement> statementsToDeleteBlank = new LinkedList<>();
        for (StmtIterator i = shapeModel.listStatements(); i.hasNext(); ) {
            Statement stmtNodeShape = i.next();
            if (stmtNodeShape.getSubject().isAnon()) {
                if (!shapeModel.listStatements(null, null, stmtNodeShape.getSubject()).hasNext()) {
                    for (StmtIterator j = shapeModel.listStatements(stmtNodeShape.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                        Statement stmtNodeShapeDel = j.next();
                        statementsToDeleteBlank.add(stmtNodeShapeDel);
                    }
                }
            }
        }
        shapeModel.remove(statementsToDeleteBlank);

        return shapeModel;
    }

    //add owl:imports
    private Model addOWLimports(Model shapeModel, String baseURI, String owlImports) {
        if (!owlImports.isEmpty()) {
            String[] listImports = owlImports.split(",");
            for (String listImport : listImports) {
                //creates the resource
                Resource r = shapeModel.createResource(baseURI);
                //creates the object
                RDFNode o = shapeModel.createResource(listImport.replace(" ", ""));
                //adds the property
                r.addProperty(OWL2.imports, o);
            }
        }
        return shapeModel;

        //TODO: below is important to be used before model validation - to be put in another method
        //this below seems actually for the combining models and not to write the owl:imports
        //shapeModel = SHACLUtil.createIncludesModel((Model) shapeModel,fowlImportsDefineTab.getText());
        //shapeModels.set(m,shapeModel);
    }

    //add header to SHACL
    private Model addSHACLheader(Model shapeModel, String baseURI) {

        Resource shaclHeaderRes = ResourceFactory.createResource(baseURI + "#Ontology");
        shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, RDF.type, OWL2.Ontology));

        for (Statement stmt : rdfsHeaderStatements) {
            if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#title")) || stmt.getPredicate().equals(DCTerms.title)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.title, stmt.getObject()));
            } else if (stmt.getPredicate().equals(OWL2.versionIRI)) {
                int len = Arrays.asList(stmt.getObject().toString().split("/")).size();
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createProperty(baseURI + "/" + Arrays.asList(stmt.getObject().toString().split("/")).get(len - 1))));
            } else if (stmt.getPredicate().equals(DCAT.keyword)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(DCAT.theme)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createLangLiteral("constraint", "en")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#license")) || stmt.getPredicate().equals(DCTerms.license)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.license, stmt.getObject()));
            } else if (stmt.getPredicate().equals(OWL2.versionInfo)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#rightsHolder")) || stmt.getPredicate().equals(DCTerms.rightsHolder)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.rightsHolder, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#conformsTo")) || stmt.getPredicate().equals(DCTerms.conformsTo)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.conformsTo, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#description")) || stmt.getPredicate().equals(DCTerms.description)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.description, ResourceFactory.createPlainLiteral("Describing constraints extracted from RDFS.")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#modified")) || stmt.getPredicate().equals(DCTerms.modified)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.modified, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#language")) || stmt.getPredicate().equals(DCTerms.language)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.language, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#publisher")) || stmt.getPredicate().equals(DCTerms.publisher)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.publisher, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#identifier")) || stmt.getPredicate().equals(DCTerms.identifier)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.identifier, ResourceFactory.createPlainLiteral("urn:uuid" + UUID.randomUUID())));
            }
        }
        shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.issued, ResourceFactory.createTypedLiteral(String.valueOf(LocalDateTime.now()), XSDDatatype.XSDdateTime)));
        return shapeModel;
    }

    //get owl:imports
    private String getOWLimports(Model shapeModel, String baseURI) {
        String owlImports = "";
        if (shapeModel.contains(shapeModel.getResource(baseURI), OWL2.imports)) {
            int count = 0;
            for (NodeIterator res = shapeModel.listObjectsOfProperty(shapeModel.getResource(baseURI), OWL2.imports); res.hasNext(); ) {
                if (count == 0) {
                    owlImports = res.next().toString();
                } else {
                    owlImports = owlImports + ", " + res.next().toString();
                }
                count++;
            }
        }
        return owlImports;
    }

    private Boolean existsInBase(Resource resItem, Map<String, Model> baseTier1Map, Map<String, Model> baseTier2Map, Map<String, Model> baseTier3Map) {

        boolean exixtsInBase = false;

        //if (options.isBaseprofilesshaclglag()) {
        if (baseTier1Map.get("unionmodelbaseprofilesshacl").listStatements(resItem, RDF.type, RDFS.Class).hasNext()) {
            exixtsInBase = true;
        }
        //}

        if (options.isBaseprofilesshaclglag2nd()) {
            if (baseTier2Map.get("unionmodelbaseprofilesshacl").listStatements(resItem, RDF.type, RDFS.Class).hasNext()) {
                exixtsInBase = true;
            }
        }

        if (options.isBaseprofilesshaclglag3rd()) {
            if (baseTier3Map.get("unionmodelbaseprofilesshacl").listStatements(resItem, RDF.type, RDFS.Class).hasNext()) {
                exixtsInBase = true;
            }
        }


        return exixtsInBase;
    }

    //add a PropertyGroup to a shape model including all necessary properties
    private Model addPropertyGroup(Model shapeModel, String nsURIprofile, String localName, ArrayList<Object> groupFeatures) {
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the PropertyGroup
         * localName - is the name of the PropertyGroup
         * rdfURI - is the URI of the rdf
         * shaclURI - is the URI of the shacl
         * groupFeatures / is a structure with the details
         * rdfsURI - is the URI of the rdfs
         */
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "PropertyGroup");
        //adds the property
        r.addProperty(RDF.type, SH.PropertyGroup);
        //up to here this defines e.g. eqbd:MyGroup a sh:PropertyGroup ;

        //Property p1 = shapeModel.createProperty(shaclURI, "order");
        RDFNode o1 = shapeModel.createTypedLiteral(groupFeatures.get(3), "http://www.w3.org/2001/XMLSchema#integer");
        r.addProperty(SH.order, o1);

        //Property p2 = shapeModel.createProperty(shaclURI, "name");
        //r.addProperty(SH.name, groupFeatures.get(0).toString()); // this is ok if the group needs to be a name

        //Property p3 = shapeModel.createProperty(shaclURI, "description");
        //r.addProperty(SH.description, groupFeatures.get(1).toString()); // this is ok if the group needs to be a description

        //Property p4 = shapeModel.createProperty(rdfsURI, "label");
        //creates the object which is the CIM class
        r.addProperty(RDFS.label, groupFeatures.get(2).toString());

        //RDFDataMgr.write(System.out, shapeModel, RDFFormat.TURTLE);
        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties for Class check
    private Model addNodeShapeProfileClass(Model shapeModel, String nsURIprofile, String localName, String classFullURI) {
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetSubjectsOf, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties for Class Count check
    private Model addNodeShapeProfileClassCount(Model shapeModel, String nsURIprofile, String localName, String classFullURI) {
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetNode, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    private Boolean classIsConcrete(Resource resource, Model model) {
        boolean concrete = false;
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#";
        RDFNode concreteNode = ResourceFactory.createProperty(concreteNs, "concrete");
        Property cimsStereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");

        if (model.listStatements(resource, cimsStereotype, concreteNode).hasNext()) {
            concrete = true;
        }

        return concrete;
    }

    private Boolean classIsNotEnumOrDatatype(Resource resource, Model model) {
        boolean notEnumOrDT = false;

        RDFNode enumeration = ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration");
        RDFNode primitive = ResourceFactory.createPlainLiteral("Primitive");
        RDFNode datatype = ResourceFactory.createPlainLiteral("CIMDatatype");
        RDFNode compound = ResourceFactory.createPlainLiteral("Compound");
        Property cimsStereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");

        if (!model.listStatements(resource, cimsStereotype, enumeration).hasNext() &&
                !model.listStatements(resource, cimsStereotype, primitive).hasNext() &&
                !model.listStatements(resource, cimsStereotype, datatype).hasNext() &&
                !model.listStatements(resource, cimsStereotype, compound).hasNext()) {
            notEnumOrDT = true;
        }

        return notEnumOrDT;
    }

    private Boolean classIsDescription(Resource resource, Model model) {
        boolean description = false;
        RDFNode descriptionStereotype = ResourceFactory.createPlainLiteral("Description");
        Property cimsStereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");

        if (model.listStatements(resource, cimsStereotype, descriptionStereotype).hasNext()) {
            description = true;
        }

        return description;
    }

    private Boolean classHasAttribute(Resource resource, Model model) {

        return model.listStatements(null, RDFS.domain, resource).hasNext();
    }

    private LinkedList<Resource> getConcreteSubclassesFromBase(Resource resItem, Map<String, Model> baseTier1Map, Map<String, Model> baseTier2Map, Map<String, Model> baseTier3Map) {

        LinkedList<Resource> concreteclasses = new LinkedList<>();

        if (options.isBaseprofilesshaclglag()) {
            LinkedList<Resource> subclassResources = new LinkedList<>();
            subclassResources = getAllSubclassesResource(baseTier1Map.get("unionmodelbaseprofilesshacl"), resItem, subclassResources);
            for (Resource res : subclassResources) {
                if (classIsConcrete(res, baseTier1Map.get("unionmodelbaseprofilesshacl"))) {
                    if (!concreteclasses.contains(res)) {
                        concreteclasses.add(res);
                    }
                }
            }
        }

        if (options.isBaseprofilesshaclglag2nd()) {
            LinkedList<Resource> subclassResources = new LinkedList<>();
            subclassResources = getAllSubclassesResource(baseTier2Map.get("unionmodelbaseprofilesshacl"), resItem, subclassResources);
            for (Resource res : subclassResources) {
                if (classIsConcrete(res, baseTier2Map.get("unionmodelbaseprofilesshacl"))) {
                    if (!concreteclasses.contains(res)) {
                        concreteclasses.add(res);
                    }
                }
            }
        }

        if (options.isBaseprofilesshaclglag3rd()) {
            LinkedList<Resource> subclassResources = new LinkedList<>();
            subclassResources = getAllSubclassesResource(baseTier3Map.get("unionmodelbaseprofilesshacl"), resItem, subclassResources);
            for (Resource res : subclassResources) {
                if (classIsConcrete(res, baseTier3Map.get("unionmodelbaseprofilesshacl"))) {
                    if (!concreteclasses.contains(res)) {
                        concreteclasses.add(res);
                    }
                }
            }
        }

        if (options.isBaseprofilesshaclignorens()) {//check if the class exists in the base profiles
            //get all classes and check their local name
            for (StmtIterator i = baseTier1Map.get("unionmodelbaseprofilesshaclinheritanceonly").listStatements(null, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                Statement stmtinheritanceign = i.next();
                if (stmtinheritanceign.getSubject().getLocalName().equals(resItem.getLocalName())) {
                    if (!concreteclasses.contains(stmtinheritanceign.getObject().asResource())) {
                        concreteclasses.add(stmtinheritanceign.getObject().asResource());
                    }
                }
            }
        }

        return concreteclasses;
    }

    private Boolean classIsRangeForInverseAssociationEnd(Resource resource, Model model) {
        boolean association = false;
        RDFNode literalNO = ResourceFactory.createPlainLiteral("No");
        Property assocUsed = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed");

        if (model.listStatements(null, RDFS.range, resource).hasNext()) { // has properties
            for (StmtIterator i = model.listStatements(null, RDFS.range, resource); i.hasNext(); ) {
                Statement stmt = i.next();
                if (model.listStatements(stmt.getSubject(), assocUsed, literalNO).hasNext()) {
                    association = true;
                    break;
                }
            }
        }

        return association;
    }

    //get all resources of subclasses
    private LinkedList<Resource> getAllSubclassesResource(Model model, Resource parentClassRes, LinkedList<Resource> subclassResource) {

        if (model.listStatements(parentClassRes, RDF.type, RDFS.Class).hasNext()) {
            for (StmtIterator j = model.listStatements(null, RDFS.subClassOf, (RDFNode) null); j.hasNext(); ) {
                Statement stmtB = j.next();
                if (stmtB.getObject().asResource().getLocalName().equals(parentClassRes.getLocalName())) {
                    subclassResource.add(stmtB.getSubject());
                    subclassResource = getAllSubclassesResource(model, stmtB.getSubject(), subclassResource);
                }
            }
        }
        return subclassResource;
    }

    //add a NodeShape to a shape model including all necessary properties
    private Model addNodeShape(Model shapeModel, String nsURIprofile, String localName, String classFullURI) {
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetClass, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    private Property asProperty(Resource resource) {
        return (Property) (resource instanceof Property ? (Property) resource : new PropertyImpl(resource.asNode(), (EnhGraph) resource.getModel()));
    }

    //add a PropertyNode to a shape model including all necessary properties
    private Model addPropertyNode(Model shapeModel, Resource nodeShapeResource, ArrayList<Object> propertyNodeFeatures, String nsURIprofile, String localName, String propertyFullURI) {
        /*
         * propertyNodeFeatures structure
         * 0 - type of check: cardinality, datatype, associationValueType
         * 1 - message
         * 2 - name
         * 3 - description
         * 4 - severity
         * 5 - cardinality
         * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
         * in case of enumeration 6 is set to Enumeration
         * in case of compound 6 is set to the compound class
         * 7 - is a list of uri of the enumeration attributes
         * 8 - order
         * 9 - group
         * 10 - - the list of concrete classes for association - the value type at the used end
         * 11 - classFullURI for the targetClass of the NodeShape
         * 12 - the uri of the compound class to be used in sh:class
         */
        String checkType = propertyNodeFeatures.getFirst().toString();
        String multiplicity = "";
        int lowerBound = 0;
        int upperBound = 0;
        switch (checkType) {
            case "cardinality":
                String cardinality = propertyNodeFeatures.get(5).toString();
                if (cardinality.length() == 1) {
                    //need to have sh:minCount 1 ; and sh:maxCount 1 ;
                    multiplicity = "required";
                    lowerBound = 1;
                    upperBound = 1;
                    shapeModel = addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);

                } else if (cardinality.length() == 4) {
                    multiplicity = "seeBounds";
                    lowerBound = 0;
                    upperBound = 0;

                    if (Character.isDigit(cardinality.charAt(0))) {
                        lowerBound = Character.getNumericValue(cardinality.charAt(0));
                        //propertyNodeFeatures.set(1,"Cardinality violation. Lower bound shall be "+lowerBound);
                    }
                    if (Character.isDigit(cardinality.charAt(3))) {
                        upperBound = Character.getNumericValue(cardinality.charAt(3));
                        //propertyNodeFeatures.set(1,"Cardinality violation. Upper bound shall be "+upperBound);
                    } else {
                        upperBound = 999; // means that no upper bound is defined when we have upper bound "to many"
                    }
             /*   if (lowerBound!=1 && upperBound!=1) {//is they are the same 1..1 "Missing required association" is used
                    propertyNodeFeatures.set(1, "Cardinality violation. Cardinality shall be " + cardinality);
                }else if (lowerBound!=1 && upperBound!=1) {
                }else if (lowerBound!=1 && upperBound!=1) {
                }*/
                    if (lowerBound != 0 && upperBound != 999) { // covers 1..1 x..y excludes 0..n
                        if (lowerBound != 1 && upperBound != 1) {//is they are the same 1..1 "Missing required association" is used
                            propertyNodeFeatures.set(1, "Cardinality violation. Cardinality shall be " + cardinality);
                        }
                        shapeModel = addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    } else if (lowerBound == 0 && upperBound != 999) {//need to cover 0..x
                        propertyNodeFeatures.set(1, "Cardinality violation. Upper bound shall be " + upperBound);
                        shapeModel = addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    } else if (lowerBound != 0 && upperBound == 999) {//need to cover x..n
                        propertyNodeFeatures.set(1, "Cardinality violation. Lower bound shall be " + lowerBound);
                        shapeModel = addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    }

                }
                break;
            case "datatype":
                //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
                //just linked with the nodeshape
                String nsURIprofileID = "";
                if (localName.contains("IdentifiedObject")) {
                    nsURIprofileID = options.getiOuri();
                } else {
                    nsURIprofileID = nsURIprofile;
                }

                if (shapeModel.containsResource(shapeModel.getResource(nsURIprofileID + localName + "-datatype"))) {
                    //adding the reference in the NodeShape
                    //Property p = shapeModel.createProperty(shaclURI, "property");
                    //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
                    RDFNode o = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    nodeShapeResource.addProperty(SH.property, o);
                } else {
                    //adding the reference in the NodeShape
                    //Property p = shapeModel.createProperty(shaclURI, "property");
                    //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
                    RDFNode o = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    nodeShapeResource.addProperty(SH.property, o);

                    //adding the properties for the PropertyShape
                    Resource r = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    //Property p1 = shapeModel.createProperty(rdfURI, "type");
                    //RDFNode o1 = shapeModel.createResource(shaclURI + "PropertyShape");
                    r.addProperty(RDF.type, SH.PropertyShape);

                    //adding the property for the sh:group
                    //Property p1g = shapeModel.createProperty(shaclURI, "group");
                    //creates the object which is the Group
                    RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                    if (localName.contains("IdentifiedObject")) {
                        o1g = shapeModel.createResource(options.getiOuri() + "DatatypesGroupIO");
                    }
                    r.addProperty(SH.group, o1g);

                    //adding a property for the order
                    //Property p1o = shapeModel.createProperty(shaclURI, "order");
                    RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                    if (localName.contains("IdentifiedObject")) {
                        o1o = shapeModel.createTypedLiteral("0.1", "http://www.w3.org/2001/XMLSchema#decimal");
                    }
                    r.addProperty(SH.order, o1o);

                    //adding a message
                    //Property p2 = shapeModel.createProperty(shaclURI, "message");
                    r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());

                    if (propertyNodeFeatures.get(13).toString().isEmpty()) {
                        RDFNode o5 = shapeModel.createResource(propertyFullURI);
                        if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
                            List<RDFNode> pathList = new ArrayList<>();
                            pathList.add(o5);
                            pathList.add(RDF.type.asResource());

                            RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                            r.addProperty(path, pathRDFlist);
                        } else {
                            //Property p5 = shapeModel.createProperty(shaclURI, "path");

                            r.addProperty(path, o5);
                        }


                    } else { // it enters here when it is  compound and the sh:path needs to be a list
                        //path for the case when the attribute is a compound
                        List<RDFNode> pathList = (List<RDFNode>) propertyNodeFeatures.get(13);
//                        if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
//                            pathList.add(RDF.type.asResource());
//                        }
                        RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                        r.addProperty(path, pathRDFlist);
                    }

                    //Property p6 = shapeModel.createProperty(shaclURI, "name");
                    r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                    //Property p7 = shapeModel.createProperty(shaclURI, "description");
                    r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                    //Property p8 = shapeModel.createProperty(shaclURI, "severity");
                    RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                    r.addProperty(SH.severity, o8);

                    //add specific properties for the datatype check
                    if (propertyNodeFeatures.get(6).toString().equals("Enumeration")) {
                        //this is the case where the datatype is enumeration
                        if (shapeModel.getProperty(shapeModel.getResource(nsURIprofile + localName + "-datatype"), SH.in) == null) {
                            //Property p9 = shapeModel.createProperty(shaclURI, "nodeKind");
                            //RDFNode o9 = shapeModel.createResource(shaclURI + "IRI");
                            r.addProperty(SH.nodeKind, SH.IRI);

                            //Property p10 = shapeModel.createProperty(shaclURI, "in");
                            List<RDFNode> enumAttributes = new ArrayList<>();
                            for (int en = 0; en < ((ArrayList<?>) propertyNodeFeatures.get(7)).size(); en++) {
                                enumAttributes.add(shapeModel.createResource(((ArrayList<?>) propertyNodeFeatures.get(7)).get(en).toString()));
                            }
                            RDFList enumRDFlist = shapeModel.createList(enumAttributes.iterator());
                            r.addProperty(SH.in, enumRDFlist);
                        }
                    } else if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
                        //this is the case where the datatype is compound

                        r.addProperty(SH.nodeKind, SH.BlankNode);
                        RDFNode oC = shapeModel.createResource(propertyNodeFeatures.get(12).toString());
                        if (options.isAssociationValueTypeOption()) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected
                            //adding sh:in
                            List<RDFNode> classIn = new ArrayList<>();
                            classIn.add(oC);

                            RDFList classInRDFlist = shapeModel.createList(classIn.iterator());
                            r.addProperty(SH.in, classInRDFlist);

                        } else {
                            r.addProperty(SH.class_, oC);
                        }

                    } else {
                        //this is the case where the datatype is a primitive or it is the primitive of the .value attribute of CIMDatatype
                        //Property p9 = shapeModel.createProperty(shaclURI, "nodeKind");
                        //RDFNode o9 = shapeModel.createResource(shaclURI + "Literal");
                        if (propertyNodeFeatures.get(6).toString().equals("URI")) {
                            if (options.isShaclURIDatatypeAsResource()) {
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }
                        } else {
                            r.addProperty(SH.nodeKind, SH.Literal);
                        }

                        //Property p10 = shapeModel.createProperty(shaclURI, "datatype");
                        //the following are all primitives part of CIM17 domain package
                        RDFNode o10 = shapeModel.createResource();
                        if (propertyNodeFeatures.get(6).toString().equals("Integer")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDinteger.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Float")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDfloat.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("String")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Boolean")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDboolean.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Date")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdate.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("DateTime")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdateTime.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("DateTimeStamp")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdateTimeStamp.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Decimal")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdecimal.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Duration")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDduration.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("MonthDay")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDgMonthDay.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Time")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDtime.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("URI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                            if (!options.isShaclURIDatatypeAsResource()) {
                                r.addProperty(SH.datatype, o10);
                            }
                        } else if (propertyNodeFeatures.get(6).toString().equals("IRI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("StringIRI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("StringFixedLanguage")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("URL")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("LangString")) {
                            o10 = shapeModel.createResource(RDFLangString.rdfLangString.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Version")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("UUID")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        }
                        if (!propertyNodeFeatures.get(6).toString().equals("URI")) {
                            r.addProperty(SH.datatype, o10);
                        }
                    }
                }
                break;
            case "associationValueType":
                if (((LinkedList<?>) propertyNodeFeatures.get(10)).size() == 1) {
                    //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
                    //just linked with the nodeshape
                    //if (localName.contains("DCLine")){
                    //    System.out.println(localName);
                    //}
//                    Resource r = null;
//                    RDFNode o9 = null;
//                    if (propertyNodeFeatures.get(2).toString().contains("AssessedElement.OverlappingZone")){
//                        int debug=2;
//                    }

                    if (options.isAssociationValueTypeOption()) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected

                        if (propertyNodeFeatures.get(2).toString().endsWith("-nodeKind")) {
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-nodeKind"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResource.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResource.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //the 09 is the class - value type
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());


                                r.addProperty(SH.message, "The node kind shall be IRI (rdf:resource is expected).");
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }
                        } else {
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-valueType"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResource.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResource.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //the 09 is the class - value type
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString())
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                        + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                                if (options.isAssociationValueTypeOptionSingle()) {
                                    //adding path
                                    RDFNode o5path = shapeModel.createResource(propertyFullURI);
                                    r.addProperty(path, o5path);


                                    //adding the sh:class

                                    r.addProperty(SH.class_, o9);

                                } else {
                                    //adding path
                                    List<RDFNode> pathList = new ArrayList<>();
                                    pathList.add(shapeModel.createResource(propertyFullURI));
                                    pathList.add(RDF.type.asResource());

                                    RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                                    r.addProperty(path, pathRDFlist);
                                    //adding sh:in
                                    List<RDFNode> classIn = new ArrayList<>();
                                    classIn.add(o9);
                                    RDFList classInRDFlist = shapeModel.createList(classIn.iterator());
                                    r.addProperty(SH.in, classInRDFlist);
                                }

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }

                        }

                    } else {
                        if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-valueType"))) {
                            //adding the reference in the NodeShape
                            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            nodeShapeResource.addProperty(SH.property, o);
                        } else {
                            //adding the reference in the NodeShape
                            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            nodeShapeResource.addProperty(SH.property, o);

                            //adding the properties for the PropertyShape
                            Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            r.addProperty(RDF.type, SH.PropertyShape);

                            //adding the property for the sh:group
                            //creates the object which is the Group
                            RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                            r.addProperty(SH.group, o1g);

                            //adding a property for the order
                            RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                            r.addProperty(SH.order, o1o);

                            //adding name
                            r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                            //adding description
                            r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                            //adding severity
                            RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                            r.addProperty(SH.severity, o8);

                            //the 09 is the class - value type
                            RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());

                            //adding path
                            RDFNode o5 = shapeModel.createResource(propertyFullURI);
                            r.addProperty(path, o5);

                            //adding the sh:class

                            r.addProperty(SH.class_, o9);

                            //adding a message
                            //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                            r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                    + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                            //adding sh:nodeKind
                            r.addProperty(SH.nodeKind, SH.IRI);
                        }
                    }

                } else {
                    //in case there are multiple concrete classes that inherit the association
                    if (options.isAssociationValueTypeOption()) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected

                        String classFullURI = propertyNodeFeatures.get(11).toString();

                        shapeModel = addNodeShapeValueType(shapeModel, nsURIprofile, localName, classFullURI);


                        if (propertyNodeFeatures.get(2).toString().endsWith("-nodeKind")) {
                            Resource nodeShapeResourceValueType = shapeModel.getResource(nsURIprofile + localName + "-valueTypeNodeShape");

                            if (shapeModel.containsResource(shapeModel.createResource(nsURIprofile + localName + "-nodeKind"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResourceValueType.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResourceValueType.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding path
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "The node kind shall be IRI (rdf:resource is expected).");


                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);

                            }
                        } else {
                            Resource nodeShapeResourceValueType = shapeModel.getResource(nsURIprofile + localName + "-valueTypeNodeShape");

                            if (shapeModel.containsResource(shapeModel.createResource(nsURIprofile + localName + "-valueType"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResourceValueType.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResourceValueType.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding path
                                List<RDFNode> pathList = new ArrayList<>();
                                pathList.add(shapeModel.createResource(propertyFullURI));
                                pathList.add(RDF.type.asResource());

                                RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                                r.addProperty(path, pathRDFlist);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "One of the following occurs: 1) The value is not IRI; 2) The value is not the right class.");

                                //adding sh:in
                                List<RDFNode> classNames = new ArrayList<>();
                                for (int item = 0; item < ((LinkedList<?>) propertyNodeFeatures.get(10)).size(); item++) {
                                    classNames.add(shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(item).toString()));
                                }

                                RDFList classInRDFlist = shapeModel.createList(classNames.iterator());
                                r.addProperty(SH.in, classInRDFlist);


                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }
                        }


                    } else { // in case the option "Use sh:in for association value type constraint instead of sh:class and sh:or" is not selected
                        List<RDFNode> classNames = new ArrayList<>();
                        for (int item = 0; item < ((LinkedList<?>) propertyNodeFeatures.get(10)).size(); item++) {
                            Resource classNameRes = (Resource) ((LinkedList<?>) propertyNodeFeatures.get(10)).get(item);
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType"))) {
                                classNames.add(shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType")); // adds to the list to be used for sh:or
                            } else {
                                classNames.add(shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType")); // adds to the list to be used for sh:or
                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding path
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding name
                                r.addProperty(SH.name, localName + classNameRes.getLocalName() + "-valueType");//propertyNodeFeatures.get(2).toString()

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding the sh:class
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(item).toString());
                                r.addProperty(SH.class_, o9);

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                        + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                            }
                        }

                        String classFullURI = propertyNodeFeatures.get(11).toString();

                        shapeModel = addNodeShapeValueType(shapeModel, nsURIprofile, localName, classFullURI);
                        //RDFList orRDFlist = shapeModel.createList(classNames.iterator());
                        Resource nodeShapeResourceOr = shapeModel.getResource(nsURIprofile + localName + "-valueType");
                        if (!shapeModel.getResource(nodeShapeResourceOr.toString()).hasProperty(SH.or)) { // creates sh:or only if it is missing
                            RDFList orRDFlist = shapeModel.createList(classNames.iterator());
                            nodeShapeResourceOr.addProperty(SH.or, orRDFlist);
                        }
                    }
                }
                break;
        }

        return shapeModel;
    }

    private Model addPropertyNodeCardinality(Model shapeModel, Resource nodeShapeResource, ArrayList<Object> propertyNodeFeatures, String nsURIprofile, String localName, String propertyFullURI, String multiplicity, Integer lowerBound, Integer upperBound) {
        /*
         * propertyNodeFeatures structure
         * 0 - type of check: cardinality, datatype
         * 1 - message
         * 2 - name
         * 3 - description
         * 4 - severity
         * 5 - cardinality
         * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
         * in case of enumeration 6 is set to Enumeration
         * 7 - is a list of uri of the enumeration attributes
         * 8 - order
         * 9 - group
         */
        //The if here is to ensure that all property shapes for IdentifiedObjects are in one namespace
        if (localName.contains("IdentifiedObject")) {
            nsURIprofile = options.getiOuri();
        }

        //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
        //just linked with the nodeshape
        if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-cardinality"))) {
            //adding the reference in the NodeShape
            //Property p = shapeModel.createProperty(shaclURI, "property");
            //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-cardinality");
            nodeShapeResource.addProperty(SH.property, o);

        } else {
            //adding the reference in the NodeShape
            //Property p = shapeModel.createProperty(shaclURI, "property");
            //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-cardinality");
            nodeShapeResource.addProperty(SH.property, o);

            //adding the properties for the PropertyShape
            Resource r = shapeModel.createResource(nsURIprofile + localName + "-cardinality");
            //Property p1 = shapeModel.createProperty(rdfURI, "type");
            //RDFNode o1 = shapeModel.createResource(shaclURI + "PropertyShape");
            r.addProperty(RDF.type, SH.PropertyShape);

            //adding the property for the sh:group
            //Property p1g = shapeModel.createProperty(shaclURI, "group");
            //creates the object which is the Group
            RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
            if (localName.contains("IdentifiedObject")) {
                o1g = shapeModel.createResource(options.getiOuri() + "CardinalityIO");
            }
            r.addProperty(SH.group, o1g);

            //adding a property for the order
            //Property p1o = shapeModel.createProperty(shaclURI, "order");
            RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
            if (localName.contains("IdentifiedObject")) {
                o1o = shapeModel.createTypedLiteral("0.1", "http://www.w3.org/2001/XMLSchema#decimal");
            }
            r.addProperty(SH.order, o1o);

            if (multiplicity.equals("required")) {
                //Property p3 = shapeModel.createProperty(shaclURI, "minCount");
                RDFNode o3 = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
                r.addProperty(SH.minCount, o3);
                //Property p4 = shapeModel.createProperty(shaclURI, "maxCount");
                RDFNode o4 = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
                r.addProperty(SH.maxCount, o4);
            } else if (multiplicity.equals("seeBounds")) {
                if (lowerBound < 999 && lowerBound != 0) {
                    //Property p3 = shapeModel.createProperty(shaclURI, "minCount");
                    RDFNode o3 = shapeModel.createTypedLiteral(lowerBound, "http://www.w3.org/2001/XMLSchema#integer");
                    r.addProperty(SH.minCount, o3);
                }
                if (upperBound < 999 && upperBound != 0) {
                    //Property p4 = shapeModel.createProperty(shaclURI, "maxCount");
                    RDFNode o4 = shapeModel.createTypedLiteral(upperBound, "http://www.w3.org/2001/XMLSchema#integer");
                    r.addProperty(SH.maxCount, o4);
                }
            }

            //adding a message
            //Property p2 = shapeModel.createProperty(shaclURI, "message");
            r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());

            if (propertyNodeFeatures.get(13).toString().isEmpty() && !propertyNodeFeatures.get(11).toString().equals("Inverse")) {
                //Property p5 = shapeModel.createProperty(shaclURI, "path");
                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                r.addProperty(path, o5);
            } else if (!propertyNodeFeatures.get(13).toString().isEmpty()) { // it enters here when it is  compound and the sh:path needs to be a list
                //path for the case when the attribute is a compound
                List<RDFNode> pathList = (List<RDFNode>) propertyNodeFeatures.get(13);
                RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                r.addProperty(path, pathRDFlist);
                //Resource nodeShapeResourcePath = shapeModel.getResource(nsURIprofile + localName+"Cardinality");
                //if (!shapeModel.getResource(nodeShapeResourcePath.toString()).hasProperty(SH.path)) { // creates sh:path only if it is missing
                //    nodeShapeResourcePath.addProperty(SH.path, pathRDFlist);
                //}
            } else if (propertyNodeFeatures.get(11).toString().equals("Inverse")) {
                Set<String> propSet = (Set<String>) propertyNodeFeatures.get(10);
                if (propSet.size() == 1) {
                    Resource resbn = ResourceFactory.createResource();
                    for (String s : propSet) {
                        Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(s));
                        r.addProperty(path, asProperty(resbn));
                        shapeModel.add(stmtbn);
                    }
                } else {
                    //sh:path         [sh:alternativePath ([sh:inversePath  cim:ContingencyElement.Contingency] [sh:inversePath  cim17:ContingencyElement.Contingency])] ;
                    Resource resbnAP = ResourceFactory.createResource();
                    List<Property> invPathList = new LinkedList<>();
                    for (String s : propSet) {
                        Resource resbn = ResourceFactory.createResource();
                        Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(s));
                        invPathList.add(asProperty(resbn));
                        shapeModel.add(stmtbn);

                    }
                    RDFList classIPRDFlist = shapeModel.createList(invPathList.iterator());
                    Statement stmtbnAP = ResourceFactory.createStatement(resbnAP, SH.alternativePath, classIPRDFlist);
                    r.addProperty(path, asProperty(resbnAP));
                    shapeModel.add(stmtbnAP);
                }

//                if (baseprofilesshaclglag == 0) {
//                    Resource resbn = ResourceFactory.createResource();
//                    Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString()));
//                    //r.addProperty(SH.path, JenaUtil.asProperty(resbn));
//                    r.addProperty(SH.path, asProperty(resbn));
//                    shapeModel.add(stmtbn);
//                }else if (baseprofilesshaclglag == 1){
//                    Resource resbn = ResourceFactory.createResource();
//                    Property classinverseFull = ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString());
//
//                    Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString()));
//                    //r.addProperty(SH.path, JenaUtil.asProperty(resbn));
//                    r.addProperty(SH.path, asProperty(resbn));
//                    shapeModel.add(stmtbn);
//                }
            }

            //Property p6 = shapeModel.createProperty(shaclURI, "name");
            r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

            //Property p7 = shapeModel.createProperty(shaclURI, "description");
            r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

            //Property p8 = shapeModel.createProperty(shaclURI, "severity");
            RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
            r.addProperty(SH.severity, o8);
        }
        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties
    private Model addNodeShapeValueType(Model shapeModel, String nsURIprofile, String localName, String classFullURI) {
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */
        String valueType;
        if (options.isAssociationValueTypeOption()) {
            valueType = "-valueTypeNodeShape";
        } else {
            valueType = "-valueType";
        }
        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName + valueType);
        r.addProperty(RDF.type, SH.NodeShape);
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetClass, o1);


        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    private Model addPropertyNodeForAttributeSingleNew(Model shapeModel, ArrayList<Object> propertyNodeFeatures, String nsURIprofile, Resource nodeShapeResource, String localNameAttr, String propertyFullURI, int cardGroupOrder, int datatypeGroupOrder, Model model, Statement stmt, Property multiplicity) {
        //Cardinality check
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
        propertyNodeFeatures.set(5, cardinality);
        propertyNodeFeatures.set(1, "Missing required property (attribute).");
        propertyNodeFeatures.set(2, localNameAttr + "-cardinality");
        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the property (attribute).");
        propertyNodeFeatures.set(4, "Violation");
        propertyNodeFeatures.set(8, cardGroupOrder + 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
        shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);
        //add datatypes checks depending on it is Primitive, Datatype or Enumeration
        propertyNodeFeatures.set(2, localNameAttr + "-datatype");
        propertyNodeFeatures.set(3, "This constraint validates the datatype of the property (attribute).");
        propertyNodeFeatures.set(0, "datatype");
        propertyNodeFeatures.set(8, datatypeGroupOrder - 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "DatatypesGroup"); // this is the group
        Resource datatypeRes = null;
        if (model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"), (RDFNode) null).hasNext()) {
            datatypeRes = model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"), (RDFNode) null).next().getObject().asResource();
        } else {
            datatypeRes = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().asResource();
        }
        if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Primitive")).hasNext()) {
            String datatypePrimitive = model.getRequiredProperty(datatypeRes, RDFS.label).getObject().asLiteral().getString(); //this is localName e.g. String

            propertyNodeFeatures.set(6, datatypePrimitive);
            propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

        } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("CIMDatatype")).hasNext()) {
            Resource datatypevalue = ResourceFactory.createProperty(datatypeRes.getURI() + ".value");

            String datatypePrimitive = model.getRequiredProperty(datatypevalue, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType")).getObject().asResource().getLocalName(); //this is localName e.g. String

            propertyNodeFeatures.set(6, datatypePrimitive);
            propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");
        } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()) {

            //String datatypeCompound = model.getRequiredProperty(datatypeRes,RDFS.label).getObject().asLiteral().getString();
            String datatypeCompound = datatypeRes.getURI();

            propertyNodeFeatures.set(6, "Compound");
            propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
            propertyNodeFeatures.set(12, datatypeCompound);
        } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#enumeration")).hasNext()) {
            propertyNodeFeatures.set(6, "Enumeration");
            propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
            //this adds the structure which is a list of possible enumerated values
            List<Resource> enumValues = model.listSubjectsWithProperty(RDF.type, datatypeRes).toList();
            List<String> enumValuesStr = new ArrayList<>();
            for (Resource enumValue : enumValues) {
                enumValuesStr.add(enumValue.toString());
            }
            propertyNodeFeatures.set(7, enumValuesStr);
        }

        shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);

        return shapeModel;
    }

    //adds the PropertyNode for attribute for compound.
    private Model addPropertyNodeForAttributeCompoundNew(Model shapeModel, ArrayList<Object> propertyNodeFeatures, Model model, String nsURIprofile, Resource nodeShapeResource, Resource stmt, Resource datatypeRes) {

        for (StmtIterator i = model.listStatements(null, RDFS.domain, datatypeRes); i.hasNext(); ) {
            Statement stmtComp = i.next();
            String localNameAttrComp = stmtComp.getSubject().getLocalName();
            String propertyFullURIComp = stmtComp.getSubject().getURI();
            shapeModel = addPropertyNodeForAttributeSingleCompoundNew(shapeModel, propertyNodeFeatures, model, nsURIprofile, 1, nodeShapeResource, localNameAttrComp, propertyFullURIComp, stmt, stmtComp.getSubject());
//            if (((ArrayList<?>) shapeDataCompound.get(atasComp)).get(8).toString().equals("Compound")) {
//                ArrayList<?> shapeDataCompoundDeep = ((ArrayList<?>) ((ArrayList<?>) shapeDataCompound.get(atasComp)).get(10));
//                shapeModel= addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompoundDeep, nsURIprofile, nodeShapeResource, localNameAttrComp,propertyFullURIComp);
//
//            }
        }

        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    private Model addPropertyNodeForAttributeSingleCompoundNew(Model shapeModel, ArrayList<Object> propertyNodeFeatures, Model model, String nsURIprofile, Integer order, Resource nodeShapeResource, String localNameAttr, String propertyFullURI, Resource stmt, Resource stmtComp) {
        //Cardinality check
        Property multiplicity = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity");
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = "";
        Resource compFullRes = ResourceFactory.createResource(propertyFullURI);
        if (model.listStatements(compFullRes, multiplicity, (RDFNode) null).hasNext()) {
            cardinality = model.listStatements(compFullRes, multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
        }
        propertyNodeFeatures.set(5, cardinality);
        propertyNodeFeatures.set(1, "Missing required attribute.");
        propertyNodeFeatures.set(2, localNameAttr + "-cardinality");
        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the property (attribute).");
        propertyNodeFeatures.set(4, "Violation");
        propertyNodeFeatures.set(8, order); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

        boolean skip = false;
        try {
            List pathCompound = new ArrayList<>();
            pathCompound.add(stmt);
            pathCompound.add(stmtComp);
            propertyNodeFeatures.set(13, pathCompound);
        } catch (Exception e) {
            skip = true;
        }
        if (skip == false) {


            shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);

            //add datatypes checks depending on it is Primitive, Datatype or Enumeration
            propertyNodeFeatures.set(2, localNameAttr + "-datatype");
            propertyNodeFeatures.set(3, "This constraint validates the datatype of the property (attribute).");
            propertyNodeFeatures.set(0, "datatype");
            propertyNodeFeatures.set(8, order); // this is the order
            propertyNodeFeatures.set(9, nsURIprofile + "DatatypesGroup"); // this is the group

            Resource datatypeRes = null;
            if (model.listStatements(stmt, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"), (RDFNode) null).hasNext()) {
                datatypeRes = model.listStatements(stmt, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"), (RDFNode) null).next().getObject().asResource();
            } else {
                datatypeRes = model.listStatements(stmt, RDFS.range, (RDFNode) null).next().getObject().asResource();
            }
            if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Primitive")).hasNext()) {
                String datatypePrimitive = model.getRequiredProperty(datatypeRes, RDFS.label).getObject().asLiteral().getString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

            } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("CIMDatatype")).hasNext()) {
                Resource datatypevalue = ResourceFactory.createProperty(datatypeRes.getURI() + ".value");

                String datatypePrimitive = model.getRequiredProperty(datatypevalue, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType")).getObject().asResource().getLocalName(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");
            } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createPlainLiteral("Compound")).hasNext()) {

                //String datatypeCompound = model.getRequiredProperty(datatypeRes,RDFS.label).getObject().asLiteral().getString();
                String datatypeCompound = datatypeRes.getURI();

                propertyNodeFeatures.set(6, "Compound");
                propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                propertyNodeFeatures.set(12, datatypeCompound);
            } else if (model.listStatements(datatypeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#enumeration")).hasNext()) {
                propertyNodeFeatures.set(6, "Enumeration");
                propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                //this adds the structure which is a list of possible enumerated values
                List<Resource> enumValues = model.listSubjectsWithProperty(RDF.type, datatypeRes).toList();
                List<String> enumValuesStr = new ArrayList<>();
                for (Resource enumValue : enumValues) {
                    enumValuesStr.add(enumValue.toString());
                }
                propertyNodeFeatures.set(7, enumValuesStr);
            }


//            switch (((ArrayList<?>) shapeData.get(atas)).get(8).toString()) {
//                case "Primitive": {
//                    String datatypePrimitive = ((ArrayList<?>) shapeData.get(atas)).get(10).toString(); //this is localName e.g. String
//
//                    propertyNodeFeatures.set(6, datatypePrimitive);
//                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");
//
//                    break;
//                }
//                case "CIMDatatype": {
//                    String datatypePrimitive = ((ArrayList<?>) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String
//
//                    propertyNodeFeatures.set(6, datatypePrimitive);
//                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");
//
//                    break;
//                }
//                case "Compound": {
//                    String datatypeCompound = ((ArrayList<?>) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String
//
//                    propertyNodeFeatures.set(6, "Compound");
//                    propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
//                    propertyNodeFeatures.set(12, datatypeCompound);
//                    break;
//                }
//                case "Enumeration":
//                    propertyNodeFeatures.set(6, "Enumeration");
//                    propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is an enumerated value which is not part of the enumeration.");
//                    //this adds the structure which is a list of possible enumerated values
//                    propertyNodeFeatures.set(7, ((ArrayList<?>) shapeData.get(atas)).get(10));
//                    break;
//            }
            shapeModel = addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);
            propertyNodeFeatures.set(13, ""); // in order to be empty for the next attribute
        }
        return shapeModel;
    }

    private Boolean isClassConcreteInBase(Resource resItem, Map<String, Model> baseTier1Map, Map<String, Model> baseTier2Map, Map<String, Model> baseTier3Map) {

        boolean isConcreteInBase = false;

        //if (options.isBaseprofilesshaclglag()) {
        if (classIsConcrete(resItem, baseTier1Map.get("unionmodelbaseprofilesshacl"))) {
            isConcreteInBase = true;
        }
        //}

        if (options.isBaseprofilesshaclglag2nd()) {
            if (classIsConcrete(resItem, baseTier2Map.get("unionmodelbaseprofilesshacl"))) {
                isConcreteInBase = true;
            }
        }

        if (options.isBaseprofilesshaclglag3rd()) {
            if (classIsConcrete(resItem, baseTier3Map.get("unionmodelbaseprofilesshacl"))) {
                isConcreteInBase = true;
            }
        }


        return isConcreteInBase;
    }
}
