# Shape Development Loop

A fast workflow for writing and testing SHACL shapes without running full validation against real grid models.

The idea: keep a folder of small test model archives (`.zip`), each designed to pass or fail a specific shape. Use the `validate --workflow manual` command to run your shapes against them immediately. The feedback loop is seconds, not minutes.

---

## The loop

```
edit shapes.ttl  →  validate --workflow manual  →  read output  →  repeat
```

---

## Setup

**1. Create a test models folder.**

You need at least two types of test models per shape:
- A **conforming** model — an instance that should pass the shape. Verifies the shape does not reject valid data.
- A **non-conforming** model — an instance that should fail the shape. Verifies the shape fires correctly.

```
test-models/
  MyShape-conform.zip       ← should produce 0 violations
  MyShape-nonconform.zip    ← should produce ≥ 1 violation
```

Each ZIP should contain one or more CGMES/RDF/XML instance files. They can be minimal — just enough triples to trigger (or not trigger) the shape.

**2. Have your shapes file ready.** A `.ttl` file containing the `sh:NodeShape` or `sh:PropertyShape` definitions you are working on.

---

## Run the test

```powershell
java -jar CimPal-CLI.jar validate `
  --workflow manual `
  --shacl-files "C:\Shapes\MyShape.ttl" `
  --models "C:\TestModels" `
  --datatype-map CGMES30NC25 `
  --xml-base "http://iec.ch/TC57/CIM100" `
  --workers 1 `
  --max-results 10 `
  --export-turtle
```

```bash
java -jar CimPal-CLI.jar validate \
  --workflow manual \
  --shacl-files "/shapes/MyShape.ttl" \
  --models "/test-models" \
  --datatype-map CGMES30NC25 \
  --xml-base "http://iec.ch/TC57/CIM100" \
  --workers 1 \
  --max-results 10 \
  --export-turtle
```

**What happens:**
- The CLI scans the `--models` folder (up to 3 levels deep) for `.zip` archives.
- Each ZIP is validated against all the shapes in `--shacl-files`.
- Progress and per-model results go to stderr.
- An Excel report is written beside each ZIP archive.
- If `--export-turtle` is set, a `.ttl` validation report is also written beside each ZIP.

---

## Using a config file for the loop

Save this as `dev-loop.json` in your shapes folder:

```json
{
  "_comment": "Fast shape development loop — edit this file and the .ttl, then re-run",

  "workflow": "manual",

  "shaclConstraintFiles": [
    "REPLACE_WITH_PATH/MyShape.ttl"
  ],
  "_note_shaclConstraintFiles": "Path(s) to the .ttl file(s) you are writing. Multiple files are unioned into one shapes graph, so you can split shapes across files and test them together.",

  "modelsDir": "REPLACE_WITH_PATH/test-models",
  "_note_modelsDir": "Folder containing .zip test model archives. The CLI scans up to 3 levels deep. Each .zip should contain RDF/XML CGMES instance files.",

  "datatypeMap": "CGMES30NC25",
  "_note_datatypeMap": "Match this to the CGMES version the test models use. CGMES30NC25 covers CIM17/CGMES3/NC2.5.",

  "xmlBase": "http://iec.ch/TC57/CIM100",
  "_note_xmlBase": "Must match the namespace in the test model files. For CIM17 this is http://iec.ch/TC57/CIM100.",

  "workers": 1,
  "_note_workers": "1 worker is enough for a small test set. Increase if you have many test models.",

  "maxResultsPerConstraint": 10,
  "_note_maxResultsPerConstraint": "10 is plenty for development — you just need to know the shape fires. Set to 0 for an exhaustive run.",

  "exportTurtle": true,
  "_note_exportTurtle": "true writes a .ttl SHACL report beside each model. Useful for debugging — the .ttl shows the exact focus node and constraint that fired."
}
```

Run:
```
java -jar CimPal-CLI.jar validate --config dev-loop.json
```

---

## Reading the output

**The result goes to stderr** (progress messages) and an Excel file beside each `.zip`.

A conforming model should produce:
```
[PROGRESS] 100%
✓ MyShape-conform.zip — 0 violations
```

A non-conforming model should produce:
```
[PROGRESS] 100%
✗ MyShape-nonconform.zip — 3 violations
  cim:VoltageLevel | sh:minCount | focus: <#_uuid_123>
  ...
```

*(Exact output format is in the Excel file; the stderr shows a summary.)*

---

## When the shape does not fire on a non-conform model

Common causes:

1. **Wrong datatype map.** If the shape checks a numeric range or boolean value and the data parses as plain strings, the constraint cannot fire. Confirm `--datatype-map` matches the model version. Check the Turtle report — if the literal appears without an XSD type annotation, the map is wrong.

2. **Wrong XML base.** If subjects resolve to different URIs than the shapes target, no focus nodes are found. Run a SPARQL query against the model to confirm the actual subject URIs:
   ```
   java -jar CimPal-CLI.jar sparql --models MyShape-nonconform.zip \
     --query "SELECT ?s ?type WHERE { ?s a ?type } LIMIT 10"
   ```

3. **Shape targets the wrong class.** Check `sh:targetClass` in your shape against what class the test instance actually declares via `rdf:type`.

4. **Shape path is wrong.** Check `sh:path` — the property URI must exactly match the predicate used in the data.

---

## Planned: synthetic instance generation [NOT YET IMPLEMENTED]

In a future CLI phase, the `gen-instances` command will generate minimal conforming and non-conforming instances directly from a SHACL shape and an RDFS profile. This removes the need to write test models by hand.

Until then, create test ZIPs manually or extract small subsets from real model archives.

---

## Promoting a shape to full validation

Once a shape passes its test models:

1. Move it to your shapes repository folder under `--constraints-root`.
2. Add a row to the mapping CSV pointing to the shape.
3. Run the full mapping validation: `java -jar CimPal-CLI.jar validate --config nightly.json`.
4. Confirm the shape produces the expected violations on real data and does not break conforming models.
