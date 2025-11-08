package engine;

/** Tunables for timing and autoscaling. */
public class EngineConfig {
    public int targetTps = 20;            // fixed-step simulation ticks per second
    public int renderTargetFps = 120;     // 0 = uncapped
    public int minWorkers = 0;            // allow 0 on low-end CPUs
    public int maxWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    public boolean enableAutoscale = true;
    public long autoscaleCooldownMs = 2500;
    public double inlineJobBudgetMsWhenNoWorkers = 2.0; // run X ms of jobs inline if workers==0
}
