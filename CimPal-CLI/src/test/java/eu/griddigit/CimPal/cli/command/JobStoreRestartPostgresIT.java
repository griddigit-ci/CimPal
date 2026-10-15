/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

/**
 * {@link JobStoreRestartTest} on a real PostgreSQL (DEP-11): a server stops while a job runs and
 * two wait, another starts on the same database and work root. Needs Docker; skipped without it.
 */
class JobStoreRestartPostgresIT extends JobStoreRestartTest {

    @Override
    String newDatabase() {
        return TestPostgres.newDatabase();
    }

    @Override
    JdbcJobStore open(String url) {
        ServeServer.Config c = ServeServer.Config.builder().token(TOKEN).build();
        return new JdbcJobStore(new JdbcPool(url, TestPostgres.user(), TestPostgres.password(), 4), c.maxJobs(),
                c.jobTtl(), c.jobTimeout(), c.jobLogLines(), clock);
    }
}
