<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal user guide

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

CimPal is a toolset for CIM and CGMES data. It validates grid models against SHACL constraints, generates SHACL from RDFS profiles, converts and compares RDF, runs SPARQL queries, and produces manifests and test instance data. It is developed by gridDigIt Kft. and licensed under the EUPL-1.2.

This guide is for engineers and IT staff who want to install CimPal, run it in their own environment and connect it to their pipelines. For every command and flag, the [CLI reference](../cli/index.md) has the details.

## Three ways to run it

| | How | Good for |
|---|---|---|
| **Command line or container** | `java -jar CimPal-CLI.jar validate …`, or the Docker image `ghcr.io/griddigit-ci/cimpal` | One-off runs, scripts, CI jobs, Airflow tasks. This is the recommended way to automate CimPal. |
| **Local server** | `serve`: an HTTP API on the same machine, with a bearer token | Tools that call CimPal many times and want a warm JVM |
| **AI assistant** | `mcp`: a Model Context Protocol server for Claude and other MCP clients | Interactive shape development and analysis with an AI assistant |

There is also a desktop application (`CimPal.exe` / `CimPal.jar`, JavaFX) with the same functions and some extra interactive tools. This guide focuses on the command line and its automation.

[Deployment modes](deployment-modes.md) compares the options in detail, including what is planned.

## Where to start

| You want to… | Read |
|---|---|
| Know what CimPal can do and what it needs (for IT or procurement) | [Capabilities](capabilities.md) |
| Install it | [Installation](install.md) |
| Validate a model in five minutes | [Quickstart](quickstart.md) |
| Choose between CLI, container, server and MCP | [Deployment modes](deployment-modes.md) |
| Know how much memory and CPU a model needs | [Sizing](sizing.md) |
| Run it from Apache Airflow | [Airflow](airflow.md) |
| Run `serve` for a team: proxy, TLS, Kubernetes | [Running CimPal as a service](service.md) |
| Understand what it reads, writes and connects to | [Security for operators](security.md) |
| Write config files, read exit codes and JSON output | [Configuration](configuration.md) |
| Fix a problem | [Troubleshooting](troubleshooting.md) |
| Know which versions are supported and what stays stable | [Versioning and support](versioning-and-support.md) |
| Look up a term | [Glossary](glossary.md) |

## The short version

1. Install Java 25 and download `CimPal-CLI.jar` from the [releases](https://github.com/griddigit-ci/CimPal/releases), or pull the Docker image.
2. Describe a validation in a JSON config: mapping CSV, models folder, constraints folder, output folder.
3. Run `validate --config run.json --format json`. Exit code `0` means all conforming and `1` means violations were found. An Excel report goes to the output folder.
4. To automate it, use the exit code and the JSON summary.
