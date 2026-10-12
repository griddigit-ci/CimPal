<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-7 — File exchange: job workspaces, upload and download

| Field | Value |
| --- | --- |
| Status | In review (PR into `devel`) |
| Phase | D1 |
| Depends on | DEP-5 |
| Size | M–L |
| Branch | `feature/dep-7-file-exchange` |
| Requirements | R11, R12 |
| Decisions needed | D-6 file exchange model |

## Goal

A caller that does not share a filesystem with the server (Airflow on another machine, a laptop, a CI runner) can send input files, run a job on them, and get the outputs back. Callers that do share storage (Kubernetes PVC, NFS) keep using paths under the allowed roots.

## Two modes

| Mode | How | When |
| --- | --- | --- |
| Shared volume (exists) | Config paths under `--root`/`--read-root`/`--write-root` (SEC-2) | Airflow and CimPal mount the same PVC/NFS; large model sets |
| Upload/download (new) | `POST /v1/uploads` (zip) → `uploadId`; job config `"inputUpload": "<uploadId>"`; outputs listed and downloaded per job | No shared filesystem; models up to the upload limit |

Native `s3://` input is **not** in scope (D-6). The guide documents the pattern: an upstream Airflow task copies from S3 to the shared volume, or downloads and uploads through the SDK.

## API additions

| Method and path | Purpose | Responses |
| --- | --- | --- |
| `POST /v1/uploads` | Body `application/zip`, streamed to disk, size-capped by `--max-upload-bytes` (default 2 GiB); extracted with the hardened zip limits (`ModelFactory` / SEC-5) into `<work-root>/uploads/<uploadId>/` | 201 + `{uploadId, files[], totalBytes, expiresAt}`; 413, 415, 400 (bad zip / limit hit) |
| `GET /v1/uploads/{id}` | Metadata and file list | 200, 404 |
| `DELETE /v1/uploads/{id}` | Remove an upload not used by a queued/running job | 204, 404, 409 |
| `GET /v1/jobs/{id}/files` | Output files of the job (relative names, sizes, media types) | 200, 404, 409 (not finished) |
| `GET /v1/jobs/{id}/files/{path}` | Download one output file (streamed) | 200, 404 |
| `GET /v1/jobs/{id}/archive` | All outputs as one zip (streamed) | 200, 404, 409 |

Job workspace: each job gets `<work-root>/jobs/<jobId>/out/`. When a job uses `inputUpload`, the upload folder is a read root for that job only, relative config paths resolve against it, and output paths resolve under the job's `out/`. Output keys in the config that point elsewhere are refused in upload mode.

Retention: uploads and job workspaces expire with `--upload-ttl` (default `PT24H`) and the job TTL from DEP-5; a total disk budget `--work-max-bytes` refuses new uploads with 507 when exceeded.

## Scope

- CLI serve: upload handler (the only non-JSON POST; Content-Type `application/zip` allowed on that route only), workspace manager, download handlers
- Core: reuse the zip extraction limits; `PathPolicy` per-job roots (no global mutation)
- `--work-root` (default `<first --write-root or --root>/.cimpal-work`), `--max-upload-bytes`, `--upload-ttl`, `--work-max-bytes`
- OpenAPI spec, docs (`docs/cli/serve.md`, `docs/guide/service.md`)

## Acceptance criteria (status checklist)

