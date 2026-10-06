<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-2 — Regression tests for past security findings

| Field | Value |
| --- | --- |
| Status | In review ([PR #46](https://github.com/griddigit-ci/CimPal/pull/46)) |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-2-security-regressions` |

## Goal

Every remediation in SECURITY-SELF-ATTESTATION.md §4 (F1–F13, H1) has a test that fails if the fix is reverted.

## Scope

- Core and Main tests only; no production changes unless a finding turns out not to be remediated (then stop and report)

## Acceptance criteria (status checklist)

- [x] At least one test `F<n>_<short>` per finding
- [x] Finding → test mapping table filled in below
- [x] Any finding found not remediated is reported, not silently fixed

## Finding → test map

| Finding | Test |
| --- | --- |
| F1 | `SecurityRegressionTest`: `F1_githubCredentialIsNeverAttachedForAHostThatMerelyMentionsGitHub` (5 URL shapes), `F1_credentialHostsAreAnExactAllowlist`, `F1_credentialedClientNeverFollowsRedirects`. Existing: `ShapeSourceTest.classify_httpsHostMerelyMentioningAllowlistedDomain_refused`, `classify_embeddedCredentials_refusedByEgressPolicy` |
| F2 | `SecurityRegressionTest`: `F2_egressGateRefusesNonHttpsCredentialsAndUnlistedHosts` (6), `F2_egressGateAcceptsAnAllowlistedHttpsHost`, `F2_publicHostCheckRefusesInternalAddresses` (9), `F2_oversizedResponseBodyIsRefused`; `F2_refusedImportFailsTheRowInsteadOfPassing` is **disabled: not remediated** (finding 1 below). Existing: `ShapeSourceTest.loopbackRemoteImport_isRefusedAndNotFetched`, `metadataEndpointImport_isRefusedAndNotFetched`, `classify_plainHttp_…`, `classify_httpsNonAllowlistedHost_…`, `classify_protocolRelativeFromRemote_…` |
| F3 | `SecurityRegressionMainTest`: `F3_noSingleStringRuntimeExecAnywhere`, `F3_outputFolderIsOpenedThroughDesktopNotAShellCommand` |
| F4 | `SecurityRegressionTest`: `F4_comparisonCsvNeutralisesFormulaTriggers` (6 payloads); `SecurityRegressionMainTest`: `F4_shaclInformationCsvNeutralisesFormulaTriggers` (7), `F4_benignShaclInformationValuesAreUnchanged`. Existing: `ComparisonCsvWriterTest` (9) |
| F5 | `SecurityRegressionTest`: `F5_cacheFileNameCannotEscapeTheCacheDirectory` (5 URL shapes) |
| F6 | `SecurityRegressionTest`: `F6_cachesAndDebugLogLiveInThePerUserDirectoryNotTheSharedTemp`, `F6_privateDirectoryIsOwnerOnlyOnPosix` (POSIX only) |
| F7 | `SecurityRegressionTest`: `F7_entryCountLimitTrips`, `F7_nestingDepthLimitTrips`, `F7_brokenArchiveFailsInsteadOfYieldingAPartialModel`, `F7_onDemandZipInputHasAnEntryLimit`. The total-size limit (2 GiB) is not testable in a unit test; see finding 3 |
| F8 | `SecurityRegressionTest`: `F8_zipSlipEntryIsRefused` (3), `F8_destinationGuardIsAStrictNormalisedContainmentCheck` (Core copy); `SecurityRegressionMainTest`: `F8_destinationGuardIsAStrictNormalisedContainmentCheck` (the 3 Main copies) |
| F9 | `SecurityRegressionTest`: `F9_lineTerminatorsInLoggedValuesAreNeutralised` (CR, LF, NEL, LS, PS), `F9_loggedValuesAreBoundedAndUrlQueriesRedacted` |
| F10 | `SecurityRegressionTest`: `F10_noDeveloperWorkstationPathsInShippedSources` (source scan of all modules) |
| F11 | `SecurityRegressionTest`: `F11_noBarePrintStackTraceInCoreOrMain` (source scan); `SecurityRegressionMainTest`: `F11_taskDependenciesLoadAndAnUnknownTaskIsUnconstrained` |
| F12 | `SecurityRegressionTest`: `F12_noEndOfLifeOrSilencingDependenciesAndScannersStayConfigured` (poms and module-info) |
| F13 | `SecurityRegressionMainTest`: `F13_knowledgeSourcesMustBePublicCredentialFreeHttpsOnPort443` (10), `F13_retrievalNeverFollowsRedirectsAndIsSizeBounded`, `F13_ollamaAcceptsLocalHttpEndpointsOnly` (5), `F13_localOllamaEndpointIsAccepted`. Existing: `AiKnowledgeSearchSecurityTest` (4) |
| H1 | `SecurityRegressionMainTest`: `H1_helpThemeIsSetThroughTheDomNotExecuteScript`, `H1_noScriptIsBuiltFromDataInHelpOrAiViews` |

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-2.md` (in plan mode).

```text
Read SECURITY-SELF-ATTESTATION.md §4. For each finding F1–F13 and H1, write at least one test named `F<n>_<short>` that fails if the remediation is reverted. Examples: F1 credential never sent to a non-allowlisted host (StubHttpServer records headers); F2 owl:imports to loopback/private/non-allowlisted hosts refused and reported as an error, not a pass; F4 CSV cells starting with = + - @ tab CR are neutralised; F5 cache filename cannot escape the cache dir; F7 zip bomb / entry-count / total-size limits trip; F8 zip-slip entry rejected; F9 CR/LF in logged values escaped; F13 AI fetch refuses private addresses and redirects.
Before writing each test, verify the current code actually implements the remediation. If a finding is NOT remediated as described, stop and report it; do not fix silently.
Fill in the finding → test class#method table in this file.
```

Standard footer (applies to every work package):

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, known issues, next steps) and docs/cli/* if CLI behaviour changed. Summarise what changed and what is left open.

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-10-01 | The remediation helpers are private in `ValidationTools`, `ModelFactory`, `ExportSHACLInformation` and others. The tests reach them by reflection, not by widening visibility, so TEST-2 has no production changes. Structural fixes (F3, F10, F11, F12, H1) are pinned by source and build-file scans through a new test-support helper, `SourceScan`. | Claude Code |
| 2026-10-01 | Findings that turned out not to be remediated, or that apply to code added since the attestation, are reported below and not fixed, as the plan requires. The F2 "error, not a pass" test is committed but `@Disabled`, with the reason, so it can be switched on with the fix. | Claude Code |

## Notes and results

### Results 2026-10-01

**Tests:** 118 before TEST-2 on `devel` (Core 81, Main 32, CLI 5), 206 after (Core 136, Main 65, CLI 5). There are 88 new tests in `SecurityRegressionTest` (Core, 55) and `SecurityRegressionMainTest` (Main, 33), plus the test-support helper `SourceScan`. `mvn -B clean verify` is green on Windows.
- **Skipped (2):** the disabled F2 finding test, and the POSIX-only F6 check on Windows.
- **Reverted-fix check:** reverting F1 (redirect `NEVER`), F4 (Main formula neutralisation), F5 (splitting on both separators), F8 (normalised containment) or F9 (log neutralisation) fails the matching `F<n>_` tests every time.
- **Coverage:** Core line 28.4% → 29.1%, Main 4.7% → 7.6%. The floors were raised.

### Findings: not remediated, or regressed in later code (reported here, fixed in [SEC-5](SEC-5.md))

1. **High: a refused or unresolvable `owl:imports` gives a clean pass (F2, and the CLAUDE.md rule "never a silent pass").** If an import is refused by the egress gate or can't be resolved, the row prints a `[WARN]` and is validated without those shapes. In `F2_refusedImportFailsTheRowInsteadOfPassing`, the only shapes sit in a refused import, and the violating model is reported as **conforming**. The test is committed `@Disabled`; with the check enabled it fails (`conforming` expected 0, was 1). Fix: count an unresolvable or refused import as a row error, or as a "partial" status that is never reported as conforming. SEC-2 already made *network* imports fail the row.
2. **Medium: CSV formula injection in the CLI (F4).** `sparql` (`--output x.csv` and `--format csv`) and `compare` (`.csv` output and `--format csv`) write CSV with RFC-4180 quoting only. Cells starting with `=`, `+`, `-`, `@`, TAB or CR are not neutralised. These commands came after the attestation, which says "no other CSV emission path exists". Fix: reuse `ComparisonCsvWriter`'s neutralising escape.
3. **Medium: the archive limits are checked too late, and one path has none (F7).**
   - `ModelFactory.unzip` calls `ZipBudget.account` after `readAllBytes()`, so a single huge entry is read fully into memory before any limit applies. The 2 GiB total cap is also larger than a typical heap.
   - `ModelFactory.modelLoadPerFiles`, used by `sparql`, `compare-instances` and `manifest`, has no `ZipBudget` at all.

   Fix: count bytes while streaming each entry (like `ZipXmlEntry.openStream`'s limit), add a per-entry cap, and use the budget in `modelLoadPerFiles`. Note that the disk-extracting path the attestation names (`unzipXmlFiles`) no longer exists. Its replacement, `scanZipXmlEntries` with `ZipXmlEntry.openStream`, has entry, declared-size and streaming limits plus a zip-slip check, and is tested.
4. **Low: the public-host check misses some private ranges (F2).** `requirePublicHost` does not refuse IPv6 unique-local addresses (`fc00::/7`), because Java's `isSiteLocalAddress` only covers the deprecated `fec0::/10`, nor carrier-grade NAT (`100.64.0.0/10`). The AI gate (F13) does refuse ULA. Fix: share one `isPublicAddress` check between the two gates.

### Observations (no action needed)

- `RDFVisualisationController` builds the `executeScript` call for zoom by concatenating a number it computes itself, which is the H1 pattern but with no data in it. `H1_noScriptIsBuiltFromDataInHelpOrAiViews` allows exactly that exception.
- The CLI has 11 `printStackTrace(System.err)` calls. F11 covered Core and Main; a CLI writing to stderr is expected, but `SparqlCommand` prints a full trace for internal errors.
- `OllamaClient.apiUri` refuses `http://[::1]:11434`, because `URI.getHost()` returns `[::1]` with brackets. That is functional, not a security problem.

