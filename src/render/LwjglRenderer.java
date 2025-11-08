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

/**
 * Real renderer: opens a window, uploads MeshBlob objects to GL, draws them.
 * WASD + Space/Ctrl to move, hold Right Mouse Button to capture mouse for look.
 */
public class LwjglRenderer implements Renderer {
    private final World world;
    
    private final Telemetry tm;
    private final EngineConfig cfg;

    private long window = 0;
    private boolean init = false;

    private final List<GLMesh> meshes = new ArrayList<>();
    private Shader shader;
    private final Camera cam = new Camera();
    private final Matrix4f vp = new Matrix4f();

    public LwjglRenderer(World world, Telemetry tm, EngineConfig cfg) {
        this.world = world; this.tm = tm; this.cfg = cfg;
    }

    @Override public void pollInput() {
        if (!init) return;
        glfwPollEvents();
        float dt = (float)Math.max(0.0001, tm.renderMs()/1000.0);
        cam.updateInput(window, dt);
    }

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
        if (!init) initWindowAndContext();

        glEnable(GL_DEPTH_TEST);
        glClearColor(0.08f, 0.10f, 0.14f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        shader.use();

        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        float aspect = Math.max(1, w[0]) / (float)Math.max(1, h[0]);

        Matrix4f proj = cam.proj(aspect, new Matrix4f());
        Matrix4f view = cam.view(new Matrix4f());
        proj.mul(view, vp);

        float[] vpArr = new float[16];
        vp.get(vpArr);
        glUniformMatrix4fv(shader.uVP, false, vpArr);

        for (GLMesh m : meshes) m.draw();

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
