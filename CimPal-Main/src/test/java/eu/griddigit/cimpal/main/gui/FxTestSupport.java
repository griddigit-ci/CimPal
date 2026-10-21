/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import eu.griddigit.cimpal.main.application.MainController;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared plumbing of the GUI smoke tests (TEST-6), used through {@link GuiSmoke}.
 * <p>
 * Per test it starts the JavaFX toolkit (once per JVM), installs {@link ScriptedDialogs} as the
 * {@link GuiDialogs} backend, puts an {@link InMemoryPreferences} tree into
 * {@code MainController.prefs}, and afterwards restores every public static field of
 * {@link MainController}, {@code System.out}/{@code err} and the dialog backend. It fails the test
 * when a file dialog was not scripted, or when a window is still open: a dialog that bypasses
 * {@link GuiDialogs} shows up as such a window instead of silently blocking.
 * <p>
 * The static helpers run work on the FX thread with a timeout and find the nodes of a loaded tab.
 * Waiting is done on latches and on the alerts that workers raise when they finish, never on sleeps.
 */
public final class FxTestSupport implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback {

    /** How long one step on the FX thread may take; a whole test is limited by {@link GuiSmoke}. */
    public static final Duration FX_TIMEOUT = Duration.ofSeconds(30);

    private static final Object TOOLKIT_LOCK = new Object();
    private static boolean toolkitStarted;

    private static volatile ScriptedDialogs dialogs;
    private static final Map<Field, Object> savedStatics = new LinkedHashMap<>();
    private static PrintStream savedOut;
    private static PrintStream savedErr;

    /** A tab loaded from its FXML into a scene of its own. */
    public record Loaded<C>(Parent root, C controller, Scene scene) {
    }

    @FunctionalInterface
    public interface FxAction {
        void run() throws Exception;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        startToolkit();
    }

