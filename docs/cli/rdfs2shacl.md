# `rdfs2shacl` — RDFS to SHACL Generation Command

Generates SHACL shape files from RDFS CIM profile definitions exported by cimsyntaxgen or cimcontextor. Takes `.rdf` profile files as input and produces `.ttl` shape files.

---

## Quick examples

**Generate shapes from a single RDFS profile:**
```
java -jar CimPal-CLI.jar rdfs2shacl ^
  --rdfs-files C:\Profiles\EQ.rdf ^
  --output-dir C:\Shapes\generated
```

**Generate shapes from multiple profiles (one shape file per profile):**
```
java -jar CimPal-CLI.jar rdfs2shacl ^
  --rdfs-files C:\Profiles\EQ.rdf,C:\Profiles\TP.rdf,C:\Profiles\SV.rdf ^
  --output-dir C:\Shapes\generated
```

**With closed shapes and datatype split:**
```
java -jar CimPal-CLI.jar rdfs2shacl ^
  --rdfs-files C:\Profiles\EQ.rdf ^
  --output-dir C:\Shapes\generated ^
  --closed-shapes ^
  --split-datatypes
```

**From a config file:**
```
java -jar CimPal-CLI.jar rdfs2shacl --config configs/rdfs2shacl.json
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--rdfs-files` | comma-separated paths | — | RDFS profile `.rdf` files. One shape file is generated per input file. **Required.** |
| `--output-dir` | folder path | — | Directory where generated `.ttl` files are written. Created if it does not exist. **Required.** |
| `--rdfs-format` | `2019` / `2020` | `2020` | RDFS format version. `2020` = augmented RDFS with `owl:Ontology` header (current cimsyntaxgen output). `2019` = older augmented format without the header. |
| `--cims-namespace` | URI | `http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#` | The CIMS extensions namespace URI used to interpret RDFS metadata. Rarely needs changing for standard CIM profiles. |
| `--io-prefix` | string | `mRID` | Local name of the IdentifiedObject mRID property. Used internally to locate the mRID shape. |
| `--io-uri` | URI | `http://iec.ch/TC57/CIM100#IdentifiedObject.mRID` | Full URI of the IdentifiedObject mRID property. Adjust for CIM16: `http://iec.ch/TC57/2013/CIM-schema-cim16#IdentifiedObject.mRID` |
| `--shapes-base-uri` | URI | `""` | Base URI for the generated shape graphs. Written as the `owl:Ontology` subject in each shape file. If empty, no ontology header is added. |
| `--shapes-namespace-prefix` | string | `""` | Namespace prefix for generated shapes (e.g. `"cim17-eq-shapes"`). Used in prefix declarations. |
| `--shapes-namespace-uri` | URI | `""` | Namespace URI for generated shapes (e.g. `"http://example.org/shapes/EQ#"`). |
| `--closed-shapes` | flag | off | Generate `sh:closed true` on each node shape. Closed shapes reject any property not explicitly listed — use with care, as it makes shapes very strict. |
| `--split-datatypes` | flag | off | Write a separate `datatype-<name>.ttl` file for datatype constraints alongside the main shape file. Useful when the main shapes file is used without datatype validation. |
| `--export-inherit-tree` | flag | off | Write an `inheritance-<name>.ttl` file encoding the class inheritance relationships. |
| `--validate-shapes` | flag | off | Run SHACL-SHACL validation on the generated shapes (using the SHACL specification shapes) and print the results. Indicates whether the generated shapes are syntactically valid SHACL. |
| `--dry-run` | flag | off | Print resolved config and exit without generating. |

---

## Output files

For each input RDFS file, one or more files are written to `--output-dir`:

| File | Always? | Condition |
|---|---|---|
| `<ProfileName>.ttl` | Yes | Main shape file (e.g. `EQ.ttl`) |
| `datatype-<ProfileName>.ttl` | No | Only if `--split-datatypes` |
| `inheritance-<ProfileName>.ttl` | No | Only if `--export-inherit-tree` |

The `<ProfileName>` is derived from the input filename stem (e.g. `EQ.rdf` → `EQ`, `CoreEquipment.rdf` → `CoreEquipment`).

---

## What gets generated

For a standard augmented RDFS (v2020) profile, the command generates SHACL `sh:NodeShape` definitions for every non-abstract CIM class in the profile, with `sh:PropertyShape` entries for each attribute and association.

Cardinalities (`sh:minCount`, `sh:maxCount`) are derived from the RDFS `cims:multiplicity` annotations. Datatype constraints (`sh:datatype`) come from `rdfs:range` on the property. Association value type constraints use `sh:class`.

---

## Namespace auto-extraction

The command extracts namespace information from the `owl:Ontology` declaration in each RDFS file. If the profile has a well-formed ontology header (standard for cimsyntaxgen output), this works automatically.

If namespace extraction fails for a non-standard profile:
- Override `--shapes-namespace-prefix` and `--shapes-namespace-uri` explicitly
- Use `--cims-namespace` to set the CIMS extensions namespace if the profile uses a non-standard one

---

## CGMES version notes

**CGMES 3.0 (CIM17):**
- `--rdfs-format 2020`
- `--cims-namespace http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#` (default)
- `--io-uri http://iec.ch/TC57/CIM100#IdentifiedObject.mRID` (default)

**CGMES 2.4 (CIM16):**
- `--rdfs-format 2020` or `2019` depending on the export vintage
- `--io-uri http://iec.ch/TC57/2013/CIM-schema-cim16#IdentifiedObject.mRID`

---

## JSON config format

```json
{
  "_comment": "Generate SHACL shapes from CGMES 3.0 Equipment profile",

  "rdfsFiles": [
    "REPLACE_WITH_PATH/EQ.rdf",
    "REPLACE_WITH_PATH/TP.rdf"
  ],
  "_note_rdfsFiles": "One shape file is generated per input file. Paths can be absolute or relative to this config file.",

  "outputDir": "REPLACE_WITH_PATH/generated-shapes",
  "_note_outputDir": "Created if it does not exist. Existing files with the same name are overwritten.",

  "rdfsFormat": "2020",
  "_note_rdfsFormat": "Use 2020 for profiles exported by current cimsyntaxgen. Use 2019 for older exports.",

  "cimsNamespace": "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#",
  "_note_cimsNamespace": "The CIMS extensions namespace. Rarely needs changing for standard CIM profiles.",

  "ioPrefix": "mRID",
  "ioUri": "http://iec.ch/TC57/CIM100#IdentifiedObject.mRID",
  "_note_ioUri": "For CIM16: http://iec.ch/TC57/2013/CIM-schema-cim16#IdentifiedObject.mRID",

  "shapesBaseUri": "",
  "shapesNsPrefix": "",
  "shapesNsUri": "",

  "closedShapes": false,
  "_note_closedShapes": "true generates sh:closed shapes. Very strict — any undeclared property causes a violation.",

  "splitDatatypes": false,
  "_note_splitDatatypes": "true writes a separate datatype-<name>.ttl file alongside the main shape file.",

  "exportInheritTree": false,
  "validateShapes": false
}
```

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Shape generation succeeded |
| 2 | Bad input (no RDFS files, output dir not creatable) |
| 3 | Internal error (parse failure, SHACL generation error) |
