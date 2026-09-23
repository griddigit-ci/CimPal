package eu.griddigit.cimpal.core.presets;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;

/**
 * Predefined presets for validating CGMES datasets.
 * Each preset returns a Builder that still needs the data and shapes before calling build().
 */
public class SHACLValidationOptionsPresets {
    /** Base URI for CIM 16 (CGMES 2.4.15) datasets. */
    public static final String CIM16_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";
    /** Base URI for CIM 17 (CGMES 3.0) datasets. */
    public static final String CIM17_XML_BASE = "http://iec.ch/TC57/CIM100";

    /** CGMES 3.0 data with the NC 2.5 datatype map: the SHACL Validation tab's defaults. */
    public static SHACLValidationOptions.Builder cgmes30() {
        return SHACLValidationOptions.builder()
                .datatypeMap(DatatypeMapPreset.CGMES30_NC25)
                .xmlBase(CIM17_XML_BASE);
    }

    /** CGMES 2.4.15 data with the NC 2.2 datatype map. */
    public static SHACLValidationOptions.Builder cgmes24() {
        return SHACLValidationOptions.builder()
                .datatypeMap(DatatypeMapPreset.CGMES24_NC22)
                .xmlBase(CIM16_XML_BASE);
    }
}
