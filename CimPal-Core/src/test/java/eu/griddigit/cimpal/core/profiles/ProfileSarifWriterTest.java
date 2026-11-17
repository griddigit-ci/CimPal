/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.Finding;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SARIF for code scanning, the line locator behind it, and the Markdown run summary. */
class ProfileSarifWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "http://example.org/data";
    private static final String EX = "http://example.org/grid#";

    @TempDir
    Path repo;

    private Path coreFile;
    private InstanceHeader core;

    @BeforeEach
    void document() throws IOException {
        coreFile = write("Instance/areaA/a core.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:ex="http://example.org/grid#">
                  <ex:Device rdf:ID="D1">
                    <ex:name>one</ex:name>
                  </ex:Device>
                  <ex:Device rdf:ID="D2">
                    <ex:name>two</ex:name>
                    <ex:name>two again</ex:name>
                  </ex:Device>
                  <ex:Device rdf:about="#D3">
                    <ex:node rdf:resource="#N9"/>
                  </ex:Device>
                </rdf:RDF>
                """);
        core = header(coreFile, null);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static InstanceHeader header(Path file, String entry) {
        return new InstanceHeader(file, entry, InstanceHeader.Kind.CGMES, List.of("urn:p"), null, List.of());
    }

    private static SHACLValidationResult result(String shape, String component, String focus, String path,
                                                String message, String severity) {
        return new SHACLValidationResult(shape, focus, severity, message, "", path, component, "",
                "A device needs one name", "", "Device name", "");
    }

    private static Finding finding(InstanceHeader document, String shape, String component, String id, String property,
                                   String message) {
        return new Finding("cgmes-areaA", List.of(document.source()),
                result(shape, component, "ex:" + id, property == null ? "" : "ex:" + property, message, "sh:Violation"),
                List.of(document), BASE + "#" + id, property == null ? null : EX + property);
    }

    private ProfileValidationSummary summary(List<Finding> findings, InstanceHeader... reported) {
        ProfileValidationSummary.GroupResult group = new ProfileValidationSummary.GroupResult("cgmes-areaA",
                List.of(reported).stream().map(InstanceHeader::source).toList(), List.of(), List.of("Core.ttl"),
                findings.size(), 0, Map.of("Violation", (long) findings.size()), false, false, 1200, List.of(reported));
        return new ProfileValidationSummary(List.of(group), findings, List.of(), List.of(), List.of(), true, false,
                null, List.of());
    }

    private JsonNode sarif(ProfileValidationSummary summary, ProfileSarifWriter.Mode mode) throws IOException {
        return JSON.readTree(ProfileSarifWriter.toJson(summary,
                new ProfileSarifWriter.Settings(repo, null, mode, "2026.11.1", BASE)));
    }

    // ---- line locator --------------------------------------------------------------------

    @Test
    void objectsAndTheirPropertiesAreLocatedByLine() throws Exception {
        Map<String, XmlLineLocator.Located> found = XmlLineLocator.locate(coreFile,
                List.of(BASE + "#D1", BASE + "#D2", BASE + "#D3", BASE + "#Nowhere"), BASE);

        assertThat(found).doesNotContainKey(BASE + "#Nowhere");
        assertThat(found.get(BASE + "#D1").line()).isEqualTo(4);
        assertThat(found.get(BASE + "#D2").lineOf(EX + "name")).isEqualTo(8);
        assertThat(found.get(BASE + "#D3").line()).isEqualTo(11);
        assertThat(found.get(BASE + "#D3").lineOf(EX + "node")).isEqualTo(12);
        assertThat(found.get(BASE + "#D3").lineOf(EX + "other")).isEqualTo(11);
    }

    @Test
    void theDocumentsOwnXmlBaseWinsAndAbsoluteIrisAreKept() throws Exception {
        Path file = write("x.xml", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xml:base="http://other.example/m">
                  <rdf:Description rdf:ID="A"/>
                  <rdf:Description rdf:about="urn:uuid:1234"/>
                </rdf:RDF>
                """);

        Map<String, XmlLineLocator.Located> found = XmlLineLocator.locate(file,
                List.of("http://other.example/m#A", "urn:uuid:1234", BASE + "#A"), BASE);

        assertThat(found.keySet()).containsExactlyInAnyOrder("http://other.example/m#A", "urn:uuid:1234");
    }

    @Test
    void anExternalEntityIsNeverRead() throws Exception {
        Path secret = write("secret.txt", "TOP SECRET");
        Path file = write("xxe.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE rdf:RDF [<!ENTITY s SYSTEM "%s">]>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:ID="A"><rdf:value>&s;</rdf:value></rdf:Description>
                </rdf:RDF>
                """.formatted(secret.toUri()));

        // Either it fails, or it finds A without having read the entity: never the content.
        try {
            assertThat(XmlLineLocator.locate(file, List.of(BASE + "#A"), BASE)).containsKey(BASE + "#A");
        } catch (IOException ex) {
            assertThat(ex.getMessage()).doesNotContain("TOP SECRET");
        }
    }

    // ---- SARIF ---------------------------------------------------------------------------

    @Test
    void oneResultPerRuleAndDocumentPointsAtTheFirstOccurrence() throws Exception {
        JsonNode log = sarif(summary(List.of(
                finding(core, "ex:DeviceCard-name", "sh:MaxCountConstraintComponent", "D2", "name", "two names"),
                finding(core, "ex:DeviceCard-name", "sh:MaxCountConstraintComponent", "D1", "name", "two names"),
                finding(core, "ex:DeviceNodeRef-node", "sh:ClassConstraintComponent", "D3", "node", "no node")),
                core), ProfileSarifWriter.Mode.RULE);

        assertThat(log.path("version").asText()).isEqualTo("2.1.0");
        JsonNode run = log.path("runs").get(0);
        assertThat(run.path("tool").path("driver").path("name").asText()).isEqualTo("CimPal");
        assertThat(run.path("tool").path("driver").path("rules")).extracting(r -> r.path("id").asText())
                .containsExactly("DeviceCard-name/MaxCount", "DeviceNodeRef-node/Class");
        assertThat(run.path("automationDetails").path("id").asText()).isEqualTo("cimpal-profiles/");
        JsonNode results = run.path("results");
        assertThat(results).hasSize(2);

        JsonNode count = results.get(0);
        assertThat(count.path("ruleId").asText()).isEqualTo("DeviceCard-name/MaxCount");
        assertThat(count.path("level").asText()).isEqualTo("error");
        assertThat(count.path("occurrenceCount").asInt()).isEqualTo(2);
        JsonNode location = count.path("locations").get(0).path("physicalLocation");
        assertThat(location.path("artifactLocation").path("uri").asText()).isEqualTo("Instance/areaA/a%20core.xml");
        // D1's name (line 5) comes before D2's (line 8).
        assertThat(location.path("region").path("startLine").asInt()).isEqualTo(5);
        assertThat(count.path("message").path("text").asText()).contains("two names").contains("2 object(s): ex:D1, ex:D2");
        assertThat(results.get(1).path("locations").get(0).path("physicalLocation").path("region")
                .path("startLine").asInt()).isEqualTo(12);
        assertThat(count.path("partialFingerprints").path("primaryLocationLineHash").asText()).matches("[0-9a-f]{32}:1");
    }

    @Test
    void oneResultPerObjectWhenAskedFor() throws Exception {
        JsonNode results = sarif(summary(List.of(
                finding(core, "ex:DeviceCard-name", "sh:MaxCountConstraintComponent", "D2", "name", "two names"),
                finding(core, "ex:DeviceCard-name", "sh:MaxCountConstraintComponent", "D1", "name", "two names")),
                core), ProfileSarifWriter.Mode.OBJECT).path("runs").get(0).path("results");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r.path("locations").get(0).path("physicalLocation").path("region")
                .path("startLine").asInt()).containsExactlyInAnyOrder(5, 8);
        assertThat(results.get(0).path("partialFingerprints").path("primaryLocationLineHash").asText())
                .isNotEqualTo(results.get(1).path("partialFingerprints").path("primaryLocationLineHash").asText());
    }

    @Test
    void aFingerprintDoesNotChangeWhenTheFileIsEditedAboveTheFinding() throws Exception {
        List<Finding> findings = List.of(
                finding(core, "ex:DeviceCard-name", "sh:MaxCountConstraintComponent", "D2", "name", "two names"));
        for (ProfileSarifWriter.Mode mode : ProfileSarifWriter.Mode.values()) {
            JsonNode before = sarif(summary(findings, core), mode).path("runs").get(0).path("results").get(0);
            Files.writeString(coreFile, Files.readString(coreFile).replace("<ex:Device rdf:ID=\"D1\">",
                    "<!-- a comment\n over two lines -->\n  <ex:Device rdf:ID=\"D1\">"));
            JsonNode after = sarif(summary(findings, core), mode).path("runs").get(0).path("results").get(0);
            document();

            int line = before.path("locations").get(0).path("physicalLocation").path("region").path("startLine").asInt();
            assertThat(after.path("locations").get(0).path("physicalLocation").path("region").path("startLine").asInt())
                    .as(mode.name()).isEqualTo(line + 2);
            assertThat(after.path("partialFingerprints")).as(mode.name()).isEqualTo(before.path("partialFingerprints"));
        }
    }

    @Test
    void severitiesMapToLevelsAndShapeFindingsGetALocation() throws Exception {
        Finding warning = new Finding("cgmes-areaA", List.of(core.source()),
                result("ex:W", "sh:PatternConstraintComponent", "ex:D1", "ex:name", "w", "sh:Warning"),
                List.of(core), BASE + "#D1", EX + "name");
        Finding info = new Finding("cgmes-areaA", List.of(core.source()),
                result("ex:I", "sh:PatternConstraintComponent", "ex:D1", "ex:name", "i", "sh:Info"),
                List.of(core), BASE + "#D1", EX + "name");
        Finding aboutShapes = new Finding("cgmes-areaA", List.of(),
                result("ex:Broken", "cimpal:InvalidSparqlConstraint", "", "", "could not be parsed", "sh:Violation"));

        JsonNode results = sarif(summary(List.of(warning, info, aboutShapes), core), ProfileSarifWriter.Mode.RULE)
                .path("runs").get(0).path("results");

        assertThat(results).extracting(r -> r.path("ruleId").asText() + "=" + r.path("level").asText())
                .containsExactly("Broken/InvalidSparqlConstraint=error", "I/Pattern=note", "W/Pattern=warning");
        JsonNode shapes = results.get(0);
        assertThat(shapes.path("locations").get(0).path("physicalLocation").path("region").path("startLine").asInt()).isEqualTo(1);
        assertThat(shapes.path("message").path("text").asText()).contains("about the shapes");
    }

    @Test
    void aDocumentInAnArchivePointsAtTheArchive() throws Exception {
        InstanceHeader inZip = header(write("Instance/b.zip", "not read"), "b_core.xml");

        JsonNode result = sarif(summary(List.of(
                finding(inZip, "ex:DeviceCard-name", "sh:MinCountConstraintComponent", "E1", "name", "no name")),
                inZip), ProfileSarifWriter.Mode.RULE).path("runs").get(0).path("results").get(0);

        JsonNode location = result.path("locations").get(0).path("physicalLocation");
        assertThat(location.path("artifactLocation").path("uri").asText()).isEqualTo("Instance/b.zip");
        assertThat(location.path("region").path("startLine").asInt()).isEqualTo(1);
        assertThat(result.path("message").path("text").asText()).contains("In archive entry b_core.xml");
    }

    @Test
    void aDocumentOutsideTheBaseAndTooManyResultsAreRefused(@TempDir Path elsewhere) throws Exception {
        ProfileValidationSummary outside = summary(List.of(), core);
        assertThatThrownBy(() -> ProfileSarifWriter.toJson(summary(List.of(
                        finding(core, "ex:S", "sh:MinCountConstraintComponent", "D1", "name", "m")), core),
                new ProfileSarifWriter.Settings(elsewhere, null, ProfileSarifWriter.Mode.RULE, null, BASE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the SARIF base");
        assertThat(sarif(outside, ProfileSarifWriter.Mode.RULE).path("runs").get(0).path("results")).isEmpty();

        List<Finding> many = new ArrayList<>();
        for (int i = 0; i <= ProfileSarifWriter.MAX_RESULTS; i++) {
            many.add(finding(core, "ex:S", "sh:MinCountConstraintComponent", "X" + i, "name", "m"));
        }
        assertThatThrownBy(() -> sarif(summary(many, core), ProfileSarifWriter.Mode.OBJECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("25000");
        assertThat(sarif(summary(many, core), ProfileSarifWriter.Mode.RULE).path("runs").get(0).path("results")).hasSize(1);
    }

    @Test
    void anIncompleteRunIsNeverWrittenAsIfItWereClean() throws Exception {
        // Nothing found, but a document was skipped for its profile: an upload must not close alerts as fixed.
        ProfileValidationSummary incomplete = new ProfileValidationSummary(summary(List.of(), core).groups(),
                List.of(), List.of(new ProfileGroupPlanner.Skipped("other.xml", "unmapped profile: urn:x")),
                List.of("Core.rdf: artifact not found: X.ttl"), List.of(), false, false, null, List.of());

        JsonNode run = sarif(incomplete, ProfileSarifWriter.Mode.RULE).path("runs").get(0);

        assertThat(run.path("invocations").get(0).path("executionSuccessful").asBoolean()).isFalse();
        assertThat(run.path("results")).hasSize(1);
        JsonNode result = run.path("results").get(0);
        assertThat(result.path("ruleId").asText()).isEqualTo(ProfileSarifWriter.INCOMPLETE_RULE);
        assertThat(result.path("level").asText()).isEqualTo("error");
        assertThat(result.path("message").path("text").asText()).contains("other.xml").contains("X.ttl");

        JsonNode clean = sarif(summary(List.of(), core), ProfileSarifWriter.Mode.RULE).path("runs").get(0);
        assertThat(clean.path("invocations").get(0).path("executionSuccessful").asBoolean()).isTrue();
        assertThat(clean.path("results")).isEmpty();
    }

    @Test
    void aFileTheLineLocatorCannotReadOnlyLosesItsLineNumbers() throws Exception {
        Path withDoctype = write("Instance/doctype.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE rdf:RDF [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="http://example.org/grid#">
                  <ex:Device rdf:ID="D1"><ex:name>&b;</ex:name></ex:Device>
                </rdf:RDF>
                """);
        InstanceHeader doc = header(withDoctype, null);

        JsonNode result = sarif(summary(List.of(finding(doc, "ex:S", "sh:MinCountConstraintComponent", "D1", "name",
                "m")), doc), ProfileSarifWriter.Mode.RULE).path("runs").get(0).path("results").get(0);

        // Line 1 when the internal entities stop the pass, line 4 when they are skipped; never expanded.
        assertThat(result.path("locations").get(0).path("physicalLocation").path("region").path("startLine").asInt())
                .isIn(1, 4);
        assertThat(result.toString()).doesNotContain("aaaaaaaaaa");
    }

    @Test
    void everyStringParsesBackToItself() throws Exception {
        for (String text : List.of("line sep para", "pair 😀 ok", "quote \" back \\ tab \t",
                "\u0000\u001f", "lone \uD800 high", "lone \uDC00 low")) {
            String json = ProfileSarifWriter.str(text);
            assertThat(json).as(text).doesNotContain(" ").doesNotContain(" ").doesNotContain("\u0000");
            // Escaped, so the document parses; a lone surrogate comes back as the same code unit.
            assertThat(JSON.readTree(json).asText()).as(text).isEqualTo(text);
        }
    }

    @Test
    void textFromTheDataIsEscapedInJsonAndMarkdown() throws Exception {
        String nasty = "a|b\nc\u0007 `code` <b>[x](http://y)";
        Finding f = new Finding("cgmes-areaA", List.of(core.source()),
                result("ex:S", "sh:MinCountConstraintComponent", "ex:D1", "ex:name", nasty, "sh:Violation"),
                List.of(core), BASE + "#D1", EX + "name");
        ProfileValidationSummary s = new ProfileValidationSummary(summary(List.of(f), core).groups(), List.of(f),
                List.of(new ProfileGroupPlanner.Skipped("evil|name.xml", nasty)), List.of(nasty), List.of(), false,
                false, null, List.of());

        JsonNode log = JSON.readTree(ProfileSarifWriter.toJson(s,
                new ProfileSarifWriter.Settings(repo, "my cat/x", ProfileSarifWriter.Mode.RULE, null, BASE)));
        assertThat(log.path("runs").get(0).path("results").get(0).path("message").path("text").asText()).contains(nasty);
        assertThat(log.path("runs").get(0).path("automationDetails").path("id").asText()).isEqualTo("my-cat-x/");

        String md = ProfileSummaryMarkdown.toMarkdown(s);
        assertThat(md).contains("**Result: incomplete.**").contains("evil\\|name.xml").doesNotContain("\u0007");
        // Free text sits in code spans, where markup, links and mentions are not rendered.
        assertThat(md).contains("`a\\|b").contains("- `a\\|b").doesNotContain("`code`");
        assertThat(md.lines().filter(l -> l.contains("<b>")).toList()).allMatch(l -> l.matches(".*`[^`]*<b>[^`]*`.*"));
        for (String line : md.lines().filter(l -> l.startsWith("|")).toList()) {
            assertThat(line.replace("\\|", "").chars().filter(c -> c == '|').count()).as(line).isIn(3L, 4L, 8L);
        }
    }
}
