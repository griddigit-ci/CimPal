<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Client demo

*Last reviewed 2026-11-04 · Describes CimPal 2026.10.30.3 and later · Dry-run on Windows 11 with Docker Desktop 29.8*

A 15-minute walkthrough to show an organisation how CimPal runs in their environment. It shows the two ways to integrate it, both with the released image `ghcr.io/griddigit-ci/cimpal`:
1. **One command per task in a container:** how Airflow, CI systems and scripts use it.
2. **CimPal as a service:** a client uploads its models, submits a job, polls and downloads the reports. First with plain HTTP requests (`curl`), then with the Python SDK.

Everything runs on one laptop with the synthetic sample data in the repository. It is a CGMES-like EQ and SSH model with ten known violations, so the result is the same every time.

## Before the meeting

- **Docker:** Docker Desktop (Windows, macOS) or Docker Engine (Linux), running.
- **The image:** pull it once, so the demo doesn't wait for the download.
  ```bash
  docker pull ghcr.io/griddigit-ci/cimpal:2026.10.30.3
  ```
- **A shell:** bash. On Windows, use Git Bash.
- **Python 3.10 or later**, for part 3 and the scripts' JSON handling.
- **The Python SDK:**
  ```bash
  pip install cimpal-client
  ```
  Until the package is on PyPI, install it from the repository:
  ```bash
  pip install ./clients/python
  ```
- **A clone of this repository.** The commands below run from its root unless they say otherwise.

On Windows, Git Bash rewrites arguments that look like paths (`/data` becomes `C:/Program Files/Git/data`). Set this once per terminal before the `docker run` commands:
```bash
export MSYS_NO_PATHCONV=1
```

## Plain HTTP or the TLS proxy?

Part 2 needs a running server. There are two ways to start it, and the choice is what the audience sees.

**Plain HTTP on localhost:** one container, `http://localhost:7474`.
1. One `docker run` starts `serve` and publishes its port on this machine only (`127.0.0.1`).
2. Clients call `http://localhost:7474` with the token in the `Authorization` header.
3. The token and the models travel unencrypted. That is acceptable only because nothing leaves the machine. The SDK enforces this: it refuses plain `http://` to any host other than this machine.

It is simple, with no certificates and no warnings, but it is not how anyone runs it for real.

**TLS proxy:** two containers, `https://localhost:8443`, as in production.
1. Docker Compose starts `serve` on an internal network that can't be reached from outside, and Caddy, a reverse proxy, in front of it.
2. Caddy holds the TLS certificate. Clients talk HTTPS to Caddy, and Caddy forwards to `serve`.
3. Caddy here signs its certificate with its own local certificate authority (CA). Browsers and `curl` therefore don't trust it by default:
   - `curl` needs `-k` (the demo script uses it);
   - the SDK gets Caddy's CA file instead, so it still checks the certificate.
4. In a real deployment, the proxy, an ingress or a load balancer has a certificate from the organisation's own CA or a public one, and nobody needs `-k`.

It shows the setup IT departments expect: TLS, a token from a secret file, a read-only container with no capabilities, and a server that is not reachable directly. [Running CimPal as a service](service.md) explains each part.

**Recommendation:** use the TLS proxy. It is one extra command and answers the security questions before they are asked. Keep plain HTTP as the fallback if Compose or the proxy misbehaves on the day.

## 1. One command in a container

```bash
export MSYS_NO_PATHCONV=1   # Git Bash on Windows only
cd integrations/airflow/examples/data
docker run --rm -v "$PWD:/data" -w /data ghcr.io/griddigit-ci/cimpal:2026.10.30.3 \
  validate --config run.json --format json --summary-file out/summary.json --violations-exit-code 0
```

What to point out:
- **One container per task:** it reads the models from the mounted folder, writes the Excel report and `summary.json` to `out/`, and exits. Nothing stays running.
- **The JSON on stdout** is the contract for automation: `"hasViolations": true` and `totals`. Its schema is published in [Configuration](configuration.md).
- **Exit codes:** `--violations-exit-code 0` makes "violations found" a success, so a pipeline can carry on and act on the summary. Without it, the exit code is 1.
- **Airflow:** this is the `KubernetesPodOperator` / `DockerOperator` pattern in [Airflow](airflow.md).

