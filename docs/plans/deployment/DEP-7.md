<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-7 — File exchange: job workspaces, upload and download

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] Upload streams to disk (never fully in memory); size cap enforced while reading; zip-slip, zip-bomb, symlink entries, absolute names, device names refused (reuse SEC-2/SEC-5 checks; regression tests for each)
- [ ] `application/zip` accepted only on `POST /v1/uploads`; every other POST still requires JSON (SEC-1 rule kept); test
- [ ] A job with `inputUpload` can read only its upload and write only its `out/`; a config path escaping either → 403 at submit time
- [ ] Download endpoints stream, set `Content-Type` and `Content-Disposition` safely (sanitised filename), reject `..` and encoded traversal in `{path}`
- [ ] TTL clean-up and disk budget tested with an injected clock; files of queued/running jobs never deleted
- [ ] Shared-volume mode unchanged (regression test)
- [ ] OpenAPI spec updated; contract tests cover the new routes
- [ ] `/security-review` clean or findings fixed with regression tests

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
