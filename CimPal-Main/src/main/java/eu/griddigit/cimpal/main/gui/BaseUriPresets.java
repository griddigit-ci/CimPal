/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.gui;

import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The base URIs a CIM dataset is realistically based on, offered as a dropdown beside a base URI
 * field.
 * <p>
 * Picking a preset fills the field and makes it read-only; picking {@link #OTHER} makes it
 * editable. Locking matters because a mistyped base URI is not rejected anywhere downstream - it
 * silently yields a model whose subjects resolve nowhere - and these nine URIs cover almost every
 * real case.
 * <p>
 * Shared by every tab that takes a base URI, so the list and its spellings cannot drift apart
 * between them.
 */
public final class BaseUriPresets {

    /** The entry that unlocks the field instead of supplying a value. */
    public static final String OTHER = "Other";

    /** Sensible default for tabs that read a dataset: current CIM. */
    public static final String DEFAULT_SELECTION = "CIM 17";

    private static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("CIM 16", "http://iec.ch/TC57/2013/CIM-schema-cim16");
        PRESETS.put(DEFAULT_SELECTION, "http://iec.ch/TC57/CIM100");
        PRESETS.put("Stable CIM", "https://cim.ucaiug.io/ns");
        PRESETS.put("ENTSO-E extensions prior 2021", "http://entsoe.eu/CIM/SchemaExtension/3/1");
        PRESETS.put("EU extensions prior 2026", "http://iec.ch/TC57/CIM100-European");
        PRESETS.put("EU extensions 2026 on", "https://cim.ucaiug.io/ns/eu");
        PRESETS.put("EU NC extensions", "https://cim4.eu/ns/nc");
        PRESETS.put("DCAT", "http://www.w3.org/ns/dcat");
        PRESETS.put("DCTERMS", "http://purl.org/dc/terms");
        // Last, and deliberately without a value: see applyTo().
        PRESETS.put(OTHER, null);
    }

    private BaseUriPresets() {
    }

    /** Selection labels in the order they should be offered. */
    public static Collection<String> selections() {
        return PRESETS.keySet();
    }

    /** The URI a selection stands for, or {@code null} for {@link #OTHER} and unknown labels. */
    public static String uriFor(String selection) {
        return selection == null ? null : PRESETS.get(selection);
    }

    /** The URI of {@link #DEFAULT_SELECTION}, for callers that need a fallback. */
    public static String defaultUri() {
        return PRESETS.get(DEFAULT_SELECTION);
    }

    /**
     * Populates {@code selector}, selects {@code initialSelection}, and keeps {@code field} in
     * step with it from now on.
     *
     * @param initialSelection a label from {@link #selections()}. Pass {@link #OTHER} to leave the
     *                         field as the FXML set it - which is how a tab whose base URI is
     *                         optional keeps its blank default.
     */
    public static void bind(ChoiceBox<String> selector, TextField field, String initialSelection) {
        if (selector == null || field == null) {
            return;
        }
        selector.getItems().setAll(selections());
        selector.getSelectionModel().select(initialSelection);
        selector.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> applyTo(field, newValue));
        applyTo(field, initialSelection);
    }

    /**
     * Fills and locks the field for a preset, or unlocks it for {@link #OTHER}. Switching to
     * {@code OTHER} leaves the text alone rather than clearing it: the URI wanted is usually a
     * variant of the preset that was just showing.
     */
    private static void applyTo(TextField field, String selection) {
        String uri = uriFor(selection);
        if (uri == null) {
            field.setEditable(true);
        } else {
            field.setText(uri);
            field.setEditable(false);
        }
    }
}
