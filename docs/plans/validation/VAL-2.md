<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-2 — Profile validator

| Field | Value |
| --- | --- |
| Status | In review (branch local, not pushed; PR after VAL-1) |
| Phase | V |
| Depends on | VAL-1 |
| Size | L |
| Branch | `feature/val-2-profile-validator` |

## Goal

A Core builder API, `ProfileValidator` + `ProfileValidationOptions`, that validates a folder of instance files the way ReliCapGrid does: shapes chosen per file from PROF, files grouped so references resolve, counting and closed-shape constraints evaluated per file, everything else on the group, findings attributed to the file that holds them.

## Scope

- Core: `ProfileValidator`, `ProfileValidationOptions`, a result model (`ProfileValidationSummary` with groups, findings, skipped files, gaps, `complete`), group planning, Excel and Turtle report output.
- Core tests with synthetic fixtures; golden files under `snapshots/profile-validation`.
- `docs/PROJECT.md`.

Out of scope: CLI wiring (VAL-3), SARIF and line numbers (VAL-4), the schema layer.

## Acceptance criteria (status checklist)

- [x] Group planning per decision V-2: `folder` mode with boundary folders, and explicit groups. Every mapped file is reported in exactly one group; the plan (groups, reported, context, union shapes, dataset shapes per file) is available without running validation, for `--dry-run`
- [x] Shape selection per [README §4](README.md#4-shape-selection-per-group-shape_split): boundary shapes dropped outside the boundary group, common shapes added (V-5). Which shapes run per file is decided by content, not by file name (D3)
- [x] Two passes per [README §5](README.md#5-two-passes-validate_group), split on `sh:sourceConstraintComponent` (MinCount, MaxCount, Closed), through `SHACLValidator`
- [x] De-duplication on (focus node, path, value, component, source shape)
- [x] Attribution: each finding names the reported file(s) that contain its focus node; findings that belong only to context files are left out of the result but stay in the group's full report; findings with no file are reported once per run
- [x] Dependency check ([README §6](README.md#6-dependency-check)) as gap messages
- [x] Fail closed (V-4): unmapped profile or missing artifact is an error unless allowed; when allowed, the file is under `skipped` with its reason and the summary has `complete = false`. A failed `owl:imports`, or a shape whose SPARQL does not parse, is never a silent pass
- [x] No finding is reported twice across groups: a file that is context in several groups is reported only in its own group, and its per-dataset pass runs once
- [x] Outputs in the output folder: one Excel report (V-8: group and file columns), and with `exportTurtle` one full `sh:ValidationReport` per group including context findings
- [x] Works with every `ValidationEngine` by construction (V-6), **tested on `APACHE_JENA` only**; `maxResultsPerConstraint`, `workers` and `runStats` are honoured. Document how `maxResultsPerConstraint` interacts with the drop/keep filter (a cap applied before the filter can hide results)
- [x] The existing validators are untouched: `MappingValidatorTest` and `SHACLValidatorTest` golden files are byte-identical
- [x] Tests, each on a synthetic two-area + boundary data set built under `@TempDir`:
  - an object continued with `rdf:about` in a second file does **not** raise `maxCount` on the union, and a real duplicate inside one file does
  - a `sh:closed` list of profile A does not fire on properties that file B (profile B) adds to the same object
  - a `sh:class` / reference check across two files passes on the union and would fail per file
  - a boundary-only shape fires in the boundary group and not in an area group
  - a finding on a context file is not in the area's result, and is in the boundary group's result
  - a file with no declaration is skipped and listed; an unmapped URI fails, and with allow-unmapped is listed and `complete` is false
  - a missing dependency is a gap
  - a shape with invalid SPARQL surfaces as a finding or error
  - golden-file test of the whole summary

## Instructions for Claude Code

Start a session with: `Execute docs/plans/validation/VAL-2.md` (in plan mode).

```text
Read docs/plans/validation/README.md first: §3 to §6 are the specification, and decisions V-2, V-4, V-5, V-6, V-7 and V-8 are decided (README, Decisions), and the Generality section applies: no ReliCapGrid-specific name or layout in Core.

1. Fetch validate_instances.py (README, "What is being copied") and read shape_split, build_cgmes_groups, build_nc_groups and validate_group. Check the open assumption in README §5 about scope= against the triplets 0.2.0 source (PyPI or GitHub) and record what you find in the decisions log.
2. Read SHACLValidator, SHACLValidationOptions, SHACLValidationReport and SHACLValidationResult, and how the "combined" workflow in ValidateCommand uses them. Check whether a result carries sh:sourceConstraintComponent, focus node, path and value; if a field is missing, add it to the model without changing existing output (golden files must not move).
3. Design for cost before coding. Parse each instance file once and keep its model; build a group's union from those models; validate a file's per-dataset pass on its own model. Decide whether to strip per-dataset constraints from the union shapes before the run or to filter results after it: stripping is faster, filtering is the reference behaviour. If you strip, add a test that proves both give the same findings on the fixture.
4. Attribution: map each focus node to the files whose model has it as a subject. Do not re-scan files for this.
5. Tests first, from the acceptance list. The rdf:about-continuation and sh:closed cases are the reason this function exists: write them so they fail on a naive "validate the union against everything" run, and show that failure in the notes.
6. Memory: a group with a large EQ as context can be big. Report peak heap for the largest fixture you have through RunStats, and say in the notes what bounds it.
7. Concurrency: ValidationTools has static flags. Do not add static state; groups may run in parallel only if that is safe under serve --workers N.
```

Standard footer: see [`../README.md`](../README.md#standard-footer-for-every-wp-session) (`/wp-footer VAL-2` at the start, `/end-session VAL-2` at the end).

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-11-16 | Plan approved. The open assumption of README §5 is confirmed: the triplets 0.2.0 documentation says of `scope` that "out-of-scope data is not loaded", so the per-file pass validates each file alone. | Maintainer / Claude Code |
| 2026-11-16 | D1: classes. `core.utils.ProfileValidator` (beside `SHACLValidator`, whose package-private shape loader it uses), `core.models.ProfileValidationOptions` and `ProfileValidationSummary`, `core.profiles.ProfileGroupPlanner` and `ProfileGroup`. `plan()` gives the groups without validating. | Claude Code |
| 2026-11-16 | D2: grouping by folder (V-2): each first-level subfolder of the models folder is an area, documents directly in it are the area `(root)`. Within an area, documents with `md:Model.profile` and documents with `dcterms:conformsTo` are separate groups (`cgmes-<area>`, `nc-<area>`); the second takes the first as context. Boundary folders give `cgmes-boundary` / `nc-boundary`, context for all others. Explicit groups (`GroupSpec`: name, reported, context, boundary, extra shapes, assumed profiles for V-7) replace this. | Claude Code |
| 2026-11-17 | D3: **which shapes run per file is decided by content**: every shape file that applies to a document (its profiles' shapes, plus common and extra shapes) and holds `sh:minCount`, `sh:maxCount` or `sh:closed` takes part in that document's own pass. The reference's file-name filter (`-Con-Simple-`) is not used and there is no option for it: with it, such a constraint in any other file was dropped from the group pass and run nowhere (security review, High). Cost: a file that mixes cardinality with SPARQL constraints is evaluated once per document. | Claude Code |
| 2026-11-17 | D4: the split is on the exact component name (`MinCount`, `MaxCount`, `Closed`). `sh:qualifiedMinCount` / `sh:qualifiedMaxCount` stay on the group, as in the reference. | Claude Code |
| 2026-11-17 | D5: attribution is by focus node, read from the report's RDF (the result list shortens node names and cannot be looked up): the reported documents that hold the node as a subject, failing that as an object. Held only by context documents: not a finding of this group, counted in `GroupResult.contextFindings` and kept in the group's Turtle report. Held by no document: a finding without documents, once per run. | Claude Code |
| 2026-11-17 | D6: a SPARQL constraint or SPARQL target whose query does not parse is taken out, reported as one finding (component `cimpal:InvalidSparqlConstraint`) and makes the run incomplete; the rest runs. Jena otherwise rejects the whole shapes graph, and the real library is known to hold such a query. Shapes that still do not parse stop the run. | Claude Code (announced in the plan) |
| 2026-11-17 | D7: what makes a run **incomplete** (`complete = false`, so it never conforms): a document skipped for an unknown or incomplete profile (V-4), shapes that did not all load (for example an unresolved `owl:imports`), a constraint taken out (D6), a pass that checked nothing. A group carries the same flag, and its workbook rows and Turtle `sh:conforms` follow it. | Claude Code |
| 2026-11-17 | D8: a document that declares no profile is a listed skip and does **not** make the run incomplete, as decided in V-4 (reference data has none). The security review rated this High, because a model whose header was stripped is then passed over. Mitigation here: when no document at all can be validated the run is refused, and every skipped document has a non-conforming row in the workbook. **Left for the maintainer to confirm** (see *Open*). | Claude Code |
| 2026-11-17 | D9: group names of explicit groups are 1 to 80 plain characters and not a device name; two groups whose report files would have the same name are refused. Paths of explicit groups go through `PathPolicy` before use. The output folder is checked before the run starts. | Claude Code |

## Notes and results

**Implementation (2026-11-16/17).** Core: `ProfileValidator`, `ProfileValidationOptions`, `ProfileValidationSummary`, `ProfileGroupPlanner`, `ProfileGroup`; `ProfileCatalog.filesNamed`, `InstanceHeader.id`. No existing class changed behaviour; the golden files of `MappingValidatorTest` and `SHACLValidatorTest` are untouched.

**Test counts (`mvn -B verify -DexcludedGroups=gui`, Windows).** Before (VAL-1 branch): Core 764 (15 skipped). After: Core 790 (15 skipped; 26 in `ProfileValidatorTest`), Main 78, CLI 685 (10 skipped) + 3 + 37 ITs (21 skipped); build green. The golden files under `snapshots/mapping-validation` and `snapshots/shacl-validator` are unchanged; `snapshots/profile-validation/summary.txt` is new. Coverage floors not raised.

**The false findings the method avoids, shown failing.** `ProfileValidatorTest.validatingTheGroupAsOneDataSetAgainstAllItsShapesGivesTheFalseFindingsThisMethodAvoids` runs the naive validation (area A and the boundary as one data set against every shape of both profiles) and asserts that it reports five results that are wrong: `maxCount` on D1 because a second document adds a name, each profile's closed shape on D1 because the other profile adds properties, and `minCount` for `state` on D2 and D3, which the second profile never describes. The profile validator reports none of them and still reports the real duplicate inside one document (D2).

**Cost and memory.** Each document is parsed once, typed with the datatype map while parsing, and released after the last group that uses it; a group's data is a view over those models (`MultiUnion`), not a copy; parsed shape sets are cached per run. What bounds memory is the largest set of documents alive at once: a group's reported and context documents plus the boundary, which is context for every group and stays for the whole run. Peak heap was not measured on real data (the fixtures are tiny); `RunStats` gets the triples and bytes loaded.

**Result limit.** `maxResultsPerConstraint` applies to each pass before its results are filtered, so with a limit a constraint's results for a group may be fewer than the limit, or missing; the run is then partial and does not conform.

**Security review (security-reviewer agent, read-only).** Two High, both fixed or decided: cardinality and closed-shape constraints outside the name-filtered files were never evaluated (fixed, D3); a document without a profile is skipped while the run stays complete (decided in V-4, mitigated, D8). Medium, fixed: qualified counts matched by name suffix (D4); context-only results dropped without trace (now counted, D5); workbook rows and Turtle `sh:conforms` ignoring incompleteness (D7); a pass that checked nothing; a result that cannot be read back is now an error; the request-supplied regular expression is gone with D3; broken constraints are taken out of a copy of the shapes, never of a model another run may share. Low, fixed: message added to the de-duplication key; group names (D9); the workbook file that is written is the file that is checked; output folder checked first; folders not read are listed as gaps; skip records and group names sanitised; only planned archive entries are parsed. **Not fixed, recorded in PROJECT.md *Known issues*:** `ValidationExcelWriter` does not neutralise formula-like cell text (existing writer, used by all validation reports); no server-side cap on results or workers (belongs to the serve/mcp wiring, VAL-3).

**Not covered by a test.** A per-document pass that checks nothing (the flag is set, no fixture produces it); engines other than Jena; a symlink planted at the workbook's name.

**Open.**
- Maintainer: confirm D8, or make a document without a profile count as incomplete (one line in `ProfileGroupPlanner.member`), perhaps only outside a list of reference-data folders.
- A run on real data (an application-profiles-library checkout and a set of instance files) has not been done; it is the first thing that would show a problem, including the cost of D3 on the real Complex files.
