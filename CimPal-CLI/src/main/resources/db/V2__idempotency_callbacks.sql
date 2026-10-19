-- Copyright (c) 2020-2026 gridDigIt Kft.
-- Licensed under the EUPL-1.2-or-later.
-- SPDX-License-Identifier: EUPL-1.2+
--
-- DEP-14: Idempotency-Keys of submits and uploads, and completion callbacks of jobs. See V1 for
-- the rules (never edit an applied file; plain SQL for PostgreSQL and H2).

CREATE TABLE cimpal_idempotency (
    client VARCHAR(64) NOT NULL,
    idem_key VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    done BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (client, idem_key)
);

CREATE INDEX cimpal_idempotency_created ON cimpal_idempotency (created_at);

CREATE TABLE cimpal_callbacks (
    job_id UUID PRIMARY KEY,
    client VARCHAR(64) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INTEGER DEFAULT 0 NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    last_status INTEGER,
    last_error VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX cimpal_callbacks_due ON cimpal_callbacks (state, next_attempt_at);
