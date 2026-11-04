/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.OWL2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RDFS → serialisation info (GUI Generate information from RDFS, CLI {@code rdfs-serialisation}). */
class RdfsSerialisationTest {

    private static final String PREFIXES = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix cims: <http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix eu: <http://iec.ch/TC57/CIM100-European#> .
            """;

    /** RDFS 2020 layout: an owl:Ontology header, a Description class and an enumeration. */
    private static final String EQ = PREFIXES + """
            <http://example.org/EQ#Ontology> a owl:Ontology ;
                owl:versionIRI <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0> ; dcat:keyword "EQ" .
            cim:ACLineSegment a rdfs:Class ; cims:stereotype "Description" .
            cim:PhaseCode a rdfs:Class ; cims:stereotype <http://iec.ch/TC57/NonStandard/UML#enumeration> .
            cim:PhaseCode.A a cim:PhaseCode .
            cim:PhaseCode.B a cim:PhaseCode .
            """;

    private static Model turtle(String text) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString(text, Lang.TURTLE).parse(m);
        return m;
    }

    private static List<Resource> members(Model out, String uri) {
        return out.listObjectsOfProperty(out.createResource(uri), OWL2.members).mapWith(n -> n.asResource()).toList();
    }

    @Test
    void descriptionClassesAndEnumValuesBecomeMembers() {
        Model out = RdfsSerialisation.build(List.of(Map.entry("EQ", turtle(EQ))));

        String ns = "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0#";
        assertThat(members(out, ns + "RdfAbout")).extracting(Resource::getLocalName).containsExactly("ACLineSegment");
        assertThat(members(out, ns + "RdfEnum")).extracting(Resource::getLocalName)
                .containsExactlyInAnyOrder("PhaseCode.A", "PhaseCode.B");
        assertThat(out.getNsPrefixURI("eq")).isEqualTo(ns);
        assertThat(out.getNsPrefixURI("cim")).isEqualTo("http://iec.ch/TC57/CIM100#");
        assertThat(out.getNsPrefixURI("eu")).isEqualTo("http://iec.ch/TC57/CIM100-European#");
    }

    @Test
    void aCgmes24ProfileIsFoundByItsEntsoeUriLabel() {
        String rdfs = PREFIXES + """
                cim:EquipmentVersion.entsoeURIcore rdfs:label "entsoeURIcore"@en ; rdfs:domain cim:EquipmentVersion ;
                    cims:isFixed "http://entsoe.eu/CIM/EquipmentCore/3/1" .
                cim:EquipmentVersion.shortName rdfs:label "shortName"@en ; rdfs:domain cim:EquipmentVersion ;
                    cims:isFixed "EQ" .
                cim:Line a rdfs:Class ; cims:stereotype "Description" .
                """;

        Model out = RdfsSerialisation.build(List.of(Map.entry("EQ24", turtle(rdfs))));

        assertThat(out.getNsPrefixURI("eq")).isEqualTo("http://entsoe.eu/CIM/EquipmentCore/3/1#");
        assertThat(members(out, "http://entsoe.eu/CIM/EquipmentCore/3/1#RdfAbout")).hasSize(1);
    }

    /** Regression: a profile without a header got the namespace "null#" (or the previous profile's). */
    @Test
    void aProfileWithoutAHeaderIsAnError() {
        Model noHeader = turtle(PREFIXES + "cim:Line a rdfs:Class ; cims:stereotype \"Description\" .");

        assertThatThrownBy(() -> RdfsSerialisation.build(List.of(Map.entry("EQ", turtle(EQ)), Map.entry("broken.rdf", noHeader))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("broken.rdf");
    }

    /** Security review: the profile IRI is literal text from the RDFS; one with '>' could inject Turtle. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "http://x/p> <http://a> <http://b> . <http://x/q", "has space", "relative/path", "http://x/{y}"})
    void anUnsafeProfileIriIsAnError(String iri) {
        String rdfs = EQ.replace("owl:versionIRI <http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0>",
                "owl:versionIRI \"" + iri + "\"");

        assertThatThrownBy(() -> RdfsSerialisation.build(List.of(Map.entry("EQ.rdf", turtle(rdfs)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EQ.rdf");
    }

    /** Security review: a keyword that is not a prefix name threw an unchecked IllegalPrefixException. */
    @Test
    void aKeywordThatCantBeAPrefixIsAnError() {
        String rdfs = EQ.replace("dcat:keyword \"EQ\"", "dcat:keyword \"1 bad\"");

        assertThatThrownBy(() -> RdfsSerialisation.build(List.of(Map.entry("EQ.rdf", turtle(rdfs)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keyword");
    }

    @Test
    void severalProfilesEachGetTheirOwnNamespace() {
        String ssh = EQ.replace("CoreEquipment-EU", "SteadyStateHypothesis-EU").replace("\"EQ\"", "\"SSH\"")
                .replace("EQ#Ontology", "SSH#Ontology");

        Model out = RdfsSerialisation.build(List.of(Map.entry("EQ", turtle(EQ)), Map.entry("SSH", turtle(ssh))));

        assertThat(out.getNsPrefixURI("eq")).contains("CoreEquipment-EU");
        assertThat(out.getNsPrefixURI("ssh")).contains("SteadyStateHypothesis-EU");
    }
}
