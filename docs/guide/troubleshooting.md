<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Troubleshooting

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later*

Start with the exit code and stderr. CimPal writes progress and errors to stderr, and with `--format json` only the result goes to stdout.

## "Out of memory" (exit code 3)

```
[ERROR] Out of memory (max heap N MB). Give the JVM more memory (-Xmx, or the container's memory limit); see docs/guide/sizing.md for sizes by model.
```

The model needed more heap than the JVM had. The run stopped and reported nothing as conforming.
- **JAR:** raise `-Xmx`, e.g. `java -Xmx6g -jar CimPal-CLI.jar …`.
- **Container:** raise the memory limit (`docker run --memory 8g`, or the Kubernetes `limits.memory`). The heap is 75 % of it.
- **Docker Desktop:** its VM's memory is the upper bound (WSL 2: `.wslconfig`).
- **Plan the size:** see [Sizing](sizing.md). For a model that only just fits, `--stats` shows how close it came (`peakHeapBytes`, `gcMs`).

`serve` and `mcp` stop after an out-of-memory error: restart them with more memory.

## Exit code 2

The input is wrong; stderr names the problem. Common causes:
- **A missing file or folder:** paths in a config file are relative to the **config file**, not to the current directory.
- **A container path that doesn't match the mount:** `/data/...` inside the container is the folder you mounted.
- **A path outside the allowed folders** (`serve`, `mcp`, `run`): add a `--root`, or move the files.
- **An existing output file** in a `serve`/`mcp`/`run` request: set `"overwrite": true`.
- **A bad option value,** e.g. `--violations-exit-code` outside 0–255.

## `serve` answers with an error

| Status | Meaning | What to do |
|---|---|---|
| 401 | No or wrong bearer token | Send `Authorization: Bearer <token>`. The token is new on every start: read it from the token file, or set `CIMPAL_API_TOKEN` before starting. |
| 403 | Refused: a path outside the allowed folders, a wrong `Host` header, or a browser `Origin` | Check the paths and `--root`. Call it as `localhost:<port>` with the same port the server listens on (in Docker, publish the same port inside and outside). Add `--allow-origin` only for a browser tool you trust. |
| 413 | Request body over `--max-body-bytes` (1 MB) | Put long lists in a config file and pass its path |
| 415 | No `Content-Type: application/json` | Add the header |
| 503 | Queue full: one command runs and `--queue-size` wait | Retry later (see `Retry-After`), or run more instances |
| 504 | The command didn't finish within `--request-timeout` (30 min) | Raise the timeout, or run large validations with the CLI or container instead |
| 500 | Internal error (exit 3), including out of memory | See stderr of the server |

## `owl:imports` can't be fetched

The validation reports an error for the row, not a pass. Check:
- **The host:** imports are fetched only from `raw.githubusercontent.com`, `api.github.com` and `github.com`. Other hosts are refused. Put the files next to the constraints and import them locally instead.
- **Network:** the container or machine needs outbound HTTPS to those hosts. With `--network none` every remote import fails, as intended.
- **Proxy with TLS inspection:** the JVM must trust the proxy's root certificate.
  - JAR: import it into the Java trust store.
  - Container: mount it at `/certificates` and set `USE_SYSTEM_CA_CERTS=1` (see [docker.md](../cli/docker.md)).
- **Rate limits:** set `GITHUB_TOKEN`.

## Runs are slow

- **Measure first:** run with `--stats`. A GC share (`gcMs / wallMs`) above about 10 % means the heap is too small.
- **Cores:** give the run 2–4 cores. More helps little for one model ([Sizing](sizing.md#cores-at-twice-the-smallest-heap)).
- **Quick checks:** `--max-results 10` stops collecting after 10 findings per shape.
- **`serve`:** commands run one at a time, so a long validation blocks the queue. Use separate CLI or container runs for large models.

## Windows: "Could not create modular JAR file" when building

This only matters if you build CimPal yourself. Windows locks `CimPal-CLI/target/CimPal-CLI.jar` while a process uses it, e.g. Claude Desktop running `mcp` from it. Stop that process, or point Claude Desktop at a copy of the JAR or at the Docker image.

## The JSON on stdout is broken or mixed with other text

- **Logging options in `JAVA_OPTS`:** in the container image, JVM messages go to stderr, but options such as `-verbose:gc`, a bare `-Xlog:gc` or `-showversion` write to stdout. Send them to stderr (`-Xlog:gc:stderr`) or a file.
- **Your own wrapper script:** make sure it doesn't print to stdout.

## Still stuck

Open an issue on [GitHub](https://github.com/griddigit-ci/CimPal/issues) with:
- the CimPal version (`--version`);
- the command;
- the exit code;
- the stderr output, with any confidential paths or names removed.

For security problems, see [Versioning and support](versioning-and-support.md#reporting-a-vulnerability).
