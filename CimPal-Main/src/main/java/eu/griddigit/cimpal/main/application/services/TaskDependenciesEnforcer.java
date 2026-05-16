package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class TaskDependenciesEnforcer {
    private ObjectMapper objectMapper;
    private Map<String, List<String>> taskOrderConstraints;
    public TaskDependenciesEnforcer() throws IOException, URISyntaxException {
        this.objectMapper = new ObjectMapper();
        URL uri = getClass().getResource("/taskOrderRules/taskAllowed.json");

        if (uri != null) {
            try {
                InputStream file = uri.openStream();
                taskOrderConstraints = objectMapper.readValue(file,
                        new TypeReference<Map<String, List<String>>>(){});
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Resource not found: /taskOrderRules/taskAllowed.json");
        }
    }

    public String checkIfAddingTaskIsAllowed(String taskName) {
        String name = "";
        WizardContext wizardContext = WizardContext.getInstance();
        var selectedTasks = wizardContext.getSelectedTasks();
        var currentTaskConstraints = this.taskOrderConstraints.get(taskName);

        for (var task : selectedTasks) {
            if (currentTaskConstraints.contains(task.getName())){
                name = task.getName();
            }
        }
        return name;
    }

}
