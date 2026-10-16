/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings the job store's schema up to date (DEP-11): the SQL files {@code db/V<n>__*.sql} in
 * {@link #MIGRATIONS}, each applied once, in order, in its own transaction, and recorded in
 * {@code cimpal_schema_version}. A database newer than this build (a version it doesn't know) is
 * refused, so an older replica never runs against a schema it can't read. No Flyway: a fixed list
 * of resources is all it takes.
 */
final class JdbcMigrator {

    /** Every migration, oldest first: version and resource. Append only. */
    static final List<String> MIGRATIONS = List.of("/db/V1__jobs.sql");

    private JdbcMigrator() {
    }

    /**
     * Applies what is missing.
     *
     * @return the schema version now
     * @throws SQLException          when a statement fails (that migration is rolled back)
     * @throws IllegalStateException when the database is newer than this build
     */
    static int migrate(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS cimpal_schema_version ("
                    + "version INTEGER PRIMARY KEY, applied_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        }
        int current = current(c);
        if (current > MIGRATIONS.size()) {
            throw new IllegalStateException("The job store's schema is version " + current + ", newer than this"
                    + " CimPal knows (" + MIGRATIONS.size() + "); upgrade CimPal");
        }
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            List<String> statements = statements(read(MIGRATIONS.get(v - 1)));
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cimpal_schema_version (version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, v);
                    ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
        return current(c);
    }

    private static int current(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM cimpal_schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String read(String resource) {
        try (InputStream in = JdbcMigrator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Migration " + resource + " missing from this build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration " + resource, e);
        }
    }

    /** The statements of a file: comment lines dropped, split at a semicolon that ends a line. */
    static List<String> statements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (t.endsWith(";")) {
                String stmt = current.toString().strip();
                out.add(stmt.substring(0, stmt.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        return out;
    }
}
