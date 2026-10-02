<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# `serve` — HTTP Daemon Command

Starts a local HTTP server that exposes every CimPal operation as a REST endpoint. The server accepts JSON request bodies (same format as command config files) and returns JSON responses.

**Why use the daemon instead of the CLI?**  
Every CLI invocation starts a fresh JVM and re-initialises Jena, the SHACL engine, and all loaded libraries. For a single run this overhead is negligible. For an automated validation agent that calls CimPal dozens or hundreds of times per session, keeping the JVM warm eliminates repeated cold-start cost.

---

## Quick start

```
java -jar CimPal-CLI.jar serve
```

The server starts on `localhost:7474`. It creates a new bearer token on every start and writes it to a token file that only you can read. The startup output shows the file's path, never the token. Every endpoint except `GET /health` needs the token (see [Security](#security)).

Check that it's alive (no token needed):
```
curl http://localhost:7474/health
# {"status":"ok","version":"CimPal CLI 2026.9"}
```

Read the token and call an endpoint (Git Bash / Linux):
```bash
TOKEN=$(cat "$LOCALAPPDATA/CimPal/serve.token")    # Linux/macOS: ~/.cimpal/serve.token
curl -H "Authorization: Bearer $TOKEN" http://localhost:7474/commands
```

PowerShell:
```powershell
$token = Get-Content "$env:LOCALAPPDATA\CimPal\serve.token"
Invoke-RestMethod http://localhost:7474/commands -Headers @{ Authorization = "Bearer $token" }
```

Stop it cleanly:
```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  http://localhost:7474/shutdown
```

Or press `Ctrl-C`. The token file is removed when the server stops.

---

## All flags

| Flag | Type | Default | Description |
|---|---|---|---|
| `--port` / `-p` | integer | `7474` | Port to listen on. |
| `--host` | string | `localhost` | Bind address. The default `localhost` means the server is not accessible from the network, only from processes on the same machine. A non-loopback address also needs `--allow-remote`. |
| `--allow-remote` | flag | off | Allow a non-loopback `--host`. The token is still required, and a warning is printed. Traffic is plain HTTP, so only use this on a trusted network. |
| `--token-file` | path | `%LOCALAPPDATA%\CimPal\serve.token` (Windows), `~/.cimpal/serve.token` (elsewhere) | Where the token is written. Not used when `CIMPAL_API_TOKEN` is set. |
| `--allow-origin` | string (repeatable) | none | Browser `Origin` that may call the server, e.g. `http://localhost:3000`. |
| `--max-body-bytes` | integer | `1048576` (1 MB) | Largest accepted request body. |
| `--queue-size` | integer | `4` | Requests that may wait while one runs. More get 503. |
| `--request-timeout` | ISO-8601 duration | `PT30M` | Time a request may take, including time spent waiting in the queue. Longer gets 504. |

| Environment variable | Description |
|---|---|
| `CIMPAL_API_TOKEN` | Use this token instead of generating one (at least 32 characters). No token file is written. Useful when a supervisor starts the server and hands the token to the agent. |

---

## Security

`serve` runs CimPal commands, which read and write files, on behalf of whoever calls it. The server is therefore closed to everything except the local user who started it:

| Check | Rejected with |
|---|---|
| **Host header** must be `localhost`, `127.0.0.1` or `[::1]` with the server's port (with `--allow-remote`, also the bound host). This blocks DNS-rebinding attacks from web pages. | 403 |
| **Origin header**, if present, must be listed in `--allow-origin`. Browsers send it on cross-site requests, so pages you visit can't call the server. No CORS headers are ever sent. | 403 |
| **Path** must match an endpoint exactly (`/validatefoo` is not `/validate`). | 404 |
| **Method**: commands and `/shutdown` are POST only; `/health` and `/commands` are GET only. | 405 |
| **Token**: every endpoint except `GET /health` needs `Authorization: Bearer <token>`. The comparison is constant-time, and the token never appears in logs or responses. | 401 |
| **Content type**: POST bodies must be `application/json` (a charset parameter is fine). HTML forms can't send that without a CORS preflight. | 415 |
| **Body size**: at most `--max-body-bytes`. | 413 |
| **Queue**: one command runs at a time and at most `--queue-size` wait. | 503 (with `Retry-After`) |
| **Timeout**: after `--request-timeout`, the caller gets 504. | 504 |

