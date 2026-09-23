package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.GuiHelper;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import javafx.concurrent.Task;
import javafx.scene.control.TextArea;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Multithreading class to manage Tasks execution on a separate thread from the UI
public class TaskExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutionService.class);

    private static final String STATUS_COMPLETED = "Completed";
    private static final String STATUS_CANCELLED = "Cancelled";
    private static final String STATUS_FAILED = "Failed";
    private static final String STATUS_SKIPPED = "Skipped";

    private final TaskStateUpdater taskUpdater = new TaskStateUpdater();

    // The chain currently on the worker thread. It is kept in a field so that a cancel coming from
    // the UI can actually reach it: the Task used to be a local variable, so nothing could ever
    // call cancel() and the isCancelled() check inside the loop was dead code.
    private volatile Task<Void> runningChain;

    /**
     * Stops the chain and marks everything that has not finished as cancelled.
     * <p>
     * The running task is cancelled <em>without</em> interrupting the worker thread. The tasks
     * write RDF files as they go, and interrupting mid-write would leave truncated files behind,
     * so the chain stops at the next task boundary rather than instantly. Tasks that already
     * finished keep their real status instead of being relabelled.
     */
    public void cancelExecutionOfSelectedTasks(WizardContext wizardContext) {
        Task<Void> chain = this.runningChain;
        if (chain != null) {
            chain.cancel(false);
        }

        for (SelectedTask selectedTask : wizardContext.getSelectedTasks()) {
            String status = selectedTask.getStatus();
            if (!STATUS_COMPLETED.equals(status) && !STATUS_FAILED.equals(status)) {
                selectedTask.updateStatus(STATUS_CANCELLED);
                selectedTask.updateInfo("0%");
            }
        }

        wizardContext.getTableForTaskStatus().setItems(wizardContext.getSelectedTasks());
        wizardContext.getTableForTaskStatus().refresh();
    }

    public Thread executeSelectedTasks(WizardContext wizardContext) {
        Task<Void> chain = new Task<>() {
            @Override
            protected Void call() {
                List<SelectedTask> selectedTasks = wizardContext.getSelectedTasks();

                for (int i = 0; i < selectedTasks.size(); i++) {
                    SelectedTask selectedTask = selectedTasks.get(i);

                    if (isCancelled()) {
                        report(wizardContext, "Execution cancelled - " + (selectedTasks.size() - i)
                                + " task(s) were not started. No combined result was written.");
                        return null;
                    }

                    try {
                        selectedTask.getTask().execute(selectedTask);
                    } catch (Exception e) {
                        // Each task hands the next one its result through the shared
                        // DataGeneratorModel, so a task that throws leaves that model half
                        // mutated. Continuing would run every later task against corrupt input
                        // and still finish with a green status, so the chain stops here.
                        LOG.error("Task failed: {}", selectedTask.getName(), e);
                        report(wizardContext, "Error at Task: " + selectedTask.getName());
                        report(wizardContext, describe(e));
                        taskUpdater.updateState(selectedTask, STATUS_FAILED, selectedTask.getInfo(), wizardContext);
                        skipRemaining(wizardContext, selectedTasks, i + 1);
                        report(wizardContext, "Chain stopped - no combined result was written to the output directory.");
                        return null;
                    }
                }

                // The last task writes the run's result into the output directory as part of its
                // own save, so there is nothing left to write here.
                report(wizardContext, "All tasks finished. The result is in the output directory.");
                return null;
            }
        };

        chain.setOnSucceeded(event -> this.runningChain = null);
        chain.setOnCancelled(event -> this.runningChain = null);
        chain.setOnFailed(event -> {
            // call() handles its own failures, so this is a safety net for anything that escapes
            // it. Without a handler the Task swallows the throwable and the run looks successful.
            this.runningChain = null;
            Throwable error = chain.getException();
            LOG.error("Task chain terminated unexpectedly", error);
            report(wizardContext, "Execution stopped unexpectedly: " + describe(error));
        });

        this.runningChain = chain;

        Thread taskThread = new Thread(chain);
        taskThread.setDaemon(true);
        taskThread.start();
        return taskThread;
    }

    private void skipRemaining(WizardContext wizardContext, List<SelectedTask> selectedTasks, int fromIndex) {
        for (int i = fromIndex; i < selectedTasks.size(); i++) {
            taskUpdater.updateState(selectedTasks.get(i), STATUS_SKIPPED, "0%", wizardContext);
        }
    }

    private static void report(WizardContext wizardContext, String message) {
        TextArea output = WizardContext.getExecutionTextArea();
        if (output != null) {
            GuiHelper.appendTextToOutputWindow(output, message, true);
        }
    }

    // getMessage() is null for the most common failures here (NPE, index out of bounds), which is
    // why the previous output could be a blank line where the error should have been.
    private static String describe(Throwable error) {
        if (error == null) {
            return "Unknown error.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }
}
