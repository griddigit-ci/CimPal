<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CimPal `serve` with Docker Compose and TLS

`compose.yaml` runs two containers:
- **`cimpal`:** the CimPal image running `serve`. It sits on an internal network only, has a read-only root filesystem, no capabilities, and reads its token from `./secrets/token`.
- **`caddy`:** the TLS proxy, published on `https://localhost:8443`. It uses its own local CA (`tls internal`), so curl needs `-k` or Caddy's root certificate.

TLS ends at Caddy; CimPal itself only speaks plain HTTP, inside the compose network. More on running CimPal as a service: [docs/guide/service.md](../../docs/guide/service.md).

## Start

```bash
cd deploy/docker-compose
mkdir -p data secrets
(umask 077 && openssl rand -hex 32 > secrets/token)
cp /path/to/your/model.ttl data/      # the models the jobs read
export CIMPAL_UID=$(id -u) CIMPAL_GID=$(id -g)   # run as you, so the token file can stay private
docker compose up -d
docker compose logs -f cimpal
```

- **Folder, not file:** `./secrets` is mounted as a folder. A single-file mount would keep showing the old file after an editor or `mv` replaced it, and a revoked token would stay valid.
- **Docker Desktop (Windows, macOS):** file ownership isn't checked; `openssl rand -hex 32` can be replaced by any random string of at least 32 characters.

## Use

```bash
token=$(cat secrets/token)
curl -k https://localhost:8443/ready
curl -k -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"command":"sparql","config":{"models":["/data/model.ttl"],"query":"SELECT * WHERE { ?s ?p ?o } LIMIT 5"}}' \
  https://localhost:8443/v1/jobs
curl -k -H "Authorization: Bearer $token" https://localhost:8443/v1/jobs/<jobId>/result
curl -k -H "Authorization: Bearer $token" https://localhost:8443/metrics
```

Paths in a job's config are container paths under `/data`, which is `./data` here.

## Rotate the token

1. Write the new token as a second line: `secrets/token` then holds the old and the new token, and both are accepted within a minute (`--token-reload`).
2. Switch the clients to the new token.
3. Remove the old line. After the next reload, the old token is refused.

No restart is needed. An empty file (or one with only `#` comments) revokes every token. A file that can't be read is bridged for one check and then refused as well, so `/ready` turns 503. Replace the file in one step (write a new file, then `mv`) rather than writing into it, so `serve` never reads half a token.

## Stop

`docker compose down` sends SIGTERM:
- `serve` stops taking work and cancels queued jobs;
- it gives a running command up to `--shutdown-grace` (60 s) to finish, and then exits.

`stop_grace_period: 90s` gives it that time before Docker kills the container.
