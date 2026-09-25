<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `excel2shacl` — Excel to SHACL Generation Command

Generates SHACL constraint shapes from an Excel spreadsheet and an RDFS profile. Used for spreadsheet-driven constraint authoring — define constraints in Excel rows, generate `.ttl` shape files automatically.

---

## Quick examples

**Generate shapes from Excel + RDFS:**
```
java -jar CimPal-CLI.jar excel2shacl ^
  --rdfs-file C:\Profiles\EQ.rdf ^
  --excel-file C:\Constraints\value-constraints.xlsx ^
  --output C:\Shapes\value-constraints.ttl ^
  --ns-prefix "eu-nc" ^
  --ns-uri "https://cim.ucaiug.io/ns/nc#"
```

**From a config file:**
```
java -jar CimPal-CLI.jar excel2shacl --config configs/excel2shacl.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--rdfs-file` | file path | — | RDFS profile `.rdf` file. Provides the class/attribute vocabulary used to resolve constraint targets. **Required.** |
| `--excel-file` | file path | — | Excel `.xlsx` file with constraint definitions. **Required.** See **Excel format** below. |
| `--output` | file path | — | Output Turtle `.ttl` file. **Required.** |
| `--ns-prefix` | string | `""` | Namespace prefix for the generated shapes (e.g. `"eu-nc"`). |
| `--ns-uri` | URI | `""` | Namespace URI for the generated shapes (e.g. `"https://cim.ucaiug.io/ns/nc#"`). |
| `--cims-namespace` | URI | `http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#` | CIMS extensions namespace. Rarely needs changing. |
| `--cim-namespace` | URI | `http://iec.ch/TC57/CIM100#` | CIM namespace. Use `http://iec.ch/TC57/2013/CIM-schema-cim16#` for CGMES 2.4. |
| `--dry-run` | flag | off | Print resolved config and exit without generating. |

---

## Excel format

The Excel file must have at minimum a **data sheet** (sheet index 0). It may also have a **Config sheet** (named exactly `Config`).

**Config sheet** (optional but recommended): defines namespace prefix→URI mappings. Two columns: prefix (column A), URI (column B). When present, these namespaces override the `--ns-prefix` / `--ns-uri` / `--cims-namespace` / `--cim-namespace` flags. When absent, the flags are used as fallbacks.

**Data sheet** (required): each row defines one SHACL constraint. The exact column mapping depends on the constraint type — refer to the existing Excel templates in the GUI for the column structure. This is the same format that the GUI's "Excel to SHACL" tab consumes.

---

## How it works

1. The RDFS file is loaded and parsed to extract class/attribute/association structure (shape data).
2. The Excel data sheet is read row by row.
3. For each row, a SHACL `sh:PropertyShape` is created on the matching `sh:NodeShape`.
4. All generated shapes are written to the output `.ttl` file in Turtle format.

The generated shapes are a subset of what `rdfs2shacl` would produce — they are value-constraint shapes authored explicitly in the Excel, not scaffolded from cardinality metadata.

---

## Limitations

- Base profile shapes (multi-tier inheritance) are not supported in the CLI. If your Excel-based workflow uses base profiles, run it from the GUI for now.
- The `sh:group` and prefix features of the organizer (`organize` command) can be applied to the output afterwards.

---

## JSON config format

```json
{
  "_comment": "Generate SHACL constraints from Excel + RDFS profile",

  "rdfsFile": "REPLACE_WITH_PATH/EQ.rdf",
  "_note_rdfsFile": "The RDFS profile .rdf file. Provides class and property vocabulary for the generated shapes.",

  "excelFile": "REPLACE_WITH_PATH/constraints.xlsx",
  "_note_excelFile": "The Excel .xlsx file. Must have a data sheet at index 0. May have a 'Config' sheet for namespace declarations.",

  "output": "REPLACE_WITH_PATH/constraints.ttl",
  "_note_output": "Where to write the generated SHACL Turtle file.",

  "nsPrefix": "eu-nc",
  "_note_nsPrefix": "Namespace prefix for the shapes (e.g. 'eu-nc' or 'cim17-eq-shapes'). Used in generated sh:NodeShape URIs.",

  "nsUri": "https://cim.ucaiug.io/ns/nc#",
  "_note_nsUri": "Namespace URI for the shapes. Must end with # or /.",

  "cimsNamespace": "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#",
  "_note_cimsNamespace": "CIMS extensions namespace. Rarely needs changing.",

  "cimNamespace": "http://iec.ch/TC57/CIM100#",
  "_note_cimNamespace": "CIM namespace. For CGMES 2.4 use http://iec.ch/TC57/2013/CIM-schema-cim16#"
}
```

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Shape file generated successfully |
| 2 | Bad input (file not found, required field missing) |
| 3 | Internal error (RDFS parse error, Excel read error) |
