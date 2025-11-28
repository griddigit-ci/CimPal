package eu.griddigit.cimpal.core.interfaces;

public interface ShaclAutoTesterCallback {
    void updateProgress(double progress);
    void appendOutput(String message);
}
