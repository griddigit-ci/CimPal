/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.util;

import eu.griddigit.cimpal.main.gui.GuiSmoke;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ModelFactory}'s file choosers behind the GuiDialogs seam (TEST-6 security review). A
 * multi-file dialog closed without a selection must look to the caller as it does in the
 * application, where {@code showOpenMultipleDialog} returns null: nothing selected, no exception.
 */
@GuiSmoke
@Tag("smoke")
class ModelFactoryDialogsSmokeTest {

    @Test
    void multipleSelectionClosedWithoutFilesSelectsNothing() {
        dialogs().answerOpen();

        List<File> selected = ModelFactory.fileChooserCustom(false, "RDF files", List.of("*.rdf"), "Select files");

        assertThat(selected).isNullOrEmpty();
    }

    @Test
    void singleSelectionClosedWithoutAFileSelectsNothing() {
        dialogs().answerOpen();

        List<File> selected = ModelFactory.fileChooserCustom(true, "RDF files", List.of("*.rdf"), "Select a file");

        assertThat(selected).isEmpty();
    }
}
