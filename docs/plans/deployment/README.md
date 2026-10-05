<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Deployment and integration plan

Track: **Deployment** (WP prefix `DEP`). Written 2026-10-02 in Claude Desktop (CimPal Project). Status of each WP lives in the master table in [`../README.md`](../README.md); this file holds the why, the target picture, the requirements and the order.

## Purpose

CimPal is going to be used by external organisations (TSOs, RSCs, consultants) outside gridDigIt. They need to be able to answer three questions without reading the code:

1. **Level 2 — How is it deployed and what does it need?** Form factor (CLI, container, server), authentication, network exposure, and how much CPU and memory a run needs for a given model size.
2. **Level 3 — How do we integrate it into our pipelines?** In practice: Apache Airflow. Either Airflow starts CimPal as a container/CLI task, or Airflow calls a CimPal server, ideally through a Python SDK and an Airflow provider.
3. **How do we set it up and operate it?** Installation, configuration, sizing, security and support policy, written for an external reader.

This track delivers the code, packaging, measurements and documentation to answer all three.

## Current state (2026-10-02)

| Area | State |
| --- | --- |
| Packaging | One fat JAR, `CimPal-CLI/target/CimPal-CLI.jar`, needs JRE 25. GUI as `CimPal.jar` + `CimPal.exe`. No container image, no jlink image. |
| CLI automation | Exit codes 0/1/2/3; `--format json` on `validate`, `sparql`, `compare`, `compare-instances` keeps stdout JSON-only. JSON only on stdout, no file option. Exit 1 (violations) cannot be remapped. |
| `serve` | JDK `HttpServer`, `localhost:7474`. After SEC-1/SEC-2: bearer token (file or `CIMPAL_API_TOKEN`), Host/Origin checks, 1 MB body cap, one worker + bounded queue (503), `--request-timeout` (504), `--allow-remote` for non-loopback, allowed roots (`--root`, `--read-root`, `--write-root`). **Synchronous**: the HTTP call stays open for the whole run (5–30 min for production CGMES sets). No OpenAPI spec, no job ids, no upload/download, no versioned paths. Host check only accepts loopback or the bound host, so it can't sit behind a reverse proxy with a public name. |
| `mcp` | stdio MCP server, 10 tools, for AI agents. Not a team/service interface. No timeout/queue (G4 open). |
| Concurrency | One command at a time per JVM: `ValidationTools` static flags and process-wide `System.out` capture in `serve`/`mcp`/`run`. |
| Resource figures | None measured. No benchmark, no sizing guide. The only figure is "5–30 min" for production validation. |
| Python / Airflow | Nothing. One Python snippet in `docs/cli/serve.md`. |
| External docs | `docs/cli/*` is a good command reference but written for developers; no install/quickstart/sizing/integration guide, no support policy. |

The older discovery notes in `docs/PROJECT.md` → *REST API — discovery and implementation plan* are the input to DEP-5..DEP-8. This plan supersedes that section's ordering.

## Target picture

Three integration paths, each useful on its own, built in this order:

```
 Path A  Container / CLI task                 (Phase D0)
   Airflow ──KubernetesPodOperator / DockerOperator / BashOperator──▶ cimpal image ──▶ files on shared storage
           ◀── exit code + JSON summary (XCom) ──

 Path B  CimPal as a service                  (Phase D1)
   Airflow ──HttpOperator / HttpSensor──▶ cimpal serve (/v1, async jobs, OpenAPI) ──▶ job workspace / shared volume
           ◀── 202 + jobId … poll … result ──

 Path C  Python SDK + Airflow provider        (Phase D2)
   Airflow DAG ──CimPalValidateOperator (deferrable)──▶ cimpal-client (Python) ──▶ /v1 API
```

Principles:

- **The engine stays Java.** The SDK and provider are thin Python clients over the HTTP API; the SDK's core is generated from the OpenAPI spec so it cannot drift.
- **Scale out, not up.** The recommended production shape is one worker per container and more replicas, not many workers in one JVM. In-JVM concurrency (DEP-8) is optional.
- **Security guarantees from SEC-1/SEC-2 never regress.** Every server change keeps token auth, Host/Origin checks, allowed roots, size and queue limits, and gets `/security-review`.
- **Backwards compatible.** Existing CLI flags, configs and the current synchronous `serve` endpoints keep working.

## Requirements

Each WP lists the requirement IDs it covers.

### Functional

