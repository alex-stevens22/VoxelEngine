package render;

import static org.lwjgl.glfw.GLFW.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class Camera {
    private final Vector3f pos = new Vector3f(0, 3, 6);
    private float yaw = -90f, pitch = -10f;
    private double lastX = Double.NaN, lastY = Double.NaN;
    private boolean captured = false;

    Matrix4f view(Matrix4f out) {
        Vector3f f = front(), center = new Vector3f(pos).add(f);
        return out.identity().lookAt(pos, center, new Vector3f(0,1,0));
    }
    Matrix4f proj(float aspect, Matrix4f out) { return out.identity().perspective((float)Math.toRadians(70), aspect, 0.05f, 500f); }

    void toggleCapture(long win) {
        captured = !captured;
        glfwSetInputMode(win, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        lastX = lastY = Double.NaN;
    }

    void updateInput(long win, float dt) {
        float speed = (glfwGetKey(win, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) ? 10f : 5f;
        Vector3f f = front(), r = right();
        if (glfwGetKey(win, GLFW_KEY_W) == GLFW_PRESS) pos.fma(speed*dt, f);
        if (glfwGetKey(win, GLFW_KEY_S) == GLFW_PRESS) pos.fma(-speed*dt, f);
        if (glfwGetKey(win, GLFW_KEY_A) == GLFW_PRESS) pos.fma(-speed*dt, r);
        if (glfwGetKey(win, GLFW_KEY_D) == GLFW_PRESS) pos.fma(speed*dt, r);
        if (glfwGetKey(win, GLFW_KEY_SPACE) == GLFW_PRESS) pos.y += speed*dt;
        if (glfwGetKey(win, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) pos.y -= speed*dt;

        if (glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS && !captured) toggleCapture(win);
        if (glfwGetMouseButton(win, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_RELEASE && captured) toggleCapture(win);

        double[] cx = new double[1], cy = new double[1];
        glfwGetCursorPos(win, cx, cy);
        if (!Double.isNaN(lastX)) {
            float dx = (float)(cx[0] - lastX), dy = (float)(cy[0] - lastY);
            float sens = 0.1f; yaw += dx * sens; pitch -= dy * sens;
            pitch = Math.max(-89.9f, Math.min(89.9f, pitch));
        }
        lastX = cx[0]; lastY = cy[0];
    }

    private Vector3f front() {
        float cy = (float)Math.cos(Math.toRadians(yaw)), sy = (float)Math.sin(Math.toRadians(yaw));
        float cp = (float)Math.cos(Math.toRadians(pitch)), sp = (float)Math.sin(Math.toRadians(pitch));
        return new Vector3f(cy*cp, sp, sy*cp).normalize();
    }
    private Vector3f right() { Vector3f f = front(); return new Vector3f(f).cross(0,1,0).normalize(); }
}
