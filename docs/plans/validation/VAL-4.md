<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-4 — SARIF and run summary

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | V |
| Depends on | VAL-3 |
| Size | M |
| Branch | `feature/val-4-sarif` |

## Goal

The result of a profiles run can be uploaded to GitHub code scanning, as ReliCapGrid does: findings grouped by rule, each pointing at a file and line, so alerts close when the data is fixed and open on a regression. A Markdown summary of the run comes with it.

## Scope

- Core: a SARIF 2.1.0 writer for the `ProfileValidationSummary`, source-line lookup for a focus node in a CIMXML file, a `summary.md` writer.
- CLI: `--sarif <file>` and `--summary-md <file>` on `validate --workflow profiles` (config `sarifFile`, `summaryMarkdown`), `PathGuard`, `CommandSchemas`, OpenAPI.
- Docs: `docs/cli/validate.md`, `docs/cli/ci-pipeline.md` (a GitHub Actions example with `github/codeql-action/upload-sarif` and `security-events: write`), `docs/PROJECT.md`.

## Acceptance criteria (status checklist)

- [ ] The SARIF file validates against the SARIF 2.1.0 schema (test with a vendored copy of the schema, no network) and is accepted by GitHub's upload limits: check the current limits on results, locations per result and file size in GitHub's documentation, and cap with a clear note in the output when exceeded
- [ ] One rule per (source shape, constraint component); `ruleId` is stable across runs (derived from the shape IRI's local name and the component, as in the reference: e.g. `IdentifiedObject.mRID-cardinality/sh:minCount`); rule help text carries `sh:message` / `sh:description`
- [ ] Level from `sh:resultSeverity`: Violation → `error`, Warning → `warning`, Info → `note`
- [ ] Each result has locations with a repository-relative `artifactLocation.uri` (relative to a `--sarif-base` folder, default `--models`, forward slashes) and the `startLine` of the element that declares the focus node (`rdf:ID` or `rdf:about`) in that file; an `occurrenceCount` property per grouped result
- [ ] Findings without a file are anchored to the first reported file at line 1, as the reference does, and say so in the message
- [ ] Line lookup is a streaming pass (StAX), one pass per file for all its focus nodes, with XML external entities and DTDs disabled; ZIP members are located as `archive.zip` plus the member name in the message, since code scanning cannot point inside an archive (document this)
- [ ] `partialFingerprints` are set so the same finding keeps its identity when lines shift (focus node + rule), and a test shows a moved element keeps its fingerprint
- [ ] `summary.md`: the group table (files, errors, warnings, notes, seconds), rules with occurrence counts, skipped files with reasons, gaps, and the method notes
- [ ] All text copied from data into SARIF or Markdown is escaped (Markdown table cells, JSON), with a test using a literal that contains `|`, a newline and a control character
- [ ] `PathGuard.FIELDS` has `sarifFile` and `summaryMarkdown` as WRITE_FILE; schemas, OpenAPI and the Airflow mirror are in step; written atomically (`AtomicFiles`)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/validation/VAL-4.md` (in plan mode).

```text
Read docs/plans/validation/README.md and the notes of VAL-2 and VAL-3.

1. Reference behaviour: write_summary() and export_release() in validate_instances.py, and the to_sarif / locate functions of triplets 0.2.0 (look at its source for the exact SARIF shape: grouping, ruleId, occurrenceCount). The ReliCapGrid workflow .github/workflows/shacl_validation.yml at commit 5fee55c7 shows the upload step and categories.
2. Before designing, read GitHub's current SARIF support documentation for the supported properties, the fingerprint rules and the upload limits; put the numbers you find in the decisions log with the date.
3. Tests first: schema validity, stable ruleId, line numbers on a fixture with rdf:ID and rdf:about elements, the moved-element fingerprint, escaping.
4. Use Jackson 3 (tools.jackson) for the JSON, as the CLI does. No SARIF library dependency unless it is clearly worth a new module requirement; say why in the plan.
5. Run /security-check-change: this WP adds an XML parser path and two write paths.
6. Finish with a manual check I can repeat: the commands to produce a SARIF from a small sample and what to look for.
```

Standard footer: see [`../README.md`](../README.md#standard-footer-for-every-wp-session) (`/wp-footer VAL-4` at the start, `/end-session VAL-4` at the end).

## Decisions log

| Date | Decision | By |
| --- | --- | --- |

## Notes and results
