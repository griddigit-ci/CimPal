/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.converters;

import eu.griddigit.cimpal.core.models.RDFtoSHACLOptions;
import eu.griddigit.cimpal.core.models.RdfsModelDefinition;
import eu.griddigit.cimpal.core.presets.RDFtoSHACLOptionsPresets;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.topbraid.shacl.vocabulary.SH;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-master tests (TEST-3) for RDFS → SHACL generation on two synthetic profiles in
 * {@code fixtures/shacl-generation/}: the RDFS 2020 layout in the CIM100 namespace (CGMES 3.0)
 * and the RDFS 2019 layout in the CIM16 namespace (CGMES 2.4.15). Snapshots:
 * {@code snapshots/rdfs2shacl/}.
 */
class SHACLFromRDFTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("rdfs2shacl");
    private static final String FEATURE = "shacl-generation";
    private static final String PROFILE_2020 = "mini-equipment-2020-cim100.rdf";
    private static final String PROFILE_2019 = "mini-equipment-2019-cim16.rdf";
    private static final String SHAPES_BASE = "http://example.com/shapes/MiniEquipment";

    @TempDir
    Path tempDir;

    private static Model profile(String file) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(Fixtures.bytes(FEATURE, file)), "", Lang.RDFXML);
        return model;
    }

    private static RdfsModelDefinition definition(String file, String nsUri) {
        return new RdfsModelDefinition(file.replace(".rdf", ""), "meq", nsUri, SHAPES_BASE, "");
    }

    /** The options {@code rdfs2shacl} passes, with the given format and toggles. */
    private static RDFtoSHACLOptions.Builder cliOptions(String file, RDFtoSHACLOptions.RdfsFormatShapes format, String nsUri) {
        return RDFtoSHACLOptions.builder()
                .rdfsModels(new ArrayList<>(List.of(profile(file))))
                .rdfsModelDefinitions(List.of(definition(file, nsUri)))
                .rdfsFormatShapes(format)
                .shaclOutputFormat(RDFtoSHACLOptions.SerializationFormat.TURTLE)
                .cimsNamespace(RDFtoSHACLOptionsPresets.DEFAULT_CIMS_NAMESPACE)
                .iOprefix("mRID")
                .iOuri("http://iec.ch/TC57/CIM100#IdentifiedObject.mRID")
                .shaclCommonPref("")
                .shaclCommonURI("")
                .excludeMRID(false)
                .closedShapes(false)
                .splitDatatypes(false)
                .associationValueTypeOption(false)
                .associationValueTypeOptionSingle(false)
                .shapesOnAbstractOption(false)
                .exportInheritTree(false)
                .shaclURIDatatypeAsResource(false)
                .shaclSkipNcPropertyReference(false)
                .baseprofilesshaclglag(false)
                .baseprofilesshaclignorens(false)
                .baseprofilesshaclglag2nd(false)
                .baseprofilesshaclglag3rd(false)
                .shaclFlagInverse(false)
                .shaclFlagCount(false)
                .shaclFlagCountDefaultURI(false);
    }

    private static RDFtoSHACLOptions.Builder cli2020() {
        return cliOptions(PROFILE_2020, RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2020,
                "http://example.com/ns/MiniEquipment-EU#");
    }

    /** The SHACL header's {@code dct:issued} is the generation time, so literals are normalised first. */
    private static void assertGolden(String name, Model shapes) {
        SNAPSHOTS.assertIsomorphic(name, Snapshots.normalizeLiterals(shapes, Normalizer.timestamps()));
    }

    private static SHACLFromRDF convert(RDFtoSHACLOptions options) throws Exception {
        SHACLFromRDF converter = new SHACLFromRDF(options);
        converter.convert();
        return converter;
    }

    /** Reads every Turtle file the converter saved, keyed by file name, as a single sorted listing. */
    private String savedFiles(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString() + "\n").sorted().reduce("", String::concat);
        }
    }

    @Test
    void cgmes30Rdfs2020WithTheCliDefaults() throws Exception {
        SHACLFromRDF converter = convert(cli2020().build());

        assertEquals(1, converter.getShapeModels().size());
        assertFalse(converter.getRdfsHeaderStatements().isEmpty(), "the owl:Ontology header is read");
        assertGolden("cgmes30-2020-cli", converter.getShapeModels().getFirst());

        // The saved file is the shape model plus the owl:imports and the SHACL header.
        Path out = Files.createDirectories(tempDir.resolve("out"));
        converter.saveShapeModel(out);
        assertEquals("mini-equipment-2020-cim100.ttl\n", savedFiles(out));
        Model saved = RDFDataMgr.loadModel(out.resolve("mini-equipment-2020-cim100.ttl").toString());
        assertGolden("cgmes30-2020-cli__saved", saved);
    }

    @Test
    void cgmes24Rdfs2019WithTheCliDefaults() throws Exception {
        SHACLFromRDF converter = convert(cliOptions(PROFILE_2019, RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2019,
                "http://example.com/ns/MiniEquipment#").iOuri("http://iec.ch/TC57/2013/CIM-schema-cim16#IdentifiedObject.mRID").build());

        assertNull(converter.getRdfsHeaderStatements(), "the 2019 layout has no header, so none is read");
        assertGolden("cgmes24-2019-cli", converter.getShapeModels().getFirst());
    }

    @Test
    void closedShapes() throws Exception {
        SHACLFromRDF converter = convert(cli2020().closedShapes(true).build());

        assertGolden("cgmes30-2020-closed", converter.getShapeModels().getFirst());
    }

    @Test
    void splitDatatypesMovesTheDatatypeShapesToTheirOwnModel() throws Exception {
        SHACLFromRDF converter = convert(cli2020().splitDatatypes(true).build());

        assertEquals(1, converter.getShapeModelDTs().size());
        assertGolden("cgmes30-2020-split__shapes", converter.getShapeModels().getFirst());
        assertGolden("cgmes30-2020-split__datatypes", converter.getShapeModelDTs().getFirst());

        Path out = Files.createDirectories(tempDir.resolve("out"));
        converter.saveShapeModel(out);
        converter.saveShapeModelDT(out);
        assertEquals("datatype-mini-equipment-2020-cim100.ttl\nmini-equipment-2020-cim100.ttl\n", savedFiles(out));
    }

    @Test
    void exportInheritTree() throws Exception {
        SHACLFromRDF converter = convert(cli2020().exportInheritTree(true).build());

        assertEquals(1, converter.getInheritanceModels().size());
        assertGolden("cgmes30-2020-inheritance", converter.getInheritanceModels().getFirst());
    }

    @Test
    void guiDefaultPreset() throws Exception {
        RDFtoSHACLOptions.Builder preset = RDFtoSHACLOptionsPresets.defaultPreset(
                new ArrayList<>(List.of(profile(PROFILE_2020))), RDFtoSHACLOptionsPresets.DEFAULT_IO_PREFIX,
                RDFtoSHACLOptionsPresets.DEFAULT_IO_URI, RDFtoSHACLOptionsPresets.DEFAULT_CIMS_NAMESPACE);
        // The preset leaves the model definitions out (a "todo" in the preset), so build() refuses it.
        assertThrows(IllegalStateException.class, preset::build);

        SHACLFromRDF converter = convert(preset
                .rdfsModelDefinitions(List.of(definition(PROFILE_2020, "http://example.com/ns/MiniEquipment-EU#")))
                .shaclCommonPref("").shaclCommonURI("")
                .build());
        assertGolden("cgmes30-2020-gui-preset", converter.getShapeModels().getFirst());
    }

    @Test
    void generatedShapesAreValidShacl() throws Exception {
        SHACLFromRDF converter = convert(cli2020().closedShapes(true).splitDatatypes(true).build());
        converter.validateShapeModels();

        List<ValidationReport> reports = converter.getValidationReports();
        assertEquals(1, reports.size());
        assertTrue(reports.getFirst().conforms(), () -> "SHACL-SHACL: " + reports.getFirst().getEntries());
    }

    @Test
    void cimtoolOwlIsRefused() {
        RDFtoSHACLOptions options = cli2020().rdfsFormatShapes(RDFtoSHACLOptions.RdfsFormatShapes.CIMTOOL_MERGED_OWL).build();

        assertThrows(UnsupportedOperationException.class, () -> new SHACLFromRDF(options).convert());
    }

    @Test
    void emptyRdfsGivesAShapeModelWithoutShapes() throws Exception {
        SHACLFromRDF converter = convert(cli2020().rdfsModels(new ArrayList<>(List.of(ModelFactory.createDefaultModel()))).build());

        assertGolden("empty-rdfs", converter.getShapeModels().getFirst());
    }

    // ---- Suspected bugs: each test asserts the correct behaviour and fails today ----

    /**
     * The IdentifiedObject cardinality shapes point at {@code <ioUri>CardinalityIO}, but the group
     * the converter declares is {@code <ioUri>CardinalityGroup} (with the label "CardinalityIO").
     */
    @Disabled("Suspected bug (TEST-3): IdentifiedObject cardinality shapes reference an undeclared sh:group")
    @Test
    void everyReferencedGroupIsDeclared() throws Exception {
        Model shapes = convert(cli2020().iOprefix(RDFtoSHACLOptionsPresets.DEFAULT_IO_PREFIX)
                .iOuri(RDFtoSHACLOptionsPresets.DEFAULT_IO_URI).build()).getShapeModels().getFirst();

        List<RDFNode> undeclared = shapes.listObjectsOfProperty(SH.group).toList().stream()
                .filter(group -> !shapes.contains(group.asResource(), RDF.type, SH.PropertyGroup))
                .toList();
        assertEquals(List.of(), undeclared);
    }

    @Test
    void savingBeforeConvertingFails() {
        SHACLFromRDF converter = new SHACLFromRDF(cli2020().build());

        // Current behaviour: an unchecked NullPointerException with a message, not an IllegalStateException.
        NullPointerException failure = assertThrows(NullPointerException.class, () -> converter.saveShapeModel(tempDir));
        assertTrue(failure.getMessage().contains("convert"), failure::getMessage);
    }
}
