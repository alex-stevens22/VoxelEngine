package render;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import engine.EngineConfig;
import engine.Telemetry;
import engine.InputState;
import world.World;
import world.World.ChunkPos;
import world.World.GpuUpload;
import world.World.MeshBlob;

/**
 * Renderer owns the GLFW window + GL context and draws the world.
 * Call init() exactly once on the render thread before the loop.
 * Each frame: pollInput → drainGpuUploadQueue → cullAndRenderFrame.
 * Call shutdown() once when the thread exits.
 */
public final class LwjglRenderer implements Renderer {

    // External references
    private final World world;
    private final Telemetry tm;
    private final EngineConfig cfg;

    // Window/GL
    private long window = 0L;
    // private boolean glReady = false;

    // Shaders/uniforms
    private Shader shader;
    private int uVP = -1;
    private final Matrix4f vp = new Matrix4f();

    // Texture atlas
    TextureAtlas atlas;

    // Uploaded meshes by chunk
    private final Map<ChunkPos, GLMesh> meshesByChunk = new HashMap<>();

    // Input helpers (render-thread)
    private double lastX = Double.NaN, lastY = Double.NaN;
    private boolean prevLmb = false, prevRmb = false;

    // One-time debug print
    private boolean debugPrintedBind = false;

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    // New constructor (used going forward)
    public LwjglRenderer(World world, Telemetry tm, EngineConfig cfg) {
        this.world = world;
        this.tm = tm;
        this.cfg = cfg;
    }

    // Compatibility constructor for existing VoxelEngine call:
    // new LwjglRenderer(world, telemetry, config, inputState)
    public LwjglRenderer(World world, Telemetry tm, EngineConfig cfg, InputState input) {
        this(world, tm, cfg); // we now read world.input instead of a separate InputState
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void init() {
//    	if (glReady) {
//            System.out.println("[LwjglRenderer] init() called but glReady already true");
//            return;
//        }

        System.out.println("[LwjglRenderer] init: starting GLFW");

        glfwSetErrorCallback(org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err));
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }

