<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Deployment modes

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

CimPal is one program, `CimPal-CLI.jar`, that you can run in several ways. Pick by who starts it and how often.

| Mode | Start it with | Use it when | Avoid it when |
|---|---|---|---|
| **CLI** | `java -jar CimPal-CLI.jar <command> …` | People or scripts run a validation now and then; CI jobs | You need many small calls per minute (each run starts a JVM, ~1.5–2 s) |
| **Container** | `docker run ghcr.io/griddigit-ci/cimpal:<version> <command> …` | No Java on the host; Kubernetes; scheduled pipelines (Airflow) | — |
| **Pipeline** (`run`) | `run pipeline.json` | Several steps in a fixed order: validate, then compare, then convert… | Steps need to start servers (not allowed) |
| **Local server** (`serve`) | `serve` | A tool on the same machine calls CimPal repeatedly and wants a warm JVM | Many parallel users (one command at a time) |
| **Service** (`serve` behind a proxy) | Container behind a TLS reverse proxy or Kubernetes Ingress; [examples](service.md) | A team or an orchestrator (Airflow) submits jobs over the network | Many parallel users in one instance; run several instances instead |
| **AI assistant** (`mcp`) | Started by Claude Desktop or another MCP client | Interactive analysis and shape development with an AI assistant | Unattended automation; use the CLI or container instead |

## CLI and container

This is the recommended way to automate CimPal.
- Each run is a separate process: it reads its inputs, writes its reports, and ends with an exit code.
- Nothing stays running and nothing is shared between runs, so you scale by running more processes or containers.
- What a scheduler needs is in [Configuration](configuration.md#exit-codes): exit codes, a JSON summary on stdout or in a file (`--summary-file`), and a configurable exit code for violations (`--violations-exit-code`).
- The container image adds no behaviour of its own. It runs the same JAR as a non-root user, sizes the heap from the memory limit, and works with a read-only root filesystem; see [Running CimPal in Docker](../cli/docker.md).

## `serve`: the local HTTP server

`serve` keeps one JVM running and offers the commands over HTTP.
- **Job API (`/v1`):** submit a command with its JSON config to `POST /v1/jobs` and get a job id back at once. Then poll the job, fetch its JSON result, and read its progress lines. The API is described by an OpenAPI 3.1 document at `/v1/openapi.json`.
- **Synchronous endpoints** (`POST /validate`, …), kept for local use: a POST with a JSON config, answered with the command's JSON result.
- **Built for one machine:**
  - It listens on `localhost:7474` by default.
  - Every request except `GET /health` needs the bearer token. The token is new on every start; it is written to a file you can read, or taken from `CIMPAL_API_TOKEN`.
  - Paths in requests must lie under the allowed folders (`--root`).
- **Current limits:**
  - **Synchronous endpoints:** the HTTP call stays open until the command ends, up to `--request-timeout` (30 minutes by default). Large validations can take that long; use the job API instead.
  - **One command at a time:** jobs and synchronous requests wait in one short queue (`--queue-size`, default 4) and get HTTP 503 when it is full.
  - **Jobs live in memory:** they are lost when the server restarts.
  - **Plain HTTP, no TLS:** `--allow-remote` lets it listen on other interfaces, but the traffic is not encrypted. For network access, put a TLS proxy or Ingress in front and list its name with `--allowed-host`; see [Running CimPal as a service](service.md).
  - **No upload or download:** files must already be in a folder the server can reach.

Reference: [serve](../cli/serve.md). In a container: [docker.md, `serve`](../cli/docker.md#serve).

## `mcp`: for AI assistants

`mcp` speaks the Model Context Protocol over stdin and stdout. An MCP client such as Claude Desktop starts it and calls its ten tools (`validate`, `sparql`, `compare`, …).
- It has no network port.
- File paths in tool calls must lie under `--root`.
- Tool results are the commands' JSON.

Reference: [mcp](../cli/mcp.md).

## Pipelines (`run`)

`run` executes a JSON list of steps (`validate`, `compare`, `convert`, …) in one process, with per-step control over stopping on errors or violations. Paths are checked against allowed folders like in `serve`. A pipeline can't start `serve`, `mcp` or another `run`. Reference: [run](../cli/run.md).

## Planned

These are on the roadmap but **not available yet**:

| Feature | Work package |
|---|---|
| File exchange: job workspaces, upload and download | DEP-7 |
| Several jobs in parallel in one JVM (optional; the default answer is more replicas) | DEP-8 |
| Python SDK (`cimpal-client`) | DEP-9 |
| Airflow provider (operator, hook, sensor, deferrable trigger) | DEP-10 |

Until then, use the CLI or container per task. It works today with any scheduler, including Airflow: see [Airflow](airflow.md).
