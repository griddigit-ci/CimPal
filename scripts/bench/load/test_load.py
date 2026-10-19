# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""Self-test of the load tool's pure parts (DEP-16): python -m unittest discover -s scripts/bench/load"""
from __future__ import annotations

import random
import sys
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as load  # noqa: E402
import sweep  # noqa: E402


def job(seq, scenario="small", arrival=0.0, status="succeeded", wait=1.0, run=2.0, submit=10.0):
    ran = wait is not None and run is not None
    return load.JobRecord(seq, scenario, arrival, status=status, exitCode=0, submitMs=submit, queueWaitS=wait,
                          runS=run, e2eS=wait + run if ran else None, clientS=wait + run + 0.5 if ran else None,
                          jobId=f"id{seq}")


META = {"label": "t", "started": "now", "loadModel": "open", "describe": "x", "server": "s",
        "hardware": {"cpu": "c", "logicalCpus": 4, "ramBytes": 2**34, "os": "o"}}


class Helpers(unittest.TestCase):

    def test_percentile_interpolates_and_skips_none(self):
        self.assertIsNone(load.percentile([], 95))
        self.assertEqual(load.percentile([5], 99), 5)
        self.assertEqual(load.percentile([1, 2, 3, 4, None], 50), 2.5)
        self.assertAlmostEqual(load.percentile(range(1, 101), 95), 95.05)

    def test_mixes(self):
        self.assertEqual(load.parse_mix("mixed"), load.MIXES["mixed"])
        self.assertEqual(load.parse_mix("sparql"), {"sparql": 1})
        self.assertEqual(load.parse_mix("small=3,upload"), {"small": 3, "upload": 1})
        for bad in ("nope", "small=0", "small=-1"):
            with self.assertRaises(ValueError, msg=bad):
                load.parse_mix(bad)

    def test_arrivals_are_poisson_and_repeatable(self):
        a = load.arrivals(60, 3600, random.Random(1))
        self.assertEqual(a, load.arrivals(60, 3600, random.Random(1)))
        self.assertTrue(all(0 < t < 3600 for t in a))
        self.assertEqual(a, sorted(a))
        self.assertLess(abs(len(a) - 3600), 4 * 60)  # mean 3600, sd 60
        with self.assertRaises(ValueError):
            load.arrivals(0, 10, random.Random(1))

    def test_routes_hide_ids(self):
        self.assertEqual(load.route("/v1/jobs/0c6f3a52-1b7e-4a8e-9a65-2f1d7b1e0c11/files"), "/v1/jobs/{id}/files")
        self.assertEqual(load.route("/v1/jobs"), "/v1/jobs")

    def test_a_token_never_goes_over_plain_http_to_another_host(self):
        token = "t" * 40
        load.check_url("http://127.0.0.1:7474", token)
        load.check_url("https://cimpal.example.com", token)
        with self.assertRaises(SystemExit):
            load.check_url("http://cimpal.example.com:7474", token)

    def test_publish_takes_a_plain_name(self):
        for bad in ("../x", "a/b", ".."):
            with self.assertRaises(SystemExit, msg=bad):
                sweep.main(["--publish", bad, "--experiments", "limit"])

    def test_metrics_are_parsed(self):
        text = ("# HELP cimpal_queue_depth x\n# TYPE cimpal_queue_depth gauge\ncimpal_queue_depth 3\n"
                "cimpal_jobs{status=\"queued\"} 2\ncimpal_jvm_heap_used_bytes 1.5E8\n"
                "cimpal_http_responses_total{code=\"5xx\"} 4\n")
        self.assertEqual(load.parse_metrics(text),
                         {"cimpal_queue_depth": 3.0, "cimpal_jvm_heap_used_bytes": 1.5e8, "http_5xx": 4.0})


class Summary(unittest.TestCase):

    def test_slos_outcomes_and_refusals(self):
        jobs = [job(i, arrival=i) for i in range(1, 10)] + [job(10, status="refused_503", wait=None, run=None)]
        probes = [load.Probe(i, "GET /v1/health", 5 + i, True, 200) for i in range(199)]
        probes.append(load.Probe(200, "GET /v1/health", 0, False, None))
        http = Counter({"POST /v1/jobs 202": 9, "POST /v1/jobs 503": 4, "GET /v1/jobs/{id} 200": 30})
        s = load.summarise(jobs, probes, [{"t": 0, "cimpal_queue_depth": 2, "cimpal_running": 1}], http, META)

        self.assertEqual(s["scenarios"]["all"]["outcomes"], {"succeeded": 9, "refused_503": 1})
        self.assertEqual(s["httpRefusedOrFailed"], {"503": 4})
        self.assertEqual(s["slo"]["availability"]["measured"], 99.5)
        self.assertTrue(s["slo"]["availability"]["ok"])
        self.assertTrue(s["slo"]["queue_wait_p95_s"]["ok"])
        self.assertEqual(s["server"]["maxQueueDepth"], 2)
        self.assertIn("| all | 10 |", load.render_report(s))

    def test_a_growing_queue_wait_is_seen(self):
        jobs = [job(i, arrival=i, wait=i * 10.0) for i in range(1, 13)]
        self.assertTrue(load.summarise(jobs, [], [], Counter(), META)["queueWaitGrowing"])
        steady = [job(i, arrival=i, wait=1.0) for i in range(1, 13)]
        self.assertFalse(load.summarise(steady, [], [], Counter(), META)["queueWaitGrowing"])

    def test_a_missed_slo_is_marked(self):
        jobs = [job(1, wait=900.0)]
        s = load.summarise(jobs, [load.Probe(0, "GET /v1/health", 500, True, 200)], [], Counter(), META)
        self.assertFalse(s["slo"]["queue_wait_p95_s"]["ok"])
        self.assertFalse(s["slo"]["get_p95_ms"]["ok"])
        self.assertIn("**no**", load.render_report(s))

    def test_the_verdict_names_failed_jobs_and_missed_slos(self):
        s = load.summarise([job(1), job(2, status="failed"), job(3, wait=900.0)], [], [], Counter(), META)
        self.assertEqual(load.verdict(s, False), ["1 job(s) failed"])
        self.assertEqual(load.verdict(s, True), ["1 job(s) failed", "SLO queue_wait_p95_s missed (810.10 s)"])
        ok = load.summarise([job(1)], [load.Probe(0, "GET /v1/health", 5, True, 200)], [], Counter(), META)
        self.assertEqual(load.verdict(ok, True), [])

    def test_the_sweep_report_has_every_experiment(self):
        s = load.summarise([job(i, arrival=i) for i in range(1, 5)], [], [], Counter({"POST /v1/jobs 503": 2}), META)
        md = sweep.render({"started": "now", "hardware": META["hardware"], "heap": "8g", "quick": False, "cells": {
            "limit/small": s, "workers/small-c4-w2": s, "ramp/medium-80pct": s, "overload/medium-3x": s}})
        for heading in ("Throughput limit", "Workers and cores", "Queue wait against load", "Overload"):
            self.assertIn(heading, md)
        self.assertIn("| small | 4 | 2 |", md)
        self.assertIn("| 80 % |", md)
        self.assertIn("SPDX-License-Identifier", md)


if __name__ == "__main__":
    unittest.main()
