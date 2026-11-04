/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.converters;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reference data → common data (GUI Convert Reference data to Common data, CLI {@code ref2common}). */
class CommonDataConverterTest {

    private static final String REF = """
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix nc: <http://entsoe.eu/ns/nc#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix dcat: <http://www.w3.org/ns/dcat#> .
            @prefix ex: <http://example.org/ref#> .
            ex:BV400 a cim:BaseVoltage ; cim:BaseVoltage.nominalVoltage 400.0 ;
                cim:IdentifiedObject.name "400 kV" ; skos:prefLabel "400" ; dcat:keyword "x" .
            ex:Area1 a nc:Area ; nc:Area.baseVoltage ex:BV400 .
            ex:Other a <http://example.org/other#Thing> ; cim:IdentifiedObject.name "skipped" .
            [] a cim:BaseVoltage .
            """;

    private static Model turtle(String text) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString(text, Lang.TURTLE).parse(m);
        return m;
    }

    @Test
    void cimAndNcSubjectsAreCopiedWithLocalIds() {
        Model out = CommonDataConverter.convert(turtle(REF));

        Resource bv = out.createResource(CommonDataConverter.ID_BASE + "_BV400");
        assertThat(out.contains(bv, RDF.type, out.createResource("http://iec.ch/TC57/CIM100#BaseVoltage"))).isTrue();
        assertThat(out.contains(bv, out.createProperty("http://iec.ch/TC57/CIM100#IdentifiedObject.name"), "400 kV")).isTrue();
        Resource area = out.createResource(CommonDataConverter.ID_BASE + "_Area1");
        assertThat(out.contains(area, out.createProperty("http://entsoe.eu/ns/nc#Area.baseVoltage"), bv)).isTrue();
    }

    @Test
    void skosDcatOtherClassesAndBlankNodesAreLeftOut() {
        Model out = CommonDataConverter.convert(turtle(REF));

        assertThat(out.listSubjects().toList()).hasSize(2);
        assertThat(out.listStatements(null, out.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                (org.apache.jena.rdf.model.RDFNode) null).toList()).isEmpty();
    }

    @Test
    void withoutACimOrNcPrefixNothingCanBeRecognised() {
        assertThatThrownBy(() -> CommonDataConverter.convert(turtle("<http://x#a> a <http://x#B> .")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prefix");
    }

    @Test
    void theXmlHasRelativeIdsAndNoTemporaryBase() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommonDataConverter.write(CommonDataConverter.convert(turtle(REF)), out);

        String xml = out.toString(StandardCharsets.UTF_8);
        assertThat(xml).contains("_BV400").doesNotContain("delete.eu");
        Model back = ModelFactory.createDefaultModel();
        RDFParser.fromString(xml, Lang.RDFXML).base("http://example.org/back").parse(back);
        assertThat(back.size()).isEqualTo(5);
    }
}
