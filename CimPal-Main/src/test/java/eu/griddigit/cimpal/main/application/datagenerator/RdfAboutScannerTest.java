package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RdfAboutScannerTest {
    private static final String BASE = "https://cim.ucaiug.io/ns";

    // A Network Code dataset in miniature: a header identified by URN, a contingency the file
    // defines, and a generating unit it only refers to - the grid model defines that one.
    private static final String CIM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:cim="https://cim.ucaiug.io/ns#" xmlns:dcat="http://www.w3.org/ns/dcat#">
              <dcat:Dataset rdf:about="urn:uuid:3a3b27be-1111-2222-3333-444455556666"/>
              <cim:ContingencyEquipment rdf:ID="_19bfe9d5-5d04-4c3c-9919-ca1b2d1215ae">
                <cim:IdentifiedObject.name>G1</cim:IdentifiedObject.name>
              </cim:ContingencyEquipment>
              <cim:GeneratingUnit rdf:about="#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0">
                <cim:IdentifiedObject.name>rdf:about="#_not-a-subject" in text is ignored</cim:IdentifiedObject.name>
              </cim:GeneratingUnit>
            </rdf:RDF>
            """;

    private static Set<String> uris(Set<Resource> resources) {
        return resources.stream().map(Resource::getURI).collect(Collectors.toSet());
    }

    private static Model parse(InputStream in) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, in, BASE, Lang.RDFXML);
        return model;
    }

    @Test
    void recordsWhichObjectsWereWrittenWithAboutAndWhichWithId() {
        RdfAboutScanner scanner = new RdfAboutScanner(new ByteArrayInputStream(CIM_XML.getBytes(StandardCharsets.UTF_8)));
        Model model = parse(scanner);

        Set<Resource> about = scanner.aboutSubjects(model);
        assertEquals(Set.of("urn:uuid:3a3b27be-1111-2222-3333-444455556666", BASE + "#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0"),
                uris(about));
        assertEquals(Set.of(BASE + "#_19bfe9d5-5d04-4c3c-9919-ca1b2d1215ae"), uris(scanner.idSubjects(model, about)));
    }

    @Test
    void passesTheStreamThroughUnchanged() {
        Model scanned = parse(new RdfAboutScanner(new ByteArrayInputStream(CIM_XML.getBytes(StandardCharsets.UTF_8))));
        Model plain = parse(new ByteArrayInputStream(CIM_XML.getBytes(StandardCharsets.UTF_8)));
        assertTrue(scanned.isIsomorphicWith(plain), "the scanner must not change what is parsed");
    }

    @Test
    void findsTheAttributeWhenReadsSplitIt() {
        // One byte per read, so the attribute name and value straddle every read boundary.
        InputStream trickle = new FilterInputStream(new ByteArrayInputStream(CIM_XML.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        RdfAboutScanner scanner = new RdfAboutScanner(trickle);
        Model model = parse(scanner);
        assertEquals(2, scanner.aboutSubjects(model).size());
    }

    @Test
    void acceptsSpacesAroundTheEqualsSignAndSingleQuotes() {
        String xml = CIM_XML.replace("rdf:about=\"#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0\"",
                "rdf:about = '#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0'");
        RdfAboutScanner scanner = new RdfAboutScanner(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Model model = parse(scanner);
        assertTrue(uris(scanner.aboutSubjects(model)).contains(BASE + "#_413bbc8a-683c-7e07-4fb9-aa6ec83278e0"));
    }
}
