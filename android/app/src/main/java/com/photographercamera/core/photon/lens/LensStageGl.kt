/*
 * PhotographerCamera 自有 glue 层 —— lens 光学阶段 GPU 实现。
 *
 * 忠实移植桌面端 tools/profile_renderer.py apply_lens()（docs/HANDOFF_android_lens.md）：
 *   1. distortion   径向畸变 barrel(+)/pincushion(-)，f = 1 + 0.35k·r²
 *   2. sharpness_falloff  r>0.25 后向角落渐进模糊，权重 clamp(fo·1.2·(r-0.25), 0, 0.85)
 *   3. vignette     自然光学暗角（乘法）1 - v·0.75·clip(r-0.3)^1.5
 *   4. bloom        宽口径溢光：阈值 0.82、大核模糊、增益 0.8·lb
 *   5. flare        水平变形条纹（长横核）+ 暖核心辉光（小核），冷条纹/暖辉光配色同桌面
 * 桌面 chromatic_aberration 由 photon 配方空间效应承接（径向分通道），此处不做。
 *
 * 分辨率相对缩放与桌面 _rel_scale 对齐：relScale = clamp(maxSide/400, 1, 6)。
 * 大核（bloom/flare）在 1/4 分辨率上做可分离模糊（等效桌面大 σ），合成时线性放大。
 *
 * 无自持 EGL 上下文——必须在调用方（LutRenderer 渲染线程 / LutImageProcessor
 * glDispatcher）的当前上下文上运行；两个宿主各自持有独立实例（program 不跨上下文共享）。
 */
