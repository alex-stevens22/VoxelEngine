package engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jobs.JobSystem;
import render.LwjglRenderer;
import render.Renderer;
import world.World;

/** Entry point: wires config, telemetry, jobs, world, renderer, and threads. */
public class VoxelEngine {
    public static void main(String[] args) throws Exception {
        EngineConfig cfg = new EngineConfig();
        Telemetry tm = new Telemetry();
        JobSystem jobs = new JobSystem(cfg, tm);
        World world = new World(jobs);
        Renderer renderer = new LwjglRenderer(world, tm, cfg);

        SimulationThread sim = new SimulationThread(world, jobs, tm, cfg);
        RenderThread rt = new RenderThread(renderer, tm, cfg);

        Thread simThread = new Thread(sim, "SimThread");
        Thread renderThread = new Thread(rt, "RenderThread");
        simThread.start();
        renderThread.start();

        // Console debug once per second
        ScheduledExecutorService dbg = Executors.newSingleThreadScheduledExecutor();
        dbg.scheduleAtFixedRate(() -> System.out.printf(
            "RT %.2f ms | ST %.2f ms | Q=%d | workers=%d%n",
            tm.renderMs(), tm.simMs(), tm.getQueuedJobs(), jobs.currentWorkers()
        ), 1, 1, TimeUnit.SECONDS);

        // Seed some chunk work so you can see geometry
        world.requestInitialChunks(0, 0, 2);

        // Run ~30s for demo, then shutdown
        Thread.sleep(30_000);
        System.out.println("Shutting down...");
        rt.stop();
        sim.stop();
        jobs.shutdown();
        dbg.shutdownNow();
        renderThread.join();
        simThread.join();
    }
}
