package eu.griddigit.cimpal.main.application.datagenerator.resources;

/**
 * The family a set of RDFS profiles belongs to.
 * <p>
 * It decides the vocabulary of the model header the wizard reads and writes, and which tasks the
 * wizard offers: most tasks were written against CGMES grid models and only some of them carry
 * over to Network Code Profile datasets.
 */
public enum ProfileFamily {

    /** CGMES and the other CIM releases: the header is an md:FullModel. */
    CGMES("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel",
            "http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"),

    /**
     * ENTSO-E Network Code Profiles: the header is a dcat:Dataset from the DatasetMetadata
     * profile, and each dataset names the profile it conforms to in dcterms:conformsTo.
     */
    NCP("http://www.w3.org/ns/dcat#Dataset",
            "http://purl.org/dc/terms/description");

    private final String headerClass;
    private final String headerDescriptionProperty;

    ProfileFamily(String headerClass, String headerDescriptionProperty) {
        this.headerClass = headerClass;
        this.headerDescriptionProperty = headerDescriptionProperty;
    }

    /** URI of the class every model file of this family carries exactly one header of. */
    public String getHeaderClass() {
        return headerClass;
    }

    /** URI of the header property holding the free-text description of the model. */
    public String getHeaderDescriptionProperty() {
        return headerDescriptionProperty;
    }
}
