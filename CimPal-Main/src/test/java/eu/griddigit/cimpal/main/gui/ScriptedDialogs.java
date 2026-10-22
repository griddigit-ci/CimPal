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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The {@link GuiDialogs.Backend} of the GUI smoke tests. A test queues the answers its file dialogs
 * should give; every alert is recorded instead of shown, so nothing modal ever blocks the FX thread.
 * A file dialog the test did not script is answered with "cancel" and recorded as unexpected, and
 * {@link FxTestSupport} fails the test for it.
 */
public final class ScriptedDialogs implements GuiDialogs.Backend {

    /** One alert the code under test raised. */
    public record ShownAlert(Alert.AlertType type, String title, String header, String content) {
        @Override
        public String toString() {
            return type + " '" + title + "': " + content;
        }
    }

    private final Deque<List<File>> openAnswers = new ArrayDeque<>();
    private final Deque<File> saveAnswers = new ArrayDeque<>();
    private final Deque<File> directoryAnswers = new ArrayDeque<>();
    private final Deque<ButtonType> confirmAnswers = new ArrayDeque<>();
    private final List<ShownAlert> alerts = new ArrayList<>();
    private final List<String> unexpected = new ArrayList<>();
    private final List<String> fileDialogTitles = new ArrayList<>();

    /** The next open dialog (single or multiple selection) answers with {@code files}. */
    public synchronized ScriptedDialogs answerOpen(File... files) {
        openAnswers.add(List.of(files));
        return this;
    }

    /** The next save dialog answers with {@code file}. */
    public synchronized ScriptedDialogs answerSave(File file) {
        saveAnswers.add(file);
        return this;
    }

    /** The next folder dialog answers with {@code folder}. */
    public synchronized ScriptedDialogs answerDirectory(File folder) {
        directoryAnswers.add(folder);
        return this;
    }

    /** The next confirmation alert is closed with {@code button}; unscripted ones are cancelled. */
    public synchronized ScriptedDialogs answerConfirm(ButtonType button) {
        confirmAnswers.add(button);
        return this;
    }

    @Override
    public synchronized File open(FileChooser chooser, Window owner) {
        List<File> answer = nextOpen(chooser);
        return answer == null || answer.isEmpty() ? null : answer.getFirst();
    }

    @Override
    public synchronized List<File> openMultiple(FileChooser chooser, Window owner) {
        List<File> answer = nextOpen(chooser);
        return answer == null ? null : new ArrayList<>(answer);
    }

    private List<File> nextOpen(FileChooser chooser) {
        fileDialogTitles.add(chooser.getTitle());
        List<File> answer = openAnswers.poll();
        if (answer == null) {
            unexpected.add("Unscripted open dialog '" + chooser.getTitle() + "'");
        }
        return answer;
    }

    @Override
    public synchronized File save(FileChooser chooser, Window owner) {
        fileDialogTitles.add(chooser.getTitle());
        File answer = saveAnswers.poll();
        if (answer == null) {
            unexpected.add("Unscripted save dialog '" + chooser.getTitle() + "'");
        }
        return answer;
    }

    @Override
    public synchronized File directory(DirectoryChooser chooser, Window owner) {
        fileDialogTitles.add(chooser.getTitle());
        File answer = directoryAnswers.poll();
        if (answer == null) {
            unexpected.add("Unscripted folder dialog '" + chooser.getTitle() + "'");
        }
        return answer;
    }

    @Override
    public synchronized Optional<ButtonType> alert(Alert alert) {
        alerts.add(new ShownAlert(alert.getAlertType(), alert.getTitle(), alert.getHeaderText(), alert.getContentText()));
        notifyAll();
        if (alert.getAlertType() == Alert.AlertType.CONFIRMATION) {
            ButtonType answer = confirmAnswers.poll();
            if (answer == null) {
                unexpected.add("Unscripted confirmation '" + alert.getTitle() + "'");
                return Optional.of(ButtonType.CANCEL);
            }
            return Optional.of(answer);
        }
        return Optional.of(ButtonType.OK);
    }

    /** Every alert raised so far, oldest first. */
    public synchronized List<ShownAlert> alerts() {
        return List.copyOf(alerts);
    }

    /** Alerts of type ERROR raised so far. */
    public synchronized List<ShownAlert> errors() {
        return alerts.stream().filter(alert -> alert.type() == Alert.AlertType.ERROR).toList();
    }

    /** Titles of the file and folder dialogs opened so far, in order. */
    public synchronized List<String> fileDialogTitles() {
        return List.copyOf(fileDialogTitles);
    }

    /** Dialogs nobody scripted, plus answers queued but never asked for. */
    synchronized List<String> problems() {
        List<String> problems = new ArrayList<>(unexpected);
        if (!openAnswers.isEmpty()) problems.add(openAnswers.size() + " scripted open answer(s) never used");
        if (!saveAnswers.isEmpty()) problems.add(saveAnswers.size() + " scripted save answer(s) never used");
        if (!directoryAnswers.isEmpty()) problems.add(directoryAnswers.size() + " scripted folder answer(s) never used");
        if (!confirmAnswers.isEmpty()) problems.add(confirmAnswers.size() + " scripted confirmation(s) never used");
        return problems;
    }

    /**
     * Waits until an alert matching {@code match} has been raised (by a worker finishing, typically)
     * and returns it. Fails, listing what was raised instead, when none arrives within {@code timeout}.
     */
    public synchronized ShownAlert awaitAlert(Predicate<ShownAlert> match, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (ShownAlert alert : alerts) {
                if (match.test(alert)) {
                    return alert;
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("No matching alert within " + timeout.toSeconds() + " s; raised: " + alerts);
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    /** Waits for an alert with the given title. */
    public ShownAlert awaitAlert(String title, Duration timeout) throws InterruptedException {
        return awaitAlert(alert -> title.equals(alert.title()), timeout);
    }
}
