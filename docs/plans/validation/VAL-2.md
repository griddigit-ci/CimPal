<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-2 — Profile validator

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] Group planning per decision V-2: `folder` mode with boundary folders, and explicit groups. Every mapped file is reported in exactly one group; the plan (groups, reported, context, union shapes, dataset shapes per file) is available without running validation, for `--dry-run`
- [ ] Shape selection per [README §4](README.md#4-shape-selection-per-group-shape_split): boundary shapes dropped outside the boundary group, common shapes added, dataset shapes by file name (V-5)
- [ ] Two passes per [README §5](README.md#5-two-passes-validate_group), split on `sh:sourceConstraintComponent` (MinCount, MaxCount, Closed), through `SHACLValidator`
- [ ] De-duplication on (focus node, path, value, component, source shape)
- [ ] Attribution: each finding names the reported file(s) that contain its focus node; findings that belong only to context files are left out of the result but stay in the group's full report; findings with no file are reported once per run
- [ ] Dependency check ([README §6](README.md#6-dependency-check)) as gap messages
- [ ] Fail closed (V-4): unmapped profile or missing artifact is an error unless allowed; when allowed, the file is under `skipped` with its reason and the summary has `complete = false`. A failed `owl:imports`, or a shape whose SPARQL does not parse, is never a silent pass
- [ ] No finding is reported twice across groups: a file that is context in several groups is reported only in its own group, and its per-dataset pass runs once
- [ ] Outputs in the output folder: one Excel report (V-8: group and file columns), and with `exportTurtle` one full `sh:ValidationReport` per group including context findings
- [ ] Works with every `ValidationEngine` (V-6); `maxResultsPerConstraint`, `workers` and `runStats` are honoured. Document how `maxResultsPerConstraint` interacts with the drop/keep filter (a cap applied before the filter can hide results)
- [ ] The existing validators are untouched: `MappingValidatorTest` and `SHACLValidatorTest` golden files are byte-identical
- [ ] Tests, each on a synthetic two-area + boundary data set built under `@TempDir`:
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

## Notes and results
