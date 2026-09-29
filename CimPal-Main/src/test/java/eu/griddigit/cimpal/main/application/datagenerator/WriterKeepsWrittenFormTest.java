package eu.griddigit.cimpal.main.application.datagenerator;

import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WriterKeepsWrittenFormTest {
    private static final String BASE = "http://iec.ch/TC57/CIM100";
    private static final String CIM = BASE + "#";

    @Test
    void eachResourceIsWrittenInItsOwnFormWhateverTheRuleForItsClass() {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("cim", CIM);
        Resource terminal = model.createResource(CIM + "Terminal");
        Resource equipment = model.createResource(CIM + "Equipment");
        // An SSH has both kinds of Terminal: the rule says Terminals are references, yet this file
        // defines one of them. And it refers to equipment through the abstract Equipment class,
        // which no rule names.
        Resource definedTerminal = model.createResource(CIM + "_t1").addProperty(RDF.type, terminal);
        Resource referencedTerminal = model.createResource(CIM + "_t2").addProperty(RDF.type, terminal);
        Resource referencedEquipment = model.createResource(CIM + "_e1").addProperty(RDF.type, equipment);
        model.createResource(CIM + "_e2").addProperty(RDF.type, equipment);   // new: neither set, no rule

        Map<String, Object> properties = new HashMap<>();
        properties.put("showXmlDeclaration", "true");
        properties.put("showDoctypeDeclaration", "false");
        properties.put("showXmlEncoding", "true");
        properties.put("xmlbase", BASE);
        properties.put("tab", "2");
        properties.put("relativeURIs", "same-document");
        properties.put("instanceData", "true");
        properties.put("sortRDF", "true");
        properties.put("sortRDFprefix", "false");
        properties.put("showXmlBaseDeclaration", "false");
        properties.put("aboutRules", Set.of(terminal));
        properties.put("aboutResources", Set.of(referencedEquipment, referencedTerminal));
        properties.put("idResources", Set.of(definedTerminal));

        CustomRDFFormat.RegisterCustomFormatWriters();
        Context context = new Context();
        context.set(SysRIOT.sysRdfWriterProperties, properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFWriter.create().base(BASE).format(CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY).context(context).source(model).output(out);
        String xml = out.toString(StandardCharsets.UTF_8);

        assertTrue(xml.contains("rdf:ID=\"_t1\""), "a Terminal the file defines stays rdf:ID despite the rule:\n" + xml);
        assertTrue(xml.contains("rdf:about=\"#_t2\""), "a Terminal the file refers to stays rdf:about:\n" + xml);
        assertTrue(xml.contains("rdf:about=\"#_e1\""), "a reference no rule names stays rdf:about:\n" + xml);
        assertTrue(xml.contains("rdf:ID=\"_e2\""), "an object in neither set is left to the rules:\n" + xml);
    }
}
