package render;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;

import engine.EngineConfig;
import engine.Telemetry;
import world.World;
import world.World.MeshBlob;
import engine.InputState;

import java.util.HashMap;
import java.util.Map;

import world.World.GpuUpload;
import world.World.ChunkPos;
import world.World.MeshBlob;


/**
 * Real renderer: opens a window, uploads MeshBlob objects to GL, draws them.
 * WASD + Space/Ctrl to move, hold Right Mouse Button to capture mouse for look.
 */
public class LwjglRenderer implements Renderer {
	private final InputState input;
    private final World world;
    
    private final Telemetry tm;
    private final EngineConfig cfg;

    private long window = 0;
    private boolean init = false;

    private final Map<ChunkPos, GLMesh> meshesByChunk = new HashMap<>();
    private Shader shader;
    private final Matrix4f vp = new Matrix4f();
    private int hiVao = 0, hiVbo = 0;

    public LwjglRenderer(World world, Telemetry tm, EngineConfig cfg, InputState input) {
    	this.world = world; this.tm = tm; this.cfg = cfg; this.input = input;
    }
    
    private void ensureHighlightBuffers() {
        if (hiVao != 0) return;
        hiVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
        org.lwjgl.opengl.GL30.glBindVertexArray(hiVao);
        hiVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, hiVbo);
        // 24 verts * (3 pos + 3 color) * 4 bytes = 576 bytes, but we’ll allow more
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 4096, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);

        int stride = 6 * Float.BYTES; // xyz rgb
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, stride, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);

        org.lwjgl.opengl.GL30.glBindVertexArray(0);
    }

    /** Draws a yellow wireframe box around the given block (world coords). */
    private void drawBlockOutline(int bx, int by, int bz) {
        ensureHighlightBuffers();

        // Build 12 edges (24 vertices). Offset by half-voxel to sit on the block boundary.
        final float x = bx, y = by, z = bz;
        final float x0 = x,     y0 = y,     z0 = z;
        final float x1 = x + 1, y1 = y + 1, z1 = z + 1;
        final float r=1f,g=0.9f,b=0f; // yellow

        float[] v = {
            // bottom rectangle
            x0,y0,z0, r,g,b,   x1,y0,z0, r,g,b,
            x1,y0,z0, r,g,b,   x1,y0,z1, r,g,b,
            x1,y0,z1, r,g,b,   x0,y0,z1, r,g,b,
            x0,y0,z1, r,g,b,   x0,y0,z0, r,g,b,

            // top rectangle
            x0,y1,z0, r,g,b,   x1,y1,z0, r,g,b,
            x1,y1,z0, r,g,b,   x1,y1,z1, r,g,b,
            x1,y1,z1, r,g,b,   x0,y1,z1, r,g,b,
            x0,y1,z1, r,g,b,   x0,y1,z0, r,g,b,

            // vertical edges
            x0,y0,z0, r,g,b,   x0,y1,z0, r,g,b,
            x1,y0,z0, r,g,b,   x1,y1,z0, r,g,b,
            x1,y0,z1, r,g,b,   x1,y1,z1, r,g,b,
            x0,y0,z1, r,g,b,   x0,y1,z1, r,g,b,
        };

        org.lwjgl.opengl.GL30.glBindVertexArray(hiVao);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, hiVbo);
        org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0, v);

        // Thick-ish lines so it’s visible
        org.lwjgl.opengl.GL11.glLineWidth(2.0f);
        org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_LINES, 0, v.length / 6);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);
    }

    @Override public void pollInput() {
        if (!init) return;
        glfwPollEvents();

        input.keyW = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        input.keyA = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        input.keyS = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        input.keyD = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        input.keySpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        input.keyCtrl  = glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;
        input.keyShift = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
        input.keyEsc   = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;

        // Always disable cursor while the window is focused; press ESC to request close.
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            glfwSetWindowShouldClose(window, true);
        }

        // Accumulate mouse delta since last frame (hidden cursor still moves virtually)
        double[] cx = new double[1], cy = new double[1];
        glfwGetCursorPos(window, cx, cy);
        if (!Double.isNaN(lastX)) {
            double dx = cx[0] - lastX;
            double dy = cy[0] - lastY;
            // Sensitivity: increase this if it feels sluggish (0.2–0.4 is typical)
            double sens = 0.25;
            input.addMouseDelta(dx * sens, dy * sens);
        }
        lastX = cx[0]; lastY = cy[0];


        boolean lNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT)  == GLFW_PRESS;
        boolean rNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;

        // rising edge only → one “click” per press
        if (lNow && !prevLmb) input.signalLeftClick();
        if (rNow && !prevRmb) input.signalRightClick();

        prevLmb = lNow;
        prevRmb = rNow;
    }
    
    private boolean prevLmb = false, prevRmb = false;
    private double lastX = Double.NaN, lastY = Double.NaN; // (you likely already have these)



    @Override
    public void drainGpuUploadQueue() {
        if (!init) return;
        int uploads = 0;
        GpuUpload up;
        while (uploads < 8 && (up = world.gpuUploads.poll()) != null) {
            // build GL mesh
            MeshBlob blob = up.mesh;
            GLMesh newMesh = new GLMesh(blob.vertices, blob.indices);

            // replace old one if present
            GLMesh old = meshesByChunk.put(up.pos, newMesh);
            if (old != null) old.destroy();

            uploads++;
        }
    }


    @Override public void cullAndRenderFrame() {
        // Lazy-init the window + GL context the first time this is called.
        if (!init) initWindowAndContext();

        // Basic render state
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.08f, 0.10f, 0.14f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Use our tiny shader (uVP = view-projection matrix)
        shader.use();

        // Get current framebuffer size to compute aspect ratio
        int[] wArr = new int[1], hArr = new int[1];
        glfwGetFramebufferSize(window, wArr, hArr);
        float aspect = Math.max(1, wArr[0]) / (float) Math.max(1, hArr[0]);

        // ------ Build the camera from the PLAYER (yaw/pitch/pos) ------
        // Forward from yaw/pitch (in degrees)
        float yaw   = world.player.yaw;
        float pitch = world.player.pitch;
        float cy = (float) Math.cos(Math.toRadians(yaw));
        float sy = (float) Math.sin(Math.toRadians(yaw));
        float cp = (float) Math.cos(Math.toRadians(pitch));
        float sp = (float) Math.sin(Math.toRadians(pitch));
        float fx = cy * cp, fy = sp, fz = sy * cp;

        // Eye and target (look-at)
        org.joml.Vector3f eye    = world.player.pos;
        org.joml.Vector3f center = new org.joml.Vector3f(eye.x + fx, eye.y + fy, eye.z + fz);

        // Projection (70° FOV) and view matrices
        org.joml.Matrix4f proj = new org.joml.Matrix4f()
                .perspective((float) Math.toRadians(70), aspect, 0.05f, 500f);
        org.joml.Matrix4f view = new org.joml.Matrix4f()
                .lookAt(eye, center, new org.joml.Vector3f(0, 1, 0));

        // VP = proj * view
        proj.mul(view, vp);

        // Upload uVP to the shader
        float[] vpArr = new float[16];
        vp.get(vpArr);
        glUniformMatrix4fv(shader.uVP, false, vpArr);
        
        // After setting the uVP uniform and before drawing meshes:
        for (GLMesh m : meshesByChunk.values()) {
            m.draw();
        }
        // Highlight targeted block (raycast from world; returns null if nothing hit)
        World.RayHit hit = world.raycast(world.player, 8.0f);
        if (hit != null) {
            // Depth test ON so the outline occludes properly behind blocks
            drawBlockOutline(hit.x, hit.y, hit.z);
        }

        // Present
        glfwSwapBuffers(window);
    }

    @Override
    public void shutdown() {
        if (!init) return;
        // destroy all chunk meshes
        for (GLMesh m : meshesByChunk.values()) m.destroy();
        meshesByChunk.clear();
        shader.destroy();
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
        init = false;
    }

    
    @Override
    public boolean shouldClose() {
        return init && glfwWindowShouldClose(window);
    }

    private void initWindowAndContext() {
        if (init) return;
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(1280, 720, "VoxelEngine (LWJGL)", NULL, NULL);
        if (window == 0) throw new RuntimeException("Failed to create window");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // vsync
        GL.createCapabilities();
        
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }

        shader = new Shader(VS_SRC, FS_SRC);
        init = true;
        System.out.println("[Renderer] Window + GL initialized");
    }

    private static final String VS_SRC =
        "#version 330 core\n" +
        "layout(location=0) in vec3 inPos;\n" +
        "layout(location=1) in vec3 inColor;\n" +
        "uniform mat4 uVP;\n" +
        "out vec3 vColor;\n" +
        "void main(){ vColor = inColor; gl_Position = uVP * vec4(inPos,1.0); }\n";

    private static final String FS_SRC =
        "#version 330 core\n" +
        "in vec3 vColor;\n" +
        "out vec4 fragColor;\n" +
        "void main(){ fragColor = vec4(vColor, 1.0); }\n";
}
