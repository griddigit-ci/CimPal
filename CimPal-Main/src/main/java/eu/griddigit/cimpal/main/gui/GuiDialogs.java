/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place where CimPal shows a native file dialog or a modal alert.
 * <p>
 * Callers configure the {@link FileChooser}, {@link DirectoryChooser} or {@link Alert} as before
 * and hand it here instead of calling {@code showOpenDialog} / {@code showAndWait} themselves. In
 * the application this simply shows the dialog. The GUI smoke tests replace the {@link Backend},
 * so they can answer a file dialog with a fixture path and see an alert's text without a modal
 * window blocking the FX thread; that is the only reason the backend can be swapped.
 * <p>
 * File choosers go through {@link eu.griddigit.cimpal.main.util.ModelFactory#fileChooserCustom},
 * {@code folderChooserCustom} and {@code fileSaveCustom}, and info/warning/error dialogs through
 * {@link GUIhelper}, so most callers reach this class indirectly.
 */
public final class GuiDialogs {

    /** Shows the dialogs. The default shows them for real; tests install a scripted one. */
    public interface Backend {
        File open(FileChooser chooser, Window owner);

        List<File> openMultiple(FileChooser chooser, Window owner);

        File save(FileChooser chooser, Window owner);

        File directory(DirectoryChooser chooser, Window owner);

        Optional<ButtonType> alert(Alert alert);
    }

    /** Shows every dialog on screen, exactly as the callers did before this class existed. */
    public static final Backend SYSTEM = new Backend() {
        @Override
        public File open(FileChooser chooser, Window owner) {
            return chooser.showOpenDialog(owner);
        }

        @Override
        public List<File> openMultiple(FileChooser chooser, Window owner) {
            return chooser.showOpenMultipleDialog(owner);
        }

        @Override
        public File save(FileChooser chooser, Window owner) {
            return chooser.showSaveDialog(owner);
        }

        @Override
        public File directory(DirectoryChooser chooser, Window owner) {
            return chooser.showDialog(owner);
        }

        @Override
        public Optional<ButtonType> alert(Alert alert) {
            return alert.showAndWait();
        }
    };

    private static volatile Backend backend = SYSTEM;

    private GuiDialogs() {
    }

    /** A single file to open, or null when the dialog was cancelled. */
    public static File open(FileChooser chooser, Window owner) {
        return backend.open(chooser, owner);
    }

    /**
     * Files to open, or null when the dialog was cancelled (as {@link FileChooser#showOpenMultipleDialog}).
     * An empty selection is returned as null too, so every backend looks like the real dialog and
     * callers that only check for null never see an empty list.
     */
    public static List<File> openMultiple(FileChooser chooser, Window owner) {
        List<File> files = backend.openMultiple(chooser, owner);
        return files == null || files.isEmpty() ? null : files;
    }

    /** The file to save to, or null when the dialog was cancelled. */
    public static File save(FileChooser chooser, Window owner) {
        return backend.save(chooser, owner);
    }

    /** The chosen folder, or null when the dialog was cancelled. */
    public static File directory(DirectoryChooser chooser, Window owner) {
        return backend.directory(chooser, owner);
    }

    /** Shows {@code alert} and waits for it to be closed; the button that closed it, if any. */
    public static Optional<ButtonType> alert(Alert alert) {
        return backend.alert(alert);
    }

    /** Shows a confirmation and returns true only when it was closed with OK. */
    public static boolean confirm(Alert alert) {
        return alert(alert).orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Replaces the backend. For the GUI smoke tests only (through {@code FxTestSupport}, in this
     * package): package-private, so no other code can install a backend that, say, answers every
     * {@link #confirm} with OK. A test must hand back {@link #SYSTEM} with {@link #resetBackend()}.
     */
    static void useBackendForTesting(Backend testBackend) {
        backend = Objects.requireNonNull(testBackend, "testBackend");
    }

    /** Restores the backend that shows dialogs on screen. Package-private, as is the install. */
    static void resetBackend() {
        backend = SYSTEM;
    }

    /** The backend in use; for tests, which check that it is {@link #SYSTEM} again after a reset. */
    static Backend currentBackend() {
        return backend;
    }
}
