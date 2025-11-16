package render;
public interface Renderer {
	default void init() {} /** Called once on the render thread before the loop. */
    void pollInput(); /** Per-frame input collection (GLFW events, keys, mouse). */
    void drainGpuUploadQueue(); /** Apply pending GPU uploads (e.g., new/updated meshes). */
    void cullAndRenderFrame();  /** Build matrices, bind resources, and draw. */
    void shutdown();
    
    default boolean shouldClose() { return false; }
}