| ID | Requirement | WP |
| --- | --- | --- |
| R1 | CimPal runs from an official, versioned container image without a local Java install. The image runs as non-root. | DEP-1 |
| R2 | The image honours container CPU and memory limits, and an out-of-memory condition ends with exit 3 and a clear message instead of a hang or a silent kill. | DEP-1, DEP-2 |
| R3 | A run can report its resource use as JSON: wall time, CPU time, peak heap, peak RSS where the OS exposes it, triples loaded, input size. | DEP-2 |
| R4 | A published sizing guide gives CPU and memory recommendations per model size class, produced by a benchmark that anyone can re-run from the repo. | DEP-2 |
| R5 | Every JSON-capable command can write its JSON result to a file as well as stdout. | DEP-3 |
| R6 | The exit code for "violations found" is configurable, so an orchestrator can treat findings as data rather than task failure. | DEP-3 |
| R7 | Long work runs as asynchronous jobs: submit → job id → poll status → fetch result. No HTTP call blocks for more than seconds. | DEP-5 |
| R8 | The HTTP API is versioned (`/v1`) and described by an OpenAPI 3.1 document that the server serves and that contract tests check. | DEP-5 |
| R9 | Authentication with bearer tokens read from a secret (file or env), with two valid tokens at once for rotation without downtime. | DEP-6 |
| R10 | Deployable behind a reverse proxy or Kubernetes ingress: configured allowed host names, TLS at the proxy, liveness and readiness endpoints, graceful SIGTERM. | DEP-6 |
| R11 | Files reach the server either through a shared volume (allowed roots) or by upload, and results can be listed and downloaded. All paths stay under the allowed roots. | DEP-7 |
| R12 | Every server resource is bounded: queue length, number of stored jobs, job age, upload size, job run time. | DEP-5, DEP-6, DEP-7 |
| R13 | Structured logs (JSON lines with job id, command, duration, exit code); optional metrics endpoint. | DEP-6 |
| R14 | A Python SDK (sync and async) with typed errors and retry on 503, its core generated from the OpenAPI spec. | DEP-9 |
| R15 | An Airflow provider: connection type, hook, deferrable operator, trigger, sensor. XCom carries a small summary; violations either fail the task or are left for branching. | DEP-10 |
| R16 | An external user guide: install, quickstart, deployment modes, capability statement, sizing, security for operators, Airflow, API, SDK, troubleshooting, versioning and support policy. | DEP-4 (first edition), then every WP updates its page |

### Non-functional and constraints

| ID | Constraint |
| --- | --- |
| N1 | No regression of SEC-1/SEC-2 behaviour. `/security-review` and `/security-check-change` on every change to `serve`, `run`, `mcp`, I/O, upload or process handling. |
| N2 | Logic in Core, HTTP in CLI (SEC-1 decision). No new static mutable state. |
| N3 | Existing CLI flags, config keys, exit codes and the current synchronous `serve` endpoints keep working. New behaviour is opt-in or under `/v1`. |
| N4 | `mvn -B verify` stays green on Windows and Ubuntu. Tests that need Docker, Python or Airflow are skipped cleanly when the tool is absent, and run in a dedicated CI job where it is present. |
| N5 | Licence header on every new file (Java, Python, YAML, Dockerfile, Markdown), in that file type's comment syntax. |
| N6 | Every user-visible behaviour change updates `docs/cli/*` and the matching page under `docs/guide/`. |

## Work packages

| Phase | ID | Work package | Covers | Depends on | Size |
| --- | --- | --- | --- | --- | --- |
| D0 | [DEP-1](DEP-1.md) | Container image | R1, R2 | CI-1 | S–M |
| D0 | [DEP-2](DEP-2.md) | Resource statistics, benchmark and sizing guide | R2, R3, R4 | DEP-1 (soft) | M |
| D0 | [DEP-3](DEP-3.md) | CLI automation options and Airflow container pattern | R5, R6 | DEP-1 | M |
| D0 | [DEP-4](DEP-4.md) | External user guide, first edition | R16 | DEP-1, DEP-2, DEP-3 | M |
| D1 | [DEP-5](DEP-5.md) | Async job API (`/v1`) and OpenAPI spec | R7, R8, R12 | SEC-1, SEC-2 | L |
| D1 | [DEP-6](DEP-6.md) | Service deployment: proxy, tokens, probes, SIGTERM, logs, manifests | R9, R10, R12, R13 | DEP-5, DEP-1 | M |
| D1 | [DEP-7](DEP-7.md) | File exchange: job workspaces, upload and download | R11, R12 | DEP-5 | M–L |
| D1 | [DEP-8](DEP-8.md) | Concurrent workers in one JVM (optional) | — | DEP-5, decision D-8 | L |
| D2 | [DEP-9](DEP-9.md) | Python SDK `cimpal-client` | R14 | DEP-5, DEP-7 | M |
| D2 | [DEP-10](DEP-10.md) | Airflow provider | R15 | DEP-9, DEP-3 | M |

