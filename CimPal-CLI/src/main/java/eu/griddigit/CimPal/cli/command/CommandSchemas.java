/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * The JSON Schema of each command's config, as {@code mcp} offers it to AI clients (tool input
 * schemas) and as the {@code /v1} job API describes it in its OpenAPI document (DEP-5). One source,
 * so the two can't drift apart; {@code OpenApiSpecTest} checks the spec against it.
 */
final class CommandSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One command: its MCP tool name, its CLI name, whether it prints JSON, and its config schema. */
    record Entry(String toolName, String command, boolean supportsJson, String description, ObjectNode inputSchema) {

        /** A fresh copy, so callers can embed it without sharing the node. */
        @Override
        public ObjectNode inputSchema() {
            return inputSchema.deepCopy();
        }
    }

    private static final List<Entry> ALL = List.of(
            entry("validate", "validate", true,
                    "Validate CGMES/CIM model files against SHACL constraint shapes. "
                            + "Returns a JSON summary with totals (conforming/violations/errors) "
                            + "and per-shape violation groups with sample focus nodes. "
                            + "Use this as the primary diagnostic step in the validation loop.",
                    p -> {
                        p.set("workflow", schema("string",
                                "Workflow: 'mapping' (default, uses a CSV mapping file) or "
                                        + "'timestamped' (groups by timestamp)."));
                        p.set("mappingCsv", schema("string",
                                "Absolute path to the CSV mapping file. "
                                        + "Three columns: model file names | constraint .ttl path | label. "
                                        + "Required for mapping and timestamped workflows."));
                        p.set("modelsDir", schema("string",
                                "Absolute path to the root folder containing model ZIP/XML archives."));
                        p.set("constraintsRoot", schema("string",
                                "Absolute path to the root folder for SHACL constraint .ttl files."));
                        p.set("outputDir", schema("string",
                                "Absolute path to the output folder for Excel reports and ZIPs."));
                        p.set("datatypeMap", schema("string",
                                "CGMES version preset: 'CGMES30NC25' (default), 'CGMES30NC24', 'CGMES24NC22', "
                                        + "or absolute path to a custom .properties file."));
                        p.set("xmlBase", schema("string",
                                "Base URI for parsing model files. "
                                        + "CGMES 3.0: 'http://iec.ch/TC57/CIM100' (default). "
                                        + "CGMES 2.4: 'http://iec.ch/TC57/2013/CIM-schema-cim16'."));
                        p.set("engine", schema("string",
                                "Validation engine: 'APACHE_JENA' (default, production-grade), "
                                        + "'PYSHACL', 'PYSHACL_OXIGRAPH', 'RUST_SHACL' (experimental)."));
                        p.set("workers", schema("integer",
                                "Parallel validation workers. 0 = auto-sized from available heap."));
                        p.set("maxResultsPerConstraint", schema("integer",
                                "Max violations per shape (0 = unlimited). Use 10 for quick checks."));
                        p.set("samples", schema("integer",
                                "Focus-node samples per shape group in JSON output (default 3). "
                                        + "Set 0 to disable per-shape detail."));
                        p.set("exportTurtle", schema("boolean",
                                "Write .ttl validation report files alongside Excel output."));
                    }),
            entry("sparql", "sparql", true,
                    "Execute a SPARQL SELECT query against one or more CIM/RDF model files. "
                            + "Use to inspect model data, count triples, or diagnose why a SHACL constraint fires.",
                    p -> {
                        p.set("models", arraySchema("Absolute paths to model files (.xml, .rdf, .ttl, .zip)."));
                        p.set("query", schema("string",
                                "SPARQL SELECT query string, or absolute path to a .sparql/.rq file."));
                        p.set("xmlBase", schema("string",
                                "Base URI for parsing model files (default: http://iec.ch/TC57/CIM100)."));
                        p.set("output", schema("string",
                                "Absolute path for output file (.xlsx or .csv). "
                                        + "If omitted, results are returned inline."));
                    }, "models", "query"),
            entry("compare", "compare", true,
                    "Compare two RDF files (RDFS profiles or SHACL shapes) and report differences. "
                            + "Use to audit what changed between profile versions or shape-set revisions.",
                    p -> {
                        p.set("fileA", schema("string", "Absolute path to the first (reference/before) file."));
                        p.set("fileB", schema("string", "Absolute path to the second (changed/after) file."));
                        p.set("compareType", schema("string",
                                "Algorithm: 'rdfs' (augmented RDFS), 'shacl' (any RDF), "
                                        + "'rdfs-cimtool', 'auto' (detect from extension, default)."));
                        p.set("normalizeCimVersion", schema("boolean",
                                "Rename the 'cim' namespace in fileB to match fileA before comparing. "
                                        + "Use when comparing across CIM version boundaries."));
                        p.set("output", schema("string", "Absolute path for the diff report (.xlsx or .csv)."));
                    }, "fileA", "fileB"),
            entry("compare_instances", "compare-instances", true,
                    "Compare two sets of CIM instance-data model files and report differences. "
                            + "Use to verify a conversion or transformation preserved all data.",
                    p -> {
                        p.set("modelsA", arraySchema("Absolute paths to the first (before/reference) model files."));
                        p.set("modelsB", arraySchema("Absolute paths to the second (after/changed) model files."));
                        p.set("xmlBase", schema("string",
                                "Base URI for parsing files (default: http://iec.ch/TC57/CIM100)."));
                        p.set("ignoreSv", schema("boolean", "Ignore SV (state-variable) profile differences."));
                        p.set("ignoreTp", schema("boolean", "Ignore TP (topology) differences."));
                        p.set("ignoreDl", schema("boolean", "Ignore DL (diagram layout) differences."));
                        p.set("output", schema("string", "Absolute path for output (.xlsx or .csv)."));
                    }, "modelsA", "modelsB"),
            entry("convert", "convert", false,
                    "Convert RDF model files between formats (RDF/XML, Turtle, JSON-LD). "
                            + "Use to produce canonical Turtle for git-friendly diffs.",
                    p -> {
                        p.set("input", schema("string", "Absolute path to the source file."));
                        p.set("inputFiles", arraySchema(
                                "Absolute paths to source files for model union (mutually exclusive with 'input')."));
                        p.set("output", schema("string",
                                "Absolute path for the output file. Extension determines format: "
                                        + ".ttl -> Turtle, .xml/.rdf -> RDF/XML, .jsonld -> JSON-LD."));
                        p.set("xmlBase", schema("string", "Base URI for parsing RDF/XML files."));
                        p.set("sort", schema("boolean", "Sort triples for deterministic, diffable output."));
                        p.set("rdfFormat", schema("string",
                                "RDF/XML sub-format: RDFXML_PLAIN (default), RDFXML_ABBREV, CIMXML, RDFS_CIMXML."));
                    }, "output"),
            entry("rdfs_to_shacl", "rdfs2shacl", false,
                    "Generate SHACL shape files from RDFS CIM profile definitions. "
                            + "Produces one .ttl shape file per input RDFS file.",
                    p -> {
                        p.set("rdfsFiles", arraySchema("Absolute paths to RDFS profile .rdf files."));
                        p.set("outputDir", schema("string",
                                "Absolute path to the output directory for generated .ttl files."));
                        p.set("rdfsFormat", schema("string",
                                "RDFS format version: '2020' (default, cimsyntaxgen augmented) or '2019'."));
                        p.set("closedShapes", schema("boolean",
                                "Generate sh:closed shapes (very strict - rejects undeclared properties)."));
                        p.set("splitDatatypes", schema("boolean",
                                "Write a separate datatype constraint file alongside the main shapes."));
                        p.set("validateShapes", schema("boolean",
                                "Run SHACL-SHACL validation on generated shapes and report results."));
                    }, "rdfsFiles", "outputDir"),
            entry("organize", "organize", false,
                    "Reorganize SHACL constraint files according to an Excel mapping template. "
                            + "Splits constraints from input .ttl files into per-file outputs with canonical ordering.",
                    p -> {
                        p.set("shaclFiles", arraySchema("Absolute paths to SHACL .ttl/.rdf files to organize."));
                        p.set("templateXlsx", schema("string",
                                "Absolute path to the Excel template defining the output structure."));
                        p.set("outputDir", schema("string", "Absolute path to the root output directory."));
                    }, "shaclFiles", "templateXlsx", "outputDir"),
            entry("excel_to_shacl", "excel2shacl", false,
                    "Generate SHACL constraint shapes from an Excel spreadsheet and RDFS profile.",
                    p -> {
                        p.set("rdfsFile", schema("string", "Absolute path to the RDFS profile .rdf file."));
                        p.set("excelFile", schema("string",
                                "Absolute path to the Excel .xlsx constraint definitions file."));
                        p.set("output", schema("string", "Absolute path for the generated .ttl shape file."));
                        p.set("nsPrefix", schema("string", "Namespace prefix for generated shapes."));
                        p.set("nsUri", schema("string", "Namespace URI for generated shapes."));
                    }, "rdfsFile", "excelFile", "output"),
            entry("gen_instances", "gen-instances", false,
                    "Generate CIM/RDF instance data from a CimPal Excel template. "
                            + "Use to create synthetic pass/fail fixtures for shape testing.",
                    p -> {
                        p.set("templateXlsx", schema("string",
                                "Absolute path to the CimPal Advanced template .xlsx file."));
                        p.set("output", schema("string", "Absolute path for the generated RDF/XML file."));
                        p.set("xmlBase", schema("string", "Base URI (default: http://iec.ch/TC57/CIM100)."));
                        p.set("stripPrefixes", schema("boolean", "Remove unused namespace prefixes."));
                    }, "templateXlsx", "output"),
            entry("manifest", "manifest", false,
                    "Generate a DCAT/CGMES manifest Turtle file describing a set of model files.",
                    p -> {
                        p.set("dir", schema("string",
                                "Absolute path to a folder containing the model files to describe."));
                        p.set("files", arraySchema(
                                "Absolute paths to individual model files (alternative to 'dir')."));
                        p.set("accessUrl", schema("string", "The dcat:accessURL written into the manifest."));
                        p.set("output", schema("string", "Absolute path for the manifest .ttl file."));
                    })
    );

    private CommandSchemas() {
    }

    /** Every command that {@code mcp} and {@code serve} offer, in a fixed order. */
    static List<Entry> all() {
        return ALL;
    }

    /** The entry for an MCP tool name, or null. */
    static Entry byToolName(String toolName) {
        return ALL.stream().filter(e -> e.toolName().equals(toolName)).findFirst().orElse(null);
    }

    /** The entry for a CLI command name, or null. */
    static Entry byCommand(String command) {
        return ALL.stream().filter(e -> e.command().equals(command)).findFirst().orElse(null);
    }

    private static Entry entry(String toolName, String command, boolean supportsJson, String description,
                               Consumer<ObjectNode> properties, String... required) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        properties.accept(schema.putObject("properties"));
        if (required.length > 0) {
            ArrayNode req = schema.putArray("required");
            for (String r : required) {
                req.add(r);
            }
        }
        return new Entry(toolName, command, supportsJson, description, schema);
    }

    private static ObjectNode schema(String type, String description) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", type);
        n.put("description", description);
        return n;
    }

    private static ObjectNode arraySchema(String description) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "array");
        n.putObject("items").put("type", "string");
        n.put("description", description);
        return n;
    }
}