The token is 256 random bits, new on every start. The token file is created with owner-only permissions before the token is written into it: POSIX `rw-------`, or on Windows an ACL that grants only your account (looked up as `%USERDOMAIN%\%USERNAME%`). A link already at the path is replaced, not followed. On POSIX, a token directory that other users can write to is refused. The file is deleted when the server stops, unless another `serve` instance has since written its own token there. Anyone who can read the file can call the server, so don't copy it to shared locations.

If Windows can't look up your account by its qualified name (for example on some Entra ID machines), the file is created first and restricted to its owner before the token is written. With the default location under `%LOCALAPPDATA%`, which only you can read, that makes no difference. With `--token-file` in a shared folder, prefer `CIMPAL_API_TOKEN` there.

A command that times out while still waiting in the queue never runs. A command that times out while running is left to finish, because interrupting it could leave half-written reports behind. It keeps its place in the queue until it returns, so the queue limit still holds, but the worker stays busy. Restart the server if a command hangs.

Limits that protect the server itself:
- A request (headers and body) must arrive within 60 seconds, so a client can't hold a connection open by sending slowly. Commands that run longer than that are not affected. This is the JDK setting `sun.net.httpserver.maxReqTime`; set `-Dsun.net.httpserver.maxReqTime=<seconds>` to change it. It applies to the whole JVM, and a body that takes longer than this to upload (e.g. a large body over a slow `--allow-remote` link) is dropped.
- This bounds how long one connection is held, not how many a client opens. A local process that keeps opening slow connections can still slow the server down. That is a limit of this design, and it needs local access unless `--allow-remote` is used.
- `--queue-size` can be at most 64, and `--max-body-bytes` at most 64 MB.

---

## API reference

### Command endpoints — `POST /<command>`

Each subcommand is exposed as a `POST` endpoint. The request body is a JSON object with the same keys the command's config file accepts. The response is the JSON output the command produces (equivalent to running with `--format json`).

| Endpoint | Equivalent CLI command |
|---|---|
| `POST /validate` | `cimpal validate --format json --config <body>` |
| `POST /sparql` | `cimpal sparql --format json --config <body>` |
| `POST /convert` | `cimpal convert --config <body>` |
| `POST /compare` | `cimpal compare --format json --config <body>` |
| `POST /compare-instances` | `cimpal compare-instances --format json --config <body>` |
| `POST /rdfs2shacl` | `cimpal rdfs2shacl --config <body>` |
| `POST /organize` | `cimpal organize --config <body>` |
| `POST /excel2shacl` | `cimpal excel2shacl --config <body>` |
| `POST /gen-instances` | `cimpal gen-instances --config <body>` |
| `POST /manifest` | `cimpal manifest --config <body>` |

### Utility endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/health` | `GET` | Returns `{"status":"ok","version":"...","busy":false,"stalled":false,"queued":0}`. `busy` means a command is running, and `queued` is the number waiting. `status` becomes `"stalled"` (and `stalled` true) when the running command has taken longer than `--request-timeout`: it can't be stopped, so restart the server if it doesn't return. The only endpoint that needs no token. |
| `/commands` | `GET` | Lists all available command and utility endpoints. Needs the token. |
| `/shutdown` | `POST` | Stops the server gracefully. Needs the token and `Content-Type: application/json`. |

`run`, `serve` and `mcp` aren't exposed as endpoints.

---

## HTTP status codes

| Code | Meaning |
|---|---|
| 200 | Command ran successfully (including when violations were found — exit 0 or 1) |
| 400 | Bad request — invalid input, missing required field (exit 2) |
| 401 | Missing or wrong bearer token |
| 403 | Host or Origin header not allowed |
| 404 | No such endpoint |
| 405 | Method not allowed — wrong HTTP verb for the endpoint |
| 413 | Request body larger than `--max-body-bytes` |
| 415 | POST without `Content-Type: application/json` |
| 500 | Internal error — command crashed (exit 3) |
| 503 | Queue full (`Retry-After` header set) |
| 504 | Command didn't finish within `--request-timeout` |