package com.photographercamera.core.photon.lens

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class LensStageGl {

    // ---- 顶点（全屏 quad；贴图源 pass 由调用方传入 st/crop 由片元采样） -------------
    private fun vsSource(): String = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aUV;
        out vec2 vUV;
        void main() {
            vUV = aUV;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    // ---- Warp：distortion + CA-free + falloff blur + 光学暗角（一 pass） -------------
    // 贴图源支持 OES（相机帧）与 2D（bitmap）。st/crop 约定与 photon 颜色链一致：
    // uv' = st * vec4(warpedRawUV) （raw 空间先 warp，再过 stMatrix）
    private fun fsWarp(oes: Boolean): String {
        val ext = if (oes) "#extension GL_OES_EGL_image_external_essl3 : require\n" else ""
        val sampler = if (oes) "samplerExternalOES" else "sampler2D"
        return """
        #version 300 es
        ${ext}precision highp float;
        in vec2 vUV;
        out vec4 fragColor;
        uniform $sampler uTex;
        uniform mat4 uSTMatrix;
        uniform float uDistortion;
        uniform float uFalloff;
        uniform float uVignette;
        uniform float uBlurTexel;   // falloff 高斯 σ，单位 texel
        void main() {
            vec2 n = vUV * 2.0 - 1.0;
            float r2 = dot(n, n);
            // 1. distortion：输出像素 n → 采样位置 n·f
            float f = 1.0 + 0.35 * uDistortion * r2;
            vec2 nw = n * f;
            // 2. falloff：r 归一到 0..1（对角=1），权重同桌面
            float r = length(n) / 1.41421356;
            float wgt = clamp(uFalloff * 1.2 * clamp(r - 0.25, 0.0, 1.0), 0.0, 0.85);
            vec2 texel = 1.0 / vec2(textureSize(uTex, 0));
            vec2 suv = (uSTMatrix * vec4((nw + 1.0) * 0.5, 0.0, 1.0)).xy;
            vec3 c;
            if (uFalloff > 0.0001 && wgt > 0.001) {
                // 9-tap 高斯，步长 = uBlurTexel（输出空间 texel，对扭曲后的图像近似）
                float w0 = 0.227027, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.054054, w4 = 0.016216;
                vec2 st = texel * uBlurTexel;
                vec3 acc = vec3(0.0);
                float wsum = 0.0;
                for (int i = -4; i <= 4; i++) {
                    float w = (i == 0) ? w0 : ((abs(i) == 1) ? w1 : ((abs(i) == 2) ? w2 : ((abs(i) == 3) ? w3 : w4)));
                    vec2 off = vec2(float(i)) * st;
                    vec2 p = (uSTMatrix * vec4(((n * f + off) + 1.0) * 0.5, 0.0, 1.0)).xy;
                    acc += texture(uTex, p).rgb * w;
                    wsum += w;
                }
                c = acc / wsum;
                vec3 sharpC = texture(uTex, suv).rgb;
                c = mix(sharpC, c, wgt);
            } else {
                c = texture(uTex, suv).rgb;
            }
            // 3. 光学暗角（乘法，风格化之前）
            if (uVignette > 0.0001) {
                float fall = pow(clamp(r - 0.3, 0.0, 1.0), 1.5);
                c *= 1.0 - uVignette * 0.75 * fall;
            }
            fragColor = vec4(c, 1.0);
        }
        """.trimIndent()
    }

    // ---- Extract：bloom 亮部(rgb) + flare 亮部亮度(a)，1/4 分辨率渲染 -------------
    private val fsExtract = """
        #version 300 es
        precision highp float;
        in vec2 vUV;
        out vec4 fragColor;
        uniform sampler2D uTex;
        void main() {
            vec3 c = texture(uTex, vUV).rgb;
            vec3 bloomB = max(c - 0.82, vec3(0.0)) / 0.18;
            float lum = dot(c, vec3(0.3333333));
            float flareB = max(lum - 0.9, 0.0) / 0.1;
            fragColor = vec4(bloomB, flareB);
        }
    """.trimIndent()

    // ---- Blur9：可分离 9-tap 高斯（uStep = 方向 × σtexel） -----------------------
    private val fsBlur9 = """
        #version 300 es
        precision highp float;
        in vec2 vUV;
        out vec4 fragColor;
        uniform sampler2D uTex;
        uniform vec2 uStep;   // uv 空间单步步长（已含方向与 σ）
        void main() {
            // 权重对应 σ≈2.4、间距 1.4 的高斯（经典 9-tap），uStep 里做 σ 缩放
            float w0 = 0.227027, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.054054, w4 = 0.016216;
            vec4 acc = texture(uTex, vUV) * w0;
            acc += (texture(uTex, vUV + uStep)      + texture(uTex, vUV - uStep)) * w1;
            acc += (texture(uTex, vUV + uStep * 2.0) + texture(uTex, vUV - uStep * 2.0)) * w2;
            acc += (texture(uTex, vUV + uStep * 3.0) + texture(uTex, vUV - uStep * 3.0)) * w3;
            acc += (texture(uTex, vUV + uStep * 4.0) + texture(uTex, vUV - uStep * 4.0)) * w4;
            fragColor = acc;
        }
    """.trimIndent()

    // ---- Streak：水平长核（13-tap，桌面 36×4 变形条纹的横臂） ---------------------
    private val fsStreak = """
        #version 300 es
        precision highp float;
        in vec2 vUV;
        out vec4 fragColor;
        uniform sampler2D uTex;
        uniform vec2 uStepH;   // uv 空间水平步长
        void main() {
            float w0 = 0.1969, w1 = 0.1741, w2 = 0.1216, w3 = 0.0663, w4 = 0.0283,
                  w5 = 0.0094, w6 = 0.0024;
            float acc = texture(uTex, vUV).a * w0;
            for (int i = 1; i <= 6; i++) {
                float w = (i == 1) ? w1 : ((i == 2) ? w2 : ((i == 3) ? w3 : ((i == 4) ? w4 : ((i == 5) ? w5 : w6))));
                acc += texture(uTex, vUV + vec2(uStepH.x * float(i), 0.0)).a * w;
                acc += texture(uTex, vUV - vec2(uStepH.x * float(i), 0.0)).a * w;
            }
            fragColor = vec4(acc);
        }
    """.trimIndent()

    // ---- Composite：warp + bloom·0.8 + flare(暖辉光+冷条纹) ----------------------
    private val fsComposite = """
        #version 300 es
        precision highp float;
        in vec2 vUV;
        out vec4 fragColor;
        uniform sampler2D uWarp;    // 全分辨率
        uniform sampler2D uBloom;   // 1/4
        uniform sampler2D uGlow;    // 1/4（flare 暖辉光，a 通道亮度）
        uniform sampler2D uStreak;  // 1/4（flare 冷条纹，a 通道亮度）
        uniform float uBloomAmt;
        uniform float uFlareAmt;
        void main() {
            vec3 c = texture(uWarp, vUV).rgb;
            if (uBloomAmt > 0.0001) {
                c += uBloomAmt * 0.8 * texture(uBloom, vUV).rgb;
            }
            if (uFlareAmt > 0.0001) {
                float glow = texture(uGlow, vUV).a;
                float streak = texture(uStreak, vUV).a;
                c += uFlareAmt * (0.55 * glow * vec3(1.0, 0.92, 0.8)
                                + 0.45 * streak * vec3(0.85, 0.92, 1.0));
            }
            fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // ---- GL 资源（惰性创建，属于宿主上下文） --------------------------------------
    private var quadBuf: FloatBuffer? = null
    private var warpOesProgram = 0
    private var warp2dProgram = 0
    private var extractProgram = 0
    private var blurProgram = 0
    private var streakProgram = 0
    private var compositeProgram = 0

    private class Rt(val tex: Int, val fbo: Int, var w: Int, var h: Int) {
        fun ensure(nw: Int, nh: Int): Boolean {
            if (w == nw && h == nh) return false
            w = nw; h = nh
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, nw, nh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            return true
        }
    }

    private var warpRt: Rt? = null
    private var outRt: Rt? = null
    private var eRt: Rt? = null
    private var bRt: Rt? = null
    private var gRt: Rt? = null
    private var sRt: Rt? = null
    private var t1Rt: Rt? = null
    private var t2Rt: Rt? = null

    private fun quad(): FloatBuffer {
        quadBuf?.let { return it }
        val f = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        // strip: (-1,-1)(1,-1)(-1,1)(1,1)，uv 同序
        f.put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        f.position(0)
        quadBuf = f
        return f
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw IllegalStateException("lens shader compile failed: $log")
        }
        return sh
    }

    private fun link(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vsSource()))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("lens program link failed: $log")
        }
        return p
    }

    private fun ensurePrograms(needOes: Boolean) {
        if (warp2dProgram == 0) warp2dProgram = link(fsWarp(false))
        if (needOes && warpOesProgram == 0) warpOesProgram = link(fsWarp(true))
        if (extractProgram == 0) extractProgram = link(fsExtract)
        if (blurProgram == 0) blurProgram = link(fsBlur9)
        if (streakProgram == 0) streakProgram = link(fsStreak)
        if (compositeProgram == 0) compositeProgram = link(fsComposite)
    }

    private fun makeRt(w: Int, h: Int): Rt {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Rt(tex[0], fbo[0], w, h)
    }

    private fun bind(rt: Rt, w: Int, h: Int) {
        rt.ensure(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rt.fbo)
        GLES30.glViewport(0, 0, w, h)
    }

    private fun drawQuad() {
        val f = quad()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val buf = f.duplicate()
        buf.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, buf)
        val buf2 = f.duplicate()
        buf2.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, buf2)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun relScale(maxSide: Int): Float = min(6f, max(1f, maxSide / 400f))

    /**
     * 完整 lens 阶段。输入可以是 OES 相机纹理（带 st/crop）或普通 TEXTURE_2D
     * （st=identity, crop=(0,0,1,1)）。返回输出纹理 id（全分辨率，TEXTURE_2D）。
     * 全零参数直接返回 0（调用方沿用原输入，渲染逐像素不变）。
     */
    fun process(
        srcTex: Int,
        srcTarget: Int,
        srcStMatrix: FloatArray,
        srcCropRect: FloatArray,
        width: Int,
        height: Int,
        p: LensParams,
    ): Int {
        if (p.isZero || width <= 0 || height <= 0) return 0
        val needOes = srcTarget == GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        ensurePrograms(needOes)
        val qw = max(1, width / 4)
        val qh = max(1, height / 4)

        if (warpRt == null) warpRt = makeRt(width, height)
        if (outRt == null) outRt = makeRt(width, height)
        if (eRt == null) eRt = makeRt(qw, qh)
        if (bRt == null) bRt = makeRt(qw, qh)
        if (gRt == null) gRt = makeRt(qw, qh)
        if (sRt == null) sRt = makeRt(qw, qh)
        if (t1Rt == null) t1Rt = makeRt(qw, qh)
        if (t2Rt == null) t2Rt = makeRt(qw, qh)

        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        val prevTex = IntArray(1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, prevTex, 0)

        // ---- 1. warp (+falloff + 光学暗角) ----------------------------------------
        val warpP = if (needOes) warpOesProgram else warp2dProgram
        GLES30.glUseProgram(warpP)
        bind(warpRt!!, width, height)
        GLES30.glBindTexture(srcTarget, srcTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(warpP, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(warpP, "uSTMatrix"), 1, false, srcStMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(warpP, "uDistortion"), p.distortion)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(warpP, "uFalloff"), p.sharpnessFalloff)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(warpP, "uVignette"), p.vignette)
        // 桌面 ksz=4·relScale → σ≈0.6·relScale+0.2 texel（输出空间近似）
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(warpP, "uBlurTexel"),
            if (p.sharpnessFalloff > 0.0001f) 0.6f * relScale(max(width, height)) + 0.2f else 1f,
        )
        drawQuad()

        // warp 输出变为普通 2D 纹理（identity 变换）
        val idSt = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val fullCrop = floatArrayOf(0f, 0f, 1f, 1f)

        // ---- 2. extract（1/4） -----------------------------------------------------
        GLES30.glUseProgram(extractProgram)
        bind(eRt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, warpRt!!.tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(extractProgram, "uTex"), 0)
        drawQuad()

        // ---- 3. bloom 大核（1/4，σ≈2.2 quarter-texel → 全分辨率 ≈8.8） -------------
        val bloomSigmaQ = 2.2f
        GLES30.glUseProgram(blurProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgram, "uTex"), 0)
        bind(t1Rt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, eRt!!.tex)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgram, "uStep"), bloomSigmaQ * 1.4f / qw, 0f)
        drawQuad()
        bind(bRt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t1Rt!!.tex)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgram, "uStep"), 0f, bloomSigmaQ * 1.4f / qh)
        drawQuad()

        // ---- 4. flare 暖辉光（1/4 小核） -------------------------------------------
        val glowSigmaQ = 0.8f
        bind(t2Rt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, eRt!!.tex)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgram, "uStep"), glowSigmaQ * 1.4f / qw, 0f)
        drawQuad()
        bind(gRt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t2Rt!!.tex)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgram, "uStep"), 0f, glowSigmaQ * 1.4f / qh)
        drawQuad()

        // ---- 5. flare 冷条纹（水平长臂 + 竖向小核） ---------------------------------
        // 桌面横核 36·relScale 全分辨率宽 → 1/4 下 span=9·relScale，13-tap 步长 0.75·relScale
        GLES30.glUseProgram(streakProgram)
        bind(t1Rt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, eRt!!.tex)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(streakProgram, "uStepH"), 0.75f * relScale(max(width, height)) / qw, 0f)
        drawQuad()
        bind(sRt!!, qw, qh)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t1Rt!!.tex)
        GLES30.glUseProgram(blurProgram)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgram, "uStep"), 0f, glowSigmaQ * 1.4f / qh)
        drawQuad()

        // ---- 6. composite（全分辨率） ----------------------------------------------
        GLES30.glUseProgram(compositeProgram)
        bind(outRt!!, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, warpRt!!.tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uWarp"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bRt!!.tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uBloom"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gRt!!.tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uGlow"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sRt!!.tex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "uStreak"), 3)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uBloomAmt"), p.bloom)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "uFlareAmt"), p.flare)
        drawQuad()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        // 还原状态
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex[0])

        return outRt!!.tex
    }

    /** 释放宿主上下文里的 GL 资源（在对应上下文线程调用；可选）。 */
    fun release() {
        val rts = listOfNotNull(warpRt, outRt, eRt, bRt, gRt, sRt, t1Rt, t2Rt)
        for (rt in rts) {
            GLES30.glDeleteTextures(1, intArrayOf(rt.tex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(rt.fbo), 0)
        }
        warpRt = null; outRt = null; eRt = null; bRt = null; gRt = null; sRt = null; t1Rt = null; t2Rt = null
        for (p in intArrayOf(warpOesProgram, warp2dProgram, extractProgram, blurProgram, streakProgram, compositeProgram)) {
            if (p != 0) GLES30.glDeleteProgram(p)
        }
        warpOesProgram = 0; warp2dProgram = 0; extractProgram = 0; blurProgram = 0; streakProgram = 0; compositeProgram = 0
    }
}
