# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal on Kubernetes: one pod per validation, the summary through XCom (DEP-3, path A).

The pod runs the CimPal image (docs/cli/docker.md) with the model set on a PersistentVolumeClaim
mounted at /data. CimPal writes its JSON summary to /airflow/xcom/return.json, which the
KubernetesPodOperator's sidecar reads as the task's XCom; `--violations-exit-code 0` makes
"violations found" a successful task, so the next task can branch on `hasViolations`.

Airflow Variables (all optional):
    cimpal_image   the image, default ghcr.io/griddigit-ci/cimpal:2026.10.6.1 (a release tag; never :latest)
    cimpal_pvc     the PersistentVolumeClaim holding run.json and the models, default cimpal-data

Sizing: the requests below suit models up to about 1 million triples; see docs/guide/sizing.md.
"""
from __future__ import annotations

from datetime import datetime

from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from airflow.sdk import dag, task
from kubernetes.client import models as k8s

PVC = "{{ var.value.get('cimpal_pvc', 'cimpal-data') }}"


def compact(summary: dict) -> dict:
    """The part of CimPal's summary worth keeping in XCom: never whole reports.

    Strict on purpose: a missing key raises instead of reading as "conforming", and a run that
    validated no rows is not conforming either.
    """
    totals = summary["totals"]
    return {
        "conforms": totals["total"] > 0 and totals["violations"] == 0 and totals["errors"] == 0,
        "hasViolations": summary["hasViolations"],
        "totals": totals,
        "report": summary.get("report") or summary.get("reports"),
    }


@dag(
    dag_id="cimpal_kpo",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    tags=["cimpal", "validation"],
    doc_md=__doc__,
)
def cimpal_kpo():
    validate = KubernetesPodOperator(
        task_id="validate",
        name="cimpal-validate",
        image="{{ var.value.get('cimpal_image', 'ghcr.io/griddigit-ci/cimpal:2026.10.6.1') }}",
        arguments=[
            "validate", "--config", "/data/run.json",
            # Keep the XCom small: totals and the report path, no per-shape samples.
            "--samples", "0",
            "--summary-file", "/airflow/xcom/return.json",
            "--violations-exit-code", "0",
        ],
        volumes=[
            k8s.V1Volume(name="data",
                         persistent_volume_claim=k8s.V1PersistentVolumeClaimVolumeSource(claim_name=PVC)),
            # The image runs with a read-only root filesystem; /tmp must be writable.
            k8s.V1Volume(name="tmp", empty_dir=k8s.V1EmptyDirVolumeSource()),
        ],
        volume_mounts=[
            k8s.V1VolumeMount(name="data", mount_path="/data"),
            k8s.V1VolumeMount(name="tmp", mount_path="/tmp"),
        ],
        container_resources=k8s.V1ResourceRequirements(
            requests={"memory": "1536Mi", "cpu": "2"},
            limits={"memory": "1536Mi", "cpu": "2"},
        ),
        # Pod Security "restricted": non-root, no privileges, no service-account token in a pod
        # that parses untrusted RDF.
        security_context=k8s.V1PodSecurityContext(
            run_as_user=10001, run_as_group=10001, fs_group=10001, run_as_non_root=True,
            seccomp_profile=k8s.V1SeccompProfile(type="RuntimeDefault")),
        container_security_context=k8s.V1SecurityContext(
            read_only_root_filesystem=True, allow_privilege_escalation=False,
            capabilities=k8s.V1Capabilities(drop=["ALL"])),
        automount_service_account_token=False,
        do_xcom_push=True,
        get_logs=True,
        on_finish_action="delete_pod",
    )

    @task.branch
    def route(summary: dict) -> str:
        # Strict: a summary without the key fails here rather than taking the "clean" branch.
        return "report_violations" if summary["hasViolations"] else "publish"

    @task
    def report_violations(summary: dict) -> dict:
        result = compact(summary)
        print(f"CimPal found violations: {result['totals']} - report: {result['report']}")
        return result

    @task
    def publish(summary: dict) -> dict:
        result = compact(summary)
        print(f"All conforming: {result['totals']}")
        return result

    summary = validate.output
    route(summary) >> [report_violations(summary), publish(summary)]


cimpal_kpo()
