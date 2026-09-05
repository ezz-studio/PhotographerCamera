/**
 * CameraPreviewView — the GL surface that owns the EGL context, the camera OES
 * texture and the ProfileRenderer. Every preview frame runs through the SAME
 * 15-layer chain as still capture (docs/rendering_pipeline.md), guaranteeing the
 * preview matches the saved photo.
 *
 * Pipeline per frame:
 *   SurfaceTexture (OES) -> updateTexImage -> ProfileRenderer.renderPreview
 *   (OES->2D, MainChain, Sharpen, Bloom, Halation, Grain+Noise, Vignette) -> screen
 *
 * The camera is fed from [setCameraEngine]; the look is set via [setProfile]. No
 * photographer-specific code lives here — it is fully data-driven.
 */
package com.photographercamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.photographercamera.core.camera.CameraEngine
import com.photographercamera.core.camera.RawFrame
import com.photographercamera.core.gpu.GpuParams
import com.photographercamera.core.gpu.ProfileRenderer
import javax.microedition.khronos.egl.EGLConfig

class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private var renderer: ProfileRenderer? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var camera: CameraEngine? = null
    private var params: GpuParams? = null

    /** Set when ProfileRenderer creation failed (shader/driver issue) — diagnostics. */
    var lastInitError: Throwable? = null
        private set

    /** UV window (cx, cy, sx, sy) of the on-screen capture box, for the vignette. */
    @Volatile private var vignetteWindow: FloatArray? = null

    /**
     * Camera2 OES standard transform matrix, refreshed every frame from
     * [SurfaceTexture.getTransformMatrix]. Sent to the OES vertex shader
     * (passthrough_oes.vert) so the camera image is rotated/mirrored/cropped
     * exactly as the sensor produced it — no more hand-rolled UV rotations
     * (which were the cause of the real-device "strip preview" deformation).
     */
    private val stMatrix = FloatArray(16)

    fun setVignetteWindow(cx: Float, cy: Float, sx: Float, sy: Float) {
        vignetteWindow = floatArrayOf(cx, cy, sx, sy)
    }

    private val frameListener = SurfaceTexture.OnFrameAvailableListener {
        requestRender()
    }

    init {
        setEGLContextClientVersion(3)
        // Device-adaptive EGL config: some new GPUs/drivers reject the
        // hardcoded RGBA8888+depth16 combo - fall back through progressively
        // smaller configs instead of failing surface creation outright.
        setEGLConfigChooser(FallbackConfigChooser())
        preserveEGLContextOnPause = true
        com.photographercamera.core.device.DeviceCompat.initSystem(context.applicationContext)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: EGLConfig?) {
                // Display scheduling class for the GL thread: keeps render
                // submission ahead of background work on big.LITTLE soellers.
                com.photographercamera.core.device.DeviceCompat.applyDisplayThreadPriority()
                // Identify the GPU while the context is current (Adreno / Mali /
                // PowerVR / Intel / translator drivers) for quirks and logs.
                com.photographercamera.core.device.DeviceCompat.initGlInfo(
                    android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER),
                    android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION),
                )
                com.photographercamera.core.debug.DebugLog.log(
                    "GL",
                    com.photographercamera.core.device.DeviceCompat.summarize(),
                )
                val tex = IntArray(1)
                GLES30.glGenTextures(1, tex, 0)
                oesTex = tex[0]
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                // Create the OES SurfaceTexture ON the GL thread so it is bound to this GL
                // context from the start — this avoids SurfaceTexture.attachToGLContext(),
                // which throws "Error during attachToGLContext" once the camera producer is
                // already attached to the buffer queue.
                val st = SurfaceTexture(oesTex)
                surfaceTexture = st
                st.setOnFrameAvailableListener(frameListener)
                // Shader compile/link differs per GPU driver (ANGLE on emulators vs
                // Adreno/Mali on real devices). A failure here must NOT kill the GL
                // thread — degrade to a null renderer (black preview, app alive) and
                // keep the error for diagnostics.
                try {
                    renderer = ProfileRenderer.create(context.assets)
                    com.photographercamera.core.debug.DebugLog.log("GL", "renderer ready, OES tex=$oesTex")
                } catch (t: Throwable) {
                    android.util.Log.e("PC_GL", "ProfileRenderer init failed — preview disabled", t)
                    com.photographercamera.core.debug.DebugLog.logError("GL", "ProfileRenderer init failed — preview disabled", t)
                    renderer = null
                    lastInitError = t
                }
                // Hand the texture to the camera and start the preview pipeline.
                camera?.setSurfaceTexture(st)
                try {
                    camera?.open()
                } catch (t: Throwable) {
                    android.util.Log.e("PC_GL", "camera open failed", t)
                }
            }

            override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
                GLES30.glViewport(0, 0, w, h)
                com.photographercamera.core.debug.DebugLog.log("GL", "surface changed ${w}x${h}")
            }

            private var firstFrameLogged = false
            // Debug layer stepper: poll pc_layers.txt ~1x/sec so the chain can be
            // bisected LIVE on-device (0=passthrough .. 7=full) without rebuilding.
            private var frameCount = 0
            // Frame-arrival diagnostics (0-layer flat => camera feed is suspect #1):
            // ts advancing = SurfaceTexture receives frames; txErr>0 = updateTexImage
            // throwing every frame (silent catch used to hide exactly this).
            private var txErrCount = 0
            private var lastTs = -1L

            override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                val st = surfaceTexture ?: return
                val r = renderer ?: return
                val p = params
                if (++frameCount % 30 == 0) {
                    // Debug layer stepper: read filesDir/pc_layers.txt (adb run-as
                    // writable on debug builds) ~1x/sec; 0=passthrough .. 7=full.
                    try {
                        val f = java.io.File(context.filesDir, "pc_layers.txt")
                        if (f.exists()) {
                            val v = f.readText().trim().toIntOrNull()
                            if (v != null) r.setDebugLayerLimit(v)
                        } else if (frameCount == 30) {
                            com.photographercamera.core.debug.DebugLog.log(
                                "GL", "layers file absent: ${f.absolutePath}",
                            )
                        }
                    } catch (t: Throwable) {
                        if (frameCount == 30) {
                            com.photographercamera.core.debug.DebugLog.logError("GL", "layers file read failed", t)
                        }
                    }
                    // probe a LIVE frame (camera streaming + layer limit applied)
                    if (frameCount == 90) r.requestProbe()
                    val ts = st.timestamp
                    com.photographercamera.core.debug.DebugLog.log(
                        "GL",
                        "frames: n=$frameCount ts=$ts delta=${ts - lastTs} txErr=$txErrCount",
                    )
                    lastTs = ts
                }
                try {
                    st.updateTexImage()
                } catch (e: Exception) {
                    // A single bad frame (e.g. SurfaceTexture detached during lens switch)
                    // must not permanently kill the GL thread; skip and retry next frame.
                    txErrCount++
                    return
                }
                // Standard Camera2 OES transform: HAL publish the rotation/mirror
                // matrix per frame; the vertex shader applies it.
                st.getTransformMatrix(stMatrix)
                val t = st.timestamp
                // texture buffer size comes from the engine (may differ per lens);
                // the renderer uses it to compute the aspect-cover window.
                val ts = camera?.previewSize ?: android.util.Size(width, height)
                if (p == null) {
                    // No profile applied yet: show the raw camera instead of a
                    // black frame (black preview used to hide every other bug).
                    r.renderPassthroughPreview(oesTex, width, height, stMatrix, ts.width, ts.height)
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        com.photographercamera.core.debug.DebugLog.log(
                            "GL",
                            "first frame PASSTHROUGH (no profile params yet) buf=${ts.width}x${ts.height}",
                        )
                    }
                    return
                }
                r.renderPreview(oesTex, width, height, p, stMatrix, t / 1_000_000, ts.width, ts.height, vignetteWindow)
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    com.photographercamera.core.debug.DebugLog.log(
                        "GL",
                        "first frame CHAIN rendered buf=${ts.width}x${ts.height} view=${width}x${height}",
                    )
                }
            }
        })
        // Render continuously so the preview never depends on the frame-available
        // callback firing (some devices/drivers only deliver intermittently); this
        // also guarantees the view keeps refreshing even if a single frame is bad.
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
    }

    /**
     * Bind the OES camera frames to the GL pipeline. The SurfaceTexture is now created
     * directly on the GL thread in onSurfaceCreated (so it is GL-context-bound from the
     * start) and handed to the CameraEngine via setSurfaceTexture(); the old
     * attachToGLContext() path is removed because it throws on some devices/emulators.
     */

    fun setCameraEngine(engine: CameraEngine) {
        camera = engine
        // Listen for preview resolution changes so the GL thread can pick up
        // the actual CameraX-negotiated size (instead of the 1600x1200 default).
        engine.onPreviewResolutionChanged = { _, _ ->
            // Request a re-render so onDrawFrame picks up the updated previewSize.
            requestRender()
        }
    }

    fun setProfile(gpuParams: GpuParams) {
        params = gpuParams
    }

    /**
     * Capture the current frame through the full chain and return it as a Bitmap.
     * Must be called on the GL thread (or immediately after a frame is available).
     */
    fun captureCurrentFrame(onCaptured: (Bitmap) -> Unit) {
        queueEvent {
            val st = surfaceTexture ?: return@queueEvent
            val r = renderer ?: return@queueEvent
            val p = params ?: return@queueEvent
            try {
                st.updateTexImage()
            } catch (_: Exception) {
                return@queueEvent
            }
            st.getTransformMatrix(stMatrix)
            val ts = camera?.previewSize ?: android.util.Size(width, height)
            val bmp = try {
                r.renderToBitmap(oesTex, width, height, p, stMatrix, st.timestamp / 1_000_000, ts.width, ts.height)
            } catch (t: Throwable) {
                android.util.Log.e("PC_RENDER", "renderToBitmap failed — delivering blank frame", t)
                Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)
            }
            onCaptured(bmp)
        }
    }

    /**
     * Render an already-decoded high-resolution still (e.g. a 4K JPEG from the
     * dedicated still reader) through the SAME color chain as the preview, so the
     * saved photo matches the on-screen look. Runs on the GL thread; [onDone]
     * receives the processed Bitmap at the still's native resolution.
     *
     * Real-device memory safety: at 4K the chain peaks at ~450MB (3× RGBA16F
     * intermediates + RGBA8 readback + output bitmap). Devices that cannot
     * allocate that throw (OOM is an Error, not an Exception) — so we retry
     * progressively at half resolution, and as a last resort deliver the
     * UNPROCESSED downscaled still. A photo that saves always beats a crash.
     */
    /**
     * RAW ISP capture: develop the untouched Bayer frame on the GPU
     * (raw_isp.frag: black level -> WB -> demosaic -> CCM -> gamma) and run
     * the recipe chain on top. A null renderer or an unsupported raw size
     * returns a 1x1 bitmap so the caller falls back to the preview-frame
     * capture path (the same signal the GPU-chain-SKIPPED branch uses).
     */
    fun renderRawThroughChain(frame: RawFrame, onDone: (Bitmap) -> Unit) {
        queueEvent {
            val r = renderer
            val p = params
            if (r == null || p == null) {
                com.photographercamera.core.debug.DebugLog.log(
                    "SHOT",
                    "RAW ISP SKIPPED (renderer=${r != null}, params=${p != null}) - falling back",
                )
                onDone(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                return@queueEvent
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = try {
                r.renderRawChain(
                    frame.bayer,
                    frame.width,
                    frame.height,
                    frame.rowStride,
                    frame.rotDeg,
                    frame.calib,
                    p,
                    t0,
                )
            } catch (t: Throwable) {
                com.photographercamera.core.debug.DebugLog.logError("SHOT", "RAW ISP render failed", t)
                null
            }
            if (out == null) {
                com.photographercamera.core.debug.DebugLog.log("SHOT", "RAW ISP unavailable - falling back")
                onDone(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            } else {
                com.photographercamera.core.debug.DebugLog.log(
                    "SHOT",
                    "RAW ISP + chain done ${out.width}x${out.height} in ${android.os.SystemClock.elapsedRealtime() - t0}ms",
                )
                onDone(out)
            }
        }
    }

    fun renderBitmapThroughChain(bmp: Bitmap, zoom: Float = 1f, onDone: (Bitmap) -> Unit) {
        queueEvent {
            val r = renderer
            val p = params
            // HARDWARE bitmaps (API 26+) cannot copyPixelsToBuffer / GL-upload -
            // copy to a software ARGB_8888 bitmap first (no-op on most devices).
            val input = if (bmp.config == Bitmap.Config.HARDWARE) {
                try {
                    bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                } catch (_: Throwable) {
                    bmp
                }
            } else bmp
            if (r == null || p == null) {
                // unprocessed still — every filter looks identical in this path,
                // so it MUST be visible in the log when it happens
                com.photographercamera.core.debug.DebugLog.log(
                    "SHOT",
                    "GPU chain SKIPPED (renderer=${r != null}, params=${p != null}) — saving unprocessed still ${input.width}x${input.height}",
                )
                onDone(input)
                return@queueEvent
            }
            // Render target: PORTRAIT 3:4 (the final saved ratio), long side derived
            // from the still itself (data-driven, capped by the DEVICE BUDGET - see
            // DeviceCompat.recomputeCaptureBudget: 2880 normal, 2160 on low-RAM or
            // translator-GPU devices - to bound GPU memory where allocation fails).
            val longSide = maxOf(input.width, input.height).coerceIn(720, com.photographercamera.core.device.DeviceCompat.captureMaxLongSidePx)
            val tw = longSide * 3 / 4
            val th = longSide
            // CPU-side crop-to-3:4 + downscale BEFORE the GPU upload: the chain then
            // runs at ~2160x2880 instead of the full 4096x3072 sensor frame —
            // ~2x less RGBA16F bandwidth, measurably faster shots, same output.
            // (renderBitmap keeps the full source size for its buffers otherwise.)
            val targetAR = tw.toFloat() / th.toFloat()
            val srcAR = input.width.toFloat() / input.height
            var base = input
            if (kotlin.math.abs(srcAR - targetAR) > 0.01f) {
                try {
                    val cw: Int; val ch: Int; val l: Int; val t: Int
                    if (srcAR > targetAR) {
                        ch = input.height; cw = (ch * targetAR).toInt()
                        l = (input.width - cw) / 2; t = 0
                    } else {
                        cw = input.width; ch = (cw / targetAR).toInt()
                        l = 0; t = (input.height - ch) / 2
                    }
                    base = Bitmap.createBitmap(input, l, t, cw, ch)
                } catch (_: Throwable) {
                    base = input // crop failed — fall through, GPU cover handles it
                }
            }
            if (base.width != tw || base.height != th) {
                base = Bitmap.createScaledBitmap(base, tw, th, true) ?: base
            }
            // DAZZ-pattern zoom crop: the still was captured at the session's
            // native 1x FOV, so crop the SAME centered 1/zoom fraction the
            // viewfinder capture box shows. Applied after the 3:4 crop so both
            // operate in the upright portrait frame; no upscaling afterwards
            // (a 4x crop saves a 4x-smaller photo - real digital-zoom behaviour).
            val z = zoom.coerceAtLeast(1f)
            if (z > 1.001f && base.width > 64 && base.height > 64) {
                val s = 1f / z
                val cw = (base.width * s).toInt().coerceIn(64, base.width)
                val chh = (base.height * s).toInt().coerceIn(64, base.height)
                try {
                    base = Bitmap.createBitmap(
                        base, (base.width - cw) / 2, (base.height - chh) / 2, cw, chh,
                    )
                } catch (_: Throwable) {
                    // crop failed - save the unzoomed frame rather than nothing
                }
            }
            var src = base
            var attempt = 0
            fun avgRgb(b: Bitmap): String {
                return try {
                    val s = Bitmap.createScaledBitmap(b, 4, 4, true)
                    var r = 0; var g = 0; var bl = 0
                    for (y in 0 until 4) for (x in 0 until 4) {
                        val px = s.getPixel(x, y)
                        r += (px shr 16) and 0xFF; g += (px shr 8) and 0xFF; bl += px and 0xFF
                    }
                    "${r / 16},${g / 16},${bl / 16}"
                } catch (_: Throwable) { "?" }
            }
            com.photographercamera.core.debug.DebugLog.log(
                "SHOT", "chain input avg RGB=${avgRgb(base)} ${base.width}x${base.height}",
            )
            while (true) {
                try {
                    val out = r.renderBitmap(src, p, src.width, src.height, System.nanoTime() / 1_000_000)
                    com.photographercamera.core.debug.DebugLog.log(
                        "SHOT", "chain output avg RGB=${avgRgb(out)}",
                    )
                    onDone(out)
                    return@queueEvent
                } catch (t: Throwable) {
                    attempt++
                    android.util.Log.e("PC_RENDER", "renderBitmap failed (attempt $attempt, ${src.width}x${src.height})", t)
                    com.photographercamera.core.debug.DebugLog.logError(
                        "SHOT",
                        "GPU chain attempt $attempt failed at ${src.width}x${src.height} -> ${tw}x$th",
                        t,
                    )
                    if (attempt >= 3) {
                        // last resort: deliver the unprocessed (already downscaled) still
                        onDone(src)
                        return@queueEvent
                    }
                    val nw = (src.width / 2).coerceAtLeast(320)
                    val nh = (src.height / 2).coerceAtLeast(240)
                    src = Bitmap.createScaledBitmap(src, nw, nh, true) ?: run {
                        onDone(base); return@queueEvent
                    }
                }
            }
        }
    }

    /**
     * UV rotation aligning the camera buffer to the display — DEPRECATED.
     *
     * Sensor orientation + mirror are now handled by the HAL via
     * [SurfaceTexture.getTransformMatrix], which is fed straight into the
     * vertex shader as mat4 u_stMatrix. The previous hand-rolled mat2 u_rot
     * was the cause of the real-device "preview rendered as a stretched
     * horizontal strip" bug (worked fine on the ANGLE emulator only).
     * Kept here as a comment for grep-time archaeology.
     */

    override fun onDetachedFromWindow() {
        renderer?.release()
        // The OES SurfaceTexture was created by this view on the GL thread; release it
        // here so the GL resources are torn down together with the surface.
        surfaceTexture?.release()
        surfaceTexture = null
        super.onDetachedFromWindow()
    }
}

/**
 * Device-adaptive EGLConfig chooser. Tries progressively smaller color/depth
 * combinations - some new GPU drivers and translator drivers reject the
 * classic RGBA8888+depth16 request, which used to kill the preview surface
 * before the renderer even ran.
 */
private class FallbackConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10, display: javax.microedition.khronos.egl.EGLDisplay): EGLConfig {
        // EGL_OPENGL_ES3_BIT = 0x40; keep ES2 bit (0x04) as well for old drivers.
        val es3 = 0x40
        val candidates = listOf(
            intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_STENCIL_SIZE, 0,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, es3,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            ),
            intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 0,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, es3,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            ),
            intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 0,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            ),
            intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 5,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 6,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 5,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            ),
        )
        for (attrs in candidates) {
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, attrs, null, 0, num) && num[0] > 0) {
                val configs = arrayOfNulls<EGLConfig>(num[0])
                if (egl.eglChooseConfig(display, attrs, configs, num[0], num) && num[0] > 0) {
                    return configs[0]!!
                }
            }
        }
        throw RuntimeException("DeviceCompat: no usable EGLConfig on ${android.os.Build.MODEL}")
    }
}
