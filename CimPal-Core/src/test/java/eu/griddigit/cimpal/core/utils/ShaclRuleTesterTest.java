/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.ShaclRuleTestOptions;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.ModelResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Note;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Outcome;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.RuleResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Verdict;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SHACL rule test: each rule folder's Conform models must not trigger the rule and its
 * NonConform models must. Suites are built under {@link TempDir} from {@link TestModels} lines.
 */
class ShaclRuleTesterTest {

    /**
     * Two rules on cim:ACLineSegment. The shapes have a declared prefix, so a finding's source
     * shape label is shortened ({@code ex:...}), as with real constraint sets.
     */
    private static final String SHAPES = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix ex:  <http://example.org/rules#> .

            ex:ACLineSegment a sh:NodeShape ;
                sh:targetClass cim:ACLineSegment ;
                sh:property ex:ACLineSegment.name-required , ex:ACLineSegment.r-positive , ex:ACLineSegment.x-retired .

            ex:ACLineSegment.name-required a sh:PropertyShape ;
                sh:name "NameRequired" ;
                sh:path cim:IdentifiedObject.name ;
                sh:minCount 1 ;
                sh:severity sh:Violation .

            ex:ACLineSegment.r-positive a sh:PropertyShape ;
                sh:name "ResistancePositive" ;
                sh:path cim:ACLineSegment.r ;
                sh:minExclusive "0.0"^^xsd:float ;
                sh:severity sh:Violation .

