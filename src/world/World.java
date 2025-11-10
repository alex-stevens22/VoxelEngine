package world;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import engine.InputState;
import jobs.Job;
import jobs.JobPriority;
import jobs.JobSystem;

/**
 * Voxel world with chunked storage, flat terrain init, block raycast/editing,
 * and meshing jobs (naive face culling).
 */
public class World {
    // ---- chunk config ----
    public static final int CHUNK_SIZE_X = 16;
    public static final int CHUNK_SIZE_Y = 128; // tall enough for classic feeling
    public static final int CHUNK_SIZE_Z = 16;

    // simple block id palette
    public static final byte AIR=0, GRASS=1, DIRT=2, STONE=3;

    // Ready-to-upload meshes consumed by renderer
    public final ConcurrentLinkedQueue<GpuUpload> gpuUploads = new ConcurrentLinkedQueue<>();
    
    public static final class GpuUpload {
        public final ChunkPos pos;
        public final MeshBlob mesh;
        public GpuUpload(ChunkPos pos, MeshBlob mesh) { this.pos = pos; this.mesh = mesh; }
    }


    private final ConcurrentHashMap<ChunkPos, Chunk> chunks = new ConcurrentHashMap<>();
    private final JobSystem jobs;
    private final InputState input;
    public final Player player = new Player();

    public World(JobSystem jobs, InputState input) {
        this.jobs = jobs; this.input = input;
        // seed a few chunks around origin
        requestInitialChunks(0,0,2);
    }

    // ---- per-tick hooks from SimulationThread ----
    public void consumeInputs() {
        // Mouse look
        //double[] d = input.consumeMouseDelta();
        //if (d[0] != 0 || d[1] != 0) player.addLook((float)d[0], (float)d[1]);
    }

    public void tick(double dt) {
        // movement
        boolean fwd = input.keyW, back = input.keyS, left = input.keyA, right = input.keyD;
        boolean up = false, down = false;
        boolean sprint = input.keyShift, jump = input.keySpace;
        player.tick(this, fwd, back, left, right, up, down, sprint, jump, (float)dt);

        // clicks: consume ONCE
        boolean leftClick  = input.consumeLeftClick();
        boolean rightClick = input.consumeRightClick();
        
        player.postSimTick();   // NEW: publish this tick’s position

        if (leftClick || rightClick) {
            // bump reach; see section 2 below
            RayHit hit = raycast(player, 10.0f);
            if (hit != null) {
                if (leftClick) {
                    setBlock(hit.x, hit.y, hit.z, AIR);
                    // System.out.println("Removed @ " + hit.x+","+hit.y+","+hit.z);
                } else { // rightClick
                    int px = hit.x + hit.nx, py = hit.y + hit.ny, pz = hit.z + hit.nz;
                    setBlock(px, py, pz, GRASS);
                    // System.out.println("Placed @ " + px+","+py+","+pz);
                }
            }
        }
    }


    public void processChunkPipelines() { /* meshing is done via jobs as edits happen */ }

    // ---- chunk management ----
    public void requestInitialChunks(int cx, int cz, int radius) {
        for (int dz=-radius; dz<=radius; dz++)
            for (int dx=-radius; dx<=radius; dx++)
                ensureChunk(cx+dx, cz+dz);
    }

    private Chunk ensureChunk(int cx, int cz) {
        return chunks.computeIfAbsent(new ChunkPos(cx,cz), key -> {
            Chunk c = new Chunk();
            flatInit(cx, cz, c);     // simple flat terrain
            // queue initial mesh
            jobs.submit(new MeshJob(this, key));
            return c;
        });
    }

    // flat world: y<12 stone, y==12 grass, 9..11 dirt
    private void flatInit(int cx, int cz, Chunk c) {
        for (int z=0; z<CHUNK_SIZE_Z; z++)
            for (int x=0; x<CHUNK_SIZE_X; x++) {
                for (int y=0; y<CHUNK_SIZE_Y; y++) {
                    byte id = AIR;
                    if (y < 9) id = STONE;
                    else if (y < 12) id = DIRT;
                    else if (y == 12) id = GRASS;
                    c.set(x,y,z,id);
                }
            }
    }

    // ---- voxel access (world coords) ----
    public boolean isSolid(int wx, int wy, int wz) {
        byte id = getBlock(wx, wy, wz);
        return id != AIR;
    }

