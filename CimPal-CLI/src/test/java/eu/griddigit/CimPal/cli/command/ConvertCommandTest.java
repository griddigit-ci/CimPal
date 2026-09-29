/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConvertCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void rdfXmlToTurtlePreservesTheGraph() throws Exception {
        TestModels.CgmesModelBuilder eq = TestModels.eq()
                .resource("Substation", "_sub1").literal("IdentifiedObject.name", "North")
                .resource("VoltageLevel", "_vl1").reference("VoltageLevel.Substation", "_sub1");
        Path input = Files.writeString(tempDir.resolve("eq.xml"), eq.toRdfXml());
        Path output = tempDir.resolve("eq.ttl");

        int exitCode = new CommandLine(new CimPalCli()).execute("convert",
                "--input", input.toString(), "--output", output.toString(),
                "--xml-base", TestModels.XML_BASE);

        assertThat(exitCode).isEqualTo(ExitCode.OK);
        Model converted = ModelFactory.createDefaultModel();
        RDFDataMgr.read(converted, output.toString(), Lang.TURTLE);
        Snapshots.assertIsomorphic(eq.toModel(), converted);
    }
}
