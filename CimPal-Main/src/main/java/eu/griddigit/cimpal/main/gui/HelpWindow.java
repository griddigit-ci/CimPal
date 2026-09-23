/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.gui;

import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The Help window: a {@link WebView} showing the bundled {@code help.html} documentation page.
 * <p>
 * Unlike the About and Preferences dialogs this window is deliberately <em>not</em> modal - a
 * reference document is of little use if it blocks the application you are reading it about.
 * Closing it hides rather than disposes it, so reopening returns to the same section.
 */
public final class HelpWindow {

    private static final String HELP_RESOURCE = "/help/help.html";
    private static final double MIN_ZOOM = 0.6;
    private static final double MAX_ZOOM = 2.0;
    private static final double ZOOM_STEP = 1.1;

    /** The single Help window, reused so repeated Help clicks re-focus instead of stacking copies. */
    private static Stage stage;
    private static WebEngine engine;

    private HelpWindow() {
    }

    /** Opens the Help window, or brings the already-open one to the front. */
    public static void show() {
        if (stage != null) {
            // Re-read the theme: it may have changed in Preferences since the page was loaded.
            applyTheme();
            stage.show();
            stage.requestFocus();
            stage.toFront();
            return;
        }

        URL help = HelpWindow.class.getResource(HELP_RESOURCE);
        if (help == null) {
            GUIhelper.showUserFriendlyError("Help unavailable",
                    "The bundled help page could not be found in the application resources.", null);
            return;
        }

        WebView view = new WebView();
        engine = view.getEngine();
        engine.setJavaScriptEnabled(true);

        // JavaFX CSS does not reach inside a WebView, so the page carries its own palettes and
        // we tell it which one to use once the document is ready to be scripted.
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                applyTheme();
            }
        });
        engine.load(help.toExternalForm());

        BorderPane root = new BorderPane(view);
        root.setTop(buildToolBar(view, help.toExternalForm()));

        stage = new Stage();
        stage.setScene(new Scene(root, 1100, 780));
        stage.setTitle("CimPal Help");
        stage.setMinWidth(640);
        stage.setMinHeight(480);
        // Hide instead of close, so the scroll position and expanded cards survive a reopen.
        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
        });
        stage.show();
    }

    private static ToolBar buildToolBar(WebView view, String homeUrl) {
        WebHistory history = engine.getHistory();

        Button back = new Button("◀ Back");
        Button forward = new Button("Forward ▶");
        Button home = new Button("Contents");
        Button zoomOut = new Button("A−");
        Button zoomIn = new Button("A+");
        Button openExternal = new Button("Open in browser");

        back.setOnAction(e -> goHistory(history, -1));
        forward.setOnAction(e -> goHistory(history, 1));
        home.setOnAction(e -> engine.load(homeUrl));
        zoomOut.setOnAction(e -> view.setZoom(clampZoom(view.getZoom() / ZOOM_STEP)));
        zoomIn.setOnAction(e -> view.setZoom(clampZoom(view.getZoom() * ZOOM_STEP)));
        openExternal.setOnAction(e -> openInExternalBrowser());

        zoomOut.setTooltip(new javafx.scene.control.Tooltip("Decrease text size"));
        zoomIn.setTooltip(new javafx.scene.control.Tooltip("Increase text size"));
        openExternal.setTooltip(new javafx.scene.control.Tooltip(
                "Open this help page in the system web browser, for printing or side-by-side reading"));

        Runnable updateNav = () -> {
            int index = history.getCurrentIndex();
            back.setDisable(index <= 0);
            forward.setDisable(index >= history.getEntries().size() - 1);
        };
        updateNav.run();
        history.currentIndexProperty().addListener((obs, o, n) -> updateNav.run());
        history.getEntries().addListener((javafx.collections.ListChangeListener<WebHistory.Entry>)
                change -> updateNav.run());

        Region spacer = new Region();
        spacer.setMinWidth(0);
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        return new ToolBar(back, forward, new Separator(), home, new Separator(),
                zoomOut, zoomIn, spacer, openExternal);
    }

    private static void goHistory(WebHistory history, int offset) {
        try {
            history.go(offset);
        } catch (IndexOutOfBoundsException ignored) {
            // Nothing to go to - the button state normally prevents this.
        }
    }

    private static double clampZoom(double zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Maps the active theme onto the page's {@code data-theme} attribute. The three groups
     * {@link ThemeManager.Theme#group()} already distinguishes - Light, Dark, Accessibility -
     * map one-to-one onto the three palettes defined in {@code help.html}.
     */
    private static void applyTheme() {
        if (engine == null || engine.getDocument() == null) {
            return;
        }
        String key = switch (ThemeManager.get().getCurrent().group()) {
            case "Dark" -> "dark";
            case "Accessibility" -> "hc";
            default -> "light";
        };
        try {
            // Set the attribute through the DOM rather than by concatenating the value into
            // script source. The value is currently one of three compile-time constants and
            // so cannot carry injected script, but building script text by concatenation is
            // a pattern that becomes injectable the moment the value's provenance widens.
            engine.getDocument().getDocumentElement().setAttribute("data-theme", key);
        } catch (RuntimeException e) {
            // A styling failure must never stop the user reading the help page.
            System.err.println("[WARN] Could not apply the theme to the help page: " + e.getMessage());
        }
    }

    /**
     * Copies the help page to a temporary file and hands it to the system browser. The copy is
     * necessary because the resource normally lives inside the application jar, which the
     * browser cannot open directly.
     */
    private static void openInExternalBrowser() {
        try (InputStream in = HelpWindow.class.getResourceAsStream(HELP_RESOURCE)) {
            if (in == null) {
                GUIhelper.showUserFriendlyError("Help unavailable",
                        "The bundled help page could not be found in the application resources.", null);
                return;
            }
            Path temp = Files.createTempFile("cimpal-help", ".html");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Desktop.getDesktop().browse(temp.toUri());
        } catch (IOException | UnsupportedOperationException e) {
            GUIhelper.showUserFriendlyError("Could not open the browser",
                    "The help page could not be handed to the system web browser. "
                            + "It is still available in this window.", e);
        }
    }
}
