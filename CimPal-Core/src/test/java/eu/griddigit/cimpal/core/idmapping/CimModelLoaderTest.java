/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CimModelLoaderTest {

    private static final String BASE = "http://iec.ch/TC57/CIM100";
    private static final String CIM16_EQ = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:cim="http://iec.ch/TC57/2013/CIM-schema-cim16#">
              <cim:Breaker rdf:ID="_b1"><cim:IdentifiedObject.name>B1</cim:IdentifiedObject.name></cim:Breaker>
            </rdf:RDF>
            """;

    @TempDir
    Path dir;

    @Test
    void cgmes24NamespaceIsReadAsCgmes30InsideAZip() throws IOException {
        Path zip = zip("eq.zip", "README.txt", "not RDF", "model/EQ.xml", CIM16_EQ);

        Graph graph = CimModelLoader.load(List.of(zip), BASE);

        assertTrue(graph.contains(NodeFactory.createURI(BASE + "#_b1"), RDF.type.asNode(),
                NodeFactory.createURI(CimModelLoader.CIM + "Breaker")));
        assertTrue(graph.contains(NodeFactory.createURI(BASE + "#_b1"),
                NodeFactory.createURI(CimModelLoader.CIM + "IdentifiedObject.name"), NodeFactory.createLiteralString("B1")));
    }

    @Test
    void jsonLdIsRefusedBeforeItCanFetchARemoteContext() throws IOException {
        // A JSON-LD @context is fetched by the parser, outside CimPal's egress gate; the file must not be parsed.
        Path jsonld = Files.writeString(dir.resolve("model.jsonld"),
                "{\"@context\": \"http://example.invalid/context.jsonld\", \"@id\": \"urn:x\"}");
        Path zip = zip("model.zip", "model.jsonld", "{\"@context\": \"http://example.invalid/c\"}");

        IOException direct = assertThrows(IOException.class, () -> CimModelLoader.load(List.of(jsonld), BASE));
        IOException inZip = assertThrows(IOException.class, () -> CimModelLoader.load(List.of(zip), BASE));

        assertTrue(direct.getMessage().startsWith("Unsupported model format"), direct.getMessage());
        assertTrue(inZip.getMessage().startsWith("Unsupported model format"), inZip.getMessage());
    }

    @Test
    void owlFileIsReadAsRdfXml() throws IOException {
        Path owl = Files.writeString(dir.resolve("model.owl"), CIM16_EQ);

        Graph graph = CimModelLoader.load(List.of(owl), BASE);

        assertTrue(graph.contains(NodeFactory.createURI(BASE + "#_b1"), RDF.type.asNode(),
                NodeFactory.createURI(CimModelLoader.CIM + "Breaker")));
    }

    @Test
    void zipWithoutAnyRdfFileIsAnError() throws IOException {
        Path zip = zip("empty.zip", "README.txt", "nothing to read");

        IOException ex = assertThrows(IOException.class, () -> CimModelLoader.load(List.of(zip), BASE));

        assertTrue(ex.getMessage().startsWith("No RDF/XML, Turtle or N-Triples file"), ex.getMessage());
    }

    @Test
    void fileThatDoesNotParseNamesTheFile() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.xml"), "<rdf:RDF");

        IOException ex = assertThrows(IOException.class, () -> CimModelLoader.load(List.of(broken), BASE));

        assertTrue(ex.getMessage().contains("broken.xml"), ex.getMessage());
        assertEquals(0, CimModelLoader.load(List.of(), BASE).size());
    }

    /** A ZIP of name/content pairs. */
    private Path zip(String name, String... entries) throws IOException {
        Path zip = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < entries.length; i += 2) {
                z.putNextEntry(new ZipEntry(entries[i]));
                z.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }
}
