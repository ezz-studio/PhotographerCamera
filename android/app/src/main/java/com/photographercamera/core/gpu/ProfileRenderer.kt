/**
 * ProfileRenderer - the 2-pass OpenGL ES 3.0 imaging engine.
 *
 * Architecture is taken verbatim from the proven 2-pass designs of
 *   zoombox (LutPreviewRenderer.kt) and dazz-retro-camera (CameraGLRenderer.kt),
 * which both compile and run successfully on production devices (and our
 * emulator). Both upstream projects run preview + capture on this exact
 * two-stage pipeline; the only difference from our earlier 15-pass multi-FBO
 * implementation is that ALL photographic effects now live in a single
 * fragment shader (effect.frag) instead of being split across 7 programs and
 * 3 ping-pong FBOs.
 *
 * Pass 1: external OES camera frame -> stable 2D RGBA8 FBO via oes2d.vert
 *         (applies stMatrix rotation + FILL_CENTER crop) + oes2d.frag
 *         (passthrough). Why a copy at all? Tile-based GPUs (Mali/Adreno)
 *         don't guarantee per-sample consistency when the same external OES
 *         texture is sampled many times in one shader - that produced the
 *         "everything looks like a flat color" failure when we had 25+
 *         effect samples reading OES directly.
 * Pass 2: effect.frag samples the stable RGBA8 2D texture ONCE per pixel
 *         and runs every photographer effect in one program:
 *           exposure -> WB -> color matrix -> tone curve (RGBA8 1D LUT)
 *           -> highlight rolloff -> shadow -> HSL (vec4[7] uniform)
 *           -> sharpen (9-tap) -> bloom (warm blur) -> halation (warm glow)
 *           -> optional 3D LUT -> grain + noise -> vignette -> film curve
 *         Output: the default framebuffer (preview) or an RGBA8 readback
 *         FBO (capture).
 *
 * All intermediate buffers are RGBA8 - no RGBA16F, no R8 LUT-as-r16f. That
 *   keeps the path compatible with the worst real-device driver (proven on
 *   the translator drivers of MuMu / LDPlayer / ANGLE via zoombox).
 *
 * Every photographic parameter is a uniform driven by [GpuParams], which is
 *   re-derived from the active [PhotographerProfile] for every frame. The
 *   shader contains NO hardcoded constants for highlight/shadow/curve/film
 *   values - all of those flow from the profile (and optionally from a
 *   per-photo auto-calibration step) so each photo's response is computed
 *   on its own and stays consistent across photos with the same exposure.
 *
 * Public API (unchanged from the 15-pass version so callers in
 *   ui/CameraPreviewView.kt and core/camera/CameraEngine.kt keep working):
 *   create(assets), setDebugLayerLimit(n), debugLayerLimit, isDegraded,
 *   renderPreview, renderPassthroughPreview, renderToBitmap, renderBitmap,
 *   requestProbe, release.
 */
