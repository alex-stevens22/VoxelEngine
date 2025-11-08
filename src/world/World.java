package world;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import jobs.Job;
import jobs.JobPriority;
import jobs.JobSystem;

/**
 * World manages chunk pipelines and produces MeshBlob objects for the renderer.
 */
public class World {
    public final ConcurrentLinkedQueue<MeshBlob> gpuUploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<ChunkPos, ChunkState> chunks = new ConcurrentHashMap<>();
    private final JobSystem jobs;

    public World(JobSystem jobs) { this.jobs = jobs; }

    public void consumeInputs() {}
    public void tick(double dt) {}
    public void processChunkPipelines() {}

    public void requestInitialChunks(int cx, int cz, int radius) {
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++)
                scheduleChunk(new ChunkPos(cx + dx, cz + dz));
    }

    private void scheduleChunk(ChunkPos pos) {
        chunks.compute(pos, (k, v) -> {
            if (v == null) v = new ChunkState();
            if (v.stage == Stage.UNLOADED) {
                v.stage = Stage.GENERATING;
                jobs.submit(new GenJob(this, pos));
            }
            return v;
        });
    }

    void onGenerated(ChunkPos pos, GeneratedData data) {
        chunks.compute(pos, (k, st) -> {
            if (st == null) st = new ChunkState();
            st.generated = data; st.stage = Stage.LIGHTING;
            jobs.submit(new LightJob(this, pos, data));
            return st;
        });
    }

    void onLighted(ChunkPos pos, LightedData data) {
        chunks.compute(pos, (k, st) -> {
            if (st == null) st = new ChunkState();
            st.lighted = data; st.stage = Stage.MESHING;
            jobs.submit(new MeshJob(this, pos, data));
            return st;
        });
    }

    void onMeshed(ChunkPos pos, MeshBlob mesh) {
        chunks.compute(pos, (k, st) -> {
            if (st == null) st = new ChunkState();
            st.mesh = mesh; st.stage = Stage.READY;
            gpuUploads.add(mesh);
            return st;
        });
    }

    // ==== data types ====
    public static final class ChunkPos {
        public final int x, z;
        public ChunkPos(int x, int z) { this.x = x; this.z = z; }
        @Override public boolean equals(Object o){ return (o instanceof ChunkPos p) && p.x==x && p.z==z; }
        @Override public int hashCode(){ return (x * 73471) ^ z; }
    }
    public enum Stage { UNLOADED, GENERATING, LIGHTING, MESHING, READY }
    public static final class ChunkState { public Stage stage = Stage.UNLOADED; public GeneratedData generated; public LightedData lighted; public MeshBlob mesh; }
    public static final class GeneratedData {}
    public static final class LightedData {}

    /** CPU-side geometry package. Vertex layout: xyz rgb (6 floats per vertex). */
    public static final class MeshBlob {
        public final float[] vertices;
        public final int[] indices;
        public MeshBlob(float[] v, int[] i) { this.vertices = v; this.indices = i; }
    }

    // ==== jobs ====
    static final class GenJob implements Job {
        private final World w; private final ChunkPos p;
        GenJob(World w, ChunkPos p){ this.w=w; this.p=p; }
        public JobPriority priority(){ return JobPriority.P1_NEAR; }
        public void run(){ busy(0.5); w.onGenerated(p,new GeneratedData()); }
    }
    static final class LightJob implements Job {
        private final World w; private final ChunkPos p; private final GeneratedData d;
        LightJob(World w, ChunkPos p, GeneratedData d){ this.w=w; this.p=p; this.d=d; }
        public JobPriority priority(){ return JobPriority.P0_CRITICAL; }
        public void run(){ busy(0.2); w.onLighted(p,new LightedData()); }
    }
    static final class MeshJob implements Job {
        private final World w; private final ChunkPos p; private final LightedData d; private final Random rng = new Random();
        MeshJob(World w, ChunkPos p, LightedData d){ this.w=w; this.p=p; this.d=d; }
        public JobPriority priority(){ return JobPriority.P0_CRITICAL; }
        public void run(){
            busy(0.2);
            // Simple colored cube at (cx,0,cz) so we can see something.
            float cx=p.x*2.2f, cy=0f, cz=p.z*2.2f, s=1f, c=0.4f+0.6f*rng.nextFloat();
            float[] v={
                // +X
                cx+s,cy-s,cz-s, c,0,0,  cx+s,cy+s,cz-s, c,0,0,  cx+s,cy+s,cz+s, c,0,0,  cx+s,cy-s,cz+s, c,0,0,
                // -X
                cx-s,cy-s,cz+s, 0,c,0,  cx-s,cy+s,cz+s, 0,c,0,  cx-s,cy+s,cz-s, 0,c,0,  cx-s,cy-s,cz-s, 0,c,0,
                // +Y
                cx-s,cy+s,cz-s, 0,0,c,  cx-s,cy+s,cz+s, 0,0,c,  cx+s,cy+s,cz+s, 0,0,c,  cx+s,cy+s,cz-s, 0,0,c,
                // -Y
                cx-s,cy-s,cz+s, c,c,0,  cx-s,cy-s,cz-s, c,c,0,  cx+s,cy-s,cz-s, c,c,0,  cx+s,cy-s,cz+s, c,c,0,
                // +Z
                cx+s,cy-s,cz+s, 0,c,c,  cx+s,cy+s,cz+s, 0,c,c,  cx-s,cy+s,cz+s, 0,c,c,  cx-s,cy-s,cz+s, 0,c,c,
                // -Z
                cx-s,cy-s,cz-s, c,0,c,  cx-s,cy+s,cz-s, c,0,c,  cx+s,cy+s,cz-s, c,0,c,  cx+s,cy-s,cz-s, c,0,c
            };
            int[] idx=new int[36]; int vi=0,ii=0;
            for(int f=0;f<6;f++){ idx[ii++]=vi; idx[ii++]=vi+1; idx[ii++]=vi+2; idx[ii++]=vi; idx[ii++]=vi+2; idx[ii++]=vi+3; vi+=4; }
            w.onMeshed(p,new MeshBlob(v,idx));
        }
    }

    private static void busy(double ms){
        long ns=(long)(ms*1_000_000), start=System.nanoTime();
        while(System.nanoTime()-start<ns) Thread.onSpinWait();
    }
}
