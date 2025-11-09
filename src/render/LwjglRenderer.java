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

    private final List<GLMesh> meshes = new ArrayList<>();
    private Shader shader;
    private final Matrix4f vp = new Matrix4f();

    public LwjglRenderer(World world, Telemetry tm, EngineConfig cfg, InputState input) {
    	this.world = world; this.tm = tm; this.cfg = cfg; this.input = input;
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

        // Mouse look: when RMB held, capture cursor and stream deltas
        boolean rmb = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;
        glfwSetInputMode(window, GLFW_CURSOR, rmb ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);

        // Accumulate mouse delta (since last frame)
        double[] cx=new double[1], cy=new double[1];
        glfwGetCursorPos(window, cx, cy);
        // Remember last across frames:
        if (!Double.isNaN(lastX)) {
            input.addMouseDelta(cx[0]-lastX, cy[0]-lastY);
        }
        lastX = cx[0]; lastY = cy[0];

        // Clicks (edge-triggered)
        if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS)  input.signalLeftClick();
        if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS) input.signalRightClick();
    }
    
    private double lastX=Double.NaN, lastY=Double.NaN;


    @Override public void drainGpuUploadQueue() {
        if (!init) return;
        int uploads = 0;
        MeshBlob blob;
        while (uploads < 4 && (blob = world.gpuUploads.poll()) != null) {
            meshes.add(new GLMesh(blob.vertices, blob.indices));
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

        // ------ Draw all uploaded meshes ------
        for (GLMesh m : meshes) {
            m.draw();
        }

        // Present
        glfwSwapBuffers(window);
    }

    @Override public void shutdown() {
        if (!init) return;
        for (GLMesh m : meshes) m.destroy();
        meshes.clear();
        shader.destroy();
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
        init = false;
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
