package world;

import org.joml.Vector3f;

/** Simple FPS-style player with AABB collision. */
public class Player {
    public final Vector3f pos = new Vector3f(0, 14, 0);   // start above ground
    public final Vector3f vel = new Vector3f();
    public float yaw = -90f, pitch = 0f;

    // Axis-aligned bounding box half-extents (player "capsule" approximated as a box)
    private static final float HALF_X = 0.3f, HALF_Y = 0.9f, HALF_Z = 0.3f;

    private static final float GRAVITY = 20f;
    private static final float JUMP_VELOCITY = 7.5f;
    private static final float MOVE_SPEED = 6.0f; // walking speed

    private boolean onGround = false;

    /** Update orientation from mouse deltas (degrees). */
    public void addLook(float dx, float dy) {
        float sens = 1.0f;
        yaw += dx * sens;
        pitch -= dy * sens;
        if (pitch > 89.9f) pitch = 89.9f;
        if (pitch < -89.9f) pitch = -89.9f;
    }

    /** Integrate physics, resolve collisions with solid voxels. */
    public void tick(World w, boolean fwd, boolean back, boolean left, boolean right, boolean up, boolean down, boolean sprint, boolean wantJump, float dt) {
        // Build desired horizontal movement from yaw
        float rad = (float)Math.toRadians(yaw);
        float fx = (float)Math.cos(rad), fz = (float)Math.sin(rad);
        // right vector
        float rx = -fz, rz = fx;

        Vector3f wish = new Vector3f();
        if (fwd)  wish.add( fx, 0,  fz);
        if (back) wish.add(-fx, 0, -fz);
        if (left) wish.add(-rx, 0, -rz);
        if (right)wish.add( rx, 0,  rz);
        float speed = sprint ? MOVE_SPEED * 1.5f : MOVE_SPEED;
        if (wish.lengthSquared() > 0) wish.normalize(speed);
        vel.x = wish.x;
        vel.z = wish.z;

        // Vertical
        if (up)   vel.y =  speed;        // noclip up (classic had fly; keep it simple via Space+Shift? We'll use Space to jump instead)
        if (down) vel.y = -speed;

        // Gravity (unless "fly" is held via up/down simultaneously; here we do normal gravity if not pressing up/down together)
        if (!up && !down) {
            vel.y -= GRAVITY * dt;
            // Jump
            if (wantJump && onGround) { vel.y = JUMP_VELOCITY; onGround = false; }
        }

        // Integrate and collide: resolve per-axis against solid voxels
        stepAndCollide(w, dt);
    }

    private void stepAndCollide(World w, float dt) {
        onGround = false;

        // X
        float nx = pos.x + vel.x * dt;
        if (!collides(w, nx, pos.y, pos.z)) pos.x = nx;
        else vel.x = 0;

        // Y
        float ny = pos.y + vel.y * dt;
        if (!collides(w, pos.x, ny, pos.z)) {
            pos.y = ny;
        } else {
            if (vel.y < 0) onGround = true;
            vel.y = 0;
        }

        // Z
        float nz = pos.z + vel.z * dt;
        if (!collides(w, pos.x, pos.y, nz)) pos.z = nz;
        else vel.z = 0;
    }

    private boolean collides(World w, float x, float y, float z) {
        // Check voxel AABBs overlapped by player box
        int minX = (int)Math.floor(x - HALF_X);
        int maxX = (int)Math.floor(x + HALF_X);
        int minY = (int)Math.floor(y - HALF_Y);
        int maxY = (int)Math.floor(y + HALF_Y);
        int minZ = (int)Math.floor(z - HALF_Z);
        int maxZ = (int)Math.floor(z + HALF_Z);

        for (int yy = minY; yy <= maxY; yy++)
            for (int zz = minZ; zz <= maxZ; zz++)
                for (int xx = minX; xx <= maxX; xx++)
                    if (w.isSolid(xx, yy, zz)) return true;
        return false;
    }
}
