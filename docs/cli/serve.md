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
# {"status":"ok","version":"CimPal CLI 2026.9.10.1"}
```

The version is the release version of the JAR.

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
| `--token-from-file` | path | none | Read the accepted token(s) from this file instead, e.g. a mounted secret. See [Token file](#token-file-for-services). Same as `CIMPAL_API_TOKEN_FILE`. |
| `--token-reload` | ISO-8601 duration | `PT60S` | How often the `--token-from-file` file is checked for changes. |
| `--allowed-host` | `name[:port]` (repeatable) | none | A name clients use to reach the server through a reverse proxy or ingress. See [Behind a proxy](#behind-a-reverse-proxy-or-ingress). |
| `--allow-origin` | string (repeatable) | none | Browser `Origin` that may call the server, e.g. `http://localhost:3000`. |
| `--max-body-bytes` | integer | `1048576` (1 MB) | Largest accepted request body. |
| `--queue-size` | integer | `4` | Requests that may wait while the workers are busy. More get 503. |
| `--workers` | integer | `1` (at most 64) | Commands that may run at once. See [Thread safety and concurrency](#thread-safety-and-concurrency). |
| `--max-concurrent-heap-bytes` | integer | three quarters of the maximum heap | With `--workers` above 1: a command starts only while the estimated heap of the running commands plus its own fits this. A command alone always starts. |
| `--request-timeout` | ISO-8601 duration | `PT30M` | Time a request may take, including time spent waiting in the queue. Longer gets 504. |
| `--job-timeout` | ISO-8601 duration | `PT2H` | A [`/v1` job](#job-api-v1) running longer than this is marked `timed_out` and `/v1/health` reports `stalled`. It is not interrupted. |
| `--max-jobs` | integer | `1000` | Jobs kept (100–100000), in memory or in the job store. Beyond this, the oldest finished jobs are dropped. |
| `--job-ttl` | ISO-8601 duration | `PT24H` | How long a finished job is kept. |
| `--job-log-lines` | integer | `5000` | Progress lines kept per job (1–100000); older lines are dropped. |
| `--max-result-bytes` | integer | `16777216` (16 MiB) | Largest job result kept. A larger result is answered with 410; write such results to a file. |
| `--job-store-bytes` | integer | a quarter of the maximum heap | Memory for the results and logs of all finished jobs together (at least 1048576). Beyond it the oldest finished jobs are dropped; the most recent one is always kept. Not used with `--job-store jdbc`, which keeps them in the database. |
| `--job-store` | `memory` or `jdbc` | `memory` (or `CIMPAL_JOB_STORE`) | Where jobs live. `jdbc`: in PostgreSQL, so they survive a restart; see [Jobs that survive a restart](#jobs-that-survive-a-restart). |
| `--job-store-url` | JDBC URL | none (or `CIMPAL_JOB_STORE_URL`) | `jdbc:postgresql://host:5432/db?sslmode=verify-full`. Must not hold the password. |
| `--job-store-user` | string | none (or `CIMPAL_JOB_STORE_USER`) | Database user. |
| `--job-store-password-file` | path | none (or `CIMPAL_JOB_STORE_PASSWORD_FILE`) | File with the database password, e.g. a mounted secret. `CIMPAL_JOB_STORE_PASSWORD` holds it directly. Never on the command line. |
| `--job-store-pool` | integer | `--workers` + 4 (at most 64) | Database connections. |
| `--idempotency-ttl` | ISO-8601 duration | `PT24H` (PT1M to P30D) | How long an `Idempotency-Key` is remembered. See [Safe retries](#safe-retries-idempotency-key). |
| `--callback-secrets` | path | none | `name:secret` file that signs each client's job callbacks. Without it, a job with a callback is refused. See [Completion callbacks](#completion-callbacks). |
| `--requeue-interrupted` | flag | off | With `jdbc`: after a restart, run jobs that were running again instead of failing them. Only for commands whose outputs are simply overwritten. |
| `--shutdown-grace` | ISO-8601 duration | `PT60S` | On SIGTERM, Ctrl-C or `POST /shutdown`: how long to wait for a running command. If it is still running then, `serve` exits with 3. |
| `--log-format` | `text` or `json` | `text` | `json`: one JSON object per line on stderr, nothing on stdout. See [Logs](#logs). |
| `--metrics` | flag | off | Serve Prometheus metrics at `GET /metrics` (needs an admin token). |
| `--client-quotas` | path | none | JSON file of per-client limits, read at start. See [Clients and quotas](#clients-and-quotas). |
| `--work-root` | path | `.cimpal-work` in the first `--write-root`, else in the first `--root` | Folder for [uploads and job outputs](#file-exchange-uploads-and-downloads). No request path may point into it.<br>**Start:** it is emptied at every start, but only if it carries `serve`'s `.cimpal-work` marker. A folder that is not empty and has no marker is refused.<br>**Refused:** a work folder that is, or contains, a `--root`/`--read-root`/`--write-root`.<br>**Read-only data folder:** if the default can't be created (e.g. a read-only `/data` in a container), `serve` starts without file exchange and logs a warning. |
| `--no-file-exchange` | flag | off | Turn uploads and downloads off: no work folder, and those routes answer 404. |
| `--max-upload-bytes` | integer | `2147483648` (2 GiB, at most 16 GiB) | Largest upload. |
| `--upload-ttl` | ISO-8601 duration | `PT24H` | How long an upload is kept once no queued or running job uses it. |
| `--work-max-bytes` | integer | `21474836480` (20 GiB) | Disk space for uploads and job outputs together; an upload that doesn't fit gets 507. |
| `--request-read-time` | ISO-8601 duration | `PT60S` (at most `PT2H`) | Time a client has to send a whole request, body included. It protects against slow clients; raise it for large uploads sent without a buffering proxy (a proxy receives the upload first and forwards it fast). |

| Environment variable | Description |
|---|---|
| `CIMPAL_API_TOKEN` | Use this token instead of generating one (at least 32 characters). No token file is written. Useful when a supervisor starts the server and hands the token to the agent. |

---

## Allowed folders (`--root`)

File paths in requests may only point inside the allowed folders. Every path field in a request (`input`, `output`, `modelsDir`, `mappingCsv`, `shaclFiles`, `constraintFiles`, `dataFiles`, and so on) is checked before the command runs, and so are the paths CimPal resolves itself: files named in a mapping CSV, `owl:imports` in shapes, files the `organize` template names. A path outside the roots is refused with **403**, and the message names it.

| Flag | Default | Description |
|---|---|---|
| `--root <dir>` | the working directory (refused if that is your home folder or a drive root) | Folder whose files may be read and written. Repeatable. |
| `--read-root <dir>` | — | Folder that may only be read. Repeatable. |
| `--write-root <dir>` | — | Folder that outputs may be written to; its files can also be read. Repeatable. |
| `--allow-unc` | off | Accept UNC network paths (`\server\share`). They still have to be under a root. |

- Relative paths in a request resolve against the first `--root` (or the working directory).
- `..` and absolute paths outside the roots are refused. So are links and junctions that lead outside, and Windows device names (`CON`, `NUL`, `COM1`, ...) and NTFS alternate data streams.
- **Existing output files named in the request** (`output`) are not overwritten unless the request sets `"overwrite": true`. Output folders may already exist, and files a command creates inside its output folder (reports, generated shapes) are replaced on every run.
- UNC paths count in any separator mix (`\host`, `//host`, `\/host`). They are refused before anything touches them, because on Windows even checking whether `\host\share\x` exists can send your credentials to that host.
- `owl:imports` of a network location (`file://host/...`, `//host/...`) are refused everywhere, also in normal CLI and GUI use, and fail the validation row.
- The working directory is only used as the default root if it is neither your home folder nor a drive root. Otherwise pass `--root`.

```bash
java -jar CimPal-CLI.jar serve --root C:/Data/CimPal --read-root C:/Data/shared-constraints
```

---

## Security

`serve` runs CimPal commands, which read and write files, on behalf of whoever calls it. The server is therefore closed to everything except the local user who started it:

| Check | Rejected with |
|---|---|
| **Host header** must be `localhost`, `127.0.0.1` or `[::1]` with the server's port (with `--allow-remote`, also the bound host), or a name given with `--allowed-host`. This blocks DNS-rebinding attacks from web pages. | 403 |
| **Origin header**, if present, must be listed in `--allow-origin`. Browsers send it on cross-site requests, so pages you visit can't call the server. No CORS headers are ever sent. | 403 |
| **Path** must match an endpoint exactly (`/validatefoo` is not `/validate`). | 404 |
| **File paths** in the request must be under the [allowed folders](#allowed-folders---root); existing outputs need `"overwrite": true`. | 403 |
| **Body** must be valid JSON. | 400 |
| **Method**: commands and `/shutdown` are POST only; `/health` and `/commands` are GET only. | 405 |
| **Token**: every endpoint except `GET /health`, `GET /ready`, `GET /v1/health` and `GET /v1/openapi.json` needs `Authorization: Bearer <token>`. The comparison is constant-time, and the token never appears in logs or responses. | 401 |
| **Content type**: POST bodies must be `application/json` (a charset parameter is fine). HTML forms can't send that without a CORS preflight. | 415 |
| **Body size**: at most `--max-body-bytes`. | 413 |
| **Queue**: `--workers` commands run at once (one by default) and at most `--queue-size` wait. | 503 (with `Retry-After`) |
| **Timeout**: after `--request-timeout`, the caller gets 504. | 504 |

The token is 256 random bits, new on every start. The token file is created with owner-only permissions before the token is written into it: POSIX `rw-------`, or on Windows an ACL that grants only your account (looked up as `%USERDOMAIN%\%USERNAME%`). A link already at the path is replaced, not followed. On POSIX, a token directory that other users can write to is refused. The file is deleted when the server stops, unless another `serve` instance has since written its own token there. Anyone who can read the file can call the server, so don't copy it to shared locations.

If Windows can't look up your account by its qualified name (for example on some Entra ID machines), the file is created first and restricted to its owner before the token is written. With the default location under `%LOCALAPPDATA%`, which only you can read, that makes no difference. With `--token-file` in a shared folder, prefer `CIMPAL_API_TOKEN` there.

A command that times out while still waiting in the queue never runs. A command that times out while running is left to finish, because interrupting it could leave half-written reports behind. It keeps its place in the queue until it returns, so the queue limit still holds, but its worker stays busy. Restart the server if a command hangs.

Limits that protect the server itself:
- A request (headers and body) must arrive within 60 seconds, so a client can't hold a connection open by sending slowly. Commands that run longer than that are not affected. This is the JDK setting `sun.net.httpserver.maxReqTime`; set `-Dsun.net.httpserver.maxReqTime=<seconds>` to change it. It applies to the whole JVM, and a body that takes longer than this to upload (e.g. a large body over a slow `--allow-remote` link) is dropped.
- This bounds how long one connection is held, not how many a client opens. A local process that keeps opening slow connections can still slow the server down. That is a limit of this design, and it needs local access unless `--allow-remote` is used.
- `--queue-size` and `--workers` can be at most 64, and `--max-body-bytes` at most 64 MB.

---

## API reference

### Command endpoints — `POST /<command>`

*Kept for local use.* The HTTP call stays open until the command ends, so a large validation holds the connection for many minutes. For automation, use the [job API](#job-api-v1).

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
| `/health` | `GET` | Returns `{"status":"ok","version":"...","busy":false,"stalled":false,"queued":0}`. `busy` means a command is running, and `queued` is the number waiting. `status` becomes `"stalled"` (and `stalled` true) when a running command has taken longer than `--request-timeout`: it can't be stopped, so restart the server if it doesn't return. `GET /v1/health` also gives `running` and `workers`. The only endpoint that needs no token. |
| `/ready` | `GET` | Readiness probe: 200 `{"status":"ready"}`; 503 with `"shutting down"`, or `"no usable token"` while the token file is broken. A full queue still answers 200, so a single instance stays reachable for polling and cancelling. No token needed. |
| `/metrics` | `GET` | Only with `--metrics`: Prometheus text, see [Metrics](#metrics). Needs an admin token. |
| `/commands` | `GET` | Lists all available command and utility endpoints. Needs the token. |
| `/shutdown` | `POST` | Stops the server gracefully. Needs an admin token and `Content-Type: application/json`. |

`run`, `serve` and `mcp` aren't exposed as endpoints.

### Job API (`/v1`)

The job API runs a command asynchronously.
- **Submit:** `POST /v1/jobs` answers within milliseconds with a job id.
- **Follow up:** you poll the job's status, then fetch its result and its progress lines.
- **Spec:** the OpenAPI 3.1 description is served at `GET /v1/openapi.json`. The [Python SDK](../guide/python-sdk.md) (`cimpal-client`) is generated from it.

| Endpoint | Method | Description |
|---|---|---|
| `/v1/jobs` | `POST` | Submit `{"command": "<command>", "config": {...}, "label": "..."}`. `command` is one of the ten command endpoints above; `config` has the same keys as that command's config file; `label` (optional, ≤ 200 characters) is for you. Answers **202** with the job and `Location: /v1/jobs/<id>`. |
| `/v1/jobs` | `GET` | Recent jobs, newest first. `?status=` filters (`queued`, `running`, `succeeded`, `failed`, `cancelled`, `timed_out`); `?limit=` 1–1000, default 50. |
| `/v1/jobs/<id>` | `GET` | The job: `status`, `createdAt`, `startedAt`, `finishedAt`, `exitCode`, `hasViolations`, `error`, `links`. |
| `/v1/jobs/<id>/result` | `GET` | The command's JSON output once it finished, the same as the synchronous endpoint returns. **409** while it runs or after a cancel; **410** if it was larger than `--max-result-bytes`. |
| `/v1/jobs/<id>/log` | `GET` | Progress lines: `{lines, offset, nextOffset, truncated}`. Poll with `?offset=<nextOffset>`. At most 1000 lines per call, and the last `--job-log-lines` are kept. Each line is what the command wrote to stderr, sanitised and cut at 512 characters. |
| `/v1/jobs/<id>` | `DELETE` | Cancel a **queued** job; it never starts. A running job is not interrupted (409), so it can't leave half-written reports. |
| `/v1/health` | `GET` | Like `/health`, plus `apiVersion` and `running`. No token needed. |
| `/v1/openapi.json` | `GET` | The OpenAPI 3.1 document. No token needed. |

**Statuses:**
- `queued`, then `running`, then one of:
  - `succeeded`: exit 0 or 1; `hasViolations` and `exitCode` tell them apart;
  - `failed`: exit 2 or 3, or an internal error; see `error` and the result;
  - `timed_out`: still running after `--job-timeout`. It isn't interrupted, and a result that arrives later is kept.
- `cancelled`: cancelled while queued.

**What stays the same:**
- **Checks:** the token, the Host and Origin checks, the body limit and the [allowed folders](#allowed-folders---root) apply as for the other endpoints. Paths in `config` are checked **when you submit**, so a refused path is a 403 at once, not a failed job later, and they are checked again just before the job runs.
- **Workers and queue:** jobs and synchronous requests share the `--workers` and `--queue-size`. A full queue answers 503 with `Retry-After`.

**Errors** under `/v1` are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`: `{"type", "title", "status", "detail", "jobId"?}`. The older endpoints keep `{"error": "..."}`.

**Where jobs live:** in memory by default, or in PostgreSQL with `--job-store jdbc` ([Jobs that survive a restart](#jobs-that-survive-a-restart)).
- **Restart:** in memory they are lost on restart; with `jdbc` they survive it.
- **Storage limits:** finished jobs are dropped after `--job-ttl`, then the oldest ones beyond `--max-jobs` or (in memory) beyond `--job-store-bytes`.
- **Cancelled jobs:** a cancelled job leaves the queue at once, so its slot and its request body are freed.
- **Shutdown:** `POST /shutdown` (or stopping the process) lets the running job finish. In memory it cancels queued jobs; with `jdbc` they stay queued and run after the next start.

#### Example: submit, poll, fetch (bash)

```bash
token=$(cat ~/.cimpal/serve.token)
auth=(-H "Authorization: Bearer $token")
job=$(curl -s "${auth[@]}" -H 'Content-Type: application/json' -X POST http://localhost:7474/v1/jobs \
  -d '{"command":"validate","label":"nightly","config":{"mappingCsv":"/data/mapping.csv",
       "modelsDir":"/data/models","constraintsRoot":"/data/constraints","outputDir":"/data/out"}}')
id=$(echo "$job" | python3 -c 'import json,sys; print(json.load(sys.stdin)["jobId"])')
until curl -s "${auth[@]}" "http://localhost:7474/v1/jobs/$id" | grep -qE '"status":"(succeeded|failed|timed_out|cancelled)"'; do
  sleep 5
done
curl -s "${auth[@]}" "http://localhost:7474/v1/jobs/$id/result"
```

#### Example: Python `requests`

```python
import time, requests, pathlib

base = "http://localhost:7474"
token = pathlib.Path.home().joinpath(".cimpal", "serve.token").read_text().strip()
s = requests.Session()
s.headers["Authorization"] = f"Bearer {token}"

job = s.post(f"{base}/v1/jobs", json={
    "command": "validate",
    "label": "nightly",
    "config": {"mappingCsv": "/data/mapping.csv", "modelsDir": "/data/models",
               "constraintsRoot": "/data/constraints", "outputDir": "/data/out"},
}).json()

offset = 0
while True:
    log = s.get(f"{base}/v1/jobs/{job['jobId']}/log", params={"offset": offset}).json()
    for line in log["lines"]:
        print(line)
    offset = log["nextOffset"]
    status = s.get(f"{base}/v1/jobs/{job['jobId']}").json()
    if status["status"] not in ("queued", "running"):
        break
    time.sleep(5)

if status["status"] == "succeeded":
    result = s.get(f"{base}/v1/jobs/{job['jobId']}/result").json()
    print("violations found" if status["hasViolations"] else "all conforming", result["totals"])
else:
    print("job ended as", status["status"], status["error"])
```

On Windows the token file is `%LOCALAPPDATA%\CimPal\serve.token`.

### File exchange: uploads and downloads

For callers that share no file system with the server: Airflow on another machine, a laptop, a CI runner. Callers that do share storage (a Kubernetes volume, NFS) keep using paths under the [allowed folders](#allowed-folders---root).

| Endpoint | Method | Description |
|---|---|---|
| `/v1/uploads` | `POST` | Body: a ZIP (`Content-Type: application/zip`) with the models, constraints, mapping CSV, … Answers **201** `{uploadId, files, totalBytes, createdAt, expiresAt}`. The only POST that isn't JSON. |
| `/v1/uploads/<id>` | `GET`, `DELETE` | The upload's files; delete it (409 while a queued or running job uses it). |
| `/v1/jobs` | `POST` | With `"inputUpload": "<uploadId>"` next to `command` and `config`, the job runs on that upload (below). |
| `/v1/jobs/<id>/files` | `GET` | The job's output files: `{files: [{path, size, mediaType}]}`. 409 until the job has finished; 404 for a job that used shared paths. |
| `/v1/jobs/<id>/files/<path>` | `GET` | One output file, as an attachment. |
| `/v1/jobs/<id>/archive` | `GET` | All output files as one ZIP. |

**A job with `inputUpload`:**
- **Reads:**
  - relative paths in `config` resolve inside the upload (`"models": ["models/eq.xml"]`);
  - it may also read the server's `--read-root` folders, e.g. a constraint library kept on the server;
  - nothing else: no other upload, no `--root`/`--write-root` folder.
- **Writes:** only its own output folder.
  - Relative output paths (`output`, `outputDir`, `summaryFile`) are placed there, and a missing `outputDir` becomes that folder.
  - An output path anywhere else is refused with 403 when the job is submitted.

**The upload is checked as a whole** and refused (400) if any entry:
- **leaves the folder:** `..`, an absolute name, a drive letter, a UNC name;
- **has a bad name:** a reserved device name (`CON`, `NUL`, `COM1`, …), a name ending in a dot or blank, or two names that differ only in case;
- **is too large:**
  - a central directory over 16 MiB, or more than 10,000 entries (refused from the archive's end record, before it is read);
  - more than 2 GiB extracted (1 GiB per file), where every file and folder also counts 4 KiB;
  - a name deeper than 32 folders or longer than 1,024 characters;
  - a file that expands more than 100 times once it is larger than 1 MiB.

Nested ZIPs are kept as files, not expanded, and link entries become plain files. The upload is streamed to disk and never held in memory. Concurrent uploads reserve their room in `--work-max-bytes` before extracting, so together they can't overshoot it.

**Trust model:** request paths can't reach another caller's upload or outputs. Uploads and jobs belong to the client whose token created them: with [named tokens](#clients-and-quotas) each client sees only its own, and only an admin sees everyone's. With a single shared token, everyone who holds it is the same client (`default`, an admin) and sees everything.

**Lifetime:**
- **Uploads:** kept for `--upload-ttl` after creation, but never while a queued or running job uses them.
- **Job outputs:** kept as long as the job (`--job-ttl`).
- **Restart:** in memory, the work folder is emptied when `serve` starts, as the jobs are gone. With `--job-store jdbc` it is kept and matched with the database: uploads and outputs whose records survived stay, the rest is deleted, and a job whose output folder is gone answers 410 on its files routes.

**Request time:** a large upload has to arrive within `--request-read-time` (60 s). A reverse proxy such as ingress-nginx or Caddy receives the upload first and forwards it fast; without one, raise the limit.

```bash
id=$(curl -s -H "Authorization: Bearer $token" -H 'Content-Type: application/zip' \
  --data-binary @inputs.zip http://localhost:7474/v1/uploads | python3 -c 'import json,sys; print(json.load(sys.stdin)["uploadId"])')
job=$(curl -s -H "Authorization: Bearer $token" -H 'Content-Type: application/json' http://localhost:7474/v1/jobs \
  -d "{\"command\":\"validate\",\"inputUpload\":\"$id\",\"config\":{\"workflow\":\"mapping\",\"mappingCsv\":\"mapping.csv\",\"modelsDir\":\"models\",\"constraintsRoot\":\"constraints\"}}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["jobId"])')
# ... poll /v1/jobs/$job until it has finished, then:
curl -s -H "Authorization: Bearer $token" -o outputs.zip http://localhost:7474/v1/jobs/$job/archive
```

---

## HTTP status codes

| Code | Meaning |
|---|---|
| 200 | Command ran successfully (including when violations were found — exit 0 or 1; a `violationsExitCode` in the request is ignored) |
| 400 | Bad request — invalid input, missing required field (exit 2) |
| 401 | Missing or wrong bearer token |
| 403 | Host or Origin header not allowed, a file path outside the allowed folders / an existing output without `"overwrite": true`, or a route that needs an admin token (`/shutdown`, `/metrics`, `GET /v1/jobs?owner=` another client) |
| 404 | No such endpoint; under `/v1` also a job or upload that doesn't exist **or belongs to another client** (the two answers are identical) |
| 405 | Method not allowed — wrong HTTP verb for the endpoint |
| 413 | Request body larger than `--max-body-bytes` |
| 415 | POST without `Content-Type: application/json` |
| 409 | Under `/v1`: a request with this `Idempotency-Key` is still being processed (`Retry-After: 1`); or a job in the wrong state |
| 422 | The `Idempotency-Key` was used with a different request |
| 429 | The client's quota is used up: too many queued jobs (`maxQueued`) or requests (`requestsPerMinute`). `Retry-After` says when to try again; other clients are not affected |
| 500 | Internal error — command crashed (exit 3). Out of memory: the body is `{"exitCode":3,...,"error":"Out of memory; the server stops..."}` and the server then exits with 3; restart it with more memory ([sizing guide](../guide/sizing.md)) |
| 409 | `/v1`: the job hasn't finished (result), or isn't queued any more (cancel) |
| 410 | `/v1`: the job's result was larger than `--max-result-bytes` and wasn't kept |
| 413 | Also: an upload larger than `--max-upload-bytes` |
| 415 | Also: an upload that isn't `application/zip`, or a ZIP sent anywhere else |
| 507 | `/v1/uploads`: the work area is full (`--work-max-bytes`) |
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

File paths in request bodies may be absolute or relative to the first `--root`. They must be inside the [allowed folders](#allowed-folders---root). The server and the caller share a filesystem (the daemon runs locally), so the files must be accessible from the machine where the server is running.

Paths in a job's `config` are local paths on the server; to send files with the job, see [File exchange](#file-exchange-uploads-and-downloads).

---

## Thread safety and concurrency

By default commands run **one at a time** on a single worker. While one runs, up to `--queue-size` requests wait, and more are rejected with 503. `/health` still answers while commands run. A single validation run already uses the available cores through its own worker pool, so for one agent sending one request at a time this is the right setting.

With `--workers N`, up to N commands run at once in the same JVM. Each command keeps its own:
- **allowed folders:** the path policy is per command, and threads the command starts inherit it. While `serve` runs, a path check on a thread without a command's policy is refused, never unrestricted;
- **remote-import caches** and temporary downloads;
- **output:** what a command prints goes to its own result and job log, also from the threads it starts. With `--log-format json`, each `output` line carries the `jobId` of the job that wrote it.

The results are the same as with one worker; a stress test runs mixed jobs with four workers and compares every result with the one-worker run.

**Memory admission.** Concurrent commands share one heap. Each command is estimated at **10 times the size of its input files** (files, the files in input folders, archives as they are), at least 64 MiB; the [sizing guide](../guide/sizing.md) measures about 9 times the RDF/XML size. A command whose inputs hold more than 100 000 files and folders, or can't be read, counts as too large to share: it runs alone. A command starts only while the estimates of the running commands plus its own fit `--max-concurrent-heap-bytes` (default: three quarters of `-Xmx`). A command with nothing else running always starts, however large. Waiting is first come, first served, so a large command is not overtaken by smaller ones behind it. A command waiting for memory still counts as queued, and a queued job can be cancelled.

**When to use workers or replicas.** More replicas (one worker each) remain the recommended production shape: an out-of-memory error ends the whole JVM, which with several workers fails every running command (they end with exit 3 like any OOM, never with a clean result). Use `--workers` for many small commands, where JVM start-up dominates, and keep the number at or below the cores. Process-wide numbers in `--stats` (CPU time, peak heap, GC) cover all running commands when there is more than one.

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

## Running as a service

For a team or an orchestrator such as Airflow, run `serve` in a container behind a reverse proxy or Kubernetes Ingress that terminates TLS. CimPal itself only speaks plain HTTP (decision D-7). Worked examples:
- [deploy/docker-compose](../../deploy/docker-compose/README.md): `serve` behind Caddy with TLS, the token as a Docker secret;
- [deploy/kubernetes](../../deploy/kubernetes/README.md): Deployment, Service and Ingress, with probes and a read-only root filesystem.

The guide page [Running CimPal as a service](../guide/service.md) explains the choices.

### Behind a reverse proxy or Ingress

The proxy forwards the client's Host header, e.g. `cimpal.example.com`. `serve` refuses names it doesn't know (the DNS-rebinding defence), so list each public name with `--allowed-host`:
- **`--allowed-host cimpal.example.com`** matches `Host: cimpal.example.com`, `cimpal.example.com:443` and `cimpal.example.com:80`.
- **`--allowed-host cimpal.example.com:8443`** matches only that port.

Names are compared without case and without DNS lookups. No scheme, path or wildcard is accepted. Loopback names with the server's own port keep working (health probes from inside the pod use `Host: localhost:7474`).

### Token file for services

`--token-from-file <path>` (or `CIMPAL_API_TOKEN_FILE=<path>`) reads the tokens from a file that `serve` never writes or deletes, such as a Kubernetes or Docker secret:
- **Contents:** one token per line, each at least 32 characters. Blank lines and lines starting with `#` are ignored. A line is either
  - `name:role:token`: a token of a named client, see [Clients and quotas](#clients-and-quotas); at most 256 tokens; or
  - a bare token: the client `default`, an admin. At most two (two while you rotate).
- **Reload:** the file is read every `--token-reload` (60 s), and the tokens change when its content does.
  - **No token** (empty, or only comments): every token is revoked at once.
  - **Broken file** (can't be read, or invalid): the tokens read before stay for one more check, which bridges a secret being replaced. If it is still broken then, every request is refused and `/ready` answers 503 until it is fixed. Each check logs a warning.
  - **At start:** any of these stops `serve` with exit code 2.
  - **Regular file only:** at most 64 KiB. Replace it in one step (write a new file, then rename) so it is never read half-written.
- **Rotate:**
  1. write the old and the new token;
  2. switch the clients to the new one;
  3. remove the old line.
  No restart is needed.
- **Permissions:** on Linux and macOS, a file any user can read gives a warning. Kubernetes: `defaultMode: 0440` with `fsGroup`.
- **Conflicts:** a token file together with `CIMPAL_API_TOKEN`, or together with `--token-file` (where a generated token is written), refuses to start (exit 2) rather than guessing.

### Clients and quotas

A shared service used by several independent clients (teams, pipelines, organisations) keeps them apart with **named tokens** in the token file:

```text
# name:role:token     (role: client or admin; a bare token line is client "default", role admin)
etl-airflow:client:3f9c...
etl-airflow:client:8b21...     # rotation: two tokens of one client
team-b:client:a71e...
ops:admin:9d02...
```

- **Name:** 1 to 64 letters, digits, `.`, `_` or `-`. It is an identifier, not a secret: it appears in logs, metrics and the job's `owner` field. One name has one role; one token belongs to one name.
- **Bare tokens with colons:** a line shaped `word:client:…` or `word:admin:…` is always read as a named line. Generated tokens never contain `:`; replace a hand-made bare token that does.
- **Ownership:** jobs and uploads belong to the client that created them.
  - A `client` sees only its own: `GET /v1/jobs` lists them, and every route of another client's job (result, log, files, archive, cancel) or upload answers **404, exactly as for an id that doesn't exist**. A job can only read an upload of the same client (`inputUpload`).
  - An `admin` sees everything, and filters the list with `GET /v1/jobs?owner=<name>`. Only an admin may call `/shutdown` and `/metrics`.
- **Synchronous endpoints** (`POST /validate` and the others) are attributed to the client in logs and metrics too.

**Quotas** (`--client-quotas quotas.json`) stop one client from filling the queue or the disk for everyone:

```json
{
  "_comment": "Read at start. A client's entry wins over default, which wins over the server's flags.",
  "default": { "maxQueued": 2, "requestsPerMinute": 600 },
  "clients": {
    "etl-airflow": { "maxQueued": 8, "maxRunning": 2 },
    "team-b": { "maxUploadBytes": 1073741824, "maxWorkBytes": 5368709120 }
  }
}
```

| Quota | Default | When it is used up |
|---|---|---|
| `maxQueued` | none of its own (the server's `--queue-size` bounds it) | A submit is refused with **429** and `Retry-After` once the client has `maxQueued` + `maxRunning` jobs queued or running. Other clients still submit. |
| `maxRunning` | `--workers` | The client's further jobs wait, and other clients' jobs may start before them: the next job to run is the oldest one whose client is under its `maxRunning`. |
| `maxUploadBytes` | `--max-upload-bytes` | **413** for a larger upload. |
| `maxWorkBytes` | `--work-max-bytes` | **507** once the client's uploads and job outputs take that much of the work area. |
| `requestsPerMinute` | 0 (no limit) | **429** with `Retry-After` for any request with the client's token; the budget refills evenly over a minute. |
| `callbackAllow` | none | The URL prefixes the client's [completion callbacks](#completion-callbacks) may go to, e.g. `["https://hooks.example.com/cimpal/"]`. Each is https with a path ending in `/`. Without one, a job with a callback is refused (400). |

**With several clients, set `default.maxQueued`**: without it one client can take the whole queue, and the others get 503 until its jobs finish. Synchronous endpoints (`POST /validate` …) count against `requestsPerMinute` and the server's queue, not against `maxQueued` or `maxRunning`; give such clients the `/v1` API.

Finished jobs are dropped (beyond `--max-jobs` or `--job-store-bytes`) from the client that holds the most first, so one client's many jobs don't push out another's results. A job with an upload is refused with 507 while its client's share of the work area is already full, since its outputs would count there too.

The server's own flags stay the limits of the whole server: a client's `maxRunning`, `maxUploadBytes` or `maxWorkBytes` above them is lowered to them. A quota for a name that has no token logs a warning at start. Keys starting with `_` are comments.

### Safe retries (`Idempotency-Key`)

A client whose answer to `POST /v1/jobs` or `POST /v1/uploads` got lost (a timeout, a dropped connection) can send the same request again with the same `Idempotency-Key` header and gets the **same** job or upload, never a second one (DEP-14):

| The key… | Answer |
|---|---|
| is new | the request is processed as usual |
| was used with the same request (same body; for an upload, the same bytes) | the same job (202) or upload (201) again, even after a restart with `--job-store jdbc` |
| was used with a different request | **422** |
| belongs to a request still being processed | **409** with `Retry-After: 1` |
| belongs to a job or upload that has expired since | **404** |

- **Format:** 1 to 255 visible ASCII characters, e.g. a UUID per logical request reused for its retries; anything else is 400.
- **Scope:** per client ([named tokens](#clients-and-quotas)); two clients' keys never meet.
- **Lifetime:** `--idempotency-ttl` (24 h) after first use. A refused request (4xx, 503) doesn't use up its key.
- **Exactly once:** the job's id is reserved with the key before the job is made, so a crash between the two can't lead to a second job: the repeat finds the job. A key is only taken over when it has expired, or when its request died (after 10 min) without making anything.
- **Memory store:** at most 10,000 keys per client and 100,000 in all; a client's own oldest finished keys make room, and a client whose keys are all unfinished gets 503 rather than pushing out another client's.
- The Python SDK sends a key with every `submit()` and `upload()`, and the Airflow provider one per task try.

### Completion callbacks

Instead of polling, a job can ask to be called back when it ends (DEP-14, D-19, opt-in):

```json
{"command": "validate", "config": {...}, "callback": {"url": "https://hooks.example.com/cimpal/done"}}
```

- **Who may:** a client with a signing secret in `--callback-secrets` (`name:secret` lines, like the token file) and `callbackAllow` prefixes in `--client-quotas`. Anything else is 400 at submit.
- **Where to (SSRF):** the URL must be https without user or fragment, start with one of the client's allowed prefixes (no `.`/`..` segments or encoded separators in its path), and its host must resolve to public addresses only: no loopback, private, link-local (cloud metadata), carrier-grade NAT, unique-local or reserved addresses, nor IPv6 forms that carry one (NAT64, 6to4). This is checked at submit and again before every attempt. Redirects are never followed, and an attempt is cut off after 15 s, so a slow receiver can't hold up other callbacks.
- **What the client sees of a failure:** `lastError` is only `HTTP <status>`, `timed out` or `connection failed`, never the kind of network error (the server's log has it).
- **What:** `POST` with `Content-Type: application/json` and the body `{"event": "job.finished", "jobId", "command", "status", "exitCode", "hasViolations", "finishedAt", "links"}`. Never the result, the log or a token: fetch the result with your token.
- **Signature:** `CimPal-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`. Check it, and that `t` is recent (replay); the SDK's `verify_callback()` does both. Act on the signed body's `jobId` once: delivery is at least once.
- **Delivery:** at least once, whenever the job ends (also when it is cancelled or interrupted by a restart). A 2xx answer delivers it; anything else is retried after 10 s, doubling up to 1 h, for 24 h after the job ended. With `--job-store jdbc` pending callbacks survive a restart. `GET /v1/jobs/{id}` shows `callback.state` (`waiting`, `pending`, `delivered`, `failed`), `attempts`, `lastStatus` and `lastError`; `cimpal_callbacks_total{outcome}` counts the attempts.

### Jobs that survive a restart

`--job-store jdbc` keeps the `/v1` jobs (status, checked config, result, log) and the uploads' bookkeeping in PostgreSQL (DEP-11, decision D-14):

```bash
CIMPAL_JOB_STORE_PASSWORD_FILE=/run/secrets/db-password \
cimpal serve --job-store jdbc --job-store-url 'jdbc:postgresql://db:5432/cimpal?sslmode=verify-full' \
  --job-store-user cimpal --host 0.0.0.0 --allow-remote --token-from-file /run/secrets/token
```

- **Schema:** `serve` creates its tables at start (`cimpal_jobs`, `cimpal_job_log`, `cimpal_uploads`; the version is in `cimpal_schema_version`). A database newer than this CimPal is refused. The user needs `CREATE` on the schema the first time, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` afterwards.
- **After a restart, a redeploy or a crash:**
  - queued jobs run, oldest first;
  - finished jobs, their results, logs and output files can still be fetched;
  - a job that was running ends as `failed` with "Interrupted: the server stopped while the job ran" (decision D-18). `--requeue-interrupted` queues it again instead.
- **Logs:** a running job's lines are written to the database in batches of 64 and at its end; after a crash its last lines may be missing.
- **Limits:** results are in the database, not the heap, so `--job-store-bytes` doesn't apply. `--max-result-bytes`, `--max-jobs` and `--job-ttl` do.
- **The database down:** a request that needs it is answered 503 with `Retry-After`, and `/ready` answers 503 until it is back. `cimpal_job_store_errors_total` counts the failures. Messages never show the URL's credentials or the password.
- **Work area:** the same `--work-root` must be mounted again; back it up with the database.
- **One instance per database** for now: several replicas on one database come with DEP-12.

### Shutdown

SIGTERM (`docker stop`, Kubernetes) does what `POST /shutdown` does:
- **Stop:** the server stops taking work (`/ready` answers 503) and cancels queued jobs (with `--job-store jdbc` they stay queued for the next start).
- **Drain:** running commands get up to `--shutdown-grace` to finish, so their reports aren't cut off.
- **Exit:** with 0, or with 3 and an error log line (naming the running jobs) if a command was still running.

Give the container more time than the grace period: Docker `--stop-timeout` or `stop_grace_period`, Kubernetes `terminationGracePeriodSeconds`. Ctrl-C drains the same way.

### Logs

With `--log-format json`, stderr carries one JSON object per line and stdout stays empty:

| Field | When |
|---|---|
| `ts`, `level` (`info`, `warn`, `error`), `event`, `message` | always |
| `jobId`, `command`, `status`, `exitCode`, `durationMs` | job and command events |
| `client` | events of a request or job with a token: the client's name from the token file (`default` for a single shared token), never the token |
| `remote`, `method`, `path` | request events (`remote` is the direct peer, i.e. the proxy behind one) |

**Events:**
- **Server:** `server.started` (with `tokenSource`, where the token comes from), `server.stopping`, `server.stopped`, `server.grace_exceeded`;
- **Jobs and commands:** `job.queued`, `job.started`, `job.finished`, `command.finished`;
- **Uploads:** `upload.received`;
- **Problems:** `request.rejected`, `stall`, `token.file`, `quota.unknown`, `store.error`;
- **Callbacks:** `callback.delivered`, `callback.retry`, `callback.failed`, `callback.refused`;
- **Restart:** `jobs.recovered` (with `--job-store jdbc`: queued jobs from before the restart resume);
- **Output:** `output`, which is every line the commands themselves write, e.g. `[INFO] Loading …`, tagged with the `jobId` of the job whose thread wrote it. Its level comes only from the line's start (`[WARN]`, `[ERROR]`, or a logger's `[thread] WARN`).

**Safety:**
- Every string passes the log sanitiser: control characters are replaced, and long values are cut at 512 characters.
- Tokens and the `Authorization` header are never logged.

The default `text` format keeps the familiar `[WARN] serve: …` lines.

### Metrics

With `--metrics`, `GET /metrics` (admin token required) returns Prometheus text format 0.0.4:

| Metric | Type | Labels |
|---|---|---|
| `cimpal_info` | gauge | `version` |
| `cimpal_jobs` | gauge, stored jobs | `status` |
| `cimpal_jobs_finished_total` | counter | `client`, `status` |
| `cimpal_job_duration_seconds` | histogram (1 s … 2 h) | — |
| `cimpal_requests_total` | counter, synchronous endpoints | `client`, `command`, `outcome` (`ok`, `violations`, `bad_input`, `error`, `timeout`, `busy`) |
| `cimpal_quota_refused_total` | counter, 429 answers | `client`, `quota` (`max_queued`, `requests_per_minute`) |
| `cimpal_callbacks_total` | counter, callback attempts | `outcome` (`delivered`, `retry`, `failed`, `refused`) |
| `cimpal_queue_depth`, `cimpal_busy`, `cimpal_running`, `cimpal_workers` | gauge | — |
| `cimpal_jvm_heap_used_bytes`, `cimpal_jvm_heap_max_bytes` | gauge | — |
| `cimpal_job_store_errors_total` | counter, failed job store operations (always 0 in memory) | — |

The counters start at zero on every start; a counter appears once it has counted something. The `client` label holds names from the token file only, so the number of series stays bounded.

---

## Starting automatically

To keep the daemon running as a background process (Windows):

```powershell
Start-Process -FilePath "java" `
  -ArgumentList "-jar", "CimPal-CLI.jar", "serve", "--port", "7474" `
  -WindowStyle Hidden
```

Or add it to a startup script that runs before your agent session begins. The agent reads the token from the token file after the server has started. To supply a fixed token instead, set `CIMPAL_API_TOKEN` in the server's environment.

---

## Running in Docker

Inside a container the server has to listen on all interfaces, because `localhost` there is the container itself, so it needs `--host 0.0.0.0 --allow-remote` (and prints the matching warning). Keep it reachable from your own machine only:
- **Publish the port on the host's loopback, with the same number inside and outside** (`-p 127.0.0.1:7474:7474`). The Host check accepts only the port the server listens on, so a remapped port such as `-p 127.0.0.1:8080:7474` gets 403. To use another port, change both: `--port 8080` and `-p 127.0.0.1:8080:8080`.
- **Hand it a new token for each start through `CIMPAL_API_TOKEN`** (at least 32 characters). `-e CIMPAL_API_TOKEN` without a value copies it from the environment of the `docker` command. So it doesn't appear on the docker command line, and you don't have to read a token file inside the container. Never write `-e CIMPAL_API_TOKEN=<value>`.
- **Run it on a network of its own, with `--rm`, and mount the inputs read-only.**

```powershell
$bytes = [byte[]]::new(32); [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
docker network create cimpal-serve
$env:CIMPAL_API_TOKEN = $token
docker run -d --rm --name cimpal-serve --network cimpal-serve -p 127.0.0.1:7474:7474 -e CIMPAL_API_TOKEN `
  -v "C:\Data\models:/data/models:ro" -v "C:\Data\out:/data/out" `
  ghcr.io/griddigit-ci/cimpal:latest serve --host 0.0.0.0 --allow-remote
$env:CIMPAL_API_TOKEN = $null
```

Request paths are then container paths under the default root `/data`. Never publish with `-p 7474:7474` or `-P`. Other local users can still reach the loopback port, so the token is what keeps them out. See [docker](docker.md#serve) for the Bash version and the rest of the guidance.
