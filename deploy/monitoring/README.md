<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Monitoring CimPal `serve`

Prometheus recording and alert rules for `cimpal serve --metrics` (DEP-16). They put the [service levels](../../docs/guide/service.md#service-levels) into alerts; the metrics themselves are listed in [serve.md](../../docs/cli/serve.md#metrics).

| File | What it is |
|---|---|
| `cimpal-alerts.yaml` | Recording rules (error ratios, API p95, queue wait p95) and alerts |
| `cimpal-alerts.test.yaml` | `promtool` unit tests for them (CI: `integrations.yml`, deployment examples) |

## Using them

1. **Scrape** each instance's `/metrics` with an admin token (a `client` token gets 403) under the job name `cimpal`:

   ```yaml
   scrape_configs:
     - job_name: cimpal
       scheme: https
       authorization: {credentials_file: /etc/prometheus/cimpal-admin.token}
       static_configs: [{targets: ['cimpal.example.com']}]
   ```

   In Kubernetes, scrape the pods directly (port 7474, plain HTTP inside the cluster) and keep the job label `cimpal`, or change the `job="cimpal"` matchers in the rules.
2. **Load the rules:** `rule_files: [cimpal-alerts.yaml]`. With the Prometheus Operator, put the `groups:` under `spec:` of a `PrometheusRule`.
3. **Check them:** `promtool check rules cimpal-alerts.yaml` and `promtool test rules cimpal-alerts.test.yaml` (or `docker run --rm -v "$PWD:/rules" -w /rules --entrypoint promtool prom/prometheus:v3.5.1 test rules cimpal-alerts.test.yaml`).

## The alerts

| Alert | Fires when | Severity |
|---|---|---|
| `CimPalDown` | the target can't be scraped for 2 min | critical |
| `CimPalErrorBudgetBurnFast` / `…Slow` | server errors (`5xx`) burn the 99.5 % availability budget 14.4× (1 h and 5 min) or 6× (6 h and 1 h) too fast | critical / warning |
| `CimPalApiSlow` | the API's p95 (status, polling, listing; not transfers or synchronous commands) is above 200 ms for 15 min | warning |
| `CimPalQueueWaitHigh` | jobs' queue wait p95 over 30 min is above 5 min | warning |
| `CimPalQueueNearFull` | the queue is at 80 % of `--queue-size` for 10 min | warning |
| `CimPalRefusingJobs` | 503 (queue full) answers for 10 min | warning |
| `CimPalJobStalled` | a command has run past its timeout for 5 min | critical |
| `CimPalHeapHigh` | the heap's lowest use over 10 min (what survives GC) is above 90 % | warning |
| `CimPalWorkAreaFull` | uploads and outputs fill 80 % of `--work-max-bytes` | warning |
| `CimPalJobStoreErrors` | the job store database fails (`--job-store jdbc`) | critical |
| `CimPalJobsFailing` | over 10 % of at least 10 jobs in an hour failed or timed out | warning |

503 (a full queue) and 429 (a client's quota) don't count against availability: the server answers as designed and clients retry. A queue that stays full is a capacity problem and has its own alerts. A job that ends `failed` is counted by `CimPalJobsFailing`, not in the HTTP error ratio.

Tune the thresholds to your load: a service whose jobs run for an hour needs a looser queue wait alert than the 5 minute SLO, which assumes jobs of minutes (see the [sizing guide](../../docs/guide/sizing.md#running-as-a-service-capacity)).
