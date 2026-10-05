/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The CLI version, taken from the Maven project version at build time.
 *
 * <p>{@code cimpal-cli-version.properties} is the only filtered resource of CimPal-CLI, so
 * {@code --version}, the {@code serve} {@code /health} endpoint and the {@code mcp}
 * {@code serverInfo} always report the version of the release tag, which also names the
 * Docker image. A build that skipped resource filtering reports {@value #UNKNOWN}.
 */
public final class CliVersion implements CommandLine.IVersionProvider {

    static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "cimpal-cli-version.properties";
    private static final String VERSION = load();

    /** The release version, e.g. {@code 2026.9.10.1}. */
    public static String version() {
        return VERSION;
    }

    /** The version line of {@code --version} and {@code /health}, e.g. {@code CimPal CLI 2026.9.10.1}. */
    public static String displayName() {
        return "CimPal CLI " + VERSION;
    }

    @Override
    public String[] getVersion() {
        return new String[] {displayName()};
    }

    private static String load() {
        try (InputStream in = CliVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            // An unfiltered copy still holds the Maven placeholder.
            return version.isEmpty() || version.contains("${") ? UNKNOWN : version;
        } catch (IOException ex) {
            return UNKNOWN;
        }
    }
}
