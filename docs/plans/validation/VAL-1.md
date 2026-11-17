<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-1 — Profile catalog and header reader

| Field | Value |
| --- | --- |
| Status | In review (branch local, not pushed; PR after [PR #106](https://github.com/griddigit-ci/CimPal/pull/106) is merged) |
| Phase | V |
| Depends on | — |
| Size | M |
| Branch | `feature/val-1-profile-catalog` |

## Goal

Core can answer two questions without running any validation: "which SHACL files belong to this profile URI?" (from DX-PROF) and "which profiles does this instance file declare?". This is the part of the new profile-driven validation that replaces the hand-written TTL ↔ XML mapping CSV.

## Scope

- New Core classes only (suggested: `core.profiles.ProfileCatalog`, `ProfileShapes`, `InstanceHeader`, `InstanceHeaderReader`; choose the package after looking at Core's layout). No CLI, no change to existing validation code.
- Unit tests with synthetic PROF and instance fixtures under `@TempDir`.
- `docs/PROJECT.md`.

Out of scope: grouping, running SHACL, any command or flag.

## Acceptance criteria (status checklist)

- [x] `ProfileCatalog.load(List<Path> libraries)` implements [README §1](README.md#1-profile-catalog-prof_mappy): match keys (profile IRI, `owl:versionIRI`, `owl:priorVersion`), the `role/constraints` + `conformsTo http://www.w3.org/ns/shacl` filter, artifact lookup by file name in the `SHACL` then the `RDFS` folder beside the descriptor's folder (no family names in the code)
- [x] General, not APL-only ([README, *Generality*](README.md#generality-requirement-maintainer-2026-11-16)): recursive PROF discovery by content, artifact resolution by file name (beside the descriptor, then anywhere under the library root; see D2), an unresolvable ambiguity is a gap; profile namespaces for `dcterms:conformsTo` are an option with default `https://ap.cim4.eu/`. A test with a non-APL folder layout and made-up profile IRIs
- [x] Gap messages for: no PROF files, a PROF file without a `prof:Profile` node, a profile with no resolvable SHACL artifact, each artifact not found. Gaps never throw
- [x] The same key in two libraries with different shape sets is an error (decision V-3)
- [x] `resolve(Collection<String> profileUris)` returns the sorted, de-duplicated shape paths, the unmapped URIs and the URIs whose profile lacks shapes (`incomplete`, D4)
- [x] `InstanceHeaderReader` returns, per file: kind (`cgmes`, `nc` or none), declared profile URIs, the model's own IRI, and its `md:Model.DependentOn` / `dcterms:requires` targets ([README §2](README.md#2-instance-headers-scan_instances), [§6](README.md#6-dependency-check)). It reads `.xml` and the members of `.zip` archives the way the existing validators do
- [x] Reading the header does not load the whole model into memory when that can be avoided (measure on a large EQ file and note the result); if a streaming read is not practical, say so in the notes
- [x] Every path goes through the active `PathPolicy`, as `SHACLValidator` does; an artifact basename can never resolve outside the library folder (regression test with `..`, an absolute path, mixed separators and a UNC name as the artifact value)
- [x] Tests cover: CGMES-style bare-filename artifacts, NCP-style absolute-URL artifacts, a `role/validation` resource (ignored), a `role/constraints` resource that does not conform to SHACL (ignored), all three key kinds, a file declaring two profiles, a file declaring none, a `conformsTo` value that is neither a catalog key nor under a profile namespace (not a declaration)
- [x] One optional test, written but **not yet run against a real library** (`@Tag("profile-library")`, skipped when the folder is absent) runs the catalog against a real APL checkout given by a system property and prints profile count, key count and gaps, for comparison with `python buildScripts/prof_map.py <apl_dir>`

## Instructions for Claude Code

Start a session with: `Execute docs/plans/validation/VAL-1.md` (in plan mode).

```text
Read docs/plans/validation/README.md first: it is the specification. §1, §2 and §6 are what this WP implements; decision V-3 and the Generality section apply.

1. Fetch the reference script once (README, "What is being copied") and read prof_map.py and scan_instances() in validate_instances.py. Then look at one real PROF file of each family before designing the parser: either from a local application-profiles-library checkout (ask me for the path) or fetched from GitHub. Note how hasArtifact, hasRole and conformsTo are actually written (IRI or literal, which dcterms namespace) and build the fixtures to match.
2. Grep Core for the existing header reading (ManifestGenerator: DCTERMS_CONFORMS_TO, MD_MODEL_PROFILE; ModelFactory near "Model.profile") and for how SHACLValidator opens files and ZIP members under PathPolicy. Reuse or extract; do not duplicate.
3. Write the tests first, from the acceptance list, with small synthetic PROF/RDF fixtures under @TempDir. Then implement.
4. Use Jena for parsing. Never call TypeMapper.reset(). No new dependency unless unavoidable (then add the JPMS requires).
5. Keep the API small and immutable: VAL-2 is its only caller.
```

Standard footer: see [`../README.md`](../README.md#standard-footer-for-every-wp-session) (`/wp-footer VAL-1` at the start, `/end-session VAL-1` at the end).

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-11-16 | V-1 to V-9 accepted as recommended; the function must be general, not specific to ReliCapGrid. Plan for this WP approved. | Maintainer |
| 2026-11-16 | D1: package `eu.griddigit.cimpal.core.profiles`, exported from Core. `ManifestGenerator` keeps its own private header constants; nothing existing was refactored. | Claude Code |
| 2026-11-16 | D2: artifacts are matched **by file name only**. The real descriptors set `xml:base` to the PROF namespace (a bare name resolves to a meaningless address) and the NC ones give web addresses, so "relative to the PROF file" from the first draft cannot work. Order: the `SHACL`, then the `RDFS` folder beside the descriptor's folder, then the only file of that name under the library root; several is a gap. The spec is corrected. | Claude Code |
| 2026-11-16 | D3: descriptors are found by content (a `.rdf`, `.xml` or `.ttl` file whose first megabyte mentions the PROF namespace, or that starts with a UTF-16 byte-order mark), at any depth; folders whose name starts with a dot are skipped. Reason: the public library now nests the families as submodules (`CGMES/CGMES/PROF/`), and NC descriptors are named `*-AP-RDFS2020.rdf`. | Claude Code |
| 2026-11-16 | D4: `Resolution` has `incomplete` beside `unmapped`: a profile that is known but has no shapes, or misses an artifact (not found, ambiguous, or with no usable file name). VAL-2 must treat it like an unmapped profile (V-4), so a partly resolved profile can never look clean. From the security review. | Claude Code |
| 2026-11-16 | D5: `dcterms:conformsTo` counts as a profile declaration when it is a catalog key or starts with a profile namespace (constructor argument; default constant `https://ap.cim4.eu/`). A header with `md:Model.profile` is CGMES and its `conformsTo` values are not used, as in the reference. With a typed header node (`md:FullModel`, `md:DifferenceModel`, `dcat:Dataset`) only that node's statements count. | Claude Code |
| 2026-11-16 | D6: instance syntaxes are limited to RDF/XML, Turtle and N-Triples (plus `.xml` in archives). JSON-LD is refused: its parser may load a remote `@context` outside the egress gate. The same pattern in `SHACLValidator` is recorded in PROJECT.md *Known issues*, not changed here. | Claude Code |
| 2026-11-16 | D7: limits, each an error or a gap, never a silent skip: 200,000 files per library (error), 32 MB per descriptor (gap), 10,000 header statements per document (error). | Claude Code |
| 2026-11-16 | D8: a duplicate key with different shapes is an `IllegalArgumentException`, also inside one library. VAL-3 maps it to exit 2. | Claude Code |

## Notes and results

**Implementation (2026-11-16).** Core, new package `core.profiles`: `ProfileCatalog`, `ProfileShapes`, `InstanceHeader`, `InstanceHeaderReader`; `module-info.java` exports it. Tests: `ProfileCatalogTest` (41), `InstanceHeaderReaderTest` (14), all on synthetic libraries with made-up IRIs, one of them in a non-APL layout. No existing class changed.

**Test counts (`mvn -B verify -DexcludedGroups=gui`, Windows).** Before (e939545b plus the plan files): Core 709 (13 skipped). After: Core 764 (15 skipped), Main 78, CLI 685 (10 skipped) + 3 + 37 ITs (21 skipped); build green. The two new skips are the optional real-library test and the link test (no symlink privilege on this machine; it runs on Ubuntu CI). On this machine the suite needs `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<plain long path>`, otherwise 29 existing stub-server tests fail (known, unrelated). Coverage floors were not raised (`scripts/Update-CoverageBaseline.ps1` not run).

**Streaming read.** The reader parses as a stream into a sink that keeps header statements only. A generated document of 200,000 objects (600,000 triples) is read in about 2.6 s inside the test; no model is built, so memory does not grow with the file. Not measured on a real large EQ file.

**Real descriptors looked at** (fetched 2026-11-16, not stored in the repo): `CGMES-EQ-PROF.rdf` (entsoe/CGMES-AP) and `Contingency-AP-RDFS2020.rdf` (entsoe/NC-AP). Both: `xml:base="http://www.w3.org/ns/dx/prof"`, `prof:hasArtifact` as `rdf:resource` (bare file name in CGMES, absolute URL in NC), `dcterms:conformsTo` as the literal `http://www.w3.org/ns/shacl`, role IRIs under `http://www.w3.org/ns/dx/prof/role/`. The EQ descriptor also lists EquipmentBoundary and notSolved resources, the latter with `role/validation`; the NC one lists shared constraints (DatasetMetadata, Common, IdentifiedObject, ClassCount, PrefixDeclaration).

**Security review (security-reviewer agent, read-only).** No High. Fixed here, each with a test: unusable or missing artifacts make a profile incomplete (D4); every walked folder and file is checked against the real library root and the active `PathPolicy`, so links, junctions and excluded folders are not part of a library; JSON-LD refused (D6); limits (D7); parser messages and file names in errors are sanitised and carry no full path; a relative IRI in a descriptor no longer becomes a `file:` key; UTF-16 descriptors are found. Not done: no test feeds an over-budget archive to the reader (the limits are `ModelFactory.forEachZipEntry`'s and are covered by `ZipBudgetTest`).

**Regression tests "failing before the fix".** The code is new, so there is no earlier version to fail against. Tests that failed during the work and pass now: the nine traversal cases (first written with the artifact as an IRI, which the RDF parser rejects outright, so they are given as literals), and the tests added for the review findings, which need API that did not exist before (`incomplete`, the file limit, the syntax list).

**Open.**
- Run `ProfileCatalogTest.aRealLibraryCanBeRead` with `-Dcimpal.test.profileLibrary=<application-profiles-library checkout with submodules>` and compare with `prof_map.py`. In particular: whether two descriptors of the real library share a key with different shapes (D8 would refuse the library).
- The JSON-LD question in `SHACLValidator` (PROJECT.md *Known issues*).
