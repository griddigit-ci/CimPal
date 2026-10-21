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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dialog seam itself (TEST-6 security review): only code in this package can swap the
 * backend, a reset really restores the on-screen backend, and {@link GuiDialogs#confirm} fails
 * closed. No dialog is shown and no JavaFX toolkit is needed: {@code confirm} never touches the
 * alert it is given except through the backend.
 */
class GuiDialogsTest {

    @AfterEach
    void restoreSystemBackend() {
        GuiDialogs.resetBackend();
    }

    @Test
    void onlyThisPackageCanSwapTheBackend() throws Exception {
        int install = GuiDialogs.class.getDeclaredMethod("useBackendForTesting", GuiDialogs.Backend.class).getModifiers();
        int reset = GuiDialogs.class.getDeclaredMethod("resetBackend").getModifiers();

        assertThat(Modifier.isPublic(install)).as("useBackendForTesting is public").isFalse();
        assertThat(Modifier.isPublic(reset)).as("resetBackend is public").isFalse();
    }

    @Test
    void resetRestoresTheSystemBackend() {
        GuiDialogs.useBackendForTesting(answering(Optional.of(ButtonType.OK)));
        assertThat(GuiDialogs.currentBackend()).isNotSameAs(GuiDialogs.SYSTEM);

        GuiDialogs.resetBackend();

        assertThat(GuiDialogs.currentBackend()).isSameAs(GuiDialogs.SYSTEM);
    }

    @Test
    void confirmIsFalseWhenTheDialogIsClosedWithoutAButton() {
        GuiDialogs.useBackendForTesting(answering(Optional.empty()));

        assertThat(GuiDialogs.confirm(null)).isFalse();
    }

    @Test
    void confirmIsFalseForCancel() {
        GuiDialogs.useBackendForTesting(answering(Optional.of(ButtonType.CANCEL)));

        assertThat(GuiDialogs.confirm(null)).isFalse();
    }

    @Test
    void confirmIsTrueOnlyForOk() {
        GuiDialogs.useBackendForTesting(answering(Optional.of(ButtonType.OK)));

        assertThat(GuiDialogs.confirm(null)).isTrue();
    }

    /** A backend whose every alert is closed with {@code answer}; file dialogs are cancelled. */
    private static GuiDialogs.Backend answering(Optional<ButtonType> answer) {
        return new GuiDialogs.Backend() {
            @Override
            public File open(FileChooser chooser, Window owner) {
                return null;
            }

            @Override
            public List<File> openMultiple(FileChooser chooser, Window owner) {
                return null;
            }

            @Override
            public File save(FileChooser chooser, Window owner) {
                return null;
            }

            @Override
            public File directory(DirectoryChooser chooser, Window owner) {
                return null;
            }

            @Override
            public Optional<ButtonType> alert(Alert alert) {
                return answer;
            }
        };
    }
}
