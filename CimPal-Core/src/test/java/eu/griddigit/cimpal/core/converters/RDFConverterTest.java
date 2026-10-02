/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the converter resolves and writes the relative identifiers of CGMES files, which use
 * {@code rdf:ID} and {@code #} references without an {@code xml:base}.
 */
class RDFConverterTest {

    private static final String BASE = TestModels.XML_BASE;
    private static final Pattern BASE_DIRECTIVE = Pattern.compile("(?im)^\\s*(BASE|@base)\\b");

    @TempDir
    Path tempDir;

    /** Regression: with a base URI set, the source was still read against its file location, so the Turtle kept file:/// IRIs under a BASE they did not match. */
    @Test
    void turtleWithBaseUriWritesIdentifiersRelativeToIt() throws IOException {
        String turtle = write(jena(RDFFormat.TURTLE_PRETTY, BASE));

        assertThat(turtle).doesNotContain("file:")
                .containsPattern("BASE\\s+<" + Pattern.quote(BASE) + ">")
                .contains("<#_gen1>", "<#_area1>");
        Snapshots.assertIsomorphic(source().toModel(), read(turtle, Lang.TURTLE));
    }

    /** Without a base URI, Jena was handed "" and wrote the working directory as BASE. */
    @Test
    void turtleWithoutBaseUriKeepsIdentifiersRelativeToTheDocument() throws IOException {
        String turtle = write(jena(RDFFormat.TURTLE_PRETTY, ""));

        assertThat(turtle).doesNotContain("file:").doesNotContainPattern(BASE_DIRECTIVE).contains("<#_gen1>");
        Snapshots.assertIsomorphic(source().toModel(), read(turtle, Lang.TURTLE));
    }

    @Test
    void fullIrisWritesEveryIriInFullWithoutBase() throws IOException {
        String turtle = write(jena(RDFFormat.TURTLE_PRETTY, BASE).relativeToBase(false));

        assertThat(turtle).doesNotContainPattern(BASE_DIRECTIVE).doesNotContain("<#_gen1>").contains("<" + BASE + "#_gen1>");
        Snapshots.assertIsomorphic(source().toModel(), read(turtle, Lang.TURTLE));
    }

    @Test
    void sshStyleAboutReferencesResolveAgainstTheBaseUriToo() throws IOException {
        TestModels.CgmesModelBuilder ssh = TestModels.ssh().withoutXmlBase()
                .resource("SynchronousMachine", "_gen1").literal("RegulatingCondEq.controlEnabled", "true");

        String trig = write(options(ssh, BASE).jenaTargetFormat(RDFFormat.TRIG_PRETTY));

        assertThat(trig).doesNotContain("file:").contains("<#_gen1>");
    }

    @Test
    void jenaRdfXmlTakesTheDeclarationTabAndBaseSettings() throws IOException {
        String rdfXml = write(jena(RDFFormat.RDFXML_PLAIN, BASE).showXmlDeclaration("true").tabCharacter("4"));

        assertThat(rdfXml).startsWith("<?xml").doesNotContain("file:")
                .contains("xml:base=\"" + BASE + "\"", "rdf:about=\"#_gen1\"", "\n    <rdf:Description");
        Snapshots.assertIsomorphic(source().toModel(), read(rdfXml, Lang.RDFXML));
    }

    @Test
    void cimXmlWithBaseUriWritesSameDocumentReferences() throws IOException {
        String cimXml = write(cimXml(BASE));

        assertThat(cimXml).doesNotContain("file:").contains("xml:base=\"" + BASE + "\"", "rdf:resource=\"#_area1\"");
        Snapshots.assertIsomorphic(source().toModel(), read(cimXml, Lang.RDFXML));
    }

    /** Without a base URI the CIMXML writer used to write the file:/// IRIs of the source in full. */
    @Test
    void cimXmlWithoutBaseUriKeepsIdentifiersRelativeToTheDocument() throws IOException {
        String cimXml = write(cimXml(""));

        assertThat(cimXml).doesNotContain("file:").doesNotContain("xml:base").contains("rdf:resource=\"#_area1\"");
        Snapshots.assertIsomorphic(source().toModel(), read(cimXml, Lang.RDFXML));
    }

    /** The legacy Turtle target that the RDFS Union tab and the CLI use. */
    @Test
    void legacyTurtleTargetWithoutBaseUriWritesNoBaseDirective() throws IOException {
        String turtle = write(options(source(), "").targetFormat(RDFConvertOptions.RDFFormats.TURTLE));

        assertThat(turtle).doesNotContain("file:").doesNotContainPattern(BASE_DIRECTIVE).contains("<#_gen1>");
        Snapshots.assertIsomorphic(source().toModel(), read(turtle, Lang.TURTLE));
    }

    @Test
    void unionReadsEverySourceAgainstTheBaseUri() throws IOException {
        File eq = sourceFile(source(), "eq.xml");
        File ssh = sourceFile(TestModels.ssh().withoutXmlBase().resource("SynchronousMachine", "_gen1")
                .literal("RegulatingCondEq.controlEnabled", "true"), "ssh.xml");
        RDFConvertOptions.Builder options = RDFConvertOptions.builder().modelUnionFlag(true).modelUnionFiles(List.of(eq, ssh))
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase(BASE);

        String turtle = write(options);

        assertThat(turtle).doesNotContain("file:").contains("<#_gen1>");
    }

    @Test
    void onlyRdfXmlTurtleAndTrigWriteRelativeIris() {
        assertThat(List.of(RDFFormat.RDFXML_PLAIN, RDFFormat.RDFXML_PRETTY, RDFFormat.TURTLE_PRETTY, RDFFormat.TURTLE_FLAT, RDFFormat.TRIG_BLOCKS))
                .allMatch(RDFConverter::writesRelativeIris);
        assertThat(List.of(RDFFormat.NTRIPLES, RDFFormat.NQUADS, RDFFormat.JSONLD11_PRETTY, RDFFormat.RDFJSON, RDFFormat.TRIX))
                .noneMatch(RDFConverter::writesRelativeIris);
        assertThat(RDFConverter.isRdfXml(RDFFormat.RDFXML_PRETTY)).isTrue();
        assertThat(RDFConverter.isRdfXml(RDFFormat.TURTLE_PRETTY)).isFalse();
    }

    /** An EQ file shaped like a real CGMES one: a urn:uuid header, rdf:ID objects and # references, no xml:base. */
    private static TestModels.CgmesModelBuilder source() {
        return TestModels.eq().withoutXmlBase()
                .resource("GeneratingUnit", "_gen1").literal("IdentifiedObject.name", "Unit 1")
                .reference("Equipment.EquipmentContainer", "_area1")
                .resource("GeneratingUnit", "_gen2").reference("Equipment.EquipmentContainer", "_area1");
    }

    private RDFConvertOptions.Builder jena(RDFFormat format, String base) throws IOException {
        return options(source(), base).jenaTargetFormat(format);
    }

    private RDFConvertOptions.Builder cimXml(String base) throws IOException {
        return options(source(), base).rdfXmlFormat(CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY)
                .showXmlDeclaration("true").showDoctypeDeclaration("false").tabCharacter("2").relativeURIs("same-document")
                .sortRDF("true").rdfSortOptions("false").convertInstanceData("false");
    }

    private RDFConvertOptions.Builder options(TestModels.CgmesModelBuilder model, String base) throws IOException {
        return RDFConvertOptions.builder().sourceFile(sourceFile(model, "source.xml"))
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .rdfXmlFormat(RDFFormat.RDFXML_PLAIN).xmlBase(base);
    }

    private File sourceFile(TestModels.CgmesModelBuilder model, String name) throws IOException {
        return Files.writeString(tempDir.resolve(name), model.toRdfXml()).toFile();
    }

    private static String write(RDFConvertOptions.Builder options) throws IOException {
        RDFConverter converter = new RDFConverter(options.build());
        converter.convert();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.writeConvertedModel(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Reads output back; identifiers left relative to the document resolve against {@link #BASE}, as the source model's do. */
    private static Model read(String output, Lang lang) {
        return RDFParser.fromString(output, lang).base(BASE).toModel();
    }
}
