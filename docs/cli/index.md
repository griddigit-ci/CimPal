# CimPal CLI Documentation

## Reference

| Document | What it covers |
|---|---|
| [README](README.md) | Overview, how to run, exit codes, config file format, output locations |
| [validate](validate.md) | Full reference for the `validate` command — all workflows, flags, JSON format |
| [sparql](sparql.md) | Full reference for the `sparql` command |
| [manifest](manifest.md) | Reference for the `manifest` command |
| [convert](convert.md) | Full reference for the `convert` command — formats, union, sorting |
| [rdfs2shacl](rdfs2shacl.md) | Full reference for the `rdfs2shacl` command — RDFS → SHACL generation |
| [compare](compare.md) | Full reference for the `compare` command — RDF diff, normalization, output formats |
| [compare-instances](compare-instances.md) | Full reference for the `compare-instances` command — instance data diff |
| [excel2shacl](excel2shacl.md) | Full reference for the `excel2shacl` command — Excel-driven SHACL generation |
| [organize](organize.md) | Full reference for the `organize` command — SHACL reorganizer |
| [gen-instances](gen-instances.md) | Full reference for the `gen-instances` command — Excel template → RDF/XML |
| [run](run.md) | Full reference for the `run` command — declarative JSON pipeline runner |
| [serve](serve.md) | Full reference for the `serve` command — local HTTP daemon for agent integration |
| [mcp](mcp.md) | Full reference for the `mcp` command — Model Context Protocol server for Claude |

## Workflows / How-to

| Document | What it covers |
|---|---|
| [ci-pipeline](ci-pipeline.md) | Complete command collection for a CI validation run, with placeholder notes |
| [shape-dev-loop](shape-dev-loop.md) | Fast loop for writing and testing SHACL shapes |

## Config templates

Located in `CimPal-CLI/configs/`. Each file has inline `_note_*` annotations explaining every field.

| Template | Use for |
|---|---|
| `validate-mapping-cgmes30.json` | Standard mapping validation, CGMES 3.0 / NC 2.5 |
| `validate-manual.json` | Manual shape testing against a model folder |
| `validate-timestamped.json` | Timestamped batch validation with trend comparison |
| `sparql-query.json` | SPARQL SELECT query against model files |
| `convert.json` | RDF format conversion |
| `rdfs2shacl.json` | RDFS to SHACL shape generation |
| `compare.json` | RDF profile/shape comparison |
| `compare-instances.json` | CGMES instance data comparison |
| `excel2shacl.json` | Excel-to-SHACL shape generation |
| `organize.json` | SHACL organizer / canonical restructuring |
| `gen-instances.json` | Instance data generation from Excel template |
| `pipeline-full-validation.json` | Pipeline: convert to Turtle then validate |
| `pipeline-shape-dev.json` | Pipeline: generate fixtures then validate with shapes |
| `pipeline-profile-migration.json` | Pipeline: diff profiles then generate fresh shapes |

## Commands implemented

| Command | Status |
|---|---|
| `validate` | Done — mapping, timestamped, and manual workflows |
| `sparql` | Done |
| `manifest` | Done |
| `convert` | Done — single file, multi-file union, all RDF/XML sub-formats |
| `rdfs2shacl` | Done — augmented RDFS 2019/2020, split datatypes, inherit tree |
| `compare` | Done — rdfs, shacl, rdfs-cimtool, namespace normalization, Excel/CSV output |
| `compare-instances` | Done — SV/TP/DL ignore flags, text/JSON/CSV/Excel output |
| `excel2shacl` | Done — Excel + RDFS → SHACL, with Config sheet namespace support |
| `organize` | Done — split/reorganize SHACL files per Excel template |
| `gen-instances` | Done — generates RDF/XML instance data from CimPal Excel templates |
| `run` | Done — declarative JSON pipeline runner; chains any combination of commands |
| `serve` | Done — local HTTP daemon; exposes all commands as REST endpoints for agent integration |
| `mcp` | Done — MCP server on stdio; 10 typed tools for Claude Desktop and agent integration |
