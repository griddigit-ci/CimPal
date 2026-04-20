package eu.griddigit.cimpal.main.application.tasks;

public interface ITask {
    void execute(SelectedTask parent) throws Exception;
    String validateInputs();
    String getName();
    String getPathToFXMLComponent();
    String getStatus();
    String getInfo();
    boolean getSaveResult();
}
