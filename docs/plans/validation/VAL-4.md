<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-4 — SARIF and run summary

| Field | Value |
| --- | --- |
| Status | In review (branch `feature/val-4-sarif`) |
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

- [x] The SARIF file validates against the SARIF 2.1.0 schema (test with a vendored copy of the schema, no network) and is accepted by GitHub's upload limits: above 25,000 results the run stops with exit 2 instead of writing a file GitHub would reject (D2). **The 10 MB compressed file limit is not checked.**
- [x] One rule per (source shape, constraint component); `ruleId` is stable across runs (derived from the shape IRI's local name and the component, as in the reference: here `DeviceCard-name/MaxCount`); the rule title is `sh:name`, the description `sh:description`; the result message carries `sh:message`
- [x] Level from `sh:resultSeverity`: Violation → `error`, Warning → `warning`, Info → `note`
- [x] Each result has locations with a repository-relative `artifactLocation.uri` (relative to `--sarif-base`, default the working directory when it contains `--models`, else `--models`; forward slashes) and the `startLine` of the violated property inside the object, else the object's element; `occurrenceCount` per result
- [x] Findings without a file are anchored to the first reported file at line 1, as the reference does, and say so in the message
- [x] Line lookup is a streaming pass (StAX), one pass per file for all its focus nodes, with XML external entities and DTDs disabled; ZIP members are located as `archive.zip` line 1 plus the member name in the message (documented)
- [x] `partialFingerprints` are set so the same finding keeps its identity when lines shift (focus node + rule), and a test shows a moved element keeps its fingerprint
- [x] `summary.md`: the group table (files, errors, warnings, notes, seconds), rules with occurrence counts, skipped files with reasons, gaps, and the method notes
- [x] All text copied from data into SARIF or Markdown is escaped (Markdown table cells, JSON), with a test using a literal that contains `|`, a newline and a control character
- [x] `PathGuard.FIELDS` has `sarifFile` and `summaryMarkdown` as WRITE_FILE (and `sarifBase` as READ); schemas, OpenAPI and the Airflow mirror are in step; written atomically (`AtomicFiles`)

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
| 2026-11-17 | Plan approved: one SARIF result per rule and document by default. | Maintainer |
| 2026-11-17 | D1: what a result stands for. The reference (triplets 0.2.0) gives one result per rule for a whole release, with six sample locations; GitHub annotates only the first location, so that is one alert per rule that closes only when every occurrence is fixed. Per object can exceed GitHub's limit (one ReliCapGrid release had about 10,000 SHACL and 39,000 schema findings). Default `--sarif-mode rule`: one result per rule and document, at the earliest line in the file, with the count and up to five objects. `object` is available for small data. | Claude Code / maintainer |
| 2026-11-17 | D2: GitHub limits read 2026-11-17 from its SARIF support page: 10 MB gzip-compressed per file; 20 runs per file; 25,000 results per run (more: the file is rejected); 25,000 rules per run; 1,000 locations per result (100 kept); only `partialFingerprints.primaryLocationLineHash` is used for matching; a result needs a location with `region.startLine`; paths relative to the repository root. A run that would exceed 25,000 results is refused with exit 2, not truncated: a truncated file would show fewer findings than there are. The 10 MB limit is not checked. | Claude Code |
| 2026-11-17 | D3: the fingerprint is the first 16 bytes of SHA-256 over rule id, file and archive entry (and in `object` mode the object, path, value and message), as `primaryLocationLineHash` with `:1`. Never the line: an alert keeps its identity when the file is edited above it (tested). | Claude Code |
| 2026-11-17 | D4: a line is the line of the violated property inside the object, else the object's line, as the reference does. StAX reports the line at the end of a start tag; for CIM/XML's one-tag-per-line layout that is the element's line. | Claude Code |
| 2026-11-17 | D5: no SARIF library and no new Core dependency: the JSON is written by hand with an escaper that covers control characters, U+2028/2029 and lone surrogates; the CLI test checks the file against the official OASIS SARIF 2.1.0 schema (vendored as a test fixture, draft-04, 112 KB) with the JSON Schema validator the CLI tests already use. | Claude Code |
| 2026-11-17 | Security review (security-reviewer agent, read-only): no High. **Medium, fixed:** an incomplete or partial run gave a SARIF with fewer results, and code scanning closes the alerts an upload no longer reports, so it would read as a fix; such a run now adds one `error` result (`CimPal/IncompleteRun`) listing what was not checked, and `invocations[0].executionSuccessful` is false. **Low, fixed:** a SARIF or summary file of an earlier run is removed when this run cannot write its own; a file the line locator cannot read (a DOCTYPE) only loses its line numbers instead of failing the run, and its parse message is no longer quoted; the JDK StAX parser is pinned (`newDefaultFactory`); Markdown free text sits in code spans (no links, mentions, markup); `helpUri` only for well-formed http(s) addresses. Tests added for each, and for U+2028/2029 and lone surrogates in JSON. Not changed (Info): the default SARIF base under serve may show the server-relative job folder layout. | Claude Code |
| 2026-11-17 | D6: to locate findings the summary had to know each finding's documents and full IRIs: `ProfileValidationSummary.Finding` gained `headers`, `focusIri`, `pathIri`, and `GroupResult` gained `reportedDocuments` (VAL-2 types, changed here; their existing output is unchanged and the VAL-2 golden file did not move). Pass 2 now reads its results from the report RDF, like pass 1. | Claude Code |

## Notes and results

**Implementation (2026-11-17).** Core (`core.profiles`): `ProfileSarifWriter`, `XmlLineLocator`, `ProfileSummaryMarkdown`; `ProfileValidationSummary.Finding` and `GroupResult` extended (D6). CLI: `ValidateCommand` (`--sarif`, `--sarif-base`, `--sarif-category`, `--sarif-mode`, `--summary-md`), `PathGuard` (`sarifFile`, `summaryMarkdown` WRITE_FILE; `sarifBase` READ), `CommandSchemas`; OpenAPI and the Python client model regenerated (`regenerate.py --check` passes); Airflow `PATH_FIELDS` (its `test_logic.py`: 29 passed). Test fixture: the OASIS SARIF 2.1.0 schema, `fixtures/sarif/sarif-schema-2.1.0.json`. Docs: `docs/cli/validate.md`, `docs/cli/ci-pipeline.md` (GitHub Actions example), the config template.

**Test counts (`mvn -B verify -DexcludedGroups=gui`, Windows).** Before (VAL-3 branch): Core 790, Main 78, CLI 697 (10 skipped). After: Core 803 (13 in `ProfileSarifWriterTest`), Main 78, CLI 700 (10 skipped; 3 more in `ValidateProfilesWorkflowTest`) + 3 + 37 ITs (21 skipped); build green. The first full run failed the CLI coverage ratchet by 0.0002 (branch 0.7036 against 0.7038); a test of the per-object mode, the default base, the text output and the dry run brought it back. Floors not raised.

**Manual check you can repeat.** Run the workflow with `--sarif reports/cimpal.sarif --summary-md reports/summary.md` from the repository root of a data repository, then upload with `gh api repos/<owner>/<repo>/code-scanning/sarifs` or the GitHub Actions example in `ci-pipeline.md`, and look at *Security > Code scanning*: one alert per rule and file, pointing at the line; fix one object and re-run: the alert stays open while other objects in the file break the rule, and closes when none does.

**Not verified.** No upload to GitHub was made: the file is checked against the SARIF schema, not against GitHub's processing. The 10 MB compressed size limit is not checked. No run on real data.
