package eu.griddigit.cimpal.core.interfaces;

public interface RunListener {
    void onLog(String level, String message);         // e.g. "INFO", "WARN"
    void onProgress(double fraction, String message); // fraction 0..1
}