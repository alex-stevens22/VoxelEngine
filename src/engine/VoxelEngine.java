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
        Renderer renderer = new LwjglRenderer(world, tm, cfg);

        SimulationThread sim = new SimulationThread(world, jobs, tm, cfg);
        RenderThread rt = new RenderThread(renderer, tm);

        Thread simThread = new Thread(sim, "SimThread");
        Thread renderThread = new Thread(rt, "RenderThread");
        simThread.start();
        renderThread.start();

        // Console debug once per second
        ScheduledExecutorService dbg = Executors.newSingleThreadScheduledExecutor();
        final long[] prevFrames = { tm.getFrameCount() };
        final long[] prevTimeNs = { System.nanoTime() };
        
        //renderer.drainGpuUploadQueue();
        //renderer.cullAndRenderFrame();
        //tm.markFrame(); // markFrame updates your FPS counter
        
        
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[UNCAUGHT] in " + t.getName());
            e.printStackTrace();
        });


        dbg.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long framesNow = tm.getFrameCount();

            long df = framesNow - prevFrames[0];
            double dtSec = (now - prevTimeNs[0]) / 1_000_000_000.0;
            double fps = (dtSec > 0) ? (df / dtSec) : 0.0;

            prevFrames[0] = framesNow;
            prevTimeNs[0] = now;

            double rtime = tm.renderMs();
            double stime = tm.simMs();

            System.out.printf(
                "FPS %.0f | RT %.2f ms | ST %.2f ms | Q=%d | workers=%d%n",
                fps, rtime, stime, tm.getQueuedJobs(), jobs.currentWorkers()
            );
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

        // Seed some chunk work so you can see geometry
        world.requestInitialChunks(0, 0, 2);

        renderThread.join();
        dbg.shutdownNow();
        
        System.out.println("Shutting down...");
        sim.stop();
        jobs.shutdown();
        simThread.join();
    }
}
