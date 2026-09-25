# `gen-instances` — Instance Data Generation Command

Generates CIM/RDF instance data (RDF/XML) from a CimPal Excel template. The Excel template defines the class instances, their properties, and their relationships using the CimPal "Advanced template" format. The output is a CGMES-compatible RDF/XML file.

---

## Quick examples

**Generate from an Excel template:**
```
java -jar CimPal-CLI.jar gen-instances ^
  --template-xlsx C:\Templates\my-network.xlsx ^
  --output C:\Data\EQ.xml ^
  --xml-base "http://iec.ch/TC57/CIM100"
```

**For CGMES 2.4 (CIM16):**
```
java -jar CimPal-CLI.jar gen-instances ^
  --template-xlsx C:\Templates\my-network.xlsx ^
  --output C:\Data\EQ.xml ^
  --xml-base "http://iec.ch/TC57/2013/CIM-schema-cim16"
```

**With stripped prefixes:**
```
java -jar CimPal-CLI.jar gen-instances ^
  --template-xlsx C:\Templates\my-network.xlsx ^
  --output C:\Data\EQ.xml ^
  --strip-prefixes
```

**From a config file:**
```
java -jar CimPal-CLI.jar gen-instances --config configs/gen-instances.json
```

**Preview what would run:**
```
java -jar CimPal-CLI.jar gen-instances --config configs/gen-instances.json --dry-run
```

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--config` | file path | — | JSON config file. Individual flags override it. |
| `--template-xlsx` | file path | — | CimPal Excel generation template (`.xlsx`). **Required.** See **Template format** below. |
| `--output` | file path | — | Output RDF/XML file path. **Required.** The filename (without extension) becomes the model name embedded in the header. |
| `--xml-base` | URI | `http://iec.ch/TC57/CIM100` | Base URI for the generated model. Must match the CGMES version. |
| `--strip-prefixes` | flag | off | Remove unused namespace prefix declarations from the output. Produces smaller, cleaner files. |
| `--export-extensions` | flag | off | Include columns marked as `IsExtension` in the template. Off by default — extensions are not standard CGMES. |
| `--dry-run` | flag | off | Print the resolved config and exit without generating output. |

---

## Template format

The Excel template uses the **CimPal Advanced template** format. This is the same format the GUI's "Generate Instance Data" tab uses when "Advanced template" is selected.

The workbook must have:
- A **Config sheet** (named exactly `Config`) defining:
  - Namespace prefix → URI mappings (columns A, B, C where C is "Yes"/"No")
  - The class names to process (columns A and E)
- **One sheet per class** named exactly after the class (e.g. `ACLineSegment`, `VoltageLevel`)
  - Column headers define property URIs
  - Data rows contain property values
  - An `rdf:id` column must be present in the header sheet

Generating templates from an RDFS profile (for hand-filling) can be done with the GUI's "Generate template from RDF" button in the Generate Instance Data tab.

---

## XML base URI

| CGMES version | XML base URI |
|---|---|
| CGMES 3.0 (CIM17) | `http://iec.ch/TC57/CIM100` (default) |
| CGMES 2.4 (CIM16) | `http://iec.ch/TC57/2013/CIM-schema-cim16` |
| Stable CIM (ucaiug) | `https://cim.ucaiug.io/ns` |

---

## Use in the shape development loop

`gen-instances` is designed to work with the SHACL tester as a fast fixture loop:

1. Write or edit SHACL shapes in `.ttl`
2. Generate small conforming and non-conforming instance data with `gen-instances`
3. Validate with `validate --workflow manual --shacl-files shapes.ttl --models generated/`
4. Fix shapes and repeat

This avoids using real grid data for shape development.

---

## JSON config format

```json
{
  "_comment": "Template: Generate CIM instance data from a CimPal Excel template.",
  "_how_to_use": "Copy to your working directory. Replace REPLACE_WITH_PATH values. Run: java -jar CimPal-CLI.jar gen-instances --config this-file.json",

  "templateXlsx": "REPLACE_WITH_PATH/my-network.xlsx",
  "_note_templateXlsx": "CimPal Advanced template Excel file. Must have a 'Config' sheet and one sheet per class.",

  "output": "REPLACE_WITH_PATH/EQ.xml",
  "_note_output": "Where to write the generated RDF/XML file. The filename (without .xml) becomes the model name.",

  "xmlBase": "http://iec.ch/TC57/CIM100",
  "_note_xmlBase": "Base URI for the model. For CGMES 3.0/CIM17: http://iec.ch/TC57/CIM100. For CGMES 2.4/CIM16: http://iec.ch/TC57/2013/CIM-schema-cim16.",

  "stripPrefixes": false,
  "_note_stripPrefixes": "true removes unused namespace prefixes from the output. Produces cleaner files.",

  "exportExtensions": false,
  "_note_exportExtensions": "true includes extension columns (IsExtension=Yes). Use only for non-standard outputs."
}
```

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Instance data generated successfully |
| 2 | Bad input (file not found, required field missing) |
| 3 | Internal error (Excel parse failure, model-building error, write error) |
