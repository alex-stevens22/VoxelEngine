package render;
public interface Renderer {
    void pollInput();
    void drainGpuUploadQueue();
    void cullAndRenderFrame();  // first call may perform lazy init
    void shutdown();
}
