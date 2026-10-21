/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * One PostgreSQL in a container for the JDBC job store's integration tests (DEP-11), started once
 * per JVM and shared; each test gets its own schema. Without Docker the tests are skipped, never
 * failed (CI runs them on Ubuntu).
 */
final class TestPostgres {

    /** The PostgreSQL major version the store is tested against. */
    static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> container;

    private TestPostgres() {
    }

    private static synchronized PostgreSQLContainer<?> container() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available: the PostgreSQL integration tests are skipped");
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE);
            container.start();
        }
        return container;
    }

    /** A JDBC URL whose default schema is a new, empty one. */
    static String newDatabase() {
        PostgreSQLContainer<?> pg = container();
        String schema = "t" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + schema); // a generated name, never input
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create a test schema", e);
        }
        return pg.getJdbcUrl() + (pg.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    static String user() {
        return container().getUsername();
    }

    static String password() {
        return container().getPassword();
    }
}
