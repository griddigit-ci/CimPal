<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Validation track: profile-driven (PROF) validation

WP prefix `VAL`. Status of each WP is kept only in the master table in [`../README.md`](../README.md).

This track adds a **new** validation function next to the existing ones. `mapping`, `timestamped` and `combined` stay exactly as they are; no golden file of `MappingValidatorTest` or `SHACLValidatorTest` may change.

| ID | Work package | Goal |
| --- | --- | --- |
| [VAL-1](VAL-1.md) | Profile catalog and header reader | Core: map a declared profile URI to its SHACL files through DX-PROF; read each instance file's declared profiles |
| [VAL-2](VAL-2.md) | Profile validator | Core: grouping, the two-pass run, attribution to files, dependency check, reports |
| [VAL-3](VAL-3.md) | CLI, serve, MCP, run | `validate --workflow profiles` and every machine-readable contract |
| [VAL-4](VAL-4.md) | SARIF and run summary | Code-scanning output with file and line, `summary.md` |

Order: VAL-1 → VAL-2 → VAL-3 → VAL-4, one session and one PR each. VAL-4 can be dropped or postponed without affecting the others.

## What is being copied, and from where

The method is the one ENTSO-E's ReliCapGrid repository uses to validate all of its instance files ([issue 381](https://github.com/entsoe/ReliCapGrid/issues/381), merged as [PR 387](https://github.com/entsoe/ReliCapGrid/pull/387)). The executable reference is two Python scripts at merge commit `5fee55c758f27fc37412f95cd3f4a86bdf6a4645`:

- `buildScripts/prof_map.py` (115 lines): profile URI → SHACL files.
- `buildScripts/validate_instances.py` (381 lines): grouping, the two passes, reporting.

Fetch them when a detail below is unclear (`gh api "repos/entsoe/ReliCapGrid/contents/buildScripts/<file>?ref=5fee55c7…" -H "Accept: application/vnd.github.raw"`). They are a reference for behaviour, not code to translate line by line: they use pandas and the `triplets` library, CimPal uses Jena.

Two further documents give the reasoning, not an algorithm:

