/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationReport.ConstraintFileResults;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets;
import eu.griddigit.cimpal.core.stats.RunStats;
import eu.griddigit.cimpal.core.stats.RunStatsSnapshot;
import eu.griddigit.cimpal.core.testsupport.Normalizer;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
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

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SHACLValidatorTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("shacl-validator");

    private static final String BASE = "http://example.com/data";
    private static final String SIZE = "urn:test:size";
    private static final String IMPORTS = "<http://www.w3.org/2002/07/owl#imports>";

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

    /** Writes a ZIP archive of name/content pairs; a {@code byte[]} content is stored as is. */
    private Path zip(String name, Object... namesAndContents) throws Exception {
        return Files.write(tempDir.resolve(name), zipBytes(namesAndContents));
    }

    private static byte[] zipBytes(Object... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry((String) namesAndContents[i]));
                Object content = namesAndContents[i + 1];
                out.write(content instanceof byte[] raw ? raw : content.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A node shape on ex:Thing requiring {@code path}, through an anonymous property shape. */
    private static String requiredOnThing(String shape, String path) {
        return """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <urn:test:> .
                ex:%s a sh:NodeShape ;
                    sh:targetClass ex:Thing ;
                    sh:property [ sh:path ex:%s ; sh:minCount 1 ] .
                """.formatted(shape, path);
    }

    private static List<String> constraintFiles(SHACLValidationReport report) {
        return report.getResultsByConstraintFile().stream().map(ConstraintFileResults::constraintFile).toList();
    }

    private static List<Integer> resultCounts(SHACLValidationReport report) {
        return report.getResultsByConstraintFile().stream().map(file -> file.results().size()).toList();
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
    void importSharedByTwoShapeFilesIsLoadedOnceSoEachFindingIsReportedOnce() throws Exception {
        // Parsing the shared file once per root gave its anonymous property shape a second blank
        // node, so the one missing ex:size was reported twice.
        write("constraints/common.ttl", requiredOnThing("ThingShape", "size"));
        Path eq = write("constraints/eq.ttl", "<urn:test:eq> " + IMPORTS + " <common.ttl> .");
        Path ssh = write("constraints/ssh.ttl", "<urn:test:ssh> " + IMPORTS + " <common.ttl> .");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(eq, ssh)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size(), () -> "results: " + report.getResults());
    }

    @Test
    void unresolvableImportFailsTheValidation() throws Exception {
        // A host outside the remote-import allowlist is refused without being contacted, and
        // the validation fails rather than running without those shapes (SEC-5).
        Path root = write("root.ttl", SIZE_SHAPES
                + "<urn:test:root> <http://www.w3.org/2002/07/owl#imports> <https://example.com/shapes.ttl> .\n");

        SHACLValidator validator = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(root)
                .xmlBase(BASE)
                .build());

        java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, validator::validate);
        assertTrue(failure.getMessage().contains("owl:imports"), failure::getMessage);
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
    void nestedZipArchivesOfDataAreRead() throws Exception {
        Path cgm = zip("cgm.zip", "igm/igm.zip", zipBytes("eq.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")));

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(cgm)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size());
    }

    @Test
    void dataZipWithoutXmlFilesFailsRatherThanPassing() throws Exception {
        // Read as data, an archive of the wrong kind would add nothing, and so conform.
        Path shapesZip = zip("shapes.zip", "shapes.ttl", SIZE_SHAPES);

        IOException failure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(shapesZip)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .build()).validate());

        assertTrue(failure.getMessage().contains("No .xml files found in archive: shapes.zip"), failure::getMessage);
    }

    // ---- ZIP archives of shape files ----

    @Test
    void shapeFilesInAZipAreReadAndImportEachOtherLikeFilesInAFolder() throws Exception {
        // ../shapes/size.ttl from config/root.ttl names the archive's shapes/size.ttl entry.
        Path constraints = zip("constraints.zip",
                "config/root.ttl", "<urn:test:root> " + IMPORTS + " <../shapes/size.ttl> .",
                "shapes/size.ttl", SIZE_SHAPES,
                "readme.txt", "not a shapes file");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size(), () -> "results: " + report.getResults());
        assertEquals("Thing.size", report.getResults().getFirst().getName());
        assertEquals(List.of("size.ttl"), constraintFiles(report));
    }

    @Test
    void importOfAnEntryTheZipLacksFailsTheValidation() throws Exception {
        // The import names a location inside the archive; a file of that name beside the
        // archive must not stand in for the missing entry.
        write("missing.ttl", SIZE_SHAPES);
        Path constraints = zip("constraints.zip",
                "root.ttl", SIZE_SHAPES + "<urn:test:root> " + IMPORTS + " <missing.ttl> .\n");

        IOException failure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .xmlBase(BASE)
                .build()).validate());

        assertTrue(failure.getMessage().contains("owl:imports"), failure::getMessage);
        assertTrue(failure.getMessage().contains("constraints.zip/missing.ttl"), failure::getMessage);
    }

    @Test
    void importLeavingTheZipIsResolvedBesideIt() throws Exception {
        write("shared/size.ttl", SIZE_SHAPES);
        Path constraints = zip("constraints.zip", "root.ttl", "<urn:test:root> " + IMPORTS + " <../shared/size.ttl> .");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(1, report.getResults().size());
        assertEquals(List.of("size.ttl"), constraintFiles(report));
    }

    @Test
    void networkImportsFromAZipAreRefused() throws Exception {
        // An archive entry is as third-party as a shapes file on disk: opening a UNC import would
        // make Windows contact that host with the user's credentials. That includes the archive's
        // own path on another host, which must not pass for an entry of the archive.
        Path data = write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));
        String archivePath = tempDir.resolve("constraints.zip").toUri().getRawPath();
        List<String> importingShapes = List.of(
                "<urn:test:root> " + IMPORTS + " <file://attacker.example/share/x.ttl> .",
                "<urn:test:root> " + IMPORTS + " <file:////attacker.example/share/x.ttl> .",
                "<urn:test:root> " + IMPORTS + " <file://attacker.example" + archivePath + "/x.ttl> .",
                "@base <file://attacker.example/share/> .\n<urn:test:root> " + IMPORTS + " <x.ttl> .");

        for (String shapes : importingShapes) {
            Path constraints = zip("constraints.zip", "root.ttl", SIZE_SHAPES + shapes + "\n");
            assertThrows(PathNotAllowedException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                    .dataFiles(data)
                    .shapeFiles(constraints)
                    .xmlBase(BASE)
                    .build()).validate(), shapes);
        }
    }

    @Test
    void rdfXmlShapeFilesInAZipAreValidated() throws Exception {
        Path constraints = zip("constraints.zip", "SSH.rdf", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:sh="http://www.w3.org/ns/shacl#">
                  <sh:NodeShape rdf:about="urn:test:NamedThingShape">
                    <sh:targetClass rdf:resource="urn:test:Thing"/>
                    <sh:property>
                      <sh:PropertyShape>
                        <sh:path rdf:resource="urn:test:name"/>
                        <sh:minCount rdf:datatype="http://www.w3.org/2001/XMLSchema#integer">1</sh:minCount>
                      </sh:PropertyShape>
                    </sh:property>
                  </sh:NodeShape>
                </rdf:RDF>
                """);

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(List.of("SSH.rdf"), constraintFiles(report));
        assertEquals(List.of(1), resultCounts(report));
    }

    @Test
    void otherRdfFilesInAShapesZipAreNamedInTheWarnings() throws Exception {
        // Only .ttl and .rdf are read as constraints; any other RDF file is pointed out, not dropped silently.
        Path constraints = zip("constraints.zip", "EQ.ttl", SIZE_SHAPES, "SSH.jsonld", "{}");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .datatypeMap(Map.of(SIZE, XSDDatatype.XSDfloat))
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(List.of("constraints.zip: 1 RDF file was not read as constraints; only .ttl and .rdf files are: SSH.jsonld"),
                report.getWarnings());
    }

    @Test
    void shapesZipWithoutShapeFilesFailsRatherThanValidatingNothing() throws Exception {
        Path modelZip = zip("model.zip", "eq.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));

        IOException failure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(modelZip)
                .xmlBase(BASE)
                .build()).validate());

        assertTrue(failure.getMessage().contains("No .ttl or .rdf files found in archive: model.zip"), failure::getMessage);
    }

    @Test
    void shapesZipEntryClimbingOutOfTheArchiveIsRefused() throws Exception {
        Path constraints = zip("constraints.zip", "../evil.ttl", SIZE_SHAPES);

        IOException failure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(constraints)
                .xmlBase(BASE)
                .build()).validate());

        assertTrue(failure.getMessage().contains("Unsafe ZIP entry path"), failure::getMessage);
    }

    @Test
    void shapeAndDataFilesOutsideTheAllowedRootsAreRefusedBeforeTheyAreRead() throws Exception {
        // serve, mcp and run check the paths of a request (PathGuard); the validator checks the files
        // it is given again, so it is safe under an active policy whoever calls it.
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path shapesInside = write("allowed/shapes.ttl", SIZE_SHAPES);
        Path dataInside = write("allowed/data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));
        Path shapesOutside = write("outside/shapes.ttl", SIZE_SHAPES);
        Path dataOutside = write("outside/data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));
        PathPolicy policy = PathPolicy.builder().root(allowed).build();
        java.util.function.BiFunction<Path, Path, SHACLValidator> validator = (shapes, data) ->
                new SHACLValidator(SHACLValidationOptions.builder()
                        .shapeFiles(shapes).dataFiles(data).xmlBase(BASE).build());

        assertThrows(PathNotAllowedException.class,
                () -> PathPolicy.runWith(policy, () -> validator.apply(shapesInside, dataOutside).validate()));
        assertThrows(PathNotAllowedException.class,
                () -> PathPolicy.runWith(policy, () -> validator.apply(shapesOutside, dataInside).validate()));
        SHACLValidationReport report =
                PathPolicy.runWith(policy, () -> validator.apply(shapesInside, dataInside).validate());
        assertEquals(1, report.getResults().size());
    }

    @Test
    void anImportOutsideTheAllowedRootsIsRefusedWhetherOrNotItExists() throws Exception {
        // Otherwise the error tells a shapes file's author what exists outside the roots.
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        write("secret.ttl", SIZE_SHAPES);
        Path data = write("allowed/data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>"));
        PathPolicy policy = PathPolicy.builder().root(allowed).build();
        List<String> messages = new java.util.ArrayList<>();
        for (String target : List.of("../secret.ttl", "../missing.ttl")) {
            Path shapes = write("allowed/shapes-" + messages.size() + ".ttl",
                    "<urn:test:s> " + IMPORTS + " <" + target + "> .");
            Exception refused = assertThrows(Exception.class, () -> PathPolicy.runWith(policy, () -> new SHACLValidator(
                    SHACLValidationOptions.builder().shapeFiles(shapes).dataFiles(data).xmlBase(BASE).build()).validate()));
            messages.add(refused.getClass().getSimpleName() + ": "
                    + refused.getMessage().replace(target.substring(3), "<name>"));
        }
        assertEquals(messages.get(0), messages.get(1));
    }

    @Test
    void runStatsCountTheDataLoaded() throws Exception {
        Path data = write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"><ex:size>1.5</ex:size></ex:Thing>"));
        RunStats stats = RunStats.start();

        new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(data)
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .runStats(stats)
                .build()).validate();

        RunStatsSnapshot snapshot = stats.stop();
        assertEquals(2, snapshot.triplesLoaded(), "rdf:type and ex:size");
        assertEquals(Files.size(data), snapshot.inputBytes());
    }

    @Test
    void aFileThatDoesNotParseIsNamedInTheError() throws Exception {
        // Jena's parse errors give a line and a column; with many input files, the file matters.
        Path brokenData = write("broken.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">");
        IOException dataFailure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(brokenData).shapeFiles(write("shapes.ttl", SIZE_SHAPES)).xmlBase(BASE).build()).validate());
        assertTrue(dataFailure.getMessage().contains("data file broken.xml"), dataFailure::getMessage);

        Path brokenShapes = write("broken.ttl", "<urn:test:S> a ");
        IOException shapesFailure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(write("ok.ttl", SIZE_SHAPES), brokenShapes)
                .xmlBase(BASE).build()).validate());
        assertTrue(shapesFailure.getMessage().contains("shapes file broken.ttl"), shapesFailure::getMessage);

        IOException zippedFailure = assertThrows(IOException.class, () -> new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(zip("igm.zip", "EQ.xml", "<rdf:RDF"))
                .shapeFiles(write("shapes.ttl", SIZE_SHAPES)).xmlBase(BASE).build()).validate());
        assertTrue(zippedFailure.getMessage().contains("data file igm.zip/EQ.xml"), zippedFailure::getMessage);
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

    // ---- results by constraint file ----

    /** ex:Thing _1 lacks ex:size, ex:name and ex:extra; nothing is an ex:Other. */
    private SHACLValidationReport validateThingAgainstFourConstraintFiles() throws Exception {
        Path size = write("constraints/size.ttl", SIZE_SHAPES);
        Path name = write("constraints/name.ttl", requiredOnThing("NamedThingShape", "name"));
        Path passing = write("constraints/passing.ttl", requiredOnThing("OtherShape", "size")
                .replace("ex:Thing", "ex:Other"));
        // Declares no shape itself: what it imports is counted under the imported file.
        write("constraints/imported/extra.ttl", requiredOnThing("ExtraShape", "extra"));
        Path config = write("constraints/config.ttl", "<urn:test:config> " + IMPORTS + " <imported/extra.ttl> .");

        return new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(size, name, passing, config)
                .xmlBase(BASE)
                .build()).validate();
    }

    @Test
    void resultsAreBrokenDownByTheConstraintFileDeclaringTheirShape() throws Exception {
        SHACLValidationReport report = validateThingAgainstFourConstraintFiles();

        assertEquals(3, report.getResults().size());
        assertEquals(List.of("extra.ttl", "name.ttl", "passing.ttl", "size.ttl"), constraintFiles(report));
        assertEquals(List.of(1, 1, 0, 1), resultCounts(report));
        assertEquals("Thing.size", report.getResultsByConstraintFile().get(3).results().getFirst().getName());
    }

    @Test
    void aShapeCountsUnderTheFileThatTypesItNotOneThatOnlyAddsToIt() throws Exception {
        // Read first, extension.ttl only adds a message to the property shape size.ttl declares.
        Path extension = write("extension.ttl",
                "<urn:test:ThingSizeShape> <http://www.w3.org/ns/shacl#message> \"Every thing has a size\" .");

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(extension, write("size.ttl", SIZE_SHAPES))
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(List.of("size.ttl"), constraintFiles(report));
        assertEquals(List.of(1), resultCounts(report));
    }

    @Test
    void resultsAreNotBrokenDownWhenTheirShapeCannotBeToldApart() throws Exception {
        // Without prefixes both shapes shorten to "X", the label a result names its shape by, so a
        // result could come from either file and neither may be reported as conforming.
        String shape = "<http://%s.example/X> a <http://www.w3.org/ns/shacl#PropertyShape> ;"
                + " <http://www.w3.org/ns/shacl#targetClass> <urn:test:Thing> ;"
                + " <http://www.w3.org/ns/shacl#path> <urn:test:%s> ;"
                + " <http://www.w3.org/ns/shacl#minCount> 1 .";
        Path a = write("a.ttl", shape.formatted("a", "size"));
        Path b = write("b.ttl", shape.formatted("b", "name"));

        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(a, b)
                .xmlBase(BASE)
                .build()).validate();

        assertEquals(List.of("a.ttl; b.ttl"), constraintFiles(report));
        assertEquals(List.of(2), resultCounts(report));
    }

    @Test
    void suppliedShapesModelIsReportedAsOneValidation() throws Exception {
        SHACLValidationReport report = new SHACLValidator(SHACLValidationOptions.builder()
                .dataModel(ttl("ex:t a ex:Thing ."))
                .shapesModel(ttl(SIZE_SHAPES))
                .build()).validate();

        assertEquals(List.of("shapes model"), constraintFiles(report));
        assertEquals(List.of(1), resultCounts(report));
    }

    @Test
    void workbookHasTheMappingLayoutWithOneValidationRowPerConstraintFile() throws Exception {
        SHACLValidationReport report = validateThingAgainstFourConstraintFiles();

        Path workbook = report.writeExcelTo(tempDir.resolve("out"));

        assertEquals(tempDir.resolve("out"), workbook.getParent());
        assertTrue(workbook.getFileName().toString().matches("validation_report__\\d{8}_\\d{6}\\.xlsx"),
                workbook::toString);
        // The anonymous property shapes appear in Source under per-run blank-node labels.
        SNAPSHOTS.assertExcelEquals("four-constraint-files__workbook", workbook,
                Normalizer.timestamps(), Normalizer.paths(tempDir), Normalizer.blankNodeLabels());
    }

    // ---- Golden master (TEST-3): current output, compared with src/test/resources/snapshots/shacl-validator ----

    /**
     * Line shapes in the given CIM namespace, one per severity: {@code r} must be a non-negative
     * float (Violation), {@code name} is required (Warning), {@code aggregate} must be a boolean (Info).
     * The float and boolean checks only pass when the preset's datatype map typed the literals.
     */
    private static String lineShapes(String cimNs) {
        return """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix cim: <%s> .
                @prefix ex: <urn:test:shapes:> .
                ex:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;
                    sh:property ex:Line.r , ex:Line.name , ex:Line.aggregate .
                ex:Line.r a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:name "ACLineSegment.r" ;
                    sh:datatype xsd:float ; sh:minInclusive 0.0 ; sh:severity sh:Violation ;
                    sh:message "Resistance must be a non-negative float" .
                ex:Line.name a sh:PropertyShape ; sh:path cim:IdentifiedObject.name ; sh:name "IdentifiedObject.name" ;
                    sh:minCount 1 ; sh:severity sh:Warning ; sh:message "Name is missing" .
                ex:Line.aggregate a sh:PropertyShape ; sh:path cim:Equipment.aggregate ; sh:name "Equipment.aggregate" ;
                    sh:datatype xsd:boolean ; sh:severity sh:Info ; sh:message "Aggregate must be a boolean" .
                """.formatted(cimNs);
    }

    /** Three lines: one clean, one with a negative r and a non-boolean aggregate, one without a name. */
    private static String lines(String cimNs) {
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="%s">
                  <cim:ACLineSegment rdf:ID="_ok"><cim:IdentifiedObject.name>OK</cim:IdentifiedObject.name>
                    <cim:ACLineSegment.r>0.25</cim:ACLineSegment.r><cim:Equipment.aggregate>false</cim:Equipment.aggregate></cim:ACLineSegment>
                  <cim:ACLineSegment rdf:ID="_bad"><cim:IdentifiedObject.name>Bad</cim:IdentifiedObject.name>
                    <cim:ACLineSegment.r>-1.5</cim:ACLineSegment.r><cim:Equipment.aggregate>maybe</cim:Equipment.aggregate></cim:ACLineSegment>
                  <cim:ACLineSegment rdf:ID="_anon"><cim:ACLineSegment.r>1.0</cim:ACLineSegment.r></cim:ACLineSegment>
                </rdf:RDF>
                """.formatted(cimNs);
    }

    private SHACLValidationReport validateLines(SHACLValidationOptions.Builder preset, String cimNs, String data) throws Exception {
        return new SHACLValidator(preset
                .dataFiles(write("lines.xml", data))
                .shapeFiles(write("lines.ttl", lineShapes(cimNs)))
                .workers(1)
                .build()).validate();
    }

    private void assertGolden(String name, SHACLValidationReport report) throws Exception {
        SNAPSHOTS.assertIsomorphic(name + "__report", report.getReportModel());
        Path xlsx = tempDir.resolve("out/" + name + ".xlsx");
        report.writeExcel(xlsx);
        // Rows follow the engine's result order, which differs between runs.
        SNAPSHOTS.assertExcelEqualsIgnoringRowOrder(name + "__workbook", xlsx, Normalizer.timestamps(), Normalizer.paths(tempDir));
    }

    @Test
    void goldenCgmes30SeverityMix() throws Exception {
        String ns = TestModels.CIM_NS;
        SHACLValidationReport report = validateLines(SHACLValidationOptionsPresets.cgmes30(), ns, lines(ns));

        assertFalse(report.conforms());
        assertFalse(report.isPartial());
        assertEquals(Map.of("Violation", 1L, "Warning", 1L, "Info", 1L), report.countBySeverity());
        assertEquals(List.of(), report.getWarnings());
        assertEquals("lines.xml", report.getDatasetName());
        assertGolden("cgmes30-severity-mix", report);
    }

    @Test
    void goldenCgmes24SeverityMix() throws Exception {
        String ns = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
        SHACLValidationReport report = validateLines(SHACLValidationOptionsPresets.cgmes24(), ns, lines(ns));

        assertEquals(Map.of("Violation", 1L, "Warning", 1L, "Info", 1L), report.countBySeverity());
        assertEquals(List.of(), report.getWarnings());
        assertGolden("cgmes24-severity-mix", report);
    }

    @Test
    void goldenCgmes30DataWithTheCgmes24PresetIsNotTyped() throws Exception {
        // The 2.4 map has no CIM100 keys, so every literal stays an xsd:string: on each of the three
        // lines r fails both sh:datatype and sh:minInclusive, and both aggregate values fail the boolean check.
        String ns = TestModels.CIM_NS;
        SHACLValidationReport report = validateLines(SHACLValidationOptionsPresets.cgmes24(), ns, lines(ns));

        assertEquals(Map.of("Violation", 6L, "Warning", 1L, "Info", 2L), report.countBySeverity());
        assertGolden("cgmes30-data-cgmes24-preset", report);
    }

    @Test
    void goldenConformingData() throws Exception {
        String ns = TestModels.CIM_NS;
        String clean = """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="%s">
                  <cim:ACLineSegment rdf:ID="_ok"><cim:IdentifiedObject.name>OK</cim:IdentifiedObject.name>
                    <cim:ACLineSegment.r>0.25</cim:ACLineSegment.r></cim:ACLineSegment>
                </rdf:RDF>
                """.formatted(ns);
        SHACLValidationReport report = validateLines(SHACLValidationOptionsPresets.cgmes30(), ns, clean);

        assertTrue(report.conforms());
        assertEquals(List.of(), report.getResults());
        assertEquals(Map.of(), report.countBySeverity());
        assertGolden("conforming", report);
    }

    @Test
    void goldenEmptyDataFileConforms() throws Exception {
        String ns = TestModels.CIM_NS;
        SHACLValidationReport report = validateLines(SHACLValidationOptionsPresets.cgmes30(), ns,
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>\n");

        assertTrue(report.conforms());
        // An empty run is not silently clean: it says that nothing was validated.
        assertEquals(List.of("The data holds no triples, so nothing was validated."), report.getWarnings());
        assertGolden("empty-data", report);
    }

    @Test
    void malformedDataFailsWithTheParserError() throws Exception {
        String ns = TestModels.CIM_NS;
        Exception failure = assertThrows(Exception.class, () -> validateLines(
                SHACLValidationOptionsPresets.cgmes30(), ns, "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"));

        assertEquals(java.io.IOException.class, failure.getClass(), failure::toString);
        assertTrue(failure.getMessage().startsWith("Could not read the data file lines.xml"), failure::getMessage);
    }

    @Test
    void malformedShapesFailWithTheParserError() throws Exception {
        SHACLValidator validator = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(write("data.xml", rdfXml("<ex:Thing rdf:about=\"#_1\"/>")))
                .shapeFiles(write("broken.ttl", "@prefix sh: <http://www.w3.org/ns/shacl#> .\n<urn:a> sh:targetClass ."))
                .xmlBase(BASE)
                .build());

        Exception failure = assertThrows(Exception.class, validator::validate);
        assertEquals(java.io.IOException.class, failure.getClass(), failure::toString);
        assertTrue(failure.getMessage().startsWith("Could not read the shapes file broken.ttl"), failure::getMessage);
    }
}
