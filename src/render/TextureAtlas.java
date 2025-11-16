package render;

import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.system.MemoryStack;

public final class TextureAtlas {
    public static final class Region {
        public final float u0,v0,u1,v1;
        Region(float u0,float v0,float u1,float v1){ this.u0=u0; this.v0=v0; this.u1=u1; this.v1=v1; }
        
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();
    public final Texture2D glTex;

    /**
     * Loads 16x16 tiles by name, packs into an N×N grid atlas, uploads to GL.
     * @param tileNames ordered names to pack (deterministic UVs)
     */
    public TextureAtlas(String[] tileNames) {
    	
    	System.out.println(
    		    openResource("assets/textures/block/grass_top.png") != null ? "FOUND" : "NOT FOUND"
    		);
    	
        // Load all tiles (RGBA8)
        final int tileW = 16, tileH = 16;
        byte[][] tiles = new byte[tileNames.length][];
        for (int i=0;i<tileNames.length;i++) tiles[i] = loadPngRGBA("assets/textures/block/" + tileNames[i] + ".png", 16, 16);


        // Decide atlas side: ceil(sqrt(n)) tiles per side
        int sideTiles = (int)Math.ceil(Math.sqrt(tileNames.length));
        int atlasW = sideTiles * tileW, atlasH = sideTiles * tileH;
        byte[] atlas = new byte[atlasW * atlasH * 4];

        // Blit tiles into atlas and record UVs
        for (int i=0;i<tileNames.length;i++){
            int tx = (i % sideTiles) * tileW;
            int ty = (i / sideTiles) * tileH;
            blit(atlas, atlasW, atlasH, tiles[i], tileW, tileH, tx, ty);
            float u0 = tx / (float)atlasW, v0 = ty / (float)atlasH;
            float u1 = (tx+tileW) / (float)atlasW, v1 = (ty+tileH) / (float)atlasH;
            regions.put(tileNames[i], new Region(u0,v0,u1,v1));
        }

        // Upload
        glTex = new Texture2D(atlasW, atlasH);
        glTex.uploadRGBA8(atlas);
    }

    public Region region(String name) {
        Region r = regions.get(name);
        if (r == null) r = regions.getOrDefault("missing", new Region(0,0,1,1));
        return r;
    }

    private static void blit(byte[] dst, int dw, int dh, byte[] src, int sw, int sh, int dx, int dy) {
        for (int y=0;y<sh;y++){
            int di = ((dy+y) * dw + (dx)) * 4;
            int si = (y * sw) * 4;
            System.arraycopy(src, si, dst, di, sw*4);
            
        }
    }

    private static byte[] loadPngRGBA(String resourcePath, int expectW, int expectH) {
        try (InputStream in = openResource(resourcePath)) {
            if (in == null) throw new RuntimeException("Missing texture: " + resourcePath);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length).put(bytes);
            buf.flip();
            try (MemoryStack s = stackPush()) {
                var wx = s.mallocInt(1);
                var hx = s.mallocInt(1);
                var cx = s.mallocInt(1);
                stbi_set_flip_vertically_on_load(false);
                ByteBuffer img = stbi_load_from_memory(buf, wx, hx, cx, 4);
                if (img == null) throw new RuntimeException("STB failed: " + stbi_failure_reason());
                int w = wx.get(0), h = hx.get(0);
                if (w!=expectW || h!=expectH) {
                    stbi_image_free(img);
                    throw new RuntimeException("Unexpected tile size for " + resourcePath + ": " + w + "x" + h);
                }
                byte[] out = new byte[w*h*4];
                img.get(out);
                stbi_image_free(img);
                return out;
            }
        } catch (IOException e) {
            throw new RuntimeException("Load failed: " + resourcePath, e);
        }
    }

    private static InputStream openResource(String path) {
        ClassLoader cl = TextureAtlas.class.getClassLoader();

        InputStream in = cl.getResourceAsStream(path);               // e.g. "textures/block/grass_top.png"
        if (in != null) return in;

        in = cl.getResourceAsStream("assets/" + path);               // fallback if assets/ not source folder
        if (in != null) return in;

        try { return new java.io.FileInputStream(path); } catch (IOException ignored) {}
        try { return new java.io.FileInputStream("assets/" + path); } catch (IOException ignored) {}
        return null;
    }
}