- [`conformance/README.md`](https://github.com/Haigutus/application-profiles-library/tree/docs/conformance-proposal/conformance) (a proposal): PROF is the catalog that says which RDFS and SHACL files belong to a profile, and a validator should walk PROF instead of being told the file list. It also proposes a W3C-style test manifest (`mf:Manifest`, `sht:Validate`) with negative cases made by applying a delta. That manifest runner is **out of scope** here: the proposal has six open points and a placeholder namespace. See *Later*.
- [`validate/README.md`](https://github.com/Haigutus/application-profiles-library/tree/docs/conformance-proposal/validate): checks the shapes themselves (RDF syntax, and the syntax of every `sh:select`). It found shapes with invalid SPARQL. Consequence for this track: a shape whose SPARQL does not parse must be reported as a finding, never skipped silently.

## The algorithm (normative for VAL-1 and VAL-2)

### 1. Profile catalog (`prof_map.py`)

Input: one or more folders holding a checkout of the application-profiles-library (APL). Layout: `<family>/PROF/*.rdf`, `<family>/SHACL/`, `<family>/RDFS/`, with family `CGMES` or `NCP`.

For each PROF file, find the `prof:Profile` node (`http://www.w3.org/ns/dx/prof/Profile`) and build one catalog entry:

- **Match keys:** the profile node's own IRI when it is an http(s) IRI, plus every `owl:versionIRI` and `owl:priorVersion` value in that file.
- **Shapes:** for each `prof:hasResource` descriptor, keep it only when `prof:hasRole` ends with `role/constraints` **and** `dcterms:conformsTo` is `http://www.w3.org/ns/shacl`. Take each `prof:hasArtifact`, reduce it to its basename (CGMES artifacts are bare file names, NCP ones are absolute URLs; neither resolves as a relative IRI), and look for that file in `<family>/SHACL/`, then `<family>/RDFS/`.
- **Gaps:** a PROF file without a `prof:Profile` node, a profile with no resolvable SHACL artifact, and each artifact that is not found are recorded as gap messages. They are reported, they do not stop the run.

`role/validation` resources and the variant shape sets (SolvedMAS/NotSolvedMAS, CrossProfile, InverseAssociation) are not used.

### 2. Instance headers (`scan_instances`)

Parse each instance file once. Its declared profiles are:

- `md:Model.profile` values (`http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile`) → kind **cgmes**;
- `dcterms:conformsTo` values that start with `https://ap.cim4.eu/` → kind **nc**.

A file with neither is **skipped** with the reason "no application profile declared" (in ReliCapGrid these are reference-data files). Skipped files are always listed in the output.

### 3. Groups (`build_cgmes_groups`, `build_nc_groups`)

A group has **reported** files (their findings are output) and **context** files (loaded only so cross-file references resolve). A file is reported in exactly one group.

ReliCapGrid hard-codes its folder layout (`Instance/<area>/…`):

| Group | Reported | Context |
| --- | --- | --- |
| `cgmes-<area>` | the area's CGMES files | boundary and common data |
| `cgmes-boundary` | boundary and common data | none |
| `cgmes-CGM-<name>` (assembled model) | the CGM's TP/SV/SSH | every area's EQ, plus boundary |
| `nc-<area>` | the area's NC files | the same area's CGMES files, plus boundary and common data |

CimPal must not hard-code a repository layout; see decision V-2.

### 4. Shape selection per group (`shape_split`)

For each reported file, the shapes are the union of the catalog entries of its declared profile URIs.

- **EquipmentBoundary shapes** (file name contains `EquipmentBoundary`; they are bundled into the EQ profile's PROF) are dropped, except in the boundary group. They restrict Terminal and ConnectivityNode to boundary-legal classes and produced about 28,000 false findings on full models.
- **Common shapes** that no PROF references are added to every CGMES group: `CGMES/SHACL/61970-600-1_AllProfiles-AP-Con-Complex-SHACL.ttl`, when the file exists (APL gap, application-profiles-library issue 130).
- **Union shapes** of the group = all shapes of all its reported files (plus common).
- **Dataset shapes** of a file = those of its own shapes whose file name contains `-Con-Simple-` or equals `DatasetMetadata-AP-Con-SHACL.ttl`. This is only a cost filter, so the SPARQL-heavy Complex shapes are not re-run once per file.

### 5. Two passes (`validate_group`)

The **per-dataset constraint components** are `sh:MinCountConstraintComponent`, `sh:MaxCountConstraintComponent` and `sh:ClosedConstraintComponent`.

1. **Union pass.** Data = all files of the group (reported and context) merged. Shapes = the union shapes. **Drop** every result whose `sh:sourceConstraintComponent` is a per-dataset component.
2. **Per-dataset pass.** For each reported file with dataset shapes: data = that file alone, shapes = its dataset shapes. **Keep only** results whose component is a per-dataset component.

Why: counting on the union sees the `rdf:about` continuation of an object in sibling files (false `maxCount` on mRID and name), and each profile's `sh:closed` *AllowedProperties* list is only valid for datasets of that profile (about 4,300 false findings when evaluated across profiles).

Then:

- **Deduplicate** on (focus node, result path, value, constraint component, source shape).
- **Attribute** each finding to the file(s) that hold it, and **report** it only when that file is a reported file of the group. A finding that cannot be attributed to any file (a shape-level problem such as invalid SPARQL) is reported once per run, not once per group.

Assumption to verify in VAL-2: in `triplets` 0.2.0, `validate(shapes, scope=[instance_id])` restricts the *data* to that instance's triples. The comments in the script say so ("counting must not see the rdf:about continuation of other files"); confirm against the library before relying on it.

### 6. Dependency check

Every `md:Model.DependentOn` (CGMES) and `dcterms:requires` (NC) edge declared in a header must point at a model that was loaded. A missing one is a gap message.

### 7. Not copied

- The **schema layer** (`validate_schema` against a `triplets` export schema): it needs export schemas that CimPal does not have. See *Later*.
- The duplicate-folder skip (`Instance/Jotunheim/NetworkCode/*.xml`) and the fixed group names: repository-specific.

## Generality (requirement, maintainer 2026-11-16)

This is a general CimPal function, not a ReliCapGrid runner. ReliCapGrid and the APL are the reference and the first test case; nothing in Core may depend on their folder names, group names, file counts or release list. Concretely:

- **Catalog:** any folder that holds DX-PROF descriptors works. PROF files are discovered recursively (an RDF file that contains a `prof:Profile`), not only under `<family>/PROF/`. An artifact is resolved first as an IRI relative to its PROF file, then by basename under the same library root; the APL layout (`SHACL/`, then `RDFS/`, beside `PROF/`) is the tie-breaker when a basename occurs more than once, and an ambiguity that it cannot settle is a gap, not a guess.
- **Profile declarations:** every `md:Model.profile` value is a declaration. A `dcterms:conformsTo` value is a declaration when it is a catalog key or starts with one of the profile namespaces (`--profile-namespace`, default `https://ap.cim4.eu/`, repeatable). Other `conformsTo` values are ignored.
- **Groups:** only through the general rules of V-2 (folders, boundary folders, explicit groups). No built-in area or CGM names.
- **Workarounds for APL gaps** (V-5) are named defaults that can be changed or switched off, and are documented as such.
- **Tests** use synthetic profiles with made-up IRIs and file names, so they cannot pass by accident of the APL's naming. A real APL checkout is only used by the optional comparison test.

## Mapping onto CimPal

- New code goes in **Core** (`eu.griddigit.cimpal.core.*`), wired into the CLI; nothing in `ValidationTools`. Use `SHACLValidator` + `SHACLValidationOptions` (`dataModel`/`shapesModel` or `dataFiles`/`shapeFiles`, `constraintsRoot`, `engine`, `workers`, `maxResultsPerConstraint`, `runStats`) for each pass, so engines, `owl:imports` handling, the `PathPolicy` checks and the datatype map are shared with `combined`.
- `ManifestGenerator` and `ModelFactory` already read `md:Model.profile` and `dcterms:conformsTo`; look there before writing a header reader, and reuse or extract instead of duplicating.
- False-clean is the worst outcome (CLAUDE.md, *Gotchas*): an unresolved import, an unmapped profile, a missing artifact or an unparseable shape must be visible in the result, and the totals must say the run was incomplete.
- `ValidationTools`' static flags mean anything concurrent needs care (`serve --workers N`).

## Decisions

All nine were accepted as recommended by the maintainer on 2026-11-16, together with the generality requirement above. The last column names the WP that implements each; a WP that needs to depart from one asks first and records it in its decisions log.

| # | Question | Decision | Implemented in |
| --- | --- | --- | --- |
| V-1 | New command or new workflow? | A fourth workflow of `validate`: `--workflow profiles`. It inherits `--engine`, `--datatype-map`, `--xml-base`, `--workers`, `--max-results`, `--samples`, `--export-turtle`, `--summary-file`, `--violations-exit-code`, `--stats`, and is reachable from `serve`, `mcp`, `run`, the Python SDK and the Airflow provider without a new command name in the OpenAPI enum or the generated client. The three old workflows are untouched. | VAL-3 |
| V-2 | How are groups defined? | Two modes. Default `--group-by folder`: each first-level subfolder of `--models` is a group; files in the folders named by `--boundary` (repeatable, relative to `--models`) form the boundary group and are context for every other group; NC files take the CGMES files of their own folder as context. Explicit: a `groups` array in the config (`name`, `reported`, `context`, `boundary: true/false`), which also covers the assembled-CGM case. Grouping by `Model.DependentOn` closure is left for later. | VAL-2 |
| V-3 | Where does the catalog come from? | `--profiles-library <dir>` (config `profilesLibrary`, repeatable): local APL checkouts, e.g. one for CGMES 3.0 and one for an NCP release. Keys are merged; the same key in two folders with different shapes is bad input (exit 2). No download by CimPal. | VAL-1 |
| V-4 | Unmapped profile URI, missing artifact? | Fail closed by default: bad input, exit 2, naming the URI and file. `--allow-unmapped` turns it into a skip that is listed under `skipped` and sets `complete: false` in the JSON. A file with no profile declaration at all is always a listed skip. | VAL-2 |
| V-5 | EquipmentBoundary and common shapes: hard-coded? | Defaults that mirror ReliCapGrid, each overridable: `--boundary-shapes-pattern` (default `EquipmentBoundary`), `--common-shapes` (default: the AllProfiles Complex file when present in a library). Document both as workarounds for APL gaps. | VAL-2 |
| V-6 | Which engines? | All that `SHACLValidator` supports, since the split is done on the result's `sh:sourceConstraintComponent`. Golden tests on `APACHE_JENA` only. | VAL-2 |
| V-7 | Boundary `dcat:Dataset` headers that declare no profile | Skip and list, as for any file without a declaration. ReliCapGrid's later port validates them as CoreEquipment-EU/3.0, which it calls a documented hard-code; offer that only through explicit config (`assumeProfile` on a group), not as a default. | VAL-2 |
| V-8 | Excel output? | Yes, through `ValidationExcelWriter`, with a group and a file column, so the result can be handled like the existing reports. | VAL-2 |
| V-9 | GUI tab? | Not in this track. Core is GUI-free by design, so a tab can follow as its own WP. | — |

## Later (not planned as WPs yet)

- **Schema layer:** RDFS conformance per declared profile from the `role:vocabulary` artifacts of PROF (CimPal has the RDFS tooling; the reference uses `triplets` export schemas).
- **Conformance manifest runner:** `mf:Manifest` / `sht:Validate` rows, positive and negative, deltas as SPARQL Update or DifferenceModel, EARL reports. Wait until the proposal's open points OP-1 to OP-6 are settled.
- **Grouping by dependency closure** and **GUI tab** (V-2, V-9).
- **GitHub Action / CI recipe** that uploads the SARIF of VAL-4 to code scanning.
