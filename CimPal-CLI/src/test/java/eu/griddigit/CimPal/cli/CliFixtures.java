/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One small, synthetic, successful run per CLI command for the contract tests (TEST-4): the
 * inputs are written under a folder, and the same run is given both as flags (absolute paths)
 * and as a config file (paths relative to the config's folder). Used in-process by
 * {@code ConfigFileEquivalenceTest} and {@code CommandContractTest}, and as a real process by
 * {@code PackagedJarIT}.
 */
public final class CliFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CliFixtures() {
    }

    /** The commands with a {@code --config} file; {@code run}, {@code serve} and {@code mcp} have none. */
    public static final List<String> CONFIG_COMMANDS = List.of("validate", "sparql", "compare", "compare-instances",
            "convert", "rdfs2shacl", "excel2shacl", "organize", "gen-instances", "manifest");

    /** The commands with {@code --format json}. */
    public static final List<String> JSON_COMMANDS = List.of("validate", "sparql", "compare", "compare-instances", "run");

    /**
     * One run of {@code command}.
     *
     * @param flags    the arguments after the command name, with absolute paths
     * @param config   the same settings as config keys, with paths relative to the folder
     * @param exitCode the exit code the run ends with
     * @param outputs  files (relative to the folder) the run writes and that two equal runs write equally
     */
    public record Scenario(String command, List<String> flags, ObjectNode config, int exitCode, List<String> outputs) {

        /** {@code command} followed by the flags. */
        public String[] args() {
            List<String> args = new ArrayList<>();
            args.add(command);
            args.addAll(flags);
            return args.toArray(String[]::new);
        }

        /** Writes the config into {@code dir} and returns the arguments that use it. */
        public String[] configArgs(Path dir) {
            Path file = dir.resolve(command + ".json");
            try {
                Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new String[] {command, "--config", file.toString()};
        }
    }

    // ---- inputs ---------------------------------------------------------------------------------

    static final String SPARQL_QUERY = "SELECT ?s ?o WHERE { ?s <urn:x:p> ?o } ORDER BY ?o";

    private static final String SHAPE_PREFIX = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n";

    /** A second constraint set for organize: two property shapes. */
    static final String ORGANIZE_SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix ex: <http://example.com/constraints#> .
            ex:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ; sh:property ex:Line.r-range , ex:Line.x-range .
            ex:Line.r-range a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:name "Line.r-range" ; sh:minInclusive 0 .
            ex:Line.x-range a sh:PropertyShape ; sh:path cim:ACLineSegment.x ; sh:name "Line.x-range" ; sh:minInclusive 0 .
            """;

    static Path write(Path dir, String relative, String content) {
        try {
            Path file = dir.resolve(relative);
            Files.createDirectories(file.getParent());
            return Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An equipment model with one substation named {@code name}. */
    static String substation(String name) {
        return TestModels.eq().resource("Substation", "_sub1").literal("IdentifiedObject.name", name).toRdfXml();
    }

    /** Writes a workbook: each sheet is a name followed by its rows (strings, or numbers). */
    public static Path workbook(Path file, Object... sheetsAndRows) {
        try {
            Files.createDirectories(file.getParent());
            try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
                for (int s = 0; s < sheetsAndRows.length; s += 2) {
                    Sheet sheet = book.createSheet((String) sheetsAndRows[s]);
                    @SuppressWarnings("unchecked")
                    List<List<Object>> rows = (List<List<Object>>) sheetsAndRows[s + 1];
                    for (int r = 0; r < rows.size(); r++) {
                        Row row = sheet.createRow(r);
                        for (int c = 0; c < rows.get(r).size(); c++) {
                            Object value = rows.get(r).get(c);
                            if (value instanceof Number number) {
                                row.createCell(c).setCellValue(number.doubleValue());
                            } else if (value != null) {
                                row.createCell(c).setCellValue(value.toString());
                            }
                        }
                    }
                }
                book.write(out);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** excel2shacl input: one range constraint on ACLineSegment.r for the mini 2020 profile. */
    static Path constraintWorkbook(Path file) {
        String cim = TestModels.CIM_NS;
        return workbook(file,
                "Constraints", List.of(
                        List.<Object>of("Class", "Id", "Name", "Description", "Message", "Severity", "Type", "Shape",
                                "Path", "Constraint1", "Value1", "Constraint2", "Value2"),
                        List.<Object>of("", "", "ACLineSegment.r-range", "r is not negative", "r is negative",
                                "Violation", "", "ACLineSegment.r-range", cim + "ACLineSegment.r",
                                "minInclusive", 0, "maxExclusive", 1000)),
                "Config", List.of(
                        List.<Object>of("Prefix", "URI"),
                        List.<Object>of("cim", cim),
                        List.<Object>of("sh", "http://www.w3.org/ns/shacl#"),
                        List.<Object>of("xsd", "http://www.w3.org/2001/XMLSchema#")));
    }

    /** organize input: each of the two property shapes into a file of its own. */
    static Path organizeTemplate(Path file) {
        List<Object> header = List.of("constraintName", "subfolder", "filename", "newPrefix", "newPrefixNS",
                "newBaseURI", "newGroupURI", "newGroupName");
        return workbook(file, "Template", List.of(header,
                List.<Object>of("Line.r-range", "line", "r.ttl", "keep", "http://example.com/new#",
                        "http://example.com/base/r.ttl", "", ""),
                List.<Object>of("Line.x-range", "line", "x.ttl", "keep", "http://example.com/new#",
                        "http://example.com/base/x.ttl", "", "")));
    }

    /**
     * gen-instances input: a Config sheet naming the header class (md:FullModel) and one class
     * sheet (cim:Substation), each laid out as the generation template is: class, property,
     * type and datatype rows, a "Mapping" row, then the data.
     */
    public static Path instanceTemplate(Path file) {
        return workbook(file,
                "Config", List.of(
                        List.<Object>of("Prefix", "Namespace", "Use", "Note", "Classes", "Header"),
                        List.<Object>of("md", TestModels.MD_NS, "Yes", "", "Substation", "FullModel"),
                        List.<Object>of("cim", TestModels.CIM_NS, "Yes", "", "", "")),
                "FullModel", List.of(
                        List.<Object>of("Class", "md:FullModel"),
                        List.<Object>of("rdf:id", "md:Model.description"),
                        List.<Object>of("Type", "Literal"),
                        List.<Object>of("Datatype", "string"),
                        List.<Object>of("Mapping", "description"),
                        List.<Object>of("urn:uuid:7c9a5b4e-1d1f-4f43-9a55-1b1f0e3c2a10", "A generated model")),
                "Substation", List.of(
                        List.<Object>of("Class", "cim:Substation", "", "true"),
                        List.<Object>of("rdf:id", "cim:IdentifiedObject.name"),
                        List.<Object>of("Type", "Literal"),
                        List.<Object>of("Datatype", "string"),
                        List.<Object>of("Mapping", "name"),
                        List.<Object>of("_sub1", "North")));
    }

    private static Path copyModel(Path dir, String relative) {
        Path target = dir.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, substation("North"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    // ---- scenarios ------------------------------------------------------------------------------

    /** Writes the inputs of {@code command}'s scenario under {@code dir}. */
    public static Scenario scenario(String command, Path dir) {
        ObjectNode config = JSON.createObjectNode();
        return switch (command) {
            case "validate" -> {
                Path shapes = write(dir, "constraints/shapes.ttl", TestModels.THING_SHAPES);
                Path data = write(dir, "models/eq.xml", TestModels.VIOLATING_THING_MODEL);
                config.put("workflow", "combined").put("outputDir", "out").put("xmlBase", TestModels.XML_BASE)
                        .put("workers", 1).put("format", "json");
                config.putArray("constraintFiles").add("constraints/shapes.ttl");
                config.putArray("dataFiles").add("models/eq.xml");
                yield new Scenario(command, List.of("--workflow", "combined", "--constraint-files", shapes.toString(),
                        "--data-files", data.toString(), "--output", dir.resolve("out").toString(),
                        "--xml-base", TestModels.XML_BASE, "--workers", "1", "--format", "json"),
                        config, ExitCode.VIOLATIONS, List.of());
            }
            case "sparql" -> {
                Path model = write(dir, "m.ttl", "<urn:x:a> <urn:x:p> \"one\" .\n<urn:x:b> <urn:x:p> \"two\" .\n");
                config.put("query", SPARQL_QUERY).put("format", "json");
                config.putArray("models").add("m.ttl");
                yield new Scenario(command, List.of("--models", model.toString(), "--query", SPARQL_QUERY,
                        "--format", "json"), config, ExitCode.OK, List.of());
            }
            case "compare" -> {
                Path a = write(dir, "a.ttl", SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"x\" .\n");
                Path b = write(dir, "b.ttl", SHAPE_PREFIX + "<urn:x:a> a sh:NodeShape ; sh:name \"y\" .\n");
                config.put("fileA", "a.ttl").put("fileB", "b.ttl").put("compareType", "shacl").put("format", "json");
                yield new Scenario(command, List.of("--file-a", a.toString(), "--file-b", b.toString(),
                        "--compare-type", "shacl", "--format", "json"), config, ExitCode.VIOLATIONS, List.of());
            }
            case "compare-instances" -> {
                Path a = write(dir, "a/eq.xml", substation("North"));
                Path b = write(dir, "b/eq.xml", substation("South"));
                config.put("xmlBase", TestModels.XML_BASE).put("format", "json");
                config.putArray("modelsA").add("a/eq.xml");
                config.putArray("modelsB").add("b/eq.xml");
                yield new Scenario(command, List.of("--models-a", a.toString(), "--models-b", b.toString(),
                        "--xml-base", TestModels.XML_BASE, "--format", "json"), config, ExitCode.VIOLATIONS, List.of());
            }
            case "convert" -> {
                Path input = write(dir, "eq.xml", substation("North"));
                config.put("input", "eq.xml").put("output", "eq.ttl").put("xmlBase", TestModels.XML_BASE);
                yield new Scenario(command, List.of("--input", input.toString(), "--output",
                        dir.resolve("eq.ttl").toString(), "--xml-base", TestModels.XML_BASE),
                        config, ExitCode.OK, List.of("eq.ttl"));
            }
            case "rdfs2shacl" -> {
                Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", dir);
                config.put("outputDir", "shapes");
                config.putArray("rdfsFiles").add(rdfs.getFileName().toString());
                yield new Scenario(command, List.of("--rdfs-files", rdfs.toString(), "--output-dir",
                        dir.resolve("shapes").toString()), config, ExitCode.OK,
                        List.of("shapes/mini-equipment-2020-cim100.ttl"));
            }
            case "excel2shacl" -> {
                Path rdfs = Fixtures.copy("shacl-generation", "mini-equipment-2020-cim100.rdf", dir);
                Path xlsx = constraintWorkbook(dir.resolve("constraints.xlsx"));
                config.put("rdfsFile", rdfs.getFileName().toString()).put("excelFile", "constraints.xlsx")
                        .put("output", "shapes.ttl").put("nsPrefix", "meqc")
                        .put("nsUri", "http://example.com/constraints/MiniEquipment#")
                        .put("cimNamespace", TestModels.CIM_NS);
                yield new Scenario(command, List.of("--rdfs-file", rdfs.toString(), "--excel-file", xlsx.toString(),
                        "--output", dir.resolve("shapes.ttl").toString(), "--ns-prefix", "meqc",
                        "--ns-uri", "http://example.com/constraints/MiniEquipment#",
                        "--cim-namespace", TestModels.CIM_NS), config, ExitCode.OK, List.of("shapes.ttl"));
            }
            case "organize" -> {
                Path shapes = write(dir, "all.ttl", ORGANIZE_SHAPES);
                Path template = organizeTemplate(dir.resolve("template.xlsx"));
                config.put("templateXlsx", "template.xlsx").put("outputDir", "organized");
                config.putArray("shaclFiles").add("all.ttl");
                yield new Scenario(command, List.of("--shacl-files", shapes.toString(), "--template-xlsx",
                        template.toString(), "--output-dir", dir.resolve("organized").toString()),
                        config, ExitCode.OK, List.of("organized/line/r.ttl", "organized/line/x.ttl"));
            }
            case "gen-instances" -> {
                Path template = instanceTemplate(dir.resolve("instances_template.xlsx"));
                config.put("templateXlsx", "instances_template.xlsx").put("output", "instances.xml")
                        .put("xmlBase", TestModels.XML_BASE);
                yield new Scenario(command, List.of("--template-xlsx", template.toString(), "--output",
                        dir.resolve("instances.xml").toString(), "--xml-base", TestModels.XML_BASE),
                        config, ExitCode.OK, List.of("instances.xml"));
            }
            case "manifest" -> {
                copyModel(dir, "models/EQ.xml");
                config.put("dir", "models").put("output", "manifest.ttl");
                yield new Scenario(command, List.of("--dir", dir.resolve("models").toString(), "--output",
                        dir.resolve("manifest.ttl").toString()), config, ExitCode.OK, List.of("manifest.ttl"));
            }
            case "run" -> {
                write(dir, "m.ttl", "<urn:x:a> <urn:x:p> \"one\" .\n");
                ObjectNode step = JSON.createObjectNode().put("id", "query").put("command", "sparql")
                        .put("query", SPARQL_QUERY).put("output", "rows.csv");
                step.putArray("models").add("m.ttl");
                ObjectNode pipeline = JSON.createObjectNode().put("name", "contract");
                pipeline.putArray("steps").add(step);
                Path file = write(dir, "pipeline.json", JSON.writeValueAsString(pipeline));
                yield new Scenario(command, List.of("--format", "json", file.toString()), config, ExitCode.OK,
                        List.of("rows.csv"));
            }
            default -> throw new IllegalArgumentException("No scenario for " + command);
        };
    }
}
