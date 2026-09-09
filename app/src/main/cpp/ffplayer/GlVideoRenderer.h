#pragma once
#include "PlayerCommon.h"

// ─── OpenGL ES Shader-Based Video Renderer ───────────────────────────────────

class GlVideoRenderer {
private:
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLContext eglContext = EGL_NO_CONTEXT;
    ANativeWindow* boundWindow = nullptr;

    GLuint program = 0;
    GLuint texY = 0;
    GLuint texU = 0;
    GLuint texV = 0;
    int texWidth = 0;
    int texHeight = 0;
    int texIs10Bit = -1;

    GLint locTexY = -1;
    GLint locTexU = -1;
    GLint locTexV = -1;
    GLint locIs10Bit = -1;
    GLint locColorStd = -1;
    GLint locHdrTransfer = -1;
    GLint locForceSdr = -1;

    static GLuint compileShader(GLenum type, const char* src) {
        GLuint shader = glCreateShader(type);
        if (!shader) return 0;
        glShaderSource(shader, 1, &src, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen > 0) {
                std::vector<char> infoLog(infoLen);
                glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
                LOGE("GlVideoRenderer: Shader compilation failed: %s", infoLog.data());
            }
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    void initShaders() {
        const char* vShaderSrc =
            "#version 300 es\n"
            "layout(location = 0) in vec4 a_position;\n"
            "layout(location = 1) in vec2 a_texCoord;\n"
            "out vec2 v_texCoord;\n"
            "void main() {\n"
            "    gl_Position = a_position;\n"
            "    v_texCoord = a_texCoord;\n"
            "}\n";

        const char* fShaderSrc =
            "#version 300 es\n"
            "precision highp float;\n"
            "precision highp int;\n"
            "\n"
            "in vec2 v_texCoord;\n"
            "out vec4 fragColor;\n"
            "\n"
            "uniform sampler2D u_texY;\n"
            "uniform sampler2D u_texU;\n"
            "uniform sampler2D u_texV;\n"
            "\n"
            "uniform int u_is10Bit;\n"
            "uniform int u_colorStd;\n"
            "uniform int u_hdrTransfer;\n"
            "uniform int u_forceSdr;\n"
            "\n"
            "vec3 hableCurve(vec3 x) {\n"
            "    const float A = 0.15;\n"
            "    const float B = 0.50;\n"
            "    const float C = 0.10;\n"
            "    const float D = 0.20;\n"
            "    const float E = 0.02;\n"
            "    const float F = 0.30;\n"
            "    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - (E / F);\n"
            "}\n"
            "\n"
            "vec3 pqToLinear(vec3 N) {\n"
            "    const float m1 = 2610.0 / 16384.0;\n"
            "    const float m2 = (2523.0 / 4096.0) * 128.0;\n"
            "    const float c1 = 3424.0 / 4096.0;\n"
            "    const float c2 = (2413.0 / 4096.0) * 32.0;\n"
            "    const float c3 = (2392.0 / 4096.0) * 32.0;\n"
            "    vec3 N_inv_m2 = pow(clamp(N, 0.0, 1.0), vec3(1.0 / m2));\n"
            "    vec3 num = max(N_inv_m2 - c1, vec3(0.0));\n"
            "    vec3 den = c2 - c3 * N_inv_m2;\n"
            "    return pow(max(num / den, vec3(0.0)), vec3(1.0 / m1));\n"
            "}\n"
            "\n"
            "vec3 hlgToLinear(vec3 N) {\n"
            "    vec3 L;\n"
            "    for (int i = 0; i < 3; i++) {\n"
            "        float n = clamp(N[i], 0.0, 1.0);\n"
            "        if (n <= 0.5) {\n"
            "            L[i] = (n * n) / 3.0;\n"
            "        } else {\n"
            "            L[i] = (exp((n - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;\n"
            "        }\n"
            "    }\n"
            "    return L;\n"
            "}\n"
            "\n"
            "void main() {\n"
            "    float y, u, v;\n"
            "    if (u_is10Bit == 1) {\n"
            "        vec4 py = texture(u_texY, v_texCoord);\n"
            "        vec4 pu = texture(u_texU, v_texCoord);\n"
            "        vec4 pv = texture(u_texV, v_texCoord);\n"
            "        y = (py.r + py.a * 256.0) * (255.0 / 1023.0);\n"
            "        u = (pu.r + pu.a * 256.0) * (255.0 / 1023.0);\n"
            "        v = (pv.r + pv.a * 256.0) * (255.0 / 1023.0);\n"
            "    } else {\n"
            "        y = texture(u_texY, v_texCoord).r;\n"
            "        u = texture(u_texU, v_texCoord).r;\n"
            "        v = texture(u_texV, v_texCoord).r;\n"
            "    }\n"
            "\n"
            "    y = clamp((y - (16.0 / 255.0)) * (255.0 / (235.0 - 16.0)), 0.0, 1.0);\n"
            "    u = u - 0.5;\n"
            "    v = v - 0.5;\n"
            "\n"
            "    vec3 rgb;\n"
            "    if (u_colorStd == 2) {\n"
            "        rgb.r = y + 1.47460 * v;\n"
            "        rgb.g = y - 0.16455 * u - 0.57135 * v;\n"
            "        rgb.b = y + 1.88140 * u;\n"
            "    } else if (u_colorStd == 1) {\n"
            "        rgb.r = y + 1.57480 * v;\n"
            "        rgb.g = y - 0.18732 * u - 0.46812 * v;\n"
            "        rgb.b = y + 1.85560 * u;\n"
            "    } else {\n"
            "        rgb.r = y + 1.40200 * v;\n"
            "        rgb.g = y - 0.34414 * u - 0.71414 * v;\n"
            "        rgb.b = y + 1.77200 * u;\n"
            "    }\n"
            "    rgb = clamp(rgb, 0.0, 1.0);\n"
            "\n"
            "    if (u_hdrTransfer == 1 || u_hdrTransfer == 2 || u_forceSdr == 1) {\n"
            "        vec3 lin;\n"
            "        if (u_hdrTransfer == 2) {\n"
            "            lin = hlgToLinear(rgb) * 3.8;\n"
            "        } else {\n"
            "            lin = pqToLinear(rgb) * 14.0;\n"
            "        }\n"
            "        if (u_colorStd == 2) {\n"
            "            mat3 to709 = mat3(\n"
            "                1.6605, -0.1246, -0.0182,\n"
            "               -0.5876,  1.1329, -0.1006,\n"
            "               -0.0728, -0.0083,  1.1187\n"
            "            );\n"
            "            lin = max(vec3(0.0), to709 * lin);\n"
            "        }\n"
            "        float whitePoint = 1.0 / (hableCurve(vec3(11.2)).x);\n"
            "        vec3 mapped = hableCurve(lin * 2.2) * whitePoint;\n"
            "        mapped = clamp(mapped, 0.0, 1.0);\n"
            "        rgb = pow(mapped, vec3(1.0 / 2.2));\n"
            "    }\n"
            "    fragColor = vec4(rgb, 1.0);\n"
            "}\n";

        GLuint vs = compileShader(GL_VERTEX_SHADER, vShaderSrc);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, fShaderSrc);
        if (!vs || !fs) return;

        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        glDeleteShader(vs);
        glDeleteShader(fs);

        locTexY = glGetUniformLocation(program, "u_texY");
        locTexU = glGetUniformLocation(program, "u_texU");
        locTexV = glGetUniformLocation(program, "u_texV");
        locIs10Bit = glGetUniformLocation(program, "u_is10Bit");
        locColorStd = glGetUniformLocation(program, "u_colorStd");
        locHdrTransfer = glGetUniformLocation(program, "u_hdrTransfer");
        locForceSdr = glGetUniformLocation(program, "u_forceSdr");
    }

