/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestModelsTest {

    @Test
    void eqBuilderProducesHeaderTypedResourcesLiteralsAndReferences() {
        Model eq = TestModels.eq()
                .resource("Substation", "_sub1").literal("IdentifiedObject.name", "Sub & 1")
                .resource("VoltageLevel", "_vl1").reference("VoltageLevel.Substation", "_sub1")
                .toModel();

        Resource sub = eq.getResource(TestModels.XML_BASE + "#_sub1");
        Resource vl = eq.getResource(TestModels.XML_BASE + "#_vl1");
        Property name = eq.createProperty(TestModels.CIM_NS, "IdentifiedObject.name");
        Property container = eq.createProperty(TestModels.CIM_NS, "VoltageLevel.Substation");

        assertThat(sub.hasProperty(RDF.type, eq.createResource(TestModels.CIM_NS + "Substation"))).isTrue();
        assertThat(sub.getProperty(name).getString()).isEqualTo("Sub & 1");
        assertThat(vl.getPropertyResourceValue(container)).isEqualTo(sub);
        assertThat(eq.contains(null, eq.createProperty(TestModels.MD_NS, "Model.profile"), TestModels.EQ_PROFILE)).isTrue();
    }

    @Test
    void sshBuilderReferencesEquipmentByAbout() {
        String ssh = TestModels.ssh().resource("ACLineSegment", "_line1").toRdfXml();
        assertThat(ssh).contains("rdf:about=\"#_line1\"").contains(TestModels.SSH_PROFILE);
    }

    @Test
    void withoutXmlBaseLeavesTheBaseToTheReader() {
        TestModels.CgmesModelBuilder eq = TestModels.eq().withoutXmlBase().resource("Substation", "_sub1");

        assertThat(eq.toRdfXml()).doesNotContain("xml:base").contains("rdf:ID=\"_sub1\"");
        assertThat(eq.toModel().containsResource(eq.toModel().getResource(TestModels.XML_BASE + "#_sub1"))).isTrue();
    }

    @Test
    void shapesAndThingFixturesParse() {
        assertThat(Snapshots.turtle(TestModels.minCountShape("cim:ACLineSegment", "cim:IdentifiedObject.name")).size()).isPositive();
        assertThat(Snapshots.turtle(TestModels.datatypeShape("cim:ACLineSegment", "cim:ACLineSegment.r", "float")).size()).isPositive();
        assertThat(Snapshots.turtle(TestModels.THING_SHAPES).size()).isPositive();
        assertThat(TestModels.parseRdfXml(TestModels.VIOLATING_THING_MODEL).size()).isPositive();
    }
}
