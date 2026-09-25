# `organize` — SHACL Organizer Command

Reorganizes SHACL constraint files according to an Excel mapping template. Splits constraints from one or more input `.ttl` files into multiple output files, each with a defined structure, namespace, and canonical ordering.

Use this after editing shapes manually to restore canonical formatting, or after generating shapes with `rdfs2shacl`/`excel2shacl` to reorganize them into a versioned layout.

---

## Quick examples

**Reorganize SHACL files per an Excel template:**
```
java -jar CimPal-CLI.jar organize ^
  --shacl-files C:\Shapes\raw\EQ.ttl,C:\Shapes\raw\TP.ttl ^
  --template-xlsx C:\Shapes\organize-template.xlsx ^
  --output-dir C:\Shapes\organized
```

**From a config file:**
```
java -jar CimPal-CLI.jar organize --config configs/organize.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--shacl-files` | comma-separated paths | — | SHACL `.ttl` or `.rdf` files to organize. All are loaded and treated as a single combined shapes graph. **Required.** |
| `--template-xlsx` | file path | — | Excel mapping template (`.xlsx`) defining the output file structure. **Required.** See **Template format** below. |
| `--output-dir` | folder path | — | Root directory where reorganized files are written. Created if it does not exist. **Required.** |
| `--xml-base` | URI | `""` | Base URI used when loading `.rdf` format SHACL files. Leave empty for `.ttl` Turtle files. |
| `--dry-run` | flag | off | Print resolved config and exit without processing. |

---

## Template format

The Excel template has one row per constraint, defining where it should go in the reorganized output:

| Column | Content |
|---|---|
| A | Constraint name (must match the `sh:name` value in the loaded shapes) |
| B | Subdirectory within `--output-dir` |
| C | Output filename (without extension) |
| D | Namespace prefix for the output file |
| E | Namespace URI for the output file |
| F | Base URI for the output file |
| G | Group URI |
| H | Group name |

When column E is `"skip"`, that constraint is excluded from the output entirely.

This is the same template format used by the GUI's SHACL Organizer tab. Templates used in the GUI can be passed directly to this command.

---

## How it works

1. All SHACL files are loaded and merged into one combined shapes graph.
2. The Excel template is read to build a mapping: constraint name → output file path + namespace.
3. Each constraint (identified by `sh:name`) is located in the combined graph.
4. The constraint and all its related statements (property shapes, SPARQL queries, group links, blank nodes) are extracted and written to the target output file.
5. Output files are serialized as Turtle (`.ttl`).

Constraints not found in any input file are silently skipped. Constraints found but with `"skip"` in column E are excluded.

---

## Relationship to other commands

- Run `rdfs2shacl` first to generate scaffold shapes from a profile.
- Edit the generated shapes as needed.
- Run `organize` to split them into the canonical layout for your constraint library.
- Run `validate` to verify the reorganized shapes against instance data.

---

## JSON config format

```json
{
  "_comment": "Organize SHACL files per Excel mapping template",

  "shaclFiles": [
    "REPLACE_WITH_PATH/raw/EQ.ttl",
    "REPLACE_WITH_PATH/raw/TP.ttl"
  ],
  "_note_shaclFiles": "All input .ttl or .rdf SHACL files. They are merged into one shapes graph before the template is applied.",

  "templateXlsx": "REPLACE_WITH_PATH/organize-template.xlsx",
  "_note_templateXlsx": "Excel template defining the output structure. See the command reference for the column layout.",

  "outputDir": "REPLACE_WITH_PATH/organized",
  "_note_outputDir": "Root directory for output files. Sub-directories are created as specified in column B of the template.",

  "xmlBase": ""
}
```

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Reorganization completed successfully |
| 2 | Bad input (file not found, required field missing) |
| 3 | Internal error (parse failure, write error) |
