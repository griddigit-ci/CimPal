<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal

## Dependencies

See [Dependencies](dependencies.md) for the full list of project dependencies.

## General
CimPal is an open-source Java application published by gridDigIt and licensed under the [EUPL-1.2-or-later](https://interoperable-europe.ec.europa.eu/collection/eupl/eupl-text-eupl-12).
The application is based on Apache Jena (Apache License v2.0), TopBraid SHACL API (Apache License v2.0), and JAVAFX (GPLv2+CE license).

CimPal helps CIM and CGMES implementation: it validates model data against SHACL constraints, authors and maintains those constraints, and shows what changed between versions of profiles and data. What it does:

- **Validation** of CIM/CGMES models against SHACL: by a mapping file, by timestamp, or a set of files as one dataset, with Excel reports and JSON results.
- **Shape authoring:** SHACL from RDFS profiles and from Excel templates, organising and testing constraint sets.
- **Conversion** between RDF/XML, Turtle and JSON-LD, and **comparison** of RDFS profiles, SHACL shapes and instance data.
- **SPARQL** queries over model files, CGMES instance generation and manifests.
- **Automation:** a command-line tool and Docker image, a local REST service (`serve`) with an async job API, an MCP server for AI assistants, a Python SDK and an Apache Airflow provider.

See the [capability statement](docs/guide/capabilities.md) for the details.

## Documentation

- **[User guide](docs/guide/index.md)**: installing, running, sizing, securing and integrating CimPal (CLI, Docker, `serve`, MCP, Apache Airflow), with a [capability statement](docs/guide/capabilities.md) and a [quickstart](docs/guide/quickstart.md).
- **[CLI reference](docs/cli/index.md)**: every command and option.

The command-line interface (validation, SPARQL, conversion, comparison, MCP server and more) is documented in [docs/cli](docs/cli/index.md). Each release ships it as `CimPal-CLI.jar` and as the Docker image `ghcr.io/griddigit-ci/cimpal` ([how to run it](docs/cli/docker.md)).

gridDigIt aims at providing support and further enhancement of the CimPal. Please address any requests either via the support email or in GitHub, by submitting an issue in the repository.

Web site: https://cimpal.app/ \
Support email: cimpal@griddigit.eu 

Copyright (c) 2020-2026 [gridDigIt Kft](https://griddigit.eu).