    public byte getBlock(int wx, int wy, int wz) {
        int cx = floorDiv(wx, CHUNK_SIZE_X), cz = floorDiv(wz, CHUNK_SIZE_Z);
        int lx = floorMod(wx, CHUNK_SIZE_X), lz = floorMod(wz, CHUNK_SIZE_Z);
        if (wy < 0 || wy >= CHUNK_SIZE_Y) return AIR;
        Chunk ch = chunks.get(new ChunkPos(cx,cz));
        if (ch == null) return AIR;
        return ch.get(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, byte id) {
        int cx = floorDiv(wx, CHUNK_SIZE_X), cz = floorDiv(wz, CHUNK_SIZE_Z);
        int lx = floorMod(wx, CHUNK_SIZE_X), lz = floorMod(wz, CHUNK_SIZE_Z);
        if (wy < 0 || wy >= CHUNK_SIZE_Y) return;

        ChunkPos key = new ChunkPos(cx,cz);
        Chunk ch = ensureChunk(cx, cz);
        ch.set(lx, wy, lz, id);

        // re-mesh this chunk and any neighbor touched on border
        jobs.submit(new MeshJob(this, key));
        if (lx==0) jobs.submit(new MeshJob(this, new ChunkPos(cx-1,cz)));
        if (lx==CHUNK_SIZE_X-1) jobs.submit(new MeshJob(this, new ChunkPos(cx+1,cz)));
        if (lz==0) jobs.submit(new MeshJob(this, new ChunkPos(cx,cz-1)));
        if (lz==CHUNK_SIZE_Z-1) jobs.submit(new MeshJob(this, new ChunkPos(cx,cz+1)));
    }

    private static int floorDiv(int a, int b) { int q = a / b; int r = a % b; return (r<0)?(q-1):q; }
    private static int floorMod(int a, int b) { int r = a % b; return (r<0)?(r+b):r; }

    // ---- raycast (grid DDA) from player eye ----
    public static final class RayHit { public final int x,y,z, nx,ny,nz; RayHit(int x,int y,int z,int nx,int ny,int nz){ this.x=x; this.y=y; this.z=z; this.nx=nx; this.ny=ny; this.nz=nz; } }
    public RayHit raycast(Player p, float maxDist) {
        // Eye position slightly above center
        float ex = p.pos.x, ey = p.pos.y + 0.6f, ez = p.pos.z;
        // Forward direction from yaw/pitch
        float cy=(float)Math.cos(Math.toRadians(p.yaw)), sy=(float)Math.sin(Math.toRadians(p.yaw));
        float cp=(float)Math.cos(Math.toRadians(p.pitch)), sp=(float)Math.sin(Math.toRadians(p.pitch));
        float dx=cy*cp, dy=sp, dz=sy*cp;

        // DDA
        int cx = (int)Math.floor(ex), cyi = (int)Math.floor(ey), cz = (int)Math.floor(ez);
        int stepX = dx>0?1:-1, stepY = dy>0?1:-1, stepZ = dz>0?1:-1;
        float tDeltaX = (dx==0)?Float.MAX_VALUE:Math.abs(1f/dx);
        float tDeltaY = (dy==0)?Float.MAX_VALUE:Math.abs(1f/dy);
        float tDeltaZ = (dz==0)?Float.MAX_VALUE:Math.abs(1f/dz);
        float tMaxX = ((dx>0)?( (cx+1)-ex ):( ex-cx )) * tDeltaX;
        float tMaxY = ((dy>0)?( (cyi+1)-ey):( ey-cyi)) * tDeltaY;
        float tMaxZ = ((dz>0)?( (cz+1)-ez):( ez-cz)) * tDeltaZ;

        int nx=0, ny=0, nz=0;
        float t=0;
        while (t <= maxDist) {
            if (isSolid(cx,cyi,cz)) return new RayHit(cx,cyi,cz, -nx,-ny,-nz); // normal is opposite of step that entered
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { cx += stepX; t = tMaxX; tMaxX += tDeltaX; nx = stepX; ny = nz = 0; }
                else { cz += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nz = stepZ; nx = ny = 0; }
            } else {
                if (tMaxY < tMaxZ) { cyi += stepY; t = tMaxY; tMaxY += tDeltaY; ny = stepY; nx = nz = 0; }
                else { cz += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nz = stepZ; nx = ny = 0; }
            }
        }
        return null;
    }

    // ---- chunk + storage ----
    public static final class Chunk {
        // Flat byte array: x + z*SX + y*SX*SZ
        private final byte[] vox = new byte[CHUNK_SIZE_X * CHUNK_SIZE_Y * CHUNK_SIZE_Z];
        private static int idx(int x,int y,int z){ return x + z*CHUNK_SIZE_X + y*CHUNK_SIZE_X*CHUNK_SIZE_Z; }
        public byte get(int x,int y,int z){ return vox[idx(x,y,z)]; }
        public void set(int x,int y,int z, byte id){ vox[idx(x,y,z)] = id; }
    }
    public static final class ChunkPos {
        public final int x,z;
        public ChunkPos(int x,int z){ this.x=x; this.z=z; }
        @Override public boolean equals(Object o){ return (o instanceof ChunkPos p) && p.x==x && p.z==z; }
        @Override public int hashCode(){ return (x*73471) ^ z; }
    }

    // CPU-side geometry package. Vertex layout: xyz rgb (6 floats)
    public static final class MeshBlob {
        public final float[] vertices;
        public final int[] indices;
        public MeshBlob(float[] v, int[] i){ this.vertices=v; this.indices=i; }
    }

