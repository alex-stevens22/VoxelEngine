// Minimal VoxelEngine skeleton (dynamic workers) — single file, no modules, default package.
// Works on Java 21; no external deps. Run with: Run As → Java Application.

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

public class VoxelEngine {

    // ---------- entry point ----------
    public static void main(String[] args) throws Exception {
        EngineConfig cfg = new EngineConfig();
        cfg.targetTps = 20;		// Target Ticks per Second (20 default)
        cfg.renderTargetFps = 120;
        cfg.minWorkers = 0;
        cfg.maxWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        cfg.enableAutoscale = true;
        cfg.autoscaleCooldownMs = 2500;
        cfg.inlineJobBudgetMsWhenNoWorkers = 2.0;

        Telemetry tm = new Telemetry();
        JobSystem jobs = new JobSystem(cfg, tm);
        World world = new World(jobs);
        Renderer renderer = new StubRenderer(world, tm);

        SimulationThread sim = new SimulationThread(world, jobs, tm, cfg);
        RenderThread rt = new RenderThread(renderer, tm, cfg);

        Thread simThread = new Thread(sim, "SimThread");
        Thread renderThread = new Thread(rt, "RenderThread");

        simThread.start();
        renderThread.start();
        
        // --- debug telemetry printer ---
        ScheduledExecutorService dbg = Executors.newSingleThreadScheduledExecutor();
        dbg.scheduleAtFixedRate(() -> {
            System.out.printf(
                "RT %.2f ms | ST %.2f ms | Q=%d | wait≈%.2f ms | workers=%d%n",
                tm.renderMs(), tm.simMs(), tm.getQueuedJobs(), tm.avgJobWaitMs(), jobs.currentWorkers()
            );
        }, 1, 1, TimeUnit.SECONDS);


        // queue a few chunks so you can see the system doing work
        world.requestInitialChunks(0, 0, 2);

        // demo: run ~15s then stop
        Thread.sleep(15_000);
        System.out.println("Shutting down...");
        rt.stop();
        sim.stop();
        jobs.shutdown();
        dbg.shutdownNow();
        renderThread.join();
        simThread.join();
    }

    // ---------- config / telemetry ----------
    static final class EngineConfig {
        int targetTps = 20;
        int renderTargetFps = 120;
        int minWorkers = 0;
        int maxWorkers = 4;
        boolean enableAutoscale = true;
        long autoscaleCooldownMs = 2500;
        double inlineJobBudgetMsWhenNoWorkers = 2.0;
    }

    static final class Telemetry {
        private static final double ALPHA = 0.1;
        private final AtomicReference<Double> renderMs = new AtomicReference<>(0.0);
        private final AtomicReference<Double> simMs = new AtomicReference<>(0.0);
        private final AtomicReference<Double> avgJobWaitMs = new AtomicReference<>(0.0);
        private final AtomicReference<Double> avgJobExecMs = new AtomicReference<>(0.0);
        private final AtomicInteger queuedJobs = new AtomicInteger(0);

        void setQueuedJobs(int q) { queuedJobs.set(q); }
        int getQueuedJobs() { return queuedJobs.get(); }
        void sampleRender(double ms) { ema(renderMs, ms); }
        void sampleSim(double ms) { ema(simMs, ms); }
        void sampleJobWait(double ms) { ema(avgJobWaitMs, ms); }
        void sampleJobExec(double ms) { ema(avgJobExecMs, ms); }
        double renderMs() { return renderMs.get(); }
        double simMs() { return simMs.get(); }
        double avgJobWaitMs() { return avgJobWaitMs.get(); }

        private void ema(AtomicReference<Double> ref, double v) {
            ref.getAndUpdate(prev -> prev + ALPHA * (v - prev));
        }
    }

    // ---------- renderer (stub) ----------
    interface Renderer {
        void pollInput();
        void drainGpuUploadQueue();
        void cullAndRenderFrame();
        void shutdown();
    }
    static final class StubRenderer implements Renderer {
        private final World world;
        private final Telemetry tm;
        private final Random rng = new Random();
        StubRenderer(World w, Telemetry t) { world = w; tm = t; }

        public void pollInput() {
            // occasionally request more chunks to simulate camera motion
            if (rng.nextDouble() < 0.10 /* was 0.02*/) world.requestInitialChunks(rng.nextInt(5)-2, rng.nextInt(5)-2, 2);
        }
        public void drainGpuUploadQueue() {
            int uploads = 0;
            MeshBlob blob;
            while (uploads < 2 && (blob = world.gpuUploads.poll()) != null) {
                Util.busy(0.25);
                uploads++;
            }
        }
        public void cullAndRenderFrame() { Util.busy(1.0 + rng.nextDouble()); }
        public void shutdown() {}
    }