    /** Starts the JavaFX toolkit once per JVM; later calls (and a toolkit started elsewhere) are fine. */
    public static void startToolkit() {
        synchronized (TOOLKIT_LOCK) {
            if (toolkitStarted) {
                return;
            }
            CountDownLatch started = new CountDownLatch(1);
            try {
                Platform.startup(started::countDown);
                await(started, "JavaFX toolkit start");
            } catch (IllegalStateException alreadyRunning) {
                // Another test class already started it.
            }
            Platform.setImplicitExit(false);
            toolkitStarted = true;
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        savedStatics.clear();
        for (Field field : mainControllerStatics()) {
            savedStatics.put(field, field.get(null));
        }
        savedOut = System.out;
        savedErr = System.err;
        MainController.prefs = InMemoryPreferences.newRoot().node("CimPal");
        dialogs = new ScriptedDialogs();
        GuiDialogs.useBackendForTesting(dialogs);
        List<String> open = callOnFx(FxTestSupport::showingWindowTitles);
        assertTrue(open.isEmpty(), "Windows left open before the test: " + open);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        List<String> problems = new ArrayList<>();
        try {
            // Let pending runLater work (a finishing worker's dialog, say) land first.
            List<String> strayWindows = callOnFx(() -> {
                List<String> titles = showingWindowTitles();
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    window.hide();
                }
                return titles;
            });
            if (!strayWindows.isEmpty()) {
                problems.add("Window(s) left open - a dialog not routed through GuiDialogs, or a result "
                        + "window the test did not close: " + strayWindows);
            }
            problems.addAll(dialogs.problems());
        } finally {
            GuiDialogs.resetBackend();
            for (Map.Entry<Field, Object> saved : savedStatics.entrySet()) {
                saved.getKey().set(null, saved.getValue());
            }
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
        if (context.getExecutionException().isEmpty() && !problems.isEmpty()) {
            throw new AssertionError(String.join("\n", problems));
        }
    }

    private static List<Field> mainControllerStatics() {
        List<Field> fields = new ArrayList<>();
        for (Field field : MainController.class.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<String> showingWindowTitles() {
        List<String> titles = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                titles.add(window instanceof Stage stage ? "'" + stage.getTitle() + "'" : window.getClass().getSimpleName());
            }
        }
        return titles;
    }

    /** The dialog backend of the running test. */
    public static ScriptedDialogs dialogs() {
        return dialogs;
    }

    // ---- FX thread -------------------------------------------------------------------------

    /** Runs {@code action} on the FX thread and waits for it, rethrowing what it threw. */
    public static void runOnFx(FxAction action) throws Exception {
        callOnFx(() -> {
            action.run();
            return null;
        });
    }

    /** Computes a value on the FX thread and waits for it, rethrowing what it threw. */
    public static <T> T callOnFx(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(task.call());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(FX_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            // A modal dialog shown directly (not through GuiDialogs) keeps the handler from
            // returning, but the FX thread still runs other work, so name what is open.
            throw new AssertionError("Timed out after " + FX_TIMEOUT.toSeconds() + " s waiting for work on the FX "
                    + "thread; windows showing: " + windowsShowingNow()
                    + " (a dialog not routed through GuiDialogs blocks the action like this)");
        }
        Throwable error = failure.get();
        if (error instanceof Exception exception) {
            throw exception;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return result.get();
    }

    /** Titles of the showing windows, asked of the FX thread with a short wait; for failure messages. */
    private static List<String> windowsShowingNow() {
        CountDownLatch listed = new CountDownLatch(1);
        AtomicReference<List<String>> titles = new AtomicReference<>(List.of("(FX thread did not answer)"));
        Platform.runLater(() -> {
            titles.set(showingWindowTitles());
            listed.countDown();
        });
        try {
            listed.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return titles.get();
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            assertTrue(latch.await(FX_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "Timed out after " + FX_TIMEOUT.toSeconds() + " s waiting for " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + what, e);
        }
    }

    // ---- loading and finding nodes ---------------------------------------------------------

    /**
     * Loads {@code /fxml/<fxml>} as {@code MainController} does for its tab, puts it into a scene
     * and runs a CSS and layout pass, so that the content of titled panes and scroll panes exists
     * and can be looked up.
     */
    public static <C> Loaded<C> loadTab(String fxml) throws Exception {
        URL url = FxTestSupport.class.getResource("/fxml/" + fxml);
        assertNotNull(url, "FXML not found: /fxml/" + fxml);
        return callOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(url);
            Parent root = loader.load();
            Scene scene = new Scene(root, 1280, 900);
            root.applyCss();
            root.layout();
            return new Loaded<>(root, loader.getController(), scene);
        });
    }

    /** The node with fx:id {@code id} under {@code root}, which must exist and have {@code type}. */
    public static <T extends Node> T node(Parent root, String id, Class<T> type) {
        Node node = root.lookup("#" + id);
        assertNotNull(node, "No node with fx:id '" + id + "'");
        assertTrue(type.isInstance(node), "fx:id '" + id + "' is a " + node.getClass().getSimpleName()
                + ", not a " + type.getSimpleName());
        return type.cast(node);
    }

    /** Every button (or other labelled control) under {@code root} showing {@code text}, in scene order. */
    public static List<ButtonBase> buttons(Parent root, String text) {
        List<ButtonBase> found = new ArrayList<>();
        collect(root, text, found);
        return found;
    }

    /** The one button under {@code root} showing {@code text}. */
    public static ButtonBase button(Parent root, String text) {
        List<ButtonBase> found = buttons(root, text);
        assertEquals(1, found.size(), "Expected exactly one button '" + text + "', found " + found.size());
        return found.getFirst();
    }

    private static void collect(Parent parent, String text, List<ButtonBase> found) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof ButtonBase button && text.equals(((Labeled) button).getText())) {
                found.add(button);
            }
            if (child instanceof Parent nested) {
                collect(nested, text, found);
            }
        }
    }

    // ---- acting ----------------------------------------------------------------------------

    /** Clicks {@code button} on the FX thread and waits until its handler has returned. */
    public static void fire(ButtonBase button) throws Exception {
        runOnFx(() -> {
            assertFalse(button.isDisabled(), "Button '" + button.getText() + "' is disabled");
            button.fire();
        });
    }

    /**
     * Clicks {@code button} without waiting for its handler: for handlers that open a modal window
     * and only return once it is closed. Wait for the window with {@link #awaitWindow}.
     */
    public static void fireLater(ButtonBase button) {
        Platform.runLater(button::fire);
    }

    /** Waits for a showing window titled {@code title} and returns it. */
    public static Stage awaitWindow(String title, Duration timeout) throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<Stage> stage = new AtomicReference<>();
        ListChangeListener<Window> listener = change -> {
            Stage match = findShowing(title);
            if (match != null && stage.compareAndSet(null, match)) {
                shown.countDown();
            }
        };
        runOnFx(() -> {
            Stage match = findShowing(title);
            if (match != null) {
                stage.set(match);
                shown.countDown();
            } else {
                Window.getWindows().addListener(listener);
            }
        });
        try {
            boolean appeared = shown.await(timeout.toSeconds(), TimeUnit.SECONDS);
            assertTrue(appeared, "No window '" + title + "' within " + timeout.toSeconds()
                    + " s; alerts raised: " + dialogs.alerts());
            return stage.get();
        } finally {
            Platform.runLater(() -> Window.getWindows().removeListener(listener));
        }
    }

    private static Stage findShowing(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage candidate && window.isShowing() && title.equals(candidate.getTitle())) {
                return candidate;
            }
        }
        return null;
    }

    /** Closes a window the test opened (a result window, say). */
    public static void close(Stage stage) throws Exception {
        runOnFx(stage::close);
    }
}
