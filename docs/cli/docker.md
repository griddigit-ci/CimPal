<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Docker image

Every release also publishes the CLI as a container image:

```
ghcr.io/griddigit-ci/cimpal:<version>     e.g. ghcr.io/griddigit-ci/cimpal:2026.10.1.1
ghcr.io/griddigit-ci/cimpal:latest
```

The image holds the same `CimPal-CLI.jar` as the GitHub release, on the Eclipse Temurin 25 JRE (Ubuntu 24.04), for `linux/amd64` and `linux/arm64`. The GUI is not in it. Commands, flags, config files, JSON output and exit codes are exactly those of `java -jar CimPal-CLI.jar`, so the rest of `docs/cli/` applies unchanged.

---

## Quick start

```
docker pull ghcr.io/griddigit-ci/cimpal:latest
docker run --rm ghcr.io/griddigit-ci/cimpal:latest --version
docker run --rm ghcr.io/griddigit-ci/cimpal:latest validate --help
```

Without arguments the image prints the top-level help.

---

## Working with files

The container only sees what you mount. Mount your working folder at `/data` (the image's working directory) and give the CLI paths **inside the container**.

Windows (PowerShell):
```powershell
docker run --rm -v "C:\Data:/data" ghcr.io/griddigit-ci/cimpal:latest `
  validate --config /data/validate-mapping.json --format json
```

Linux and macOS:
```bash
docker run --rm -v "$PWD:/data" ghcr.io/griddigit-ci/cimpal:latest \
  validate --config /data/validate-mapping.json --format json
```

- Every path, in flags **and in config files**, is a container path: `/data/models`, not `C:\Data\models`. A config written for the JAR on Windows needs its paths changed.
- Paths inside the container are case-sensitive.
- `docker run` returns the CLI's exit code (`0` ok, `1` violations found, `2` bad input, `3` internal error), so CI scripts check it exactly as with the JAR.
- With `--format json`, stdout holds only the JSON and progress goes to stderr, as with the JAR.

### File ownership on Linux hosts

The image runs as the non-root user `cimpal` (UID 10001). With Docker Desktop on Windows or macOS, files written to a bind mount belong to you anyway. On a Linux host they would belong to UID 10001, so run the container as yourself:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/data" ghcr.io/griddigit-ci/cimpal:latest \
  validate --config /data/validate-mapping.json
```

Any UID works. A UID other than 10001 gets a private temporary home for the run, so CimPal's `~/.cimpal` cache is never shared between users. That cache is not kept after the run.

---

## Memory

The JVM may use up to 75 % of the memory the container gets (`-XX:MaxRAMPercentage=75`). Give the container a limit with `--memory`, or set the heap directly; an explicit `-Xmx` wins:

```
docker run --rm -e JAVA_TOOL_OPTIONS=-Xmx8g -v "C:\Data:/data" ghcr.io/griddigit-ci/cimpal:latest validate --config /data/run.json
```

The JVM then prints `Picked up JAVA_TOOL_OPTIONS: ...` on stderr, with the whole value, and that ends up in `docker logs` and in the MCP client's log. Never put secrets, such as proxy passwords, in it. With Docker Desktop, the memory of its Linux VM is the upper bound (WSL 2: `.wslconfig`; otherwise Settings → Resources).

---

## Remote `owl:imports` and certificates

Remote imports are fetched only from the hosts on CimPal's allowlist (`raw.githubusercontent.com`, `api.github.com`, `github.com`), so the container needs outbound HTTPS to them. Pass a GitHub token with `-e GITHUB_TOKEN`. With `--network none` a remote import fails as an error; it never passes silently.

The fetch cache lives in `/home/cimpal/.cimpal` inside the container (other UIDs: in their temporary home), so `--rm` discards it. A long-running `mcp` or `serve` container keeps it.

If a proxy or antivirus re-signs HTTPS (TLS inspection), the JVM has to trust that root certificate. Put it in a folder as a PEM `.crt` file, mount the folder at `/certificates` and set `USE_SYSTEM_CA_CERTS=1`:

```
docker run --rm -e USE_SYSTEM_CA_CERTS=1 -v "C:\Certs:/certificates:ro" -v "C:\Data:/data" `
  ghcr.io/griddigit-ci/cimpal:latest validate --config /data/run.json --format json
```

This is the Temurin base image's certificate hook. Its messages go to stderr, so `--format json` and `mcp` output stay clean. Two things to know:
- The inspecting proxy sees the requests in clear text, including the `GITHUB_TOKEN` header.
- The hook adds Ubuntu's CA bundle to the JVM trust store as well as your certificate.

---

## MCP server for Claude Desktop

Claude Desktop starts the container and talks to it over stdio. Use `-i` (keep stdin open), never `-t`:

```json
{
  "mcpServers": {
    "cimpal": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-v", "C:/Data:/data", "ghcr.io/griddigit-ci/cimpal:latest", "mcp"]
    }
  }
}
```

- Tool arguments are container paths (`/data/...`), so tell the agent where your files are mounted.
- Prefer read-only inputs and a separate writable output folder: `"-v", "C:/Data/models:/data/models:ro", "-v", "C:/Data/out:/data/out"`.
- For a GitHub token, add `"-e", "GITHUB_TOKEN"` to `args` (no value, before the image name) and the value under `"env": {"GITHUB_TOKEN": "..."}` of the server entry. Docker then takes it from the environment Claude Desktop starts it with. A value written into `args` would be visible to anyone who can list processes.
- The ready-to-edit template is `CimPal-CLI/configs/claude-desktop-config-docker.json`.
- Docker Desktop must be running when Claude Desktop starts the server.
- When Claude Desktop stops the server, stdin closes, `mcp` exits and `--rm` removes the container. This also happens when the `docker` client is killed.
- Unlike the JAR, the image is not locked by Windows while the server runs, so `mvn package` keeps working.

---

## `serve`

> **Not for network use yet.** `serve` has no authentication and an open `/shutdown` (gap G1, closed by work package SEC-1). Anyone who can reach its port can read and write every file the container can see.

Inside a container `serve` has to listen on all interfaces, because `localhost` there is the container itself. So keep the port on the host's loopback, put the container on a network of its own, and give it as little as possible:

```
docker network create cimpal-serve
docker run -d --name cimpal-serve --network cimpal-serve -p 127.0.0.1:7474:7474 `
  -v "C:\Data\models:/data/models:ro" -v "C:\Data\out:/data/out" `
  ghcr.io/griddigit-ci/cimpal:latest serve --host 0.0.0.0