        int w = 1280, h = 720;
        window = glfwCreateWindow(w, h, "VoxelEngine", 0L, 0L);
        System.out.println("[LwjglRenderer] window handle = " + window);
        if (window == 0L) throw new IllegalStateException("glfwCreateWindow failed");

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1);
        
       // if (glReady) return;

        // Surface GLFW errors to console
        glfwSetErrorCallback(org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err));

        if (!glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }

        // OpenGL 3.3 core
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        // Hide cursor + enable raw mouse if available
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }

        // Base GL state
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.08f, 0.10f, 0.14f, 1f);

        // Build shader
        shader = new Shader(VS_SRC, FS_SRC);
        glUseProgram(shader.programId);
        uVP = glGetUniformLocation(shader.programId, "uVP");
        int uTex = glGetUniformLocation(shader.programId, "uTex");
        glUniform1i(uTex, 0); // bind sampler to texture unit 0

        // Build atlas AFTER GL is ready
        System.out.println("Building atlas…");
        String[] atlasTiles = { "grass_top", "grass_side", "dirt", "stone", "missing" };
        atlas = new TextureAtlas(atlasTiles);

        // Give world a UV provider for meshing
        world.setUVProvider(name -> {
            TextureAtlas.Region r = atlas.region(name);
            return new float[]{ r.u0, r.v0, r.u1, r.v1 };
        });
        System.out.println("Atlas OK");

        // Optional GL debug
        if (org.lwjgl.opengl.GL.getCapabilities().GL_KHR_debug) {
            org.lwjgl.opengl.GL43.glEnable(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT);
            org.lwjgl.opengl.GL43.glDebugMessageCallback((src, type, id, sev, len, msg, user) -> {
                System.err.println("[GL] " + org.lwjgl.opengl.GLDebugMessageCallback.getMessage(len, msg));
            }, 0L);
        }

       // glReady = true;
    }

    @Override
    public void shutdown() {
        //if (!glReady) return;

        // Destroy meshes
        for (GLMesh m : meshesByChunk.values()) {
            try { m.destroy(); } catch (Throwable ignored) {}
        }
        meshesByChunk.clear();

        // Destroy atlas + shader
        try { if (atlas != null && atlas.glTex != null) atlas.glTex.destroy(); } catch (Throwable ignored) {}
        try { if (shader != null) shader.destroy(); } catch (Throwable ignored) {}

        // Window/GLFW
        if (window != 0L) {
            try {
                glfwDestroyWindow(window);
            } finally {
                window = 0L;
                glfwTerminate();
            }
        }
    }

    @Override
    public boolean shouldClose() {
        return window != 0L && glfwWindowShouldClose(window);
    }

    // ---------------------------------------------------------------------
    // Per-frame
    // ---------------------------------------------------------------------

    @Override
    public void pollInput() {
        if (window == 0L) return;

        glfwPollEvents();

        // Close on Esc
        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
            glfwSetWindowShouldClose(window, true);
        }

        // Keyboard → movement flags (consumed by SimulationThread)
        var in = world.input;
        in.keyW     = glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS;
        in.keyS     = glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS;
        in.keyA     = glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS;
        in.keyD     = glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS;
        in.keySpace = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        in.keyShift = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
                   || glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;

        // Mouse look: per-frame on render thread
        double[] cx = new double[1], cy = new double[1];
        glfwGetCursorPos(window, cx, cy);
        if (!Double.isNaN(lastX)) {
            double dx = cx[0] - lastX;
            double dy = cy[0] - lastY;
            double sens = 0.30; // tune 0.2–0.4
            world.player.addLook((float)(dx * sens), (float)(dy * sens));
        }
        lastX = cx[0]; lastY = cy[0];

        // Mouse buttons: edge-triggered
        boolean lNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT)  == GLFW_PRESS;
        boolean rNow = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;
        if (lNow && !prevLmb) world.input.signalLeftClick();
        if (rNow && !prevRmb) world.input.signalRightClick();
        prevLmb = lNow; prevRmb = rNow;
    }

    @Override
    public void drainGpuUploadQueue() {
        if (window == 0L) return;

        int uploads = 0;
        GpuUpload up;
        while (uploads < 8 && (up = world.gpuUploads.poll()) != null) {
            MeshBlob blob = up.mesh;
            GLMesh newMesh = new GLMesh(blob.vertices, blob.indices);

            GLMesh old = meshesByChunk.put(up.pos, newMesh);
            if (old != null) old.destroy();

            uploads++;
        }
    }

    @Override
    public void cullAndRenderFrame() {
//    	if (!glReady) {
//            System.out.println("[LwjglRenderer] cullAndRenderFrame called before glReady");
//            return;
//        }
    	System.out.println("[LwjglRenderer] cullAndRenderFrame");

        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Framebuffer size → aspect
        int[] wArr = new int[1], hArr = new int[1];
        glfwGetFramebufferSize(window, wArr, hArr);
        float aspect = Math.max(1, wArr[0]) / (float)Math.max(1, hArr[0]);

        // Camera: yaw/pitch every frame; position = interpolated between 20 TPS ticks
        float yaw = world.player.yaw;
        float pitch = world.player.pitch;
        float cy = (float)Math.cos(Math.toRadians(yaw));
        float sy = (float)Math.sin(Math.toRadians(yaw));
        float cp = (float)Math.cos(Math.toRadians(pitch));
        float sp = (float)Math.sin(Math.toRadians(pitch));
        float fx = cy*cp, fy = sp, fz = sy*cp;

        float alpha = (float) tm.interpAlpha();
        Vector3f p0 = world.player.prevPos;
        Vector3f p1 = world.player.currPos;
        float ex = p0.x + (p1.x - p0.x) * alpha;
        float ey = p0.y + (p1.y - p0.y) * alpha;
        float ez = p0.z + (p1.z - p0.z) * alpha;

        Vector3f eye    = new Vector3f(ex, ey, ez);
        Vector3f center = new Vector3f(ex + fx, ey + fy, ez + fz);

        Matrix4f proj = new Matrix4f().perspective((float)Math.toRadians(70), aspect, 0.05f, 500f);
        Matrix4f view = new Matrix4f().lookAt(eye, center, new Vector3f(0,1,0));
        proj.mul(view, vp);

        // Upload uVP
        glUseProgram(shader.programId);
        FloatBuffer fb = BufferUtils.createFloatBuffer(16);
        vp.get(fb);
        glUniformMatrix4fv(uVP, false, fb);

        // Bind atlas once (unit 0)
        if (!debugPrintedBind) { System.out.println("Binding atlas"); debugPrintedBind = true; }
        if (atlas != null) atlas.glTex.bind(0);

        // Draw all chunk meshes
        for (GLMesh m : meshesByChunk.values()) {
            m.draw();
        }

        glfwSwapBuffers(window);
    }

    // ---------------------------------------------------------------------
    // Shader (helper)
    // ---------------------------------------------------------------------

    private static final String VS_SRC =
        "#version 330 core\n" +
        "layout(location=0) in vec3 inPos;\n" +
        "layout(location=1) in vec3 inColor;\n" +
        "layout(location=2) in vec2 inUV;\n" +
        "uniform mat4 uVP;\n" +
        "out vec3 vColor;\n" +
        "out vec2 vUV;\n" +
        "void main(){ vColor=inColor; vUV=inUV; gl_Position = uVP * vec4(inPos,1.0); }\n";

    private static final String FS_SRC =
        "#version 330 core\n" +
        "in vec3 vColor;\n" +
        "in vec2 vUV;\n" +
        "uniform sampler2D uTex;\n" +
        "out vec4 fragColor;\n" +
        "void main(){ vec4 tex = texture(uTex, vUV); fragColor = tex * vec4(vColor,1.0); }\n";

    private static final class Shader {
        final int programId;
        Shader(String vsSrc, String fsSrc) {
            int vs = glCreateShader(GL_VERTEX_SHADER);
            glShaderSource(vs, vsSrc);
            glCompileShader(vs);
            if (glGetShaderi(vs, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new IllegalStateException("VS compile failed: " + glGetShaderInfoLog(vs));
            }
            int fs = glCreateShader(GL_FRAGMENT_SHADER);
            glShaderSource(fs, fsSrc);
            glCompileShader(fs);
            if (glGetShaderi(fs, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new IllegalStateException("FS compile failed: " + glGetShaderInfoLog(fs));
            }
            programId = glCreateProgram();
            glAttachShader(programId, vs);
            glAttachShader(programId, fs);
            glLinkProgram(programId);
            if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Program link failed: " + glGetProgramInfoLog(programId));
            }
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
        void destroy() { glDeleteProgram(programId); }
    }
}