            ex:ACLineSegment.x-retired a sh:PropertyShape ;
                sh:name "RetiredRule" ;
                sh:deactivated true ;
                sh:path cim:ACLineSegment.x ;
                sh:minCount 1 .
            """;

    /** Types {@code cim:ACLineSegment.r} as the bundled maps do, so the range check compares numbers. */
    private static final Map<String, org.apache.jena.datatypes.RDFDatatype> DATATYPES =
            Map.of(TestModels.CIM_NS + "ACLineSegment.r", XSDDatatype.XSDfloat);

    @TempDir
    Path tempDir;

    private Path suite() throws Exception {
        return Files.createDirectories(tempDir.resolve("suite"));
    }

    private Path shapes() throws Exception {
        Path file = tempDir.resolve("shapes.ttl");
        if (!Files.exists(file)) {
            Files.writeString(file, SHAPES);
        }
        return file;
    }

    /** A line with a name and a resistance of 1.5: conforms to every rule. */
    private static String goodLine() {
        return TestModels.eq().resource("ACLineSegment", "_l1")
                .literal("IdentifiedObject.name", "Line 1").literal("ACLineSegment.r", "1.5").toRdfXml();
    }

    /** A line without a name: breaks NameRequired only. */
    private static String unnamedLine() {
        return TestModels.eq().resource("ACLineSegment", "_l1").literal("ACLineSegment.r", "1.5").toRdfXml();
    }

    /** A named line with a negative resistance: breaks ResistancePositive only. */
    private static String negativeLine() {
        return TestModels.eq().resource("ACLineSegment", "_l1")
                .literal("IdentifiedObject.name", "Line 1").literal("ACLineSegment.r", "-1.0").toRdfXml();
    }

    /** Writes a model archive holding one RDF/XML file. */
    private static Path model(Path folder, String name, String rdfXml) throws Exception {
        return archive(folder, name, "EQ.xml", rdfXml);
    }

    private static Path archive(Path folder, String name, String entry, String content) throws Exception {
        Files.createDirectories(folder);
        Path zip = folder.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out)) {
            ZipEntry zipEntry = new ZipEntry(entry);
            // A fixed time, so that archives of the same content are the same bytes.
            zipEntry.setTime(946_684_800_000L);
            zipOut.putNextEntry(zipEntry);
            zipOut.write(content.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }
        return zip;
    }

    private ShaclRuleTestOptions.Builder options(Path suite) throws Exception {
        return ShaclRuleTestOptions.builder()
                .shapeFiles(shapes())
                .suiteFolder(suite)
                .datatypeMap(DATATYPES)
                .xmlBase(TestModels.XML_BASE)
                .excelReports(false)
                .workers(2);
    }

    private static RuleResult rule(ShaclRuleTestReport report, String name) {
        return report.getRules().stream().filter(r -> r.rule().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No result for rule " + name + ": " + report.getRules()));
    }

    private static ModelResult only(RuleResult rule, boolean conform) {
        List<ModelResult> models = rule.models().stream().filter(m -> m.conform() == conform).toList();
        assertEquals(1, models.size(), "models in " + (conform ? "Conform" : "NonConform"));
        return models.getFirst();
    }

    // ---- verdicts -------------------------------------------------------------

    /**
     * Regression: the tester used to look the rule's sh:name up from the shortened source-shape
     * label ({@code ex:...}), found no shape, and threw a NullPointerException at the first finding.
     */
    @Test
    void ruleThatFiresOnlyOnItsNonConformModelsPasses() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        RuleResult rule = rule(report, "NameRequired");
        assertEquals(Verdict.PASS, rule.verdict(), rule.message());
        assertEquals(0, only(rule, true).ruleFindings());
        assertEquals(1, only(rule, false).ruleFindings());
        assertTrue(report.passed());
    }

    /**
     * A finding counts for the rule its own shape is named after. A result carries its shape's
     * names joined by " | ", so read back from that string, the finding of a shape named
     * "NameRequired | Range" was credited to NameRequired, which then passed without firing.
     */
    @Test
    void nameContainingTheSeparatorIsNotCreditedToAnotherRule() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        // Has a name, so NameRequired stays silent; breaks only the range shape below.
        model(suite.resolve("NameRequired/NonConform"), "negative.zip", negativeLine());
        Path shapes = Files.writeString(tempDir.resolve("separator.ttl"), """
                @prefix sh:  <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix cim: <http://iec.ch/TC57/CIM100#> .
                @prefix ex:  <http://example.org/rules#> .
                ex:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;
                    sh:property ex:Name , ex:Range .
                ex:Name a sh:PropertyShape ; sh:name "NameRequired" ;
                    sh:path cim:IdentifiedObject.name ; sh:minCount 1 .
                ex:Range a sh:PropertyShape ; sh:name "NameRequired | Range" ;
                    sh:path cim:ACLineSegment.r ; sh:minExclusive "0.0"^^xsd:float .
                """);

        RuleResult rule = rule(new ShaclRuleTester(options(suite).shapeFiles(shapes).build()).run(), "NameRequired");

        assertEquals(Verdict.FAIL, rule.verdict(), rule.message());
        ModelResult nonConform = only(rule, false);
        assertEquals(Outcome.EXPECTED_TRIGGER_NOT_FOUND, nonConform.outcome());
        assertEquals(0, nonConform.ruleFindings());
        assertEquals(1, nonConform.otherFindings());
    }

    /** Shapes declaring AnonymousNameRequired on an anonymous (blank node) property shape. */
    private Path anonymousShapes() throws Exception {
        return Files.writeString(tempDir.resolve("anonymous.ttl"), """
                @prefix sh:  <http://www.w3.org/ns/shacl#> .
                @prefix cim: <http://iec.ch/TC57/CIM100#> .
                @prefix ex:  <http://example.org/rules#> .
                ex:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;
                    sh:property [ sh:name "AnonymousNameRequired" ; sh:path cim:IdentifiedObject.name ; sh:minCount 1 ] .
                """);
    }

    /** A rule declared on an anonymous property shape is found by its sh:name like a named one. */
    @Test
    void ruleOnABlankNodePropertyShapeIsFound() throws Exception {
        Path suite = suite();
        model(suite.resolve("AnonymousNameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("AnonymousNameRequired/NonConform"), "bad.zip", unnamedLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).shapeFiles(anonymousShapes()).build()).run(),
                "AnonymousNameRequired");

        assertEquals(Verdict.PASS, rule.verdict(), rule.message());
        assertEquals(1, only(rule, false).ruleFindings());
    }

    /**
     * The fail-open direction of matching by shape node: if an anonymous shape's findings were
     * missed, a Conform model breaking it would pass.
     */
    @Test
    void ruleOnABlankNodePropertyShapeFiringOnAConformModelFails() throws Exception {
        Path suite = suite();
        model(suite.resolve("AnonymousNameRequired/Conform"), "unnamed.zip", unnamedLine());
        model(suite.resolve("AnonymousNameRequired/NonConform"), "bad.zip", unnamedLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).shapeFiles(anonymousShapes()).build()).run(),
                "AnonymousNameRequired");

        assertEquals(Verdict.FAIL, rule.verdict());
        assertEquals(Outcome.UNEXPECTED_TRIGGER, only(rule, true).outcome());
        assertEquals(1, only(rule, true).ruleFindings());
    }

    @Test
    void ruleThatFiresOnAConformModelFails() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "unnamed.zip", unnamedLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "NameRequired");

        assertEquals(Verdict.FAIL, rule.verdict());
        assertEquals(Outcome.UNEXPECTED_TRIGGER, only(rule, true).outcome());
        assertEquals(Outcome.PASS, only(rule, false).outcome());
    }

    @Test
    void ruleThatDoesNotFireOnANonConformModelFails() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "named.zip", goodLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "NameRequired");

        assertEquals(Verdict.FAIL, rule.verdict());
        assertEquals(Outcome.EXPECTED_TRIGGER_NOT_FOUND, only(rule, false).outcome());
    }

    @Test
    void findingsOfOtherRulesDoNotAffectTheVerdict() throws Exception {
        Path suite = suite();
        // Conforms to NameRequired, but breaks ResistancePositive.
        model(suite.resolve("NameRequired/Conform"), "negative.zip", negativeLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "NameRequired");

        assertEquals(Verdict.PASS, rule.verdict(), rule.message());
        assertEquals(0, only(rule, true).ruleFindings());
        assertEquals(1, only(rule, true).otherFindings());
    }

    @Test
    void theDatatypeMapIsAppliedSoARangeRuleComparesNumbers() throws Exception {
        Path suite = suite();
        model(suite.resolve("ResistancePositive/Conform"), "good.zip", goodLine());
        model(suite.resolve("ResistancePositive/NonConform"), "negative.zip", negativeLine());

        ShaclRuleTestReport typed = new ShaclRuleTester(options(suite).build()).run();
        assertEquals(Verdict.PASS, rule(typed, "ResistancePositive").verdict(), rule(typed, "ResistancePositive").message());

        // Untyped, "1.5" is a string that cannot be compared with a float, so the rule fires on the good line.
        ShaclRuleTestReport untyped = new ShaclRuleTester(options(suite).datatypeMap(DatatypeMapPreset.NONE).build()).run();
        assertEquals(Outcome.UNEXPECTED_TRIGGER, only(rule(untyped, "ResistancePositive"), true).outcome());
        assertTrue(untyped.getWarnings().stream().anyMatch(w -> w.startsWith("No datatype map was applied")),
                () -> "warnings: " + untyped.getWarnings());
    }

    // ---- which models are validated --------------------------------------------

    /**
     * Regression: models were cached by file name, so a Conform and a NonConform model that share
     * a name shared one report, and one of the two verdicts was decided by the other model.
     */
    @Test
    void sameNamedModelsWithDifferentContentAreValidatedSeparately() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "model.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "model.zip", unnamedLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        assertEquals(Verdict.PASS, rule(report, "NameRequired").verdict(), rule(report, "NameRequired").message());
        assertEquals(2, report.getValidatedModels());
    }

    @Test
    void identicalCopiesAreValidatedOnceAndEachGetsItsReport() throws Exception {
        Path suite = suite();
        Path first = model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        Path second = model(suite.resolve("ResistancePositive/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        model(suite.resolve("ResistancePositive/NonConform"), "negative.zip", negativeLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).excelReports(true).turtleReports(true).build()).run();

        assertEquals(4, report.getModelFiles());
        assertEquals(3, report.getValidatedModels());
        assertTrue(report.passed(), () -> report.getRules().toString());
        for (Path copy : List.of(first, second)) {
            assertTrue(Files.isRegularFile(copy.resolveSibling("good_report.xlsx")), "Excel report beside " + copy);
            assertTrue(Files.isRegularFile(copy.resolveSibling("good_report.ttl")), "Turtle report beside " + copy);
        }
    }

    // ---- nothing is skipped silently ---------------------------------------------

    /** Regression: a rule folder whose name matched no shape passed its Conform models silently. */
    @Test
    void ruleFolderThatNoShapeNamesIsAnErrorNotAPass() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequried/Conform"), "good.zip", goodLine());
        model(suite.resolve("namerequired/Conform"), "good.zip", goodLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        RuleResult typo = rule(report, "NameRequried");
        assertEquals(Verdict.ERROR, typo.verdict());
        assertEquals(Outcome.NOT_TESTED, only(typo, true).outcome());
        assertTrue(rule(report, "namerequired").message().contains("\"NameRequired\""),
                () -> rule(report, "namerequired").message());
        assertFalse(report.passed());
    }

    @Test
    void ruleWhoseShapesAreDeactivatedIsAnError() throws Exception {
        Path suite = suite();
        model(suite.resolve("RetiredRule/NonConform"), "good.zip", goodLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "RetiredRule");

        assertEquals(Verdict.ERROR, rule.verdict());
        assertTrue(rule.message().contains("deactivated"), rule.message());
    }

    /**
     * Regression: a model outside a Conform or NonConform folder crashed the run, or was skipped
     * unreported. Now it is noted, and a rule whose folder holds one cannot pass: the model may be
     * the test that would have failed it.
     */
    @Test
    void misplacedModelsAreNotedAndKeepTheirRuleFromPassing() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        Path atTop = model(suite, "loose.zip", goodLine());
        Path inRuleFolder = model(suite.resolve("NameRequired"), "stray.zip", goodLine());
        Path otherFolder = suite.resolve("NameRequired/Maybe");
        model(otherFolder, "maybe.zip", goodLine());
        Path nested = suite.resolve("NameRequired/Conform/older");
        model(nested, "old.zip", unnamedLine());
        Files.writeString(suite.resolve("NameRequired/Conform/notes.txt"), "not a model");

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        List<Path> noted = report.getNotes().stream().map(Note::path).toList();
        assertEquals(List.of(atTop, inRuleFolder, otherFolder, nested).stream().sorted().toList(),
                noted.stream().sorted().toList());
        RuleResult rule = rule(report, "NameRequired");
        assertEquals(Verdict.ERROR, rule.verdict());
        assertEquals("Not completely tested: 3 item(s) in its folder were not tested (see Notes).", rule.message());
        assertEquals(2, rule.models().size());
        assertFalse(report.passed());
    }

    /** A rule seen only to stay silent, or only to fire, has not been shown to do both. */
    @Test
    void ruleWithModelsOnOneSideOnlyIsNotAPass() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        Files.createDirectories(suite.resolve("NameRequired/NonConform"));
        model(suite.resolve("ResistancePositive/NonConform"), "negative.zip", negativeLine());
        // An unzipped model is not read: a model is an archive.
        Files.createDirectories(suite.resolve("ResistancePositive/Conform"));
        Files.writeString(suite.resolve("ResistancePositive/Conform/EQ.xml"), goodLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        RuleResult conformOnly = rule(report, "NameRequired");
        assertEquals(Verdict.ERROR, conformOnly.verdict());
        assertEquals("Not completely tested: no NonConform models.", conformOnly.message());
        RuleResult nonConformOnly = rule(report, "ResistancePositive");
        assertEquals(Verdict.ERROR, nonConformOnly.verdict());
        assertEquals("Not completely tested: no Conform models (1 loose .xml file(s) there, which are read only"
                + " inside a .zip archive).", nonConformOnly.message());
        assertFalse(report.passed());
    }

    /** A failure is a finding about the rule, so it is not hidden behind what else was not tested. */
    @Test
    void failingRuleStaysAFailureWhenAlsoNotCompletelyTested() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "unnamed.zip", unnamedLine());

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "NameRequired");

        assertEquals(Verdict.FAIL, rule.verdict());
        assertEquals("1 Conform model(s) triggered the rule. Not completely tested: no NonConform models.",
                rule.message());
    }

    /** A folder of archives with no Conform or NonConform folder used to drop out of the results unseen. */
    @Test
    void folderOfArchivesWithoutConformOrNonConformIsARuleThatCouldNotBeTested() throws Exception {
        Path suite = suite();
        Path stray = model(suite.resolve("NameRequired"), "test.zip", unnamedLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        RuleResult rule = rule(report, "NameRequired");
        assertEquals(Verdict.ERROR, rule.verdict());
        assertTrue(rule.message().startsWith("None of its models is in a Conform or NonConform folder"), rule.message());
        assertEquals(List.of(stray), report.getNotes().stream().map(Note::path).toList());
    }

    /**
     * A symbolic link to a folder of archives in a rule's folder is noted like a folder, so the
     * rule cannot pass. Skipped where links cannot be created (Windows without the privilege).
     */
    @Test
    void symlinkedFolderOfArchivesKeepsItsRuleFromPassing() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        model(elsewhere, "old.zip", unnamedLine());
        Path link = suite.resolve("NameRequired/old");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic links cannot be created here: " + ex.getMessage());
        }

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        assertEquals(List.of(link), report.getNotes().stream().map(Note::path).toList());
        assertEquals(Verdict.ERROR, rule(report, "NameRequired").verdict());
    }

    /** The verdicts survive a results workbook that cannot be written: they are in the report and the Output pane. */
    @Test
    void verdictsSurviveAResultsWorkbookThatCannotBeWritten() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        ShaclRuleTestOptions options = options(suite).build();
        // Readable everywhere under the temp folder, writable only somewhere else: the workbook is refused.
        PathPolicy noWritesToTheSuite = PathPolicy.builder().readRoot(tempDir)
                .writeRoot(Files.createDirectories(tempDir.resolve("elsewhere"))).build();

        ShaclRuleTestReport report = PathPolicy.runWith(noWritesToTheSuite, () -> new ShaclRuleTester(options).run());

        assertNull(report.getWorkbook());
        assertEquals(Verdict.PASS, rule(report, "NameRequired").verdict());
    }

    /** Every rule passing is not enough when something in the suite was left untested. */
    @Test
    void notesKeepTheRunFromPassing() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        model(suite, "forgotten.zip", unnamedLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        assertEquals(Verdict.PASS, rule(report, "NameRequired").verdict());
        assertFalse(report.passed());
    }

    @Test
    void modelThatCannotBeReadIsAnErrorOfItsRuleOnly() throws Exception {
        Path suite = suite();
        archive(suite.resolve("NameRequired/Conform"), "no-xml.zip", "readme.txt", "no RDF here");
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        Files.createDirectories(suite.resolve("ResistancePositive/Conform"));
        Files.write(suite.resolve("ResistancePositive/Conform/corrupt.zip"), new byte[]{1, 2, 3, 4});
        model(suite.resolve("ResistancePositive/NonConform"), "negative.zip", negativeLine());
        model(suite.resolve("ResistancePositive/Conform"), "good.zip", goodLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        for (String name : List.of("NameRequired", "ResistancePositive")) {
            RuleResult rule = rule(report, name);
            assertEquals(Verdict.ERROR, rule.verdict(), name);
            assertEquals(1, rule.models().stream().filter(m -> m.outcome() == Outcome.ERROR).count(), name);
        }
        assertEquals(Outcome.PASS, rule(report, "ResistancePositive").models().stream()
                .filter(m -> m.model().getFileName().toString().equals("good.zip")).findFirst().orElseThrow().outcome());
    }

    /**
     * Fail closed: an import the egress policy refuses (here a host off the allowlist, refused
     * before any connection) stops the run, rather than leaving the rules it holds out and
     * passing their Conform models. The old tester skipped every import that was not a file.
     */
    @Test
    void importThatCannotBeResolvedStopsTheRun() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        Path importing = Files.writeString(tempDir.resolve("importing.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <urn:test:rules> a owl:Ontology ; owl:imports <https://example.com/rules/more.ttl> .
                """);

        IOException failure = assertThrows(IOException.class,
                () -> new ShaclRuleTester(options(suite).shapeFiles(shapes(), importing).build()).run());
        assertTrue(failure.getMessage().contains("owl:imports"), failure.getMessage());
    }

    /** Folder and file names reach the workbook as text: one that looks like a formula is never evaluated. */
    @Test
    void namesThatLookLikeFormulasAreWrittenAsText() throws Exception {
        Path suite = suite();
        model(suite.resolve("=SUM(1,2)/Conform"), "=HYPERLINK(1).zip", goodLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        try (InputStream in = Files.newInputStream(report.getWorkbook()); Workbook book = WorkbookFactory.create(in)) {
            Cell rule = book.getSheet("Summary").getRow(1).getCell(0);
            assertEquals(CellType.STRING, rule.getCellType());
            assertEquals("=SUM(1,2)", rule.getStringCellValue());
            Cell model = book.getSheet("Models").getRow(1).getCell(2);
            assertEquals(CellType.STRING, model.getCellType());
            assertEquals("=HYPERLINK(1).zip", model.getStringCellValue());
        }
    }

    /** A shapes graph Jena rejects stops the run once, rather than failing every model with the same error. */
    @Test
    void constraintsThatAreNotAValidShapesGraphStopTheRun() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        Path broken = Files.writeString(tempDir.resolve("broken.ttl"), """
                @prefix sh:  <http://www.w3.org/ns/shacl#> .
                @prefix cim: <http://iec.ch/TC57/CIM100#> .
                @prefix ex:  <http://example.org/rules#> .
                ex:ACLineSegment a sh:NodeShape ; sh:targetClass cim:ACLineSegment ; sh:property ex:NotInThisFile .
                """);

        IOException failure = assertThrows(IOException.class,
                () -> new ShaclRuleTester(options(suite).shapeFiles(broken).build()).run());
        assertTrue(failure.getMessage().contains("not a valid SHACL shapes graph"), failure.getMessage());
    }

    @Test
    void ruleFolderWithoutModelsIsAnError() throws Exception {
        Path suite = suite();
        Files.createDirectories(suite.resolve("NameRequired/Conform"));
        Files.createDirectories(suite.resolve("NameRequired/NonConform"));

        RuleResult rule = rule(new ShaclRuleTester(options(suite).build()).run(), "NameRequired");

        assertEquals(Verdict.ERROR, rule.verdict());
        assertTrue(rule.models().isEmpty());
    }

    // ---- reports -------------------------------------------------------------------

    /** A model's counts are its report's rows, so the Models sheet and the report beside the model agree. */
    @Test
    void findingCountsMatchTheModelsReport() throws Exception {
        Path suite = suite();
        // Two lines: one without a name, one with a negative resistance.
        String twoFaults = TestModels.eq()
                .resource("ACLineSegment", "_l1").literal("ACLineSegment.r", "1.5")
                .resource("ACLineSegment", "_l2").literal("IdentifiedObject.name", "Line 2").literal("ACLineSegment.r", "-1.0")
                .toRdfXml();
        Path model = model(suite.resolve("NameRequired/NonConform"), "two-faults.zip", twoFaults);

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).excelReports(true).build()).run();

        ModelResult result = only(rule(report, "NameRequired"), false);
        assertEquals(1, result.ruleFindings());
        assertEquals(1, result.otherFindings());
        try (InputStream in = Files.newInputStream(model.resolveSibling("two-faults_report.xlsx"));
             Workbook book = WorkbookFactory.create(in)) {
            assertEquals(result.ruleFindings() + result.otherFindings(), book.getSheetAt(0).getLastRowNum());
        }
    }

    /** Regression: a report that could not be written was only printed to stderr. */
    @Test
    void reportThatCannotBeWrittenIsNoted() throws Exception {
        Path suite = suite();
        Path good = model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        // A folder where the report file would go.
        Path blocked = Files.createDirectories(good.resolveSibling("good_report.xlsx"));

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).excelReports(true).build()).run();

        assertEquals(List.of(blocked), report.getNotes().stream().map(Note::path).toList());
        assertEquals(Verdict.PASS, rule(report, "NameRequired").verdict());
    }

    @Test
    void writesTheResultsWorkbookIntoTheSuiteFolder() throws Exception {
        Path suite = suite();
        model(suite.resolve("NameRequired/Conform"), "good.zip", goodLine());
        model(suite.resolve("NameRequired/NonConform"), "bad.zip", unnamedLine());
        model(suite.resolve("ResistancePositive/Conform"), "negative.zip", negativeLine());
        model(suite, "loose.zip", goodLine());

        ShaclRuleTestReport report = new ShaclRuleTester(options(suite).build()).run();

        Path workbook = report.getWorkbook();
        assertNotNull(workbook);
        assertEquals(suite.toAbsolutePath().normalize(), workbook.getParent());
        assertTrue(workbook.getFileName().toString().startsWith(ShaclRuleTester.WORKBOOK_PREFIX));
        try (InputStream in = Files.newInputStream(workbook); Workbook book = WorkbookFactory.create(in)) {
            assertEquals(List.of("Summary", "Models", "Notes", "Run"), sheetNames(book));
            assertEquals(List.of(
                    List.of("Rule (sh:name)", "Result", "Conform models", "Conform passed", "NonConform models",
                            "NonConform passed", "Message"),
                    List.of("NameRequired", "Pass", "1", "1", "1", "1", ""),
                    List.of("ResistancePositive", "Fail", "1", "0", "0", "0", "1 Conform model(s) triggered the rule."
                            + " Not completely tested: no NonConform models.")),
                    rows(book.getSheet("Summary")));
            List<List<String>> models = rows(book.getSheet("Models"));
            assertEquals(List.of("NameRequired", "NonConform", "bad.zip", "1", "0", "Pass", ""), models.get(2));
            assertEquals("loose.zip", rows(book.getSheet("Notes")).get(1).getFirst());
        }
        try (Stream<Path> files = Files.list(suite)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().endsWith(".xlsx")).count());
        }
    }

    private static List<String> sheetNames(Workbook book) {
        List<String> names = new ArrayList<>();
        book.forEach(sheet -> names.add(sheet.getSheetName()));
        return names;
    }

    private static List<List<String>> rows(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < row.getLastCellNum(); column++) {
                cells.add(formatter.formatCellValue(row.getCell(column)));
            }
            rows.add(cells);
        }
        return rows;
    }
}
