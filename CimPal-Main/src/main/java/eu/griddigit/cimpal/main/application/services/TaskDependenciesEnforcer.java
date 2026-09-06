package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class TaskDependenciesEnforcer {

    private static final String RULES_RESOURCE = "/taskOrderRules/taskAllowed.json";

    private final ObjectMapper objectMapper;
    private final Map<String, List<String>> taskOrderConstraints;

    /**
     * Loads the task-ordering rules from the application bundle.
     * <p>
     * A missing or unreadable rules file is fatal rather than swallowed: the previous
     * implementation logged the failure and continued with a null constraint map, so the
     * first call to {@link #checkIfAddingTaskIsAllowed(String)} failed with a
     * {@link NullPointerException} far from the real cause. The stream is also closed,
     * which it previously was not.
     */
    public TaskDependenciesEnforcer() throws IOException, URISyntaxException {
        this.objectMapper = new ObjectMapper();

        URL rules = getClass().getResource(RULES_RESOURCE);
        if (rules == null) {
            throw new IOException("Missing bundled resource: " + RULES_RESOURCE);
        }

        try (InputStream in = rules.openStream()) {
            this.taskOrderConstraints = objectMapper.readValue(
                    in, new TypeReference<Map<String, List<String>>>() {});
        }
    }

    public String checkIfAddingTaskIsAllowed(String taskName) {
        String name = "";
        WizardContext wizardContext = WizardContext.getInstance();
        var selectedTasks = wizardContext.getSelectedTasks();
        // A task with no recorded constraints is unconstrained, not an error.
        var currentTaskConstraints = this.taskOrderConstraints.getOrDefault(taskName, List.of());

        for (var task : selectedTasks) {
            if (currentTaskConstraints.contains(task.getName())){
                name = task.getName();
            }
        }
        return name;
    }

}
