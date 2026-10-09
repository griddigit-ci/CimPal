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
        metrics.jobFinished(JobStatus.SUCCEEDED, 0.5);
        metrics.jobFinished(JobStatus.SUCCEEDED, 45);
        metrics.jobFinished(JobStatus.FAILED, 9000);
        metrics.jobFinished(JobStatus.CANCELLED, -1);

        String text = metrics.render("CimPal CLI 1", Map.of(JobStatus.SUCCEEDED, 2L), 3, true);

        assertThat(text).contains(
                "cimpal_job_duration_seconds_bucket{le=\"1\"} 1\n",
                "cimpal_job_duration_seconds_bucket{le=\"10\"} 1\n",
                "cimpal_job_duration_seconds_bucket{le=\"60\"} 2\n",
                "cimpal_job_duration_seconds_bucket{le=\"7200\"} 2\n",
                "cimpal_job_duration_seconds_bucket{le=\"+Inf\"} 3\n",
                "cimpal_job_duration_seconds_count 3\n",
                "cimpal_job_duration_seconds_sum 9045.5\n",
                "cimpal_jobs_finished_total{status=\"cancelled\"} 1\n",
                "cimpal_jobs_finished_total{status=\"failed\"} 1\n",
                "cimpal_jobs{status=\"succeeded\"} 2\n",
                "cimpal_jobs{status=\"queued\"} 0\n",
                "cimpal_queue_depth 3\n",
                "cimpal_busy 1\n",
                "# TYPE cimpal_job_duration_seconds histogram\n");
    }

    @Test
    void labelValuesAreEscaped() {
        assertThat(ServeMetrics.escape("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\nd");
        assertThat(new ServeMetrics().render("v\"1", Map.of(), 0, false)).contains("cimpal_info{version=\"v\\\"1\"} 1\n");
    }
}
