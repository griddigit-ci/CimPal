# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The client against a real ``java -jar CimPal-CLI.jar serve``.

The JAR is ``CimPal-CLI/target/CimPal-CLI.jar`` (``mvn -B -pl CimPal-CLI -am package -DskipTests``)
or the one ``CIMPAL_JAR`` names. Without Java or the JAR these tests are skipped, unless
``CIMPAL_INTEGRATION_REQUIRED=1`` (CI) makes that a failure.
"""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import time
import zipfile
from collections.abc import Iterator
from pathlib import Path

import httpx
import pytest

from cimpal_client import AsyncCimPal, CimPal, ForbiddenError, JobStatus

pytestmark = pytest.mark.integration

TOKEN = "integration-token-0123456789abcdef0123456789"
JAR = Path(os.environ.get("CIMPAL_JAR") or Path(__file__).resolve().parents[3] / "CimPal-CLI/target/CimPal-CLI.jar")

SHAPES = """\
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix ex: <urn:test:> .
ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
    sh:property [ sh:path ex:size ; sh:minCount 1 ] .
"""
DATA = """\
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
  <ex:Thing rdf:about="#_1"/>
</rdf:RDF>
"""
CONFIG = {
    "workflow": "combined",
    "constraintFiles": ["constraints/shapes.ttl"],
    "dataFiles": ["models/data.xml"],
    "xmlBase": "http://example.com/data",
    "workers": 1,
}


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.fixture(scope="module")
def server(tmp_path_factory: pytest.TempPathFactory) -> Iterator[str]:
    """A serve on a free port with a token, its own root and work folder; its base URL."""
    java = shutil.which("java")
    if java is None or not JAR.is_file():
        if os.environ.get("CIMPAL_INTEGRATION_REQUIRED") == "1":
            pytest.fail(f"CIMPAL_INTEGRATION_REQUIRED is set, but Java or {JAR} is missing")
        pytest.skip(f"needs Java and {JAR}")
    root = tmp_path_factory.mktemp("root")
    port = _free_port()
    log = (root.parent / "serve.log").open("wb")
    env = {**os.environ, "CIMPAL_API_TOKEN": TOKEN}
    process = subprocess.Popen(  # noqa: S603 (fixed arguments)
        [
            java,
            "-jar",
            str(JAR),
            "serve",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--root",
            str(root),
            "--work-root",
            str(root.parent / "work"),
        ],
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    base = f"http://127.0.0.1:{port}"
    try:
        deadline = time.monotonic() + 60
        while True:
            if process.poll() is not None:
                pytest.fail(f"serve exited with {process.returncode}; see {log.name}")
            try:
                if httpx.get(base + "/v1/health", timeout=2).status_code == 200:
                    break
            except httpx.TransportError:
                pass
            if time.monotonic() > deadline:
                pytest.fail(f"serve did not answer within 60 s; see {log.name}")
            time.sleep(0.3)
        yield base
    finally:
        try:
            httpx.post(
                base + "/shutdown",
                headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
                timeout=10,
            )
            process.wait(timeout=60)
        except (httpx.TransportError, subprocess.TimeoutExpired):
            process.kill()
        log.close()


@pytest.fixture
def models(tmp_path: Path) -> Path:
    folder = tmp_path / "input"
    (folder / "constraints").mkdir(parents=True)
    (folder / "models").mkdir()
    (folder / "constraints" / "shapes.ttl").write_text(SHAPES, encoding="utf-8")
    (folder / "models" / "data.xml").write_text(DATA, encoding="utf-8")
    return folder


def test_upload_submit_wait_result_download(server: str, models: Path, tmp_path: Path) -> None:
    with CimPal(server, token=TOKEN) as cp:
        assert cp.health().api_version == "1"
        upload = cp.upload(models)
        assert sorted(f.name for f in upload.files) == ["constraints/shapes.ttl", "models/data.xml"]

        job = cp.submit("validate", CONFIG, input_upload=upload, label="sdk integration")
        job = cp.wait(job, timeout=300, poll_interval=0.5)

        assert job.status is JobStatus.SUCCEEDED, cp.log(job).lines
        assert job.exit_code == 1 and job.has_violations is True
        summary = cp.result(job)
        assert summary["hasViolations"] is True
        assert summary["totals"]["violations"] == 1

        written = cp.download_outputs(job, tmp_path / "out")
        assert any(p.suffix == ".xlsx" for p in written), written
        archive = cp.download_archive(job, tmp_path / "outputs.zip")
        assert sorted(zipfile.ZipFile(archive).namelist()) == sorted(
            p.relative_to(tmp_path / "out").as_posix() for p in written
        )
        assert cp.log(job).lines, "the job's progress lines"
        assert any(j.job_id == job.job_id for j in cp.list_jobs())


async def test_the_async_client_runs_the_same_job(server: str, models: Path) -> None:
    async with AsyncCimPal(server, token=TOKEN) as cp:
        upload = await cp.upload(models)
        job = await cp.wait(await cp.submit("validate", CONFIG, input_upload=upload), timeout=300, poll_interval=0.5)
        assert job.status is JobStatus.SUCCEEDED
        assert (await cp.result(job))["hasViolations"] is True


def test_a_path_outside_the_allowed_folders_is_forbidden_at_submit(server: str) -> None:
    with CimPal(server, token=TOKEN) as cp, pytest.raises(ForbiddenError) as raised:
        cp.submit("validate", {**CONFIG, "dataFiles": ["/etc/passwd" if os.name != "nt" else "C:/Windows/win.ini"]})
    assert raised.value.status == 403
    assert raised.value.detail
