package render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;

/** Simple 2D texture (RGBA8, nearest + mipmaps) without GL45 DSA. */
final class Texture2D {
    final int id;
    final int width, height;

    Texture2D(int w, int h) {
        this.width = w; this.height = h;
        this.id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);

        // allocate storage (null pointer) – we'll upload later
        glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        // classic MC look: nearest, plus mipmaps for distance
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void uploadRGBA8(byte[] pixels) {
        // OpenGL needs a *direct* NIO buffer
        ByteBuffer buf = BufferUtils.createByteBuffer(pixels.length);
        buf.put(pixels).flip();

        glBindTexture(GL_TEXTURE_2D, id);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    void destroy() { glDeleteTextures(id); }
}
