<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Glossary

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

## CIM, CGMES and models

- **CIM (Common Information Model):** the IEC 61970/61968 data model for power systems. CimPal works with CIM 16 (CGMES 2.4.15) and CIM 17/CIM100 (CGMES 3.0).
- **CGMES (Common Grid Model Exchange Standard):** ENTSO-E's profile of CIM for exchanging grid models between TSOs. A model set is split into **profiles**.
- **Profile:** one part of a model set, such as **EQ** (equipment), **SSH** (steady-state hypothesis), **TP** (topology), **SV** (state variables), **DL** (diagram layout), or the boundary set. Each profile is an RDF/XML file.
- **IGM / CGM:** an Individual Grid Model (one TSO's model) and a Common Grid Model (the merged models of several TSOs).
- **Header (`md:FullModel`):** the metadata block at the top of each file: profile, scenario time, dependencies.
- **Scenario time:** the point in time a model describes. The `timestamped` validation workflow groups files by it.
- **RDFS profile:** the RDF Schema that describes a profile's classes and properties. CimPal turns it into SHACL with `rdfs2shacl`.

## RDF and SHACL

- **RDF:** the graph data model behind CIM files. A **triple** is one statement (subject, property, value). CimPal's sizes are in triples.
- **RDF/XML, Turtle, JSON-LD:** file formats for RDF. CIM files are RDF/XML, and SHACL is usually written in Turtle.
- **SHACL:** the W3C Shapes Constraint Language: rules that RDF data must satisfy.
- **Shape:** a set of SHACL rules for some nodes, e.g. "every ACLineSegment has exactly one length". A **focus node** is the node a shape checks.
- **Constraint component:** one kind of rule: `sh:minCount`, `sh:datatype`, `sh:class`, `sh:sparql`, etc.
- **Violation / finding:** a focus node that breaks a rule. Severity can be Violation, Warning or Info.
- **`owl:imports`:** a statement in a constraint file that pulls in another file. CimPal loads local imports, and remote ones from an allowlist of GitHub hosts.
- **Mapping CSV:** CimPal's list of what to validate. Each row names model files (`xml_inputs`) and the constraint files (`ttl`) to check them against.
- **Datatype map:** tells CimPal the datatype of each CIM property, so values such as `1.5` are checked as numbers. Presets include `CGMES30NC25`.
- **SPARQL:** the query language for RDF. CimPal runs SELECT queries (`sparql`) and SHACL-SPARQL rules. Federated `SERVICE` queries are disabled.

## Running CimPal

- **JAR:** the Java program file, `CimPal-CLI.jar`, run with `java -jar`.
- **JVM, heap, `-Xmx`:** the Java virtual machine runs CimPal. The heap is its working memory, and `-Xmx` sets the heap's maximum.
- **Exit code:** the number a command ends with: `0` ok, `1` violations found, `2` bad input, `3` internal error.
- **Allowed folders (roots):** the folders `serve`, `mcp` and `run` may read and write; set with `--root`, `--read-root`, `--write-root`.
- **Bearer token:** the secret every `serve` request must carry in its `Authorization` header.
- **MCP (Model Context Protocol):** the protocol AI assistants such as Claude use to call tools. `mcp` makes CimPal's commands available as tools.

## Deployment and pipelines

- **Docker image / container:** CimPal packed with Java 25, runnable anywhere with `docker run`; `ghcr.io/griddigit-ci/cimpal:<version>`.
- **GHCR:** the GitHub Container Registry, where the image is published.
- **Kubernetes (K8s):** runs containers across a cluster and gives each a CPU and memory budget (`requests` and `limits`).
- **PVC (PersistentVolumeClaim):** storage in Kubernetes that a pod can mount, e.g. at `/data`.
- **Sizing:** how much memory and how many cores a run needs for a model of a given size: [Sizing](sizing.md).
- **Airflow:** Apache Airflow, a workflow scheduler. A pipeline is a Python file called a **DAG**, made of **tasks**, and CimPal can be one task.
- **Operator / Hook / Sensor / Trigger:** Airflow building blocks.
  - An operator is a task type (e.g. `KubernetesPodOperator`, which runs one container per task).
  - A hook wraps a connection.
  - A sensor waits for a condition.
  - A trigger lets a deferrable operator wait without holding a worker.
- **XCom:** how Airflow passes small values (a few KB) between tasks. CimPal's JSON summary goes there; reports go to storage.
- **OpenAPI spec:** a machine-readable description of an HTTP API. Planned for CimPal's `/v1` API (DEP-5).
- **S3:** object storage. CimPal does not read S3 itself; copy the files to a volume first ([Airflow](airflow.md#files-volumes-and-s3)).
