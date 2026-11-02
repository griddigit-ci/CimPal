<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `ref2common`: reference data to common data

Converts reference data into "common data" written as CIM/XML instance data. Every subject typed with a class in the `cim` or `nc` namespace is copied with its properties. dcat, dcterms and skos properties are dropped, and every IRI becomes a document-local id `_<local name>`. It is the same as the GUI's **Tools ▸ Reference Data ▸ Convert Reference data to Common data**, and both use `CommonDataConverter` in Core.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar ref2common --rdf-files C:\Ref\BaseVoltages.rdf,C:\Ref\Areas.rdf --output C:\Ref\CommonData.xml
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (keys `rdfFiles`, an array, and `output`). Flags override it; relative paths resolve against its folder. |
| `--rdf-files` | comma-separated paths | — | Reference data files: `.rdf`/`.xml` (RDF/XML) or `.ttl`. They are merged before converting. JSON-LD is refused. **Required.** |
| `--output` | file path | — | The `.xml` file to write. Parent folders are created; an existing file is replaced. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

---

## What it writes

- **Which subjects:** the `cim` and `nc` namespaces are the ones the input declares as prefixes. A subject is copied when one of its `rdf:type` values is in either namespace. Blank nodes are skipped.
- **Ids:** every IRI, the subject's and its references', becomes `_<local name>`. The file has relative ids and no `xml:base`.
- **Properties:** all of them except `rdf:type` (written once), dcat, dcterms and skos.
- **Layout:** sorted CIM/XML (`RDFXML_CUSTOM_PLAIN_PRETTY`), with an `md:FullModel` header on top if there is one.

Input that declares neither a `cim` nor an `nc` prefix is an error (exit 2), and so is input with no subject typed with a class in them. An empty file would otherwise look like a successful conversion.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | File written |
| 2 | Bad input: a flag missing, a file not found, unreadable, unparseable, JSON-LD or a quad format, no `cim`/`nc` prefix or no subject typed with them, an `--output` that isn't `.xml`, or an unreadable config |
| 3 | Internal error, e.g. the file can't be written |
