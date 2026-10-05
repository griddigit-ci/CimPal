/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The datatype maps bundled with CimPal, offered as a dropdown, plus {@link #OTHER} for a
 * {@code .properties} map of the user's own.
 * <p>
 * Shared by every tab that loads CIM models for validation, so the list, its labels and its
 * help text cannot drift apart between them. The labels are the presets' display names.
 */
public final class DatatypeMapPresets {

    /** The entry that asks for a map file instead of naming a bundled map. */
    public static final String OTHER = BaseUriPresets.OTHER;

    /** Sensible default: current CGMES. */
    public static final String DEFAULT_SELECTION = DatatypeMapPreset.CGMES30_NC25.displayName();

    /** In the order they are offered. */
    private static final List<DatatypeMapPreset> PRESETS = List.of(
            DatatypeMapPreset.CGMES30_NC25, DatatypeMapPreset.CGMES30_NC24, DatatypeMapPreset.CGMES24_NC22);

    /** What the dropdown does, for its "?" icon; a tab adds how it uses the map. */
    public static final String HELP =
            "Select the CGMES and NC version combination used to load the datatype mapping for validation. "
                    + "The map types the literals as the models are parsed, which is what lets a constraint on a "
                    + "numeric range or a boolean value fire at all.\n\n"
                    + "CGMES 3.0 / NC 2.5 uses the CIM17 / CGMES 3 / NC 2.5 datatype map.\n\n"
                    + "CGMES 3.0 / NC 2.4 uses the CIM17 / CGMES 3 / NC 2.4 datatype map.\n\n"
                    + "CGMES 2.4 / NC 2.2 uses the CIM16 / CGMES 2.4 / NC 2.2 datatype map.\n\n"
                    + "Other reveals a Browse button for a .properties datatype map of your own, in the same "
                    + "format as the bundled ones.";

    private DatatypeMapPresets() {
    }

    /** Selection labels in the order they should be offered, {@link #OTHER} last. */
    public static List<String> selections() {
        List<String> selections = new ArrayList<>();
        PRESETS.forEach(preset -> selections.add(preset.displayName()));
        selections.add(OTHER);
        return selections;
    }

    /** The bundled map a selection stands for, or {@code null} for {@link #OTHER} and unknown labels. */
    public static DatatypeMapPreset presetFor(String selection) {
        return PRESETS.stream().filter(preset -> preset.displayName().equals(selection)).findFirst().orElse(null);
    }

    /**
     * Populates {@code selector}, selects {@code initialSelection}, and from then on shows
     * {@code fileControls} - the map file's field and Browse button - only while {@link #OTHER} is
     * selected. Hidden controls are unmanaged too, so they give their space back.
     */
    public static void bind(ChoiceBox<String> selector, String initialSelection, Node... fileControls) {
        if (selector == null) {
            return;
        }
        selector.getItems().setAll(selections());
        selector.getSelectionModel().select(initialSelection);
        selector.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> show(OTHER.equals(newValue), fileControls));
        show(OTHER.equals(selector.getValue()), fileControls);
    }

    private static void show(boolean shown, Node... nodes) {
        for (Node node : nodes) {
            if (node != null) {
                node.setVisible(shown);
                node.setManaged(shown);
            }
        }
    }
}
