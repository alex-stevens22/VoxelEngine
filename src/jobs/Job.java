package jobs;
public interface Job extends Runnable {
    JobPriority priority();
    default String name() { return getClass().getSimpleName(); }
}
