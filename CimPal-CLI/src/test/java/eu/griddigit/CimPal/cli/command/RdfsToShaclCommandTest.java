/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.topbraid.shacl.vocabulary.SH;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RdfsToShaclCommandTest {

    private static final String CIM100 = "http://iec.ch/TC57/CIM100#";

    @TempDir
    Path tempDir;

    private Model generateWithDefaults() {
        Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", tempDir);
        Path out = tempDir.resolve("out");

        int exitCode = new CommandLine(new CimPalCli()).execute("rdfs2shacl",
                "--rdfs-files", rdfs.toString(), "--output-dir", out.toString());

        assertThat(exitCode).isEqualTo(ExitCode.OK);
        return RDFDataMgr.loadModel(out.resolve("mini-equipment-2020-cim100.ttl").toString());
    }

    @Test
    void defaultsGenerateShapesForTheConcreteClasses() {
        Model shapes = generateWithDefaults();

        assertThat(shapes.listSubjectsWithProperty(SH.targetClass).toList())
                .extracting(Resource::getLocalName)
                .contains("ACLineSegment", "Terminal");
    }

    /**
     * {@code --io-uri} defaults to the mRID property URI, but the converter uses it as the
     * namespace of the shared IdentifiedObject shapes (the GUI passes a constraints namespace).
     * The shapes then land in the CIM namespace, e.g.
     * {@code cim:IdentifiedObject.mRIDIdentifiedObject.mRID-datatype}.
     */
    @Disabled("Suspected bug (TEST-3): the rdfs2shacl --io-uri default puts the IdentifiedObject shapes in the CIM namespace")
    @Test
    void defaultsKeepGeneratedShapesOutOfTheCimNamespace() {
        Model shapes = generateWithDefaults();

        List<String> shapesInCim = shapes.listSubjectsWithProperty(RDF.type, SH.PropertyShape).toList().stream()
                .map(Resource::getURI)
                .filter(uri -> uri != null && uri.startsWith(CIM100))
                .toList();
        assertThat(shapesInCim).isEmpty();
    }
}