    static final class RenderThread implements Runnable {
        private final Renderer renderer; private final Telemetry tm; private final EngineConfig cfg;
        private volatile boolean running = true;
        RenderThread(Renderer r, Telemetry t, EngineConfig c) { renderer = r; tm = t; cfg = c; }
        void stop() { running = false; }

        public void run() {
            final long targetNs = (cfg.renderTargetFps > 0) ? 1_000_000_000L / cfg.renderTargetFps : 0L;
            while (running) {
                long start = System.nanoTime();
                renderer.pollInput();
                renderer.drainGpuUploadQueue();
                renderer.cullAndRenderFrame();
                tm.sampleRender((System.nanoTime() - start) / 1_000_000.0);

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

    // ---------- simulation ----------
    static final class SimulationThread implements Runnable {
        private final World world; private final JobSystem jobs; private final Telemetry tm; private final EngineConfig cfg;
        private volatile boolean running = true;
        SimulationThread(World w, JobSystem j, Telemetry t, EngineConfig c) { world = w; jobs = j; tm = t; cfg = c; }
        void stop() { running = false; }

        public void run() {
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

    // ---------- jobs ----------
    enum JobPriority { P0_CRITICAL, P1_NEAR, P2_BACKGROUND }
    interface Job extends Runnable { JobPriority priority(); default String name() { return getClass().getSimpleName(); } }

    static final class JobSystem {
        private final EngineConfig cfg; private final Telemetry tm;
        private final PriorityBlockingQueue<ScheduledJob> queue = new PriorityBlockingQueue<>(64, (a,b) -> {
            int p = Integer.compare(a.job.priority().ordinal(), b.job.priority().ordinal());
            return (p != 0) ? p : Long.compare(a.order, b.order);
        });
        private final ConcurrentLinkedQueue<Worker> workers = new ConcurrentLinkedQueue<>();
        private final AtomicInteger workerCount = new AtomicInteger(0);
        private final ScheduledExecutorService scaler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoScaler"); t.setDaemon(true); return t; });
        private volatile long lastScale = 0;

        JobSystem(EngineConfig c, Telemetry t) {
            cfg = c; tm = t;
            int target = Math.min(cfg.maxWorkers, Math.max(cfg.minWorkers, Runtime.getRuntime().availableProcessors() - 2));
            setWorkerCount(target);
            if (cfg.enableAutoscale) scaler.scheduleAtFixedRate(this::autoscaleTick, 1, 1, TimeUnit.SECONDS);
        }

        void shutdown() { scaler.shutdownNow(); setWorkerCount(0); }
        int currentWorkers() { return workerCount.get(); }
        void submit(Job job) { queue.offer(new ScheduledJob(job)); tm.setQueuedJobs(queue.size()); }

        void runInlineFor(double budgetMs) {
            long budgetNs = (long)(budgetMs * 1_000_000);
            long start = System.nanoTime();
            for (;;) {
                if (System.nanoTime() - start >= budgetNs) break;
                ScheduledJob sj = queue.poll();
                if (sj == null) break;
                executeJob(sj);
            }
            tm.setQueuedJobs(queue.size());
        }

        private void autoscaleTick() {
            int q = queue.size(); tm.setQueuedJobs(q);
            double render = tm.renderMs(), sim = tm.simMs();
            int wc = workerCount.get();
            long now = System.currentTimeMillis();
            if (now - lastScale < cfg.autoscaleCooldownMs) return;

            boolean healthy = render < 8.0 && sim < (1000.0 / (cfg.targetTps * 0.8));
            if (wc < cfg.maxWorkers && healthy && q > (4 * (wc + 1))) {
                setWorkerCount(wc + 1);
            } else if (wc > cfg.minWorkers && q == 0 && render > 12.0) {
                setWorkerCount(wc - 1);
            }
        }

        private void setWorkerCount(int target) {
            int cur = workerCount.get();
            if (target == cur) return;
            if (target > cur) {
                for (int i = cur; i < target; i++) {
                    Worker w = new Worker(this, i); workers.add(w); w.start();
                }
            } else {
                int stop = cur - target;
                for (int i = 0; i < stop; i++) { Worker w = workers.poll(); if (w != null) w.stopWorker(); }
            }
            workerCount.set(target);
            lastScale = System.currentTimeMillis();
            System.out.println("[JobSystem] Workers set to " + target);
        }

        ScheduledJob takeJob() throws InterruptedException { return queue.take(); }

        void executeJob(ScheduledJob sj) {
            long start = System.nanoTime();
            try { sj.job.run(); }
            finally { tm.sampleJobExec((System.nanoTime() - start) / 1_000_000.0); }
        }
    }

    static final class Worker extends Thread {
        private final JobSystem js; private volatile boolean running = true;
        Worker(JobSystem js, int idx) { super("Worker-" + idx); this.js = js; setDaemon(true); }
        void stopWorker() { running = false; interrupt(); }
        public void run() {
            while (running) {
                try { js.executeJob(js.takeJob()); }
                catch (InterruptedException ie) { if (!running) break; }
                catch (Throwable t) { t.printStackTrace(); }
            }
        }
    }

    static final class ScheduledJob {
        final Job job; final long order; private static final AtomicLong COUNTER = new AtomicLong();
        ScheduledJob(Job j) { job = j; order = COUNTER.getAndIncrement(); }
    }

    // ---------- world / chunks (mock pipeline) ----------
    static final class World {
        final JobSystem jobs;
        final ConcurrentLinkedQueue<MeshBlob> gpuUploads = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<ChunkPos, ChunkState> chunks = new ConcurrentHashMap<>();
        World(JobSystem j) { jobs = j; }

        void consumeInputs() { /* TODO */ }
        void tick(double dt) { /* TODO */ }

        void requestInitialChunks(int cx, int cz, int r) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++)
                scheduleChunk(new ChunkPos(cx + dx, cz + dz));
        }

        void scheduleChunk(ChunkPos pos) {
            chunks.compute(pos, (k, v) -> {
                if (v == null) v = new ChunkState();
                if (v.stage == Stage.UNLOADED) { v.stage = Stage.GENERATING; jobs.submit(new GenJob(this, pos)); }
                return v;
            });
        }

        // callbacks from jobs
        void onGenerated(ChunkPos pos, GeneratedData d) {
            chunks.compute(pos, (k, st) -> { if (st == null) st = new ChunkState();
                st.generated = d; st.stage = Stage.LIGHTING; jobs.submit(new LightJob(this, pos, d)); return st; });
        }
        void onLighted(ChunkPos pos, LightedData d) {
            chunks.compute(pos, (k, st) -> { if (st == null) st = new ChunkState();
                st.lighted = d; st.stage = Stage.MESHING; jobs.submit(new MeshJob(this, pos, d)); return st; });
        }
        void onMeshed(ChunkPos pos, MeshBlob m) {
            chunks.compute(pos, (k, st) -> { if (st == null) st = new ChunkState();
                st.mesh = m; st.stage = Stage.GPU_UPLOAD_PENDING; gpuUploads.add(m); st.stage = Stage.READY; return st; });
        }

        void processChunkPipelines() { /* jobs advance the pipeline via callbacks */ }
    }

    // simple records / states
    static final class ChunkPos {
        final int x, z; ChunkPos(int x, int z) { this.x = x; this.z = z; }
        public boolean equals(Object o){ return (o instanceof ChunkPos p) && p.x==x && p.z==z; }
        public int hashCode(){ return (x * 73471) ^ z; }
    }
    enum Stage { UNLOADED, GENERATING, LIGHTING, MESHING, GPU_UPLOAD_PENDING, READY }
    static final class ChunkState { Stage stage = Stage.UNLOADED; GeneratedData generated; LightedData lighted; MeshBlob mesh; }
    static final class GeneratedData { }
    static final class LightedData { }
    static final class MeshBlob { }

    // jobs (simulate work with Util.busy)
    static final class GenJob implements Job {
        private final World w; private final ChunkPos p; private final Random rng = new Random();
        GenJob(World w, ChunkPos p){ this.w=w; this.p=p; }
        public JobPriority priority(){ return JobPriority.P1_NEAR; }
        public void run(){ Util.busy(3.0 + rng.nextDouble()); w.onGenerated(p, new GeneratedData()); }
    }
    static final class LightJob implements Job {
        private final World w; private final ChunkPos p; private final GeneratedData d; private final Random rng = new Random();
        LightJob(World w, ChunkPos p, GeneratedData d){ this.w=w; this.p=p; this.d=d; }
        public JobPriority priority(){ return JobPriority.P0_CRITICAL; }
        public void run(){ Util.busy(2.0 + rng.nextDouble()); w.onLighted(p, new LightedData()); }
    }
    static final class MeshJob implements Job {
        private final World w; private final ChunkPos p; private final LightedData d; private final Random rng = new Random();
        MeshJob(World w, ChunkPos p, LightedData d){ this.w=w; this.p=p; this.d=d; }
        public JobPriority priority(){ return JobPriority.P0_CRITICAL; }
        public void run(){ Util.busy(2.0 + rng.nextDouble()); w.onMeshed(p, new MeshBlob()); }
    }

    // ---------- util ----------
    static final class Util {
        static void busy(double ms) {
            long ns = (long)(ms * 1_000_000);
            long start = System.nanoTime();
            while (System.nanoTime() - start < ns) Thread.onSpinWait();
        }
    }
}