package com.photographercamera.core.gpu

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ProfileRenderer private constructor(
    private val assets: AssetManager,
    private val progCopy: Int,
    private val progEffect: Int,
    private val progBlit: Int,
    private val progRawIsp: Int,
    private val quad: Int,
) {
    companion object {
        // Stride 16 floats per vertex: pos(vec2) + uv(vec2) interleaved so
        // layout(location=0) is position and layout(location=1) is uv.
        private val QUAD_DATA = FloatArray(6 * 4).also { arr ->
            val ps = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
            val uv = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f)
            for (i in 0 until 6) {
                arr[i * 4 + 0] = ps[i * 2]
                arr[i * 4 + 1] = ps[i * 2 + 1]
                arr[i * 4 + 2] = uv[i * 2]
                arr[i * 4 + 3] = uv[i * 2 + 1]
            }
        }

        fun create(assets: AssetManager): ProfileRenderer {
            val quad = makeQuad()
            val progCopy = GLSL.program(assets, "shaders/oes2d.vert", "shaders/oes2d.frag")
            val progEffect = GLSL.program(assets, "shaders/passthrough.vert", "shaders/effect.frag")
            val progBlit = GLSL.program(assets, "shaders/passthrough.vert", "shaders/blit.frag")
            // Optional RAW ISP stage (RAW capture path only - preview never
            // touches it). Failure here degrades to the ISP-JPEG capture path,
            // never the preview.
            val progRawIsp = GLSL.program(assets, "shaders/passthrough.vert", "shaders/raw_isp.frag")
            if (progRawIsp == 0) {
                com.photographercamera.core.debug.DebugLog.log(
                    "GL", "raw_isp program failed to build - RAW ISP path disabled (JPEG fallback)",
                )
            }
            if (progCopy == 0 || progBlit == 0) {
                throw IllegalStateException("minimum pipeline (oes2d copy / blit) failed to build - preview impossible")
            }
            val degraded = mutableListOf<String>()
            if (progEffect == 0) degraded.add("effect")
            com.photographercamera.core.debug.DebugLog.log(
                "GL",
                "renderer ready mode=" + (if (degraded.isEmpty()) "FULL_2PASS" else "DEGRADED") +
                    " skipped=[" + degraded.joinToString(",") + "]",
            )
            return ProfileRenderer(assets, progCopy, progEffect, progBlit, progRawIsp, quad)
        }

        private fun makeQuad(): Int {
            val vbo = IntArray(1)
            GLES30.glGenBuffers(1, vbo, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            val bb = ByteBuffer.allocateDirect(QUAD_DATA.size * 4).order(ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(QUAD_DATA)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_DATA.size * 4, bb, GLES30.GL_STATIC_DRAW)
            return vbo[0]
        }
    }

    // Intermediate RGBA8 FBO that Pass 1 writes into.
    private var mainTex = 0
    private var mainFbo = 0
    private var mainW = 0
    private var mainH = 0

    // Last coverage snapshot we logged — emit to DebugLog only on change so the
    // GL thread is not spammed with one log per frame.
    private var lastCoverageLog = ""
    private fun noteCoverageChange(texW: Int, texH: Int, viewW: Int, viewH: Int, isRotated90: Boolean, win: FloatArray) {
        val line = "tex=${texW}x${texH} view=${viewW}x${viewH} rot90=$isRotated90 win=[${"%.3f".format(win[0])},${"%.3f".format(win[1])},${"%.3f".format(win[2])},${"%.3f".format(win[3])}]"
        if (line != lastCoverageLog) {
            lastCoverageLog = line
            com.photographercamera.core.debug.DebugLog.log("GL", "coverage: $line")
        }
    }

    // RGBA8 FBO for capture readback.
    private var outTex = 0
    private var outFbo = 0
    private var outW = 0
    private var outH = 0

    // RGBA8 1024x1 tone curve LUT (dazz-proven format). All four channels
    // mirror R; the shader reads R.
    private var toneTex = 0
    private var toneUploaded = FloatArray(0)

    // Optional 3D LUT (.cube).
    private var lut3dTex = 0
    private var lut3dSize = 0

    /** Number of effect stages to run on the live preview:
     *   0 = passthrough (OES - 2D - screen, no effects)
     *   1 = full effect shader (single program; not divisible into sub-passes
     *       because the architecture collapsed everything into one shader,
     *       matching zoombox's LutPreviewRenderer). */
    @Volatile var debugLayerLimit: Int = 1
        private set

    fun setDebugLayerLimit(n: Int) {
        val v = n.coerceIn(0, 1)
        if (v != debugLayerLimit) {
            debugLayerLimit = v
            com.photographercamera.core.debug.DebugLog.log("GL", "debugLayerLimit -> $v")
        }
    }

    val isDegraded: Boolean
        get() = progEffect == 0

    // ---- public API ---------------------------------------------------------

    fun renderPreview(
        oesTex: Int,
        viewW: Int,
        viewH: Int,
        params: GpuParams,
        stMatrix: FloatArray,
        timestampMs: Long,
        texW: Int = viewW,
        texH: Int = viewH,
        vignetteWindow: FloatArray? = null,
    ) {
        ensureBuffers(viewW, viewH)
        runCopyPass(oesTex, stMatrix, viewW, viewH, texW, texH)
        if (debugLayerLimit >= 1 && progEffect != 0) {
            runEffectPass(mainTex, viewW, viewH, params, timestampMs, toScreen = true, vignetteWindow = vignetteWindow)
        } else {
            blitToScreen(mainTex, viewW, viewH)
        }
    }

    fun renderPassthroughPreview(
        oesTex: Int,
        viewW: Int,
        viewH: Int,
        stMatrix: FloatArray,
        texW: Int,
        texH: Int,
    ) {
        ensureBuffers(viewW, viewH)
        runCopyPass(oesTex, stMatrix, viewW, viewH, texW, texH)
        blitToScreen(mainTex, viewW, viewH)
    }

    fun renderToBitmap(
        srcTex: Int,
        w: Int,
        h: Int,
        params: GpuParams,
        stMatrix: FloatArray,
        timestampMs: Long,
        texW: Int = w,
        texH: Int = h,
    ): Bitmap {
        ensureBuffers(w, h)
        runCopyPass(srcTex, stMatrix, w, h, texW, texH)
        runEffectPass(mainTex, w, h, params, timestampMs, toScreen = false)
        return readback(w, h, flipY = true)
    }

    fun renderBitmap(
        srcBmp: Bitmap,
        params: GpuParams,
        displayW: Int,
        displayH: Int,
        timestampMs: Long,
    ): Bitmap {
        val w = srcBmp.width
        val h = srcBmp.height
        ensureBuffers(w, h)
        // Mirror the OLD renderBitmap pattern: upload to a SEPARATE temp
        // texture (not mainTex), then blit it into mainFbo. Direct glTexImage2D
        // onto mainTex while mainFbo is attached to mainTex was suspected to
        // produce undefined results on some drivers -- safer to keep the upload
        // texture and the rendering texture separate, matching the 15-pass
        // pipeline's proven behavior.
        val tmpTex = IntArray(1)
        GLES30.glGenTextures(1, tmpTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        srcBmp.copyPixelsToBuffer(px)
        px.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px,
        )
        // Blit tmpTex into mainFbo via the simple progBlit (no effect yet).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progBlit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progBlit, "u_input"), 0)
        // Set on progBlit (the bound program), not progEffect.
        u4fOn(progBlit, "u_uvWin", 1f, 1f, 0f, 0f)
        drawQuad()
        GLES30.glDeleteTextures(1, tmpTex, 0)
        // Run the effect shader on mainTex (which now contains the camera
        // frame) and read back the result.
        runEffectPass(mainTex, w, h, params, timestampMs, toScreen = false)
        // Bitmap upload already cancels glReadPixels' bottom-up origin —
        // an extra flip V-mirrored every still ("upside down" photos).
        return readback(w, h, flipY = false)
    }

    /**
     * RAW ISP capture path: develop an untouched Bayer frame on the GPU and
     * run the normal recipe chain on top.
     *
     * Pipeline: upload RAW16 -> raw_isp.frag (black level -> WB -> bilinear
     * demosaic -> CCM -> gamma, with rotation + 3:4 cover crop baked into the
     * sampling coords) -> mainTex -> effect chain -> readback. Mirrors the
     * doc's "Camera2 -> RAW -> RAW ISP -> recipe -> GPU -> JPEG" route.
     *
     * @return the processed upright bitmap, or null when the RAW path cannot
     *     run (no ISP program, raw texture over GL limit) so the caller can
     *     fall back to the proven ISP-JPEG capture.
     */
    fun renderRawChain(
        raw: ByteBuffer,
        rawW: Int,
        rawH: Int,
        rowStrideBytes: Int,
        rotDeg: Int,
        calib: RawCalibration,
        params: GpuParams,
        timestampMs: Long,
    ): Bitmap? {
        if (progRawIsp == 0 || rawW <= 0 || rawH <= 0) return null
        val maxTex = run {
            val v = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
            v[0]
        }
        if (maxOf(rawW, rawH) > maxTex) {
            com.photographercamera.core.debug.DebugLog.log(
                "GL", "raw ${rawW}x${rawH} exceeds GL_MAX_TEXTURE_SIZE=$maxTex - fallback",
            )
            return null
        }
        val rot = (((rotDeg % 360) + 360) % 360).let {
            // tolerate odd HAL values by snapping to the nearest right angle
            when {
                it in 315..360 || it < 45 -> 0
                it in 45..134 -> 90
                it in 135..224 -> 180
                else -> 270
            }
        }
        val isRot90 = rot == 90 || rot == 270
        val upW = if (isRot90) rawH else rawW
        val upH = if (isRot90) rawW else rawH

        // 3:4 portrait target within the capture budget (same policy as the
        // JPEG still path) - the crop itself happens inside the ISP shader.
        val cap = com.photographercamera.core.device.DeviceCompat.captureMaxLongSidePx
        val longSide = maxOf(upW, upH).coerceIn(720, cap)
        val th = longSide
        val tw = (longSide * 3L / 4L).toInt()
        ensureBuffers(tw, th)

        // cover-crop window in UPRIGHT uv (same math as the preview copy pass).
        val win = coverWindow(tw, th, upW, upH, isRot90)

        // Upload the Bayer plane as GL_R16UI (zero conversion on the CPU).
        val rawTex = IntArray(1)
        GLES30.glGenTextures(1, rawTex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowStrideBytes / 2)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, rawW, rawH, 0,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, raw,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)

        // ISP pass: raw -> mainTex (upright, cropped, demosaiced, sRGB).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
        GLES30.glViewport(0, 0, tw, th)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progRawIsp)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[0])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progRawIsp, "u_raw"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(progRawIsp, "u_rawSize"), rawW.toFloat(), rawH.toFloat())
        GLES30.glUniform2i(
            GLES30.glGetUniformLocation(progRawIsp, "u_cfa"),
            calib.cfaOffset[0], calib.cfaOffset[1],
        )
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(progRawIsp, "u_black"), 1, calib.blackLevel, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(progRawIsp, "u_white"), calib.whiteLevel)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(progRawIsp, "u_wb"), 1, calib.wbGains, 0)
        GLES30.glUniformMatrix3fv(
            GLES30.glGetUniformLocation(progRawIsp, "u_ccm"), 1, false, calib.ccm, 0,
        )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progRawIsp, "u_rot"), rot)
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(progRawIsp, "u_win"),
            win[0], win[1], win[2], win[3],
        )
        drawQuad()
        GLES30.glDeleteTextures(1, rawTex, 0)

        // Recipe chain on the developed frame, then readback.
        runEffectPass(mainTex, tw, th, params, timestampMs, toScreen = false)
        com.photographercamera.core.debug.DebugLog.log(
            "RAW", "isp chain done raw=${rawW}x${rawH} rot=$rot -> ${tw}x${th}",
        )
        // RAW upload: first buffer row (sensor top) goes to texel v=0 — same
        // convention as the bitmap-upload path, so no Y-flip on readback.
        return readback(tw, th, flipY = false)
    }

    fun requestProbe() {
        com.photographercamera.core.debug.DebugLog.log("GL", "requestProbe: 2-pass build has no per-stage passes; mainFbo center now sample")
        run {
            if (mainFbo == 0 || mainW == 0 || mainH == 0) return@run
            val bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
            GLES30.glReadPixels(mainW / 2, mainH / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            bb.rewind()
            com.photographercamera.core.debug.DebugLog.log(
                "GL",
                "probe[mainFbo-center]=${(bb.get(0).toInt() and 0xFF)},${(bb.get(1).toInt() and 0xFF)},${(bb.get(2).toInt() and 0xFF)}",
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    fun release() {
        listOf(progCopy, progEffect, progBlit, progRawIsp).forEach { if (it != 0) GLES30.glDeleteProgram(it) }
        if (mainTex != 0) GLES30.glDeleteTextures(1, intArrayOf(mainTex), 0)
        if (mainFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mainFbo), 0)
        if (outTex != 0) GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
        if (outFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
        if (toneTex != 0) GLES30.glDeleteTextures(1, intArrayOf(toneTex), 0)
        if (lut3dTex != 0) GLES30.glDeleteTextures(1, intArrayOf(lut3dTex), 0)
        if (quad != 0) GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
    }

    // ---- pass 1: OES -> 2D copy ---------------------------------------------

    private fun runCopyPass(
        oesTex: Int,
        stMatrix: FloatArray,
        viewW: Int,
        viewH: Int,
        texW: Int,
        texH: Int,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo)
        GLES30.glViewport(0, 0, viewW, viewH) // stale-viewport guard
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progCopy)

        // Detect a 90°/270° rotation by reading the upper 2x2 of the HAL-supplied
        // stMatrix. For 0°/180° stMatrix[0]/[5] are ±1; for 90°/270° they are 0.
        // The shader applies stMatrix to (display-uv → oes-uv), so the displayed
        // image's effective dimensions are (texH, texW) when rotated and
        // (texW, texH) when not. Comparing the BUFFER AR against the view AR
        // would then crop the wrong axis and the displayed content collapses
        // into a narrow vertical strip on portrait phones (the "doesn't fill
        // the preview" symptom the user reported). Using the post-rotation
        // effective AR aligns the crop with the view's actual aspect ratio.
        val isRotated90 = kotlin.math.abs(stMatrix[0]) < 1e-3f &&
            kotlin.math.abs(stMatrix[5]) < 1e-3f
        val win = coverWindow(viewW, viewH, texW, texH, isRotated90)
        noteCoverageChange(texW, texH, viewW, viewH, isRotated90, win)

        val stMatLoc = GLES30.glGetUniformLocation(progCopy, "u_stMatrix")
        val cropLoc = GLES30.glGetUniformLocation(progCopy, "u_uvWin")
        GLES30.glUniformMatrix4fv(stMatLoc, 1, false, stMatrix, 0)
        GLES30.glUniform4f(cropLoc, win[0], win[1], win[2], win[3])

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progCopy, "u_input"), 0)
        drawQuad()
    }

    // ---- pass 2: effect -> screen or FBO ------------------------------------

    private fun runEffectPass(
        srcTex: Int,
        w: Int,
        h: Int,
        p: GpuParams,
        timestampMs: Long,
        toScreen: Boolean,
        vignetteWindow: FloatArray? = null,
    ) {
        if (progEffect == 0) return
        val target = if (toScreen) 0 else ensureOut(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progEffect)

        // CRITICAL: reset u_uvWin to identity for progEffect. The previous
        // runCopyPass left u_uvWin = coverWindow crop coords (e.g.
        // (0.1, 0, 0.9, 1)), but progEffect uses passthrough.vert which reads
        // u_uvWin as affine (sx, sy, ox, oy). With sy=0, oy=1 every fragment
        // ends up sampling mainTex's bottom row -> flat color output.
        u4f("u_uvWin", 1f, 1f, 0f, 0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progEffect, "u_input"), 0)

        // Main chain - every value comes from PhotographerProfile via
        // GpuParams; the shader applies them with no offset or baked-in
        // constant. Per-photo highlight/shadow/curve consistency is therefore
        // guaranteed as long as the profile is parsed correctly.
        u1f("u_exposure", p.exposure)
        u2f("u_wb", p.wbTemp, p.wbTint)
        uMat3("u_colorMatrix", p.colorMatrixGL)
        bindToneLut(p)
        u3f("u_highlight", p.highlightThreshold, p.highlightStrength, p.highlightSaturation)
        u3f("u_shadow", p.shadowBlackPoint, p.shadowCompression, p.shadowSaturation)
        bindHsl(p)
        // Sharpen / Bloom / Halation
        u1f("u_sharpenAmount", p.sharpenAmount)
        u1f("u_sharpenRadius", p.sharpenRadius)
        u2f("u_texel", 1f / w, 1f / h)
        u1f("u_bloomAmount", p.bloomAmount)
        u1f("u_bloomThreshold", p.bloomThreshold)
        u1f("u_bloomRadius", p.bloomRadius)
        u1f("u_halationAmount", p.halationAmount)
        u1f("u_halationThreshold", p.halationThreshold)
        u1f("u_halationRadius", p.halationRadius)
        u1f("u_halationWarmth", p.halationWarmth)
        // Grain + noise
        u1f("u_grainAmount", p.grainVec[0])
        u1f("u_grainSize", p.grainVec[1])
        u1f("u_grainDensity", p.grainVec[2])
        u1f("u_noiseLuma", p.noiseVec[0])
        u1f("u_noiseChroma", p.noiseVec[1])
        u1f("u_seed", (((timestampMs ushr 10) and 0x3FFL).toFloat() / 1024f))
        // Vignette
        u1f("u_vignetteAmount", p.vignetteAmount)
        u1f("u_vignetteRadius", p.vignetteRadius)
        u1f("u_vignetteFeather", p.vignetteFeather)
        u2f("u_vignetteCenter", p.vignetteCenter[0], p.vignetteCenter[1])
        u2f("u_viewSize", w.toFloat(), h.toFloat())
        // u_vwin is (centerX, centerY, sizeX, sizeY) per the UI's capture-box
        // alignment; identity = full frame centered.
        val vwin = vignetteWindow ?: floatArrayOf(0.5f, 0.5f, 1f, 1f)
        u4f("u_vwin", vwin[0], vwin[1], vwin[2], vwin[3])
        // Film curve
        u2f("u_film", p.filmFloor, p.filmCeil)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progEffect, "u_filmEnabled"), if (p.filmEnabled) 1 else 0)
        // Optional 3D LUT
        bindLut3d(p)

        drawQuad()
    }

    private fun blitToScreen(srcTex: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progBlit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progBlit, "u_input"), 0)
        u4fOn(progBlit, "u_uvWin", 1f, 1f, 0f, 0f)
        drawQuad()
    }

    // ---- buffer / texture helpers -------------------------------------------

    private fun ensureBuffers(w: Int, h: Int) {
        if (mainTex != 0 && mainW == w && mainH == h) return
        if (mainTex != 0) GLES30.glDeleteTextures(1, intArrayOf(mainTex), 0)
        if (mainFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(mainFbo), 0)
        mainTex = createRgba8Texture(w, h)
        mainFbo = createFbo(mainTex)
        mainW = w
        mainH = h
    }

    private fun ensureOut(w: Int, h: Int): Int {
        if (outTex != 0 && outW == w && outH == h) return outFbo
        if (outTex != 0) GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
        if (outFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
        outTex = createRgba8Texture(w, h)
        outFbo = createFbo(outTex)
        outW = w
        outH = h
        return outFbo
    }

    private fun createRgba8Texture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        return tex[0]
    }

    private fun createFbo(tex: Int): Int {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            com.photographercamera.core.debug.DebugLog.log(
                "GL", "FBO incomplete 0x${Integer.toHexString(status)} - falling back",
            )
        }
        return fbo[0]
    }

    private fun drawQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    /**
     * Read the outFbo result into a Bitmap.
     *
     * [flipY] depends on how the content got INTO mainTex:
     *  - OES/SurfaceTexture path ([renderToBitmap]): the HAL stMatrix bakes a
     *    V-flip, so FBO GL-bottom row = image BOTTOM row; glReadPixels then
     *    returns that row first and it lands on the Bitmap top — mirrored.
     *    flipY=true corrects it.
     *  - Bitmap-upload / RAW-upload paths ([renderBitmap], [renderRawChain]):
     *    the first data row (image TOP) goes to texel v=0 (GL bottom), which
     *    ALREADY cancels glReadPixels' bottom-up origin — the buffer comes
     *    back upright and any extra flip V-mirrors the photo ("upside
     *    down" stills). flipY=false.
     */
    private fun readback(w: Int, h: Int, flipY: Boolean): Bitmap {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h)
        val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
        bb.position(0)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(bb)
        val out = if (flipY) flipVertical(bmp) else bmp
        if (out !== bmp) bmp.recycle()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return out
    }

    private fun flipVertical(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val half = h / 2
        for (y in 0 until half) {
            val a = y * w
            val b = (h - y - 1) * w
            for (x in 0 until w) {
                val tmp = pixels[a + x]
                pixels[a + x] = pixels[b + x]
                pixels[b + x] = tmp
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- LUT uploads ---------------------------------------------------------

    private fun bindToneLut(p: GpuParams) {
        if (toneTex == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            toneTex = tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        val n = p.toneLut.size
        val enabled = if (n > 1) 1 else 0
        if (enabled == 1 && (n != toneUploaded.size || !p.toneLut.contentEquals(toneUploaded))) {
            val bytes = ByteArray(n * 4)
            for (i in 0 until n) {
                val b = ((p.toneLut[i].coerceIn(0f, 1f) * 255f) + 0.5f).toInt().coerceIn(0, 255)
                bytes[i * 4 + 0] = b.toByte()
                bytes[i * 4 + 1] = b.toByte()
                bytes[i * 4 + 2] = b.toByte()
                bytes[i * 4 + 3] = b.toByte()
            }
            val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            bb.put(bytes); bb.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, n, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb,
            )
            toneUploaded = p.toneLut.copyOf()
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progEffect, "u_toneLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progEffect, "u_toneEnabled"), enabled)
    }

    private fun bindHsl(p: GpuParams) {
        val loc = GLES30.glGetUniformLocation(progEffect, "u_hsl[0]")
        if (loc < 0) return
        val data = FloatArray(7 * 4)
        val src = p.hslLut
        if (src.size == 7 * 3) {
            for (i in 0 until 7) {
                data[i * 4 + 0] = src[i * 3 + 0]
                data[i * 4 + 1] = src[i * 3 + 1]
                data[i * 4 + 2] = src[i * 3 + 2]
                data[i * 4 + 3] = 0f
            }
        }
        GLES30.glUniform4fv(loc, 7, data, 0)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(progEffect, "u_hslEnabled"),
            if (p.hslLut.size == HSL_LUT_SIZE * 3) 1 else 0,
        )
    }

    private fun bindLut3d(p: GpuParams) {
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progEffect, "u_lut3dEnabled"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(progEffect, "u_lut3dStrength"), 0f)
    }

    // ---- cover window (FILL_CENTER crop for preview) -----------------------

    private fun coverWindow(viewW: Int, viewH: Int, texW: Int, texH: Int, isRotated90: Boolean = false): FloatArray {
        if (viewW <= 0 || viewH <= 0 || texW <= 0 || texH <= 0) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        // The shader uses u_uvWin as a crop in DISPLAY UV space (base UVs before
        // stMatrix). When the sensor is rotated 90°/270°, the displayed image's
        // effective dimensions are (texH, texW). Compare the display AR against
        // the view AR so a 4:3 sensor on a 3:4 portrait view returns (0,0,1,1)
        // - i.e. full coverage, which is what FILL_CENTER means.
        val (effW, effH) = if (isRotated90) Pair(texH, texW) else Pair(texW, texH)
        val effAR = effW.toFloat() / effH.toFloat()
        val viewAR = viewW.toFloat() / viewH.toFloat()
        return if (effAR > viewAR) {
            // Displayed content wider than view -> crop horizontal sides of
            // the displayed image (top/bottom of u_uvWin are 0/1).
            val crop = viewAR / effAR
            val off = (1f - crop) * 0.5f
            floatArrayOf(off, 0f, off + crop, 1f)
        } else {
            // Displayed content taller than view -> crop vertical sides of the
            // displayed image (left/right of u_uvWin are 0/1).
            val crop = effAR / viewAR
            val off = (1f - crop) * 0.5f
            floatArrayOf(0f, off, 1f, off + crop)
        }
    }

    // ---- uniform setters -----------------------------------------------------

    private fun u1f(name: String, v: Float) {
        val loc = GLES30.glGetUniformLocation(progEffect, name)
        if (loc >= 0) GLES30.glUniform1f(loc, v)
    }

    private fun u2f(name: String, x: Float, y: Float) {
        val loc = GLES30.glGetUniformLocation(progEffect, name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    private fun u3f(name: String, x: Float, y: Float, z: Float) {
        val loc = GLES30.glGetUniformLocation(progEffect, name)
        if (loc >= 0) GLES30.glUniform3f(loc, x, y, z)
    }

    private fun u4f(name: String, x: Float, y: Float, z: Float, w: Float) {
        u4fOn(progEffect, name, x, y, z, w)
    }

    // IMPORTANT: glUniform* acts on the CURRENTLY BOUND program. The location
    // MUST come from that same program - looking it up in progEffect while
    // progBlit is bound is undefined behavior (it worked on the MuMu
    // translator driver by accident and produced a flat color block on
    // others, because progBlit's u_uvWin stayed at its (0,0,0,0) default and
    // every fragment sampled texel (0,0)).
    private fun u4fOn(prog: Int, name: String, x: Float, y: Float, z: Float, w: Float) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform4f(loc, x, y, z, w)
    }

    private fun uMat3(name: String, mat: FloatArray) {
        val loc = GLES30.glGetUniformLocation(progEffect, name)
        if (loc >= 0) GLES30.glUniformMatrix3fv(loc, 1, false, mat, 0)
    }
}