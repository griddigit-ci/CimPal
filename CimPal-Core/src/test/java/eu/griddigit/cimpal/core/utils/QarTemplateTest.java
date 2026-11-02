/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** QAR template (GUI Create xlsx template from QAR, CLI {@code qar-template}). */
class QarTemplateTest {

    private static Model header(String id, String mas, String profile) {
        Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix md: <http://iec.ch/TC57/61970-552/ModelDescription/1#> .
                <%s> a md:FullModel ; md:Model.modelingAuthoritySet "%s" ; md:Model.profile "%s" .
                """.formatted(id, mas, profile), Lang.TURTLE).parse(m);
        return m;
    }

    @Test
    void oneRowPerFileGroupedByMas() {
        Map<String, Model> models = new LinkedHashMap<>();
        models.put("REN_SSH.xml", header("urn:uuid:3", "http://www.ren.pt/OperationalPlanning", "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0"));
        models.put("REE_EQ.xml", header("urn:uuid:1", "http://www.ree.es/OperationalPlanning", "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"));
        models.put("REN_EQ.xml", header("urn:uuid:2", "http://www.ren.pt/OperationalPlanning", "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"));

        List<List<Object>> rows = QarTemplate.rows(models);

        assertThat(rows).extracting(r -> r.getFirst()).containsExactly("REE_EQ.xml", "REN_EQ.xml", "REN_SSH.xml");
        assertThat(rows.get(1)).containsExactly("REN_EQ.xml", ModelFactory.getProfileKeyword(models.get("REN_EQ.xml")),
                "http://www.ren.pt/OperationalPlanning", "urn:uuid:2");
    }

    @Test
    void aFileWithoutHeaderGetsEmptyCells() {
        Map<String, Model> models = Map.of("x.xml", org.apache.jena.rdf.model.ModelFactory.createDefaultModel());

        assertThat(QarTemplate.rows(models).getFirst()).containsSubsequence("x.xml").endsWith("", "");
    }
}
