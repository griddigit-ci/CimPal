<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-1 — Profile catalog and header reader

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] `ProfileCatalog.load(List<Path> libraries)` implements [README §1](README.md#1-profile-catalog-prof_mappy): match keys (profile IRI, `owl:versionIRI`, `owl:priorVersion`), the `role/constraints` + `conformsTo http://www.w3.org/ns/shacl` filter, artifact lookup by basename in `<family>/SHACL/` then `<family>/RDFS/`, families `CGMES` and `NCP`
- [ ] General, not APL-only ([README, *Generality*](README.md#generality-requirement-maintainer-2026-11-16)): recursive PROF discovery, artifact resolution relative to the PROF file and then by basename under the library root, an unresolvable ambiguity is a gap; profile namespaces for `dcterms:conformsTo` are an option with default `https://ap.cim4.eu/`. A test with a non-APL folder layout and made-up profile IRIs
- [ ] Gap messages for: no PROF files, a PROF file without a `prof:Profile` node, a profile with no resolvable SHACL artifact, each artifact not found. Gaps never throw
- [ ] The same key in two libraries with different shape sets is an error (decision V-3)
- [ ] `resolve(Collection<String> profileUris)` returns the sorted, de-duplicated shape paths and the list of unmapped URIs
- [ ] `InstanceHeaderReader` returns, per file: kind (`cgmes`, `nc` or none), declared profile URIs, the model's own IRI, and its `md:Model.DependentOn` / `dcterms:requires` targets ([README §2](README.md#2-instance-headers-scan_instances), [§6](README.md#6-dependency-check)). It reads `.xml` and the members of `.zip` archives the way the existing validators do
- [ ] Reading the header does not load the whole model into memory when that can be avoided (measure on a large EQ file and note the result); if a streaming read is not practical, say so in the notes
- [ ] Every path goes through the active `PathPolicy`, as `SHACLValidator` does; an artifact basename can never resolve outside the library folder (regression test with `..`, an absolute path, mixed separators and a UNC name as the artifact value)
- [ ] Tests cover: CGMES-style bare-filename artifacts, NCP-style absolute-URL artifacts, a `role/validation` resource (ignored), a `role/constraints` resource that does not conform to SHACL (ignored), all three key kinds, a file declaring two profiles, a file declaring none, a `conformsTo` value that is neither a catalog key nor under a profile namespace (not a declaration)
- [ ] One optional test (`@Tag`, skipped when the folder is absent) runs the catalog against a real APL checkout given by a system property and prints profile count, key count and gaps, for comparison with `python buildScripts/prof_map.py <apl_dir>`

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

## Notes and results
