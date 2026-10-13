<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Python SDK (`cimpal-client`)

*Last reviewed 2026-10-13 · Describes the release after CimPal 2026.10.6.1*

`cimpal-client` calls a CimPal server's job API (`/v1`, the [`serve`](../cli/serve.md) command) from Python, so you never hand-craft HTTP calls. It uploads input files, submits commands as jobs, waits for them and fetches their results and reports. The validation runs on the server; the package contains no CimPal engine.

## Install

```bash
pip install cimpal-client                          # once published; until then, from a release tag:
pip install "git+https://github.com/griddigit-ci/CimPal.git@<tag>#subdirectory=clients/python"
```

It needs Python 3.10 or later. Its version is the CimPal version it was built with; a client works with any server of the same API version (`apiVersion` `1`), and warns (`CompatibilityWarning`) when the server reports another one.

The client is licensed under Apache-2.0, so it can go into your own code without licence review. The CimPal server stays EUPL-1.2.

## Connect

```python
from cimpal_client import CimPal

cp = CimPal("https://cimpal.example.com", token="...")
print(cp.health())          # no token needed for this one
cp.close()                  # or use `with CimPal(...) as cp:`
```

**The token** is taken from, in order:
1. `token=`;
2. `token_file=`;
3. the `CIMPAL_API_TOKEN` environment variable;
4. the file named by `CIMPAL_API_TOKEN_FILE`.

These are the variables `serve` itself reads. In a token file with several lines, the first token is used. The token never appears in `repr()` or in error messages.

**Plain HTTP:** the client sends the token over plain `http://` only to this machine (`localhost`, `127.0.0.1`, `::1`). For another host it refuses to start, because the token would cross the network unencrypted. Use `https://` (a TLS proxy in front of `serve`, see [Running CimPal as a service](service.md)), or pass `allow_insecure_http=True` on a network you trust.

**Redirects** are never followed, so the token can't be sent elsewhere. A redirect, e.g. from `http://` to `https://`, raises a `CimPalError` naming the target URL; use that URL as `base_url`.

**Other options:**
- `timeout` (seconds or an `httpx.Timeout`; default 60 s per request);
- `verify` (TLS verification or a CA bundle path);
- `retries` and `backoff`;
- `check_api_version=False` to skip the version check.

## Run a validation on your own files

```python
from cimpal_client import CimPal

with CimPal("https://cimpal.example.com") as cp:
    up = cp.upload("igm/")                    # a folder (zipped on the fly), a .zip, or one file
    job = cp.submit("validate", {
        "workflow": "combined",
        "constraintFiles": ["constraints/EQ-constraints.ttl"],
        "dataFiles": ["models/IGM.zip"],
        "xmlBase": "http://iec.ch/TC57/CIM100",
    }, input_upload=up, label="nightly IGM check")
    job = cp.wait(job, timeout=3600)
    summary = cp.result(job)
    print(job.status, summary["hasViolations"], summary["totals"])
    cp.download_outputs(job, "reports/")      # or cp.download_archive(job, "reports.zip")
```

**What an upload sends:** every regular file in the folder, hidden ones (`.env`, `.git`) included, so upload a folder of inputs only. Links and Windows junctions are not followed: nothing outside the folder is sent.

**Paths in the job's config:**
- With `input_upload`, relative paths resolve inside the upload, and outputs go to the job's own folder on the server, which `download_outputs` fetches.
- Without an upload, paths are paths on the server and must lie under its allowed folders (`--root`).

The config is the same object as a `--config` file; [Configuration](configuration.md) and the [CLI reference](../cli/index.md) list the keys of each command.

**What `result()` returns:** the document the command prints with `--format json`, e.g. the validation summary with `hasViolations`, `totals` and the report path. Commands without JSON output give `{"exitCode": …, "status": …}`. A command that printed plain text gives `{"output": "…"}`. Any other answer, such as an HTML page from a proxy, raises `ServerError`: it is never taken for a result without findings.

