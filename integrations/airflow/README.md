<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal with Apache Airflow (path A: a container or process per task)

These example DAGs run a CimPal validation as one Airflow task, put a small JSON summary into XCom, and let the next task decide what to do with violations. The [Airflow guide](../../docs/guide/airflow.md) explains the pattern; this folder holds runnable examples and their tests.

| File | What it shows |
|---|---|
| `examples/cimpal_kpo.py` | `KubernetesPodOperator`: the CimPal image in a pod, models on a PVC, summary to `/airflow/xcom/return.json` (the task's XCom), then a branch on `hasViolations` |
| `examples/cimpal_docker.py` | `DockerOperator` on the worker: a host folder mounted at `/data`, summary file read by a follow-up task |
| `examples/cimpal_bash.py` | `BashOperator`: `java -jar CimPal-CLI.jar` on a worker with Java 25 |
| `examples/data/` | A small synthetic EQ/SSH model set (1,983 triples, 10 known violations, from `scripts/bench/gen_models.py`) and `run.json`, the validate config |
| `tests/test_dag_integrity.py` | The DAGs import cleanly; the Bash DAG really runs CimPal (when `CIMPAL_JAR` is set) |

All three DAGs call CimPal with:
- **`--summary-file <path>`**: the JSON result is also written to a file, atomically.
- **`--violations-exit-code 0`**: "violations found" ends the task successfully, so findings become data. Bad input (exit 2) and internal errors (exit 3) still fail it.

They target **Airflow 3** (tested with 3.3.2), using the TaskFlow API from `airflow.sdk` and the providers `cncf-kubernetes`, `docker` and `standard`.

## Using them

1. Copy the DAG you need into your DAGs folder.
2. Set the Airflow Variables it reads:

| Variable | Used by | Default |
|---|---|---|
| `cimpal_image` | kpo, docker | `ghcr.io/griddigit-ci/cimpal:latest`. Pin a release tag, e.g. `ghcr.io/griddigit-ci/cimpal:2026.10.6.1`. |
| `cimpal_pvc` | kpo | `cimpal-data`: the PersistentVolumeClaim holding `run.json` and the models |
| `cimpal_jar` | bash | `/opt/cimpal/CimPal-CLI.jar` |

The Docker and Bash DAGs read the worker environment variable `CIMPAL_DATA_DIR` when they are parsed. The Bash DAG has no trigger-time parameters, and passes values to the shell only as environment variables, so nothing a triggerer types can reach the command line. All three DAGs run one at a time (`max_active_runs=1`), because runs share their output folder.

The Docker DAG reads two worker environment variables, because a mount can't be templated:
- `CIMPAL_DATA_DIR`: the host folder.
- `CIMPAL_DOCKER_USER`: on Linux, the UID:GID that owns that folder.

3. Put your `run.json` (a [validate config](../../docs/cli/validate.md)) and the models in the data folder or volume. Paths in `run.json` are relative to the file. Keep `"samples": 0` unless you need per-shape detail: the KPO summary *is* the XCom, and XCom should stay small.

4. Size the pod or container from the [sizing guide](../../docs/guide/sizing.md). The KPO example requests 1.5 GiB and 2 CPUs, enough for models up to about 1 million triples.

## Testing

The CI workflow `.github/workflows/integrations.yml` runs these tests on Ubuntu. Locally (Linux or macOS; Airflow does not run on Windows):

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r integrations/airflow/requirements-test.txt \
  --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-3.12.txt
export AIRFLOW_HOME=$PWD/target/airflow-home
airflow db migrate
mvn -B -pl CimPal-CLI -am package -DskipTests
CIMPAL_JAR=$PWD/CimPal-CLI/target/CimPal-CLI.jar pytest integrations/airflow/tests -v
```

Without `CIMPAL_JAR`, only the import and structure tests run.

## End-to-end walkthrough on Kubernetes (manual)

On kind, or Docker Desktop's Kubernetes, with Airflow from the official Helm chart.

1. Start a cluster and install Airflow:
   ```bash
   kind create cluster --name cimpal
   helm repo add apache-airflow https://airflow.apache.org
   helm install airflow apache-airflow/airflow --namespace airflow --create-namespace
   ```
   Then get the DAGs in: mount `examples/` with the chart's `dags.persistence` or gitSync options, or build an image with them.
2. Create the PVC and copy the example data onto it, for example with a short-lived pod that mounts it:
   ```bash
   kubectl -n airflow apply -f - <<'EOF'
   apiVersion: v1
   kind: PersistentVolumeClaim
   metadata: {name: cimpal-data}
   spec: {accessModes: [ReadWriteOnce], resources: {requests: {storage: 1Gi}}}
   EOF
   kubectl -n airflow run copy --image=busybox --restart=Never \
     --overrides='{"spec":{"volumes":[{"name":"d","persistentVolumeClaim":{"claimName":"cimpal-data"}}],"containers":[{"name":"copy","image":"busybox","command":["sleep","600"],"volumeMounts":[{"name":"d","mountPath":"/data"}]}]}}'
   kubectl -n airflow cp integrations/airflow/examples/data/. copy:/data
   kubectl -n airflow exec copy -- chown -R 10001:10001 /data
   kubectl -n airflow delete pod copy
   ```
3. Set the Variables `cimpal_image` (a release tag) and `cimpal_pvc` = `cimpal-data`, then trigger `cimpal_kpo`.
4. **Expected:**
   - `validate` succeeds.
   - Its XCom shows `"hasViolations": true` and `"totals": {"violations": 1, ...}` (one mapping row with violations).
   - `route` picks `report_violations`, and `publish` is skipped.
   - The Excel report is on the PVC under `out/`.
5. A model without violations takes the `publish` branch instead. Bad input fails `validate` with exit code 2.
