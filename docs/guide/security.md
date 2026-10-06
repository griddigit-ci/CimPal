<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Security for operators

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

What CimPal touches, and how to run it safely. The [capability statement](capabilities.md#security-posture) has the short version.

## What CimPal reads, writes and connects to

| | What |
|---|---|
| **Reads** | The model files, constraint files, mapping CSV and config you point it at; ZIP archives of models (within size budgets) |
| **Writes** | Reports and outputs where you tell it to; a cache of fetched imports in your user profile (`%LOCALAPPDATA%\CimPal` or `~/.cimpal`, owner-only) |
| **Connects to** | Only `raw.githubusercontent.com`, `api.github.com` and `github.com` over HTTPS, and only to fetch `owl:imports` that constraint files declare |
| **Listens on** | Nothing, except `serve` (HTTP, `localhost:7474` by default) |

- **Outbound:** SPARQL `SERVICE` (federated queries) is disabled, both in user queries and in SHACL-SPARQL constraints. No other outbound connection is made.
- **Failed imports:** an import that can't be fetched, because the host is not on the allowlist or the network is unreachable, is reported as an **error**, never as a passing validation.
- **Offline:** with no network (e.g. `docker run --network none`), constraint sets that need remote imports fail visibly. Constraint sets without remote imports work normally.

## Allowed folders

`serve`, `mcp` and `run` accept file paths from requests, tool calls or pipeline files. These must lie under the **allowed folders**:
- `--root`: read and write. The default is the working directory.
- `--read-root`: read only.
- `--write-root`: outputs.

Other rules:
- Paths outside them are refused (HTTP 403, or exit 2).
- An existing output file is only replaced when the request says `"overwrite": true`.
- Links and junctions that lead outside the roots are refused.
- A home folder or a drive root is never used as the default root.

Direct use of the other commands from a shell is not restricted. The operating system's file permissions apply, as for any program.

## `serve`

- **Token:** every request except `GET /health` needs `Authorization: Bearer <token>`. The token is new on every start: it is written to a file only your user can read, or taken from `CIMPAL_API_TOKEN`. Treat it like a password.
- **Network:** `serve` binds to localhost. `--allow-remote` is needed for any other address, and the traffic is plain HTTP. There is no TLS inside CimPal (decision D-7), so put a TLS-terminating proxy in front if you need network access; full proxy support is planned (DEP-6).
- **Browsers:** a Host-header check (against DNS rebinding) and an Origin check (no CORS headers are sent; `--allow-origin` lists exceptions).
- **Limits:** request bodies up to 1 MB, one command at a time with a queue of 4, and a 30-minute timeout per request. All are configurable.
- **Shutdown:** `POST /shutdown` with the token, or stop the process.

## Container

- **User:** UID 10001, not root.
- **Ports:** no `EXPOSE`. Publish `serve` only on the host loopback, as [docker.md](../cli/docker.md#serve) shows.
- **Filesystem:** a read-only root filesystem works (`--read-only --tmpfs /tmp`; in Kubernetes `readOnlyRootFilesystem` plus an `emptyDir` at `/tmp`).
- **Kubernetes:** the Airflow example pod meets Pod Security "restricted": non-root, all capabilities dropped, `RuntimeDefault` seccomp, no service-account token.
- **Mounts:** only what the run needs. Mount the models read-only and only the output folder writable. Never mount a home folder or a drive root at `/data`.
- **Secrets:** pass `GITHUB_TOKEN` and `CIMPAL_API_TOKEN` as environment variables without a value on the command line (`-e NAME`), or from a secret store. Don't put secrets in `JAVA_OPTS`, `JAVA_TOOL_OPTIONS` or config files.

## Parsing untrusted files

Models and constraint files may come from outside your organisation.
- **XML:** CimPal does not resolve XML external entities.
- **Archives:** ZIP archives are read within size and entry limits.
- **CSV:** CSV outputs are escaped against spreadsheet formula injection.
- **Resource limits:** give each run a memory limit (container or `-Xmx`). A model too large for it ends with exit 3 instead of exhausting the host.

## Assessment and reporting

- **Assessment:** a security self-assessment is in [SECURITY-SELF-ATTESTATION.md](../../SECURITY-SELF-ATTESTATION.md) (September 2026). Each fixed finding has a regression test.
- **Planned (SEC-4):** later hardening (serve authentication, allowed folders, container and out-of-memory handling) will be covered by a refreshed, signed attestation and a `SECURITY.md`.
- **Reporting a vulnerability:** see [Versioning and support](versioning-and-support.md#reporting-a-vulnerability). Please don't open a public issue for it.
