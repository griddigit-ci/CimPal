package eu.griddigit.cimpal.main.application.datagenerator;

import common.customWriter.CustomRDFFormat;
import datagenerator.resources.BaseInstanceModel;
import datagenerator.resources.RDFSProfile;
import datagenerator.resources.UnzippedFiles;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DataGeneratorModel {
    // RDFS Profile
    private RDFSProfile rdfsProfileVersion;
    private Map<String,ArrayList<Object>> profileDataMap;
    private Map<String,Model> profileDataMapAsModel;
    private Map<String,Model> profileModelMap ;

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
                    Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

                    RDFDataMgr.read(model, inputStream, rdfsProfileVersion.getBaseNamespace(), rdfSourceFormat);

                    BaseInstanceModel loadedModel = new BaseInstanceModel(unzippedFiles.fileNames().getFirst());
                    loadedModel.setBaseInstanceModel(model);
                    baseModelMap.put(unzippedFiles.fileNames().getFirst(), loadedModel);
                }
                else{
                    for (int i = 0; i < unzippedFiles.fileNames().size(); i++){
                        inputStream = unzippedFiles.getInputStreamList().get(i);
                        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

                        RDFDataMgr.read(model, inputStream, rdfsProfileVersion.getBaseNamespace(), rdfSourceFormat);

                        BaseInstanceModel loadedModel = new BaseInstanceModel(unzippedFiles.fileNames().get(i));
                        loadedModel.setBaseInstanceModel(model);
                        baseModelMap.put(unzippedFiles.fileNames().get(i), loadedModel);
                    }
                }
            }
            else {
                inputStream = new FileInputStream(file);
                if (inputStream.available() == 0)
                    throw new IOException("File is empty: " + file);
                Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

                RDFDataMgr.read(model, inputStream, rdfsProfileVersion.getBaseNamespace(), rdfSourceFormat);

                BaseInstanceModel loadedModel = new BaseInstanceModel(FilenameUtils.getName(file));
                loadedModel.setBaseInstanceModel(model);
                baseModelMap.put(FilenameUtils.getName(file), loadedModel);
            }
        }

        return baseModelMap;
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
        if (baseInstanceModel == null) {
            baseInstanceModel = this.loadBaseInstanceModel(this.baseInstanceModelPaths);
        }
        if (profileModelMap == null) {
            this.loadRDFSProfileModel();
        }
    }


    public void setRdfsProfileVersion(RDFSProfile selectedRDFSProfile) {
        rdfsProfileVersion = selectedRDFSProfile;
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