curl http://localhost:7474/health
docker stop cimpal-serve
```

- The dedicated network keeps other containers away from the port. On the default bridge network, every container can reach it.
- Mount the inputs read-only; only the output folder is writable.
- Pass no secrets, such as `GITHUB_TOKEN`, to a `serve` container until SEC-1. Any caller could make CimPal use them.
- Never use `-p 7474:7474` or `-P`. They publish on every interface of the host, and on Linux Docker's published ports can bypass host firewall rules such as ufw.
- On Linux, use Docker Engine 28 or later. It blocks direct access to container ports from other hosts on the local network, which older engines allowed.
- Stop it with `docker stop`. `POST /shutdown` stops the HTTP listener but leaves the process, and so the container, running. This is a known issue, handed to SEC-1.
- Request paths are container paths, as above.

---

## Validation engines

The image contains no Python, so only the default `APACHE_JENA` engine works. `PYSHACL`, `PYSHACL_OXIGRAPH` and `RUST_SHACL` fail with "No Python interpreter with the required ... package".

---

## Image details

| Item | Value |
|---|---|
| Base | `eclipse-temurin:25-jre-noble`, pinned by digest in `CimPal-CLI/docker/Dockerfile` |
| Platforms | `linux/amd64`, `linux/arm64` |
| User | `10001:10001` (`cimpal`), `HOME=/home/cimpal`; any other UID gets a private temporary home |
| Working directory | `/data` |
| Entrypoint | `/opt/cimpal/entrypoint.sh`, which runs `java -XX:MaxRAMPercentage=75 -jar /opt/cimpal/CimPal-CLI.jar` |
| Default command | `--help` |
| Exposed ports | none |
| Licence | EUPL-1.2-or-later; the text is at `/opt/cimpal/LICENSE.md` |

Release images carry SBOM and provenance attestations:

```
docker buildx imagetools inspect ghcr.io/griddigit-ci/cimpal:<version> --format "{{ json .SBOM }}"
docker buildx imagetools inspect ghcr.io/griddigit-ci/cimpal:<version> --format "{{ json .Provenance }}"
```

---

## Building and testing locally

From the repository root:

```
mvn -B -pl CimPal-CLI -am package -DskipTests
docker build -f CimPal-CLI/docker/Dockerfile -t cimpal:dev .
./scripts/Test-DockerImage.ps1 -Image cimpal:dev
```

The Dockerfile only copies the built JAR; it does not run Maven. `Test-DockerImage.ps1` checks:
- the version, the numeric non-root user, that no port is exposed, and that the binaries are read-only;
- a mapping validation into a bind mount (JSON on stdout, Excel and Turtle reports on disk);
- that the cache stays private for the image user and for another UID;
- that a remote `owl:imports` fails closed with `--network none`;
- MCP over stdio, with and without `USE_SYSTEM_CA_CERTS`;
- `serve` `/health` on a loopback port.

CI runs it on every push and pull request (job "Docker image"), and the release runs it before pushing.
