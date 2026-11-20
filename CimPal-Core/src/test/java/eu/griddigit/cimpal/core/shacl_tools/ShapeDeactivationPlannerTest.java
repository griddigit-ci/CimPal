/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Candidate;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.MatchKind;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Plan;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.ShapeInfo;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.SourceModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ShapeDeactivationPlannerTest {

    private static final String PREFIXES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix equ: <http://example.com/cgmes#> .
            @prefix eqc: <http://example.com/extension#> .
            """;

    /** A miniature CGMES set: five rules, one SPARQL constraint, one node shape listing two of them. */
    private static final String BASE = PREFIXES + """
            equ:Switch a sh:NodeShape ; sh:targetClass cim:Switch ;
                sh:property equ:Switch.ratedCurrent-valueRange , equ:Equipment.aggregate-notUsed .
            equ:Switch.ratedCurrent-valueRange a sh:PropertyShape ; sh:path cim:Switch.ratedCurrent ;
                sh:name "C:301:EQ:Switch.ratedCurrent:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
            equ:Equipment.aggregate-notUsed a sh:PropertyShape ; sh:path cim:Equipment.aggregate ;
                sh:name "C:301:EQ:Equipment.aggregate:notUsed" ; sh:sparql equ:Equipment.aggregate-notUsedSparql .
            equ:Equipment.aggregate-notUsedSparql a sh:SPARQLConstraint ; sh:select "SELECT $this WHERE { }" .
            equ:CurrentLimit a sh:NodeShape ; sh:targetClass cim:CurrentLimit ; sh:property equ:CurrentLimit.normalValue-valueRange .
            equ:CurrentLimit.normalValue-valueRange a sh:PropertyShape ; sh:path cim:CurrentLimit.normalValue ;
                sh:name "C:301:EQ:CurrentLimit.normalValue:valueRange" ; sh:minInclusive "0.0"^^xsd:float .
            equ:BaseVoltage.nominalVoltage-valueRange a sh:PropertyShape ; sh:path cim:BaseVoltage.nominalVoltage ;
                sh:name "C:301:EQ:BaseVoltage.nominalVoltage:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
            equ:BaseVoltage a sh:NodeShape ; sh:targetClass cim:BaseVoltage ; sh:property equ:BaseVoltage.nominalVoltage-valueRange .
            equ:Group a sh:PropertyGroup .
            """;

    /** A miniature replacing set in its own namespace. */
    private static final String OVERRIDE = PREFIXES + """
            eqc:Equipment.aggregate-notUsed a sh:PropertyShape ; sh:path cim:Equipment.aggregate ;
                sh:name "C:EXT:EQ:Equipment.aggregate:notUsed" ; sh:maxCount 0 .
            eqc:Breaker a sh:NodeShape ; sh:targetClass cim:Switch ; sh:property eqc:Switch.ratedCurrent-positive .
            eqc:Switch.ratedCurrent-positive a sh:PropertyShape ; sh:path cim:Switch.ratedCurrent ;
                sh:name "C:EXT:EQ:Switch.ratedCurrent:requiredForSwitchSubtype" ;
                sh:minExclusive "0.0"^^xsd:float ; sh:minCount 1 .
            eqc:CurrentLimit.normalValue-required a sh:PropertyShape ; sh:path cim:CurrentLimit.normalValue ;
                sh:name "C:EXT:EQ:CurrentLimit.normalValue:required" ; sh:minCount 1 .
            eqc:CurrentLimit a sh:NodeShape ; sh:targetClass cim:CurrentLimit ; sh:property eqc:CurrentLimit.normalValue-required .
            """;

    @Test
    void sameRuleNameInAnotherNamespaceIsSuggested() {
        Candidate candidate = candidates(plan(BASE, OVERRIDE, null)).get("http://example.com/cgmes#Equipment.aggregate-notUsed");

        assertNotNull(candidate);
        assertTrue(candidate.suggested());
        assertEquals(MatchKind.SAME_RULE, candidate.matches().getFirst().kind());
        assertEquals("http://example.com/extension#Equipment.aggregate-notUsed", candidate.matches().getFirst().overrideIri());
    }

    @Test
    void samePathAndParameterOnOverlappingTargetIsSuggested() {
        Candidate candidate = candidates(plan(BASE, OVERRIDE, null)).get("http://example.com/cgmes#Switch.ratedCurrent-valueRange");

        assertNotNull(candidate);
        assertTrue(candidate.suggested());
        assertEquals(MatchKind.SAME_PATH, candidate.matches().getFirst().kind());
        assertTrue(candidate.reasons().contains("same minExclusive"), candidate.reasons());
    }

    @Test
    void sameElementWithAnotherKindOfRuleIsRelatedOnly() {
        Candidate candidate = candidates(plan(BASE, OVERRIDE, null)).get("http://example.com/cgmes#CurrentLimit.normalValue-valueRange");

        assertNotNull(candidate, "minInclusive and minCount on the same path are different checks, but the element matches");
        assertFalse(candidate.suggested());
        assertEquals(Set.of(MatchKind.RELATED),
                candidate.matches().stream().map(m -> m.kind()).collect(Collectors.toSet()));
    }

    @Test
    void unmatchedShapesAreNotListedButAreInTheCatalogue() {
        Plan plan = plan(BASE, OVERRIDE, null);

        assertFalse(candidates(plan).containsKey("http://example.com/cgmes#BaseVoltage.nominalVoltage-valueRange"));
        Set<String> catalogue = plan.baseShapes().stream().map(ShapeInfo::iri).collect(Collectors.toSet());
        assertTrue(catalogue.contains("http://example.com/cgmes#BaseVoltage.nominalVoltage-valueRange"));
        assertTrue(catalogue.contains("http://example.com/cgmes#Switch"));
        assertFalse(catalogue.contains("http://example.com/cgmes#Equipment.aggregate-notUsedSparql"),
                "SPARQL constraints are switched off through the shapes that use them");
        assertFalse(catalogue.contains("http://example.com/cgmes#Group"));
    }

    @Test
    void targetsIncludeThoseOfTheNodeShapesThatReachAPropertyShape() {
        ShapeInfo shape = plan(BASE, OVERRIDE, null).baseShapes().stream()
                .filter(s -> s.iri().endsWith("#Switch.ratedCurrent-valueRange")).findFirst().orElseThrow();

        assertEquals(Set.of("http://iec.ch/TC57/CIM100#Switch"), shape.targets());
        assertEquals(List.of("http://www.w3.org/ns/shacl#PropertyShape"), shape.types());
    }

    @Test
    void samePathWithoutOverlappingTargetIsNotAMatch() {
        String override = PREFIXES + """
                eqc:Other a sh:NodeShape ; sh:targetClass cim:Breaker ; sh:property eqc:Other.r .
                eqc:Other.r a sh:PropertyShape ; sh:path cim:Switch.ratedCurrent ; sh:minExclusive "0.0"^^xsd:float .
                """;

        assertFalse(candidates(plan(BASE, override, null)).containsKey("http://example.com/cgmes#Switch.ratedCurrent-valueRange"));
    }

    @Test
    void referenceFileFlagsDroppedAndChangedShapes() {
        String reference = PREFIXES + """
                equ:Switch a sh:NodeShape ; sh:targetClass cim:Switch ; sh:property equ:Switch.ratedCurrent-valueRange .
                equ:Switch.ratedCurrent-valueRange a sh:PropertyShape ; sh:path cim:Switch.ratedCurrent ;
                    sh:name "C:301:EQ:Switch.ratedCurrent:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
                equ:CurrentLimit a sh:NodeShape ; sh:targetClass cim:CurrentLimit ; sh:property equ:CurrentLimit.normalValue-valueRange .
                equ:CurrentLimit.normalValue-valueRange a sh:PropertyShape ; sh:path cim:CurrentLimit.normalValue ;
                    sh:name "C:301:EQ:CurrentLimit.normalValue:valueRange" ; sh:minInclusive "1.0"^^xsd:float .
                equ:BaseVoltage a sh:NodeShape ; sh:targetClass cim:BaseVoltage ; sh:property equ:BaseVoltage.nominalVoltage-valueRange .
                equ:BaseVoltage.nominalVoltage-valueRange a sh:PropertyShape ; sh:path cim:BaseVoltage.nominalVoltage ;
                    sh:name "C:301:EQ:BaseVoltage.nominalVoltage:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
                """;
        Map<String, Candidate> candidates = candidates(plan(BASE, PREFIXES, reference));

        Candidate dropped = candidates.get("http://example.com/cgmes#Equipment.aggregate-notUsed");
        assertTrue(dropped.suggested());
        assertEquals(MatchKind.NOT_IN_REFERENCE, dropped.matches().getFirst().kind());

        Candidate changed = candidates.get("http://example.com/cgmes#CurrentLimit.normalValue-valueRange");
        assertTrue(changed.suggested());
        assertEquals(MatchKind.CHANGED_IN_REFERENCE, changed.matches().getFirst().kind());
        assertTrue(changed.reasons().contains("minInclusive"), changed.reasons());

        Candidate lists = candidates.get("http://example.com/cgmes#Switch");
        assertFalse(lists.suggested(), "a node shape that only dropped a property shape stays active");
        assertEquals(MatchKind.LISTS_CHANGED_IN_REFERENCE, lists.matches().getFirst().kind());

        assertFalse(candidates.containsKey("http://example.com/cgmes#BaseVoltage.nominalVoltage-valueRange"),
                "an unchanged shape is not listed");
        assertFalse(candidates.containsKey("http://example.com/cgmes#BaseVoltage"));
    }

    /**
     * A reference trims one base set. A second base file in another namespace must not come back
     * as dropped wholesale: that would switch off every rule of it.
     */
    @Test
    void referenceOnlyJudgesTheNamespacesItCovers() {
        String otherBase = PREFIXES + """
                @prefix eq600: <http://example.com/cgmes600#> .
                eq600:Line.r-datatype a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:datatype xsd:float .
                """;
        String reference = PREFIXES + """
                equ:BaseVoltage.nominalVoltage-valueRange a sh:PropertyShape ; sh:path cim:BaseVoltage.nominalVoltage ;
                    sh:name "C:301:EQ:BaseVoltage.nominalVoltage:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
                """;
        Plan plan = ShapeDeactivationPlanner.plan(
                List.of(new SourceModel("base.ttl", parse(BASE)), new SourceModel("base600.ttl", parse(otherBase))),
                List.of(), List.of(new SourceModel("reference.ttl", parse(reference))));
        Map<String, Candidate> candidates = candidates(plan);

        assertFalse(candidates.containsKey("http://example.com/cgmes600#Line.r-datatype"));
        assertTrue(candidates.get("http://example.com/cgmes#Equipment.aggregate-notUsed").suggested(),
                "the covered namespace is still compared");
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("base600.ttl")), plan.warnings().toString());
    }

    /** One configuration over several base files: each reference judges the base file it trims. */
    @Test
    void eachReferenceJudgesItsOwnBaseFile() {
        String otherBase = PREFIXES + """
                @prefix eq600: <http://example.com/cgmes600#> .
                eq600:Line.r-datatype a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:datatype xsd:float .
                eq600:Line.x-datatype a sh:PropertyShape ; sh:path cim:ACLineSegment.x ; sh:datatype xsd:float .
                """;
        String reference301 = PREFIXES + """
                equ:BaseVoltage.nominalVoltage-valueRange a sh:PropertyShape ; sh:path cim:BaseVoltage.nominalVoltage ;
                    sh:name "C:301:EQ:BaseVoltage.nominalVoltage:valueRange" ; sh:minExclusive "0.0"^^xsd:float .
                """;
        String reference600 = PREFIXES + """
                @prefix eq600: <http://example.com/cgmes600#> .
                eq600:Line.r-datatype a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:datatype xsd:float .
                """;
        Plan plan = ShapeDeactivationPlanner.plan(
                List.of(new SourceModel("base.ttl", parse(BASE)), new SourceModel("base600.ttl", parse(otherBase))),
                List.of(),
                List.of(new SourceModel("ref301.ttl", parse(reference301)), new SourceModel("ref600.ttl", parse(reference600))));
        Map<String, Candidate> candidates = candidates(plan);

        Candidate dropped600 = candidates.get("http://example.com/cgmes600#Line.x-datatype");
        assertTrue(dropped600.suggested());
        assertTrue(dropped600.reasons().contains("ref600.ttl does not declare it"), dropped600.reasons());
        assertFalse(candidates.containsKey("http://example.com/cgmes600#Line.r-datatype"), "kept by its reference");
        Candidate dropped301 = candidates.get("http://example.com/cgmes#Equipment.aggregate-notUsed");
        assertTrue(dropped301.reasons().contains("ref301.ttl does not declare it"), dropped301.reasons());
        assertTrue(plan.warnings().isEmpty(), plan.warnings().toString());
    }

    @Test
    void literalsAreComparedByValueSoAnUntypedIntegerEqualsATypedOne() {
        String base = PREFIXES + """
                equ:A a sh:PropertyShape ; sh:path cim:A.x ; sh:maxCount 1 .
                """;
        String reference = PREFIXES + """
                equ:A a sh:PropertyShape ; sh:path cim:A.x ; sh:maxCount "1"^^xsd:integer .
                """;

        assertTrue(plan(base, PREFIXES, reference).candidates().isEmpty());
    }

    @Test
    void shapeAlsoDeclaredByAnOverrideIsNeverSuggested() {
        String override = PREFIXES + """
                equ:Equipment.aggregate-notUsed a sh:PropertyShape ; sh:path cim:Equipment.aggregate ;
                    sh:name "C:EXT:EQ:Equipment.aggregate:notUsed" .
                """;
        Candidate candidate = candidates(plan(BASE, override, null)).get("http://example.com/cgmes#Equipment.aggregate-notUsed");

        assertFalse(candidate.suggested(), "deactivating it would switch the override off too");
        assertTrue(candidate.matches().stream().anyMatch(m -> m.kind() == MatchKind.SAME_IRI));
    }

    @Test
    void ruleNamesNeedTheComplexRuleForm() {
        assertNull(ShapeDeactivationPlanner.RuleName.parse("ACLineSegment.length-cardinality"));
        assertNull(ShapeDeactivationPlanner.RuleName.parse("C:301:EQ:"));
        assertEquals("equipment.aggregate:notused",
                ShapeDeactivationPlanner.RuleName.parse(" C:EXT:EQ:Equipment.aggregate:notUsed ").key());
    }

    private static Plan plan(String base, String override, String reference) {
        return ShapeDeactivationPlanner.plan(
                List.of(new SourceModel("base.ttl", parse(base))),
                List.of(new SourceModel("override.ttl", parse(override))),
                reference == null ? List.of() : List.of(new SourceModel("reference.ttl", parse(reference))));
    }

    private static Map<String, Candidate> candidates(Plan plan) {
        return plan.candidates().stream().collect(Collectors.toMap(c -> c.shape().iri(), Function.identity()));
    }

    private static Model parse(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(model);
        return model;
    }
}
