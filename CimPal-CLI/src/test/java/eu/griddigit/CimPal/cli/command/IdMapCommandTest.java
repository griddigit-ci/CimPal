/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code id-map}: options, config, exit codes and the workbook it writes. */
class IdMapCommandTest {

    @TempDir
    Path dir;

    @Test
    void mapsTwoFilesOntoOneAndPrintsAJsonSummary() throws Exception {
        Path north = Files.writeString(dir.resolve("north.xml"), substation("a", "North", "South"));
        Path south = Files.writeString(dir.resolve("south.xml"), substation("a", "South", "North"));
        Path merged = Files.writeString(dir.resolve("merged.xml"),
                merge(substation("b", "North", "South"), substation("b", "South", "North")));
        Path out = dir.resolve("map.xlsx");

        Captured run = run("id-map", "--model1", north + "," + south, "--model2", merged.toString(),
                "--output", out.toString(), "--format", "json");

        assertThat(run.exit).as(run.err).isEqualTo(ExitCode.OK);
        JsonNode json = new ObjectMapper().readTree(run.out);
        assertThat(json.path("schema").asString()).isEqualTo("cimpal-id-map-summary/3");
        JsonNode model1 = json.path("model1");
        assertThat(model1.path("objects").asInt()).isPositive();
        assertThat(model1.path("certain").asInt()).isEqualTo(model1.path("objects").asInt());
        assertThat(model1.path("unmatched").asInt()).isZero();
        try (InputStream in = Files.newInputStream(out); Workbook wb = WorkbookFactory.create(in)) {
            Sheet matched = wb.getSheet("Matched");
            Row header = matched.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Model1_ID");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Element_type");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Model2_ID");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Model2_2nd_name");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Confidence");
            boolean lineMapped = false;
            for (int r = 1; r <= matched.getLastRowNum(); r++) {
                Row row = matched.getRow(r);
                if (row.getCell(0).getStringCellValue().equals("a_line")) {
                    assertThat(row.getCell(2).getStringCellValue()).isEqualTo("b_line");
                    lineMapped = true;
                }
            }
            assertThat(lineMapped).isTrue();
        }
    }

    @Test
    void configPathsAreRelativeToTheConfigFile() throws Exception {
        Files.writeString(dir.resolve("north.xml"), substation("a", "North", "South"));
        Files.writeString(dir.resolve("south.xml"), substation("a", "South", "North"));
        Files.writeString(dir.resolve("merged.xml"),
                merge(substation("b", "North", "South"), substation("b", "South", "North")));
        Path config = Files.writeString(dir.resolve("id-map.json"), """
                {
                  "_comment": "documentation keys are ignored",
                  "model1Files": ["north.xml", "south.xml"],
                  "model2Files": ["merged.xml"],
                  "output": "out/map.xlsx",
                  "includeTerminals": false
                }
                """);

        Captured run = run("id-map", "--config", config.toString());

        assertThat(run.exit).as(run.err).isEqualTo(ExitCode.OK);
        assertThat(dir.resolve("out/map.xlsx")).exists();
        assertThat(run.out).contains("certain 16 (100.0%)").contains("not matched 0 (0.0%)");
    }

    @Test
    void unmatchedObjectsGiveExitCode1() throws Exception {
        Path model1 = Files.writeString(dir.resolve("model1.xml"), substation("a", "North", "South"));
        Path other = Files.writeString(dir.resolve("other.xml"), substation("b", "Elsewhere", "Nowhere"));

        Captured run = run("id-map", "--model1", model1.toString(), "--model2", other.toString(),
                "--output", dir.resolve("map.xlsx").toString(), "--no-same-ids");

        assertThat(run.exit).as(run.err).isEqualTo(ExitCode.VIOLATIONS);
        assertThat(dir.resolve("map.xlsx")).exists();
    }

    @Test
    void missingInputIsBadInput() throws Exception {
        Captured run = run("id-map", "--model1", dir.resolve("missing.xml").toString(),
                "--model2", dir.resolve("missing2.xml").toString(), "--output", dir.resolve("map.xlsx").toString());

        assertThat(run.exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.err).contains("file not found");
        assertThat(dir.resolve("map.xlsx")).doesNotExist();
    }

    @Test
    void dryRunInJsonModeKeepsStdoutFreeOfText() {
        Captured run = run("id-map", "--model1", "a.xml", "--model2", "b.xml", "--format", "json", "--dry-run");

        assertThat(run.exit).isEqualTo(ExitCode.OK);
        assertThat(run.out).isEmpty();
        assertThat(run.err).contains("Dry Run");
    }

    @Test
    void configWithAnInvalidPathIsBadInput() throws Exception {
        Path config = Files.writeString(dir.resolve("bad.json"), "{\"model1Files\": [\"a\\u0000b.xml\"]}");

        Captured run = run("id-map", "--config", config.toString());

        assertThat(run.exit).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run.err).contains("Invalid path in config");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private record Captured(int exit, String out, String err) {
    }

    private static Captured run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int exit = CimPalCli.inProcess().execute(args);
            return new Captured(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    /**
     * One substation (busbar, line bay with a breaker, a load) and, for the first of the pair, the
     * line to the other one. IDs carry {@code prefix}, so two prefixes give the same grid twice.
     */
    private static String substation(String prefix, String name, String other) {
        String s = prefix + "_" + name;
        String line = name.compareTo(other) < 0 ? """
                  <cim:Line rdf:ID="_%1$s_lineC"><cim:IdentifiedObject.name>%2$s-%3$s</cim:IdentifiedObject.name></cim:Line>
                  <cim:ACLineSegment rdf:ID="_%1$s_line">
                    <cim:IdentifiedObject.name>%2$s-%3$s</cim:IdentifiedObject.name>
                    <cim:Equipment.EquipmentContainer rdf:resource="#_%1$s_lineC"/>
                    <cim:ACLineSegment.r>1.5</cim:ACLineSegment.r><cim:ACLineSegment.x>15</cim:ACLineSegment.x>
                  </cim:ACLineSegment>
                  <cim:Terminal rdf:ID="_%1$s_line_t1"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_line"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_%2$s_end"/></cim:Terminal>
                  <cim:Terminal rdf:ID="_%1$s_line_t2"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_line"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_%3$s_end"/></cim:Terminal>
                """.formatted(prefix, name, other) : "";
        return """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:cim="http://iec.ch/TC57/CIM100#">
                  <cim:Substation rdf:ID="_%1$s"><cim:IdentifiedObject.name>%2$s</cim:IdentifiedObject.name></cim:Substation>
                  <cim:VoltageLevel rdf:ID="_%1$s_vl"><cim:IdentifiedObject.name>%2$s 220</cim:IdentifiedObject.name>
                    <cim:VoltageLevel.Substation rdf:resource="#_%1$s"/></cim:VoltageLevel>
                  <cim:ConnectivityNode rdf:ID="_%1$s_bb"><cim:ConnectivityNode.ConnectivityNodeContainer rdf:resource="#_%1$s_vl"/></cim:ConnectivityNode>
                  <cim:ConnectivityNode rdf:ID="_%1$s_end"><cim:ConnectivityNode.ConnectivityNodeContainer rdf:resource="#_%1$s_vl"/></cim:ConnectivityNode>
                  <cim:BusbarSection rdf:ID="_%1$s_bbs"><cim:IdentifiedObject.name>%2$s BB</cim:IdentifiedObject.name>
                    <cim:Equipment.EquipmentContainer rdf:resource="#_%1$s_vl"/></cim:BusbarSection>
                  <cim:Terminal rdf:ID="_%1$s_bbs_t"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_bbs"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_bb"/></cim:Terminal>
                  <cim:Breaker rdf:ID="_%1$s_cb"><cim:IdentifiedObject.name>%2$s CB</cim:IdentifiedObject.name>
                    <cim:Equipment.EquipmentContainer rdf:resource="#_%1$s_vl"/></cim:Breaker>
                  <cim:Terminal rdf:ID="_%1$s_cb_t1"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_cb"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_bb"/></cim:Terminal>
                  <cim:Terminal rdf:ID="_%1$s_cb_t2"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_cb"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_end"/></cim:Terminal>
                  <cim:ConformLoad rdf:ID="_%1$s_load"><cim:IdentifiedObject.name>%2$s load</cim:IdentifiedObject.name>
                    <cim:Equipment.EquipmentContainer rdf:resource="#_%1$s_vl"/></cim:ConformLoad>
                  <cim:Terminal rdf:ID="_%1$s_load_t"><cim:Terminal.ConductingEquipment rdf:resource="#_%1$s_load"/>
                    <cim:Terminal.ConnectivityNode rdf:resource="#_%1$s_bb"/></cim:Terminal>
                %3$s</rdf:RDF>
                """.formatted(s, name, line);
    }

    private static String merge(String a, String b) {
        String bodyB = b.substring(b.indexOf('>', b.indexOf("<rdf:RDF")) + 1, b.lastIndexOf("</rdf:RDF>"));
        return a.substring(0, a.lastIndexOf("</rdf:RDF>")) + bodyB + "</rdf:RDF>\n";
    }
}
