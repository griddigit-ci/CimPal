/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.gui;

import eu.griddigit.cimpal.main.application.MainController;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Per-field and per-dialog memory of the last path a user picked.
 *
 * <p>Before this class there was a single global {@code LastWorkingFolder} preference, which only
 * ever remembered the last folder touched anywhere in the application and was therefore wrong for
 * most fields most of the time. Here every tab field and every file dialog gets its own key, so a
 * dialog reopens where that dialog was last used.
 *
 * <p>Everything is stored in a dedicated {@code paths} child node so it can be wiped from
 * Preferences without disturbing the CIM settings that live alongside it.
 */
public final class PathMemory {

    /** Key naming convention: {@code tab.<camelCaseTab>.<field>} or {@code dialog.<site>.<purpose>}. */
    private static final String NODE_NAME = "paths";

    /** Suffix under which the containing folder of a remembered file is kept. */
    private static final String DIR_SUFFIX = ".dir";

    /** The pre-existing single-value memory, still read as a fallback and still written by ModelFactory. */
    private static final String LEGACY_KEY = "LastWorkingFolder";

    private PathMemory() {
    }

    /**
     * The backing store. Resolved lazily because {@code MainController.prefs} is assigned during
     * application start-up; a null return means "no memory available", and every method below
     * degrades to its unremembered behaviour rather than failing.
     */
    private static Preferences store() {
        Preferences root = MainController.prefs;
        if (root == null) {
            return null;
        }
        try {
            return root.node(NODE_NAME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Remembers {@code file} under {@code key}. A file's parent folder is stored too, so a dialog
     * for the same key can reopen in the containing folder even after the file itself is deleted.
     */
    public static void remember(String key, File file) {
        Preferences store = store();
        if (store == null || key == null || file == null) {
            return;
        }
        try {
            store.put(key, file.getAbsolutePath());
            File folder = file.isDirectory() ? file : file.getParentFile();
            if (folder != null) {
                store.put(key + DIR_SUFFIX, folder.getAbsolutePath());
            }
        } catch (RuntimeException e) {
            // A path too long for Preferences is not worth failing a file selection over.
        }
    }

    /** Forgets whatever was stored under {@code key}. Used when a Reset clears its field. */
    public static void forget(String key) {
        Preferences store = store();
        if (store == null || key == null) {
            return;
        }
        try {
            store.remove(key);
            store.remove(key + DIR_SUFFIX);
        } catch (RuntimeException e) {
            // Nothing actionable; the value simply stays.
        }
    }

    /** The full path last remembered under {@code key}, or null if there is none. */
    public static String recall(String key) {
        Preferences store = store();
        if (store == null || key == null) {
            return null;
        }
        String value = store.get(key, "");
        return value.isBlank() ? null : value;
    }

    /**
     * The folder a dialog for {@code key} should open in. Never returns a directory that does not
     * exist, so callers can hand the result straight to a chooser.
     *
     * <p>Three tiers, each validated: the folder stored for this key, then the legacy global
     * {@code LastWorkingFolder} so nobody loses the behaviour they have today, then the user's home
     * directory. For the first two, a path that no longer exists is walked up to its nearest
     * surviving ancestor - a user who just deleted a folder expects to land in its parent.
     */
    public static File resolveDirectory(String key) {
        Preferences store = store();
        if (store != null && key != null) {
            File remembered = nearestExistingDirectory(store.get(key + DIR_SUFFIX, ""));
            if (remembered != null) {
                return remembered;
            }
            remembered = nearestExistingDirectory(store.get(key, ""));
            if (remembered != null) {
                return remembered;
            }
        }

        if (MainController.prefs != null) {
            File legacy = nearestExistingDirectory(MainController.prefs.get(LEGACY_KEY, ""));
            if (legacy != null) {
                return legacy;
            }
        }

        // FileUtils rather than a literal "C:" so this still resolves on macOS and Linux.
        return FileUtils.getUserDirectory();
    }

    /**
     * Walks {@code path} up to the nearest existing directory, or returns null if none of its
     * ancestors exist either. A stored file path resolves to its containing folder.
     */
    private static File nearestExistingDirectory(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File candidate;
        try {
            candidate = new File(path).getAbsoluteFile();
        } catch (RuntimeException e) {
            return null;
        }
        while (candidate != null) {
            if (Files.isDirectory(candidate.toPath())) {
                return candidate;
            }
            candidate = candidate.getParentFile();
        }
        return null;
    }

    /** True when {@code key} still points at a file or folder that exists on disk. */
    public static boolean pathExists(String key) {
        String path = recall(key);
        return path != null && new File(path).exists();
    }

    /**
     * Sets a validated initial directory on {@code chooser}, or leaves it unset when no directory
     * can be found. Handing a FileChooser a non-existent File is what used to make dialogs fail.
     */
    public static void prepare(FileChooser chooser, String key) {
        if (chooser == null) {
            return;
        }
        File directory = resolveDirectory(key);
        if (directory != null && Files.isDirectory(directory.toPath())) {
            chooser.setInitialDirectory(directory);
        }
    }

    /** As {@link #prepare(FileChooser, String)}, for a folder dialog. */
    public static void prepare(DirectoryChooser chooser, String key) {
        if (chooser == null) {
            return;
        }
        File directory = resolveDirectory(key);
        if (directory != null && Files.isDirectory(directory.toPath())) {
            chooser.setInitialDirectory(directory);
        }
    }

    /** @see #bind(TextField, String, Consumer) */
    public static void bind(TextField field, String key) {
        bind(field, key, null);
    }

    /**
     * Restores {@code key} into {@code field} if the path still exists, then keeps the store in
     * sync with whatever the user does next.
     *
     * <p>{@code onRestore} exists because a browse handler usually does more than fill its field -
     * it also assigns a controller member, publishes it to a static, or enables a Run button.
     * Restoring by {@code setText} alone would show a path while leaving that state unset, which is
     * a half-state that fails on Run. Callers pass a lambda replicating their own post-selection
     * side effects; it runs only when the restored path still exists.
     *
     * <p>Write-back is a text listener, which is why no existing browse handler needed editing:
     * they all already end in {@code setText}. It also means a Reset that calls {@code clear()}
     * stores an empty value, so Reset forgets.
     */
    public static void bind(TextField field, String key, Consumer<File> onRestore) {
        if (field == null || key == null) {
            return;
        }

        String remembered = recall(key);
        if (remembered != null) {
            File file = new File(remembered);
            if (file.exists()) {
                field.setText(remembered);
                if (onRestore != null) {
                    onRestore.accept(file);
                }
            } else {
                // The path is gone. Leave the field empty rather than showing a dead path, but keep
                // the folder entry so this field's Browse dialog still opens somewhere sensible.
                Preferences store = store();
                if (store != null) {
                    store.remove(key);
                }
            }
        }

        field.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.isBlank()) {
                forget(key);
            } else if (new File(newValue).exists()) {
                remember(key, new File(newValue));
            }
            // Anything else is not a path: a few fields show a joined list of several files, and
            // some show a name the user is still typing. Keep the last known good value rather
            // than storing something that can never be restored.
        });
    }

    /** How many paths are currently remembered. Shown in Preferences, Remembered locations. */
    public static int count() {
        Preferences store = store();
        if (store == null) {
            return 0;
        }
        try {
            int total = 0;
            for (String key : store.keys()) {
                if (!key.endsWith(DIR_SUFFIX)) {
                    total++;
                }
            }
            return total;
        } catch (BackingStoreException | RuntimeException e) {
            return 0;
        }
    }

    /** Wipes every remembered path, including the legacy global folder. */
    public static void clearAll() {
        Preferences store = store();
        if (store != null) {
            try {
                store.clear();
                store.flush();
            } catch (BackingStoreException | RuntimeException e) {
                // Nothing actionable: the store is unavailable, so there is nothing to clear.
            }
        }
        if (MainController.prefs != null) {
            try {
                MainController.prefs.remove(LEGACY_KEY);
            } catch (RuntimeException e) {
                // As above.
            }
        }
    }
}