    void initTextures() {
        if (!texY) {
            GLuint texs[3];
            glGenTextures(3, texs);
            texY = texs[0];
            texU = texs[1];
            texV = texs[2];
            for (int i = 0; i < 3; i++) {
                glBindTexture(GL_TEXTURE_2D, texs[i]);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }
        }
    }

public:
    GlVideoRenderer() = default;
    ~GlVideoRenderer() { release(); }

    bool isReady() const { return eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT; }

    bool init(ANativeWindow* window, bool forceRecreate = false) {
        if (!window) return false;
        if (!forceRecreate && boundWindow == window && isReady()) return true;
        release();

        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL_NO_DISPLAY) {
            LOGE("GlVideoRenderer: eglGetDisplay failed");
            return false;
        }

        EGLint major = 0, minor = 0;
        if (!eglInitialize(eglDisplay, &major, &minor)) {
            LOGE("GlVideoRenderer: eglInitialize failed");
            return false;
        }

        const EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_NONE
        };

        EGLConfig config;
        EGLint numConfigs = 0;
        if (!eglChooseConfig(eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs <= 0) {
            const EGLint attribs2[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_BLUE_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_RED_SIZE, 8,
                EGL_NONE
            };
            if (!eglChooseConfig(eglDisplay, attribs2, &config, 1, &numConfigs) || numConfigs <= 0) {
                LOGE("GlVideoRenderer: eglChooseConfig failed");
                return false;
            }
        }

        const EGLint ctxAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        if (eglContext == EGL_NO_CONTEXT) {
            const EGLint ctxAttribs2[] = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL_NONE
            };
            eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
        if (eglContext == EGL_NO_CONTEXT) {
            LOGE("GlVideoRenderer: eglCreateContext failed");
            return false;
        }

        eglSurface = eglCreateWindowSurface(eglDisplay, config, window, nullptr);
        if (eglSurface == EGL_NO_SURFACE) {
            LOGE("GlVideoRenderer: eglCreateWindowSurface failed");
            return false;
        }

        if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            LOGE("GlVideoRenderer: eglMakeCurrent failed");
            return false;
        }

        boundWindow = window;
        initShaders();
        initTextures();
        LOGI("GlVideoRenderer: Initialized OpenGL ES 3.0 video renderer for window %p", window);
        return true;
    }

    void release() {
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (program) {
                glDeleteProgram(program);
                program = 0;
            }
            if (texY) {
                GLuint texs[3] = {texY, texU, texV};
                glDeleteTextures(3, texs);
                texY = texU = texV = 0;
            }
            if (eglSurface != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL_NO_SURFACE;
            }
            if (eglContext != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL_NO_CONTEXT;
            }
            eglTerminate(eglDisplay);
            eglDisplay = EGL_NO_DISPLAY;
        }
        boundWindow = nullptr;
        texWidth = 0;
        texHeight = 0;
        texIs10Bit = -1;
    }

    bool render(AVFrame* f, bool forceSdr, int rotation = 0) {
        if (!f || !f->data[0] || !isReady() || !program) return false;
        if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false;

        bool is10Bit = (f->format == AV_PIX_FMT_YUV420P10LE ||
                        f->format == AV_PIX_FMT_YUV420P10BE ||
                        f->format == AV_PIX_FMT_YUV422P10LE ||
                        f->format == AV_PIX_FMT_YUV444P10LE ||
                        f->format == AV_PIX_FMT_YUV420P12LE);

        int w = f->width;
        int h = f->height;
        int uvW = (f->format == AV_PIX_FMT_YUV444P10LE || f->format == AV_PIX_FMT_YUV444P) ? w : (w / 2);
        int uvH = (f->format == AV_PIX_FMT_YUV422P10LE || f->format == AV_PIX_FMT_YUV422P || f->format == AV_PIX_FMT_YUV444P10LE || f->format == AV_PIX_FMT_YUV444P) ? h : (h / 2);

        glUseProgram(program);

        // Upload Y
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texY);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[0] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, w, h, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[0]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[0]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[0]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, w, h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[0]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[0]);
            }
        }

        // Upload U
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texU);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[1] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, uvW, uvH, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[1]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[1]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[1]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvW, uvH, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[1]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[1]);
            }
        }

        // Upload V
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texV);
        if (is10Bit) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[2] / 2);
            if (texWidth != w || texHeight != h || texIs10Bit != 1) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, uvW, uvH, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[2]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, f->data[2]);
            }
        } else {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, f->linesize[2]);
            if (texWidth != w || texHeight != h || texIs10Bit != 0) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvW, uvH, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[2]);
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uvW, uvH, GL_LUMINANCE, GL_UNSIGNED_BYTE, f->data[2]);
            }
        }

        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        texWidth = w;
        texHeight = h;
        texIs10Bit = is10Bit ? 1 : 0;

        int colorStd = 0;
        if (f->color_primaries == AVCOL_PRI_BT2020) colorStd = 2;
        else if (f->color_primaries == AVCOL_PRI_BT709 || w >= 1280 || h >= 720) colorStd = 1;

        int hdrTransfer = 0;
        if (f->color_trc == AVCOL_TRC_SMPTE2084) hdrTransfer = 1;
        else if (f->color_trc == AVCOL_TRC_ARIB_STD_B67) hdrTransfer = 2;

        glUniform1i(locTexY, 0);
        glUniform1i(locTexU, 1);
        glUniform1i(locTexV, 2);
        glUniform1i(locIs10Bit, is10Bit ? 1 : 0);
        glUniform1i(locColorStd, colorStd);
        glUniform1i(locHdrTransfer, hdrTransfer);
        glUniform1i(locForceSdr, forceSdr ? 1 : 0);

        EGLint surfW = 0, surfH = 0;
        eglQuerySurface(eglDisplay, eglSurface, EGL_WIDTH, &surfW);
        eglQuerySurface(eglDisplay, eglSurface, EGL_HEIGHT, &surfH);
        glViewport(0, 0, surfW > 0 ? surfW : w, surfH > 0 ? surfH : h);

        float u0 = 0.0f, v0 = 0.0f;
        float u1 = 0.0f, v1 = 1.0f;
        float u2 = 1.0f, v2 = 0.0f;
        float u3 = 1.0f, v3 = 1.0f;

        if (rotation == 90) {
            u0 = 0.0f; v0 = 1.0f;
            u1 = 1.0f; v1 = 1.0f;
            u2 = 0.0f; v2 = 0.0f;
            u3 = 1.0f; v3 = 0.0f;
        } else if (rotation == 180) {
            u0 = 1.0f; v0 = 1.0f;
            u1 = 1.0f; v1 = 0.0f;
            u2 = 0.0f; v2 = 1.0f;
            u3 = 0.0f; v3 = 0.0f;
        } else if (rotation == 270) {
            u0 = 1.0f; v0 = 0.0f;
            u1 = 0.0f; v1 = 0.0f;
            u2 = 1.0f; v2 = 1.0f;
            u3 = 0.0f; v3 = 1.0f;
        }

        const float vertices[] = {
            -1.0f,  1.0f,  u0, v0,
            -1.0f, -1.0f,  u1, v1,
             1.0f,  1.0f,  u2, v2,
             1.0f, -1.0f,  u3, v3
        };
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), vertices + 2);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);

        eglSwapBuffers(eglDisplay, eglSurface);
        return true;
    }
};
