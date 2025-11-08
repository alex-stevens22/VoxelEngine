import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import org.lwjgl.opengl.GL;

public class LWJGLCheck {
    public static void main(String[] args) {
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");
        long window = glfwCreateWindow(800, 600, "LWJGL Test", 0, 0);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        while (!glfwWindowShouldClose(window)) {
            glClearColor(0.3f, 0.5f, 0.7f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glfwSwapBuffers(window);
            glfwPollEvents();
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
