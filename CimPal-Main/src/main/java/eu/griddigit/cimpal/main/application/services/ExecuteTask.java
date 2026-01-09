package eu.griddigit.cimpal.main.application.services;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class ExecuteTask<V> extends FutureTask<V> {
    public ExecuteTask(Callable<V> callable) {
        super(callable);
    }

    @Override
    public void run() {
        super.run();
    }

    @Override
    protected void done() {
        WizardContext context = WizardContext.getInstance();
        super.done();

    }

}