- [x] Upload streams to disk (never fully in memory); size cap enforced while reading; zip-slip, zip-bomb, symlink entries, absolute names, device names refused (reuse SEC-2/SEC-5 checks; regression tests for each)
- [x] `application/zip` accepted only on `POST /v1/uploads`; every other POST still requires JSON (SEC-1 rule kept); test
- [x] A job with `inputUpload` can read only its upload and write only its `out/`; a config path escaping either → 403 at submit time
- [x] Download endpoints stream, set `Content-Type` and `Content-Disposition` safely (sanitised filename), reject `..` and encoded traversal in `{path}`
- [x] TTL clean-up and disk budget tested with an injected clock; files of queued/running jobs never deleted
- [x] Shared-volume mode unchanged (regression test)
- [x] OpenAPI spec updated; contract tests cover the new routes
- [x] `/security-review` clean or findings fixed with regression tests

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-7.md` (in plan mode).

```text
Add upload/download file exchange to the /v1 API (R11, R12 in docs/plans/deployment/README.md). Decision D-6: no native S3 in CimPal.
This is the riskiest server change in the track: it adds a binary upload route and serves files. Write the hostile tests first: zip-slip (../, absolute, drive letters, UNC, encoded), zip bomb (ratio and total size), symlink entries, device names (CON, NUL), duplicate names, very many entries, upload larger than the cap, slow upload, wrong Content-Type, download path traversal (.., %2e%2e, backslashes), download of another job's files.
1. Upload: stream the request body to a temp file under <work-root>/uploads/.incoming with a counting InputStream that aborts at --max-upload-bytes; then extract with the existing hardened zip routine into <work-root>/uploads/<uploadId>/. Delete partial files on any failure.
2. ServeSecurity: allow application/zip only on POST /v1/uploads; keep all other SEC-1 checks (Host, Origin, token, size via the new cap for this route only).
3. Per-job PathPolicy: build a new policy object for each job (upload dir read-only, out/ writable) instead of mutating the server-wide one.
4. Downloads: resolve {path} against out/ with PathPolicy, stream with a fixed buffer, sanitise Content-Disposition.
5. Retention and disk budget with an injected Clock.
6. Update cimpal-v1.json and the contract tests; docs/cli/serve.md, docs/guide/service.md (both modes, with an Airflow S3 → volume example).
Run /security-review and /security-check-change; fix findings with regression tests and record them here.
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
| 2026-10-09 | A job on an upload reads its upload and the server's `--read-root` folders (shared constraint libraries), never `--root`/`--write-root`, other uploads or the work area; it writes only its `out/`. | Maintainer |
| 2026-10-09 | `--request-read-time` (default `PT60S`, as SEC-1) instead of a longer default; raise it for large uploads sent without a buffering proxy. | Maintainer |
| 2026-10-09 | `inputUpload` is a field of the job request, not a config key, so the command config schemas stay equal to the MCP ones. | Claude Code (plan approved) |
| 2026-10-09 | `PathPolicy.Builder.exclude`: an excluded folder is refused unless the path is under a root strictly inside it, so a job can use its own upload and `out/` inside the excluded work area. A work root that is or contains a root is refused at start. | Claude Code |
| 2026-10-09 | The work area carries a `.cimpal-work` marker; only a marked folder is emptied at start, and a non-empty unmarked folder is refused. `--no-file-exchange` turns the feature off. | Claude Code (security review) |
| 2026-10-09 | Nested ZIPs are stored, not expanded; link entries become plain files. In-memory metadata like the jobs; one command at a time (process-wide `PathPolicy` slot). | Claude Code (plan approved) |

## Notes and results

**Built** (branch `feature/dep-7-file-exchange`):
- **Core:**
  - `ZipExtractor`, which extracts an untrusted ZIP to disk;
  - `PathPolicy.exclude` and `isReservedName`;
  - `LogSanitizer.hasUnsafeCharacters`, now public.
- **CLI:**
  - `WorkArea`;
  - `/v1/uploads` and the `/files` and `/archive` routes of a job;
  - `inputUpload`; `RootOptions.jobPolicy`; `PathGuard.placeOutputs`, `outputFields` and the base-only lookup;
  - five flags plus `--no-file-exchange`.
- **OpenAPI:** the document is extended.
- **Docs:** `docs/cli/serve.md` (*File exchange*), `docs/guide/service.md` (both modes, S3 with Airflow), and the security, capabilities and deployment-modes pages.

**Tests:**
- **Totals:**
  - Core: 363 → 388 (`ZipExtractorTest`, 38 of its own; `PathPolicyTest` +5).
  - CLI: 217 → 248 (`FileExchangeApiTest` 24, `ServeCommandTest` +5, `PathGuardTest` +2).
