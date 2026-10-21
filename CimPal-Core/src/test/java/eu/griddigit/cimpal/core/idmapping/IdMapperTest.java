/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdMapperTest {

    @TempDir
    Path dir;

    private final SyntheticGrid grid = SyntheticGrid.build();

    @Test
    void reIdentifiedCopyOfTwoFilesMapsEveryObject() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 1).renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        assertTrue(result.getUnmatched().isEmpty(), () -> "unmatched: " + result.getUnmatched());
        assertEquals(result.getModel1ObjectCount(), result.getMatchedCount());
        assertEquals("Charlie", nameOfNew(result, "subC"));
        List<MatchedRow> probable = result.getMatched().stream()
                .filter(m -> !MatchedRow.CERTAIN.equals(m.confidence())).toList();
        assertTrue(probable.isEmpty(), () -> "an identical grid is matched with certainty: " + probable);
        assertEquals(100.0, result.getModel1Overview().percent(result.getModel1Overview().certain()));
    }

    @Test
    void scrambledSubstationAndLineNamesMatchThroughTheBoundaryAndTheLines() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 2)
                .scrambleNames(Set.of("Substation", "Line", "ACLineSegment", "VoltageLevel"), 1.0)
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        for (String sub : List.of("subA", "subB", "subC", "subD")) {
            assertNotNull(row(result, sub), sub + " not matched");
        }
        MatchedRow d = row(result, "subD");
        assertTrue(d.note().contains("name differs"), d.note());
        assertEquals(MatchedRow.CERTAIN, d.confidence(), "every line and the whole inside agree, whatever the name");
        for (String line : List.of("aclsAB1", "aclsAB2", "aclsAC", "aclsBD", "aclsCD1", "aclsCD2", "aclsDX")) {
            assertNotNull(row(result, line), line + " not matched");
        }
    }

    @Test
    void lineParametersAloneAnchorTheMatchingWithoutNamesOrSharedIds() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 3)
                .scrambleNames(Set.of("Substation", "Line", "ACLineSegment"), 1.0)
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder().useNames(false).matchSameIds(false));

        assertNoWrongMatches(result, copy);
        assertEquals("line-parameters", row(result, "subC").method(), "C is where two parameter-matched lines meet");
        for (String sub : List.of("subA", "subB", "subC", "subD")) {
            assertNotNull(row(result, sub), sub + " not matched");
        }
    }

    @Test
    void lineInTwoSectionsMapsBothSectionsToTheWholeLine() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 4)
                .mergeSeries(SyntheticGrid.IRI + "_aclsCD1", SyntheticGrid.IRI + "_aclsCD2")
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        // The merged section's second terminal moved from the middle node to Delta: checked below.
        Map<String, String> truth = copy.truthById();
        String movedTerminal = truth.get("aclsCD1_t2");
        List<String> special = List.of("aclsCD1_t2", "aclsCD2_t1", "aclsCD2_t2");
        assertNoWrongMatches(result, copy, special);
        MatchedRow s1 = row(result, "aclsCD1");
        MatchedRow s2 = row(result, "aclsCD2");
        assertNotNull(s1);
        assertNotNull(s2);
        assertEquals(s1.model2Id(), s2.model2Id());
        assertTrue(s2.note().contains("model 1 line has 2 sections, model 2 has 1"), s2.note());
        assertEquals(movedTerminal, row(result, "aclsCD2_t2").model2Id(), "the Delta end of section 2");
        assertNull(row(result, "aclsCD1_t2"), "section 1's terminal at the middle node has no counterpart");
        assertNull(row(result, "aclsCD2_t1"), "section 2's terminal at the middle node has no counterpart");
        UnmatchedRow mid = unmatched(result, Side.MODEL1, "cnLineCD_mid");
        assertNotNull(mid);
        assertTrue(mid.reason().contains("single section"), mid.reason());
        assertNotNull(row(result, "lineCD"), "the Line container follows its sections");
    }

    @Test
    void wholePfLineMapsToOneSectionOfTheSplitNewLine() throws IOException {
        Model wholeLine = ModelRewriter.of(grid.eq(), 0)
                .mergeSeries(SyntheticGrid.IRI + "_aclsCD1", SyntheticGrid.IRI + "_aclsCD2").model();
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 14).renameIds(Set.of());
        Map<String, String> truth = copy.truthById();

        IdMappingResult result = run(wholeLine, copy.model(), IdMappingOptions.builder());

        // either model 2 half is right for the whole section, and its limits follow it; its second terminal
        // sits at Delta now, where section 2's terminal is in model 2
        assertNoWrongMatches(result, copy, List.of("aclsCD1", "aclsCD1_t2", "aclsCD1_ols", "aclsCD1_patl"));
        MatchedRow whole = row(result, "aclsCD1");
        assertNotNull(whole);
        assertTrue(List.of(truth.get("aclsCD1"), truth.get("aclsCD2")).contains(whole.model2Id()), whole.toString());
        assertTrue(whole.note().contains("model 2 line has 2 sections"), whole.note());
        String otherHalf = whole.model2Id().equals(truth.get("aclsCD1")) ? truth.get("aclsCD2") : truth.get("aclsCD1");
        UnmatchedRow rest = unmatched(result, Side.MODEL2, otherHalf);
        assertNotNull(rest);
        assertTrue(rest.reason().startsWith("part of a line matched as a whole"), rest.reason());
        MatchedRow farEnd = row(result, "aclsCD1_t2");
        if (farEnd != null) {
            assertEquals(truth.get("aclsCD2_t2"), farEnd.model2Id(), "only the terminal at the same substation");
        }
    }

    @Test
    void stationKeptAsTwoSubstationsInTheNewModelMatchesAsOne() throws IOException {
        // model 2 keeps Alpha's 110 kV level as a substation of its own (as FINGLAS / FINGLAS1)
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 15)
                .splitStation(SyntheticGrid.IRI + "_vlA110", "Alpha 110 kV")
                .renameIds(Set.of());

        IdMappingResult grouped = run(copy.model(), IdMappingOptions.builder());
        IdMappingResult apart = run(copy.model(), IdMappingOptions.builder().groupStations(false));

        assertNoWrongMatches(grouped, copy);
        assertNotNull(row(grouped, "ldA"), "the load in the split-off voltage level");
        assertNotNull(row(grouped, "bbA110"));
        MatchedRow alpha = row(grouped, "subA");
        assertTrue(alpha.note().contains("one station"), alpha.note());
        List<UnmatchedRow> model1Left = grouped.getUnmatched().stream().filter(u -> u.side() == Side.MODEL1).toList();
        assertTrue(model1Left.isEmpty(), () -> "model 1 left: " + model1Left);
        UnmatchedRow part = grouped.getUnmatched().stream()
                .filter(u -> u.side() == Side.MODEL2 && "Substation".equals(u.elementType())).findFirst().orElseThrow();
        assertTrue(part.reason().startsWith("part of one station with"), part.reason());
        assertNull(row(apart, "ldA"), "without grouping, the split-off part has no counterpart");
    }

    @Test
    void lineThroughATerminalJunctionWithABusbarMatchesTheWholeLine() throws IOException {
        // model 1 cuts Bravo-Delta at a terminal point with a busbar section (PowerFactory "Term_...")
        Model model1 = ModelRewriter.of(grid.eq(), 0)
                .addJunction(SyntheticGrid.IRI + "_aclsBD", "Term_Bravo - Delta", true).model();
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 16).renameIds(Set.of());
        Map<String, String> truth = copy.truthById();

        IdMappingResult result = run(model1, copy.model(), IdMappingOptions.builder());

        // the cut moved section 1's far terminal to the junction; the model 2 section has no own counterpart
        List<String> cut = List.of("aclsBD", "aclsBD_t2", "aclsBD_b", "aclsBD_b_t1", "aclsBD_b_t2");
        assertNoWrongMatches(result, copy, cut);
        assertEquals(truth.get("aclsBD"), row(result, "aclsBD").model2Id());
        assertEquals(truth.get("aclsBD"), row(result, "aclsBD_b").model2Id());
        UnmatchedRow junction = unmatched(result, Side.MODEL1, "aclsBD_jsub");
        assertNotNull(junction);
        assertTrue(junction.reason().startsWith("line junction"), junction.reason());
        assertNotNull(row(result, "subB"));
        assertNotNull(row(result, "brBD"), "the bays at both ends still match");
        assertNotNull(row(result, "brDB"));
    }

    @Test
    void classesOnlyOneModelHasAreCountedNotListed() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 17).addObjects("Analog", 3).renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertTrue(result.getUnmatched().stream().noneMatch(u -> "Analog".equals(u.elementType())));
        IdMappingResult.TypeCount analog = result.getTypeCounts().stream()
                .filter(t -> t.elementType().equals("Analog")).findFirst().orElseThrow();
        assertEquals(3, analog.model2Objects());
        assertFalse(analog.inBothModels());
        assertEquals(0, result.getModel2Overview().unmatched(), "not part of the overview either");
    }

    @Test
    void lineSectionsAreNamedAfterTheirLine() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 18)
                .rename(SyntheticGrid.IRI + "_aclsBD", "A")
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertEquals("Bravo-Delta (A)", row(result, "aclsBD").model2SecondName());
        assertEquals("Alpha-Charlie", row(result, "aclsAC").model2SecondName(), "section named like its line");
    }

    @Test
    void nodesWithoutContainerTakeTheSubstationOfTheirEquipment() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 5)
                .dropNodeContainers(cn -> cn.getURI().endsWith("_mid") || cn.getURI().endsWith("_end"))
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        assertTrue(result.getUnmatched().isEmpty(), () -> "unmatched: " + result.getUnmatched());
    }

    @Test
    void differentLineParametersStillMatchByStructureWithANote() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 6).scaleLineParameters(10).renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        MatchedRow bd = row(result, "aclsBD");
        assertNotNull(bd);
        assertTrue(bd.note().contains("parameters differ"), bd.note());
    }

    @Test
    void symmetricBaysWithoutNamesAreReportedNotGuessed() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 7)
                .scrambleNames(Set.of("Line", "ACLineSegment", "Breaker", "Disconnector", "ConnectivityNode",
                        "Terminal", "OperationalLimitSet"), 1.0)
                .renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        UnmatchedRow parallel = unmatched(result, Side.MODEL1, "aclsAB1");
        assertNotNull(parallel, "the two identical A-B circuits cannot be told apart");
        assertTrue(parallel.reason().startsWith("ambiguous"), parallel.reason());
        assertFalse(parallel.candidates().isEmpty());
        assertNotNull(row(result, "aclsBD"), "the unique lines still match");
    }

    @Test
    void cgmes24NamespaceIsReadLikeCgmes30() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 8).renameIds(Set.of());
        Model pf24 = toNamespace(grid.eq(), "http://iec.ch/TC57/2013/CIM-schema-cim16#");

        IdMappingResult result = run(pf24, copy.model(), IdMappingOptions.builder());

        assertNoWrongMatches(result, copy);
        assertTrue(result.getUnmatched().isEmpty(), () -> "unmatched: " + result.getUnmatched());
    }

    @Test
    void aliasTableMatchesStationCodesToNames() throws IOException {
        Model renamed = ModelFactory.createDefaultModel().add(grid.eq());
        Property name = renamed.createProperty(SyntheticGrid.CIM + "IdentifiedObject.name");
        Map<String, String> codes = Map.of("Alpha", "ALP01", "Bravo", "BRA02", "Charlie", "CHA03", "Delta", "DEL04");
        for (Statement st : renamed.listStatements(null, name, (String) null).toList()) {
            if (codes.containsKey(st.getString())) {
                st.changeObject(codes.get(st.getString()));
            }
        }
        ModelRewriter copy = ModelRewriter.of(renamed, 9).renameIds(Set.of());
        Path aliases = dir.resolve("aliases.csv");
        Files.writeString(aliases, "Code;Name\nALP01;Alpha\nBRA02;Bravo\nCHA03;Charlie\nDEL04;Delta\n",
                StandardCharsets.UTF_8);

        IdMappingResult result = run(grid.eq(), copy.model(), IdMappingOptions.builder().nameAliasFile(aliases));

        assertNoWrongMatches(result, copy);
        assertEquals("name", row(result, "subB").method());
    }

    @Test
    void workbookHasTheRequestedColumnsInXlsxAndXls() throws IOException {
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 10).renameIds(Set.of());
        IdMappingResult result = run(copy.model(), IdMappingOptions.builder());

        for (String fileName : List.of("map.xlsx", "map.xls")) {
            Path out = dir.resolve(fileName);
            result.writeExcel(out);
            try (InputStream in = Files.newInputStream(out); Workbook wb = WorkbookFactory.create(in)) {
                Sheet matched = wb.getSheet("Matched");
                assertEquals(List.of("Model1_ID", "Element_type", "Model2_ID", "Model2_2nd_name"),
                        cells(matched.getRow(0)).subList(0, 4));
                assertEquals(result.getMatched().size(), matched.getLastRowNum());
                Map<String, List<String>> byId = new HashMap<>();
                for (int r = 1; r <= matched.getLastRowNum(); r++) {
                    List<String> row = cells(matched.getRow(r));
                    byId.put(row.get(0), row);
                }
                List<String> substation = byId.get("subB");
                assertEquals("Substation", substation.get(1));
                assertEquals(copy.truthById().get("subB"), substation.get(2));
                assertEquals("Bravo", substation.get(3));
                assertNotNull(wb.getSheet("Unmatched"));
                assertNotNull(wb.getSheet("Summary"));
            }
        }
    }

    @Test
    void formulaLikeNamesStayText() throws IOException {
        Model model = ModelFactory.createDefaultModel().add(grid.eq());
        Resource sub = model.getResource(SyntheticGrid.IRI + "_subA");
        Property name = model.createProperty(SyntheticGrid.CIM + "IdentifiedObject.name");
        model.removeAll(sub, name, null);
        sub.addProperty(name, "=HYPERLINK(\"http://x\")");
        ModelRewriter copy = ModelRewriter.of(model, 11).renameIds(Set.of());
        IdMappingResult result = run(model, copy.model(), IdMappingOptions.builder());
        Path out = dir.resolve("map.xlsx");

        result.writeExcel(out);

        try (InputStream in = Files.newInputStream(out); Workbook wb = WorkbookFactory.create(in)) {
            Sheet matched = wb.getSheet("Matched");
            for (int r = 1; r <= matched.getLastRowNum(); r++) {
                if ("subA".equals(matched.getRow(r).getCell(0).getStringCellValue())) {
                    var cell = matched.getRow(r).getCell(3);
                    assertEquals(org.apache.poi.ss.usermodel.CellType.STRING, cell.getCellType());
                    assertTrue(cell.getCellStyle().getQuotePrefixed());
                }
            }
        }
    }

    @Test
    void queryWithServiceIsRefused() throws IOException {
        Path queries = Files.createDirectories(dir.resolve("queries"));
        Files.writeString(queries.resolve(QueryLibrary.OBJECTS),
                "SELECT ?object WHERE { SERVICE <http://example.com/sparql> { ?object ?p ?o } }");
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 12).renameIds(Set.of());

        assertThrows(RuntimeException.class,
                () -> run(copy.model(), IdMappingOptions.builder().queryFolder(queries)));
    }

    @Test
    void editedQueryInTheFolderReplacesTheBundledOne() throws IOException {
        Path queries = Files.createDirectories(dir.resolve("queries"));
        String bundled = new QueryLibrary(null).text(QueryLibrary.DEPENDENTS);
        Files.writeString(queries.resolve(QueryLibrary.DEPENDENTS),
                bundled.replace("cim:OperationalLimit.OperationalLimitSet", ""));
        ModelRewriter copy = ModelRewriter.of(grid.eq(), 13).renameIds(Set.of());

        IdMappingResult result = run(copy.model(), IdMappingOptions.builder().queryFolder(queries)
                .includeOthers(false));

        assertNoWrongMatches(result, copy);
        assertEquals(null, row(result, "aclsBD_patl"), "CurrentLimit is no dependent any more");
        assertNotNull(row(result, "aclsBD_ols"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private IdMappingResult run(Model model2Model, IdMappingOptions.Builder options) throws IOException {
        return run(grid.eq(), model2Model, options);
    }

    /** model 1 split over two files (one EQ against two EQs), model 2 in one, the common data apart. */
    private IdMappingResult run(Model model1Model, Model model2Model, IdMappingOptions.Builder options) throws IOException {
        List<Model> halves = split(model1Model);
        Path pf1 = dir.resolve("pf1.xml");
        Path pf2 = dir.resolve("pf2.xml");
        Path model2 = dir.resolve("new.xml");
        Path common = dir.resolve("common.xml");
        ModelRewriter.write(halves.get(0), pf1);
        ModelRewriter.write(halves.get(1), pf2);
        ModelRewriter.write(model2Model, model2);
        ModelRewriter.write(grid.common(), common);
        return new IdMapper(options.model1Files(List.of(pf1, pf2)).model2File(model2).commonFiles(List.of(common)).build()).run();
    }

    private static List<Model> split(Model model) {
        Model a = ModelFactory.createDefaultModel();
        Model b = ModelFactory.createDefaultModel();
        List<Resource> subjects = new ArrayList<>(model.listSubjects().toList());
        subjects.sort((x, y) -> x.getURI().compareTo(y.getURI()));
        for (int i = 0; i < subjects.size(); i++) {
            (i % 2 == 0 ? a : b).add(model.listStatements(subjects.get(i), null, (org.apache.jena.rdf.model.RDFNode) null));
        }
        return List.of(a, b);
    }

    private static Model toNamespace(Model model, String ns) {
        Model out = ModelFactory.createDefaultModel();
        out.setNsPrefix("cim", ns);
        for (Statement st : model.listStatements().toList()) {
            Property p = out.createProperty(st.getPredicate().getURI().replace(SyntheticGrid.CIM, ns));
            var o = st.getObject();
            if (st.getPredicate().getURI().endsWith("#type") && o.isURIResource()) {
                o = out.createResource(o.asResource().getURI().replace(SyntheticGrid.CIM, ns));
            }
            out.add(st.getSubject(), p, o);
        }
        return out;
    }

    /** Every matched row maps to the object the rewrite made of it; nothing is mapped wrong. */
    private static void assertNoWrongMatches(IdMappingResult result, ModelRewriter copy) {
        assertNoWrongMatches(result, copy, List.of());
    }

    private static void assertNoWrongMatches(IdMappingResult result, ModelRewriter copy, List<String> skip) {
        Map<String, String> truth = copy.truthById();
        Map<String, String> absorbed = copy.absorbedById();
        for (MatchedRow m : result.getMatched()) {
            if (skip.contains(m.model1Id())) {
                continue;
            }
            String expected = absorbed.containsKey(m.model1Id()) ? absorbed.get(m.model1Id()) : truth.get(m.model1Id());
            assertEquals(expected, m.model2Id(), () -> "wrong match: " + m);
        }
    }

    private static MatchedRow row(IdMappingResult result, String model1Id) {
        return result.getMatched().stream().filter(m -> m.model1Id().equals(model1Id)).findFirst().orElse(null);
    }

    private static UnmatchedRow unmatched(IdMappingResult result, Side side, String id) {
        return result.getUnmatched().stream().filter(u -> u.side() == side && u.id().equals(id)).findFirst().orElse(null);
    }

    private static String nameOfNew(IdMappingResult result, String model1Id) {
        return row(result, model1Id).model2SecondName();
    }

    private static List<String> cells(Row row) {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            var cell = row.getCell(c);
            out.add(cell == null ? "" : cell.getStringCellValue());
        }
        return out;
    }
}
