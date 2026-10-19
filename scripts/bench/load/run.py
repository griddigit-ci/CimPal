# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal service load test (DEP-16): drives `serve`'s /v1 job API with the Python SDK and records
what a client and an operator see under load.

Load models:
  open (default)   jobs arrive as a Poisson process at --rate jobs per minute for --duration
                   seconds, whatever the server does (as independent clients do); the queue wait
                   then shows whether the server keeps up
  closed           --clients clients each submit a job, wait for it and submit the next, for
                   --duration seconds: the throughput this gives is the server's limit

Scenarios, mixed by weight (--scenario mixed, or one of them, or --mix small=6,sparql=2):
  small    validate a 100k-triple synthetic model (scripts/bench/gen_models.py) from a shared folder
  medium   validate a 1M-triple model from a shared folder
  sparql   a SPARQL count over the 100k model's EQ file
  upload   upload the 100k model as a ZIP, validate it, download the output archive

Per job: submit latency, queue wait (startedAt - createdAt), run time, end-to-end time
(finishedAt - createdAt), outcome. During the run: GET /v1/health and GET /v1/jobs/{id} latency
every --probe-interval, and /metrics (queue depth, running, heap, work area) every
--metrics-interval; HTTP answers by route and status (503 and 429 included, also those the SDK
retried).

Server: --url and --token-file for a running server (its --root/--write-root must hold the paths
given by --server-models-root and --server-output-root), or --start-server to launch
CimPal-CLI.jar here with --workers, --queue-size, --heap and --cores (the first N logical CPUs:
CPU affinity plus -XX:ActiveProcessorCount; taskset on Linux).

Results: <out>/jobs.csv, probes.csv, metrics.csv, http.csv, summary.json and report.md.
Needs the SDK (pip install -e clients/python) and Python 3.10+. See scripts/bench/load/README.md.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import ctypes
import json
import math
import os
import random
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from collections import Counter
from dataclasses import asdict, dataclass, fields
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE.parent))
import run_bench  # noqa: E402  (DEP-2: models and hardware)

try:
    import httpx
    from cimpal_client import AsyncCimPal, errors
except ImportError:  # a development tree without `pip install -e clients/python`
    sys.path.insert(0, str(REPO / "clients" / "python" / "src"))
    try:
        import httpx
        from cimpal_client import AsyncCimPal, errors
    except ImportError as e:
        sys.exit(f"[load] needs the CimPal SDK and its dependencies: pip install -e clients/python ({e})")

XML_BASE = run_bench.XML_BASE
SPARQL_QUERY = "SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }"
FINAL = {"succeeded", "failed", "cancelled", "timed_out"}

#: name -> (model size, kind)
SCENARIOS = {
    "small": ("100k", "validate"),
    "medium": ("1M", "validate"),
    "sparql": ("100k", "sparql"),
    "upload": ("100k", "upload"),
}
MIXES = {"mixed": {"small": 6, "medium": 1, "sparql": 2, "upload": 1}}

#: SLOs (docs/guide/service.md, decided for DEP-16): name -> (target, unit, lower is better)
SLOS = {
    "availability": (99.5, "%", False),
    "get_p95_ms": (200.0, "ms", True),
    "submit_p95_ms": (500.0, "ms", True),
    "queue_wait_p95_s": (300.0, "s", True),
}

WANTED_METRICS = ("cimpal_queue_depth", "cimpal_running", "cimpal_workers", "cimpal_queue_size",
                  "cimpal_jvm_heap_used_bytes", "cimpal_jvm_heap_max_bytes", "cimpal_work_used_bytes",
                  "cimpal_stalled")


@dataclass
class JobRecord:
    seq: int
    scenario: str
    arrivalS: float               # seconds after the start
    status: str = "unfinished"    # a job status, or refused_503, refused_429, error_<status>
    exitCode: int | None = None
    submitMs: float | None = None
    uploadMs: float | None = None
    downloadMs: float | None = None
    queueWaitS: float | None = None
    runS: float | None = None
    e2eS: float | None = None     # finishedAt - createdAt (server clocks)
    clientS: float | None = None  # arrival to seeing the final status (includes polling)
    jobId: str = ""
    error: str = ""


