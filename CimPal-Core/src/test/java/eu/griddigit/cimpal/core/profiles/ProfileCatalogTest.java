/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The catalog that maps a profile IRI to its SHACL files through DX-PROF descriptors. The
 * libraries here are synthetic, with made-up IRIs and file names, so nothing passes because of
 * the naming of a real profile library.
 */
class ProfileCatalogTest {

    private static final String SHACL = "http://www.w3.org/ns/shacl";

    @TempDir
    Path tempDir;

    /** A descriptor in the style of the real ones: xml:base is the PROF namespace. */
    private static String prof(String profileIri, String versionIri, String priorVersion, String... resources) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:owl="http://www.w3.org/2002/07/owl#"
                         xmlns:prof="http://www.w3.org/ns/dx/prof/"
                         xmlns:dcterms="http://purl.org/dc/terms/"
                         xml:base="http://www.w3.org/ns/dx/prof">
                  <rdf:Description rdf:about="%s">
                    <rdf:type rdf:resource="http://www.w3.org/ns/dx/prof/Profile"/>
                """.formatted(profileIri));
        if (versionIri != null) {
            xml.append("    <owl:versionIRI rdf:resource=\"").append(versionIri).append("\"/>\n");
        }
        if (priorVersion != null) {
            xml.append("    <owl:priorVersion rdf:resource=\"").append(priorVersion).append("\"/>\n");
        }
        for (int i = 0; i < resources.length; i++) {
            xml.append("    <prof:hasResource rdf:resource=\"urn:test:res:").append(profileIri.hashCode())
                    .append(':').append(i).append("\"/>\n");
        }
        xml.append("  </rdf:Description>\n");
        for (int i = 0; i < resources.length; i++) {
            xml.append("  <rdf:Description rdf:about=\"urn:test:res:").append(profileIri.hashCode())
                    .append(':').append(i).append("\">\n").append(resources[i]).append("  </rdf:Description>\n");
        }
        return xml.append("</rdf:RDF>\n").toString();
    }

    private static String resource(String role, String conformsTo, String artifact) {
        return "    <prof:hasRole rdf:resource=\"http://www.w3.org/ns/dx/prof/role/" + role + "\"/>\n"
                + "    <prof:hasArtifact rdf:resource=\"" + artifact + "\"/>\n"
                + (conformsTo == null ? "" : "    <dcterms:conformsTo>" + conformsTo + "</dcterms:conformsTo>\n");
    }

    private static String constraints(String artifact) {
        return resource("constraints", SHACL, artifact);
    }

    private Path write(String relative, String content) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        // The catalog lists files by their real path (a temp folder may be spelled in 8.3 form).
        return Files.writeString(file, content).toRealPath();
    }

    private Path shape(String relative) throws IOException {
        return write(relative, "<urn:test:S> a <http://www.w3.org/ns/shacl#NodeShape> .\n");
    }

    private ProfileCatalog load(String... libraries) throws IOException {
        return ProfileCatalog.load(List.of(libraries).stream().map(tempDir::resolve).toList());
    }

    // ---- keys and roles ------------------------------------------------------------------

    @Test
    void aProfileIsFoundByItsIriItsVersionIriAndItsPriorVersion() throws Exception {
        Path shapes = shape("lib/fam/SHACL/Alpha-Con.ttl");
        write("lib/fam/PROF/Alpha.rdf", prof("urn:example:Alpha", "urn:example:Alpha/2.0", "urn:example:Alpha/1.0",
                constraints("Alpha-Con.ttl")));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.keys()).containsExactly("urn:example:Alpha", "urn:example:Alpha/1.0", "urn:example:Alpha/2.0");
        for (String key : catalog.keys()) {
            assertThat(catalog.find(key).orElseThrow().shapeFiles()).as(key).containsExactly(shapes);
        }
        assertThat(catalog.gaps()).isEmpty();
        assertThat(catalog.profiles()).hasSize(1);
    }

    @Test
    void onlyConstraintsResourcesThatConformToShaclAreKept() throws Exception {
        Path kept = shape("lib/fam/SHACL/Kept.ttl");
        shape("lib/fam/SHACL/Validation.ttl");
        shape("lib/fam/SHACL/NotShacl.ttl");
        shape("lib/fam/SHACL/NoConformsTo.ttl");
        write("lib/fam/RDFS/Vocabulary.rdf", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>");
        write("lib/fam/PROF/Alpha.rdf", prof("urn:example:Alpha", null, null,
                constraints("Kept.ttl"),
                resource("validation", SHACL, "Validation.ttl"),
                resource("constraints", "http://www.w3.org/ns/shex", "NotShacl.ttl"),
                resource("constraints", null, "NoConformsTo.ttl"),
                resource("vocabulary", null, "Vocabulary.rdf")));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.find("urn:example:Alpha").orElseThrow().shapeFiles()).containsExactly(kept);
        assertThat(catalog.gaps()).isEmpty();
    }

    @Test
    void anArtifactGivenAsAWebAddressIsResolvedByItsFileName() throws Exception {
        Path shapes = shape("lib/fam/SHACL/Beta-Simple.ttl");
        write("lib/fam/PROF/Beta-described.rdf", prof("https://profiles.example/Beta", "https://profiles.example/Beta/2.3",
                null, constraints("https://con.example/Beta-Simple/2.3/Beta-Simple.ttl")));

        assertThat(load("lib").find("https://profiles.example/Beta/2.3").orElseThrow().shapeFiles())
                .containsExactly(shapes);
    }

    @Test
    void conformsToMayBeAnIriOrEndWithAHash() throws Exception {
        Path shapes = shape("lib/SHACL/Gamma.ttl");
        String asIri = "    <prof:hasRole rdf:resource=\"http://www.w3.org/ns/dx/prof/role/constraints\"/>\n"
                + "    <prof:hasArtifact rdf:resource=\"Gamma.ttl\"/>\n"
                + "    <dcterms:conformsTo rdf:resource=\"http://www.w3.org/ns/shacl#\"/>\n";
        write("lib/PROF/Gamma.rdf", prof("urn:example:Gamma", null, null, asIri));

        assertThat(load("lib").find("urn:example:Gamma").orElseThrow().shapeFiles()).containsExactly(shapes);
    }

    @Test
    void aTurtleDescriptorIsRead() throws Exception {
        Path shapes = shape("lib/shapes/Delta.ttl");
        write("lib/catalog.ttl", """
                @prefix prof: <http://www.w3.org/ns/dx/prof/> .
                @prefix dcterms: <http://purl.org/dc/terms/> .
                <urn:example:Delta> a prof:Profile ;
                    prof:hasResource [ prof:hasRole <http://www.w3.org/ns/dx/prof/role/constraints> ;
                                       prof:hasArtifact <shapes/Delta.ttl> ;
                                       dcterms:conformsTo <http://www.w3.org/ns/shacl> ] .
                """);

        assertThat(load("lib").find("urn:example:Delta").orElseThrow().shapeFiles()).containsExactly(shapes);
    }

    // ---- layout --------------------------------------------------------------------------

    @Test
    void descriptorsAreFoundAtAnyDepthAndUnderAnyNameAndHiddenFoldersAreSkipped() throws Exception {
        Path shapes = shape("lib/a/b/c/rules/Epsilon.ttl");
        write("lib/a/b/c/descriptors/whatever-name.xml", prof("urn:example:Epsilon", null, null,
                constraints("Epsilon.ttl")));
        write("lib/.git/PROF/Hidden.rdf", prof("urn:example:Hidden", null, null, constraints("Epsilon.ttl")));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.keys()).containsExactly("urn:example:Epsilon");
        assertThat(catalog.find("urn:example:Epsilon").orElseThrow().shapeFiles()).containsExactly(shapes);
    }

    @Test
    void theFolderBesideTheDescriptorWinsOverAFileOfTheSameNameElsewhere() throws Exception {
        Path beside = shape("lib/one/SHACL/Common.ttl");
        shape("lib/two/SHACL/Common.ttl");
        write("lib/one/PROF/One.rdf", prof("urn:example:One", null, null, constraints("Common.ttl")));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.find("urn:example:One").orElseThrow().shapeFiles()).containsExactly(beside);
        assertThat(catalog.gaps()).isEmpty();
    }

    @Test
    void theShaclFolderIsSearchedBeforeTheRdfsFolder() throws Exception {
        Path inShacl = shape("lib/fam/SHACL/Both.ttl");
        shape("lib/fam/RDFS/Both.ttl");
        Path onlyRdfs = shape("lib/fam/RDFS/OnlyRdfs.ttl");
        write("lib/fam/PROF/Zeta.rdf", prof("urn:example:Zeta", null, null,
                constraints("Both.ttl"), constraints("OnlyRdfs.ttl")));

        assertThat(load("lib").find("urn:example:Zeta").orElseThrow().shapeFiles())
                .containsExactly(onlyRdfs, inShacl);
    }

    @Test
    void aNameFoundTwiceAwayFromTheDescriptorIsAGapNotAGuess() throws Exception {
        shape("lib/x/Twice.ttl");
        shape("lib/y/Twice.ttl");
        write("lib/descriptors/Eta.rdf", prof("urn:example:Eta", null, null, constraints("Twice.ttl")));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.find("urn:example:Eta").orElseThrow().shapeFiles()).isEmpty();
        assertThat(catalog.gaps()).anyMatch(g -> g.contains("Twice.ttl") && g.contains("found 2 times"));
    }

    // ---- gaps ----------------------------------------------------------------------------

    @Test
    void whatALibraryDoesNotDeliverIsAGapAndNeverAnException() throws Exception {
        shape("lib/fam/SHACL/Present.ttl");
        write("lib/fam/PROF/Partly.rdf", prof("urn:example:Partly", null, null,
                constraints("Present.ttl"), constraints("Absent.ttl")));
        write("lib/fam/PROF/Nothing.rdf", prof("urn:example:Nothing", null, null, constraints("AlsoAbsent.ttl")));
        write("lib/fam/PROF/NoProfile.rdf", """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:prof="http://www.w3.org/ns/dx/prof/">
                  <rdf:Description rdf:about="urn:example:lonely">
                    <prof:hasRole rdf:resource="http://www.w3.org/ns/dx/prof/role/constraints"/>
                  </rdf:Description>
                </rdf:RDF>
                """);
        write("lib/fam/PROF/Broken.rdf", "<rdf:RDF xmlns:prof=\"http://www.w3.org/ns/dx/prof/\"><unclosed>");
        Files.createDirectories(tempDir.resolve("empty"));

        ProfileCatalog catalog = load("lib", "empty");

        assertThat(catalog.find("urn:example:Partly").orElseThrow().missingArtifacts()).containsExactly("Absent.ttl");
        assertThat(catalog.find("urn:example:Nothing").orElseThrow().shapeFiles()).isEmpty();
        assertThat(catalog.gaps()).containsExactlyInAnyOrder(
                "Partly.rdf: artifact not found: Absent.ttl",
                "Nothing.rdf: no resolvable constraints-role SHACL artifacts",
                "Nothing.rdf: artifact not found: AlsoAbsent.ttl",
                "NoProfile.rdf: no prof:Profile node",
                catalog.gaps().stream().filter(g -> g.startsWith("Broken.rdf: could not be read as RDF")).findFirst()
                        .orElse("Broken.rdf gap missing"),
                "no PROF profile found under empty");
    }

    @Test
    void aMissingLibraryFolderIsAnError() {
        assertThatThrownBy(() -> load("nowhere")).isInstanceOf(IOException.class)
                .hasMessageContaining("Profile library folder not found");
        assertThatThrownBy(() -> ProfileCatalog.load(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- several libraries ---------------------------------------------------------------

    @Test
    void librariesAreMergedAndTheSameProfileWithTheSameShapesTwiceIsFine() throws Exception {
        Path alpha = shape("lib1/SHACL/Alpha.ttl");
        write("lib1/PROF/Alpha.rdf", prof("urn:example:Alpha", null, null, constraints("Alpha.ttl")));
        write("lib1/PROF/AlphaAgain.rdf", prof("urn:example:Alpha", null, null, constraints("Alpha.ttl")));
        Path beta = shape("lib2/SHACL/Beta.ttl");
        write("lib2/PROF/Beta.rdf", prof("urn:example:Beta", null, null, constraints("Beta.ttl")));

        ProfileCatalog catalog = load("lib1", "lib2");

        ProfileCatalog.Resolution resolution = catalog.resolve(
                List.of("urn:example:Beta", "urn:example:Unknown", "urn:example:Alpha", "urn:example:Beta"));
        assertThat(resolution.shapeFiles()).containsExactly(alpha, beta);
        assertThat(resolution.unmapped()).containsExactly("urn:example:Unknown");
        assertThat(resolution.incomplete()).isEmpty();
        assertThat(resolution.complete()).isFalse();
        assertThat(catalog.resolve(List.of("urn:example:Alpha")).complete()).isTrue();
    }

    @Test
    void aProfileWithoutAllItsShapesIsIncompleteNeverACleanMapping() throws Exception {
        Path present = shape("lib/SHACL/Present.ttl");
        write("lib/PROF/Partly.rdf", prof("urn:example:Partly", null, null,
                constraints("Present.ttl"), constraints("Absent.ttl")));
        write("lib/PROF/Unusable.rdf", prof("urn:example:Unusable", null, null,
                constraints("Present.ttl"), constraints("https://host.example/My%20Rules.ttl")));
        write("lib/PROF/Empty.rdf", prof("urn:example:Empty", null, null, constraints("Nowhere.ttl")));

        ProfileCatalog catalog = load("lib");
        ProfileCatalog.Resolution resolution = catalog.resolve(
                List.of("urn:example:Partly", "urn:example:Unusable", "urn:example:Empty"));

        assertThat(resolution.shapeFiles()).containsExactly(present);
        assertThat(resolution.unmapped()).isEmpty();
        assertThat(resolution.incomplete())
                .containsExactly("urn:example:Partly", "urn:example:Unusable", "urn:example:Empty");
        assertThat(resolution.complete()).isFalse();
        assertThat(catalog.find("urn:example:Unusable").orElseThrow().missingArtifacts()).hasSize(1);
        assertThat(catalog.find("urn:example:Unusable").orElseThrow().complete()).isFalse();
    }

    @Test
    void aRelativeProfileIriNeverBecomesAKeyThatNamesTheFilesLocation() throws Exception {
        shape("lib/SHACL/Alpha.ttl");
        write("lib/PROF/Relative.rdf", prof("", "urn:example:Relative/1", null, constraints("Alpha.ttl"))
                .replace(" xml:base=\"http://www.w3.org/ns/dx/prof\"", ""));

        assertThat(load("lib").keys()).containsExactly("urn:example:Relative/1");
    }

    @Test
    void aFolderWithTooManyFilesIsNotAProfileLibrary() throws Exception {
        for (int i = 0; i < 6; i++) {
            shape("lib/SHACL/S" + i + ".ttl");
        }

        assertThatThrownBy(() -> ProfileCatalog.load(List.of(tempDir.resolve("lib")), 5))
                .isInstanceOf(IOException.class).hasMessageContaining("more than 5 files");
        assertThat(ProfileCatalog.load(List.of(tempDir.resolve("lib")), 6).keys()).isEmpty();
    }

    @Test
    void aUtf16DescriptorIsStillFound() throws Exception {
        Path shapes = shape("lib/SHACL/Wide.ttl");
        Path file = tempDir.resolve("lib/PROF/Wide.rdf");
        Files.createDirectories(file.getParent());
        Files.write(file, ("\uFEFF" + prof("urn:example:Wide", null, null, constraints("Wide.ttl"))
                .replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"")).getBytes(StandardCharsets.UTF_16BE));

        assertThat(load("lib").find("urn:example:Wide").orElseThrow().shapeFiles()).containsExactly(shapes);
    }

    @Test
    void aLinkInsideTheLibraryThatLeadsOutOfItIsNotFollowed() throws Exception {
        shape("outside/SHACL/Secret.ttl");
        write("outside/PROF/Outside.rdf", prof("urn:example:Outside", null, null, constraints("Secret.ttl")));
        shape("lib/SHACL/Alpha.ttl");
        write("lib/PROF/Alpha.rdf", prof("urn:example:Alpha", null, null,
                constraints("Alpha.ttl"), constraints("Linked.ttl")));
        try {
            Files.createSymbolicLink(tempDir.resolve("lib/linked"), tempDir.resolve("outside"));
            Files.createSymbolicLink(tempDir.resolve("lib/SHACL/Linked.ttl"), tempDir.resolve("outside/SHACL/Secret.ttl"));
        } catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "links cannot be created here: " + ex.getMessage());
        }

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.keys()).containsExactly("urn:example:Alpha");
        assertThat(catalog.find("urn:example:Alpha").orElseThrow().missingArtifacts()).containsExactly("Linked.ttl");
    }

    @Test
    void aFolderThePolicyExcludesIsNotPartOfTheLibrary() throws Exception {
        Path own = shape("lib/SHACL/Alpha.ttl");
        write("lib/PROF/Alpha.rdf", prof("urn:example:Alpha", null, null,
                constraints("Alpha.ttl"), constraints("Theirs.ttl")));
        shape("lib/work/SHACL/Theirs.ttl");
        write("lib/work/PROF/Theirs.rdf", prof("urn:example:Theirs", null, null, constraints("Theirs.ttl")));
        PathPolicy policy = PathPolicy.builder().root(tempDir).exclude(tempDir.resolve("lib/work")).build();

        ProfileCatalog catalog = PathPolicy.runWith(policy, () -> load("lib"));

        assertThat(catalog.keys()).containsExactly("urn:example:Alpha");
        assertThat(catalog.find("urn:example:Alpha").orElseThrow().shapeFiles()).containsExactly(own);
        assertThat(catalog.find("urn:example:Alpha").orElseThrow().missingArtifacts()).containsExactly("Theirs.ttl");
        // Without the policy both are there: the exclusion is what hides them.
        assertThat(load("lib").keys()).containsExactly("urn:example:Alpha", "urn:example:Theirs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\\\host.invalid\\share\\lib", "//host.invalid/share/lib"})
    void underAPathPolicyANetworkOrDeviceLibraryIsRefusedUntouched(String library) {
        PathPolicy policy = PathPolicy.builder().root(tempDir).build();

        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> ProfileCatalog.load(List.of(Path.of(library)))))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void theSameKeyWithDifferentShapesInTwoLibrariesIsRefused() throws Exception {
        shape("lib1/SHACL/Alpha.ttl");
        write("lib1/PROF/Alpha.rdf", prof("urn:example:Alpha", "urn:example:Alpha/1", null, constraints("Alpha.ttl")));
        shape("lib2/SHACL/Alpha.ttl");
        write("lib2/PROF/Alpha.rdf", prof("urn:example:Alpha", "urn:example:Alpha/2", null, constraints("Alpha.ttl")));

        assertThatThrownBy(() -> load("lib1", "lib2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urn:example:Alpha").hasMessageContaining("different SHACL files");
    }

    // ---- an artifact names a file of the library and nothing else --------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside/Secret.ttl",
            "..\\outside\\Secret.ttl",
            "SHACL/..\\../outside/Secret.ttl",
            "file:///C:/outside/Secret.ttl",
            "C:\\outside\\Secret.ttl",
            "/outside/Secret.ttl",
            "\\\\host.invalid\\share\\Secret.ttl",
            "//host.invalid/share/Secret.ttl",
            "\\/host.invalid/share/Secret.ttl"})
    void anArtifactNeverResolvesOutsideTheLibrary(String artifact) throws Exception {
        // The file exists beside the library; only its name could match, and it is not inside.
        shape("outside/Secret.ttl");
        // As a literal: the RDF parser refuses a backslash in an IRI, and the descriptor with it.
        write("lib/PROF/Evil.rdf", prof("urn:example:Evil", null, null,
                "    <prof:hasRole rdf:resource=\"http://www.w3.org/ns/dx/prof/role/constraints\"/>\n"
                        + "    <prof:hasArtifact>" + artifact + "</prof:hasArtifact>\n"
                        + "    <dcterms:conformsTo>" + SHACL + "</dcterms:conformsTo>\n"));

        ProfileCatalog catalog = load("lib");

        assertThat(catalog.find("urn:example:Evil").orElseThrow().shapeFiles()).isEmpty();
        assertThat(catalog.find("urn:example:Evil").orElseThrow().missingArtifacts()).containsExactly("Secret.ttl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "dir/..", "C:", "Shapes.ttl:stream", "NUL", "con.ttl", "a%2F..%2Fb.ttl", "x/"})
    void anArtifactWithoutASafeFileNameIsNotLookedUp(String artifact) {
        assertThat(ProfileCatalog.artifactName(artifact)).isNull();
    }

    @Test
    void aFileNameIsTakenFromBareNamesAddressesAndEitherSeparator() {
        assertThat(ProfileCatalog.artifactName("Plain.ttl")).isEqualTo("Plain.ttl");
        assertThat(ProfileCatalog.artifactName("http://www.w3.org/ns/dx/Plain.ttl")).isEqualTo("Plain.ttl");
        assertThat(ProfileCatalog.artifactName("https://x.example/a/b/Plain.ttl?v=1#top")).isEqualTo("Plain.ttl");
        assertThat(ProfileCatalog.artifactName("a\\b/c\\Plain.ttl")).isEqualTo("Plain.ttl");
    }

    @Test
    void underAPathPolicyALibraryOutsideTheRootsIsRefused() throws Exception {
        shape("lib/SHACL/Alpha.ttl");
        write("lib/PROF/Alpha.rdf", prof("urn:example:Alpha", null, null, constraints("Alpha.ttl")));
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        PathPolicy policy = PathPolicy.builder().root(allowed).build();

        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> load("lib")))
                .isInstanceOf(PathNotAllowedException.class);
        assertThat(PathPolicy.runWith(PathPolicy.builder().root(tempDir).build(), () -> load("lib")).keys())
                .containsExactly("urn:example:Alpha");
    }

    // ---- a real library, when one is given -------------------------------------------------

    /**
     * Prints what the catalog finds in a real profile library, for comparison with the reference
     * ({@code python buildScripts/prof_map.py <apl_dir>} of ENTSO-E ReliCapGrid). Run with
     * {@code -Dgroups=profile-library -Dcimpal.test.profileLibrary=<folder>}.
     */
    @Test
    @Tag("profile-library")
    void aRealLibraryCanBeRead() throws Exception {
        String folder = System.getProperty("cimpal.test.profileLibrary");
        assumeTrue(folder != null && Files.isDirectory(Path.of(folder)), "no -Dcimpal.test.profileLibrary");

        ProfileCatalog catalog = ProfileCatalog.load(List.of(Path.of(folder)));

        System.out.println(catalog.profiles().size() + " profiles, " + catalog.keys().size() + " match keys");
        for (ProfileShapes profile : catalog.profiles()) {
            System.out.println("\n" + profile.profFile().getFileName());
            profile.keys().stream().sorted().forEach(k -> System.out.println("  key: " + k));
            profile.shapeFiles().forEach(p -> System.out.println("  shacl: " + p.getFileName()));
        }
        System.out.println("\nGaps:");
        catalog.gaps().forEach(g -> System.out.println("  " + g));
        assertThat(catalog.profiles()).isNotEmpty();
    }
}
