package jobs;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import engine.EngineConfig;
import engine.Telemetry;

/**
 * Priority job queue + worker threads + simple autoscaler.
 * Uses a synchronized PriorityQueue for clarity (fine for a prototype).
 */
public class JobSystem {
    private final EngineConfig cfg;
    private final Telemetry tm;

    private final Queue<ScheduledJob> queue = new PriorityQueue<>(
        Comparator.<ScheduledJob>comparingInt(sj -> sj.job.priority().ordinal())
                  .thenComparingLong(sj -> sj.order)
    );

    private final ConcurrentLinkedQueue<Worker> workers = new ConcurrentLinkedQueue<>();
    private final AtomicInteger workerCount = new AtomicInteger(0);
    private final ScheduledExecutorService scaler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AutoScaler"); t.setDaemon(true); return t; });
    private volatile long lastScale = 0;

    public JobSystem(EngineConfig c, Telemetry t) {
        this.cfg = c; this.tm = t;
        int target = Math.min(cfg.maxWorkers, Math.max(cfg.minWorkers, Runtime.getRuntime().availableProcessors() - 2));
        setWorkerCount(target);
        if (cfg.enableAutoscale) scaler.scheduleAtFixedRate(this::autoscaleTick, 1, 1, TimeUnit.SECONDS);
    }

    public void shutdown() { scaler.shutdownNow(); setWorkerCount(0); }
    public int currentWorkers() { return workerCount.get(); }

    public void submit(Job job) {
        synchronized (queue) { queue.offer(new ScheduledJob(job)); tm.setQueuedJobs(queue.size()); queue.notify(); }
    }

    public void runInlineFor(double budgetMs) {
        long budgetNs = (long)(budgetMs * 1_000_000);
        long start = System.nanoTime();
        for (;;) {
            if (System.nanoTime() - start >= budgetNs) break;
            ScheduledJob sj;
            synchronized (queue) { sj = queue.poll(); tm.setQueuedJobs(queue.size()); }
            if (sj == null) break;
            executeJob(sj);
        }
    }

    private void autoscaleTick() {
        int q; synchronized (queue) { q = queue.size(); }
        tm.setQueuedJobs(q);
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

    private ScheduledJob takeJob() throws InterruptedException {
        synchronized (queue) {
            while (queue.isEmpty()) queue.wait();
            tm.setQueuedJobs(queue.size());
            return queue.poll();
        }
    }

    private void executeJob(ScheduledJob sj) {
        try { sj.job.run(); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    private static final class Worker extends Thread {
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

    private static final class ScheduledJob {
        static final AtomicLong COUNTER = new AtomicLong();
        final Job job; final long order = COUNTER.getAndIncrement();
        ScheduledJob(Job j) { this.job = j; }
    }
}