@dataclass
class Probe:
    t: float
    endpoint: str
    ms: float
    ok: bool
    status: int | None


# ---------------------------------------------------------------------------------------------
# Pure helpers (unit tested in test_load.py)

def percentile(values, p: float) -> float | None:
    """Linear interpolation between closest ranks; None for no values."""
    s = sorted(v for v in values if v is not None)
    if not s:
        return None
    k = (len(s) - 1) * p / 100
    f = math.floor(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def parse_mix(text: str) -> dict[str, int]:
    if text in MIXES:
        return dict(MIXES[text])
    if text in SCENARIOS:
        return {text: 1}
    mix = {}
    for part in text.split(","):
        name, _, weight = part.partition("=")
        name = name.strip()
        if name not in SCENARIOS:
            raise ValueError(f"unknown scenario {name!r} (one of {', '.join(SCENARIOS)})")
        mix[name] = int(weight or 1)
        if mix[name] < 0:
            raise ValueError("weights must not be negative")
    if not any(mix.values()):
        raise ValueError("the mix needs a positive weight")
    return mix


def arrivals(rate_per_min: float, duration_s: float, rng: random.Random) -> list[float]:
    """Poisson arrival times in [0, duration)."""
    if rate_per_min <= 0:
        raise ValueError("--rate must be positive")
    times, t = [], 0.0
    while True:
        t += rng.expovariate(rate_per_min / 60)
        if t >= duration_s:
            return times
        times.append(t)


def route(path: str) -> str:
    return re.sub(r"/[0-9a-fA-F-]{36}(?=/|$)", "/{id}", path)


def parse_metrics(text: str) -> dict[str, float]:
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        name, _, value = line.rpartition(" ")
        if name in WANTED_METRICS:
            out[name] = float(value)
        elif name.startswith("cimpal_http_responses_total{code="):
            out["http_" + name.split('"')[1]] = float(value)
    return out


def _stats(values) -> dict:
    vals = [v for v in values if v is not None]
    return {"n": len(vals), "p50": percentile(vals, 50), "p95": percentile(vals, 95),
            "p99": percentile(vals, 99), "max": max(vals) if vals else None}


def summarise(jobs: list[JobRecord], probes: list[Probe], samples: list[dict], http: Counter,
              meta: dict) -> dict:
    """Everything report.md shows, as data."""
    by = {"all": jobs}
    for j in jobs:
        by.setdefault(j.scenario, []).append(j)
    scen = {}
    for name, js in by.items():
        done = [j for j in js if j.status in FINAL]
        finish = [j.arrivalS + j.clientS for j in done if j.clientS is not None]
        window = (max(finish) - min(j.arrivalS for j in js)) if finish else None
        scen[name] = {
            "arrived": len(js),
            "outcomes": dict(Counter(j.status for j in js)),
            "completedPerHour": len(done) / window * 3600 if window else None,
            "submitMs": _stats(j.submitMs for j in js),
            "queueWaitS": _stats(j.queueWaitS for j in js),
            "runS": _stats(j.runS for j in js),
            "e2eS": _stats(j.e2eS for j in js),
            "uploadMs": _stats(j.uploadMs for j in js),
            "downloadMs": _stats(j.downloadMs for j in js),
        }
    # Saturation: is the queue wait of the last third of arrivals well above the first third's?
    ordered = sorted((j for j in jobs if j.queueWaitS is not None), key=lambda j: j.arrivalS)
    third = len(ordered) // 3
    growing = None
    if third >= 3:
        first = percentile([j.queueWaitS for j in ordered[:third]], 50)
        last = percentile([j.queueWaitS for j in ordered[-third:]], 50)
        growing = last > 2 * first + 5

    probe_stats = {}
    for ep in sorted({p.endpoint for p in probes}):
        ps = [p for p in probes if p.endpoint == ep]
        probe_stats[ep] = {**_stats(p.ms for p in ps if p.ok), "errors": sum(not p.ok for p in ps)}
    gets = [p for p in probes]
    availability = 100 * sum(p.ok for p in gets) / len(gets) if gets else None

    def peak(key):
        vals = [s[key] for s in samples if key in s]
        return max(vals) if vals else None

    running = [s["cimpal_running"] for s in samples if "cimpal_running" in s]
    measured = {
        "availability": availability,
        "get_p95_ms": percentile([p.ms for p in gets if p.ok], 95),
        "submit_p95_ms": scen["all"]["submitMs"]["p95"],
        "queue_wait_p95_s": scen["all"]["queueWaitS"]["p95"],
    }
    slo = {}
    for name, (target, unit, lower) in SLOS.items():
        m = measured[name]
        slo[name] = {"target": target, "unit": unit, "measured": m,
                     "ok": None if m is None else (m <= target if lower else m >= target)}
    refused = Counter()
    for key, n in http.items():
        status = key.rsplit(" ", 1)[1]
        if status in ("429", "503") or status.startswith("5"):
            refused[status] += n
    return {
        "meta": meta,
        "scenarios": scen,
        "queueWaitGrowing": growing,
        "probes": probe_stats,
        "server": {
            "maxQueueDepth": peak("cimpal_queue_depth"),
            "meanRunning": sum(running) / len(running) if running else None,
            "peakHeapUsedBytes": peak("cimpal_jvm_heap_used_bytes"),
            "heapMaxBytes": peak("cimpal_jvm_heap_max_bytes"),
            "peakWorkUsedBytes": peak("cimpal_work_used_bytes"),
            "stalledSeen": bool(peak("cimpal_stalled")),
        },
        "http": dict(sorted(http.items())),
        "httpRefusedOrFailed": dict(sorted(refused.items())),
        "slo": slo,
    }


def _f(v, digits=1, scale=1.0) -> str:
    return "–" if v is None else f"{v / scale:,.{digits}f}"


def render_report(s: dict) -> str:
    m = s["meta"]
    out = [f"# Load test: {m['label']}", "",
           f"- **When:** {m['started']}; {m['loadModel']} load, {m['describe']}",
           f"- **Server:** {m['server']}",
           f"- **Machine:** {m['hardware'].get('cpu', '?')}, {m['hardware'].get('logicalCpus', '?')} logical CPUs, "
           f"{_f(m['hardware'].get('ramBytes'), 0, 2**30)} GiB RAM, {m['hardware'].get('os', '?')}",
           f"- **Queue wait growing (saturated):** {s['queueWaitGrowing']}", "",
           "## Jobs", "",
           "| Scenario | Arrived | Outcomes | Done/h | Submit p95 ms | Wait p50 / p95 s | Run p50 / p95 s | End-to-end p95 s |",
           "|---|---:|---|---:|---:|---:|---:|---:|"]
    for name, x in s["scenarios"].items():
        outcomes = ", ".join(f"{k} {v}" for k, v in sorted(x["outcomes"].items()))
        out.append(f"| {name} | {x['arrived']} | {outcomes} | {_f(x['completedPerHour'], 0)} | "
                   f"{_f(x['submitMs']['p95'], 0)} | {_f(x['queueWaitS']['p50'])} / {_f(x['queueWaitS']['p95'])} | "
                   f"{_f(x['runS']['p50'])} / {_f(x['runS']['p95'])} | {_f(x['e2eS']['p95'])} |")
    out += ["", "## API latency while busy", "", "| Endpoint | n | p50 ms | p95 ms | p99 ms | Errors |",
            "|---|---:|---:|---:|---:|---:|"]
    for ep, x in s["probes"].items():
        out.append(f"| `{ep}` | {x['n']} | {_f(x['p50'])} | {_f(x['p95'])} | {_f(x['p99'])} | {x['errors']} |")
    sv = s["server"]
    out += ["", "## Server", "",
            f"- Largest queue depth {_f(sv['maxQueueDepth'], 0)}, mean running {_f(sv['meanRunning'], 2)}",
            f"- Peak heap {_f(sv['peakHeapUsedBytes'], 0, 2**20)} MiB of {_f(sv['heapMaxBytes'], 0, 2**20)} MiB; "
            f"peak work area {_f(sv['peakWorkUsedBytes'], 0, 2**20)} MiB; stalled seen: {sv['stalledSeen']}",
            f"- Refused or failed HTTP answers (including those the SDK retried): "
            f"{', '.join(f'{k}: {v}' for k, v in s['httpRefusedOrFailed'].items()) or 'none'}",
            "", "## SLOs", "", "| SLO | Target | Measured | Met |", "|---|---:|---:|---|"]
    for name, x in s["slo"].items():
        sign = ">=" if name == "availability" else "<"
        out.append(f"| {name} | {sign} {x['target']:g} {x['unit']} | {_f(x['measured'], 2)} | "
                   f"{'–' if x['ok'] is None else ('yes' if x['ok'] else '**no**')} |")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------------------------
# Models

class Models:
    """The synthetic model sets, generated once under --models-root (as DEP-2 does)."""

    def __init__(self, root: Path, seed: int, server_root: str | None):
        self.root, self.seed, self.server_root = root, seed, server_root
        self.dirs: dict[str, Path] = {}

    def prepare(self, sizes) -> None:
        for size in sorted(set(sizes)):
            self.dirs[size], _ = run_bench.ensure_models(self.root, size, self.seed)

    def server_path(self, local: Path) -> str:
        if not self.server_root:
            return str(local)
        return self.server_root.rstrip("/") + "/" + local.relative_to(self.root).as_posix()

    def validate_config(self, size: str, output: str) -> dict:
        d = self.dirs[size]
        return {"workflow": "mapping", "mappingCsv": self.server_path(d / "mapping.csv"),
                "modelsDir": self.server_path(d / "models"), "constraintsRoot": self.server_path(d / "constraints"),
                "outputDir": output, "xmlBase": XML_BASE}

    def sparql_config(self, size: str) -> dict:
        eq = sorted((self.dirs[size] / "models").glob("*/*EQ*.xml"))[0]
        return {"models": [self.server_path(eq)], "query": SPARQL_QUERY, "xmlBase": XML_BASE, "format": "json"}

    @staticmethod
    def upload_config() -> dict:
        return {"workflow": "mapping", "mappingCsv": "mapping.csv", "modelsDir": "models",
                "constraintsRoot": "constraints", "xmlBase": XML_BASE}

    def upload_folder(self, size: str) -> Path:
        """mapping.csv, models/ and constraints/ only (not manifest.json)."""
        folder = self.root / f"{size}-seed{self.seed}-upload"
        if not (folder / "mapping.csv").is_file():
            shutil.rmtree(folder, ignore_errors=True)
            src = self.dirs[size]
            shutil.copytree(src / "models", folder / "models")
            shutil.copytree(src / "constraints", folder / "constraints")
            shutil.copy2(src / "mapping.csv", folder / "mapping.csv")
        return folder


# ---------------------------------------------------------------------------------------------
# A local server

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def pin_windows(proc: subprocess.Popen, cores: int) -> bool:
    k = ctypes.WinDLL("kernel32", use_last_error=True)
    k.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    return bool(k.SetProcessAffinityMask(ctypes.c_void_p(int(proc._handle)), (1 << cores) - 1))


class LocalServer:
    def __init__(self, args, models_root: Path, output_root: Path, out: Path):
        self.args, self.models_root, self.output_root, self.out = args, models_root, output_root, out
        self.proc: subprocess.Popen | None = None
        self.token = secrets.token_urlsafe(32)
        self.port = free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        self.cmd: list[str] = []

    def start(self) -> None:
        a = self.args
        java = a.java or shutil.which("java") or "java"
        cores = a.cores
        if cores and cores > (os.cpu_count() or cores):
            raise SystemExit(f"[load] --cores {cores} is more than this machine's {os.cpu_count()} logical CPUs")
        jvm = [java, f"-Xmx{a.heap}", "-XX:+ExitOnOutOfMemoryError"]
        if cores:
            jvm.append(f"-XX:ActiveProcessorCount={cores}")
        cli = ["serve", "--port", str(self.port), "--workers", str(a.workers), "--queue-size", str(a.queue_size),
               "--metrics", "--root", str(self.models_root), "--write-root", str(self.output_root),
               "--max-jobs", "100000", "--log-format", "json", *a.server_arg]
        prefix = []
        if cores and sys.platform.startswith("linux") and shutil.which("taskset"):
            prefix = ["taskset", "-c", f"0-{cores - 1}"]
        self.cmd = prefix + jvm + ["-jar", str(a.jar)] + cli
        # Only this run's token: inherited CIMPAL_* settings (a token file, a job store) would change the server.
        env = {k: v for k, v in os.environ.items() if not k.upper().startswith("CIMPAL_")}
        env["CIMPAL_API_TOKEN"] = self.token
        self.log = open(self.out / "server.log", "wb")
        self.proc = subprocess.Popen(self.cmd, env=env, stdout=self.log, stderr=subprocess.STDOUT,
                                     cwd=self.output_root)
        if cores and sys.platform == "win32" and not pin_windows(self.proc, cores):
            print("[load] warning: could not set the CPU affinity", file=sys.stderr)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise SystemExit(f"[load] serve exited with {self.proc.returncode}; see {self.out / 'server.log'}")
            try:
                if httpx.get(self.url + "/v1/health", timeout=2).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.5)
        self.stop()
        raise SystemExit("[load] serve did not become healthy within 120 s")

    def describe(self) -> str:
        a = self.args
        return (f"local `serve`, --workers {a.workers}, --queue-size {a.queue_size}, -Xmx{a.heap}, "
                f"cores {a.cores or 'all'}" + (f", {' '.join(a.server_arg)}" if a.server_arg else ""))

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                httpx.post(self.url + "/shutdown", json={}, headers={"Authorization": f"Bearer {self.token}"}, timeout=5)
                self.proc.wait(timeout=90)
            except (httpx.HTTPError, subprocess.TimeoutExpired):
                self.proc.kill()
                self.proc.wait()
        if getattr(self, "log", None):
            self.log.close()


