-- Copyright (c) 2020-2026 gridDigIt Kft.
-- Licensed under the EUPL-1.2-or-later.
-- SPDX-License-Identifier: EUPL-1.2+
--
-- The durable job store of cimpal serve (DEP-11). Applied once by JdbcMigrator; never edit an
-- applied file, add V2__... instead. Statements are separated by semicolons at line ends; no
-- semicolon may appear inside a statement. Plain SQL that PostgreSQL and H2 (PostgreSQL mode,
-- the unit tests) both accept.

CREATE TABLE cimpal_jobs (
    id UUID PRIMARY KEY,
    command VARCHAR(64) NOT NULL,
    label VARCHAR(200),
    owner VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    exit_code INTEGER,
    error TEXT,
    config TEXT NOT NULL,
    upload_id UUID,
    result TEXT,
    result_dropped BOOLEAN DEFAULT FALSE NOT NULL,
    retained_bytes BIGINT DEFAULT 0 NOT NULL,
    log_lines BIGINT DEFAULT 0 NOT NULL,
    node VARCHAR(255),
    lease_until TIMESTAMP WITH TIME ZONE
);

CREATE INDEX cimpal_jobs_status_created ON cimpal_jobs (status, created_at);

CREATE INDEX cimpal_jobs_finished ON cimpal_jobs (finished_at);

CREATE TABLE cimpal_job_log (
    job_id UUID NOT NULL,
    seq BIGINT NOT NULL,
    line TEXT NOT NULL,
    PRIMARY KEY (job_id, seq)
);

CREATE TABLE cimpal_uploads (
    id UUID PRIMARY KEY,
    owner VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total_bytes BIGINT NOT NULL,
    charged_bytes BIGINT NOT NULL,
    files TEXT NOT NULL
);
