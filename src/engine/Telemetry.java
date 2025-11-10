package engine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Simple exponentially-weighted telemetry for autoscaling + debug prints. */
public class Telemetry {
    private static final double ALPHA = 0.1;
    
    private volatile long lastSimTickNs = System.nanoTime();
    private volatile long simStepNs = 50_000_000L; // default for 20 TPS (50ms)
    
 // call this once from SimulationThread to set actual step
    public void setSimStepNs(long ns) { simStepNs = ns; }

    // call this at the end of every sim tick
    public void markSimTick() { lastSimTickNs = System.nanoTime(); }

    // render thread calls this to get interpolation factor in [0..1]
    public double interpAlpha() {
        long dt = System.nanoTime() - lastSimTickNs;
        double a = dt / (double) simStepNs;
        if (a < 0) return 0;
        if (a > 1) return 1;
        return a;
    }

    private final AtomicReference<Double> renderMs = new AtomicReference<>(0.0);
    private final AtomicReference<Double> simMs = new AtomicReference<>(0.0);
    private final AtomicInteger queuedJobs = new AtomicInteger(0);
    private final AtomicLong frameCount = new AtomicLong(0);

    public void setQueuedJobs(int q) { queuedJobs.set(q); }
    public int getQueuedJobs() { return queuedJobs.get(); }

    public void sampleRender(double ms) { ema(renderMs, ms); }
    public void sampleSim(double ms) { ema(simMs, ms); }
    public void markFrame() { frameCount.incrementAndGet(); }
    public long getFrameCount() { return frameCount.get(); }

    public double renderMs() { return renderMs.get(); }
    public double simMs() { return simMs.get(); }

    private void ema(AtomicReference<Double> ref, double v) {
        ref.getAndUpdate(prev -> prev + ALPHA * (v - prev));
    }
}
