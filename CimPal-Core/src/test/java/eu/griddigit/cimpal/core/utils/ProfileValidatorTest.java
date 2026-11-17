/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.ProfileValidationOptions;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.Finding;
import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.profiles.ProfileGroup;
import eu.griddigit.cimpal.core.profiles.ProfileGroupPlanner;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile-driven validation on a synthetic data set: two areas and a boundary, two made-up
 * profiles, shapes named after no real library. What each document holds:
 * <ul>
 *   <li>{@code boundary/bd.xml} (Core): nodes BP1, BPX (a name the boundary shape rejects) and
 *       BPL (a name too long for the common shape);
 *   <li>{@code areaA/a_core.xml} (Core): devices D1 (on the boundary node BP1), D2 (two names: a
 *       real cardinality error) and D3 (a name too long), node N1;
 *   <li>{@code areaA/a_state.xml} (State): D1 continued with a state and a second name;
 *   <li>{@code areaB/b_core.xml} (Core): device E1 on a node that exists nowhere;
 *   <li>{@code areaB/ref.xml}: no header.
 * </ul>
 */
class ProfileValidatorTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("profile-validation");
    private static final String BASE = "http://example.org/data";
    private static final String EX = "http://example.org/grid#";
    private static final String CORE = "urn:example:profile:Core/1";
    private static final String STATE = "urn:example:profile:State/1";

    private static final String TTL_PREFIXES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix ex: <http://example.org/grid#> .
            """;

    @TempDir
    Path tempDir;

    private Path models;
    private Path library;

    @BeforeEach
    void dataSet() throws IOException {
        library = tempDir.resolve("lib");
        models = tempDir.resolve("models");

        shapes("Core-Con-Simple.ttl", """
                ex:DeviceCard a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceCard-name .
                ex:DeviceCard-name sh:path ex:name ; sh:minCount 1 ; sh:maxCount 1 .
                ex:DeviceClosed a sh:NodeShape ; sh:targetClass ex:Device ; sh:closed true ;
                    sh:ignoredProperties ( rdf:type ) ; sh:property ex:DeviceClosed-name , ex:DeviceClosed-node .
                ex:DeviceClosed-name sh:path ex:name .
                ex:DeviceClosed-node sh:path ex:node .
                """);
        shapes("Core-Con-Complex.ttl", """
                ex:DeviceNodeRef a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceNodeRef-node .
                ex:DeviceNodeRef-node sh:path ex:node ; sh:class ex:Node .
                """);
        shapes("Core-Boundary-Con.ttl", """
                ex:BoundaryOnly a sh:NodeShape ; sh:targetClass ex:Node ; sh:property ex:BoundaryOnly-name .
                ex:BoundaryOnly-name sh:path ex:name ; sh:pattern "^BP" .
                """);
        shapes("State-Con-Simple.ttl", """
                ex:DeviceState a sh:NodeShape ; sh:targetClass ex:Device ; sh:closed true ;
                    sh:ignoredProperties ( rdf:type ) ; sh:property ex:DeviceState-state , ex:DeviceState-name .
                ex:DeviceState-state sh:path ex:state ; sh:minCount 1 .
                ex:DeviceState-name sh:path ex:name .
                """);
        shapes("Common-Complex.ttl", """
                ex:NameLength a sh:NodeShape ; sh:targetSubjectsOf ex:name ; sh:property ex:NameLength-name .
                ex:NameLength-name sh:path ex:name ; sh:maxLength 10 .
                """);
        prof("Core.rdf", CORE, "Core-Con-Simple.ttl", "Core-Con-Complex.ttl", "Core-Boundary-Con.ttl");
        prof("State.rdf", STATE, "State-Con-Simple.ttl");

        model("boundary/bd.xml", "urn:uuid:bd", CORE, null, """
                  <ex:Node rdf:ID="BP1"><ex:name>BP-1</ex:name></ex:Node>
                  <ex:Node rdf:ID="BPX"><ex:name>X-bad</ex:name></ex:Node>
                  <ex:Node rdf:ID="BPL"><ex:name>BP-LongLongLongName</ex:name></ex:Node>
                """);
        model("areaA/a_core.xml", "urn:uuid:a-core", CORE, "urn:uuid:bd", """
                  <ex:Device rdf:ID="D1"><ex:name>Dev1</ex:name><ex:node rdf:resource="#BP1"/></ex:Device>
                  <ex:Device rdf:ID="D2"><ex:name>Dev2</ex:name><ex:name>Dev2bis</ex:name><ex:node rdf:resource="#N1"/></ex:Device>
                  <ex:Device rdf:ID="D3"><ex:name>ThisNameIsFarTooLong</ex:name><ex:node rdf:resource="#N1"/></ex:Device>
                  <ex:Node rdf:ID="N1"><ex:name>N1</ex:name></ex:Node>
                """);
        model("areaA/a_state.xml", "urn:uuid:a-state", STATE, "urn:uuid:a-core", """
                  <ex:Device rdf:about="#D1"><ex:state>on</ex:state><ex:name>Dev1 (s)</ex:name></ex:Device>
                """);
        model("areaB/b_core.xml", "urn:uuid:b-core", CORE, "urn:uuid:not-loaded", """
                  <ex:Device rdf:ID="E1"><ex:name>E1</ex:name><ex:node rdf:resource="#Missing"/></ex:Device>
                """);
        write(models.resolve("areaB/ref.xml"), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="http://example.org/grid#">
                  <ex:Node rdf:ID="R1"><ex:name>R1</ex:name></ex:Node>
                </rdf:RDF>
                """);
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void shapes(String name, String body) throws IOException {
        write(library.resolve("SHACL").resolve(name), TTL_PREFIXES + body);
    }

    private void prof(String name, String profile, String... artifacts) throws IOException {
        StringBuilder xml = new StringBuilder("""
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:prof="http://www.w3.org/ns/dx/prof/" xmlns:dcterms="http://purl.org/dc/terms/">
                  <rdf:Description rdf:about="%s">
                    <rdf:type rdf:resource="http://www.w3.org/ns/dx/prof/Profile"/>
                """.formatted(profile));
        for (String artifact : artifacts) {
            xml.append("    <prof:hasResource rdf:resource=\"").append(profile).append('/').append(artifact).append("\"/>\n");
        }
        xml.append("  </rdf:Description>\n");
        for (String artifact : artifacts) {
            xml.append("  <rdf:Description rdf:about=\"").append(profile).append('/').append(artifact).append("\">\n")
                    .append("    <prof:hasRole rdf:resource=\"http://www.w3.org/ns/dx/prof/role/constraints\"/>\n")
                    .append("    <prof:hasArtifact>").append(artifact).append("</prof:hasArtifact>\n")
                    .append("    <dcterms:conformsTo>http://www.w3.org/ns/shacl</dcterms:conformsTo>\n")
                    .append("  </rdf:Description>\n");
        }
        write(library.resolve("PROF").resolve(name), xml.append("</rdf:RDF>\n").toString());
    }

    private void model(String relative, String modelIri, String profile, String dependsOn, String body) throws IOException {
        write(models.resolve(relative), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                         xmlns:ex="http://example.org/grid#">
                  <md:FullModel rdf:about="%s">
                    <md:Model.profile>%s</md:Model.profile>
                %s  </md:FullModel>
                """.formatted(modelIri, profile,
                dependsOn == null ? "" : "    <md:Model.DependentOn rdf:resource=\"" + dependsOn + "\"/>\n")
                + body + "</rdf:RDF>\n");
    }

    private ProfileValidationOptions.Builder options() {
        return ProfileValidationOptions.builder()
                .modelsDir(models)
                .profileLibraries(library)
                .xmlBase(BASE)
                .boundaryFolders(List.of("boundary"))
                .boundaryShapesPattern("Boundary")
                .commonShapes(List.of("Common-Complex.ttl"));
    }

    private static ProfileValidationSummary validate(ProfileValidationOptions.Builder builder) throws IOException {
        return new ProfileValidator(builder.build()).validate();
    }

    /** group / documents / shape / component / focus, one line per finding, sorted. */
    private static List<String> lines(ProfileValidationSummary summary) {
        return summary.findings().stream().map(ProfileValidatorTest::line).sorted().toList();
    }

    private static String line(Finding f) {
        SHACLValidationResult r = f.result();
        return f.group() + " | " + String.join(",", f.documents()) + " | " + local(r.getSourceShape()) + " | "
                + local(r.getConstraintComponent()) + " | " + local(r.getFocusNode());
    }

    private static String local(String value) {
        String v = value == null ? "" : value;
        return v.substring(Math.max(v.lastIndexOf('#'), v.lastIndexOf(':')) + 1);
    }

    // ---- the method ----------------------------------------------------------------------

    @Test
    void eachDocumentIsValidatedAgainstTheShapesOfItsProfileInItsGroup() throws Exception {
        ProfileValidationSummary summary = validate(options());

        assertThat(lines(summary)).containsExactly(
                "cgmes-areaA | a_core.xml | DeviceCard-name | MaxCountConstraintComponent | D2",
                "cgmes-areaA | a_core.xml | NameLength-name | MaxLengthConstraintComponent | D3",
                "cgmes-areaB | b_core.xml | DeviceNodeRef-node | ClassConstraintComponent | E1",
                "cgmes-boundary | bd.xml | BoundaryOnly-name | PatternConstraintComponent | BPX",
                "cgmes-boundary | bd.xml | NameLength-name | MaxLengthConstraintComponent | BPL");
        assertThat(summary.groups()).extracting(ProfileValidationSummary.GroupResult::name)
                .containsExactly("cgmes-boundary", "cgmes-areaA", "cgmes-areaB");
        assertThat(summary.skipped()).containsExactly(
                new ProfileGroupPlanner.Skipped("ref.xml", "no application profile declared"));
        assertThat(summary.gaps()).containsExactly(
                "declared model dependency not loaded: b_core.xml -> urn:uuid:not-loaded");
        assertThat(summary.complete()).isTrue();
        assertThat(summary.partial()).isFalse();
        assertThat(summary.conforms()).isFalse();
        assertThat(summary.countBySeverity()).containsEntry("Violation", 5L);
        assertThat(summary.workbook()).isNull();
    }

    @Test
    void validatingTheGroupAsOneDataSetAgainstAllItsShapesGivesTheFalseFindingsThisMethodAvoids() throws Exception {
        // The naive run: area A and the boundary merged, every shape of both profiles at once.
        SHACLValidationReport naive = new SHACLValidator(SHACLValidationOptions.builder()
                .dataFiles(models.resolve("areaA/a_core.xml"), models.resolve("areaA/a_state.xml"),
                        models.resolve("boundary/bd.xml"))
                .shapeFiles(library.resolve("SHACL/Core-Con-Simple.ttl"), library.resolve("SHACL/Core-Con-Complex.ttl"),
                        library.resolve("SHACL/State-Con-Simple.ttl"))
                .xmlBase(BASE).build()).validate();
        List<String> naiveLines = naive.getResults().stream()
                .map(r -> local(r.getSourceShape()) + " | " + local(r.getConstraintComponent()) + " | " + local(r.getFocusNode()))
                .sorted().distinct().toList();

        // D1 is continued in the second document: its second name breaks maxCount, and each
        // profile's closed shape rejects what the other profile adds. D2 and D3 have no state.
        assertThat(naiveLines).contains(
                "DeviceCard-name | MaxCountConstraintComponent | D1",
                "DeviceClosed | ClosedConstraintComponent | D1",
                "DeviceState | ClosedConstraintComponent | D1",
                "DeviceState-state | MinCountConstraintComponent | D2",
                "DeviceState-state | MinCountConstraintComponent | D3");

        List<String> ours = lines(validate(options()));
        assertThat(ours).noneMatch(l -> l.endsWith("| D1"));
        assertThat(ours).noneMatch(l -> l.contains("ClosedConstraintComponent") || l.contains("MinCountConstraintComponent"));
        // The real duplicate inside one document is still found.
        assertThat(ours).contains("cgmes-areaA | a_core.xml | DeviceCard-name | MaxCountConstraintComponent | D2");
    }

    @Test
    void aReferenceIntoAContextDocumentResolvesAndTheContextIsReportedInItsOwnGroupOnly() throws Exception {
        ProfileValidationSummary summary = validate(options());

        // D1 sits on boundary node BP1: with the boundary as context its class check passes.
        assertThat(lines(summary)).noneMatch(l -> l.contains("ClassConstraintComponent") && l.endsWith("| D1"));
        // BPL's long name is met in every group that loads the boundary, and reported once.
        assertThat(lines(summary)).filteredOn(l -> l.endsWith("| BPL")).containsExactly(
                "cgmes-boundary | bd.xml | NameLength-name | MaxLengthConstraintComponent | BPL");
        // The boundary-only shape runs in the boundary group and nowhere else: N1 would break it.
        assertThat(lines(summary)).filteredOn(l -> l.contains("BoundaryOnly")).hasSize(1);
        assertThat(summary.groups().get(1).context()).containsExactly("bd.xml");
        assertThat(summary.groups().get(1).shapes()).doesNotContain("Core-Boundary-Con.ttl")
                .contains("Common-Complex.ttl", "Core-Con-Simple.ttl", "State-Con-Simple.ttl");
        assertThat(summary.groups().get(0).shapes()).contains("Core-Boundary-Con.ttl");
    }

    @Test
    void withoutTheWorkaroundsEveryShapeOfAProfileRunsEverywhere() throws Exception {
        ProfileValidationSummary summary = validate(options().boundaryShapesPattern("").commonShapes(List.of()));

        // The boundary shape now also meets N1 in area A; the common shape is gone.
        assertThat(lines(summary)).contains("cgmes-areaA | a_core.xml | BoundaryOnly-name | PatternConstraintComponent | N1");
        assertThat(lines(summary)).noneMatch(l -> l.contains("NameLength"));
    }

    @Test
    void theDefaultCommonShapesAreUsedOnlyWhereTheLibraryHasThem() throws Exception {
        // The default name is not in this library, so nothing is added and nothing is missing.
        ProfileValidationSummary summary = validate(options().commonShapes(null));

        assertThat(lines(summary)).noneMatch(l -> l.contains("NameLength"));
        assertThat(summary.complete()).isTrue();
        assertThatThrownBy(() -> validate(options().commonShapes(List.of("NoSuchShapes.ttl"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Common shapes not found");
    }

    @Test
    void aCardinalityConstraintRunsPerDocumentWhateverFileItIsIn() throws Exception {
        // Not in a "Simple" file: one in the profile's other file, one in the common shapes.
        shapes("Core-Con-Complex.ttl", """
                ex:DeviceNodeRef a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceNodeRef-node .
                ex:DeviceNodeRef-node sh:path ex:node ; sh:class ex:Node ; sh:minCount 1 .
                """);
        shapes("Common-Complex.ttl", """
                ex:NameLength a sh:NodeShape ; sh:targetSubjectsOf ex:name ; sh:property ex:NameLength-name .
                ex:NameLength-name sh:path ex:name ; sh:maxLength 10 .
                ex:NodeLabel a sh:NodeShape ; sh:targetClass ex:Node ; sh:property ex:NodeLabel-label .
                ex:NodeLabel-label sh:path ex:label ; sh:minCount 1 .
                """);
        model("areaB/b_core.xml", "urn:uuid:b-core", CORE, null, """
                  <ex:Device rdf:ID="E1"><ex:name>E1</ex:name></ex:Device>
                  <ex:Node rdf:ID="N9"><ex:name>N9</ex:name></ex:Node>
                """);

        List<String> lines = lines(validate(options()));

        assertThat(lines).contains(
                "cgmes-areaB | b_core.xml | DeviceNodeRef-node | MinCountConstraintComponent | E1",
                "cgmes-areaB | b_core.xml | NodeLabel-label | MinCountConstraintComponent | N9");
        // Per document: N1 of area A is reported once, in its own document, not for every group.
        assertThat(lines).filteredOn(l -> l.endsWith("| N1")).containsExactly(
                "cgmes-areaA | a_core.xml | NodeLabel-label | MinCountConstraintComponent | N1");
    }

    @Test
    void aQualifiedCountIsNotMistakenForACardinalityConstraintAndStaysOnTheGroup() throws Exception {
        shapes("Core-Con-Complex.ttl", """
                ex:DeviceOnNode a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceOnNode-node .
                ex:DeviceOnNode-node sh:path ex:node ; sh:qualifiedValueShape [ sh:class ex:Node ] ; sh:qualifiedMinCount 1 .
                """);

        List<String> lines = lines(validate(options()));

        // E1 points at a node that exists nowhere; D1's node is in the boundary, which the group sees.
        assertThat(lines).contains(
                "cgmes-areaB | b_core.xml | DeviceOnNode-node | QualifiedMinCountConstraintComponent | E1");
        assertThat(lines).noneMatch(l -> l.contains("Qualified") && l.endsWith("| D1"));
    }

    @Test
    void twoSparqlConstraintsOfOneShapeOnTheSameNodeAreTwoFindings() throws Exception {
        shapes("Core-Con-Complex.ttl", """
                ex:Twice a sh:NodeShape ; sh:targetClass ex:Device ;
                    sh:sparql [ sh:message "first" ; sh:select "SELECT $this WHERE { $this <http://example.org/grid#name> \\"E1\\" }" ] ,
                              [ sh:message "second" ; sh:select "SELECT $this WHERE { $this <http://example.org/grid#name> \\"E1\\" }" ] .
                """);

        assertThat(validate(options()).findings()).filteredOn(f -> f.result().getSourceShape().endsWith("Twice"))
                .extracting(f -> f.result().getMessage()).containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void whatIsMetOnAContextDocumentOnlyIsCountedForTheGroup() throws Exception {
        ProfileValidationSummary summary = validate(options());

        // BPL's long name: a finding of the boundary group, counted in the two groups that load it as context.
        assertThat(summary.groups()).extracting(ProfileValidationSummary.GroupResult::contextFindings)
                .containsExactly(0, 1, 1);
    }

    // ---- fail closed ---------------------------------------------------------------------

    @Test
    void anUnknownProfileStopsTheRunUnlessAllowedAndThenTheRunIsIncomplete() throws Exception {
        model("areaB/b_other.xml", "urn:uuid:b-other", "urn:example:profile:Unknown/9", null, "");

        assertThatThrownBy(() -> validate(options())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b_other.xml").hasMessageContaining("urn:example:profile:Unknown/9");

        ProfileValidationSummary summary = validate(options().allowUnmapped(true));
        assertThat(summary.skipped()).contains(new ProfileGroupPlanner.Skipped("b_other.xml",
                "unmapped profile: urn:example:profile:Unknown/9"));
        assertThat(summary.complete()).isFalse();
        assertThat(summary.conforms()).isFalse();
    }

    @Test
    void aProfileThatLacksAShapeFileIsTreatedLikeAnUnknownOne() throws Exception {
        Files.delete(library.resolve("SHACL/State-Con-Simple.ttl"));

        assertThatThrownBy(() -> validate(options())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a_state.xml").hasMessageContaining("profile without all its shapes");

        ProfileValidationSummary summary = validate(options().allowUnmapped(true));
        assertThat(summary.complete()).isFalse();
        assertThat(summary.gaps()).contains("State.rdf: artifact not found: State-Con-Simple.ttl");
    }

    @Test
    void aCleanButIncompleteRunDoesNotConform() throws Exception {
        // Only the boundary is left, repaired; one document of an unknown profile is skipped.
        for (String file : List.of("areaA/a_core.xml", "areaA/a_state.xml", "areaB/b_core.xml")) {
            Files.delete(models.resolve(file));
        }
        model("boundary/bd.xml", "urn:uuid:bd", CORE, null, """
                  <ex:Node rdf:ID="BP1"><ex:name>BP-1</ex:name></ex:Node>
                """);
        assertThat(validate(options()).conforms()).isTrue();

        model("boundary/other.xml", "urn:uuid:other", "urn:example:profile:Unknown/9", null, "");
        ProfileValidationSummary summary = validate(options().allowUnmapped(true));
        assertThat(summary.findings()).isEmpty();
        assertThat(summary.conforms()).isFalse();
    }

    @Test
    void aConstraintWithSparqlThatCannotBeParsedIsReportedOnceAndTheRestStillRuns() throws Exception {
        shapes("Core-Con-Complex.ttl", """
                ex: a <http://www.w3.org/2002/07/owl#Ontology> ;
                    sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/grid#"^^<http://www.w3.org/2001/XMLSchema#anyURI> ] .
                ex:DeviceNodeRef a sh:NodeShape ; sh:targetClass ex:Device ; sh:property ex:DeviceNodeRef-node .
                ex:DeviceNodeRef-node sh:path ex:node ; sh:class ex:Node .
                ex:Broken a sh:NodeShape ; sh:targetClass ex:Device ;
                    sh:sparql [ sh:message "never runs" ; sh:prefixes ex: ;
                                sh:select "SELECT $this WHERE { $this ex:name ?n FILTER( }" ] .
                ex:Sound a sh:NodeShape ; sh:targetClass ex:Device ;
                    sh:sparql [ sh:message "a device named E1" ; sh:prefixes ex: ;
                                sh:select "SELECT $this WHERE { $this ex:name \\"E1\\" }" ] .
                """);

        ProfileValidationSummary summary = validate(options());

        List<Finding> broken = summary.findings().stream()
                .filter(f -> ProfileValidator.INVALID_SPARQL_COMPONENT.equals(f.result().getConstraintComponent())).toList();
        assertThat(broken).hasSize(1);
        assertThat(broken.getFirst().documents()).isEmpty();
        assertThat(broken.getFirst().result().getSourceShape()).isEqualTo(EX + "Broken");
        assertThat(broken.getFirst().result().getMessage()).contains("could not be parsed");
        assertThat(summary.complete()).isFalse();
        // The sound SPARQL constraint of the same file, with the same prefixes, still runs.
        assertThat(lines(summary)).contains("cgmes-areaB | b_core.xml | Sound | SPARQLConstraintComponent | E1");
        assertThat(lines(summary)).contains("cgmes-areaB | b_core.xml | DeviceNodeRef-node | ClassConstraintComponent | E1");
    }

    @Test
    void aGroupThatLostAConstraintDoesNotConformInItsReportsEither() throws Exception {
        shapes("Core-Con-Complex.ttl", """
                ex:Broken a sh:NodeShape ; sh:targetClass ex:Device ;
                    sh:sparql [ sh:message "never runs" ; sh:select "SELECT $this WHERE { $this ?p ?o FILTER( }" ] .
                """);
        // The boundary alone, with nothing to find: only the lost constraint stands between it and a pass.
        for (String file : List.of("areaA/a_core.xml", "areaA/a_state.xml", "areaB/b_core.xml")) {
            Files.delete(models.resolve(file));
        }
        model("boundary/bd.xml", "urn:uuid:bd", CORE, null, """
                  <ex:Node rdf:ID="BP1"><ex:name>BP-1</ex:name></ex:Node>
                """);

        ProfileValidationSummary summary = validate(options().outputDir(tempDir.resolve("out")).exportTurtleReports(true));

        assertThat(summary.groups().getFirst().incomplete()).isTrue();
        assertThat(summary.conforms()).isFalse();
        Model report = RDFDataMgr.loadModel(summary.turtleReports().getFirst().toUri().toString());
        assertThat(report.listObjectsOfProperty(report.createProperty("http://www.w3.org/ns/shacl#conforms"))
                .toList().stream().map(n -> n.asLiteral().getBoolean())).containsExactly(false);
        // The workbook says the same of the document, and lists the document that was not validated.
        String flat = Snapshots.flattenWorkbook(summary.workbook(), s -> s);
        assertThat(flat).contains("(not validated)").contains("ref.xml");
    }

    @Test
    void aFolderWhereNoDocumentDeclaresAProfileIsRefusedNotPassed() throws Exception {
        Path only = tempDir.resolve("only");
        write(only.resolve("x/ref.xml"), Files.readString(models.resolve("areaB/ref.xml")));

        assertThatThrownBy(() -> validate(options().modelsDir(only))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can be validated").hasMessageContaining("no application profile declared");
    }

    @Test
    void aBoundaryPatternThatTakesEveryShapeOfAProfileIsRefused() {
        assertThatThrownBy(() -> validate(options().boundaryShapesPattern(".ttl")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no shapes are left");
    }

    @Test
    void groupNamesThatWouldShareAReportFileOrAreNotPlainNamesAreRefused() {
        assertThatThrownBy(() -> validate(options().groups(List.of(
                new ProfileGroupPlanner.GroupSpec("a b", List.of(models.resolve("areaA")), null, false, null, null),
                new ProfileGroupPlanner.GroupSpec("a_b", List.of(models.resolve("areaB")), null, false, null, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("share the report file name");
        for (String name : List.of("", "a/b", "..", "CON", "nul.x", "x".repeat(81), "=cmd()")) {
            assertThatThrownBy(() -> new ProfileGroupPlanner.GroupSpec(name, List.of(models), null, false, null, null))
                    .as(name).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void underAPathPolicyAnOutputFolderOutsideTheRootsIsRefusedBeforeAnythingRuns() throws Exception {
        PathPolicy policy = PathPolicy.builder().readRoot(models).readRoot(library)
                .writeRoot(Files.createDirectories(tempDir.resolve("allowed-out"))).build();
        Path forbidden = tempDir.resolve("forbidden-out");

        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> validate(options().outputDir(forbidden))))
                .isInstanceOf(PathNotAllowedException.class);
        assertThat(forbidden).doesNotExist();
        // Shapes and common shapes outside the read roots are refused as well.
        Path outside = Files.writeString(tempDir.resolve("outside.ttl"), TTL_PREFIXES);
        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> validate(options()
                .commonShapes(List.of(outside.toString())))))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> validate(options().groups(List.of(
                new ProfileGroupPlanner.GroupSpec("g", List.of(models.resolve("areaB")), null, false,
                        List.of(outside), null))))))
                .isInstanceOf(PathNotAllowedException.class);
        assertThat(PathPolicy.runWith(policy, () -> validate(options().outputDir(tempDir.resolve("allowed-out"))))
                .workbook()).exists();
    }

    @Test
    void shapesThatAreBrokenBeyondTheirSparqlStopTheRun() throws Exception {
        Files.writeString(library.resolve("SHACL/Core-Con-Complex.ttl"), "this is not turtle");

        assertThatThrownBy(() -> validate(options())).isInstanceOf(IOException.class);
    }

    @Test
    void aFolderWithoutDocumentsIsAnErrorNotACleanRun() throws Exception {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));

        assertThatThrownBy(() -> validate(options().modelsDir(empty))).isInstanceOf(IOException.class)
                .hasMessageContaining("No instance documents");
        assertThatThrownBy(() -> validate(options().modelsDir(tempDir.resolve("absent"))))
                .isInstanceOf(IOException.class).hasMessageContaining("Models folder not found");
    }

    // ---- groups --------------------------------------------------------------------------

    @Test
    void thePlanIsAvailableWithoutValidating() throws Exception {
        ProfileValidator.Preview preview = new ProfileValidator(options().build()).plan();

        assertThat(preview.plan().groups()).extracting(ProfileGroup::name)
                .containsExactly("cgmes-boundary", "cgmes-areaA", "cgmes-areaB");
        ProfileGroup areaA = preview.plan().groups().get(1);
        assertThat(areaA.reported()).extracting(h -> h.source()).containsExactly("a_core.xml", "a_state.xml");
        assertThat(areaA.context()).extracting(h -> h.source()).containsExactly("bd.xml");
        assertThat(areaA.datasetShapes().values()).extracting(l -> l.stream().map(p -> p.getFileName().toString()).toList())
                .containsExactly(List.of("Common-Complex.ttl", "Core-Con-Complex.ttl", "Core-Con-Simple.ttl"),
                        List.of("Common-Complex.ttl", "State-Con-Simple.ttl"));
        assertThat(preview.plan().skipped()).hasSize(1);
        assertThat(preview.gaps()).hasSize(1);
    }

    @Test
    void documentsDirectlyInTheModelsFolderFormAGroupAndDatasetHeadersFormTheirOwn() throws Exception {
        model("top.xml", "urn:uuid:top", CORE, null, """
                  <ex:Node rdf:ID="T1"><ex:name>T1</ex:name></ex:Node>
                """);
        write(models.resolve("areaA/a_plan.xml"), """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dcat="http://www.w3.org/ns/dcat#"
                         xmlns:dcterms="http://purl.org/dc/terms/" xmlns:ex="http://example.org/grid#">
                  <dcat:Dataset rdf:about="urn:uuid:a-plan"><dcterms:conformsTo rdf:resource="%s"/></dcat:Dataset>
                  <ex:Device rdf:about="#D2"><ex:state>off</ex:state></ex:Device>
                </rdf:RDF>
                """.formatted(STATE));

        ProfileValidator.Preview preview = new ProfileValidator(options().build()).plan();

        assertThat(preview.plan().groups()).extracting(ProfileGroup::name)
                .containsExactly("cgmes-boundary", "cgmes-(root)", "cgmes-areaA", "nc-areaA", "cgmes-areaB");
        ProfileGroup nc = preview.plan().groups().get(3);
        assertThat(nc.reported()).extracting(h -> h.source()).containsExactly("a_plan.xml");
        // The area's own model documents and the boundary are its context.
        assertThat(nc.context()).extracting(h -> h.source()).containsExactly("a_core.xml", "a_state.xml", "bd.xml");
        // Common shapes belong to groups that report a CGMES document.
        assertThat(nc.unionShapes()).extracting(p -> p.getFileName().toString()).containsExactly("State-Con-Simple.ttl");
    }

    @Test
    void explicitGroupsReplaceTheFoldersAndCanAssumeAProfile() throws Exception {
        ProfileValidationSummary summary = validate(options().groups(List.of(
                new ProfileGroupPlanner.GroupSpec("everything-b", List.of(models.resolve("areaB")),
                        List.of(models.resolve("boundary/bd.xml")), false, List.of(), List.of(CORE)))));

        // ref.xml declares nothing; the group says to read it as Core. R1 is a Node, so nothing fires.
        assertThat(lines(summary)).containsExactly(
                "everything-b | b_core.xml | DeviceNodeRef-node | ClassConstraintComponent | E1");
        assertThat(summary.groups().getFirst().reported()).containsExactly("b_core.xml", "ref.xml");
        assertThat(summary.skipped()).extracting(ProfileGroupPlanner.Skipped::reason)
                .containsOnly("not reported in any group").hasSize(3);

        assertThatThrownBy(() -> validate(options().groups(List.of(
                new ProfileGroupPlanner.GroupSpec("one", List.of(models.resolve("areaB")), null, false, null, null),
                new ProfileGroupPlanner.GroupSpec("two", List.of(models.resolve("areaB/b_core.xml")), null, false, null, null)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reported in two groups");
    }

    @Test
    void documentsInsideAnArchiveAreValidatedLikeFiles() throws Exception {
        Path archive = models.resolve("areaB/b.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new java.util.zip.ZipEntry("b_core.xml"));
            out.write(Files.readAllBytes(models.resolve("areaB/b_core.xml")));
            out.closeEntry();
        }
        Files.delete(models.resolve("areaB/b_core.xml"));

        assertThat(lines(validate(options()))).contains(
                "cgmes-areaB | b.zip/b_core.xml | DeviceNodeRef-node | ClassConstraintComponent | E1");
    }

    // ---- reports -------------------------------------------------------------------------

    @Test
    void aWorkbookAndOneTurtleReportPerGroupAreWritten() throws Exception {
        Path out = tempDir.resolve("out");

        ProfileValidationSummary summary = validate(options().outputDir(out).exportTurtleReports(true));

        assertThat(summary.workbook()).exists().hasParent(out);
        assertThat(summary.turtleReports()).extracting(p -> p.getFileName().toString()).containsExactly(
                "cgmes-boundary__shacl_report.ttl", "cgmes-areaA__shacl_report.ttl", "cgmes-areaB__shacl_report.ttl");
        // The full report of area A also holds what was met on its context: BPL's long name.
        Model areaA = RDFDataMgr.loadModel(summary.turtleReports().get(1).toUri().toString());
        String focusNodes = areaA.listObjectsOfProperty(areaA.createProperty("http://www.w3.org/ns/shacl#focusNode"))
                .toList().stream().map(Object::toString).sorted().collect(Collectors.joining(" "));
        assertThat(focusNodes).contains("#BPL").contains("#D2").contains("#D3").doesNotContain("#D1");
        String flat = Snapshots.flattenWorkbook(summary.workbook(), s -> s);
        assertThat(flat).contains("cgmes-areaA").contains("a_core.xml").contains("DeviceCard-name");
    }

    @Test
    void theSummaryOfTheWholeDataSetIsStable() throws Exception {
        ProfileValidationSummary summary = validate(options());

        StringBuilder text = new StringBuilder();
        for (ProfileValidationSummary.GroupResult group : summary.groups()) {
            text.append("group ").append(group.name()).append('\n')
                    .append("  reported: ").append(group.reported()).append('\n')
                    .append("  context: ").append(group.context()).append('\n')
                    .append("  shapes: ").append(group.shapes()).append('\n')
                    .append("  findings: ").append(group.findings()).append(' ').append(group.severities()).append('\n');
        }
        lines(summary).forEach(l -> text.append("finding ").append(l).append('\n'));
        summary.skipped().forEach(s -> text.append("skipped ").append(s.source()).append(": ").append(s.reason()).append('\n'));
        summary.gaps().forEach(g -> text.append("gap ").append(g).append('\n'));
        summary.warnings().forEach(w -> text.append("warning ").append(w).append('\n'));
        text.append("complete ").append(summary.complete()).append(", partial ").append(summary.partial())
                .append(", conforms ").append(summary.conforms()).append('\n');

        SNAPSHOTS.assertTextEquals("summary.txt", text.toString());
    }
}