Phase D0 needs no server changes and makes Path A usable for external users. Phases D1 and D2 can follow in parallel with the remaining security and testing work. Relations to other tracks:

- **CI-2** (supply chain): image signing, SBOM and publishing build on DEP-1.
- **TEST-4** (CLI contract, JSON Schemas): DEP-5 reuses the JSON Schemas as OpenAPI components.
- **TEST-5** (nightly scale tests): reuses the DEP-2 benchmark harness and its budgets.
- **SEC-3** (G4 for `mcp`): independent, but DEP-5's job limits are the model for it.

## Open decisions (maintainer)

These are also listed in the master README. Each WP names the decisions it needs before its plan mode can finish.

| ID | Decision | Recommendation | Needed by |
| --- | --- | --- | --- |
| D-1 | Container registry and image name | `ghcr.io/griddigit/cimpal` (GitHub Container Registry, same org as the repo) | DEP-1 |
| D-2 | Base image | `eclipse-temurin:25-jre` (Ubuntu based); distroless later if wanted | DEP-1 |
| D-3 | Python SHACL engines in the image | Not in the default image; an optional `-python` variant later | DEP-1 |
| D-4 | Reference models for benchmarks | Synthetic scalable models now; ENTSO-E conformity models once the licence decision is made | DEP-2 |
| D-5 | HTTP layer for `/v1` | Stay on the JDK `HttpServer` (SEC-1 hardening is built on it); revisit Javalin only if SSE or many endpoints make it painful | DEP-5 |
| D-6 | File exchange | Shared volume + upload/download in v1; no native S3 in CimPal (Airflow copies S3 ↔ volume or uploads through the SDK) | DEP-7 |
| D-7 | TLS | Terminate at the reverse proxy / ingress; no TLS inside CimPal in v1 | DEP-6 |
| D-8 | In-JVM concurrency | Not now; scale with replicas. Do DEP-8 only if a customer needs several jobs per instance | DEP-8 |
| D-9 | Where the Python code lives | Same repo, `clients/python/` and `clients/airflow/`, versioned with CimPal | DEP-9 |
| D-10 | Python package names | `cimpal-client` (import `cimpal_client`) and `airflow-provider-cimpal` (import `cimpal_provider`). The `apache-airflow-providers-*` prefix is reserved for Apache community providers. | DEP-9 |
| D-11 | Licence of the Python packages | EUPL-1.2 like the rest, or Apache-2.0 to match the Airflow ecosystem | DEP-9 |
| D-12 | Supported Airflow versions | Airflow 3.x; add 2.10 only if a customer needs it | DEP-10 |
| D-13 | `/v1/openapi.json` without token? | Yes: the spec has no secrets and clients need it to bootstrap | DEP-5 |

## Glossary (for this track)

- **Airflow** — Apache Airflow, a workflow scheduler. A pipeline is a Python file called a **DAG**, made of **tasks**. CimPal would be one task, e.g. "validate this model set".
- **XCom** — how Airflow passes small values (a few KB) between tasks. CimPal's JSON summary goes here; reports go to storage.
- **Operator / Hook / Sensor / Trigger** — Airflow building blocks: an operator is a task type, a hook wraps a connection to an external system, a sensor waits for a condition, and a trigger lets a deferrable operator wait without holding a worker slot.
- **Docker image / container** — CimPal packed with Java 25 and its configs, runnable anywhere with `docker run`.
- **Kubernetes (K8s)** — runs containers across a cluster and gives each a CPU and memory budget (`requests`/`limits`). `KubernetesPodOperator` is how Airflow starts one container per task.
- **Cores / sizing** — how many CPU cores and how much memory (JVM `-Xmx`, container limit) a run needs for a model of a given size. Measured in DEP-2.
- **`serve`** — CimPal's built-in HTTP server (`java -jar CimPal-CLI.jar serve`).
- **OpenAPI spec** — machine-readable description of the HTTP API; source for docs and the generated Python client.
- **S3 URI** — address of a file in S3-compatible object storage, e.g. `s3://bucket/path/EQ.xml`. Not read by CimPal directly (D-6).
- **Deferrable operator** — an Airflow operator that hands waiting over to the triggerer process, so a 30-minute validation doesn't occupy a worker slot.
