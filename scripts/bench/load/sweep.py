# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal service capacity experiments (DEP-16): runs run.py against local servers and writes one
report for the sizing guide.

Experiments (--experiments, comma separated; default all):
  limit      per scenario (small, medium), --workers 1, closed load with 2 clients: the throughput
             limit of one worker and its run time
  workers    per --core-counts and --worker-counts, closed load with 2 clients per worker: how
             throughput and heap grow with workers, and where admission control
             (--max-concurrent-heap-bytes) holds them back
  ramp       medium, --workers 1, open load at 50/80/100/120 % of the measured limit: queue wait
             against utilisation, and the rate at which it grows without bound
  overload   medium, --workers 1, --queue-size 4, open load at 3x the limit for --overload-seconds,
             then 1x: 503 + Retry-After while full, and whether clients (SDK retries) recover

API latency (GET /v1/health and /v1/jobs/{id}) is probed in every run.

Results: <out>/<experiment>/<cell>/ (run.py's files), <out>/service.json and <out>/service.md;
--publish NAME also copies them to docs/guide/sizing-data/NAME.{json,md}.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import run as load  # noqa: E402

REPO = load.REPO
ALL = ("limit", "workers", "ramp", "overload")


def cell(out: Path, name: str, args: list[str], common: list[str]) -> dict:
    target = out / name
    if (target / "summary.json").is_file():  # resume a sweep that was interrupted
        print(f"[sweep] {name}: kept from an earlier run", file=sys.stderr)
        return json.loads((target / "summary.json").read_text(encoding="utf-8"))
    print(f"[sweep] {name}", file=sys.stderr)
    return load.run(["--start-server", "--out", str(target), "--label", name, *common, *args])


def per_hour(summary: dict, scenario: str = "all") -> float | None:
    return summary["scenarios"].get(scenario, {}).get("completedPerHour")


def run_s(summary: dict, scenario: str = "all", p: str = "p50") -> float | None:
    return summary["scenarios"].get(scenario, {}).get("runS", {}).get(p)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--experiments", default=",".join(ALL))
    p.add_argument("--core-counts", default="4,8")
    p.add_argument("--worker-counts", default="1,2,4,8")
    p.add_argument("--duration", type=float, default=240, help="seconds per closed-load cell (default 240)")
    p.add_argument("--ramp-duration", type=float, default=600, help="seconds per ramp step (default 600)")
    p.add_argument("--overload-seconds", type=float, default=120)
    p.add_argument("--heap", default="8g")
    p.add_argument("--quick", action="store_true", help="30 s cells and 4 and 2 cores: checks the set-up")
    p.add_argument("--out", type=Path, default=None)
    p.add_argument("--publish", default=None, help="name under docs/guide/sizing-data/, e.g. 2026-10-20-laptop-i7-1360P-service")
    p.add_argument("--jar", type=Path, default=REPO / "CimPal-CLI" / "target" / "CimPal-CLI.jar")
    a = p.parse_args(argv)
    if a.publish and not re.fullmatch(r"[A-Za-z0-9._-]+", a.publish) or a.publish in (".", ".."):
        p.error("--publish takes a plain file name, e.g. 2026-10-20-laptop-i7-1360P-service")
    experiments = [e.strip() for e in a.experiments.split(",") if e.strip()]
    if unknown := set(experiments) - set(ALL):
        p.error(f"unknown experiments: {', '.join(sorted(unknown))}")
    if a.quick:
        a.duration, a.ramp_duration, a.overload_seconds = 30, 30, 20
        a.core_counts, a.worker_counts = "2,4", "1,2"
    out = a.out or REPO / "target" / "bench" / "load" / ("sweep-" + time.strftime("%Y%m%d-%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    common = ["--jar", str(a.jar), "--heap", a.heap, "--drain", "1800"]
    closed = ["--load-model", "closed", "--duration", str(a.duration)]
    results: dict = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "hardware": load.run_bench.hardware(),
                     "heap": a.heap, "quick": a.quick, "cells": {}}

    def keep(name: str, summary: dict) -> dict:
        results["cells"][name] = summary
        (out / "service.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
        return summary

    limits: dict[str, float] = {}
    if "limit" in experiments or "ramp" in experiments or "overload" in experiments:
        for scenario in ("small", "medium"):
            s = keep(f"limit/{scenario}", cell(out, f"limit/{scenario}",
                                               ["--scenario", scenario, "--workers", "1", "--clients", "2", *closed],
                                               common))
            limits[scenario] = per_hour(s) or 0
    if "workers" in experiments:
        for cores in [int(c) for c in a.core_counts.split(",")]:
            for workers in [int(w) for w in a.worker_counts.split(",")]:
                for scenario in ("small", "medium"):
                    name = f"workers/{scenario}-c{cores}-w{workers}"
                    keep(name, cell(out, name, ["--scenario", scenario, "--workers", str(workers), "--cores", str(cores),
                                                "--queue-size", str(2 * workers), "--clients", str(2 * workers),
                                                *closed], common))
    if "ramp" in experiments and limits.get("medium"):
        for pct in (50, 80, 100, 120):
            rate = limits["medium"] / 60 * pct / 100
            name = f"ramp/medium-{pct}pct"
            keep(name, cell(out, name, ["--scenario", "medium", "--workers", "1", "--queue-size", "64",
                                        "--rate", f"{rate:.3f}", "--duration", str(a.ramp_duration)], common))
    if "overload" in experiments and limits.get("medium"):
        rate = limits["medium"] / 60 * 3
        name = "overload/medium-3x"
        keep(name, cell(out, name, ["--scenario", "medium", "--workers", "1", "--queue-size", "4",
                                    "--rate", f"{rate:.3f}", "--duration", str(a.overload_seconds)], common))
        name = "overload/medium-after"
        keep(name, cell(out, name, ["--scenario", "medium", "--workers", "1", "--queue-size", "4",
                                    "--rate", f"{limits['medium'] / 60 * 0.5:.3f}",
                                    "--duration", str(a.overload_seconds)], common))

    md = render(results)
    (out / "service.md").write_text(md, encoding="utf-8")
    if a.publish:
        data = REPO / "docs" / "guide" / "sizing-data"
        shutil.copy2(out / "service.json", data / f"{a.publish}.json")
        (data / f"{a.publish}.md").write_text(md, encoding="utf-8")
        print(f"[sweep] published docs/guide/sizing-data/{a.publish}.md", file=sys.stderr)
    print(md)
    return 0


def _f(v, digits=1, scale=1.0) -> str:
    return load._f(v, digits, scale)


def render(results: dict) -> str:
    hw = results["hardware"]
    cells = results["cells"]
    lines = ["<!--", "  Copyright (c) 2020-2026 gridDigIt Kft.", "  Licensed under the EUPL-1.2-or-later.",
             "  SPDX-License-Identifier: EUPL-1.2+", "-->", "",
             "# Service capacity measurements (DEP-16)", "",
             f"- **When:** {results['started']}" + (" (quick set-up check, not for sizing)" if results["quick"] else ""),
             f"- **Machine:** {hw.get('cpu')}, {hw.get('logicalCpus')} logical CPUs, "
             f"{_f(hw.get('ramBytes'), 0, 2**30)} GiB RAM, {hw.get('os')}",
             f"- **Server:** `serve` from this tree's `CimPal-CLI.jar`, -Xmx{results['heap']}, in-memory job store, "
             "shared-folder jobs (the upload scenario in run.py measures uploads separately)",
             "- **Models:** `scripts/bench/gen_models.py`, small = 100k triples, medium = 1M triples (seed 1)",
             "- **Cores:** the first N logical CPUs (affinity) and -XX:ActiveProcessorCount=N", "",
             "Produced by `python scripts/bench/load/sweep.py`; each cell's own files are in its output folder.", ""]
    lim = {k.split("/")[1]: v for k, v in cells.items() if k.startswith("limit/")}
    if lim:
        lines += ["## Throughput limit of one worker", "",
                  "| Model | Jobs/h | Run p50 s | Run p95 s | Peak heap MiB | GET p95 ms |", "|---|---:|---:|---:|---:|---:|"]
        for name, s in lim.items():
            lines.append(f"| {name} | {_f(per_hour(s), 0)} | {_f(run_s(s))} | {_f(run_s(s, p='p95'))} | "
                         f"{_f(s['server']['peakHeapUsedBytes'], 0, 2**20)} | {_f(s['slo']['get_p95_ms']['measured'])} |")
        lines.append("")
    work = {k.split("/")[1]: v for k, v in cells.items() if k.startswith("workers/")}
    if work:
        lines += ["## Workers and cores", "",
                  "Closed load, two clients per worker. *Running* is the mean number of commands running at once: "
                  "below the workers when admission control (`--max-concurrent-heap-bytes`) or the clients hold them back.", "",
                  "| Model | Cores | Workers | Jobs/h | Run p50 s | Running | Peak heap MiB | GET p95 ms |",
                  "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for name, s in work.items():
            model, c, w = name.split("-")
            lines.append(f"| {model} | {c[1:]} | {w[1:]} | {_f(per_hour(s), 0)} | {_f(run_s(s))} | "
                         f"{_f(s['server']['meanRunning'], 2)} | {_f(s['server']['peakHeapUsedBytes'], 0, 2**20)} | "
                         f"{_f(s['slo']['get_p95_ms']['measured'])} |")
        lines.append("")
    ramp = {k.split("/")[1]: v for k, v in cells.items() if k.startswith("ramp/")}
    if ramp:
        lines += ["## Queue wait against load (medium, one worker)", "",
                  "| Load (of the limit) | Arrived | Wait p50 s | Wait p95 s | Wait growing | Submit p95 ms | GET p95 ms |",
                  "|---|---:|---:|---:|---|---:|---:|"]
        for name, s in ramp.items():
            a = s["scenarios"]["all"]
            lines.append(f"| {name.split('-')[1].replace('pct', ' %')} | {a['arrived']} | {_f(a['queueWaitS']['p50'])} | "
                         f"{_f(a['queueWaitS']['p95'])} | {s['queueWaitGrowing']} | {_f(a['submitMs']['p95'], 0)} | "
                         f"{_f(s['slo']['get_p95_ms']['measured'])} |")
        lines.append("")
    over = {k.split("/")[1]: v for k, v in cells.items() if k.startswith("overload/")}
    if over:
        lines += ["## Overload (medium, one worker, queue of 4)", "",
                  "| Phase | Arrived | Outcomes | 503 answers | GET p95 ms | Availability % |", "|---|---:|---|---:|---:|---:|"]
        for name, s in over.items():
            a = s["scenarios"]["all"]
            outcomes = ", ".join(f"{k} {v}" for k, v in sorted(a["outcomes"].items()))
            lines.append(f"| {name.split('-')[1]} | {a['arrived']} | {outcomes} | "
                         f"{s['httpRefusedOrFailed'].get('503', 0)} | {_f(s['slo']['get_p95_ms']['measured'])} | "
                         f"{_f(s['slo']['availability']['measured'], 2)} |")
        lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(main())
