/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.application.Platform;

public class TaskStateUpdater {
    public void updateState(SelectedTask currentTask, String status, String percentComplete, WizardContext wizardContext) {
        Platform.runLater(() ->  {
            currentTask.updateStatus(status);
            currentTask.updateInfo(percentComplete);
            wizardContext.getTableForTaskStatus().setItems (wizardContext.getSelectedTasks());
            wizardContext.getTableForTaskStatus().refresh();
        });
    }
}
