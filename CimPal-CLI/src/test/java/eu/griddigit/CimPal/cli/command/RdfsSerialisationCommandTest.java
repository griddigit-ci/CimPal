/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code rdfs-serialisation}: the Turtle it writes, config, and exit code 2 for a profile without header. */
class RdfsSerialisationCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void writesTheRdfAboutMembersUnderTheProfileNamespace() throws Exception {
        Path rdfs = Files.writeString(tempDir.resolve("EQ.rdf"), """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                         xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:dcat="http://www.w3.org/ns/dcat#"
                         xmlns:cims="http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#" xmlns:cim="http://iec.ch/TC57/CIM100#">
                  <owl:Ontology rdf:about="http://example.org/EQ#Ontology">
                    <owl:versionIRI rdf:resource="http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"/>
                    <dcat:keyword>EQ</dcat:keyword>
                  </owl:Ontology>
                  <rdfs:Class rdf:about="http://iec.ch/TC57/CIM100#ACLineSegment"><cims:stereotype>Description</cims:stereotype></rdfs:Class>
                </rdf:RDF>
                """);
        Path out = tempDir.resolve("ser/info.ttl");

        CliRun.Result run = CliRun.run("rdfs-serialisation", "--rdfs-files", rdfs.toString(), "--output", out.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        Model info = RDFDataMgr.loadModel(out.toString());
        assertThat(info.getNsPrefixURI("eq")).isEqualTo("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0#");
        assertThat(info.contains(info.createResource("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0#RdfAbout"),
                org.apache.jena.vocabulary.OWL2.members, info.createResource("http://iec.ch/TC57/CIM100#ACLineSegment"))).isTrue();
    }

    @Test
    void configKeysResolveAgainstTheConfigFolder() throws Exception {
        Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);
        Path config = Files.writeString(tempDir.resolve("c.json"),
                "{\"rdfsFiles\": [\"mini-equipment-2020-cim100.rdf\"], \"output\": \"info.ttl\"}");

        CliRun.Result run = CliRun.run("rdfs-serialisation", "--config", config.toString());

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        assertThat(tempDir.resolve("info.ttl")).exists();
    }

    @Test
    void aProfileWithoutAHeaderIsBadInputAndWritesNothing() throws Exception {
        Path noHeader = Files.writeString(tempDir.resolve("noheader.rdf"), """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <rdfs:Class rdf:about="http://iec.ch/TC57/CIM100#Line"/>
                </rdf:RDF>
                """);
        Path out = tempDir.resolve("info.ttl");

        CliRun.Result run = CliRun.run("rdfs-serialisation", "--rdfs-files", noHeader.toString(), "--output", out.toString());

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("noheader.rdf", "no profile header");
        assertThat(out).doesNotExist();
    }
}