    // ---- meshing job (naive face culling) ----
    static final class MeshJob implements Job {
        private final World w; private final ChunkPos pos;
        MeshJob(World w, ChunkPos pos){ this.w=w; this.pos=pos; }
        public JobPriority priority(){ return JobPriority.P0_CRITICAL; }
        public void run(){
            Chunk c = w.chunks.get(pos);
            if (c == null) return;

            // build faces only where neighbor is air (including across chunk borders)
            FloatArray va = new FloatArray(32_000);
            IntArray ia = new IntArray(48_000);

            final int SX=CHUNK_SIZE_X, SY=CHUNK_SIZE_Y, SZ=CHUNK_SIZE_Z;
            int baseX = pos.x * SX, baseZ = pos.z * SZ;

            for (int y=0; y<SY; y++)
              for (int z=0; z<SZ; z++)
                for (int x=0; x<SX; x++) {
                    byte id = c.get(x,y,z);
                    if (id==AIR) continue;

                    // 6 directions
                    emitIfAir(w, baseX+x, y, baseZ+z,  1,0,0,  va,ia, colorFor(id)); // +X
                    emitIfAir(w, baseX+x, y, baseZ+z, -1,0,0,  va,ia, colorFor(id)); // -X
                    emitIfAir(w, baseX+x, y, baseZ+z,  0,1,0,  va,ia, colorFor(id)); // +Y
                    emitIfAir(w, baseX+x, y, baseZ+z,  0,-1,0, va,ia, colorFor(id)); // -Y
                    emitIfAir(w, baseX+x, y, baseZ+z,  0,0,1,  va,ia, colorFor(id)); // +Z
                    emitIfAir(w, baseX+x, y, baseZ+z,  0,0,-1, va,ia, colorFor(id)); // -Z
                }

            w.gpuUploads.add(new GpuUpload(pos, new MeshBlob(va.toArray(), ia.toArray())));
        }

        private float[] colorFor(byte id){
            if (id==GRASS) return new float[]{0.2f,0.8f,0.2f};
            if (id==DIRT)  return new float[]{0.5f,0.35f,0.2f};
            if (id==STONE) return new float[]{0.6f,0.6f,0.65f};
            return new float[]{1,1,1};
        }

        private void emitIfAir(World w, int wx,int wy,int wz, int nx,int ny,int nz, FloatArray va, IntArray ia, float[] col){
            int ax=wx+nx, ay=wy+ny, az=wz+nz;
            if (ay<0 || ay>=CHUNK_SIZE_Y || !w.isSolid(ax,ay,az)) {
                // Make a quad centered on the block face
                float x=wx+0.5f, y=wy+0.5f, z=wz+0.5f, s=0.5f;
                // Build 4 vertices for this face
                int base = va.size()/6;
                // Compute tangent vectors for face to get the rectangle corners
                // For axis-aligned faces, choose u,v vectors:
                float ux,uy,uz, vx,vy,vz;
                if (nx!=0){ ux=0;uy=1;uz=0;  vx=0;vy=0;vz=1; }      // X face
                else if (ny!=0){ ux=1;uy=0;uz=0; vx=0;vy=0;vz=1; }  // Y face
                else { ux=1;uy=0;uz=0; vx=0;vy=1;vz=0; }           // Z face
                float fx = x + nx*s, fy = y + ny*s, fz = z + nz*s;
                pushVertex(va, fx - ux*s - vx*s, fy - uy*s - vy*s, fz - uz*s - vz*s, col);
                pushVertex(va, fx + ux*s - vx*s, fy + uy*s - vy*s, fz + uz*s - vz*s, col);
                pushVertex(va, fx + ux*s + vx*s, fy + uy*s + vy*s, fz + uz*s + vz*s, col);
                pushVertex(va, fx - ux*s + vx*s, fy - uy*s + vy*s, fz - uz*s + vz*s, col);

                // Two triangles
                ia.add(base); ia.add(base+1); ia.add(base+2);
                ia.add(base); ia.add(base+2); ia.add(base+3);
            }
        }

        private void pushVertex(FloatArray va, float x,float y,float z, float[] c){
            va.add(x); va.add(y); va.add(z); va.add(c[0]); va.add(c[1]); va.add(c[2]);
        }
    }

    // simple dynamic arrays to avoid boxing/alloc storms in mesher
    static final class FloatArray {
        float[] a; int size=0; FloatArray(int cap){ a=new float[cap]; }
        void add(float v){ if(size>=a.length) grow(); a[size++]=v; }
        float[] toArray(){ float[] out=new float[size]; System.arraycopy(a,0,out,0,size); return out; }
        private void grow(){ float[] n=new float[a.length*2]; System.arraycopy(a,0,n,0,a.length); a=n; }
        int size(){ return size; }
    }
    static final class IntArray {
        int[] a; int size=0; IntArray(int cap){ a=new int[cap]; }
        void add(int v){ if(size>=a.length) grow(); a[size++]=v; }
        int[] toArray(){ int[] out=new int[size]; System.arraycopy(a,0,out,0,size); return out; }
        private void grow(){ int[] n=new int[a.length*2]; System.arraycopy(a,0,n,0,a.length); a=n; }
    }
}
