/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code serve --workers} runs commands at once in one JVM (DEP-8), so a mutable static field in
 * Core or the CLI is state that concurrent commands share. Every non-final static field must be on
 * the reviewed list below, with the reason it is safe; a new one fails here until it is reviewed
 * (and the list in {@code docs/plans/deployment/DEP-8.md} is updated).
 *
 * <p>A mutable object behind a final static field (a cache map, a registry) is not caught here;
 * those were inventoried by hand for DEP-8 and are listed in DEP-8.md.
 */
class MutableStaticsTest {

    /** "class#field" and why concurrent commands can't interfere through it. */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("eu.griddigit.cimpal.core.utils.ValidationTools#DEBUG",
                    "Set only by the GUI (setValidationDebugEnabled) or -Dcimpal.validation.debug at start;"
                            + " no CLI command or serve request changes it."),
            Map.entry("eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes#modelsABPrefMap",
                    "Deprecated GUI path only: compare() uses a per-call prefix map (CALL_PREFIXES) and"
                            + " ignores this field.")
    );

    @Test
    void everyMutableStaticFieldOfCoreAndTheCliIsReviewed() throws Exception {
        TreeSet<String> found = new TreeSet<>();
        for (Path location : List.of(location(PathPolicy.class), location(ServeServer.class))) {
            for (String className : classNames(location)) {
                Class<?> type;
                try {
                    type = Class.forName(className, false, MutableStaticsTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError e) {
                    continue; // e.g. a class of an optional dependency that isn't on the test classpath
                }
                Field[] fields;
                try {
                    fields = type.getDeclaredFields();
                } catch (LinkageError e) {
                    continue;
                }
                for (Field field : fields) {
                    int m = field.getModifiers();
                    if (Modifier.isStatic(m) && !Modifier.isFinal(m) && !field.isSynthetic()) {
                        found.add(className + "#" + field.getName());
                    }
                }
            }
        }
        assertThat(found).as("non-final static fields that aren't reviewed for serve --workers")
                .isSubsetOf(REVIEWED.keySet());
        assertThat(REVIEWED.keySet()).as("reviewed fields that no longer exist (drop them from the list)")
                .isSubsetOf(found);
    }

    /**
     * A thread a job creates inherits its output binding and path policy ({@code CliStreams},
     * {@code PathPolicy}). Core's pools are created per call and shut down with it, so their
     * threads never outlive the job. A static executor, thread, timer or HTTP client would keep
     * threads created during one job and run another job's work on them, with the first job's
     * streams and policy: another client's log and files (DEP-13). None may exist unreviewed.
     */
    @Test
    void noStaticFieldHoldsThreadsThatOutliveAJob() throws Exception {
        List<Class<?>> threadOwners = List.of(java.util.concurrent.Executor.class, Thread.class,
                java.util.Timer.class, java.net.http.HttpClient.class, ThreadGroup.class);
        TreeSet<String> found = new TreeSet<>();
        for (Path location : List.of(location(PathPolicy.class), location(ServeServer.class))) {
            for (String className : classNames(location)) {
                Field[] fields;
                try {
                    fields = Class.forName(className, false, MutableStaticsTest.class.getClassLoader())
                            .getDeclaredFields();
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())
                            && threadOwners.stream().anyMatch(t -> t.isAssignableFrom(field.getType()))) {
                        found.add(className + "#" + field.getName());
                    }
                }
            }
        }
        assertThat(found).as("static fields holding threads shared across serve jobs").isEmpty();
    }

    private static Path location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<String> classNames(Path location) throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                files.filter(f -> f.toString().endsWith(".class"))
                        .map(f -> location.relativize(f).toString().replace('\\', '/'))
                        .forEach(n -> names.add(toClassName(n)));
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class"))
                        .forEach(n -> names.add(toClassName(n)));
            }
        }
        names.removeIf(n -> n.endsWith("module-info") || n.endsWith("package-info"));
        return names;
    }

    private static String toClassName(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }
}
