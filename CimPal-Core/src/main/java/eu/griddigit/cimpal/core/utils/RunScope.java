/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one command run keeps for itself, so concurrent runs in one JVM ({@code serve
 * --workers}, DEP-8) don't share it: the in-memory caches of remote {@code owl:imports} and of
 * remote model files. Inherited by the threads the run starts, as {@link PathPolicy}'s context is.
 *
 * <p>Without a scope (the GUI, direct CLI use) {@link ValidationTools} keeps using its
 * process-wide caches, as before.
 */
public final class RunScope {

    private static final InheritableThreadLocal<RunScope> CURRENT = new InheritableThreadLocal<>();

    final Map<String, Model> remoteImports = new ConcurrentHashMap<>();
    final Map<String, Path> remoteXmlFiles = new ConcurrentHashMap<>();

    private RunScope() {
    }

    /** Runs {@code action} in a new, empty scope, then restores the previous one. */
    public static <T> T runIn(Callable<T> action) throws Exception {
        RunScope previous = CURRENT.get();
        CURRENT.set(new RunScope());
        try {
            return action.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The scope of the run on this thread, or null. */
    static RunScope current() {
        return CURRENT.get();
    }
}
