package eu.griddigit.cimpal.core.utils;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModelFactory {

    public static LinkedList<String> zipfilesnames;


    public static class InheritanceResult {
        public final Model processedModel;
        public final Model inheritanceModel;
        public InheritanceResult(Model processedModel, Model inheritanceModel) {
            this.processedModel = processedModel;
            this.inheritanceModel = inheritanceModel;
        }
    }

    //Loads one or many models
    public static Map<String, Model> modelLoad(
            List<File> files, String xmlBase,Lang rdfSourceFormat, boolean isSHACL, boolean treeID) throws IOException {

        // Thread-safe map for individual models
        ConcurrentMap<String, Model> modelMap = new ConcurrentHashMap<>();

        // Thread-safe union models
        Model unionModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Model unionNoHeader = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        // Lock objects to safely modify shared union models
        Object unionLock = new Object();
        Object unionNoHeaderLock = new Object();

        files.parallelStream().forEach(file -> {
            String ext = FilenameUtils.getExtension(file.getName()).toLowerCase();
            boolean isZip = ext.equals("zip");
            Lang format;

            List<InputStream> streams;
            try {
                if (isZip) {
                    streams = unzip(file);
                    format = Lang.RDFXML;
                } else {
                    streams = List.of(new FileInputStream(file));
                    format = getLangFromExtension(ext, rdfSourceFormat);
                }

                for (int i = 0; i < streams.size(); i++) {
                    try (InputStream in = streams.get(i)) {
                        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                        RDFDataMgr.read(model, in, xmlBase, format);

                        // Merge prefixes (deferred until after parallel loop)
                        String keyword = getProfileKeyword(model);
                        if ("FileHeader.rdf".equalsIgnoreCase(file.getName())) keyword = "FH";
                        String key = buildModelKey(file, keyword, isZip, i, treeID);

                        modelMap.put(key, model);

                        synchronized (unionLock) {
                            unionModel.add(model);
                            unionModel.setNsPrefixes(model);
                        }
                        if (!"FH".equals(keyword)) {
                            synchronized (unionNoHeaderLock) {
                                unionNoHeader.add(model);
                                unionNoHeader.setNsPrefixes(model);
                            }
                        }

                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        // Post-process final models
        Map<String, Model> result = new HashMap<>(modelMap);

        if (isSHACL) {
            Model shaclModel = ShapeFactory.createShapeModelWithOwlImport(unionModel);
            result.put("shacl", shaclModel);
        } else {
            result.put("unionModel", unionModel);
            result.put("modelUnionWithoutHeader", unionNoHeader);
        }

        return result;
    }

    public static Map<String, Model> modelLoadPerFiles(List<File> files, String xmlBase, Lang defaultLang) throws IOException {
        ConcurrentMap<String, Model> result = new ConcurrentHashMap<>();

        files.parallelStream().forEach(file -> {
            try {
                String ext = FilenameUtils.getExtension(file.getName()).toLowerCase(Locale.ROOT);
                boolean isZip = "zip".equals(ext);

                if (isZip) {
                    try (ZipFile zipFile = new ZipFile(file)) {
                        Path parentDir = file.getParentFile() != null
                                ? file.getParentFile().toPath().toAbsolutePath()
                                : Paths.get("").toAbsolutePath();

                        // Collect all valid entries first
                        List<ZipEntry> validEntries = new ArrayList<>();
                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory()) {
                                validEntries.add(entry);
                            }
                        }

                        // Process entries in parallel
                        validEntries.parallelStream().forEach(entry -> {
                            try {
                                String entryName = entry.getName();
                                Path destPath = parentDir.resolve(entryName).normalize();
                                if (!destPath.startsWith(parentDir)) {
                                    throw new IOException("Invalid zip entry path (possible zip-slip): " + entryName);
                                }

                                String entryExt = FilenameUtils.getExtension(entryName).toLowerCase(Locale.ROOT);
                                Lang lang = getLangFromExtension(entryExt, defaultLang);

                                try (InputStream in = zipFile.getInputStream(entry)) {
                                    Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                                    RDFDataMgr.read(model, in, xmlBase, lang);
                                    result.put(entryName, model);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                } else {
                    Lang lang = getLangFromExtension(ext, defaultLang);
                    try (InputStream in = new FileInputStream(file)) {
                        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                        RDFDataMgr.read(model, in, xmlBase, lang);
                        result.put(file.getName(), model);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        return new HashMap<>(result);
    }

    private static Lang getLangFromExtension(String ext, Lang fallback) {
        return switch (ext) {
            case "rdf", "xml" -> Lang.RDFXML;
            case "ttl" -> Lang.TURTLE;
            case "jsonld" -> Lang.JSONLD;
            default -> fallback;
        };
    }

    private static String buildModelKey(File file, String keyword, boolean isZip, int index, boolean treeID) {
        if (keyword == null || keyword.isEmpty()) {
            return FilenameUtils.getName(file.getName());
        }

        if (treeID) {
            if (isZip && !zipfilesnames.isEmpty()) {
                String zipName = zipfilesnames.get(Math.min(index,
                        zipfilesnames.size() - 1));
                return zipName + "|" + keyword;
            } else {
                return file.getName() + "|" + keyword;
            }
        }
        return keyword;
    }

    public static List<InputStream> unzip(File selectedFile) {
        List<InputStream> inputstreamlist = new LinkedList<>();
        zipfilesnames = new LinkedList<>();
        InputStream inputStream = null;
        try{
            ZipFile zipFile = new ZipFile(selectedFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();



            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();

                    String destPath = selectedFile.getParent() + File.separator+ entry.getName();

                    if(! isValidDestPath(selectedFile.getParent(), destPath)){
                        throw new IOException("Final file output path is invalid: " + destPath);
                    }

                    try{
                        inputStream = zipFile.getInputStream(entry);
                        inputstreamlist.add(inputStream);
                        zipfilesnames.add(entry.getName());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
            }
        } catch(IOException e){
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        return inputstreamlist;
    }

    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        // validate the destination path of a ZipFile entry,
        // and return true or false telling if it's valid or not.

        Path destPath           = Paths.get(destPathStr);
        Path destPathNormalized = destPath.normalize(); //remove ../../ etc.

        return destPathNormalized.toString().startsWith(targetDir + File.separator);
    }

    //get the keyword for the profile
    public static String getProfileKeyword(Model model) {

        String keyword="";


        if (model.listObjectsOfProperty(DCAT.keyword).hasNext()){
            keyword=model.listObjectsOfProperty(DCAT.keyword).next().toString();
        }
        if (model.listObjectsOfProperty(ResourceFactory.createProperty(DCAT.NS,"Model.keyword")).hasNext()){
            keyword=model.listObjectsOfProperty(ResourceFactory.createProperty(DCAT.NS,"Model.keyword")).next().toString();
        }

        if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))){
            keyword=model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            keyword="EQBD";
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            //TODO maybe fix RDFS. Here a quick override
            keyword="TPBD";
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).hasNext()){
            List<RDFNode> profileString=model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).toList();
            for (RDFNode node: profileString){
                String nodeString=node.toString();
                if (nodeString.equals("http://entsoe.eu/CIM/EquipmentCore/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentOperation/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1")){
                    keyword="EQ";
                }else if (nodeString.equals("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1")){
                    keyword="SSH";
                }else if (nodeString.equals("http://entsoe.eu/CIM/Topology/4/1")) {
                    keyword = "TP";
                }else if (nodeString.equals("http://entsoe.eu/CIM/StateVariables/4/1")) {
                    keyword = "SV";
                }else if (nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundary/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundaryOperation/3/1")) {
                    keyword = "EQBD";
                }else if (nodeString.equals("http://entsoe.eu/CIM/TopologyBoundary/3/1")) {
                    keyword = "TPBD";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")) {
                    keyword = "EQ";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Operation-EU/3.0")) {
                    keyword = "OP";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0")) {
                    keyword = "SC";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0")){
                    keyword="SSH";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Topology-EU/3.0")) {
                    keyword = "TP";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0")) {
                    keyword = "SV";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0")) {
                    keyword = "EQBD";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/3.0")) {
                    keyword = "DL";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/3.0")) {
                    keyword = "GL";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Dynamics-EU/1.0")) {
                    keyword = "DY";
                }
            }
        }

        if (keyword.isEmpty()){
            try{
                List<Resource> listRes=model.listSubjectsWithProperty(RDF.type).toList();
                for (Resource res : listRes){
                    if (res.getLocalName().contains("Ontology.keyword")){
                        keyword = model.getRequiredProperty(res,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().asLiteral().getString();
                    }
                }
            } catch (NullPointerException e){
                keyword = "";
            }
        }

        return keyword;
    }



    public static InheritanceResult generateInheritanceModels(
            Model model, boolean inheritanceList, boolean inheritanceListConcrete) {

        Model processed = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        processed.setNsPrefixes(model.getNsPrefixMap());

        Model inheritance = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        inheritance.setNsPrefixes(model.getNsPrefixMap());
        inheritance.setNsPrefix("owl", OWL2.NS);

        if (!inheritanceList) {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) processed.add(stmt);
            });
        } else {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) {
                    processed.add(stmt);
                    if (stmt.getPredicate().equals(RDF.type)) {
                        inheritanceStructure(stmt.getSubject(),
                                stmt.getSubject(),
                                inheritance,
                                model,
                                inheritanceListConcrete);
                    }
                }
            });
        }

        return new InheritanceResult(processed, inheritance);
    }

    private static boolean isInheritanceStmt(Statement stmt) {
        Property p = stmt.getPredicate();
        return p.equals(RDF.type)
                || p.equals(RDFS.subClassOf)
                || p.equals(RDFS.subPropertyOf)
                || p.equals(RDFS.domain)
                || p.equals(RDFS.range);
    }

    private static Model inheritanceStructure(
            Resource root, Resource current,
            Model inheritance, Model fullModel,
            boolean onlyConcrete) {

        ResIterator subs = fullModel.listSubjectsWithProperty(RDFS.subClassOf, current);
        while (subs.hasNext()) {
            Resource sub = subs.next();
            if (!onlyConcrete || isConcrete(sub, fullModel)) {
                inheritance.add(root, OWL2.members, sub);
                inheritance.add(root, RDF.type, OWL2.Class);
            }
            inheritanceStructure(root, sub, inheritance, fullModel, onlyConcrete);
        }
        return inheritance;
    }

    private static boolean isConcrete(Resource cls, Model model) {
        Property stereo = ResourceFactory
                .createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");
        NodeIterator it = model.listObjectsOfProperty(cls, stereo);
        while (it.hasNext()) {
            RDFNode n = it.next();
            if (n.isResource()
                    && n.asResource().getURI()
                    .equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                return true;
            }
        }
        return false;
    }

    public static Model LoadSHACLSHACL() {
        Model shaclModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        InputStream inputStream = ModelFactory.class.getResourceAsStream("/shacl-shacl/shacl-shaclFixed.ttl");

        if (inputStream != null) {
            RDFDataMgr.read(shaclModel, inputStream, "", Lang.TURTLE);
        } else {
            try {
                throw new FileNotFoundException("File not found for shacl validation.");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return shaclModel;
    }

}
