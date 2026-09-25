<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `sparql` — SPARQL Query Command

Executes a SPARQL SELECT query against one or more RDF model files. Outputs results as a text table, JSON, CSV, or an Excel file.

---

## Quick examples

**Query a model file, print results as a text table:**
```
java -jar CimPal-CLI.jar sparql ^
  --models C:\Data\EQ.xml ^
  --query "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20"
```

**Query from a file, save results to Excel:**
```
java -jar CimPal-CLI.jar sparql ^
  --models C:\Data\EQ.xml,C:\Data\TP.xml ^
  --query C:\Queries\inspect-voltages.sparql ^
  --output C:\Data\results.xlsx
```

**Query from a config file:**
```
java -jar CimPal-CLI.jar sparql --config configs/sparql-query.json
```

**Get JSON output for scripting:**
```
java -jar CimPal-CLI.jar sparql ^
  --models C:\Data\EQ.xml ^
  --query C:\Queries\count-violations.sparql ^
  --format json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--models` | comma-separated file paths | — | Model files to load. Accepts `.xml`, `.rdf`, `.ttl`, or `.zip` containing RDF/XML. Required. |
| `--query` | file path or inline string | — | SPARQL query. If the argument is a path to an existing file, it reads that file. Otherwise it is treated as an inline query string. Required. |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI for resolving relative URIs in the model files. See the validate docs for common values. |
| `--output` | file path | — | Write results to a file. Use `.xlsx` extension for Excel; any other extension for CSV. If omitted, results go to stdout. |
| `--format` | `text` / `json` / `csv` | `text` | Format for stdout output. Ignored when `--output` is set. |
| `--limit` | integer | `0` | Automatically append `LIMIT n` to queries that have none. `0` = do not add a limit. |

---

## Loading models

All model files in `--models` are merged into a single combined graph before the query runs. This lets you query across multiple CGMES instance files at once — for example EQ + TP + SV together.

Files are loaded using the same RDF/XML parser CimPal uses for validation, with the same `--xml-base` URI. ZIP archives are extracted and all RDF/XML files inside them are loaded.

Progress messages (file count, triple count) go to stderr so they do not pollute stdout output.

---

## Query file vs inline query

The `--query` argument is checked against the filesystem first. If the path exists as a regular file, its contents are read as the query. Otherwise the argument itself is used as an inline SPARQL string.

Store frequently-used queries as `.sparql` or `.rq` files alongside your data so they can be committed to git and version-tracked.

---

## Output formats

**`text`** (default) — a pipe-separated table printed to stdout:

```
subject | predicate | object
---------------------------------------
http://example.com/EQ_Line1 | rdf:type | cim:ACLineSegment
http://example.com/EQ_Line2 | rdf:type | cim:ACLineSegment
```

**`json`** — a JSON object with `columns` and `rows` arrays:

```json
{
  "columns": ["subject", "predicate", "object"],
  "rows": [
    {"subject": "http://example.com/EQ_Line1", "predicate": "rdf:type", "object": "cim:ACLineSegment"}
  ]
}
```

**`csv`** — RFC 4180 CSV with a header row. Quoted where needed.

**`--output results.xlsx`** — Excel workbook with one sheet, column headers in row 1.  
**`--output results.csv`** — CSV file written to disk.

---

## Large models and LIMIT

If a SELECT query has no LIMIT and the model is large (over 100k triples), the command prints a warning to stderr but still runs. Use `--limit 1000` to automatically cap any query that has no LIMIT. This does not affect queries that already include a LIMIT clause.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Query ran successfully |
| 2 | Bad input (no models, no query, file not found) |
| 3 | Internal error (RDF parse failure, SPARQL syntax error) |
