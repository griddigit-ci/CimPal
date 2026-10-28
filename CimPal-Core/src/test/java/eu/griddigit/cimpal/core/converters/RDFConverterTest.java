/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.models.RDFConvertOptions;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topbraid.shacl.vocabulary.SH;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the converter resolves and writes the relative identifiers of CGMES files, which use
 * {@code rdf:ID} and {@code #} references without an {@code xml:base}; and golden-master tests
 * (TEST-3, convert group) for every target format and RDFS option, with snapshots in
 * {@code snapshots/convert/}. Turtle and JSON-LD are compared by isomorphism; RDF/XML, CIMXML and
 * RDFS CIMXML also as text, because their ordering and layout are the behaviour under test.
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

    // ---- Golden master (TEST-3, convert group): snapshots in snapshots/convert/ ----

    /** CGMES 3.0 instance data to the legacy Turtle target (what {@code convert} writes for .ttl). */
    @Test
    void goldenCgmes30InstanceToTurtle() throws IOException {
        String turtle = write(options(eq30Xml(), BASE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE));

        assertThat(turtle).doesNotContain("file:");
        SNAPSHOTS.assertIsomorphic("eq30-turtle", read(turtle, Lang.TURTLE));
    }

    @Test
    void goldenCgmes24InstanceToTurtle() throws IOException {
        String turtle = write(options(eq24Xml(), BASE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE));

        assertThat(turtle).doesNotContain("file:").contains(CIM16_NS);
        SNAPSHOTS.assertIsomorphic("eq24-turtle", read(turtle, Lang.TURTLE));
    }

    /** The legacy JSON-LD target ({@code convert} with a .jsonld output) writes full IRIs. */
    @Test
    void goldenCgmes30InstanceToLegacyJsonLd() throws IOException {
        String jsonLd = write(options(eq30Xml(), BASE).targetFormat(RDFConvertOptions.RDFFormats.JSONLD));

        assertThat(jsonLd).doesNotContain("file:").contains(BASE + "#_line1");
        SNAPSHOTS.assertIsomorphic("eq30-jsonld-legacy", read(jsonLd, Lang.JSONLD));
    }

    /** The GUI's JSON-LD 1.1 target with the default options (compact arrays, not ordered, no native types). */
    @Test
    void goldenCgmes24InstanceToJsonLd11Default() throws IOException {
        String jsonLd = write(options(eq24Xml(), BASE).jenaTargetFormat(RDFFormat.JSONLD11_PRETTY));

        assertThat(jsonLd).doesNotContain("file:");
        SNAPSHOTS.assertIsomorphic("eq24-jsonld11-default", read(jsonLd, Lang.JSONLD11));
    }

    /**
     * Ordered output with native types: typed literals become JSON numbers and booleans. The text
     * is the snapshot; it is the same over runs, but the keys are not sorted (finding C2).
     */
    @Test
    void goldenTypedDataToJsonLd11OrderedWithNativeTypes() throws IOException {
        Path source = Files.writeString(tempDir.resolve("typed.ttl"), TYPED_TURTLE);
        RDFConvertOptions.Builder options = RDFConvertOptions.builder().sourceFile(source.toFile())
                .sourceFormat(RDFConvertOptions.RDFFormats.TURTLE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE)
                .xmlBase(BASE).jenaTargetFormat(RDFFormat.JSONLD11_PRETTY)
                .jsonLdOrdered(true).jsonLdUseNativeTypes(true);

        String jsonLd = write(options);

        SNAPSHOTS.assertTextEquals("typed-jsonld11-ordered-native.jsonld", jsonLd);
        assertThat(jsonLd).doesNotContain("\"false\"", "\"100.0\"", "\"2\"");
        // Native types are lossy: the float comes back as xsd:double, so this is not isomorphic to the source.
        Model back = read(jsonLd, Lang.JSONLD11);
        assertThat(back.size()).isEqualTo(Snapshots.turtle(TYPED_TURTLE).size());
        SNAPSHOTS.assertIsomorphic("typed-jsonld11-ordered-native", back);
    }

    /** Finding C2: the "ordered" JSON-LD option (GUI checkbox) is passed to Jena but changes nothing in the output. */
    @Disabled("Finding C2 (TEST-3): jsonLdOrdered has no effect on the JSON-LD 1.1 output")
    @Test
    void orderedJsonLdSortsTheKeysOfEachNode() throws IOException {
        Path source = Files.writeString(tempDir.resolve("typed.ttl"), TYPED_TURTLE);
        String jsonLd = write(RDFConvertOptions.builder().sourceFile(source.toFile())
                .sourceFormat(RDFConvertOptions.RDFFormats.TURTLE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE)
                .xmlBase(BASE).jenaTargetFormat(RDFFormat.JSONLD11_PRETTY).jsonLdOrdered(true));

        List<String> keys = new ArrayList<>(JsonMapper.builder().build().readTree(jsonLd).propertyNames());
        keys.remove("@context");
        assertThat(keys).isSorted();
    }

    @Test
    void goldenCgmes30InstanceToRdfXmlPlain() throws IOException {
        assertRdfXmlGolden("eq30-rdfxml-plain", eq30Xml(), jenaGolden(eq30Xml(), RDFFormat.RDFXML_PLAIN));
    }

    @Test
    void goldenCgmes30InstanceToRdfXmlAbbrev() throws IOException {
        assertRdfXmlGolden("eq30-rdfxml-abbrev", eq30Xml(), jenaGolden(eq30Xml(), RDFFormat.RDFXML_ABBREV));
    }

    @Test
    void goldenCgmes24InstanceToRdfXmlPretty() throws IOException {
        assertRdfXmlGolden("eq24-rdfxml-pretty", eq24Xml(), jenaGolden(eq24Xml(), RDFFormat.RDFXML_PRETTY));
    }

    /** {@code convert --rdf-format CIMXML} as the CLI sets it, without sorting. */
    @Test
    void goldenCgmes30InstanceToCimXmlUnsorted() throws IOException {
        assertRdfXmlGolden("eq30-cimxml-unsorted", eq30Xml(), cliCimXml(eq30Xml(), CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY, false, false));
    }

    @Test
    void goldenCgmes30InstanceToCimXmlSorted() throws IOException {
        assertRdfXmlGolden("eq30-cimxml-sorted", eq30Xml(), cliCimXml(eq30Xml(), CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY, true, false));
    }

    @Test
    void goldenCgmes24InstanceToCimXmlSortedByPrefix() throws IOException {
        assertRdfXmlGolden("eq24-cimxml-sorted-prefix", eq24Xml(), cliCimXml(eq24Xml(), CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY, true, true));
    }

    /** The GUI's "instance data" switch sorts subjects by identifier only, not by type first. */
    @Test
    void goldenCgmes30InstanceToCimXmlSortedAsInstanceData() throws IOException {
        assertRdfXmlGolden("eq30-cimxml-sorted-instance", eq30Xml(),
                cliCimXml(eq30Xml(), CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY, true, false).convertInstanceData("true"));
    }

    /** {@code convert --rdf-format RDFS_CIMXML} on the RDFS 2020 profile: the owl:Ontology header goes first. */
    @Test
    void goldenRdfs2020ToRdfsCimXmlSorted() throws IOException {
        File rdfs = Fixtures.copy(RDFS_FEATURE, RDFS_2020, tempDir).toFile();
        RDFConvertOptions.Builder options = cliCimXmlOptions(RDFConvertOptions.builder().sourceFile(rdfs),
                CustomRDFFormat.RDFXML_CUSTOM_PLAIN, true, false).xmlBase(RDFS_BASE);

        String xml = write(options);

        assertThat(xml).doesNotContain("file:").contains("xml:base=\"" + RDFS_BASE + "\"");
        assertThat(xml.indexOf("Ontology")).isLessThan(xml.indexOf("IdentifiedObject"));
        SNAPSHOTS.assertTextEquals("rdfs2020-rdfs-cimxml-sorted.xml", xml);
        Snapshots.assertIsomorphic(fixtureModel(RDFS_FEATURE, RDFS_2020, RDFS_BASE), readRdfs(xml));
    }

    @Test
    void goldenRdfs2019ToRdfsCimXmlUnsorted() throws IOException {
        File rdfs = Fixtures.copy(RDFS_FEATURE, RDFS_2019, tempDir).toFile();
        RDFConvertOptions.Builder options = cliCimXmlOptions(RDFConvertOptions.builder().sourceFile(rdfs),
                CustomRDFFormat.RDFXML_CUSTOM_PLAIN, false, false).xmlBase(RDFS_2019_BASE);

        String xml = write(options);

        SNAPSHOTS.assertTextEquals("rdfs2019-rdfs-cimxml-unsorted.xml", xml);
        Snapshots.assertIsomorphic(fixtureModel(RDFS_FEATURE, RDFS_2019, RDFS_2019_BASE),
                RDFParser.fromString(xml, Lang.RDFXML).base(RDFS_2019_BASE).toModel());
    }

    /** {@code convert --input a --input b ...}: a plain union of RDF/XML, JSON-LD and Turtle, each parsed by its extension. */
    @Test
    void goldenUnionOfMixedSyntaxes() throws IOException {
        File eq = sourceFile(eq30(), "eq.xml");
        File ssh = Fixtures.copy(FEATURE, SSH_JSONLD, tempDir).toFile();
        File typed = Files.writeString(tempDir.resolve("typed.ttl"), TYPED_TURTLE).toFile();
        RDFConvertOptions.Builder options = RDFConvertOptions.builder().modelUnionFlag(true)
                .modelUnionFiles(List.of(eq, ssh, typed)).sourceFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase(BASE);

        String turtle = write(options);

        assertThat(turtle).doesNotContain("file:");
        Model union = read(turtle, Lang.TURTLE);
        assertThat(union.getNsPrefixMap()).containsEntry("cim", TestModels.CIM_NS).containsEntry("ex", "urn:test:typed#");
        SNAPSHOTS.assertIsomorphic("union-mixed-syntaxes", union);
    }

    /**
     * The RDFS Union tab's detailed union with the "notDefined" stereotype found automatically and
     * the package fixed from the surviving ontology header's {@code dcat:keyword}.
     */
    @Test
    void goldenDetailedUnionWithDeletionStereotypeAndPackageFix() throws IOException {
        RDFConverter converter = convert(detailedUnion().modelUnionFixPackage(true));
        Model model = converter.convertedModel;

        String cim = TestModels.CIM_NS;
        assertThat(model.listStatements(model.createResource(cim + "Obsolete"), null, (RDFNode) null).hasNext()).isFalse();
        assertThat(model.listStatements(model.createResource(cim + "Terminal.legacyCode"), null, (RDFNode) null).hasNext()).isFalse();
        assertThat(model.listStatements(model.createResource(cim + "Breaker"), null, (RDFNode) null).hasNext()).isTrue();
        assertThat(model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).toList())
                .containsExactly(model.createResource(DELTA_NS + "Ontology"));
        assertThat(model.listObjectsOfProperty(model.createProperty(CIMS_NS, "multiplicity")).toList()).isNotEmpty();
        assertThat(model.listObjectsOfProperty(model.createResource(cim + "ACLineSegment.r"), model.createProperty(CIMS_NS, "multiplicity")).toList())
                .containsExactly(model.createResource(CIMS_NS + "M:0..1"));
        assertThat(model.listObjectsOfProperty(model.createProperty(CIMS_NS, "belongsToCategory")).toSet())
                .containsExactly(model.createResource(DELTA_NS + "Package_MDProfile"));
        SNAPSHOTS.assertIsomorphic("detailed-union-package-fix", model);
    }

    /** The same union with the stereotype, package and ontology URI given explicitly (prefixed names), written as RDFS CIMXML. */
    @Test
    void goldenDetailedUnionWithConfiguredUrisToRdfsCimXml() throws IOException {
        RDFConvertOptions.Builder options = cliCimXmlOptions(detailedUnion(), CustomRDFFormat.RDFXML_CUSTOM_PLAIN, true, false)
                .xmlBase(RDFS_BASE)
                .detailedUnionDeletionStereotype("notDefined")
                .detailedUnionPackageUri("meq:Package_MiniEquipmentProfile")
                .detailedUnionOntologyUri("meq:Ontology");

        String xml = write(options);

        Model model = readRdfs(xml);
        assertThat(model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).toList())
                .containsExactly(model.createResource(MEQ_NS + "Ontology"));
        assertThat(model.listObjectsOfProperty(model.createProperty(CIMS_NS, "belongsToCategory")).toSet())
                .containsExactly(model.createResource(MEQ_NS + "Package_MiniEquipmentProfile"));
        SNAPSHOTS.assertTextEquals("detailed-union-configured-rdfs-cimxml.xml", xml);
        SNAPSHOTS.assertIsomorphic("detailed-union-configured", model);
    }

    /** An unknown prefix in a configured identifier is refused. */
    @Test
    void detailedUnionRefusesAnUnknownPrefix() {
        RDFConverter converter = new RDFConverter(detailedUnion().detailedUnionPackageUri("nope:Package_X").build());

        assertThatThrownBy(converter::convert).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope:Package_X");
    }

    /**
     * Finding C1: an enumeration in the deletion package loses its members' rdf:type, but a member
     * typed with rdf:type keeps its other statements (here its label), so it is left as an orphan.
     */
    @Test
    void goldenDetailedUnionLeavesTypedEnumerationMembersLabels() throws IOException {
        Model model = convert(detailedUnion()).convertedModel;
        Resource typed = model.createResource(TestModels.CIM_NS + "ObsoleteKind.typed");
        Resource named = model.createResource(TestModels.CIM_NS + "ObsoleteKind.named");

        assertThat(model.contains(typed, RDF.type)).isFalse();
        assertThat(model.contains(typed, RDFS.label)).isTrue();
        assertThat(model.listStatements(named, null, (RDFNode) null).hasNext()).isFalse();
    }

    @Disabled("Finding C1 (TEST-3): typed members of a deleted enumeration keep their other statements")
    @Test
    void detailedUnionRemovesEveryStatementOfADeletedEnumerationsMembers() throws IOException {
        Model model = convert(detailedUnion()).convertedModel;

        assertThat(model.listStatements(model.createResource(TestModels.CIM_NS + "ObsoleteKind.typed"), null, (RDFNode) null).toList()).isEmpty();
    }

    /** Inheritance only: just rdf:type, subClassOf, domain and range survive, written as Turtle. */
    @Test
    void goldenInheritanceOnly() throws IOException {
        RDFConverter converter = convert(rdfs2020().inheritanceOnly(true));

        assertThat(converter.convertedModel.listStatements(null, RDFS.label, (RDFNode) null).hasNext()).isFalse();
        SNAPSHOTS.assertIsomorphic("rdfs2020-inheritance-only", converter.convertedModel);
        assertThatThrownBy(() -> converter.writeInheritanceModel(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** With the inheritance list, every class lists all its (concrete) descendants as owl:members. */
    @Test
    void goldenInheritanceList() throws IOException {
        RDFConverter all = convert(rdfs2020().inheritanceOnly(true).inheritanceList(true));
        RDFConverter concrete = convert(rdfs2020().inheritanceOnly(true).inheritanceList(true).inheritanceListConcrete(true));

        SNAPSHOTS.assertIsomorphic("rdfs2020-inheritance-list", read(writeInheritance(all), Lang.TURTLE));
        SNAPSHOTS.assertIsomorphic("rdfs2020-inheritance-list-concrete", read(writeInheritance(concrete), Lang.TURTLE));
        assertThat(concrete.modelInheritance.size()).isLessThan(all.modelInheritance.size());
    }

    @Test
    void goldenAddOwl() throws IOException {
        Model model = convert(rdfs2020().addOwl(true)).convertedModel;

        assertThat(model.contains(model.createResource(TestModels.CIM_NS + "ACLineSegment"), RDF.type, OWL2.Class)).isTrue();
        assertThat(model.contains(model.createResource(TestModels.CIM_NS + "Resistance"), RDF.type, OWL2.DatatypeProperty)).isTrue();
        SNAPSHOTS.assertIsomorphic("rdfs2020-add-owl", model);
    }

    /**
     * Strip prefixes drops every prefix no subject, predicate or object uses; dcterms is always
     * declared afterwards. The prefix map is the behaviour here, so it is a text snapshot.
     */
    @Test
    void goldenStripPrefixes() throws IOException {
        Model kept = convert(detailedUnion()).convertedModel;
        Model stripped = convert(detailedUnion().stripPrefixes(true)).convertedModel;

        assertThat(kept.getNsPrefixMap()).containsKey("unused");
        assertThat(stripped.getNsPrefixMap()).doesNotContainKeys("unused").containsKeys("cim", "cims", "dcterms");
        assertThat(stripped.isIsomorphicWith(kept)).isTrue();
        SNAPSHOTS.assertTextEquals("detailed-union-prefixes-stripped.txt", prefixes(stripped));
    }

    @Test
    void goldenDropOntologyHeaders() throws IOException {
        Model model = convert(rdfs2020().keepOntologyHeaders(false)).convertedModel;

        assertThat(model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).hasNext()).isFalse();
        SNAPSHOTS.assertIsomorphic("rdfs2020-no-ontology-header", model);
    }

    /** A header that declares prefixes with sh:declare is kept even when headers are dropped. */
    @Test
    void ontologyHeaderWithShDeclareIsKeptWhenHeadersAreDropped() throws IOException {
        File shapes = Files.writeString(tempDir.resolve("shapes.ttl"), SH_DECLARE_TURTLE).toFile();
        Model model = convert(RDFConvertOptions.builder().sourceFile(shapes).sourceFormat(RDFConvertOptions.RDFFormats.TURTLE)
                .targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase("").keepOntologyHeaders(false)).convertedModel;

        assertThat(model.listSubjectsWithProperty(RDF.type, OWL2.Ontology).toList()).hasSize(1);
        assertThat(model.listStatements(null, SH.declare, (RDFNode) null).toList()).hasSize(2);
    }

    /** Identical sh:declare blank nodes collapse to one; different ones stay. */
    @Test
    void goldenRemoveDuplicateShDeclareBlankNodes() {
        Model model = Snapshots.turtle(SH_DECLARE_TURTLE);
        assertThat(model.listStatements(null, SH.declare, (RDFNode) null).toList()).hasSize(3);

        Model result = RDFConverter.removeDuplicateShDeclareBlankNodes(model);

        assertThat(result).isSameAs(model);
        assertThat(result.listStatements(null, SH.declare, (RDFNode) null).toList()).hasSize(2);
        SNAPSHOTS.assertIsomorphic("sh-declare-deduplicated", result);
    }

    /** An empty Turtle file converts to an empty model; only the dcterms prefix is declared. */
    @Test
    void emptyTurtleSourceGivesAnEmptyModel() throws IOException {
        File empty = Files.writeString(tempDir.resolve("empty.ttl"), "").toFile();

        String turtle = write(RDFConvertOptions.builder().sourceFile(empty).sourceFormat(RDFConvertOptions.RDFFormats.TURTLE)
                .targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase(""));

        assertThat(read(turtle, Lang.TURTLE).isEmpty()).isTrue();
        assertThat(turtle.strip()).isEqualTo("@prefix dcterms: <http://purl.org/dc/terms/> .");
    }

    /** An empty .xml file is not RDF/XML: the parser fails. */
    @Test
    void emptyRdfXmlSourceIsAParseError() throws IOException {
        File empty = Files.writeString(tempDir.resolve("empty.xml"), "").toFile();
        RDFConverter converter = new RDFConverter(RDFConvertOptions.builder().sourceFile(empty)
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase("").build());

        assertThatThrownBy(converter::convert).isInstanceOf(RiotException.class);
    }

    @Test
    void malformedSourceIsAParseError() throws IOException {
        File broken = Files.writeString(tempDir.resolve("broken.ttl"), "<urn:a> <urn:b> .").toFile();
        RDFConverter converter = new RDFConverter(RDFConvertOptions.builder().sourceFile(broken)
                .sourceFormat(RDFConvertOptions.RDFFormats.TURTLE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase("").build());

        assertThatThrownBy(converter::convert).isInstanceOf(RiotException.class);
        assertThat(converter.convertedModel).isNull();
    }

    @Test
    void missingSourcesAreRefused() {
        RDFConvertOptions.Builder noSource = RDFConvertOptions.builder().sourceFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase("");

        assertThatThrownBy(noSource::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("sourceFile");
        assertThatThrownBy(() -> noSource.modelUnionFlag(true).modelUnionFiles(List.of()).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("modelUnionFiles");
        assertThatThrownBy(() -> noSource.modelUnionFlag(false).modelUnionFlagDetailed(true).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("modelUnionDetailedFiles");
    }

    @Test
    void writingBeforeConvertIsRefused() throws IOException {
        RDFConverter converter = new RDFConverter(options(eq30Xml(), BASE).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).build());

        assertThatThrownBy(() -> converter.writeConvertedModel(new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("convert()");
    }

    // ---- golden-master helpers ----

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("convert");
    private static final String FEATURE = "convert";
    private static final String RDFS_FEATURE = "shacl-generation";
    private static final String RDFS_2020 = "mini-equipment-2020-cim100.rdf";
    private static final String RDFS_2019 = "mini-equipment-2019-cim16.rdf";
    private static final String RDFS_DELTA = "mini-delta-2020-cim100.ttl";
    private static final String SSH_JSONLD = "ssh-cim100.jsonld";
    /** The xml:base the RDFS fixtures declare. */
    private static final String RDFS_BASE = "http://iec.ch/TC57/CIM100";
    private static final String RDFS_2019_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";
    private static final String CIM16_NS = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    private static final String EQ24_PROFILE = "http://entsoe.eu/CIM/EquipmentCore/3/1";
    private static final String CIMS_NS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
    private static final String MEQ_NS = "http://example.com/ns/MiniEquipment-EU#";
    private static final String DELTA_NS = "http://example.com/ns/MiniDelta-EU#";

    /** Typed literals, for the JSON-LD native types option and the mixed union. */
    private static final String TYPED_TURTLE = """
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex:  <urn:test:typed#> .
            <http://example.com/data#_gen1> cim:RotatingMachine.ratedS "100.0"^^xsd:float ;
                cim:Equipment.aggregate false ;
                cim:SynchronousMachine.referencePriority 2 ;
                ex:note "typed values" .
            """;

    /** An ontology header declaring prefixes with sh:declare: two identical blank nodes and a different one. */
    private static final String SH_DECLARE_TURTLE = """
            @prefix sh:   <http://www.w3.org/ns/shacl#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            <urn:test:shapes> a owl:Ontology ;
                sh:declare [ sh:prefix "cim" ; sh:namespace "http://iec.ch/TC57/CIM100#"^^xsd:anyURI ] ,
                           [ sh:prefix "cim" ; sh:namespace "http://iec.ch/TC57/CIM100#"^^xsd:anyURI ] ,
                           [ sh:prefix "eu" ; sh:namespace "http://iec.ch/TC57/CIM100-European#"^^xsd:anyURI ] .
            """;

    /** A CGMES 3.0 EQ file: two prefixes (cim, md), several classes, literals and references. */
    private static TestModels.CgmesModelBuilder eq30() {
        return TestModels.eq().withoutXmlBase()
                .resource("Terminal", "_t2").literal("IdentifiedObject.name", "T2")
                .literal("ACDCTerminal.sequenceNumber", "2").reference("Terminal.ConductingEquipment", "_line1")
                .resource("ACLineSegment", "_line1").literal("IdentifiedObject.name", "Line 1")
                .literal("ACLineSegment.r", "0.5").literal("ACLineSegment.x", "4.25")
                .reference("Equipment.EquipmentContainer", "_area1")
                .resource("Terminal", "_t1").literal("IdentifiedObject.name", "T1")
                .literal("ACDCTerminal.sequenceNumber", "1").reference("Terminal.ConductingEquipment", "_line1")
                .resource("GeographicalRegion", "_area1").literal("IdentifiedObject.name", "Area & <1>");
    }

    /** The same objects as a CGMES 2.4.15 EQ file (CIM16 namespace and profile). */
    private static String eq24Xml() {
        return eq30().toRdfXml().replace(TestModels.CIM_NS, CIM16_NS).replace(TestModels.EQ_PROFILE, EQ24_PROFILE);
    }

    private static String eq30Xml() {
        return eq30().toRdfXml();
    }

    private RDFConvertOptions.Builder rdfs2020() {
        return RDFConvertOptions.builder().sourceFile(Fixtures.copy(RDFS_FEATURE, RDFS_2020, tempDir).toFile())
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase(RDFS_BASE);
    }

    /** RDFS 2020 profile (RDF/XML) plus the Turtle delta, as the RDFS Union tab passes them. */
    private RDFConvertOptions.Builder detailedUnion() {
        File base = Fixtures.copy(RDFS_FEATURE, RDFS_2020, tempDir).toFile();
        File delta = Fixtures.copy(FEATURE, RDFS_DELTA, tempDir).toFile();
        return RDFConvertOptions.builder().modelUnionFlagDetailed(true).modelUnionDetailedFiles(List.of(base, delta))
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.TURTLE).xmlBase(RDFS_BASE);
    }

    private RDFConvertOptions.Builder jenaGolden(String xml, RDFFormat format) throws IOException {
        return options(xml, BASE).jenaTargetFormat(format).showXmlDeclaration("true").tabCharacter("2");
    }

    private RDFConvertOptions.Builder cliCimXml(String xml, RDFFormat format, boolean sort, boolean sortByPrefix) throws IOException {
        return cliCimXmlOptions(options(xml, BASE), format, sort, sortByPrefix);
    }

    /** The CIMXML settings {@code ConvertCommand} passes. */
    private static RDFConvertOptions.Builder cliCimXmlOptions(RDFConvertOptions.Builder options, RDFFormat format, boolean sort, boolean sortByPrefix) {
        return options.sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .rdfXmlFormat(format).showXmlDeclaration("true").showDoctypeDeclaration("false").tabCharacter("2")
                .relativeURIs("same-document").sortRDF(String.valueOf(sort)).rdfSortOptions(String.valueOf(sortByPrefix))
                .convertInstanceData("false").keepOntologyHeaders(true);
    }

    /** Text snapshot (the layout and order are the behaviour) plus isomorphism with the source. */
    private void assertRdfXmlGolden(String name, String sourceXml, RDFConvertOptions.Builder options) throws IOException {
        String xml = write(options);
        assertThat(xml).doesNotContain("file:");
        SNAPSHOTS.assertTextEquals(name + ".xml", xml);
        Snapshots.assertIsomorphic(TestModels.parseRdfXml(sourceXml), read(xml, Lang.RDFXML));
    }

    /** Options for a CGMES source written as source.xml, without xml:base. */
    private RDFConvertOptions.Builder options(String xml, String base) throws IOException {
        return RDFConvertOptions.builder().sourceFile(Files.writeString(tempDir.resolve("source.xml"), xml).toFile())
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML).targetFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .rdfXmlFormat(RDFFormat.RDFXML_PLAIN).xmlBase(base);
    }

    private RDFConverter convert(RDFConvertOptions.Builder options) throws IOException {
        RDFConverter converter = new RDFConverter(options.build());
        converter.convert();
        return converter;
    }

    private static String writeInheritance(RDFConverter converter) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.writeInheritanceModel(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static Model fixtureModel(String feature, String file, String base) {
        return RDFParser.fromString(Fixtures.read(feature, file), Lang.RDFXML).base(base).toModel();
    }

    private static Model readRdfs(String xml) {
        return RDFParser.fromString(xml, Lang.RDFXML).base(RDFS_BASE).toModel();
    }

    private static String prefixes(Model model) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(model.getNsPrefixMap()).forEach((prefix, ns) -> out.append(prefix).append(" = ").append(ns).append('\n'));
        return out.toString();
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
