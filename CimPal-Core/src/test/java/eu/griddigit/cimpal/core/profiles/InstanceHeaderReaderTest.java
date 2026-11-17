/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The profiles and dependencies an instance document declares in its header. */
class InstanceHeaderReaderTest {

    private static final String BASE = "http://example.org/base";
    private static final String RDF_OPEN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                     xmlns:dcat="http://www.w3.org/ns/dcat#"
                     xmlns:dcterms="http://purl.org/dc/terms/"
                     xmlns:ex="http://example.org/schema#">
            """;
    private static final String BODY = """
              <ex:Thing rdf:ID="_t1">
                <ex:Thing.name>one</ex:Thing.name>
                <dcterms:conformsTo rdf:resource="urn:example:Catalogued"/>
                <dcterms:requires rdf:resource="urn:uuid:not-a-model"/>
              </ex:Thing>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    private InstanceHeaderReader reader;

    @BeforeEach
    void catalogWithOneProfile() throws IOException {
        Path library = Files.createDirectories(tempDir.resolve("lib/PROF")).getParent();
        Files.createDirectories(library.resolve("SHACL"));
        Files.writeString(library.resolve("SHACL/Catalogued.ttl"), "<urn:test:S> a <http://www.w3.org/ns/shacl#NodeShape> .\n");
        Files.writeString(library.resolve("PROF/Catalogued.rdf"), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:prof="http://www.w3.org/ns/dx/prof/" xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="urn:example:Catalogued">
                    <rdf:type rdf:resource="http://www.w3.org/ns/dx/prof/Profile"/>
                    <prof:hasResource rdf:resource="urn:example:Catalogued/con"/>
                  </rdf:Description>
                  <rdf:Description rdf:about="urn:example:Catalogued/con">
                    <prof:hasRole rdf:resource="http://www.w3.org/ns/dx/prof/role/constraints"/>
                    <prof:hasArtifact rdf:resource="Catalogued.ttl"/>
                    <dcterms:conformsTo>http://www.w3.org/ns/shacl</dcterms:conformsTo>
                  </rdf:Description>
                </rdf:RDF>
                """);
        reader = new InstanceHeaderReader(ProfileCatalog.load(List.of(library)),
                List.of("https://profiles.example/"), BASE);
    }

    private Path xml(String name, String header) throws IOException {
        return Files.writeString(tempDir.resolve(name), RDF_OPEN + header + BODY);
    }

    private InstanceHeader readOne(Path file) throws IOException {
        List<InstanceHeader> headers = reader.read(file);
        assertThat(headers).hasSize(1);
        return headers.getFirst();
    }

    @Test
    void aModelHeaderDeclaresItsProfilesAndWhatItDependsOn() throws Exception {
        Path file = xml("model.xml", """
                  <md:FullModel rdf:about="urn:uuid:11111111-aaaa">
                    <md:Model.profile>urn:example:ProfileB</md:Model.profile>
                    <md:Model.profile>urn:example:ProfileA</md:Model.profile>
                    <md:Model.DependentOn rdf:resource="urn:uuid:22222222-bbbb"/>
                    <md:Model.DependentOn rdf:resource="urn:uuid:00000000-cccc"/>
                  </md:FullModel>
                """);

        InstanceHeader header = readOne(file);

        assertThat(header.kind()).isEqualTo(InstanceHeader.Kind.CGMES);
        assertThat(header.profiles()).containsExactly("urn:example:ProfileA", "urn:example:ProfileB");
        assertThat(header.modelIri()).isEqualTo("urn:uuid:11111111-aaaa");
        assertThat(header.dependsOn()).containsExactly("urn:uuid:00000000-cccc", "urn:uuid:22222222-bbbb");
        assertThat(header.file()).isEqualTo(file);
        assertThat(header.entry()).isNull();
        assertThat(header.source()).isEqualTo("model.xml");
    }

    @Test
    void aDatasetHeaderDeclaresProfilesByCatalogKeyOrProfileNamespaceOnly() throws Exception {
        InstanceHeader header = readOne(xml("dataset.xml", """
                  <dcat:Dataset rdf:about="urn:uuid:33333333-dddd">
                    <dcterms:conformsTo rdf:resource="urn:example:Catalogued"/>
                    <dcterms:conformsTo rdf:resource="https://profiles.example/NotInCatalog/1.0"/>
                    <dcterms:conformsTo rdf:resource="urn:iso:std:iso:19115"/>
                    <dcterms:requires rdf:resource="urn:uuid:44444444-eeee"/>
                  </dcat:Dataset>
                """));

        assertThat(header.kind()).isEqualTo(InstanceHeader.Kind.NC);
        assertThat(header.profiles()).containsExactly("https://profiles.example/NotInCatalog/1.0", "urn:example:Catalogued");
        assertThat(header.modelIri()).isEqualTo("urn:uuid:33333333-dddd");
        // The body's ex:Thing also has conformsTo and requires; only the header node's count.
        assertThat(header.dependsOn()).containsExactly("urn:uuid:44444444-eeee");
    }

    @Test
    void aDatasetThatConformsToNoProfileDeclaresNone() throws Exception {
        InstanceHeader header = readOne(xml("reference.xml", """
                  <dcat:Dataset rdf:about="urn:uuid:55555555-ffff">
                    <dcterms:conformsTo rdf:resource="urn:iso:std:iso:19115"/>
                  </dcat:Dataset>
                """));

        assertThat(header.kind()).isEqualTo(InstanceHeader.Kind.NONE);
        assertThat(header.profiles()).isEmpty();
        assertThat(header.modelIri()).isEqualTo("urn:uuid:55555555-ffff");
    }

    @Test
    void aDocumentWithoutAHeaderDeclaresNothing() throws Exception {
        Path file = Files.writeString(tempDir.resolve("bare.xml"), RDF_OPEN + """
                  <ex:Thing rdf:ID="_t1"><ex:Thing.name>one</ex:Thing.name></ex:Thing>
                </rdf:RDF>
                """);

        InstanceHeader header = readOne(file);

        assertThat(header.kind()).isEqualTo(InstanceHeader.Kind.NONE);
        assertThat(header.profiles()).isEmpty();
        assertThat(header.modelIri()).isNull();
        assertThat(header.dependsOn()).isEmpty();
    }

    @Test
    void modelProfileWinsWhenAHeaderHasBothDeclarations() throws Exception {
        InstanceHeader header = readOne(xml("both.xml", """
                  <md:FullModel rdf:about="urn:uuid:66666666-0000">
                    <md:Model.profile>urn:example:ProfileA</md:Model.profile>
                    <dcterms:conformsTo rdf:resource="urn:example:Catalogued"/>
                  </md:FullModel>
                """));

        assertThat(header.kind()).isEqualTo(InstanceHeader.Kind.CGMES);
        assertThat(header.profiles()).containsExactly("urn:example:ProfileA");
    }

    @Test
    void theDublinCoreNamespaceWrittenWithAHashIsUnderstood() throws Exception {
        Path file = Files.writeString(tempDir.resolve("hash.xml"), RDF_OPEN.replace(
                "http://purl.org/dc/terms/", "http://purl.org/dc/terms/#") + """
                  <dcat:Dataset rdf:about="urn:uuid:77777777-0000">
                    <dcterms:conformsTo rdf:resource="urn:example:Catalogued"/>
                  </dcat:Dataset>
                </rdf:RDF>
                """);

        assertThat(readOne(file).profiles()).containsExactly("urn:example:Catalogued");
    }

    @Test
    void everyDocumentOfAnArchiveIsReadNestedArchivesIncluded() throws Exception {
        String model = RDF_OPEN + """
                  <md:FullModel rdf:about="urn:uuid:88888888-0000">
                    <md:Model.profile>urn:example:ProfileA</md:Model.profile>
                  </md:FullModel>
                """ + BODY;
        String dataset = RDF_OPEN + """
                  <dcat:Dataset rdf:about="urn:uuid:99999999-0000">
                    <dcterms:conformsTo rdf:resource="urn:example:Catalogued"/>
                  </dcat:Dataset>
                """ + BODY;
        byte[] inner = zipBytes("inner/dataset.xml", dataset.getBytes(StandardCharsets.UTF_8));
        Path archive = Files.write(tempDir.resolve("models.zip"), zipBytes(
                "model.xml", model.getBytes(StandardCharsets.UTF_8),
                "readme.txt", "not a model".getBytes(StandardCharsets.UTF_8),
                "nested.zip", inner));

        List<InstanceHeader> headers = reader.read(archive);

        assertThat(headers).extracting(InstanceHeader::entry).containsExactly("model.xml", "nested.zip/inner/dataset.xml");
        assertThat(headers).extracting(InstanceHeader::kind)
                .containsExactly(InstanceHeader.Kind.CGMES, InstanceHeader.Kind.NC);
        assertThat(headers.get(1).source()).isEqualTo("models.zip/nested.zip/inner/dataset.xml");
        assertThat(headers).allMatch(h -> h.file().equals(archive));
    }

    @Test
    void anArchiveWithoutDocumentsAndAFileThatIsNotRdfAreErrorsNotEmptyResults() throws Exception {
        Path empty = Files.write(tempDir.resolve("empty.zip"),
                zipBytes("readme.txt", "nothing".getBytes(StandardCharsets.UTF_8)));
        Path broken = Files.writeString(tempDir.resolve("broken.xml"), "<rdf:RDF><unclosed>");

        assertThatThrownBy(() -> reader.read(empty)).isInstanceOf(IOException.class)
                .hasMessageContaining("No .xml files found in archive");
        assertThatThrownBy(() -> reader.read(broken)).isInstanceOf(IOException.class)
                .hasMessageContaining("Could not read the data file broken.xml");
        assertThatThrownBy(() -> reader.read(tempDir.resolve("absent.xml"))).isInstanceOf(IOException.class)
                .hasMessageContaining("Data file not found");
    }

    @Test
    void aLargeDocumentIsReadWithoutKeepingItsTriples() throws Exception {
        // 200,000 objects after the header; the reader keeps the header statements only.
        Path file = tempDir.resolve("large.xml");
        try (var out = Files.newBufferedWriter(file)) {
            out.write(RDF_OPEN);
            out.write("""
                      <md:FullModel rdf:about="urn:uuid:aaaaaaaa-0000">
                        <md:Model.profile>urn:example:ProfileA</md:Model.profile>
                      </md:FullModel>
                    """);
            for (int i = 0; i < 200_000; i++) {
                out.write("  <ex:Thing rdf:ID=\"_" + i + "\"><ex:Thing.name>n" + i + "</ex:Thing.name>"
                        + "<ex:Thing.value>" + i + "</ex:Thing.value></ex:Thing>\n");
            }
            out.write("</rdf:RDF>\n");
        }

        InstanceHeader header = readOne(file);

        assertThat(header.profiles()).containsExactly("urn:example:ProfileA");
        assertThat(header.modelIri()).isEqualTo("urn:uuid:aaaaaaaa-0000");
    }

    @Test
    void underAPathPolicyAFileOutsideTheRootsIsRefused() throws Exception {
        Path file = xml("model.xml", """
                  <md:FullModel rdf:about="urn:uuid:bbbbbbbb-0000">
                    <md:Model.profile>urn:example:ProfileA</md:Model.profile>
                  </md:FullModel>
                """);
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));

        assertThatThrownBy(() -> PathPolicy.runWith(PathPolicy.builder().root(allowed).build(), () -> reader.read(file)))
                .isInstanceOf(PathNotAllowedException.class);
        assertThat(PathPolicy.runWith(PathPolicy.builder().root(tempDir).build(), () -> reader.read(file))).hasSize(1);
    }

    @Test
    void onlySyntaxesThatFetchNothingAreRead() throws Exception {
        // A JSON-LD parser would load its context from the network; such a file is refused unread.
        for (String name : List.of("model.jsonld", "model.trix", "model.rj", "model")) {
            Path file = Files.writeString(tempDir.resolve(name),
                    "{\"@context\": \"http://host.invalid/context.jsonld\", \"@id\": \"urn:x\"}");
            assertThatThrownBy(() -> reader.read(file))
                    .as(name).isInstanceOf(IOException.class).hasMessageContaining("Not an instance data file");
        }
        Path turtle = Files.writeString(tempDir.resolve("model.ttl"), """
                <urn:uuid:cccccccc-0000> a <http://www.w3.org/ns/dcat#Dataset> ;
                    <http://purl.org/dc/terms/conformsTo> <urn:example:Catalogued> .
                """);
        assertThat(readOne(turtle).profiles()).containsExactly("urn:example:Catalogued");
    }

    @Test
    void aFloodOfHeaderStatementsIsRefusedNotKept() throws Exception {
        Path file = tempDir.resolve("flood.xml");
        try (var out = Files.newBufferedWriter(file)) {
            out.write(RDF_OPEN);
            for (int i = 0; i <= InstanceHeaderReader.MAX_HEADER_STATEMENTS; i++) {
                out.write("  <rdf:Description rdf:about=\"urn:u:" + i + "\"><dcterms:requires rdf:resource=\"urn:v:"
                        + i + "\"/></rdf:Description>\n");
            }
            out.write("</rdf:RDF>\n");
        }

        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("header statements");
    }

    @Test
    void textOfTheFileInAParseErrorCannotForgeALogLine() throws Exception {
        Path file = Files.writeString(tempDir.resolve("forged.xml"), RDF_OPEN
                + "  <ex:Thing rdf:about=\"urn:x\"><ex:p>&#10;[ERROR] forged</ex:p></ex:Thing>\n"
                + "  <ex:Thing rdf:about=\"urn:y\"><ex:q rdf:resource=\"bad iri&#10;[ERROR] forged line\"></ex:Thing>\n");

        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("forged.xml")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("\n").doesNotContain("\r"));
    }

    @Test
    void aBlankXmlBaseIsRefused() throws Exception {
        ProfileCatalog catalog = ProfileCatalog.load(List.of(tempDir.resolve("lib")));
        assertThatThrownBy(() -> new InstanceHeaderReader(catalog, List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] zipBytes(Object... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry((String) namesAndContents[i]));
                out.write((byte[]) namesAndContents[i + 1]);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