**Downloads** keep the server's relative paths under your folder, but a name that would leave it (`..`, absolute paths, drive letters, links in your folder) is refused. A download that fails midway leaves an existing file as it was.

**`wait()`:**
- It returns the job once it is `succeeded`, `failed`, `cancelled` or `timed_out`. A succeeded validation can still have violations: check `job.has_violations` or the summary.
- It polls every 2 s at first, then less often, up to every 30 s.
- With `timeout`, it raises `cimpal_client.TimeoutError` when the time is up. The job keeps running on the server; `cp.cancel(job)` cancels it only while it is still queued.

## The other methods

| Method | What it does |
|---|---|
| `health()` | Server status, version, `running`/`queued`/`workers` |
| `submit(command, config, input_upload=, label=)` | Queue a job; returns at once |
| `job(job)`, `list_jobs(status=, limit=)` | A job's state; the stored jobs |
| `wait(job, timeout=)`, `result(job)` | See above |
| `log(job, offset=)` | Up to 1000 progress lines; continue from `next_offset` |
| `cancel(job)` | Cancel a queued job |
| `upload(path)`, `get_upload(id)`, `delete_upload(id)` | Input files for jobs |
| `files(job)`, `download_file(job, path, dest)`, `download_outputs(job, folder)`, `download_archive(job, zip)` | A job's output files |

Jobs and uploads can be passed as the objects the client returned or as their ids.

## Async

`AsyncCimPal` has the same methods as coroutines, for asyncio code such as an Airflow deferrable operator:

```python
from cimpal_client import AsyncCimPal

async def validate(config):
    async with AsyncCimPal("https://cimpal.example.com") as cp:
        job = await cp.wait(await cp.submit("validate", config), timeout=3600)
        return await cp.result(job)
```

## Errors

Every error is a `CimPalError` with `status`, `title`, `detail` and `job_id` from the server's problem details:

| Error | When |
|---|---|
| `BadRequestError` | 400, 413, 415: a wrong config, a body over `--max-body-bytes` |
| `AuthError` | 401: no token or a wrong one |
| `ForbiddenError` | 403: a path outside the allowed folders, or a Host the server doesn't accept |
| `NotFoundError` | 404: no such job, upload or file (finished jobs are dropped after `--job-ttl`) |
| `ConflictError` | 409: e.g. the result of a job that hasn't finished, or cancelling a running job |
| `GoneError` | 410: the result was larger than `--max-result-bytes` and wasn't kept |
| `BusyError` | 503: the queue is full; `retry_after` says how long to wait |
| `TimeoutError` | 504, a request without an answer in time, or `wait()` ran out of time |
| `ServerError` | other 5xx (507: the server's work area is full) |

`cimpal_client.TimeoutError` is also a Python `TimeoutError`.

## Retries and timeouts

- **Busy server:** a 503 is retried up to `retries` times (default 3). The wait is the server's `Retry-After` (at most 5 minutes), or else `backoff` × 2ⁿ seconds (1, 2, 4 … up to 30).
- **Unreachable server:** a request that never reached it (connection refused, DNS) is retried the same way.
- **A 503 to a `submit` or `upload`:** retried only when CimPal sent it (problem details or `Retry-After`), because CimPal then refused the request before accepting it. A proxy's 503 may come after the server got the request, so it raises `BusyError` instead.
- **Answer lost on the way back:** such a request (a read timeout, a dropped connection) is retried only when it is a `GET`. A `submit` is therefore never sent twice: if it fails that way, check `list_jobs()` before submitting again.
- **Request timeout:** `timeout` bounds each request, not the job; use `wait(timeout=)` for the job.

## See also

- [`serve`](../cli/serve.md): starting the server, tokens, allowed folders, the `/v1` API.
- [Running CimPal as a service](service.md): the server behind a proxy, in Kubernetes.
- [`clients/python/README.md`](../../clients/python/README.md): development, the generated layer, releasing.