The response body always contains JSON. On errors, it has `{"error":"..."}`. On 200, it contains the same JSON the `--format json` flag would produce on stdout.

---

## Example: validate a model set

```bash
curl -s -X POST http://localhost:7474/validate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "workflow": "mapping",
    "mappingCsv": "C:/Data/mapping.csv",
    "modelsDir": "C:/Data/models",
    "constraintsRoot": "C:/Data/constraints",
    "outputDir": "C:/Data/output",
    "datatypeMap": "CGMES30NC25",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "samples": 3
  }'
```

Response:
```json
{
  "schema": "cimpal-validate-summary/1",
  "run": { ... },
  "totals": {"conforming": 5, "violations": 42, "errors": 0, "total": 47},
  "hasViolations": true,
  "shapes": [
    {
      "shapeId": "http://example.org/shapes/EQ#VoltageLevel.highVoltageLimit",
      "constraint": "sh:MinCountConstraintComponent",
      "path": "http://iec.ch/TC57/CIM100#VoltageLevel.highVoltageLimit",
      "count": 15,
      "sampleFocusNodes": ["http://example.org#_vl1", "http://example.org#_vl2"]
    }
  ],
  "report": "C:/Data/output/validation_report__20260920_143022.xlsx"
}
```

---

## Example: run a SPARQL query

```bash
curl -s -X POST http://localhost:7474/sparql \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "models": ["C:/Data/EQ.xml", "C:/Data/TP.xml"],
    "query": "SELECT ?s ?type WHERE { ?s a ?type } LIMIT 10",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "format": "json"
  }'
```

---

## File paths in requests

All file paths in request bodies must be **absolute paths**. The server and the caller share a filesystem (the daemon runs locally), so the files must be accessible from the machine where the server is running.

There is no file upload mechanism — the server reads from and writes to local paths that are specified in the request JSON.

---

## Thread safety and concurrency

Commands run on a **single worker thread**, one at a time. This avoids race conditions on the static flags in `ValidationTools` (debug mode, Turtle report export). For the intended use case, a local validation agent sending one request at a time, this is the right trade-off: a single validation run already saturates the available CPU cores through its own worker pool. While one command runs, up to `--queue-size` requests wait, and more are rejected with 503. `/health` still answers while commands run.

If you need to run multiple pipelines concurrently, start multiple daemon instances on different ports.

---

## Integration with the agent

The MCP tool wrappers for the validation agent call the daemon endpoints. Typical usage:

```python
# Python example — send validate request, read shapes
import os, pathlib, requests

local = os.environ.get("LOCALAPPDATA")   # set on Windows
token_file = (pathlib.Path(local, "CimPal", "serve.token") if local
              else pathlib.Path.home() / ".cimpal" / "serve.token")
headers = {"Authorization": f"Bearer {token_file.read_text().strip()}"}

# json= sets Content-Type: application/json
resp = requests.post("http://localhost:7474/validate", headers=headers, json={
    "workflow": "mapping",
    "mappingCsv": mapping_csv,
    "modelsDir": models_dir,
    "constraintsRoot": constraints_root,
    "outputDir": output_dir,
    "datatypeMap": "CGMES30NC25",
    "xmlBase": "http://iec.ch/TC57/CIM100",
    "samples": 5
})
result = resp.json()
if result.get("hasViolations"):
    for shape in result.get("shapes", []):
        print(f"{shape['count']} violations on {shape['shapeId']}")
```

---

## Starting automatically

To keep the daemon running as a background process (Windows):

```powershell
Start-Process -FilePath "java" `
  -ArgumentList "-jar", "CimPal-CLI.jar", "serve", "--port", "7474" `
  -WindowStyle Hidden
```

Or add it to a startup script that runs before your agent session begins. The agent reads the token from the token file after the server has started. To supply a fixed token instead, set `CIMPAL_API_TOKEN` in the server's environment.
