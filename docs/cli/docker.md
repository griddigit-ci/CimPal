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

- Tool arguments are container paths (`/data/...`), so tell the agent where your files are mounted. `/data`, the working directory, is also the default `--root`, so `mcp` refuses paths outside it. Mount everything the tools need below `/data`, or add `--root` after `mcp`. Never mount your home folder or a drive root at `/data`: the container can't tell, so SEC-2's refusal of those as default roots doesn't help there.
- Prefer read-only inputs and a separate writable output folder: `"-v", "C:/Data/models:/data/models:ro", "-v", "C:/Data/out:/data/out"`.
- For a GitHub token, add `"-e", "GITHUB_TOKEN"` to `args` (no value, before the image name) and the value under `"env": {"GITHUB_TOKEN": "..."}` of the server entry. Docker then takes it from the environment Claude Desktop starts it with. A value written into `args` would be visible to anyone who can list processes.
- The ready-to-edit template is `CimPal-CLI/configs/claude-desktop-config-docker.json`.
- Docker Desktop must be running when Claude Desktop starts the server.
- When Claude Desktop stops the server, stdin closes, `mcp` exits and `--rm` removes the container. This also happens when the `docker` client is killed.
- Unlike the JAR, the image is not locked by Windows while the server runs, so `mvn package` keeps working.

---

## `serve`

`serve` needs a bearer token on every request except `GET /health`, and it checks the Host header ([serve](serve.md#security)). In a container that means:
- **`--host 0.0.0.0 --allow-remote`.** The server has to listen on all interfaces, because `localhost` there is the container itself. It then prints a warning about the non-loopback address; that is expected.
- **A new token for each start, through `CIMPAL_API_TOKEN`** (at least 32 characters).
  - `-e CIMPAL_API_TOKEN` without a value copies it from the environment of the `docker` command. So it doesn't appear on the docker command line, and you don't have to fetch a token file out of the container.
  - Never write `-e CIMPAL_API_TOKEN=<value>`.
  - If `docker logs` shows "Bearer token written to ...", the variable didn't arrive.
- **The same port number inside and outside, on the host loopback.** The Host header must be `localhost`, `127.0.0.1` or `[::1]` with the port the server listens on. So `-p 127.0.0.1:7474:7474` works, but a remapped `-p 127.0.0.1:8080:7474` gets 403. For another port, change both: `--port 8080` and `-p 127.0.0.1:8080:8080`.

PowerShell (also Windows PowerShell 5.1):
```powershell
$bytes = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
docker network create cimpal-serve
$env:CIMPAL_API_TOKEN = $token
docker run -d --rm --name cimpal-serve --network cimpal-serve -p 127.0.0.1:7474:7474 -e CIMPAL_API_TOKEN `
  -v "C:\Data\models:/data/models:ro" -v "C:\Data\out:/data/out" `
  ghcr.io/griddigit-ci/cimpal:latest serve --host 0.0.0.0 --allow-remote
$env:CIMPAL_API_TOKEN = $null
Invoke-RestMethod http://localhost:7474/commands -Headers @{ Authorization = "Bearer $token" }
```

Bash:
```bash
token=$(openssl rand -hex 32)
docker network create cimpal-serve
CIMPAL_API_TOKEN=$token docker run -d --rm --name cimpal-serve --network cimpal-serve \
  --user "$(id -u):$(id -g)" -p 127.0.0.1:7474:7474 -e CIMPAL_API_TOKEN \
  -v "$PWD/models:/data/models:ro" -v "$PWD/out:/data/out" \
  ghcr.io/griddigit-ci/cimpal:latest serve --host 0.0.0.0 --allow-remote
curl -H @<(printf 'Authorization: Bearer %s\n' "$token") http://localhost:7474/commands
```

In Bash `$token` is not exported. `curl -H @file` (curl 7.55 or later) reads the header from the process substitution, so the token stays off curl's command line too.

- **The token is the real barrier.** The Host check stops browsers, which defeats DNS rebinding, but any other client can send `Host: localhost:7474`. Other local users, processes on the host, and on Linux anything that can reach the container's bridge IP can all try the port. Only the token keeps them out, so treat it like a password: a new one per start, never shared or reused.
- **Request paths** are container paths. They must lie under the allowed roots, which default to `/data`, the working directory; add `--root` for others. Existing outputs need `"overwrite": true`.
- **Never mount your home folder or a drive root at `/data`.** Inside the container `serve` and `mcp` only see `/data`, so SEC-2's refusal of a home folder or drive root as the default root can't protect you. Mount only what the commands need.
- **Stopping:** use `POST /shutdown` (token and `Content-Type: application/json`) or `docker stop`. Either way the container ends, and `--rm` removes it together with its environment.
- **Traffic is plain HTTP**, so keep the port on the host loopback. Never use `-p 7474:7474` or `-P`: they publish on every interface of the host, and on Linux Docker's published ports can bypass host firewall rules such as ufw.
- **Not with `--network host`.** There `-p` is ignored, and `--host 0.0.0.0 --allow-remote` listens on every interface of the host. With host networking, use `serve --host 127.0.0.1` without `--allow-remote`.
- **The dedicated network** keeps containers on the default bridge from connecting at all. Mount inputs read-only, with only the output folder writable.
- **`docker inspect`** shows the container's environment, token included, to anyone who can use Docker. On Linux that access is root-equivalent anyway.
- **Secrets:** anyone with the token can make CimPal use a `GITHUB_TOKEN` you pass to the container, so give a `serve` container only the secrets it needs.
- **Docker Engine:** on Linux, use version 28 or later. It blocks direct access to container ports from other hosts on the local network, which older engines allowed.

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
- `serve` set up as above: `/health`, 403 for a remapped port, 401 without the token, and `POST /shutdown`.

CI runs it on every push and pull request (job "Docker image"), and the release runs it before pushing.
