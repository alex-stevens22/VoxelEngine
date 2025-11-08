package engine;

import java.util.concurrent.locks.LockSupport;
import jobs.JobSystem;
import world.World;

/** Fixed-timestep simulation (decoupled from render). */
public class SimulationThread implements Runnable {
    private final World world;
    private final JobSystem jobs;
    private final Telemetry tm;
    private final EngineConfig cfg;
    private volatile boolean running = true;

    public SimulationThread(World w, JobSystem j, Telemetry t, EngineConfig c) {
        this.world = w; this.jobs = j; this.tm = t; this.cfg = c;
    }

    public void stop() { running = false; }

    @Override public void run() {
        final long stepNs = (long)(1_000_000_000L / (double)cfg.targetTps);
        long next = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            if (now < next) { LockSupport.parkNanos(next - now); continue; }

            long start = System.nanoTime();
            world.consumeInputs();
            world.tick(1.0 / cfg.targetTps);
            world.processChunkPipelines();

            if (jobs.currentWorkers() == 0) {
                jobs.runInlineFor(cfg.inlineJobBudgetMsWhenNoWorkers);
            }

            tm.sampleSim((System.nanoTime() - start) / 1_000_000.0);
            next += stepNs;
        }
    }
}
