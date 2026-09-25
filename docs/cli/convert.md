# `convert` — RDF Format Conversion Command

Converts RDF model files between RDF/XML, Turtle, and JSON-LD formats. Also supports merging multiple files into one (model union) and various RDF/XML sub-format options for CGMES-specific serialisation.

---

## Quick examples

**RDF/XML to Turtle (auto-detect formats from extensions):**
```
java -jar CimPal-CLI.jar convert ^
  --input C:\Data\EQ.xml ^
  --output C:\Data\EQ.ttl
```

**Turtle to canonical CGMES RDF/XML:**
```
java -jar CimPal-CLI.jar convert ^
  --input C:\Data\EQ.ttl ^
  --output C:\Data\EQ.xml ^
  --target-format RDFXML ^
  --rdf-format CIMXML ^
  --xml-base "http://iec.ch/TC57/CIM100"
```

**Merge multiple files into one Turtle file:**
```
java -jar CimPal-CLI.jar convert ^
  --input-files C:\Data\EQ.xml,C:\Data\TP.xml,C:\Data\SV.xml ^
  --output C:\Data\merged.ttl
```

**From a config file:**
```
java -jar CimPal-CLI.jar convert --config configs/convert.json
```

**Check what would run:**
```
java -jar CimPal-CLI.jar convert --config configs/convert.json --dry-run
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--input` | file path | — | Single source file. Use this OR `--input-files`, not both. |
| `--input-files` | comma-separated paths | — | Multiple source files merged into one output (model union). |
| `--output` | file path | — | Output file path. Required. Format auto-detected from extension. |
| `--source-format` | `RDFXML` / `TURTLE` / `JSONLD` | auto | Source format. Defaults to auto-detection from input extension. |
| `--target-format` | `RDFXML` / `TURTLE` / `JSONLD` | auto | Target format. Defaults to auto-detection from output extension. |
| `--xml-base` | URI | `""` (empty) | Base URI written into RDF/XML output and used when reading. For CGMES 3.0 use `http://iec.ch/TC57/CIM100`. For CGMES 2.4 use `http://iec.ch/TC57/2013/CIM-schema-cim16`. |
| `--rdf-format` | see below | `RDFXML_PLAIN` | RDF/XML sub-format. Only relevant when target is `RDFXML`. |
| `--sort` | flag | off | Sort triples in the output for deterministic, diffable files. |
| `--sort-by-prefix` | flag | off | Sort by namespace prefix rather than local name. Only meaningful with `--sort`. |
| `--strip-prefixes` | flag | off | Remove namespace prefix declarations from the output. |
| `--dry-run` | flag | off | Print the resolved config and exit without converting. |

---

## Format auto-detection

When `--source-format` or `--target-format` is omitted, the format is inferred from the file extension:

| Extension | Format |
|---|---|
| `.xml`, `.rdf` | `RDFXML` |
| `.ttl` | `TURTLE` |
| `.jsonld` | `JSONLD` |

Explicit flags always override auto-detection.

---

## RDF/XML sub-formats

Only applies when `--target-format RDFXML` (or when output extension is `.xml`/`.rdf`).

| Value | Description |
|---|---|
| `RDFXML_PLAIN` | Standard Jena RDF/XML, no abbreviations. Default. |
| `RDFXML_ABBREV` | Jena abbreviated RDF/XML (uses `rdf:resource` shorthand). |
| `RDFXML_PRETTY` | Jena pretty-printed RDF/XML. |
| `CIMXML` | CIM-specific RDF/XML (IEC 61970-552 style). Puts `FullModel` header first. Use for CGMES instance data output. |
| `RDFS_CIMXML` | RDFS-specific RDF/XML (IEC 61970-501 style). Use for profile/RDFS files. |

`CIMXML` and `RDFS_CIMXML` use the custom CimPal serializer that produces output matching what CGMES toolchains expect.

---

## Model union

When `--input-files` is used, all files are loaded and merged into a single combined RDF graph before conversion. Useful for:
- Producing a single Turtle file from a set of CGMES instance files
- Merging profile files before generating shapes
- Creating a baseline for comparison

The union operation is a simple graph merge — statements from all files are combined. Duplicate triples are deduplicated.

---

## Sorting for git-friendly diffs

When committing RDF files to git, use `--sort` to produce deterministic output. Without sorting, different Jena serialisation runs may produce different triple orderings, making diffs noisy.

```
java -jar CimPal-CLI.jar convert ^
  --input EQ.xml --output EQ.ttl --sort
```

Turtle output is naturally more compact and diff-friendly than RDF/XML for RDFS profiles and shape files.

---

## JSON config format

```json
{
  "_comment": "Convert EQ.xml to canonical CGMES Turtle",
  "input": "REPLACE_WITH_PATH/EQ.xml",
  "output": "REPLACE_WITH_PATH/EQ.ttl",
  "sourceFormat": "RDFXML",
  "targetFormat": "TURTLE",
  "xmlBase": "http://iec.ch/TC57/CIM100",
  "sort": true,
  "sortByPrefix": false,
  "stripPrefixes": false
}
```

For multi-file union:
```json
{
  "inputFiles": ["path/to/EQ.xml", "path/to/TP.xml"],
  "output": "path/to/merged.ttl",
  "sort": true
}
```

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Conversion succeeded |
| 2 | Bad input (missing file, no input specified, missing output) |
| 3 | Internal error (parse failure, write error) |
