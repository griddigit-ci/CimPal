/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny synthetic CIM/CGMES models and SHACL shapes built in code, so tests need no external data.
 *
 * <pre>{@code
 * String eq = TestModels.eq()
 *         .resource("ACLineSegment", "_line1").literal("IdentifiedObject.name", "Line 1")
 *         .toRdfXml();
 * String shapes = TestModels.minCountShape("cim:ACLineSegment", "cim:IdentifiedObject.name");
 * }</pre>
 */
public final class TestModels {

    public static final String CIM_NS = "http://iec.ch/TC57/CIM100#";
    public static final String MD_NS = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String XML_BASE = "http://example.com/data";

    public static final String EQ_PROFILE = "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0";
    public static final String SSH_PROFILE = "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0";

    /** SHACL shapes requiring {@code ex:size} on every {@code ex:Thing}. */
    public static final String THING_SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix ex: <urn:test:> .
            ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
                sh:property [ sh:path ex:size ; sh:minCount 1 ] .
            """;

    /** One {@code ex:Thing} without the {@code ex:size} that {@link #THING_SHAPES} requires, under a model header. */
    public static final String VIOLATING_THING_MODEL = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:md="http://iec.ch/TC57/61970-552/ModelDescription/1#"
                     xmlns:ex="urn:test:">
              <md:FullModel rdf:about="urn:uuid:header">
                <md:Model.scenarioTime>2026-01-01T00:00:00Z</md:Model.scenarioTime>
              </md:FullModel>
              <ex:Thing rdf:about="#_1"/>
            </rdf:RDF>
            """;

    private TestModels() {
    }

    /** An Equipment (EQ) model: resources are declared with {@code rdf:ID}. */
    public static CgmesModelBuilder eq() {
        return new CgmesModelBuilder(EQ_PROFILE, false);
    }

    /** A Steady State Hypothesis (SSH) model: resources reference EQ objects with {@code rdf:about}. */
    public static CgmesModelBuilder ssh() {
        return new CgmesModelBuilder(SSH_PROFILE, true);
    }

    /** SHACL Turtle requiring at least one {@code path} value on every instance of {@code targetClass} (CURIEs, {@code cim:} prefix bound). */
    public static String minCountShape(String targetClass, String path) {
        return shapePrefixes() + """
                ex:Shape a sh:NodeShape ; sh:targetClass %s ;
                    sh:property [ sh:path %s ; sh:minCount 1 ; sh:severity sh:Violation ;
                                  sh:message "Missing required value" ] .
                """.formatted(targetClass, path);
    }

    /** SHACL Turtle requiring every {@code path} value on {@code targetClass} to be an {@code xsd:} datatype. */
    public static String datatypeShape(String targetClass, String path, String xsdType) {
        return shapePrefixes() + """
                ex:Shape a sh:NodeShape ; sh:targetClass %s ;
                    sh:property [ sh:path %s ; sh:datatype xsd:%s ] .
                """.formatted(targetClass, path, xsdType);
    }

    /** Parses RDF/XML text (with {@link #XML_BASE}) into a Jena model. */
    public static Model parseRdfXml(String rdfXml) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(rdfXml.getBytes(StandardCharsets.UTF_8)), XML_BASE, Lang.RDFXML);
        return model;
    }

    private static String shapePrefixes() {
        return """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix cim: <%s> .
                @prefix ex: <urn:test:shapes:> .
                """.formatted(CIM_NS);
    }

    /** Fluent builder for a small CGMES-style RDF/XML instance file. */
    public static final class CgmesModelBuilder {
        private final String profile;
        private final boolean aboutReferences;
        private String modelId = "urn:uuid:00000000-0000-0000-0000-000000000001";
        private String scenarioTime = "2026-01-01T00:00:00Z";
        private boolean writeXmlBase = true;
        private final List<ResourceSpec> resources = new ArrayList<>();

        private CgmesModelBuilder(String profile, boolean aboutReferences) {
            this.profile = profile;
            this.aboutReferences = aboutReferences;
        }

        /**
         * Leaves {@code xml:base} out of {@link #toRdfXml()}, as real CGMES files do, so its
         * {@code rdf:ID} and {@code #} references resolve against whatever base the reader uses.
         * {@link #toModel()} still reads them against {@link #XML_BASE}.
         */
        public CgmesModelBuilder withoutXmlBase() {
            this.writeXmlBase = false;
            return this;
        }

        public CgmesModelBuilder modelId(String id) {
            this.modelId = id;
            return this;
        }

        public CgmesModelBuilder scenarioTime(String isoInstant) {
            this.scenarioTime = isoInstant;
            return this;
        }

        /** Starts a resource of CIM class {@code cimClass} (local name, e.g. {@code ACLineSegment}) with id {@code _x}. */
        public CgmesModelBuilder resource(String cimClass, String id) {
            resources.add(new ResourceSpec(cimClass, id));
            return this;
        }

        /** Adds a literal property (local name, e.g. {@code IdentifiedObject.name}) to the last resource. */
        public CgmesModelBuilder literal(String property, String value) {
            current().literals.put(property, value);
            return this;
        }

        /** Adds a reference property to the last resource, pointing at resource id {@code targetId}. */
        public CgmesModelBuilder reference(String property, String targetId) {
            current().references.put(property, targetId);
            return this;
        }

        public String toRdfXml() {
            StringBuilder xml = new StringBuilder()
                    .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    .append("<rdf:RDF xmlns:rdf=\"").append(RDF_NS).append("\" xmlns:cim=\"").append(CIM_NS)
                    .append("\" xmlns:md=\"").append(MD_NS).append('"')
                    .append(writeXmlBase ? " xml:base=\"" + XML_BASE + "\"" : "").append(">\n")
                    .append("  <md:FullModel rdf:about=\"").append(escape(modelId)).append("\">\n")
                    .append("    <md:Model.scenarioTime>").append(escape(scenarioTime)).append("</md:Model.scenarioTime>\n")
                    .append("    <md:Model.profile>").append(escape(profile)).append("</md:Model.profile>\n")
                    .append("  </md:FullModel>\n");
            for (ResourceSpec r : resources) {
                String idAttribute = aboutReferences ? "rdf:about=\"#" + escape(r.id) + "\"" : "rdf:ID=\"" + escape(r.id) + "\"";
                xml.append("  <cim:").append(r.cimClass).append(' ').append(idAttribute).append(">\n");
                r.literals.forEach((p, v) -> xml.append("    <cim:").append(p).append('>').append(escape(v))
                        .append("</cim:").append(p).append(">\n"));
                r.references.forEach((p, target) -> xml.append("    <cim:").append(p).append(" rdf:resource=\"#")
                        .append(escape(target)).append("\"/>\n"));
                xml.append("  </cim:").append(r.cimClass).append(">\n");
            }
            return xml.append("</rdf:RDF>\n").toString();
        }

        public Model toModel() {
            return parseRdfXml(toRdfXml());
        }

        private ResourceSpec current() {
            if (resources.isEmpty()) {
                throw new IllegalStateException("Call resource(...) before adding properties");
            }
            return resources.getLast();
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }

    private static final class ResourceSpec {
        final String cimClass;
        final String id;
        final Map<String, String> literals = new LinkedHashMap<>();
        final Map<String, String> references = new LinkedHashMap<>();

        ResourceSpec(String cimClass, String id) {
            this.cimClass = cimClass;
            this.id = id;
        }
    }
}
