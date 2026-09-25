/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import eu.griddigit.cimpal.core.presets.MappingValidationOptionsPresets;
import eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets;
import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import eu.griddigit.cimpal.core.utils.ValidationEngine;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValidationOptionsTest {

    private static final Path DATA = Path.of("eq.xml");
    private static final Path SHAPES = Path.of("shapes.ttl");

    private static SHACLValidationOptions.Builder minimal() {
        return SHACLValidationOptions.builder().dataFiles(DATA).shapeFiles(SHAPES).xmlBase("urn:base");
    }

    private static MappingValidationOptions.Builder minimalMapping() {
        return MappingValidationOptions.builder()
                .mappingCsv(Path.of("mapping.csv"))
                .modelsInput(Path.of("models"))
                .constraintsRoot(Path.of("constraints"))
                .outputDir(Path.of("out"))
                .xmlBase("urn:base");
    }

    @Test
    void defaultsAreJenaWithoutLimitsOrDatatypeMap() {
        SHACLValidationOptions options = minimal().build();

        assertEquals(ValidationEngine.APACHE_JENA, options.getEngine());
        assertEquals(0, options.getMaxResultsPerConstraint());
        assertEquals(0, options.getWorkers());
        assertEquals(DatatypeMapPreset.NONE, options.getDatatypeMapPreset());
        assertNull(options.getDatatypeMapFile());
        assertNull(options.getDatatypeMap());
    }

    @Test
    void dataAndShapesAreRequired() {
        assertThrows(IllegalStateException.class,
                () -> SHACLValidationOptions.builder().shapeFiles(SHAPES).xmlBase("urn:base").build());
        assertThrows(IllegalStateException.class,
                () -> SHACLValidationOptions.builder().dataFiles(DATA).xmlBase("urn:base").build());
    }

    @Test
    void xmlBaseIsRequiredOnlyForDataFiles() {
        assertThrows(IllegalStateException.class, () -> minimal().xmlBase(" ").build());
        assertDoesNotThrow(() -> SHACLValidationOptions.builder()
                .dataModel(ModelFactory.createDefaultModel())
                .shapesModel(ModelFactory.createDefaultModel())
                .build());
    }

    @Test
    void negativeLimitsAreRejected() {
        assertThrows(IllegalStateException.class, () -> minimal().maxResultsPerConstraint(-1).build());
        assertThrows(IllegalStateException.class, () -> minimal().workers(-1).build());
    }

    @Test
    void eachDatatypeMapSourceReplacesThePreviousOne() {
        Path file = Path.of("map.properties");
        Map<String, RDFDatatype> map = Map.of("urn:p", XSDDatatype.XSDfloat);

        SHACLValidationOptions fromFile = minimal().datatypeMap(DatatypeMapPreset.CGMES30_NC25)
                .datatypeMapFile(file).build();
        assertNull(fromFile.getDatatypeMapPreset());
        assertEquals(file, fromFile.getDatatypeMapFile());

        SHACLValidationOptions fromMap = minimal().datatypeMapFile(file).datatypeMap(map).build();
        assertNull(fromMap.getDatatypeMapFile());
        assertEquals(map, fromMap.getDatatypeMap());

        SHACLValidationOptions fromPreset = minimal().datatypeMap(map).datatypeMap(DatatypeMapPreset.CGMES24_NC22).build();
        assertNull(fromPreset.getDatatypeMap());
        assertEquals(DatatypeMapPreset.CGMES24_NC22, fromPreset.getDatatypeMapPreset());

        assertEquals(DatatypeMapPreset.NONE, minimal().datatypeMap(map).datatypeMap((DatatypeMapPreset) null)
                .build().getDatatypeMapPreset());
    }

    @Test
    void nullEngineMeansTheDefault() {
        assertEquals(ValidationEngine.APACHE_JENA, minimal().engine(null).build().getEngine());
        assertEquals(ValidationEngine.APACHE_JENA, minimalMapping().engine(null).build().getEngine());
    }

    @Test
    void inputListsAreCopied() {
        List<Path> files = new ArrayList<>(List.of(DATA));
        SHACLValidationOptions options = SHACLValidationOptions.builder()
                .dataFiles(files).shapeFiles(SHAPES).xmlBase("urn:base").build();
        files.add(Path.of("ssh.xml"));

        assertEquals(List.of(DATA), options.getDataFiles());
        assertThrows(UnsupportedOperationException.class, () -> options.getDataFiles().add(DATA));
    }

    @Test
    void presetsSetTheCgmesDatatypeMapAndBase() {
        SHACLValidationOptions cgmes30 = SHACLValidationOptionsPresets.cgmes30().dataFiles(DATA).shapeFiles(SHAPES).build();
        assertEquals(DatatypeMapPreset.CGMES30_NC25, cgmes30.getDatatypeMapPreset());
        assertEquals(SHACLValidationOptionsPresets.CIM17_XML_BASE, cgmes30.getXmlBase());

        MappingValidationOptions cgmes24 = MappingValidationOptionsPresets.cgmes24()
                .mappingCsv(Path.of("m.csv")).modelsInput(Path.of("in")).constraintsRoot(Path.of("c"))
                .outputDir(Path.of("out")).build();
        assertEquals(DatatypeMapPreset.CGMES24_NC22, cgmes24.getDatatypeMapPreset());
        assertEquals(SHACLValidationOptionsPresets.CIM16_XML_BASE, cgmes24.getXmlBase());
    }

    @Test
    void mappingRunNeedsItsPathsAndBase() {
        assertDoesNotThrow(() -> minimalMapping().build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().mappingCsv(null).build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().modelsInput(null).build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().constraintsRoot(null).build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().outputDir(null).build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().xmlBase("").build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().threads(-1).build());
        assertThrows(IllegalStateException.class, () -> minimalMapping().maxResultsPerConstraint(-1).build());
    }

    @Test
    void previousComparisonIsOnlyForTimestampedRuns() {
        Path previous = Path.of("previous.csv");

        assertThrows(IllegalStateException.class, () -> minimalMapping().previousComparisonCsv(previous).build());
        assertEquals(previous, minimalMapping().timestamped(true).previousComparisonCsv(previous).build()
                .getPreviousComparisonCsv());
    }
}
