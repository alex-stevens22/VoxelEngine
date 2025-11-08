package engine;

import java.util.concurrent.locks.LockSupport;
import render.Renderer;

/** Owns the graphics context thread and calls the renderer each frame. */
public class RenderThread implements Runnable {
    private final Renderer renderer;
    private final Telemetry tm;
    private final EngineConfig cfg;
    private volatile boolean running = true;

    public RenderThread(Renderer r, Telemetry t, EngineConfig c) {
        this.renderer = r; this.tm = t; this.cfg = c;
    }

    public void stop() { running = false; }

    @Override public void run() {
        final long targetNs = (cfg.renderTargetFps > 0) ? 1_000_000_000L / cfg.renderTargetFps : 0L;

        while (running) {
            long start = System.nanoTime();
            renderer.pollInput();
            renderer.drainGpuUploadQueue();
            renderer.cullAndRenderFrame();   // first call will init LWJGL/GL context

            double ms = (System.nanoTime() - start) / 1_000_000.0;
            tm.sampleRender(ms);

            if (targetNs > 0) {
                long sleep = targetNs - (System.nanoTime() - start);
                if (sleep > 0) LockSupport.parkNanos(sleep);
            } else {
                Thread.onSpinWait();
            }
        }
        renderer.shutdown();
    }
}
