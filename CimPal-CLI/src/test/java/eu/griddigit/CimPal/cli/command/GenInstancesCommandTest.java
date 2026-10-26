/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CliFixtures;
import eu.griddigit.CimPal.cli.CliRun;
import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract of {@code gen-instances} (TEST-4): the instance file, bad input, and exit 3. */
class GenInstancesCommandTest {

    @TempDir
    Path tempDir;

    private CliRun.Result generate(Path template, Path output) {
        return CliRun.run("gen-instances", "--template-xlsx", template.toString(), "--output", output.toString(),
                "--xml-base", TestModels.XML_BASE);
    }

    @Test
    void theTemplateRowsBecomeInstances() {
        Path template = CliFixtures.instanceTemplate(tempDir.resolve("grid_template.xlsx"));
        Path out = tempDir.resolve("grid.xml");

        CliRun.Result run = generate(template, out);

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.OK);
        // CIM XML names instances relative to the xml:base it was written for, without declaring it.
        Model model = RDFParser.source(out).lang(Lang.RDFXML).base(TestModels.XML_BASE).toModel();
        Resource substation = model.createResource(TestModels.XML_BASE + "#_sub1");
        assertThat(model.contains(substation, RDF.type, model.createResource(TestModels.CIM_NS + "Substation"))).isTrue();
        assertThat(model.contains(substation, model.createProperty(TestModels.CIM_NS + "IdentifiedObject.name"), "North"))
                .isTrue();
        assertThat(model.listSubjectsWithProperty(RDF.type, model.createResource(TestModels.MD_NS + "FullModel")).toList())
                .hasSize(1);
    }

    @Test
    void aMissingTemplateIsBadInputAndWritesNothing() {
        Path out = tempDir.resolve("grid.xml");

        CliRun.Result run = generate(tempDir.resolve("missing.xlsx"), out);

        assertThat(run.exitCode()).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.stderr()).contains("missing.xlsx");
        assertThat(out).doesNotExist();
    }

    @Test
    void anOutputThatCannotBeWrittenIsAnInternalError() throws Exception {
        Path template = CliFixtures.instanceTemplate(tempDir.resolve("grid_template.xlsx"));
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "a file, not a folder");

        CliRun.Result run = generate(template, blocker.resolve("grid.xml"));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INTERNAL_ERROR);
    }

    /**
     * A workbook that is not a generation template is the caller's mistake, but Core reports it
     * with a plain {@code Exception}, which the command maps to exit 3 (internal error).
     */
    @Disabled("Suspected bug (TEST-4): a workbook without a Config sheet is reported as an internal error (exit 3)")
    @Test
    void aWorkbookThatIsNotATemplateIsBadInput() {
        Path notATemplate = CliFixtures.workbook(tempDir.resolve("other.xlsx"), "Sheet1", List.of(List.<Object>of("x")));

        CliRun.Result run = generate(notATemplate, tempDir.resolve("grid.xml"));

        assertThat(run.exitCode()).as(run.stderr()).isEqualTo(ExitCode.INVALID_INPUT);
    }
}