# ---------------------------------------------------------------------------------------------
# The load

class Load:
    def __init__(self, args, url: str, token: str, models: Models, output_root: str, mix: dict[str, int]):
        self.args, self.url, self.token, self.models, self.output_root = args, url, token, models, output_root
        self.names = [n for n, w in mix.items() if w > 0]
        self.weights = [mix[n] for n in self.names]
        self.rng = random.Random(args.seed)
        self.jobs: list[JobRecord] = []
        self.probes: list[Probe] = []
        self.samples: list[dict] = []
        self.http: Counter = Counter()
        self.last_job: str | None = None
        self.start = 0.0
        self.stopping = asyncio.Event()
        self.tmp = Path(tempfile.mkdtemp(prefix="cimpal-load-"))

    async def _hook(self, response: httpx.Response) -> None:
        self.http[f"{response.request.method} {route(response.request.url.path)} {response.status_code}"] += 1

    def _client(self, retries: int) -> AsyncCimPal:
        http = httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0), follow_redirects=False,
                                 limits=httpx.Limits(max_connections=400, max_keepalive_connections=100),
                                 event_hooks={"response": [self._hook]})
        # Plain http only to the server launched here (loopback); the SDK refuses it elsewhere.
        return AsyncCimPal(self.url, self.token, retries=retries, allow_insecure_http=self.args.start_server,
                           http_client=http)

    async def one_job(self, cp: AsyncCimPal, seq: int, scenario: str, arrival: float) -> None:
        rec = JobRecord(seq, scenario, round(arrival, 3))
        self.jobs.append(rec)
        size, kind = SCENARIOS[scenario]
        try:
            upload = None
            if kind == "upload":
                t = time.monotonic()
                upload = await cp.upload(self.models.upload_folder(size))
                rec.uploadMs = (time.monotonic() - t) * 1000
                config = Models.upload_config()
            elif kind == "sparql":
                config = self.models.sparql_config(size)
            else:
                config = self.models.validate_config(size, f"{self.output_root.rstrip('/')}/{scenario}-{seq}")
            t = time.monotonic()
            job = await cp.submit(kind if kind != "upload" else "validate", config, input_upload=upload,
                                  label=f"load-{scenario}")
            rec.submitMs = (time.monotonic() - t) * 1000
            rec.jobId = str(job.job_id)
            self.last_job = rec.jobId
            job = await cp.wait(job, timeout=self.args.job_timeout, poll_interval=1.0, max_poll_interval=5.0)
            rec.clientS = time.monotonic() - self.start - arrival
            rec.status, rec.exitCode = str(job.status), job.exit_code
            created, started, finished = job.created_at, job.started_at, job.finished_at
            if started:
                rec.queueWaitS = (started - created).total_seconds()
                if finished:
                    rec.runS = (finished - started).total_seconds()
            if finished:
                rec.e2eS = (finished - created).total_seconds()
            if kind == "upload" and rec.status == "succeeded":
                t = time.monotonic()
                await cp.download_archive(job, self.tmp / f"{seq}.zip")
                rec.downloadMs = (time.monotonic() - t) * 1000
                (self.tmp / f"{seq}.zip").unlink(missing_ok=True)
                await cp.delete_upload(upload)
        except errors.BusyError as e:
            rec.status, rec.error = f"refused_{e.status}", (e.detail or str(e))[:200]
        except errors.TimeoutError:
            rec.status = "unfinished"
        except errors.CimPalError as e:
            rec.status, rec.error = f"error_{e.status}", (e.detail or str(e))[:200]
        except httpx.HTTPError as e:
            rec.status, rec.error = "error_transport", type(e).__name__

    async def probe_loop(self, cp: AsyncCimPal) -> None:
        while not self.stopping.is_set():
            for endpoint, call in (("GET /v1/health", cp.health),
                                   ("GET /v1/jobs/{id}", (lambda: cp.job(self.last_job)) if self.last_job else None)):
                if call is None:
                    continue
                t = time.monotonic()
                try:
                    await call()
                    ok, status = True, 200
                except errors.CimPalError as e:
                    ok, status = (e.status is not None and e.status < 500), e.status
                except httpx.HTTPError:
                    ok, status = False, None
                self.probes.append(Probe(round(t - self.start, 3), endpoint, (time.monotonic() - t) * 1000, ok, status))
            try:
                await asyncio.wait_for(self.stopping.wait(), self.args.probe_interval)
            except asyncio.TimeoutError:
                pass

    async def metrics_loop(self) -> None:
        async with httpx.AsyncClient(timeout=10) as http:
            while not self.stopping.is_set():
                try:
                    r = await http.get(self.url + "/metrics", headers={"Authorization": f"Bearer {self.token}"})
                    if r.status_code in (401, 403, 404):
                        print(f"[load] /metrics answers {r.status_code}: no server samples "
                              "(start serve with --metrics and use an admin token)", file=sys.stderr)
                        return
                    if r.status_code == 200:
                        self.samples.append({"t": round(time.monotonic() - self.start, 3), **parse_metrics(r.text)})
                except httpx.HTTPError:
                    pass
                try:
                    await asyncio.wait_for(self.stopping.wait(), self.args.metrics_interval)
                except asyncio.TimeoutError:
                    pass

    def pick(self) -> str:
        return self.rng.choices(self.names, self.weights)[0]

    async def run(self) -> None:
        a = self.args
        cp, probe_cp = self._client(a.retries), self._client(0)
        self.start = time.monotonic()
        background = [asyncio.create_task(self.probe_loop(probe_cp)), asyncio.create_task(self.metrics_loop())]
        tasks: list[asyncio.Task] = []
        seq = 0
        try:
            if a.load_model == "open":
                for t in arrivals(a.rate, a.duration, self.rng):
                    await asyncio.sleep(max(0.0, self.start + t - time.monotonic()))
                    seq += 1
                    tasks.append(asyncio.create_task(self.one_job(cp, seq, self.pick(), t)))
            else:
                counter = iter(range(1, 10**9))

                async def client() -> None:
                    while time.monotonic() - self.start < a.duration:
                        await self.one_job(cp, next(counter), self.pick(), time.monotonic() - self.start)

                tasks = [asyncio.create_task(client()) for _ in range(a.clients)]
            _, pending = await asyncio.wait(tasks, timeout=a.drain) if tasks else (None, [])
            for task in pending:
                task.cancel()
        finally:
            self.stopping.set()
            await asyncio.gather(*background, return_exceptions=True)
            await cp.aclose()
            await probe_cp.aclose()
            shutil.rmtree(self.tmp, ignore_errors=True)


