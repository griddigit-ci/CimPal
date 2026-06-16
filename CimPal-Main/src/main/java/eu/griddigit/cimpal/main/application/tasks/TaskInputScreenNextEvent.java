package eu.griddigit.cimpal.main.application.tasks;

import javafx.event.Event;
import javafx.event.EventType;

public class TaskInputScreenNextEvent extends Event {
    public static final EventType<TaskInputScreenNextEvent> LOAD_TASK_INPUT =
            new EventType<>(Event.ANY, "LOAD_TASK_INPUT");
    public TaskInputScreenNextEvent() {
        super(LOAD_TASK_INPUT);
    }
}