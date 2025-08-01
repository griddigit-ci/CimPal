package eu.griddigit.cimpal.core.models;

import org.apache.jena.riot.RDFFormat;

public class RDFConvertOptions {
    private String sourceFormat;
    private String targetFormat;
    private String xmlBase;
    private RDFFormat rdfFormat;
    private String showXmlDeclaration;
    private String showDoctypeDeclaration;
    private String tabCharacter;
    private String relativeURIs;
    private boolean modelUnionFlag;
    private boolean inheritanceOnly;
    private boolean inheritanceList;
    private boolean isInheritanceListConcrete;
    private boolean addOwl;
    private boolean modelUnionFlagDetailed;
    private String sortRDF;
    private String rdfSortOptions;
    private boolean stripPrefixes;
    private String convertInstanceData;
    private boolean modelUnionFixPackage;

    public RDFConvertOptions(String sourceFormat, String targetFormat, String xmlBase, RDFFormat rdfFormat,
                             String showXmlDeclaration, String showDoctypeDeclaration, String tabCharacter,
                             String relativeURIs, boolean modelUnionFlag, boolean inheritanceOnly, boolean inheritanceList,
                             boolean isInheritanceListConcrete, boolean addOwl, boolean modelUnionFlagDetailed, String sortRDF,
                             String rdfSortOptions, boolean stripPrefixes, String convertInstanceData, boolean modelUnionFixPackage) {
        this.sourceFormat = sourceFormat;
        this.targetFormat = targetFormat;
        this.xmlBase = xmlBase;
        this.rdfFormat = rdfFormat;
        this.showXmlDeclaration = showXmlDeclaration;
        this.showDoctypeDeclaration = showDoctypeDeclaration;
        this.tabCharacter = tabCharacter;
        this.relativeURIs = relativeURIs;
        this.modelUnionFlag = modelUnionFlag;
        this.inheritanceOnly = inheritanceOnly;
        this.inheritanceList = inheritanceList;
        this.isInheritanceListConcrete = isInheritanceListConcrete;
        this.addOwl = addOwl;
        this.modelUnionFlagDetailed = modelUnionFlagDetailed;
        this.sortRDF = sortRDF;
        this.rdfSortOptions = rdfSortOptions;
        this.stripPrefixes = stripPrefixes;
        this.convertInstanceData = convertInstanceData;
        this.modelUnionFixPackage = modelUnionFixPackage;
    }

    public String getSourceFormat() {
        return sourceFormat;
    }

    public void setSourceFormat(String sourceFormat) {
        this.sourceFormat = sourceFormat;
    }

    public String getTargetFormat() {
        return targetFormat;
    }

    public void setTargetFormat(String targetFormat) {
        this.targetFormat = targetFormat;
    }

    public String getXmlBase() {
        return xmlBase;
    }

    public void setXmlBase(String xmlBase) {
        this.xmlBase = xmlBase;
    }

    public RDFFormat getRdfFormat() {
        return rdfFormat;
    }

    public void setRdfFormat(RDFFormat rdfFormat) {
        this.rdfFormat = rdfFormat;
    }

    public String getShowXmlDeclaration() {
        return showXmlDeclaration;
    }

    public void setShowXmlDeclaration(String showXmlDeclaration) {
        this.showXmlDeclaration = showXmlDeclaration;
    }

    public String getShowDoctypeDeclaration() {
        return showDoctypeDeclaration;
    }

    public void setShowDoctypeDeclaration(String showDoctypeDeclaration) {
        this.showDoctypeDeclaration = showDoctypeDeclaration;
    }

    public String getTabCharacter() {
        return tabCharacter;
    }

    public void setTabCharacter(String tabCharacter) {
        this.tabCharacter = tabCharacter;
    }

    public String getRelativeURIs() {
        return relativeURIs;
    }

    public void setRelativeURIs(String relativeURIs) {
        this.relativeURIs = relativeURIs;
    }

    public boolean isModelUnionFlag() {
        return modelUnionFlag;
    }

    public void setModelUnionFlag(boolean modelUnionFlag) {
        this.modelUnionFlag = modelUnionFlag;
    }

    public boolean isInheritanceOnly() {
        return inheritanceOnly;
    }

    public void setInheritanceOnly(boolean inheritanceOnly) {
        this.inheritanceOnly = inheritanceOnly;
    }

    public boolean isInheritanceList() {
        return inheritanceList;
    }

    public void setInheritanceList(boolean inheritanceList) {
        this.inheritanceList = inheritanceList;
    }

    public boolean isInheritanceListConcrete() {
        return isInheritanceListConcrete;
    }

    public void setInheritanceListConcrete(boolean inheritanceListConcrete) {
        isInheritanceListConcrete = inheritanceListConcrete;
    }

    public boolean isAddOwl() {
        return addOwl;
    }

    public void setAddOwl(boolean addOwl) {
        this.addOwl = addOwl;
    }

    public boolean isModelUnionFlagDetailed() {
        return modelUnionFlagDetailed;
    }

    public void setModelUnionFlagDetailed(boolean modelUnionFlagDetailed) {
        this.modelUnionFlagDetailed = modelUnionFlagDetailed;
    }

    public String getSortRDF() {
        return sortRDF;
    }

    public void setSortRDF(String sortRDF) {
        this.sortRDF = sortRDF;
    }

    public String getRdfSortOptions() {
        return rdfSortOptions;
    }

    public void setRdfSortOptions(String rdfSortOptions) {
        this.rdfSortOptions = rdfSortOptions;
    }

    public boolean isStripPrefixes() {
        return stripPrefixes;
    }

    public void setStripPrefixes(boolean stripPrefixes) {
        this.stripPrefixes = stripPrefixes;
    }

    public String getConvertInstanceData() {
        return convertInstanceData;
    }

    public void setConvertInstanceData(String convertInstanceData) {
        this.convertInstanceData = convertInstanceData;
    }

    public boolean isModelUnionFixPackage() {
        return modelUnionFixPackage;
    }

    public void setModelUnionFixPackage(boolean modelUnionFixPackage) {
        this.modelUnionFixPackage = modelUnionFixPackage;
    }
}
