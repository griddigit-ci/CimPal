/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.datagenerator;

import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.RDFSProfile;
import eu.griddigit.cimpal.main.application.datagenerator.resources.UnzippedFiles;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DataGeneratorModel {
    // Where the two halves of a Network Code Profile's name live: the profile a dataset conforms to
    // is https://ap.cim4.eu/<name>/<version>, its vocabulary's ontology https://ap-voc.cim4.eu/<name>#Ontology.
    private static final String NCP_PROFILE_HOST = "https://ap.cim4.eu/";
    private static final String NCP_VOCABULARY_HOST = "https://ap-voc.cim4.eu/";

    // RDFS Profile
    private RDFSProfile rdfsProfileVersion;
    private Map<String,ArrayList<Object>> profileDataMap;
    private Map<String,Model> profileDataMapAsModel;
    private Map<String,Model> profileModelMap ;
    private Map<String, String> ncpKeywordsByProfileName;

    //Base instance model and files unionModel and modelUnionWithoutHeader and all other keywords
    private  Map<String, BaseInstanceModel> baseInstanceModel;
    private String[] baseInstanceModelPaths;

    //Shacl
    private Model shaclModel;
    private Map<Property, Map> shaclConstraints;

    // EQ and TB models
    private Model eqbdModel;
    private Model tpbdModel;

    // Save Properties for saving model data
    private Map<String, Object> saveProperties;

    public DataGeneratorModel() {
        initSaveProperties();
    }

    private void initSaveProperties() {
        this.saveProperties = new HashMap<>();
        saveProperties.put("filename", "test");
        saveProperties.put("showXmlDeclaration", "true");
        saveProperties.put("showDoctypeDeclaration", "false");
        saveProperties.put("tab", "2");
        saveProperties.put("relativeURIs", "same-document");
        saveProperties.put("showXmlEncoding", "true");
        //saveProperties.put("xmlBase", xmlBase);
        saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
        saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
        //saveProperties.put("fileFolder", "C:");
        //saveProperties.put("dozip", false);
        saveProperties.put("instanceData", "true"); //this is to only print the ID and not with namespace
        saveProperties.put("showXmlBaseDeclaration", "false");
        saveProperties.put("sortRDF","true");
        saveProperties.put("sortRDFprefix","false"); // if true the sorting is on the prefix, if false on the localName


        saveProperties.put("putHeaderOnTop", true);
        saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName", "RDF XML");
        saveProperties.put("fileExtension", "*.xml");
    }
/*
    public Map<String, BaseInstanceModel>  generateBaseModel() {
        // TODO generate based on profile/format/namespace
        return this.baseInstanceModel;
    }
*/
    private  Map<String, BaseInstanceModel> loadBaseInstanceModel(String[] files) throws IOException {
        Map<String, BaseInstanceModel> baseModelMap = new HashMap<>();

        // TODO Load files here for profile version data
        Lang rdfSourceFormat = null;

        for (String file : files) {

            String extension = FilenameUtils.getExtension(file);

            rdfSourceFormat = switch (extension) {
                case "rdf", "xml" -> Lang.RDFXML;
                case "ttl" -> Lang.TURTLE;
                case "jsonld" -> Lang.JSONLD;
                default -> rdfSourceFormat;
            };

            InputStream inputStream;

            if (extension.equals("zip")){
                UnzippedFiles unzippedFiles;
                unzippedFiles = InstanceDataFactory.unzipToCustomClass(new File(file));

                extension = FilenameUtils.getExtension(unzippedFiles.fileNames().getFirst());

                rdfSourceFormat = switch (extension) {
                    case "rdf", "xml" -> Lang.RDFXML;
                    case "ttl" -> Lang.TURTLE;
                    case "jsonld" -> Lang.JSONLD;
                    default -> rdfSourceFormat;
                };

                if (unzippedFiles.isSingleZip){
                    inputStream = unzippedFiles.getInputStreamList().getFirst();
                    readAndRegister(baseModelMap, unzippedFiles.fileNames().getFirst(), inputStream, rdfSourceFormat);
                }
                else{
                    for (int i = 0; i < unzippedFiles.fileNames().size(); i++){
                        inputStream = unzippedFiles.getInputStreamList().get(i);
                        readAndRegister(baseModelMap, unzippedFiles.fileNames().get(i), inputStream, rdfSourceFormat);
                    }
                }
            }
            else {
                inputStream = new FileInputStream(file);
                if (inputStream.available() == 0)
                    throw new IOException("File is empty: " + file);
                readAndRegister(baseModelMap, FilenameUtils.getName(file), inputStream, rdfSourceFormat);
            }
        }

        return baseModelMap;
    }

    // Parses one file. CIM XML tells an object the file defines (rdf:ID) from one it only refers to
    // (rdf:about), which parsing loses, so the attribute is noted on the way - see RdfAboutScanner.
    private void readAndRegister(Map<String, BaseInstanceModel> baseModelMap, String fileName, InputStream inputStream, Lang format) throws IOException {
        RdfAboutScanner scanner = format == Lang.RDFXML ? new RdfAboutScanner(inputStream) : null;
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, scanner != null ? scanner : inputStream, rdfsProfileVersion.getBaseNamespace(), format);
        Set<Resource> about = scanner == null ? null : scanner.aboutSubjects(model);
        register(baseModelMap, fileName, model, about, about == null ? null : scanner.idSubjects(model, about));
    }

    /**
     * Wraps one loaded file and records the profile it belongs to.
     * <p>
     * Every task keys its work on that profile - the RDFS it reads and the serialisation rules the
     * file is written back with - so a file whose profile cannot be told is refused here, by name,
     * rather than failing somewhere in the middle of a task.
     */
    private void register(Map<String, BaseInstanceModel> baseModelMap, String fileName, Model model,
                          Set<Resource> aboutSubjects, Set<Resource> idSubjects) throws IOException {
        BaseInstanceModel loadedModel = new BaseInstanceModel(fileName);
        loadedModel.setBaseInstanceModel(model);
        loadedModel.setWrittenForm(aboutSubjects, idSubjects);

        if (rdfsProfileVersion.isNcp()) {
            loadedModel.setProfile(ncpProfileKeyword(fileName, model));
        } else if (loadedModel.getProfile() == null) {
            throw new IOException("Cannot tell which profile " + fileName + " belongs to: the file name does not follow "
                    + "<datetime>_<process>_<TSO>_<profile>_<version>.");
        }

        baseModelMap.put(fileName, loadedModel);
    }

    /**
     * The profile keyword of a Network Code Profile dataset - CO, RA, AE...
     * <p>
     * NCP datasets are named freely, so unlike CGMES the profile cannot be read off the file name.
     * The header says it instead: dcterms:conformsTo is mandatory and carries the profile's URI
     * (https://ap.cim4.eu/Contingency/2.3), which is matched by name against the loaded vocabularies.
     * dcat:keyword is only the fallback: it is optional, and the data does not use it consistently -
     * the ReliCapGrid examples include a RemedialActionSchedule dataset keyworded FAP and an
     * EquipmentReliability one keyworded CommonData.
     */
    private String ncpProfileKeyword(String fileName, Model model) throws IOException {
        ResIterator headers = model.listSubjectsWithProperty(RDF.type, DCAT.Dataset);
        if (!headers.hasNext()) {
            throw new IOException(fileName + " has no dcat:Dataset header, so it is not a Network Code Profile dataset. "
                    + "Select a CGMES profile version for CGMES models.");
        }
        Resource header = headers.next();

        Map<String, String> keywordsByProfileName = ncpKeywordsByProfileName();
        for (Statement conformsTo : model.listStatements(header, DCTerms.conformsTo, (RDFNode) null).toList()) {
            RDFNode value = conformsTo.getObject();
            String uri = value.isURIResource() ? value.asResource().getURI()
                    : value.isLiteral() ? value.asLiteral().getLexicalForm() : null;
            String keyword = keywordsByProfileName.get(ncpProfileName(uri, NCP_PROFILE_HOST));
            if (keyword != null) {
                return keyword;
            }
        }

        // A header can carry more than one keyword - the ReliCapGrid Provenance dataset has PV and
        // Provenance - so the pick is made deterministic: one that names a loaded profile if there is
        // one, otherwise the shortest, profile keywords being short codes.
        Comparator<String> preferred = Comparator.comparing((String candidate) -> !profileModelMap.containsKey(candidate))
                .thenComparing(String::length)
                .thenComparing(Comparator.naturalOrder());
        Optional<String> keyword = model.listObjectsOfProperty(header, DCAT.keyword).toList().stream()
                .filter(RDFNode::isLiteral)
                .map(value -> value.asLiteral().getString())
                .filter(candidate -> !candidate.isBlank())
                .min(preferred);
        if (keyword.isPresent()) {
            return keyword.get();
        }
        throw new IOException("Cannot tell which profile " + fileName + " belongs to: its dcat:Dataset header names no "
                + "profile of " + rdfsProfileVersion.getName() + " in dcterms:conformsTo, and has no dcat:keyword.");
    }

    // Network Code Profile name -> keyword, read off the owl:Ontology of each loaded vocabulary.
    private Map<String, String> ncpKeywordsByProfileName() throws FileNotFoundException {
        if (ncpKeywordsByProfileName == null) {
            if (profileModelMap == null) {
                loadRDFSProfileModel();
            }
            Map<String, String> byName = new HashMap<>();
            for (Map.Entry<String, Model> entry : profileModelMap.entrySet()) {
                // The two union models hold every vocabulary's ontology, not one of their own.
                if (entry.getKey().equals("unionModel") || entry.getKey().equals("modelUnionWithoutHeader")) {
                    continue;
                }
                for (ResIterator ontologies = entry.getValue().listSubjectsWithProperty(RDF.type, OWL2.Ontology); ontologies.hasNext(); ) {
                    Resource ontology = ontologies.next();
                    String name = ontology.isURIResource() ? ncpProfileName(ontology.getURI(), NCP_VOCABULARY_HOST) : null;
                    if (name != null) {
                        byName.putIfAbsent(name, entry.getKey());
                    }
                }
            }
            ncpKeywordsByProfileName = byName;
        }
        return ncpKeywordsByProfileName;
    }

    // "Contingency" out of https://ap.cim4.eu/Contingency/2.3 or https://ap-voc.cim4.eu/Contingency#Ontology.
    private static String ncpProfileName(String uri, String host) {
        if (uri == null || !uri.startsWith(host)) {
            return null;
        }
        String rest = uri.substring(host.length());
        int end = rest.length();
        for (char separator : new char[]{'/', '#'}) {
            int at = rest.indexOf(separator);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return end == 0 ? null : rest.substring(0, end);
    }

    private  Map<String,Model> modelLoad(String[] files) throws FileNotFoundException { // Used for custom RDFS profile load
        Map<String,Model> unionModelMap = new HashMap<>();

        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Model modelUnionWithoutHeader = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        Map<String, String> prefixMapWithoutHeader = modelUnionWithoutHeader.getNsPrefixMap();
        // TODO Load files here for profile version data
        Lang rdfSourceFormat = null;
        for (String file : files) {

            String extension = FilenameUtils.getExtension(file);

            rdfSourceFormat = switch (extension) {
                case "rdf", "xml" -> Lang.RDFXML;
                case "ttl" -> Lang.TURTLE;
                case "jsonld" -> Lang.JSONLD;
                default -> rdfSourceFormat;
            };

            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

            InputStream inputStream = new FileInputStream(file);
            RDFDataMgr.read(model, inputStream, rdfsProfileVersion.getBaseNamespace(), rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());
            prefixMapWithoutHeader.putAll(model.getNsPrefixMap());

            //get profile short name for CGMES v2.4, keyword for CGMES v3
            String keyword = InstanceDataFactory.getProfileKeyword(model);
            if (FilenameUtils.getName(file).equals("FileHeader.rdf")){
                keyword="FH";
            }
            if (!keyword.isEmpty()) {
                unionModelMap.put(keyword, model);
            }else{
                unionModelMap.put(FilenameUtils.getName(file), model);
            }

            if (!keyword.equals("FH")) {
                modelUnionWithoutHeader.add(model);
            }
            modelUnion.add(model);
        }
        modelUnion.setNsPrefixes(prefixMap);
        modelUnionWithoutHeader.setNsPrefixes(prefixMap);

        unionModelMap.put("unionModel",modelUnion);
        unionModelMap.put("modelUnionWithoutHeader",modelUnionWithoutHeader);
        return unionModelMap;
    }

    private  Map<String,Model> modelLoad(InputStream[] files) { // Used for predefined RDFS model load
        Map<String,Model> unionModelMap = new HashMap<>();

        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Model modelUnionWithoutHeader = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        Map<String, String> prefixMapWithoutHeader = modelUnionWithoutHeader.getNsPrefixMap();
        // TODO Load files here for profile version data
        Lang rdfSourceFormat = Lang.RDFXML;
        int i = 0;
        for (InputStream file : files) {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

            RDFDataMgr.read(model, file, rdfsProfileVersion.getBaseNamespace(), rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());
            prefixMapWithoutHeader.putAll(model.getNsPrefixMap());

            //get profile short name for CGMES v2.4, keyword for CGMES v3
            String keyword = InstanceDataFactory.getProfileKeyword(model);
            if (rdfsProfileVersion.getPathToRDFSFiles()[i].equals("FileHeader.rdf")){
                keyword="FH";
            }
            if (!keyword.isEmpty()) {
                unionModelMap.put(keyword, model);
            }else{
                unionModelMap.put(rdfsProfileVersion.getPathToRDFSFiles()[i], model);
            }

            if (!keyword.equals("FH")) {
                modelUnionWithoutHeader.add(model);
            }
            modelUnion.add(model);
            i++;
        }
        modelUnion.setNsPrefixes(prefixMap);
        modelUnionWithoutHeader.setNsPrefixes(prefixMap);

        unionModelMap.put("unionModel",modelUnion);
        unionModelMap.put("modelUnionWithoutHeader",modelUnionWithoutHeader);
        return unionModelMap;
    }

    public void loadRDFSProfileModel() throws FileNotFoundException {
        profileDataMap = new HashMap<>();
        profileDataMapAsModel = new HashMap<>();
        profileModelMap = null;
        ArrayList<Object> profileData = null;

        // load all profile models
        if (rdfsProfileVersion.getRdfsInputStreams().length==0){
            profileModelMap = this.modelLoad(rdfsProfileVersion.getPathToRDFSFiles());
        }
        else{
            profileModelMap = this.modelLoad(rdfsProfileVersion.getRdfsInputStreams());
        }

        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#"; // TODO ASK what we make of this? - connect to preferences

        //make the profile data per profile
        for (Map.Entry<String, Model> entry : profileModelMap.entrySet()) {
            profileData = ProfileDataFactory.constructShapeData(entry.getValue(), rdfNs, concreteNs);
            profileDataMap.put(entry.getKey(), profileData);
            profileDataMapAsModel.put(entry.getKey(), ProfileDataFactory.profileDataMapAsModelTemp);
        }
    }

    public void loadShaclModel(String[] shaclFiles) throws FileNotFoundException {
        InstanceDataFactory.supportedProperties = ConstraintsFactory.supportedPropertiesInit();
        shaclModel = InstanceDataFactory.modelLoadShaclFiles(shaclFiles, rdfsProfileVersion.getBaseNamespace());
        shaclConstraints = ConstraintsFactory.getConstraints(shaclModel, profileDataMap.get("unionModel"));
        InstanceDataFactory.shaclConstraints = shaclConstraints;
    }

    public void loadInstanceModel() throws IOException {
        baseInstanceModel = this.loadBaseInstanceModel(this.baseInstanceModelPaths);
        // TODO ONLY USED ON MULTIPLY AND CONNECT
        // baseInstanceModelMapOriginal=InstanceDataFactory.modelLoad(baseInstanceModelFiles, xmlBase, null, false);;
    }

    public Map<String,Model>  loadOriginalBaseInstanceModel() throws FileNotFoundException {
        return this.modelLoad(this.baseInstanceModelPaths);
    }

    public void loadProfileAndBaseModelData() throws IOException {
        // The RDFS first: an NCP dataset's profile is read from its header and matched against the
        // loaded vocabularies, so they have to be in place before the instance files are.
        if (profileModelMap == null) {
            this.loadRDFSProfileModel();
        }
        if (baseInstanceModel == null) {
            baseInstanceModel = this.loadBaseInstanceModel(this.baseInstanceModelPaths);
        }
    }


    public void setRdfsProfileVersion(RDFSProfile selectedRDFSProfile) {
        rdfsProfileVersion = selectedRDFSProfile;
        // Putting the header on top and writing it with rdf:about both key on this class, so it has
        // to be the header class of the family being written: md:FullModel or dcat:Dataset.
        if (selectedRDFSProfile != null) {
            saveProperties.put("headerClassResource", selectedRDFSProfile.getFamily().getHeaderClass());
        }
    }

    public void setBaseInstanceModelPath(String[] filePaths) {
        this.baseInstanceModelPaths = filePaths;
    }

    public RDFSProfile getRdfsProfileVersion() {
        return rdfsProfileVersion;
    }

    public Model getEqbdModel() {
        return eqbdModel;
    }

    public void setEqbdModel(Model eqbdModel) {
        this.eqbdModel = eqbdModel;
    }

    public Model getTpbdModel() {
        return tpbdModel;
    }

    public void setTpbdModel(Model tpbdModel) {
        this.tpbdModel = tpbdModel;
    }

    public Map<String, BaseInstanceModel> getBaseInstanceModel() {
        return baseInstanceModel;
    }

    public void setBaseInstanceModel(Map<String, BaseInstanceModel>  baseInstanceModel) {
        this.baseInstanceModel = baseInstanceModel;
    }

    public Map<Property, Map> getShaclConstraints() {
        return shaclConstraints;
    }

    public Map<String, ArrayList<Object>> getProfileDataMap() {
        return profileDataMap;
    }

    public Map<String, Model> getProfileDataMapAsModel() {
        return this.profileDataMapAsModel;
    }

    public Map<String, Model> getProfileModelMap() {
        return this.profileModelMap;
    }

    public Map<String, Object> getSaveProperties() {
        return saveProperties;
    }

}
