package engine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe input bridge:
 * - Render thread writes key states + mouse deltas
 * - Simulation thread reads & consumes deltas once per tick
 */
public class InputState {
    // Keys
    public volatile boolean keyW, keyA, keyS, keyD, keySpace, keyCtrl, keyShift;
    public volatile boolean keyEsc;

    // Mouse-look deltas (accumulate on RT, consume on ST)
    private final AtomicReference<double[]> mouseDelta = new AtomicReference<>(new double[]{0,0});
    // Clicks (edge-triggered flags set by RT, consumed/cleared by ST)
    private final AtomicBoolean leftClick = new AtomicBoolean(false);
    private final AtomicBoolean rightClick = new AtomicBoolean(false);

    public void addMouseDelta(double dx, double dy) {
        mouseDelta.getAndUpdate(d -> new double[]{ d[0] + dx, d[1] + dy });
    }
    /** Returns and resets the accumulated mouse delta. */
    public double[] consumeMouseDelta() {
        return mouseDelta.getAndSet(new double[]{0,0});
    }

    public void signalLeftClick() { leftClick.set(true); }
    public void signalRightClick() { rightClick.set(true); }
    public boolean consumeLeftClick() { return leftClick.getAndSet(false); }
    public boolean consumeRightClick() { return rightClick.getAndSet(false); }
}
