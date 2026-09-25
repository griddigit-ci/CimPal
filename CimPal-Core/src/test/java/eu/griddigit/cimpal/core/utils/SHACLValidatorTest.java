/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topbraid.shacl.vocabulary.SH;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SHACLValidatorTest {

    private static final String BASE = "http://example.com/data";
    private static final String SIZE = "urn:test:size";

    /** ex:Thing needs exactly one ex:size, typed xsd:float. */
    private static final String SIZE_SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <urn:test:> .
            ex:ThingShape a sh:NodeShape ;
                sh:targetClass ex:Thing ;
                sh:property ex:ThingSizeShape .
            ex:ThingSizeShape a sh:PropertyShape ;
                sh:path ex:size ;
                sh:datatype xsd:float ;
                sh:minCount 1 ;
                sh:name "Thing.size" ;
                sh:group ex:Sizes .
            """;

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String rdfXml(String body) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                %s
                </rdf:RDF>
                """.formatted(body);
    }

    private static Model ttl(String body) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString("@prefix ex: <urn:test:> .\n" + body, Lang.TTL).parse(model);
        return model;
    }

    private SHACLValidationOptions.Builder sizeValidation(String dataBody) throws Exception {
        return SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml(dataBody)))
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE);
    }

    @Test
    void untypedLiteralFailsDatatypeConstraintWithoutMapAndWarns() throws Exception {
        SHACLValidationReport report = new SHACLValidator(
                sizeValidation("<ex:Thing rdf:about=\"#_1\"><ex:size>1.5</ex:size></ex:Thing>").build()).validate();

        assertFalse(report.conforms());
        assertEquals(1, report.getResults().size());
        assertEquals(Map.of("Violation", 1L), report.countBySeverity());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("No datatype map")),
                () -> "warnings: " + report.getWarnings());
    }

    @Test
    void datatypeMapTypesLiteralsWhileParsing() throws Exception {
        SHACLValidationReport report = new SHACLValidator(
                sizeValidation("<ex:Thing rdf:about=\"#_1\"><ex:size>1.5</ex:size></ex:Thing>")
                        .datatypeMap(Map.of(SIZE, XSDDatatype.XSDfloat))
                        .build()).validate();

        assertTrue(report.conforms(), () -> "results: " + report.getResults().size());
        assertTrue(report.getWarnings().isEmpty(), () -> "warnings: " + report.getWarnings());
    }

    @Test
    void datatypeMapFileIsReadInTheBundledFormat() throws Exception {
        Path mapFile = write("map.properties",
                "urn\\:test\\:size=Datatype[http\\://www.w3.org/2001/XMLSchema\\#float -> class java.lang.Float]\n");

        SHACLValidationReport report = new SHACLValidator(
                sizeValidation("<ex:Thing rdf:about=\"#_1\"><ex:size>1.5</ex:size></ex:Thing>")
                        .datatypeMapFile(mapFile)
                        .build()).validate();

        assertTrue(report.conforms());
    }

    @Test
    void bundledPresetTypesRealCgmesProperties() throws Exception {
        Path data = write("eq.xml", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#">
                  <cim:ACLineSegment rdf:about="#_line"><cim:ACLineSegment.r>0.25</cim:ACLineSegment.r></cim:ACLineSegment>
                </rdf:RDF>
                """);
        Path shapes = write("line.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix cim: <http://iec.ch/TC57/CIM100#> .
                <urn:test:LineShape> a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;
                    sh:property [ sh:path cim:ACLineSegment.r ; sh:datatype xsd:float ; sh:minInclusive 0.0 ] .
                """);

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptionsPresets.cgmes30()
                .dataFiles(data).shapeFiles(shapes).build()).validate();

        assertTrue(report.conforms(), () -> "results: " + report.getResults().size());
        assertEquals("eq.xml", report.getDatasetName());
    }

    @Test
    void everyBundledPresetLoads() throws Exception {
        assertTrue(DatatypeMapPreset.NONE.load().isEmpty());
        for (DatatypeMapPreset preset : DatatypeMapPreset.values()) {
            if (preset != DatatypeMapPreset.NONE) {
                assertFalse(preset.load().isEmpty(), preset.name());
            }
        }
    }

    @Test
    void resultsCarrySourceShapeMetadataAndReportModelIsAShaclReport() throws Exception {
        SHACLValidationReport report = new SHACLValidator(
                sizeValidation("<ex:Thing rdf:about=\"#_1\"/>").build()).validate();

        assertFalse(report.conforms());
        SHACLValidationResult result = report.getResults().getFirst();
        assertEquals("Thing.size", result.getName());
        assertTrue(result.getFocusNode().endsWith("_1"), result.getFocusNode());
        assertTrue(result.getGroup().endsWith("Sizes"), result.getGroup());

        Model model = report.getReportModel();
        Resource shReport = model.listSubjectsWithProperty(RDF.type, SH.ValidationReport).next();
        assertFalse(shReport.getProperty(SH.conforms).getBoolean());
        assertEquals(1, model.listObjectsOfProperty(shReport, SH.result).toList().size());
    }

    @Test
    void followsOwlImportsOfShapeFiles() throws Exception {
        write("constraints/imported/size.ttl", SIZE_SHAPES);
        Path root = write("constraints/root.ttl",
                "<urn:test:root> <http://www.w3.org/2002/07/owl#imports> <imported/size.ttl> .");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(root)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size());
        assertEquals("Thing.size", report.getResults().getFirst().getName());
    }

    @Test
    void unresolvableImportIsReportedAsWarning() throws Exception {
        // A host outside the remote-import allowlist is refused without being contacted.
        Path root = write("root.ttl", SIZE_SHAPES
                + "<urn:test:root> <http://www.w3.org/2002/07/owl#imports> <https://example.com/shapes.ttl> .\n");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(root)
                .xmlBase(BASE)
                .build()).validate();

        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("owl:imports")),
                () -> "warnings: " + report.getWarnings());
    }

    @Test
    void dataFilesAreUnionedWithOneBaseSoRelativeReferencesJoin() throws Exception {
        Path eq = write("eq.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));
        Path ssh = write("ssh.xml", rdfXml("<rdf:Description rdf:about=\"#_1\"><ex:size>2.0</ex:size></rdf:Description>"));

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(eq, ssh)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .datatypeMap(Map.of(SIZE, XSDDatatype.XSDfloat))
                .xmlBase(BASE)
                .build()).validate();

        assertTrue(report.conforms(), () -> "results: " + report.getResults().size());
    }

    @Test
    void zipArchivesOfRdfXmlAreRead() throws Exception {
        Path zip = tempDir.resolve("model.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("nested/eq.xml"));
            out.write(rdfXml("<ex:Thing rdf:about=\"#_1\"/>").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(zip)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size());
    }

    @Test
    void nonRdfXmlDataIsParsedByExtension() throws Exception {
        Path data = write("data.ttl", "@prefix ex: <urn:test:> .\n<http://example.com/data#_1> a ex:Thing .\n");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(data)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size());
    }

    @Test
    void suppliedModelsAreUsedAndNeverModified() throws Exception {
        Model data = ttl("ex:t a ex:Thing ; ex:size \"1.5\" ; ex:count 5 .");
        Model shapes = ttl("""
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                ex:S a sh:NodeShape ; sh:targetClass ex:Thing ;
                    sh:property [ sh:path ex:size ; sh:datatype xsd:float ] ;
                    sh:property [ sh:path ex:count ; sh:datatype xsd:integer ] .
                """);
        long dataSize = data.size();

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataModel(data)
                .shapesModel(shapes)
                .datatypeMap(Map.of(SIZE, XSDDatatype.XSDfloat))
                .build()).validate();

        // ex:size was typed in a copy; the unmapped, already-typed ex:count was left alone.
        assertTrue(report.conforms(), () -> "results: " + report.getResults().size());
        assertEquals(dataSize, data.size());
        assertEquals(XSDDatatype.XSDstring.getURI(), data.listObjectsOfProperty(data.createProperty(SIZE))
                .next().asLiteral().getDatatypeURI());
    }

    @Test
    void resultLimitMarksPartialAndCapsResultsPerShape() throws Exception {
        String things = String.join("\n", List.of(
                "<ex:Thing rdf:about=\"#_1\"/>", "<ex:Thing rdf:about=\"#_2\"/>", "<ex:Thing rdf:about=\"#_3\"/>",
                "<ex:Thing rdf:about=\"#_4\"/>", "<ex:Thing rdf:about=\"#_5\"/>"));

        SHACLValidationReport report = new SHACLValidator(
                sizeValidation(things).maxResultsPerConstraint(2).workers(1).build()).validate();

        assertTrue(report.isPartial());
        assertFalse(report.conforms());
        assertEquals(2, report.getResults().size());
    }

    @Test
    void shapesWithoutTargetsConformButWarn() throws Exception {
        Path shapes = write("untargeted.ttl", """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <urn:test:S> a sh:NodeShape ; sh:property [ sh:path <urn:test:p> ; sh:minCount 1 ] .
                """);

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(shapes)
                .xmlBase(BASE)
                .build()).validate();

        assertTrue(report.conforms());
        assertTrue(report.getWarnings().stream().anyMatch(w -> w.contains("no targets")),
                () -> "warnings: " + report.getWarnings());
    }

    @Test
    void missingInputsFailWithTheirPath() throws Exception {
        Path shapes = write("shapes.ttl", SIZE_SHAPES);
        Path data = write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));

        FileNotFoundException missingData = assertThrows(FileNotFoundException.class, () -> new SHACLValidator(
                SHACLValidationOptions.builder().dataFiles(tempDir.resolve("nope.xml")).shapeFiles(shapes)
                        .xmlBase(BASE).build()).validate());
        assertTrue(missingData.getMessage().contains("nope.xml"));

        FileNotFoundException missingShapes = assertThrows(FileNotFoundException.class, () -> new SHACLValidator(
                SHACLValidationOptions.builder().dataFiles(data).shapeFiles(tempDir.resolve("nope.ttl"))
                        .xmlBase(BASE).build()).validate());
        assertTrue(missingShapes.getMessage().contains("nope.ttl"));
    }

    @Test
    void reportExportsToExcelAndTurtle() throws Exception {
        SHACLValidationReport report = new SHACLValidator(
                sizeValidation("<ex:Thing rdf:about=\"#_1\"/>").build()).validate();

        Path xlsx = tempDir.resolve("out/report.xlsx");
        Path turtle = tempDir.resolve("out/report.ttl");
        report.writeExcel(xlsx);
        report.writeTurtle(turtle);

        assertTrue(Files.size(xlsx) > 0);
        Model reread = RDFDataMgr.loadModel(turtle.toUri().toString());
        assertTrue(reread.isIsomorphicWith(report.getReportModel()));
    }
}
