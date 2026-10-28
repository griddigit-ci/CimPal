/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/**
 * A {@link java.util.prefs.Preferences} tree that lives only in memory. The GUI smoke tests put
 * one into {@code MainController.prefs}, so the remembered paths and settings the tabs read and
 * write never reach the user's real CimPal preferences (the registry on Windows).
 */
final class InMemoryPreferences extends AbstractPreferences {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, InMemoryPreferences> children = new HashMap<>();

    /** A new, empty root node. */
    static InMemoryPreferences newRoot() {
        return new InMemoryPreferences(null, "");
    }

    private InMemoryPreferences(InMemoryPreferences parent, String name) {
        super(parent, name);
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() {
        values.clear();
        children.clear();
    }

    @Override
    protected String[] keysSpi() {
        return values.keySet().toArray(String[]::new);
    }

    @Override
    protected String[] childrenNamesSpi() {
        return children.keySet().toArray(String[]::new);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        return children.computeIfAbsent(name, child -> new InMemoryPreferences(this, child));
    }

    @Override
    protected void syncSpi() {
        // Nothing to synchronise: memory is the store.
    }

    @Override
    protected void flushSpi() {
        // Nothing to flush: memory is the store.
    }
}
