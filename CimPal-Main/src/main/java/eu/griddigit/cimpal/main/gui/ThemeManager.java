/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.gui;

import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.net.URL;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Applies the user's chosen colour theme to every window in the application.
 * <p>
 * Rather than registering each of the ~50 places that build a Scene, an Alert or a dialog,
 * this listens to {@link Window#getWindows()} and styles every window as it appears. That
 * covers the main stage, modal dialogs, alerts, tooltips and context menus, and keeps
 * working for any window added later without further wiring.
 */
public final class ThemeManager {

    /** Key under {@code Preferences.userRoot().node("CimPal")}. */
    public static final String PREF_KEY = "Theme";

    private static final String BASE_CSS = "/css/base.css";
    private static final ThemeManager INSTANCE = new ThemeManager();

    /**
     * The selectable themes, in the order they are listed in Preferences. Adding a theme is
     * one constant here plus one {@code /css/theme-*.css} file - the Preferences UI builds
     * its radio buttons from {@link #values()}.
     */
    public enum Theme {
        // --- Light ---
        MINIMALIST_WHITE("minimalist-white", "Minimalist White", "Light", "/css/theme-minimalist-white.css"),
        NORDIC_LIGHT("nordic-light", "Nordic Light", "Light", "/css/theme-nordic-light.css"),
        SOLARIZED_LIGHT("solarized-light", "Solarized Light", "Light", "/css/theme-solarized-light.css"),
        SEPIA("sepia", "Sepia (Warm Paper)", "Light", "/css/theme-sepia.css"),

        // --- Dark ---
        SLEEK_DARK("sleek-dark", "Sleek Dark Mode", "Dark", "/css/theme-sleek-dark.css"),
        MATERIAL_DARK("material-dark", "Material Dark", "Dark", "/css/theme-material-dark.css"),
        DRACULA("dracula", "Dracula", "Dark", "/css/theme-dracula.css"),
        SOLARIZED_DARK("solarized-dark", "Solarized Dark", "Dark", "/css/theme-solarized-dark.css"),
        CYBERPUNK("cyberpunk", "High-contrast Cyberpunk", "Dark", "/css/theme-cyberpunk.css"),

        // --- Accessibility ---
        HIGH_CONTRAST_BLACK("high-contrast-black", "High Contrast Black", "Accessibility",
                "/css/theme-high-contrast-black.css");

        public static final Theme DEFAULT = MINIMALIST_WHITE;

        private final String id;
        private final String displayName;
        private final String group;
        private final String cssPath;

        Theme(String id, String displayName, String group, String cssPath) {
            this.id = id;
            this.displayName = displayName;
            this.group = group;
            this.cssPath = cssPath;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** Heading the theme is listed under in Preferences. */
        public String group() {
            return group;
        }

        public String cssPath() {
            return cssPath;
        }

        /** Never throws: an unknown or missing id falls back to {@link #DEFAULT}. */
        public static Theme fromId(String id) {
            for (Theme theme : values()) {
                if (theme.id.equals(id)) {
                    return theme;
                }
            }
            return DEFAULT;
        }
    }

    private final Preferences prefs = Preferences.userRoot().node("CimPal");
    private Theme current = Theme.DEFAULT;
    private boolean installed;

    private ThemeManager() {
    }

    public static ThemeManager get() {
        return INSTANCE;
    }

    /**
     * Reads the saved theme, applies it to every window that is already open and hooks
     * every window opened from now on. Call once, on the JavaFX application thread.
     */
    public void install() {
        if (installed) {
            return;
        }
        installed = true;
        current = Theme.fromId(prefs.get(PREF_KEY, Theme.DEFAULT.id()));

        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::trackWindow);
            }
        });
        Window.getWindows().forEach(this::trackWindow);
    }

    /**
     * Switches theme and repaints every open window immediately. Does <em>not</em> persist
     * the choice - that is {@link #save()}, so Preferences can preview and then revert.
     */
    public void apply(Theme theme) {
        if (theme == null) {
            return;
        }
        current = theme;
        Window.getWindows().forEach(window -> applyToScene(window.getScene()));
    }

    /** Persists the current theme so the next launch starts with it. */
    public void save() {
        prefs.put(PREF_KEY, current.id());
    }

    public Theme getCurrent() {
        return current;
    }

    private void trackWindow(Window window) {
        applyToScene(window.getScene());
        // An Alert's scene can be attached after the window enters the list.
        window.sceneProperty().addListener((obs, oldScene, newScene) -> applyToScene(newScene));
    }

    private void applyToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        List<String> sheets = scene.getStylesheets();
        sheets.removeIf(sheet -> sheet.contains("/css/base.css") || sheet.contains("/css/theme-"));

        String base = resolve(BASE_CSS);
        if (base != null) {
            sheets.add(base);
        }
        String theme = resolve(current.cssPath());
        if (theme != null) {
            sheets.add(theme); // added last so it wins over base.css on ties
        }
    }

    private String resolve(String resourcePath) {
        URL url = ThemeManager.class.getResource(resourcePath);
        return url == null ? null : url.toExternalForm();
    }
}
