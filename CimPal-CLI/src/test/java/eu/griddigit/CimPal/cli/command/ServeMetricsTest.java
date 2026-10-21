/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Prometheus text of {@code GET /metrics} (DEP-6). */
class ServeMetricsTest {

    @Test
    void theHistogramIsCumulativeAndCancelledJobsHaveNoDuration() {
        ServeMetrics metrics = new ServeMetrics();
        metrics.jobFinished("default", JobStatus.SUCCEEDED, 0.5);
        metrics.jobFinished("default", JobStatus.SUCCEEDED, 45);
        metrics.jobFinished("default", JobStatus.FAILED, 9000);
        metrics.jobFinished("default", JobStatus.CANCELLED, -1);

        String text = metrics.render("CimPal CLI 1", Map.of(JobStatus.SUCCEEDED, 2L), 3, 2, 4);

        assertThat(text).contains(
                "cimpal_job_duration_seconds_bucket{le=\"1\"} 1\n",
                "cimpal_job_duration_seconds_bucket{le=\"10\"} 1\n",
                "cimpal_job_duration_seconds_bucket{le=\"60\"} 2\n",
                "cimpal_job_duration_seconds_bucket{le=\"7200\"} 2\n",
                "cimpal_job_duration_seconds_bucket{le=\"+Inf\"} 3\n",
                "cimpal_job_duration_seconds_count 3\n",
                "cimpal_job_duration_seconds_sum 9045.5\n",
                "cimpal_jobs_finished_total{client=\"default\",status=\"cancelled\"} 1\n",
                "cimpal_jobs_finished_total{client=\"default\",status=\"failed\"} 1\n",
                "cimpal_jobs{status=\"succeeded\"} 2\n",
                "cimpal_jobs{status=\"queued\"} 0\n",
                "cimpal_queue_depth 3\n",
                "cimpal_busy 1\n",
                "cimpal_running 2\n",
                "cimpal_workers 4\n",
                "# TYPE cimpal_job_duration_seconds histogram\n");
    }

    @Test
    void answersAreCountedByStatusClassWithBusyAndQuotaApart() {
        ServeMetrics metrics = new ServeMetrics();
        metrics.response("api", 200, 0.004);
        metrics.response("api", 202, 0.3);
        metrics.response("api", 500, 0.01);
        metrics.response("api", 503, 0.01);
        metrics.response("api", 429, 0.01);
        metrics.response("transfer", -1, 20);

        assertThat(metrics.render("v", Map.of(), 0, 0, 1)).contains(
                "cimpal_http_responses_total{code=\"2xx\"} 2\n",
                "cimpal_http_responses_total{code=\"5xx\"} 1\n",
                "cimpal_http_responses_total{code=\"503\"} 1\n",
                "cimpal_http_responses_total{code=\"429\"} 1\n",
                "cimpal_http_responses_total{code=\"none\"} 1\n",
                "cimpal_http_request_duration_seconds_bucket{kind=\"api\",le=\"0.005\"} 1\n",
                "cimpal_http_request_duration_seconds_bucket{kind=\"api\",le=\"0.2\"} 4\n",
                "cimpal_http_request_duration_seconds_bucket{kind=\"api\",le=\"+Inf\"} 5\n",
                "cimpal_http_request_duration_seconds_count{kind=\"api\"} 5\n",
                "cimpal_http_request_duration_seconds_bucket{kind=\"transfer\",le=\"10\"} 0\n",
                "cimpal_http_request_duration_seconds_count{kind=\"transfer\"} 1\n",
                "cimpal_http_request_duration_seconds_count{kind=\"command\"} 0\n");
    }

    @Test
    void queueWaitIsAHistogram() {
        ServeMetrics metrics = new ServeMetrics();
        metrics.jobStarted(0.2);
        metrics.jobStarted(400);
        metrics.jobStarted(-1); // clocks: never below 0

        assertThat(metrics.render("v", Map.of(), 0, 0, 1)).contains(
                "cimpal_job_queue_wait_seconds_bucket{le=\"1\"} 2\n",
                "cimpal_job_queue_wait_seconds_bucket{le=\"300\"} 2\n",
                "cimpal_job_queue_wait_seconds_bucket{le=\"600\"} 3\n",
                "cimpal_job_queue_wait_seconds_bucket{le=\"+Inf\"} 3\n",
                "cimpal_job_queue_wait_seconds_sum 400.2\n",
                "cimpal_job_queue_wait_seconds_count 3\n");
    }

    @Test
    void theCapacityGaugesLeaveOutAMissingWorkArea() {
        assertThat(ServeMetrics.capacity(4, true, 10, 100)).contains(
                "cimpal_queue_size 4\n", "cimpal_stalled 1\n", "cimpal_work_used_bytes 10\n", "cimpal_work_max_bytes 100\n");
        assertThat(ServeMetrics.capacity(4, false, 0, 0)).contains("cimpal_stalled 0\n").doesNotContain("cimpal_work_");
    }

    @Test
    void requestKinds() {
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc")).isEqualTo("api");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc/files")).isEqualTo("api");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc/files/out/report.ttl")).isEqualTo("transfer");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc/archive")).isEqualTo("transfer");
        assertThat(ServeServer.requestKind("POST", "/v1/uploads")).isEqualTo("transfer");
        assertThat(ServeServer.requestKind("GET", "/v1/uploads/abc")).isEqualTo("api");
        assertThat(ServeServer.requestKind("POST", "/validate")).isEqualTo("command");
        assertThat(ServeServer.requestKind("GET", "/health")).isEqualTo("api");
        assertThat(ServeServer.requestKind("GET", null)).isEqualTo("api");
        // Edges: an empty file name, a decoded newline, and a long path (one linear match, no backtracking).
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc/files/")).isEqualTo("api");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/abc/files/a\nb")).isEqualTo("transfer");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/" + "a".repeat(100_000) + "/archive")).isEqualTo("transfer");
        assertThat(ServeServer.requestKind("GET", "/v1/jobs/" + "a/".repeat(50_000))).isEqualTo("api");
    }

    @Test
    void labelValuesAreEscaped() {
        assertThat(ServeMetrics.escape("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\nd");
        assertThat(new ServeMetrics().render("v\"1", Map.of(), 0, 0, 1)).contains("cimpal_info{version=\"v\\\"1\"} 1\n");
    }
}