- **Spec coverage:** `FileExchangeApiTest` checks that every response of its routes in the OpenAPI document is exercised.
- **Manual run** from the dev classpath with curl:
  - an upload; a ZIP with `../../escape.txt` refused (400);
  - a `sparql` job on the upload; the download, with a safe `Content-Disposition` and `nosniff`;
  - an encoded traversal gets 404; the archive; a ZIP posted to `/v1/jobs` gets 415.

**Security review** (`security-reviewer`, 2026-10-09). No Critical or High. Fixed, with regression tests:

| Severity | Finding | Fix (test) |
| --- | --- | --- |
| Medium | A work root equal to a root re-opened the work area ("the more specific root wins" also matched the equal root). | A root equal to the excluded folder doesn't count. A work root that is or contains a root is refused at start. (`aRootThatIsTheExcludedFolderItselfDoesNotReopenIt`, `aWorkRootThatIsOrContainsAnAllowedFolderIsRefused`) |
| Medium | Start-up emptied `uploads/` and `jobs/` of any folder, e.g. a mistyped `--work-root`. | A `.cimpal-work` marker; an unmarked non-empty folder is refused and left alone. (`aForeignFolderIsNeverEmptiedAsAWorkArea`, `aFolderWithoutTheMarkerIsRefusedAndLeftAlone`) |
| Medium | Concurrent extractions each counted on the whole free budget. | The extraction room is reserved under the lock before extracting. (`extractionCannotOvershootTheBudgetAndCostsPerEntry`) |
| Medium | Implicit folders and empty files cost nothing (inode exhaustion). | Every file and folder counts as an entry and costs 4 KiB; at most 32 levels and 1,024 characters. (`emptyFilesAndFoldersAreNotFree`, `namesTooDeepOrTooLongAreRefused`) |
| Medium | `ZipFile` loaded a huge central directory before any limit (OOM). | The end record (and the ZIP64 one) is read first; more than 10,000 entries, or a directory over 16 MiB, is refused. (`aForgedEndRecordIsRefusedBeforeTheCentralDirectoryIsRead`) |
| Low | Content-Length plus `Transfer-Encoding` under-reserved the budget. | With `Transfer-Encoding` the full upload limit is reserved. (Not tested: `HttpClient` can't send both.) |
| Low | Error bodies could show server paths. | Errors name entries only; the JDK's open errors become a fixed message. (`errorsNameTheEntryNeverTheServerFolder`, `uploadErrorsNeverShowServerPaths`) |
| Low | A failed delete of the incoming ZIP leaked the reservation. | The release is in its own `finally`. |
| Low | An archive download that failed midway ended as a valid-looking ZIP. | No central directory is written on failure; a vanished file aborts the download. (Not deterministic to test.) |
| Low | A download name with `* ? < > \| "` threw on Windows, and the client hung. | Gives 404. (`aDownloadNameTheFileSystemCannotExpressIs404`) |
| Low | A failure while preparing or checking left the upload pinned. | The workspace is released, answering 500. (`aFailingCheckReleasesTheUpload`) |
| Low | A race between a job ending and being evicted left phantom bytes. | `jobEnded` does nothing once the workspace is gone. |
| Low | `deleteTree` entered NTFS junctions. | Junctions and other reparse points are removed, not entered. (`deleteTreeRemovesAJunctionWithoutEnteringIt`, runs on Windows) |
| Low | Job mode still looked up `READ_IF_EXISTS` values in the server's working directory. | In job mode, only the upload is looked in. (`aJobOnAnUploadLooksUpRelativeValuesInItsUploadOnly`) |
| Info | Trust model; no way to turn the feature off. | Documented; `--no-file-exchange`. (`fileExchangeCanBeTurnedOff`) |

Also from the review: the misleading link test was replaced by a Unix link-entry test (`aUnixLinkEntryIsWrittenAsAPlainFile`).

**Open:**
- DEP-9 (Python SDK) is generated from the extended spec.
- The kind end-to-end test could add an upload and download through the Ingress.
- Jobs pinned against eviction while being downloaded (today a vanished file aborts the download).
