package eu.griddigit.cimpal.main.application;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainGuiFxmlLoadTest {
    private static boolean toolkitStarted;

    @Test
    void mainGuiLoadsAllTabs() throws Exception {
        if (!toolkitStarted) {
            try {
                Platform.startup(() -> { });
            } catch (IllegalStateException ignored) {
                // Another JavaFX test already started the toolkit.
            }
            toolkitStarted = true;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                new FXMLLoader(getClass().getResource("/fxml/CimPalGui.fxml")).load();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "Timed out loading CimPalGui.fxml");
        if (failure.get() != null) {
            throw new AssertionError("CimPalGui.fxml failed to load", failure.get());
        }
    }
}
