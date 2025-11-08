package engine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Simple exponentially-weighted telemetry for autoscaling + debug prints. */
public class Telemetry {
    private static final double ALPHA = 0.1;

    private final AtomicReference<Double> renderMs = new AtomicReference<>(0.0);
    private final AtomicReference<Double> simMs = new AtomicReference<>(0.0);
    private final AtomicInteger queuedJobs = new AtomicInteger(0);

    public void setQueuedJobs(int q) { queuedJobs.set(q); }
    public int getQueuedJobs() { return queuedJobs.get(); }

    public void sampleRender(double ms) { ema(renderMs, ms); }
    public void sampleSim(double ms) { ema(simMs, ms); }

    public double renderMs() { return renderMs.get(); }
    public double simMs() { return simMs.get(); }

    private void ema(AtomicReference<Double> ref, double v) {
        ref.getAndUpdate(prev -> prev + ALPHA * (v - prev));
    }
}
