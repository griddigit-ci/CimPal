<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Installation

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

Each [release](https://github.com/griddigit-ci/CimPal/releases) publishes three files and a container image:

| What | For | Needs |
|---|---|---|
| `CimPal-CLI.jar` | The command line, `serve` and `mcp` (Windows, Linux, macOS) | Java 25 runtime |
| `ghcr.io/griddigit-ci/cimpal:<version>` | The same CLI in a container (linux/amd64, linux/arm64) | Docker or Kubernetes |
| `CimPal.exe` | The desktop application on Windows | Java 25 runtime, found through `JAVA_HOME` or `PATH` |
| `CimPal.jar` | The desktop application on any platform | Java 25 runtime (JavaFX is included) |

Pick a release version and use it everywhere: in scripts, configs and image tags. `latest` exists for the image, but pinned versions make runs repeatable.

## Java 25

CimPal needs a Java 25 runtime (JRE or JDK), for example Eclipse Temurin 25.

```powershell
winget install EclipseAdoptium.Temurin.25.JRE
java -version
```

```bash
# Debian/Ubuntu with the Adoptium repository set up; or download from https://adoptium.net
sudo apt-get install temurin-25-jre
java -version
```

`java -version` must report 25 or later.

## The command line (JAR)

1. Download `CimPal-CLI.jar` from the release page into a folder of your choice, e.g. `C:\Tools\CimPal` or `/opt/cimpal`.
2. Check that it runs:

```powershell
java -jar C:\Tools\CimPal\CimPal-CLI.jar --version
java -jar C:\Tools\CimPal\CimPal-CLI.jar --help
```

```bash
java -jar /opt/cimpal/CimPal-CLI.jar --version
java -jar /opt/cimpal/CimPal-CLI.jar --help
```

Nothing else needs installing. The JAR contains all its libraries, and CimPal keeps only a fetch cache in your user profile (`%LOCALAPPDATA%\CimPal` or `~/.cimpal`).

## The container image

```bash
docker pull ghcr.io/griddigit-ci/cimpal:2026.10.6.1
docker run --rm ghcr.io/griddigit-ci/cimpal:2026.10.6.1 --version
```

- **User:** the image runs as the non-root user `cimpal` (UID 10001).
- **Files:** it works on files you mount at `/data`.
- **Memory:** it sizes the Java heap from the container's memory limit.

[Running CimPal in Docker](../cli/docker.md) covers mounts, file ownership, memory, certificates and `serve` in a container.

## The desktop application

- **Windows:** download `CimPal.exe` and start it. It looks for Java 25 through `JAVA_HOME`, then `PATH`.
- **Linux and macOS:** run `java -jar CimPal.jar`.

## Verifying a download

- **JAR and EXE files:**
  1. GitHub shows a SHA-256 digest next to each release asset. Compare it with your copy:
     ```powershell
     Get-FileHash CimPal-CLI.jar -Algorithm SHA256
     ```
     ```bash
     sha256sum CimPal-CLI.jar
     ```
  2. The release workflow also prints the CLI JAR's SHA-256 in its log, and the image build checks the JAR it packages against it.
- **Container image:** it carries an SBOM and a build provenance attestation:
  ```bash
  docker buildx imagetools inspect ghcr.io/griddigit-ci/cimpal:2026.10.6.1 --format "{{ json .SBOM }}"
  docker buildx imagetools inspect ghcr.io/griddigit-ci/cimpal:2026.10.6.1 --format "{{ json .Provenance }}"
  ```

The binaries and the image are not yet signed. Signing is planned with the supply-chain work (CI-2).

## Next

- [Quickstart](quickstart.md): validate a sample model.
- [Sizing](sizing.md): memory for your model size.
