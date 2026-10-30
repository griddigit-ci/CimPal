/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.generators;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relicapgrid#401: an absolute build-host path in a source header must not reach the manifest
 * (item 1), and the invalid {@code dcat:replaces} is written as {@code dcterms:replaces} (item 5).
 */
class ManifestGeneratorHeaderTest {

    private static final String PREFIXES = """
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            """;

    private static Model parse(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new StringReader(PREFIXES + ttl), null, Lang.TURTLE);
        return m;
    }

    private static Model generate(String ttl) {
        return ManifestGenerator.generateManifest(Map.of("EQ.xml", parse(ttl)),
                List.of(new File("grid/EQ.xml")), "Instance/Grid");
    }

    @Test
    void anAbsoluteBuildPathInTheHeaderIsReplacedByTheRelativeIri() {
        Model manifest = generate("""
                <urn:uuid:11111111-1111-4111-8111-111111111111> a dcat:Dataset ;
                    dcat:distribution <urn:uuid:22222222-2222-4222-8222-222222222222> .
                <urn:uuid:22222222-2222-4222-8222-222222222222>
                    dcat:accessURL <file:///home/runner/work/repo/repo/Instance/Grid/EQ.xml> .
                """);
        var accessUrl = ResourceFactory.createProperty("http://www.w3.org/ns/dcat#", "accessURL");
        List<String> urls = manifest.listObjectsOfProperty(accessUrl).toList().stream().map(RDFNode::toString).toList();
        assertThat(urls).containsExactly("file:Instance/Grid/EQ.xml");
    }

    @Test
    void aRelativeHeaderLocationIsKept() {
        // Built in code: the Turtle parser would resolve file:Other/EQ.xml against the working folder.
        Model source = parse("""
                <urn:uuid:11111111-1111-4111-8111-111111111111> a dcat:Dataset ;
                    dcat:distribution <urn:uuid:22222222-2222-4222-8222-222222222222> .
                """);
        var accessUrl = ResourceFactory.createProperty("http://www.w3.org/ns/dcat#", "accessURL");
        source.add(ResourceFactory.createResource("urn:uuid:22222222-2222-4222-8222-222222222222"), accessUrl,
                ResourceFactory.createResource("file:Other/EQ.xml"));
        Model manifest = ManifestGenerator.generateManifest(Map.of("EQ.xml", source),
                List.of(new File("grid/EQ.xml")), "Instance/Grid");
        assertThat(manifest.listObjectsOfProperty(accessUrl).toList()).extracting(RDFNode::toString)
                .containsExactly("file:Other/EQ.xml");
    }

    @Test
    void absoluteLocationsAreRecognisedOnEveryPlatform() {
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createResource("file:///home/runner/x.xml"))).isTrue();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createResource("file:/home/runner/x.xml"))).isTrue();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createResource("file:///C:/build/x.xml"))).isTrue();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createPlainLiteral("C:\\build\\x.xml"))).isTrue();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createPlainLiteral("/srv/x.xml"))).isTrue();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createResource("file:Instance/x.xml"))).isFalse();
        assertThat(ManifestGenerator.isAbsoluteLocation(ResourceFactory.createResource("http://example.org/x.xml"))).isFalse();
    }

    @Test
    void dcatReplacesIsWrittenAsDctermsReplaces() {
        Model manifest = generate("""
                <urn:uuid:11111111-1111-4111-8111-111111111111> a dcat:Dataset ;
                    dcat:replaces <urn:uuid:33333333-3333-4333-8333-333333333333> .
                """);
        var dctReplaces = ResourceFactory.createProperty("http://purl.org/dc/terms/", "replaces");
        var dcatReplaces = ResourceFactory.createProperty("http://www.w3.org/ns/dcat#", "replaces");
        assertThat(manifest.listObjectsOfProperty(dctReplaces).toList()).extracting(RDFNode::toString)
                .containsExactly("urn:uuid:33333333-3333-4333-8333-333333333333");
        assertThat(manifest.contains(null, dcatReplaces)).isFalse();
    }

    @Test
    void dctermsReplacesIsCopiedAsIs() {
        Model manifest = generate("""
                <urn:uuid:11111111-1111-4111-8111-111111111111> a dcat:Dataset ;
                    dcterms:replaces <urn:uuid:44444444-4444-4444-8444-444444444444> .
                """);
        var dctReplaces = ResourceFactory.createProperty("http://purl.org/dc/terms/", "replaces");
        assertThat(manifest.listObjectsOfProperty(dctReplaces).toList()).extracting(RDFNode::toString)
                .containsExactly("urn:uuid:44444444-4444-4444-8444-444444444444");
    }
}
