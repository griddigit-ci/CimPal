package eu.griddigit.cimpal.main.application.tasks;

public class SelectedTask {
    private String name;
    private Integer order;
    private String action;
    private String status;
    private String info;
    private ITask task;

    //TODO Add image for action icon (delete)

    public SelectedTask(ITask task, Integer order) {
        this.name = task.getName();
        this.order = order;
        this.task = task;
        this.action = "Remove";
        this.status = task.getStatus();
        this.info = task.getInfo();

        //this.image = new ImageView( new Image("file:application.trash-85-64.png"));
    }

    public String getName() {
        return this.name;
    }

    public Integer getOrder() {
        return this.order;
    }

    public String getAction() {
        return this.action;
    }

    public String getStatus() {
        return this.status;
    }

    public String getInfo() {
        return this.info;
    }

    public ITask getTask() { return this.task; }

    public void updateStatus(String status) {
        this.status = status;
    }

    public void updateInfo(String info) {
        this.info = info;
    }

    public void decrementIndex() {
        if (this.order > 0){
            this.order = this.order - 1;
        }
    }
}
