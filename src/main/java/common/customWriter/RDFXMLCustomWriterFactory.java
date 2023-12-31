
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package common.customWriter;

import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.WriterGraphRIOT;
import org.apache.jena.riot.WriterGraphRIOTFactory;

/**
 * factory to create a custom RDF-XML writer for specific custom {@link CustomRDFFormat formats}
 *
 */
public class RDFXMLCustomWriterFactory implements WriterGraphRIOTFactory {
    @Override
    public WriterGraphRIOT create(RDFFormat syntaxForm) {
        if(CustomRDFFormat.RDFXML_CUSTOM_PLAIN.equals(syntaxForm)) {
            return new RDFXMLCustomWriter();
        }
        else if(CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY.equals(syntaxForm)) {
            return new RDFXMLCustomWriter(true);
        }
        else {
            return null;
        }
    }
}
