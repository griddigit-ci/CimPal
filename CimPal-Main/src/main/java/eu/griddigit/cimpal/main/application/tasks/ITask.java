package eu.griddigit.cimpal.main.application.tasks;

public interface ITask {
    void execute(SelectedTask parent) throws Exception;
    String validateInputs();
    String getName();
    String getPathToFXMLComponent();
    String getStatus();
    String getInfo();
    boolean getSaveResult();

    /**
     * Whether the task may be added to a wizard run.
     * <p>
     * A task whose implementation was never finished stays registered - so its name keeps
     * resolving and re-enabling it is a one-line change - but the selection list refuses it
     * instead of letting a run fail half way through, after earlier tasks have already
     * written files. Override together with {@link #getUnavailableReason()}.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Why {@link #isAvailable()} is false, shown to the user when they try to select the task.
     * Empty for an available task.
     */
    default String getUnavailableReason() {
        return "";
    }
}
