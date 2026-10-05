# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal benchmark runner (DEP-2): runs `validate --stats` over synthetic models and records
wall time, CPU, peak heap, GC and triples per run.

Each run is a cold JVM, as CLI and container users run CimPal. Modes (combinable):

  (default)         the matrix sizes x --heaps x --cores, --warmup + --runs each
  --find-min-heap   per size, bisect the smallest -Xmx that completes (exit 0 or 1, not 3)
                    with a GC share (gcMs / wallMs) under --max-gc-share
  --core-sweep      per size, run --cores at one heap (--heaps, or 2x the minimum found) and
                    report the core count beyond which wall time improves less than 10 %
  --verify          per size, one run with per-shape detail: triplesLoaded must equal the
                    generator's count and the SHACL results its expected violations
  --budget FILE     after the runs, check median wall time and peak heap per size against
                    {"1M": {"maxWallMs": 60000, "maxPeakHeapBytes": 2147483648}, ...};
                    exit 1 on a breach (for TEST-5's nightly job)

Launchers: --jar CimPal-CLI.jar (default), --classpath CP (main class
eu.griddigit.CimPal.cli.CimPalCli, for a development tree), or --docker IMAGE, which limits the
container with --memory/--cpus instead of -Xmx/-XX:ActiveProcessorCount and also records the
cgroup's memory.peak. --docker mode is not exercised by the repository's tests.

-XX:ActiveProcessorCount is the number of cores the JVM sizes its thread pools (and CimPal its
workers) for; it is not a CPU quota, so JIT and GC threads may still use more. Only --docker
(--cpus) enforces one.

Results: <out>/results.csv (one row per run, with the hardware) and <out>/summary.md.
Standard library only (Python 3.10+). See scripts/bench/README.md.
"""
from __future__ import annotations

import argparse
import csv
import ctypes
import json
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_models  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
MAIN_CLASS = "eu.griddigit.CimPal.cli.CimPalCli"
XML_BASE = "http://example.com/data"
STATS_FIELDS = ["wallMs", "cpuMs", "peakHeapBytes", "maxHeapBytes", "peakRssBytes", "gcMs",
                "availableProcessors", "inputBytes", "triplesLoaded", "javaVersion", "os"]


@dataclass
class RunResult:
    size: str
    heap: str
    cores: str
    kind: str          # warmup, run, probe, verify
    exitCode: int
    status: str        # ok, violations, oom, error, timeout
    violations: int | None
    cgroupPeakBytes: int | None
    stats: dict
    seconds: float

    def gc_share(self) -> float | None:
        wall, gc = self.stats.get("wallMs"), self.stats.get("gcMs")
        return gc / wall if wall and gc is not None else None

    def completed(self) -> bool:
        return self.status in ("ok", "violations")


# ---------------------------------------------------------------------------------------------
# Hardware

def hardware() -> dict:
    info = {"os": platform.platform(), "machine": platform.machine(), "logicalCpus": os.cpu_count(),
            "cpu": platform.processor() or "", "ramBytes": None, "python": platform.python_version()}
    if sys.platform.startswith("linux"):
        try:
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.startswith("model name"):
                    info["cpu"] = line.split(":", 1)[1].strip()
                    break
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    info["ramBytes"] = int(line.split()[1]) * 1024
        except OSError:
            pass
    elif sys.platform == "win32":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
        status = MemoryStatus()
        status.dwLength = ctypes.sizeof(MemoryStatus)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            info["ramBytes"] = status.ullTotalPhys
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                                r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                info["cpu"] = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
        except OSError:
            pass
    elif sys.platform == "darwin":
        try:
            info["cpu"] = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"],
                                         capture_output=True, text=True).stdout.strip()
            info["ramBytes"] = int(subprocess.run(["sysctl", "-n", "hw.memsize"],
                                                  capture_output=True, text=True).stdout.strip())
        except (OSError, ValueError):
            pass
    return info


# ---------------------------------------------------------------------------------------------
# Sizes and models

def heap_mb(text: str) -> int:
    t = text.strip().lower()
    if t.endswith("g"):
        return int(float(t[:-1]) * 1024)
    if t.endswith("m"):
        return int(float(t[:-1]))
    return int(t)


def ensure_models(root: Path, size: str, seed: int) -> tuple[Path, dict]:
    """Generates the model set for `size` unless an identical one is already there."""
    target = gen_models.parse_count(size)
    out = root / f"{size}-seed{seed}"
    manifest_path = out / "manifest.json"
    if manifest_path.is_file():
        m = json.loads(manifest_path.read_text(encoding="utf-8"))
        if (m.get("requestedTriples"), m.get("seed"), m.get("generatorVersion")) == \
                (target, seed, gen_models.GENERATOR_VERSION):
            return out, m
        shutil.rmtree(out)
    print(f"[bench] generating {size} triples (seed {seed}) in {out}", file=sys.stderr)
    return out, gen_models.generate(target, seed, out)


# ---------------------------------------------------------------------------------------------
# One run

class Launcher:
    def __init__(self, args):
        self.args = args
        self.java = args.java or shutil.which("java") or "java"

    def command(self, models: Path, out_dir: Path, heap: str, cores: str, samples: int) -> list[str]:
        cli = ["validate", "--workflow", "mapping", "--format", "json", "--stats",
               "--samples", str(samples), "--xml-base", XML_BASE, "--workers", str(self.args.workers)]
        if self.args.docker:
            cli += ["--mapping-csv", "/data/mapping.csv", "--models", "/data/models",
                    "--constraints-root", "/data/constraints", "--output", "/out"]
            docker = ["docker", "run", "--rm", "--read-only", "--tmpfs", "/tmp",
                      "-v", f"{models}:/data:ro", "-v", f"{out_dir}:/out"]
            if heap:
                docker += ["--memory", f"{heap_mb(heap)}m"]
            if cores:
                docker += ["--cpus", cores]
            # Print the cgroup's peak memory after the CLI ends, keeping its exit code.
            script = ('/opt/cimpal/entrypoint.sh "$@"; rc=$?; '
                      'echo "[CGROUP] $(cat /sys/fs/cgroup/memory.peak 2>/dev/null)" >&2; exit $rc')
            return docker + ["--entrypoint", "sh", self.args.docker, "-c", script, "sh"] + cli
        jvm = [self.java]
        if heap:
            jvm.append(f"-Xmx{heap}")
        if cores:
            jvm.append(f"-XX:ActiveProcessorCount={cores}")
        jvm += ["-XX:+ExitOnOutOfMemoryError", "-XX:+DisplayVMOutputToStderr"]
        if self.args.classpath:
            jvm += ["-cp", self.args.classpath, MAIN_CLASS]
        else:
            jvm += ["-jar", str(self.args.jar)]
        return jvm + cli + ["--mapping-csv", str(models / "mapping.csv"), "--models", str(models / "models"),
                            "--constraints-root", str(models / "constraints"), "--output", str(out_dir)]


def run_once(launcher: Launcher, size: str, models: Path, heap: str, cores: str, kind: str,
             samples: int = 0) -> RunResult:
    out_dir = Path(tempfile.mkdtemp(prefix="cimpal-bench-"))
    started = time.monotonic()
    try:
        proc = subprocess.run(launcher.command(models, out_dir, heap, cores, samples),
                              capture_output=True, text=True, encoding="utf-8", errors="replace",
                              timeout=launcher.args.timeout)
        code, stdout, stderr = proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired:
        code, stdout, stderr = -1, "", "timeout"
    finally:
        seconds = time.monotonic() - started
        shutil.rmtree(out_dir, ignore_errors=True)
    stats, violations = {}, None
    try:
        result = json.loads(stdout)
        stats = result.get("stats", {})
        if samples:
            violations = sum(g.get("count", 0) for g in result.get("shapes", []))
    except json.JSONDecodeError:
        pass
    status = {0: "ok", 1: "violations", 3: "oom" if "OutOfMemoryError" in stderr or "Out of memory" in stderr
              else "error", -1: "timeout"}.get(code, "error")
    m = re.search(r"\[CGROUP\] (\d+)", stderr)
    r = RunResult(size, heap or "default", cores or "all", kind, code, status, violations,
                  int(m.group(1)) if m else None, stats, round(seconds, 2))
    if status == "error":
        tail = "\n".join(stderr.strip().splitlines()[-5:])
        print(f"[bench] {size} heap={r.heap} cores={r.cores}: exit {code}\n{tail}", file=sys.stderr)
    print(f"[bench] {kind:7} {size:>5} heap={r.heap:>7} cores={r.cores:>3} -> {status:10} "
          f"wall={stats.get('wallMs')} ms peakHeap={_mb(stats.get('peakHeapBytes'))} "
          f"gc={stats.get('gcMs')} ms", file=sys.stderr)
    return r


def _mb(v) -> str:
    return f"{v / 2**20:.0f} MB" if isinstance(v, (int, float)) else "-"


# ---------------------------------------------------------------------------------------------
# Modes

def run_cell(launcher, size, models, heap, cores, warmup, runs) -> list[RunResult]:
    results = [run_once(launcher, size, models, heap, cores, "warmup") for _ in range(warmup)]
    results += [run_once(launcher, size, models, heap, cores, "run") for _ in range(runs)]
    return results


def find_min_heap(launcher, size, models, cores, lo_mb, hi_mb, max_gc_share, results) -> int | None:
    """Smallest heap (MB) that completes with a GC share under the limit; None if hi fails."""
    def good(mb: int) -> bool:
        r = run_once(launcher, size, models, f"{mb}m", cores, "probe")
        results.append(r)
        share = r.gc_share()
        return r.completed() and share is not None and share < max_gc_share

    if not good(hi_mb):
        return None
    while hi_mb - lo_mb > max(32, lo_mb // 10):
        mid = (lo_mb + hi_mb) // 2
        if good(mid):
            hi_mb = mid
        else:
            lo_mb = mid
    return hi_mb


def knee(medians: list[tuple[int, float]]) -> int | None:
    """The core count beyond which the next step improves wall time by less than 10 %."""
    medians = sorted(medians)
    for (c1, w1), (_, w2) in zip(medians, medians[1:]):
        if w2 > w1 * 0.9:
            return c1
    return medians[-1][0] if medians else None


# ---------------------------------------------------------------------------------------------
# Output

def write_results(out: Path, rows: list[RunResult], hw: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    path = out / "results.csv"
    fields = ["size", "heap", "cores", "kind", "exitCode", "status", "violations", "cgroupPeakBytes",
              "seconds", "gcShare"] + STATS_FIELDS + ["phases", "hwCpu", "hwLogicalCpus", "hwRamBytes", "hwOs"]
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            row = {k: v for k, v in asdict(r).items() if k != "stats"}
            share = r.gc_share()
            row["gcShare"] = f"{share:.4f}" if share is not None else ""
            row.update({k: r.stats.get(k) for k in STATS_FIELDS})
            row["phases"] = json.dumps(r.stats.get("phases", {}), sort_keys=True)
            row.update(hwCpu=hw["cpu"], hwLogicalCpus=hw["logicalCpus"], hwRamBytes=hw["ramBytes"], hwOs=hw["os"])
            w.writerow(row)


def cell_summary(rows: list[RunResult]) -> list[dict]:
    cells: dict[tuple, list[RunResult]] = {}
    for r in rows:
        if r.kind == "run":
            cells.setdefault((r.size, r.heap, r.cores), []).append(r)
    out = []
    for (size, heap, cores), rs in cells.items():
        done = [r for r in rs if r.completed()]
        walls = [r.stats["wallMs"] for r in done if "wallMs" in r.stats]
        heaps = [r.stats["peakHeapBytes"] for r in done if "peakHeapBytes" in r.stats]
        cpus = [r.stats["cpuMs"] for r in done if r.stats.get("cpuMs") is not None]
        gcs = [r.gc_share() for r in done if r.gc_share() is not None]
        out.append({"size": size, "heap": heap, "cores": cores, "runs": len(rs), "completed": len(done),
                    "triples": done[0].stats.get("triplesLoaded") if done else None,
                    "medianWallMs": statistics.median(walls) if walls else None,
                    "maxWallMs": max(walls) if walls else None,
                    "medianPeakHeapBytes": statistics.median(heaps) if heaps else None,
                    "maxPeakHeapBytes": max(heaps) if heaps else None,
                    "medianCpuMs": statistics.median(cpus) if cpus else None,
                    "medianGcShare": statistics.median(gcs) if gcs else None})
    return out


def write_summary(out: Path, hw: dict, cells: list[dict], min_heaps: dict, knees: dict, args) -> None:
    lines = ["# CimPal benchmark summary", "",
             f"- Hardware: {hw['cpu']}, {hw['logicalCpus']} logical CPUs, "
             f"{_mb(hw['ramBytes']) if hw['ramBytes'] else '?'} RAM, {hw['os']}",
             f"- Launcher: {'docker ' + args.docker if args.docker else ('classpath' if args.classpath else 'jar')}",
             f"- Warm-up runs {args.warmup}, measured runs {args.runs}, cold JVM per run", ""]
    if min_heaps:
        lines += ["## Smallest heap that completes (GC share < "
                  f"{args.max_gc_share:.0%})", "", "| Size | Triples | Min heap |", "|---|---|---|"]
        for size, (triples, mb) in min_heaps.items():
            lines.append(f"| {size} | {triples} | {f'{mb} MB' if mb else 'not within range'} |")
        lines.append("")
    if knees:
        lines += ["## Core sweep", "", "| Size | Cores beyond which wall time improves < 10 % |", "|---|---|"]
        lines += [f"| {size} | {k} |" for size, k in knees.items()]
        lines.append("")
    if cells:
        lines += ["## Runs (median / max)", "",
                  "| Size | Triples | Heap | Cores | Done | Wall ms (median / max) | Peak heap MB (median / max) "
                  "| CPU ms | GC share |", "|---|---|---|---|---|---|---|---|---|"]
        for c in cells:
            gc = f"{c['medianGcShare']:.1%}" if c["medianGcShare"] is not None else "-"
            lines.append(f"| {c['size']} | {c['triples']} | {c['heap']} | {c['cores']} | {c['completed']}/{c['runs']} "
                         f"| {c['medianWallMs']} / {c['maxWallMs']} "
                         f"| {_mb(c['medianPeakHeapBytes'])} / {_mb(c['maxPeakHeapBytes'])} "
                         f"| {c['medianCpuMs']} | {gc} |")
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def check_budget(budget_file: Path, cells: list[dict]) -> list[str]:
    budget = json.loads(budget_file.read_text(encoding="utf-8"))
    breaches = []
    for size, limits in budget.items():
        mine = [c for c in cells if c["size"] == size]
        if not mine:
            breaches.append(f"{size}: no measured runs")
        for c in mine:
            label = f"{size} heap={c['heap']} cores={c['cores']}"
            if c["completed"] < c["runs"]:
                breaches.append(f"{label}: {c['runs'] - c['completed']} run(s) did not complete")
            if "maxWallMs" in limits and (c["medianWallMs"] or 0) > limits["maxWallMs"]:
                breaches.append(f"{label}: median wall {c['medianWallMs']} ms > {limits['maxWallMs']}")
            if "maxPeakHeapBytes" in limits and (c["medianPeakHeapBytes"] or 0) > limits["maxPeakHeapBytes"]:
                breaches.append(f"{label}: median peak heap {c['medianPeakHeapBytes']} > {limits['maxPeakHeapBytes']}")
    return breaches


# ---------------------------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    launch = p.add_mutually_exclusive_group()
    launch.add_argument("--jar", type=Path, default=REPO / "CimPal-CLI" / "target" / "CimPal-CLI.jar")
    launch.add_argument("--classpath", help="run the CLI from this classpath instead of a JAR")
    launch.add_argument("--docker", metavar="IMAGE", help="run the CLI image; --heaps/--cores become --memory/--cpus")
    p.add_argument("--java", help="java executable (default: java on PATH)")
    p.add_argument("--sizes", default="100k", help="comma-separated triple counts, e.g. 100k,1M,5M")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--models-root", type=Path, default=REPO / "target" / "bench" / "models")
    p.add_argument("--out", type=Path, default=REPO / "target" / "bench" / "results")
    p.add_argument("--heaps", default="", help="comma-separated heaps (e.g. 1g,2g); empty: JVM default")
    p.add_argument("--cores", default="", help="comma-separated core counts; empty: all")
    p.add_argument("--workers", type=int, default=0, help="validate --workers (0 = auto)")
    p.add_argument("--warmup", type=int, default=1)
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("--timeout", type=int, default=3600, help="seconds per run")
    p.add_argument("--find-min-heap", action="store_true")
    p.add_argument("--heap-range", default="64m:12g", help="bisection range for --find-min-heap")
    p.add_argument("--max-gc-share", type=float, default=0.10)
    p.add_argument("--core-sweep", action="store_true")
    p.add_argument("--no-matrix", action="store_true", help="skip the default matrix")
    p.add_argument("--verify", action="store_true")
    p.add_argument("--budget", type=Path)
    a = p.parse_args(argv)

    if not a.docker and not a.classpath and not a.jar.is_file():
        p.error(f"no CLI JAR at {a.jar}; build it (mvn -B -pl CimPal-CLI -am package -DskipTests) "
                "or pass --classpath / --docker")
    launcher = Launcher(a)
    hw = hardware()
    sizes = [s.strip() for s in a.sizes.split(",") if s.strip()]
    heaps = [h.strip() for h in a.heaps.split(",") if h.strip()] or [""]
    cores = [c.strip() for c in a.cores.split(",") if c.strip()] or [""]
    rows: list[RunResult] = []
    min_heaps: dict[str, tuple[int, int | None]] = {}
    knees: dict[str, int | None] = {}
    failures: list[str] = []

    for size in sizes:
        models, manifest = ensure_models(a.models_root, size, a.seed)
        if a.verify:
            r = run_once(launcher, size, models, heaps[0], cores[0], "verify", samples=1_000_000)
            rows.append(r)
            if r.stats.get("triplesLoaded") != manifest["triples"]:
                failures.append(f"{size}: triplesLoaded {r.stats.get('triplesLoaded')} != {manifest['triples']}")
            if r.violations != manifest["expectedViolationsTotal"]:
                failures.append(f"{size}: {r.violations} SHACL results != {manifest['expectedViolationsTotal']} expected")
        if a.find_min_heap:
            lo, hi = (heap_mb(x) for x in a.heap_range.split(":"))
            # At all cores: more parallel workers can need more heap.
            mb = find_min_heap(launcher, size, models, "", lo, hi, a.max_gc_share, rows)
            min_heaps[size] = (manifest["triples"], mb)
        if a.core_sweep:
            sweep_heap = a.heaps.split(",")[0].strip() if a.heaps else (
                f"{2 * min_heaps[size][1]}m" if min_heaps.get(size, (0, None))[1] else "")
            sweep_cores = cores if cores != [""] else ["1", "2", "4", "8"]
            for c in sweep_cores:
                rows += run_cell(launcher, size, models, sweep_heap, c, a.warmup, a.runs)
            medians = [(int(c["cores"]), c["medianWallMs"]) for c in cell_summary(rows)
                       if c["size"] == size and c["heap"] == (sweep_heap or "default")
                       and c["cores"] in sweep_cores and c["medianWallMs"] is not None]
            knees[size] = knee(medians)
        if not a.no_matrix and not a.core_sweep:
            for h in heaps:
                for c in cores:
                    rows += run_cell(launcher, size, models, h, c, a.warmup, a.runs)
        write_results(a.out, rows, hw)

    cells = cell_summary(rows)
    write_results(a.out, rows, hw)
    write_summary(a.out, hw, cells, min_heaps, knees, a)
    print(f"[bench] results: {a.out / 'results.csv'}, summary: {a.out / 'summary.md'}", file=sys.stderr)
    if a.budget:
        failures += check_budget(a.budget, cells)
    for f in failures:
        print(f"[bench] FAIL {f}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