## 2. CimPal as a service

Prepare the Compose folder: the sample files go to `inputs/` and a random token to `secrets/token`. `inputs/` stands for the client's own machine. `data/` is the server's folder, where `serve` also keeps its work area, so the client never uploads from there.
```bash
bash deploy/demo/setup.sh
cd deploy/docker-compose
```

**With the TLS proxy:**
```bash
export CIMPAL_TAG=2026.10.30.3
docker compose up -d
docker compose ps    # cimpal: healthy, caddy: running
bash ../demo/demo-v1.sh
```

**Or with plain HTTP:**
```bash
docker run -d --name cimpal-demo -p 127.0.0.1:7474:7474 \
  -e CIMPAL_API_TOKEN="$(head -n 1 secrets/token)" ghcr.io/griddigit-ci/cimpal:2026.10.30.3 \
  serve --host=0.0.0.0 --allow-remote --allowed-host=localhost:7474
BASE=http://localhost:7474 bash ../demo/demo-v1.sh
```

`demo-v1.sh` prints each step of the [`/v1` job API](../cli/serve.md#job-api-v1):
1. **Ready?** `GET /ready` needs no token. Orchestrators use it as a probe.
2. **Upload:** the models, constraints and mapping go up as one ZIP (`POST /v1/uploads`). The client and the server share no file system.
3. **Submit:** `POST /v1/jobs` answers at once with a job ID and status `queued`. A validation can run for minutes, so the client never holds a connection open waiting for it.
4. **Poll:** `GET /v1/jobs/<id>` until the status is `succeeded`.
5. **Result:** the same JSON summary as in part 1.
6. **Download:** the Excel and Turtle reports, one by one or as one ZIP.

Also worth showing:
- **The API description:**
  ```bash
  curl -k https://localhost:8443/v1/openapi.json
  ```
  It is OpenAPI 3.1, so clients can generate their own code from it. The `/v1` API is a stable interface; see [Versioning and support](versioning-and-support.md).
- **Metrics:** for Prometheus.
  ```bash
  curl -k -H "Authorization: Bearer $(head -n 1 secrets/token)" https://localhost:8443/metrics
  ```
- **The logs:** one JSON line per request and job, ready for a log system.
  ```bash
  docker compose logs cimpal
  ```

## 3. The same from Python

With the TLS proxy, first fetch the proxy's CA certificate so the SDK can check the server:
```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt caddy-root.crt
python ../demo/demo_sdk.py
```
With plain HTTP:
```bash
python ../demo/demo_sdk.py http://localhost:7474
```

[`demo_sdk.py`](../../deploy/demo/demo_sdk.py) is about fifteen lines:
1. upload a folder (the SDK zips it);
2. submit;
3. wait;
4. read the summary;
5. download the reports.

Errors are Python exceptions. Downloads can't write outside the target folder, and the token never appears in error messages. More in [Python SDK](python-sdk.md). The Airflow provider (`airflow-provider-cimpal`) wraps the same calls in operators.

## Optional extras

- **Jobs that survive a restart:** add PostgreSQL with `compose.postgres.yaml`, as in [deploy/docker-compose/README.md](../../deploy/docker-compose/README.md). Then:
  1. submit a job;
  2. run `docker compose restart cimpal`;
  3. the job and its result are still there.
- **Kubernetes:** [deploy/kubernetes](../../deploy/kubernetes/README.md) has the same setup as manifests.
- **AI assistant:** `mcp` gives Claude the CimPal commands as tools; see [docker.md](../cli/docker.md#mcp-server-for-claude-desktop).

## After the demo

```bash
docker compose down            # TLS variant; add -v to drop Caddy's CA as well
docker rm -f cimpal-demo       # plain-HTTP variant
```
`setup.sh` leaves `inputs/`, `data/` and `secrets/` in `deploy/docker-compose`; the demo also leaves `inputs.zip`, `outputs.zip`, `reports/` and `caddy-root.crt`. Git ignores all of them. Delete them when you are done.