# ---------------------------------------------------------------------------------------------
# Output

def check_url(url: str, token: str) -> None:
    """The SDK's rule for every request here, /metrics included: a token goes over https, or plain
    http to loopback only."""
    from cimpal_client import _core
    try:
        _core.normalize_base_url(url, token=_core.resolve_token(token, None))
    except (ValueError, errors.CimPalError) as e:
        raise SystemExit(f"[load] {e}")


def write_csv(path: Path, rows: list, cls=None) -> None:
    names = [f.name for f in fields(cls)] if cls else sorted({k for r in rows for k in r})
    if cls is None and "t" in names:
        names.remove("t")
        names.insert(0, "t")
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=names)
        w.writeheader()
        for r in rows:
            w.writerow(asdict(r) if cls else r)


def write_results(out: Path, load: Load, meta: dict) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    write_csv(out / "jobs.csv", sorted(load.jobs, key=lambda j: j.seq), JobRecord)
    write_csv(out / "probes.csv", load.probes, Probe)
    write_csv(out / "metrics.csv", load.samples)
    with (out / "http.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["method", "route", "status", "count"])
        for key, n in sorted(load.http.items()):
            method, rest = key.split(" ", 1)
            path, status = rest.rsplit(" ", 1)
            w.writerow([method, path, status, n])
    summary = summarise(load.jobs, load.probes, load.samples, load.http, meta)
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (out / "report.md").write_text(render_report(summary), encoding="utf-8")
    return summary


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scenario", default="mixed", help="mixed, small, medium, sparql, upload, or name=weight,...")
    p.add_argument("--load-model", choices=["open", "closed"], default="open")
    p.add_argument("--rate", type=float, default=6.0, help="open: jobs per minute (default 6)")
    p.add_argument("--clients", type=int, default=2, help="closed: concurrent clients (default 2)")
    p.add_argument("--duration", type=float, default=600, help="seconds of arrivals (default 600)")
    p.add_argument("--drain", type=float, default=1800, help="seconds to wait for jobs after that (default 1800)")
    p.add_argument("--seed", type=int, default=1, help="arrivals and scenario choice")
    p.add_argument("--retries", type=int, default=3, help="SDK retries on 503/429 (default 3, as clients use)")
    p.add_argument("--job-timeout", type=float, default=7200, help="seconds a client waits for one job")
    p.add_argument("--probe-interval", type=float, default=1.0)
    p.add_argument("--metrics-interval", type=float, default=2.0)
    p.add_argument("--models-root", type=Path, default=REPO / "target" / "bench" / "models")
    p.add_argument("--model-seed", type=int, default=1)
    p.add_argument("--out", type=Path, default=None, help="results folder (default target/bench/load/<time>)")
    p.add_argument("--label", default=None, help="title of the report")
    p.add_argument("--fail-on-slo", action="store_true", help="exit 1 when an SLO is missed, too")
    srv = p.add_argument_group("a running server")
    srv.add_argument("--url")
    srv.add_argument("--token-file", type=Path, help="admin token (for /metrics); or CIMPAL_API_TOKEN")
    srv.add_argument("--server-models-root", help="--models-root as the server sees it, e.g. /data/models")
    srv.add_argument("--server-output-root", help="a folder the server may write job outputs to")
    loc = p.add_argument_group("a local server (--start-server)")
    loc.add_argument("--start-server", action="store_true")
    loc.add_argument("--jar", type=Path, default=REPO / "CimPal-CLI" / "target" / "CimPal-CLI.jar")
    loc.add_argument("--java")
    loc.add_argument("--workers", type=int, default=1)
    loc.add_argument("--queue-size", type=int, default=4)
    loc.add_argument("--heap", default="4g")
    loc.add_argument("--cores", type=int, default=0, help="first N logical CPUs (0: all)")
    loc.add_argument("--server-arg", action="append", default=[], help="extra serve option, repeatable")
    return p


def validate_args(a) -> None:
    if a.start_server == bool(a.url):
        raise SystemExit("[load] give either --url or --start-server")
    if a.duration <= 0 or a.drain < 0:
        raise SystemExit("[load] --duration must be positive and --drain not negative")
    if a.load_model == "closed" and a.clients < 1:
        raise SystemExit("[load] --clients must be at least 1")
    if a.url and not a.server_output_root and not a.server_models_root:
        print("[load] note: without --server-models-root the server must see --models-root at the same path",
              file=sys.stderr)


def run(argv: list[str] | None = None) -> dict:
    a = parser().parse_args(argv)
    validate_args(a)
    try:
        mix = parse_mix(a.scenario)
    except ValueError as e:
        raise SystemExit(f"[load] {e}")
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    out = a.out or REPO / "target" / "bench" / "load" / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    models = Models(a.models_root.resolve(), a.model_seed, a.server_models_root)
    models.prepare(SCENARIOS[n][0] for n, w in mix.items() if w > 0)

    server = None
    output_dir = out / "job-output"
    output_dir.mkdir(exist_ok=True)
    if a.start_server:
        server = LocalServer(a, models.root, output_dir.resolve(), out)
        server.start()
        url, token, output_root, server_desc = server.url, server.token, str(output_dir.resolve()), server.describe()
    else:
        url = a.url
        if a.token_file:
            token = a.token_file.read_text(encoding="utf-8").strip().splitlines()[0].split(":")[-1]
        else:
            token = os.environ.get("CIMPAL_API_TOKEN") or sys.exit("[load] --token-file or CIMPAL_API_TOKEN needed")
        output_root = a.server_output_root or str(output_dir.resolve())
        server_desc = f"`serve` at {url}"
        check_url(url, token)
    describe = (f"{a.rate:g} jobs/min for {a.duration:g} s" if a.load_model == "open"
                else f"{a.clients} clients for {a.duration:g} s")
    describe += ", mix " + ", ".join(f"{n}={w}" for n, w in mix.items())
    meta = {"label": a.label or f"{a.scenario}, {describe}", "started": started, "loadModel": a.load_model,
            "describe": describe, "server": server_desc, "hardware": run_bench.hardware(),
            "args": {k: (str(v) if isinstance(v, Path) else v) for k, v in vars(a).items() if k != "token_file"},
            "serverCommand": server.cmd if server else None}
    print(f"[load] {meta['label']} against {url}; results in {out}", file=sys.stderr)
    load = Load(a, url, token, models, output_root, mix)
    try:
        asyncio.run(load.run())
    finally:
        if server:
            server.stop()
        shutil.rmtree(output_dir, ignore_errors=True)
    summary = write_results(out, load, meta)
    print(render_report(summary), file=sys.stderr)
    return summary


def verdict(summary: dict, fail_on_slo: bool) -> list[str]:
    """Why the run failed: jobs that didn't succeed, and missed SLOs when asked."""
    problems = [f"{n} job(s) {status}" for status, n in sorted(summary["scenarios"]["all"]["outcomes"].items())
                if status != "succeeded"]
    if fail_on_slo:
        problems += [f"SLO {name} missed ({x['measured']:.2f} {x['unit']})" for name, x in summary["slo"].items()
                     if x["ok"] is False]
    return problems


def main(argv: list[str] | None = None) -> int:
    fail_on_slo = "--fail-on-slo" in (sys.argv[1:] if argv is None else argv)
    problems = verdict(run(argv), fail_on_slo)
    for p in problems:
        print(f"[load] FAIL: {p}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
