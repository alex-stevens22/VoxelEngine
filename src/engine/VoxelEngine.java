package engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jobs.JobSystem;
import render.LwjglRenderer;
import render.Renderer;
import world.World;
import engine.InputState;

/** Entry point: wires config, telemetry, jobs, world, renderer, and threads. */
public class VoxelEngine {
    public static void main(String[] args) throws Exception {
        EngineConfig cfg = new EngineConfig();
        InputState input = new InputState();
        Telemetry tm = new Telemetry();
        JobSystem jobs = new JobSystem(cfg, tm);
        World world = new World(jobs, input);
        Renderer renderer = new LwjglRenderer(world, tm, cfg, input);

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

        renderThread.join();
        
        System.out.println("Shutting down...");
        sim.stop();
        jobs.shutdown();
        simThread.join();
        simThread.join();
    }
}
