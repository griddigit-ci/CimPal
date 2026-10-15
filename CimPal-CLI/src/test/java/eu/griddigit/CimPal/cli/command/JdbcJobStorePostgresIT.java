/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

/**
 * {@link JdbcJobStoreTest} on a real PostgreSQL (DEP-11): the same transitions, the cancel/start
 * race, logs, eviction, restart recovery and migrations. Needs Docker; skipped without it.
 */
class JdbcJobStorePostgresIT extends JdbcJobStoreTest {

    @Override
    String newDatabase() {
        return TestPostgres.newDatabase();
    }

    @Override
    String user() {
        return TestPostgres.user();
    }

    @Override
    String password() {
        return TestPostgres.password();
    }
}
