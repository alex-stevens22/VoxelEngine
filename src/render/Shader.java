package render;

import static org.lwjgl.opengl.GL20.*;

final class Shader {
    final int programId;
    final int uVP; // uniform location

    Shader(String vs, String fs) {
        int v = glCreateShader(GL_VERTEX_SHADER); glShaderSource(v, vs); glCompileShader(v);
        if (glGetShaderi(v, GL_COMPILE_STATUS) == 0) throw new RuntimeException("VS: " + glGetShaderInfoLog(v));
        int f = glCreateShader(GL_FRAGMENT_SHADER); glShaderSource(f, fs); glCompileShader(f);
        if (glGetShaderi(f, GL_COMPILE_STATUS) == 0) throw new RuntimeException("FS: " + glGetShaderInfoLog(f));

        programId = glCreateProgram();
        glAttachShader(programId, v); glAttachShader(programId, f);
        glBindAttribLocation(programId, 0, "inPos");
        glBindAttribLocation(programId, 1, "inColor");
        glLinkProgram(programId);
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) throw new RuntimeException("Link: " + glGetProgramInfoLog(programId));
        glDeleteShader(v); glDeleteShader(f);

        uVP = glGetUniformLocation(programId, "uVP");
    }

    void use() { glUseProgram(programId); }
    void destroy() { glDeleteProgram(programId); }
}
