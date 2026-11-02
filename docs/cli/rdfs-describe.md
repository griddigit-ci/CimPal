<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `rdfs-describe`: RDFS profile descriptions to Excel

Writes one row per item of an RDFS profile (class, property, enumeration value, package) to an Excel workbook, so a profile can be reviewed without reading RDF/XML. It is the same as the GUI's **Tools ▸ RDFS Profiles ▸ Export RDFS descriptions**, and both use `RdfsDescriptions` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar rdfs-describe ^
  --rdfs-file C:\Profiles\EquipmentProfile.rdf ^
  --output C:\Reports\EQ_descriptions.xlsx
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `rdfsFile`, `output`). Flags override it; relative paths resolve against its folder. |
| `--rdfs-file` | file path | — | The RDFS profile (RDF/XML `.rdf`; `.ttl` is read as Turtle). **Required.** |
| `--output` | file path | — | The `.xlsx` workbook to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## The workbook

One sheet, `RDFS descriptions`, with a row per subject, sorted by item name:

| Column | Content |
|---|---|
| Item | The local name, e.g. `ACLineSegment.r` |
| Description | `rdfs:comment` as text, or `No description defined.` |
| Multiplicity | From `cims:multiplicity` (`...#M:0..1` → `0..1`), else `N/A` |
| Type | `Class`, `Attribute`, `Association` (has `cims:AssociationUsed`), `Package` (`cims:ClassCategory`) or `Enum` (anything else) |
| Association used | `Yes` / `No` for associations, else `N/A` |
| Stereotype | The literal `cims:stereotype` values joined by `; `, else `N/A` |
| Concrete class | `Yes` (stereotype `concrete`), `No`, or `N/A` (not a class, or a Primitive, CIMDatatype or Compound) |

Formula-like text is stored as text, never evaluated.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Workbook written |
| 2 | Bad input: a flag missing, the file not found, unreadable, unparseable or JSON-LD, an `--output` that isn't `.xlsx`, or an unreadable config |
| 3 | Internal error, e.g. the workbook can't be written |
