/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.InstantSource;
import java.util.UUID;

/**
 * The job stores the {@code /v1} API tests run against (DEP-11): the in-memory default, and the
 * JDBC store on H2 in PostgreSQL mode (a fresh in-memory database per server). A real PostgreSQL
 * is {@code JdbcJobStorePostgresIT}'s part.
 */
enum TestStores {
    MEMORY,
    H2;

    /** A fresh H2 database in PostgreSQL mode, kept until the JVM ends. */
    static String h2Url() {
        return "jdbc:h2:mem:cimpal-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    }

    static JdbcJobStore h2(ServeServer.Config config, InstantSource clock) {
        return h2(h2Url(), config, clock);
    }

    static JdbcJobStore h2(String url, ServeServer.Config config, InstantSource clock) {
        return new JdbcJobStore(new JdbcPool(url, "sa", "", 4), config.maxJobs(), config.jobTtl(),
                config.jobTimeout(), config.jobLogLines(), clock);
    }

    /**
     * {@code builder} with this store; for H2 a new database whose limits come from the builder.
     * The caller closes {@link ServeServer.Config#jobStore()} when the test ends (null for memory).
     */
    ServeServer.Config.Builder apply(ServeServer.Config.Builder builder, InstantSource clock) {
        if (this == H2) {
            builder.jobStore(h2(builder.build(), clock));
        }
        return builder;
    }
}
