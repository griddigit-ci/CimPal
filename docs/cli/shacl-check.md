<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `shacl-check`: are these SHACL shapes well formed?

Checks SHACL shape files against the SHACL-SHACL shapes, the W3C shapes that describe well-formed SHACL. A malformed shape, such as `sh:minCount "one"` or a misspelt `sh:nodeKind`, is reported here instead of silently never firing during validation. It is the same as the GUI's **Tools ▸ SHACL Constraints ▸ Validate SHACL shapes**, and both use `ShaclShapeCheck` in Core.

`rdfs2shacl --validate-shapes` runs the same check, but only on the shapes it has just generated. `shacl-check` takes any files.

Not exposed through `serve`, `mcp` or `run`.

---

## Quick example

```
java -jar CimPal-CLI.jar shacl-check --shacl-files C:\Shapes\EQ.ttl,C:\Shapes\SSH.ttl
```

```
[OK] EQ.ttl: conforms to SHACL-SHACL
[FAIL] SSH.ttl: 1 problem(s)
  - ssh:Line.r-cardinality sh:minCount [sh:Violation] Value does not have datatype xsd:integer (value: one)
2 file(s) checked, 1 not conforming.
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file (key `shaclFiles`, an array). Flags override it; relative paths resolve against its folder. |
| `--shacl-files` | comma-separated paths | — | Shape files: `.ttl` (Turtle, also when the extension is unknown) or `.rdf` (RDF/XML). JSON-LD is refused. **Required.** |
| `--dry-run` | flag | off | Print the resolved configuration and exit. |

Every file is read before anything is checked. A file that can't be read or parsed is bad input (exit 2), never an empty model that "conforms". The GUI used to load such a file as an empty model and report that it conformed.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Every file conforms |
| 1 | At least one file does not conform (a result, not a failure) |
| 2 | Bad input: no files given, a file not found, unreadable, unparseable or JSON-LD, or an unreadable config |
| 3 | Internal error |
