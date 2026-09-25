<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `compare` — RDF File Comparison Command

Compares two RDF files (RDFS profiles or SHACL shape files) and reports what changed between them. Useful for auditing profile version changes, reviewing shape edits, and generating change documentation.

---

## Quick examples

**Compare two RDFS profile versions:**
```
java -jar CimPal-CLI.jar compare ^
  --file-a C:\Profiles\EQ-v2024.rdf ^
  --file-b C:\Profiles\EQ-v2026.rdf
```

**Compare SHACL shape files:**
```
java -jar CimPal-CLI.jar compare ^
  --file-a C:\Shapes\QoCDC-v1.ttl ^
  --file-b C:\Shapes\QoCDC-v2.ttl ^
  --compare-type shacl
```

**Compare profiles with different CIM version namespaces (normalize first):**
```
java -jar CimPal-CLI.jar compare ^
  --file-a C:\Profiles\EQ-cim17.rdf ^
  --file-b C:\Profiles\EQ-cim16.rdf ^
  --normalize-cim-version
```

**Save results to Excel:**
```
java -jar CimPal-CLI.jar compare ^
  --file-a EQ-v2024.rdf --file-b EQ-v2026.rdf ^
  --output C:\Data\profile-diff.xlsx
```

**Get machine-readable JSON for scripting:**
```
java -jar CimPal-CLI.jar compare ^
  --file-a EQ-v2024.rdf --file-b EQ-v2026.rdf ^
  --format json > diff.json
```

**From a config file:**
```
java -jar CimPal-CLI.jar compare --config configs/compare.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--file-a` | file path | — | First RDF file (the "before" or reference version). **Required.** |
| `--file-b` | file path | — | Second RDF file (the "after" or changed version). **Required.** |
| `--compare-type` | see below | `auto` | Which comparison algorithm to use. |
| `--normalize-cim-version` | flag | off | Rename the `cim` namespace prefix in file-b to match file-a before comparing. Use when comparing profiles that target different CIM versions (e.g. CIM16 vs CIM17). |
| `--normalize-profile-ns` | flag | off | Rename the profile namespace in file-b to match file-a. Use when the profile URI changed between versions. |
| `--ns-prefix` | string | `""` | Which namespace prefix to normalize when `--normalize-profile-ns` is set. |
| `--output` | file path | — | Write results to a file. Use `.xlsx` for Excel; any other extension for CSV. If omitted, results go to stdout. |
| `--format` | `text` / `json` / `csv` | `text` | Output format for stdout. Ignored when `--output` is set. |
| `--dry-run` | flag | off | Print resolved config and exit without comparing. |

---

## Comparison types

| Value | Description | Input format |
|---|---|---|
| `auto` | Auto-detect from file extension: `.ttl` → `shacl`, `.rdf` → `rdfs` | Mixed |
| `rdfs` | Compares augmented RDFS profiles produced by cimsyntaxgen. Finds added/removed/changed classes, attributes, associations, and packages. | `.rdf` |
| `rdfs-cimtool` | Like `rdfs`, but handles augmented RDFS files that include CIMTool-style merged OWL artefacts. | `.rdf` |
| `shacl` | Universal RDF graph comparison. Works with SHACL shape files and any other RDF file. Compares statements directly. | `.rdf` or `.ttl` |

For RDFS profile diff work, use `rdfs`. For SHACL shape sets, use `shacl`. When unsure, use `auto`.

---

## Namespace normalization

CIM profiles across different CIM versions use different namespace URIs for the same vocabulary. A straight comparison finds thousands of "differences" that are purely the namespace change — not actual content changes.

**`--normalize-cim-version`** renames the `cim` prefix namespace in file-b to match file-a before comparing. Use when comparing CGMES 2.4 and CGMES 3.0 profile versions.

**`--normalize-profile-ns`** does the same for the profile's own namespace (not the `cim` namespace). Use when comparing two revisions of the same profile that changed their namespace URI (e.g. `http://iec.ch/TC57/CIM100-European` → `https://cim.ucaiug.io/ns/eu`).

Set `--ns-prefix` to the prefix being normalized when `--normalize-profile-ns` is used.

---

## Output formats

**Text** (default) — pipe-separated table to stdout:

```
Item                                        | Type       | Property           | File A      | File B
-------
http://iec.ch/TC57/CIM100#ACLineSegment     | Class      | rdfs:label         | ACLineSegment | (not present)
http://iec.ch/TC57/CIM100#VoltageLimit      | Class      | rdfs:comment       | old text    | new text
...
Total: 2 difference(s) found between EQ-v2024.rdf and EQ-v2026.rdf
```

**JSON** — to stdout:
```json
{
  "schema": "cimpal-compare-result/1",
  "fileA": "C:/Profiles/EQ-v2024.rdf",
  "fileB": "C:/Profiles/EQ-v2026.rdf",
  "compareType": "rdfs",
  "totalDifferences": 2,
  "differences": [
    {
      "item": "http://iec.ch/TC57/CIM100#ACLineSegment",
      "rdfType": "Class",
      "property": "rdfs:label",
      "valueA": "ACLineSegment",
      "valueB": "(not present)"
    }
  ]
}
```

**CSV** — RFC 4180 format with header row: `item,rdfType,property,valueA,valueB`

**Excel** (`--output diff.xlsx`) — one sheet with headers in row 1.

---

## Understanding the results

Each result entry has five fields:

- **item** — the URI of the class, property, or association that differs
- **rdfType** — what kind of thing it is (Class, Attribute, Association, Package, etc.)
- **property** — which RDF property differs between the two files
- **valueA** — the value in file-a (empty string if not present in file-a)
- **valueB** — the value in file-b (empty string if not present in file-b)

When a value is `(not present)`, the item exists in one file but not the other. This is an addition or deletion. When both values are non-empty and different, it is a modification.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Files are identical — no differences found |
| 1 | Differences found — check the output |
| 2 | Bad input (file not found, unsupported format) |
| 3 | Internal error |

Exit code 0 / 1 distinction is useful in CI: a diff check can gate on whether a profile version actually changed content.

---

## JSON config format

```json
{
  "_comment": "Compare two RDFS profile versions",

  "fileA": "REPLACE_WITH_PATH/EQ-v2024.rdf",
  "_note_fileA": "The reference (before) version. Accepts .rdf or .ttl.",

  "fileB": "REPLACE_WITH_PATH/EQ-v2026.rdf",
  "_note_fileB": "The changed (after) version.",

  "compareType": "auto",
  "_note_compareType": "Options: auto, rdfs, rdfs-cimtool, shacl. 'auto' detects from extension.",

  "normalizeCimVersion": false,
  "_note_normalizeCimVersion": "true renames the 'cim' namespace in fileB to match fileA before comparing. Use when comparing across CIM version boundaries.",

  "normalizeProfileNs": false,
  "nsPrefix": "",

  "output": "REPLACE_WITH_PATH/diff.xlsx",
  "_note_output": "Use .xlsx for Excel, any other extension for CSV. Remove this field to print to stdout instead.",

  "format": "text",
  "_note_format": "Only used when 'output' is not set. Options: text, json, csv."
}
```
