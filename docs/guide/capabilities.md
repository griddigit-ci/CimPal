<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal capability statement

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

**CimPal** is an open-source toolset by gridDigIt Kft. for CIM/CGMES semantic data. It validates grid models against SHACL constraints and supports the work around that: profile and shape authoring, conversion, comparison and querying. Licence: EUPL-1.2-or-later.

## Functions

| Area | What it does | CLI command |
|---|---|---|
| Validation | SHACL validation of CGMES and other CIM models, by mapping CSV or grouped by timestamp; Excel and Turtle reports, JSON summary | `validate` |
| Shape authoring | SHACL from RDFS profiles; SHACL from Excel templates; reorganising SHACL files | `rdfs2shacl`, `excel2shacl`, `organize` |
| RDF handling | Conversion between RDF/XML, Turtle and JSON-LD; SPARQL SELECT queries | `convert`, `sparql` |
| Comparison | Profile and shape comparison; instance-data comparison | `compare`, `compare-instances` |
| Packaging and test data | DCAT/CGMES manifests; instance data from Excel templates | `manifest`, `gen-instances` |
| Automation | JSON pipelines of the above; local HTTP API; MCP server for AI assistants | `run`, `serve`, `mcp` |

A JavaFX desktop application offers the same functions interactively (Windows `CimPal.exe`, or `CimPal.jar` elsewhere).

## Deployment modes

| | CLI (JAR) | Container image | `serve` (HTTP) | `mcp` (AI) |
|---|---|---|---|---|
| **Form** | `CimPal-CLI.jar`, one process per run | `ghcr.io/griddigit-ci/cimpal:<version>`, linux/amd64 and arm64 | Long-running process, JAR or container | Process started by the MCP client |
| **Needs** | Java 25 | Docker or Kubernetes | Java 25 or the image | Java 25 or the image |
| **Network exposure** | None | None (no port exposed) | Localhost by default; other interfaces only with `--allow-remote`; plain HTTP | None (stdin/stdout) |
| **Authentication** | OS user | OS user / container runtime | Bearer token on every request except `/health` | OS user |
| **Concurrency** | One command per process; scale by running more | One per container; scale by replicas | One command at a time, bounded queue (503 when full) | One tool call at a time |
| **State** | None besides output files and a fetch cache | None (read-only root filesystem works) | None between requests | None between calls |
| **Output** | Files, exit code, JSON on stdout or to a file | Same | JSON over HTTP | JSON tool results |

Details: [Deployment modes](deployment-modes.md).

## Resources

Measured with synthetic CGMES-like models: [Sizing](sizing.md).

| Model size | Recommended heap | Container memory | Validation time |
|---|---|---|---|
| 100 k triples | 512 MB | 768 MB | ~3 s |
| 1 M triples | 1 GB | 1.5 GB | ~9–13 s |
| 5 M triples | 3 GB | 4 GB | ~30–40 s |

- **Rule of thumb:** plan 0.6 GB of heap per million triples, and 2–4 cores per validation.
- **Lower bound:** real CGMES constraint sets need more CPU time than the benchmark's.
- **Reporting:** `--stats` reports a run's own time, heap and triple count.
- **Out of memory:** a run that runs out of memory ends with exit code 3, never with a "conforming" result.

## Integration

| Path | Status |
|---|---|
| **A. Container or CLI per task** (Airflow `KubernetesPodOperator`, `DockerOperator`, `BashOperator`; any CI system), with exit codes, a JSON summary file and a configurable violations exit code | Available. Examples and tests in `integrations/airflow/`; see [Airflow](airflow.md). |
| **B. CimPal as a service** (asynchronous jobs, OpenAPI `/v1`, upload and download, reverse proxy) | Planned: DEP-5, DEP-6, DEP-7 |
| **C. Python SDK and Airflow provider** | Planned: DEP-9, DEP-10 |

## Security posture

- **Paths:** `serve`, `mcp` and `run` only read and write under configured allowed folders. An existing output file is only replaced when the request asks for it.
- **`serve`:** a per-start bearer token, Host and Origin checks, a request size limit, a bounded queue and a per-request timeout. It binds to localhost unless `--allow-remote` is given.
- **Outbound connections:** only HTTPS fetches of `owl:imports` from an allowlist (`raw.githubusercontent.com`, `api.github.com`, `github.com`). SPARQL `SERVICE` (federated queries) is disabled. A failed import is reported as an error, never as a pass.
- **Container:** it runs as a non-root user (UID 10001), exposes no port, works with a read-only root filesystem, and is published with SBOM and provenance attestations.
- **Parsing:** XML external entities are not resolved, archives are read within size budgets, and CSV output is escaped against formula injection.
- **Assurance:** a security self-assessment ([SECURITY-SELF-ATTESTATION.md](../../SECURITY-SELF-ATTESTATION.md), September 2026) and regression tests for its findings. Later hardening is tracked in the project's work packages; a refreshed, signed attestation and a `SECURITY.md` are planned (SEC-4).

More in [Security for operators](security.md).

## Licence and support

- **Licence:** EUPL-1.2-or-later, an OSI-approved copyleft licence compatible with the GPL, LGPL, MPL and others. The full text ships with every release and in the container image (`/opt/cimpal/LICENSE.md`).
- **Releases:** dated versions (`YYYY.M.D.N`) on GitHub Releases and GHCR.
- **Support:** issues on GitHub; see [Versioning and support](versioning-and-support.md) for the support policy (draft) and how to report vulnerabilities.
