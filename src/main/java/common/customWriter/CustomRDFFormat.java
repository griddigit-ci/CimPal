
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package common.customWriter;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFFormatVariant;
import org.apache.jena.riot.RDFWriterRegistry;

/**
 * extends {@link RDFFormat} by new custom formats
 *
 */
public class CustomRDFFormat extends RDFFormat {
    public static final RDFFormatVariant CUSTOM_PLAIN_PRETTY         = new RDFFormatVariant("custom_plain_pretty") ;
    public static final RDFFormatVariant CUSTOM_PLAIN          = new RDFFormatVariant("custom_plain") ;

    public static final RDFFormat RDFXML_CUSTOM_PLAIN = new RDFFormat(Lang.RDFXML, CUSTOM_PLAIN);
    public static final RDFFormat RDFXML_CUSTOM_PLAIN_PRETTY = new RDFFormat(Lang.RDFXML, CUSTOM_PLAIN_PRETTY);

    public CustomRDFFormat(Lang lang) {
        super(lang);
    }

    public CustomRDFFormat(Lang lang, RDFFormatVariant variant) {
        super(lang, variant);
    }

    /**
     * registers the factories for the custom formats
     */
    public static void RegisterCustomFormatWriters()
    {
        RDFWriterRegistry.register(Lang.RDFXML, CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        RDFWriterRegistry.register(CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY, new RDFXMLCustomWriterFactory());
        RDFWriterRegistry.register(Lang.RDFXML, CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
        RDFWriterRegistry.register(CustomRDFFormat.RDFXML_CUSTOM_PLAIN, new RDFXMLCustomWriterFactory());
    }
}
