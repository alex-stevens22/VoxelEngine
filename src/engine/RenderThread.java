package engine;

import render.Renderer;

public final class RenderThread extends Thread {
    private final Renderer renderer;
    private final Telemetry tm;
    private volatile boolean running = true;

    public RenderThread(Renderer renderer, Telemetry tm) {
        this.renderer = renderer;
        this.tm = tm;
        setName("RenderThread");
    }

    public void requestStop() {
        running = false;
    }

    @Override
    public void run() {
        System.out.println("[RenderThread] start");
        try {
            System.out.println("[RenderThread] calling renderer.init()");
            renderer.init();
            System.out.println("[RenderThread] init OK, entering loop");

            // Render until either:
            //  - someone calls requestStop(), OR
            //  - the window asks to close.
            while (running) {
                renderer.pollInput();

                if (renderer.shouldClose()) {
                    System.out.println("[RenderThread] window requested close");
                    break;
                }

                renderer.drainGpuUploadQueue();
                renderer.cullAndRenderFrame();

                tm.markFrame();  // updates FPS
            }
        } finally {
            System.out.println("[RenderThread] calling renderer.shutdown()");
            renderer.shutdown();
            System.out.println("[RenderThread] shutdown done");
        }
    }
}
