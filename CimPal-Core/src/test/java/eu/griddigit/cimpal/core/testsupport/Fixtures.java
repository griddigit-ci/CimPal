/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads test fixtures from the classpath location {@code fixtures/<feature>/<file>}, i.e.
 * {@code src/test/resources/fixtures/<feature>/} of the module running the test. Works whether
 * the resources are directories or packed in a jar, so fixtures are always read or copied, never
 * opened in place.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** Classpath resource name of a fixture. */
    public static String resourceName(String feature, String file) {
        return "fixtures/" + feature + "/" + file;
    }

    /** Opens a fixture; the caller closes the stream. */
    public static InputStream open(String feature, String file) {
        String name = resourceName(feature, file);
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
        if (in == null) {
            in = Fixtures.class.getClassLoader().getResourceAsStream(name);
        }
        if (in == null) {
            throw new IllegalArgumentException("Fixture not found on the test classpath: " + name
                    + " (expected under src/test/resources/" + name + ")");
        }
        return in;
    }

    public static byte[] bytes(String feature, String file) {
        try (InputStream in = open(feature, file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String read(String feature, String file) {
        return new String(bytes(feature, file), StandardCharsets.UTF_8);
    }

    /**
     * Copies a fixture into {@code targetDir} (typically a {@code @TempDir}), keeping any
     * sub-path in {@code file}, and returns the copy.
     */
    public static Path copy(String feature, String file, Path targetDir) {
        Path target = targetDir.resolve(file).normalize();
        if (!target.startsWith(targetDir.normalize())) {
            throw new IllegalArgumentException("Fixture path escapes the target directory: " + file);
        }
        try (InputStream in = open(feature, file)) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
