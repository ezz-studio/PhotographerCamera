/**
 * GpuFilterChain — a lightweight GPU filter-pipeline abstraction.
 *
 * ARCHITECTURE PORTED FROM android-gpuimage-plus
 * (https://github.com/wysaid/android-gpuimage-plus, cloned into
 *  vendor/android-gpuimage-plus). Upstream models the imaging pipeline as:
 *
 *   - `CGEImageHandler` (library/src/main/jni/cge/common/cgeImageHandler.{h,cpp})
 *     keeps an ordered `std::vector<CGEImageFilterInterfaceAbstract*> m_vecFilters`
 *     and runs each filter in turn, ping-ponging between TWO framebuffers via
 *     `swapBufferFBO()` so every filter reads the PREVIOUS filter's output and
 *     writes to the other buffer. `drawResult()` finally blits the chain output.
 *
 *   - `CGEImageFilterInterfaceAbstract::render2Texture(handler, srcTexture,
 *     vertexBufferID)` (cgeImageFilter.{h,cpp}) is the per-pass hook each filter
 *     implements.
 *
 * This Kotlin port reproduces that exact pattern with a [GpuFilter] list and a
 * double-buffered (A/B) RGBA8 framebuffer pair. It is intentionally minimal —
 * it models ONLY the "ordered pass list + ping-pong FBO" machinery, which is
 * precisely what [ProfileRenderer]'s capture chains (YUV direct / RAW ISP) need:
 * a convert/source pass produces a frame, then an ordered tail of filters
 * (chroma-denoise -> unified effect shader) processes it into the readback FBO.
 *
 * It deliberately does NOT re-implement the upstream C++ shader/effect library;
 * the actual GL work stays in [ProfileRenderer]'s existing programs (yuv_copy,
 * chroma_denoise, effect) wrapped as [GpuFilter] instances.
 */
package com.photographercamera.core.gpu

import android.opengl.GLES30

/**
 * Mirrors `CGEImageFilterInterfaceAbstract` (cgeImageFilter.{h,cpp}) — one pass
 * of the pipeline. The upstream class has an init/release lifecycle around its
 * pure-virtual `render2Texture(handler, srcTexture, vertexBufferID)`; we model
 * that as [attach] / [render] / [release]. [attach] and [release] are no-ops by
 * default because the concrete filters here wrap programs owned by
 * [ProfileRenderer] and hold no GL resources of their own — only [render] is
 * mandatory. The chain calls [attach] when a filter is added and [release] when
 * the chain is cleared/released.
 */
interface GpuFilter {
    /** Called once when the filter is added to a chain. No-op by default. */
    fun attach() {}

    /** Render [srcTexture] into [dstFramebuffer] (size [w]x[h]). */
    fun render(srcTexture: Int, dstFramebuffer: Int, w: Int, h: Int)

    /** Called when the chain is cleared or released. No-op by default. */
    fun release() {}
}

/** Ported from `CGEImageHandler` (m_vecFilters + swapBufferFBO ping-pong). */
class GpuFilterChain {

    private val filters = mutableListOf<GpuFilter>()

    /** Append a pass. Mirrors `CGEImageHandler::addImageFilter`. */
    fun add(filter: GpuFilter): GpuFilterChain {
        filter.attach()
        filters.add(filter)
        return this
    }

    fun clear() {
        for (f in filters) f.release()
        filters.clear()
    }

    /** Delete the chain's GL resources (call from the renderer's release()). */
    fun release() {
        for (f in filters) f.release()
        filters.clear()
        if (texA != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texA), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
            texA = 0; fboA = 0
        }
        if (texB != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texB), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
            texB = 0; fboB = 0
        }
        bufW = 0; bufH = 0
        filters.clear()
    }

    // Double-buffered intermediate RGBA8 pair — mirrors the handler's
    // swapBufferFBO() ping-pong (read from one, write to the other).
    private var texA = 0
    private var fboA = 0
    private var texB = 0
    private var fboB = 0
    private var bufW = 0
    private var bufH = 0

    private fun ensure(w: Int, h: Int) {
        if (texA != 0 && bufW == w && bufH == h) return
        if (texA != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texA), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        }
        if (texB != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texB), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        }
        texA = createRgba8(w, h); fboA = createFbo(texA)
        texB = createRgba8(w, h); fboB = createFbo(texB)
        bufW = w; bufH = h
    }

    /**
     * Run the chain in order. [inputTex] feeds the first filter (a source filter
     * may ignore it). All but the LAST filter ping-pong on the internal A/B
     * buffer pair; the LAST filter renders into [finalFbo] (the caller's
     * readback target), mirroring how `CGEImageHandler::drawResult` writes the
     * chain output to its destination.
     *
     * @return [finalFbo] (the chain's final output).
     */
    fun process(inputTex: Int, finalFbo: Int, w: Int, h: Int): Int {
        if (filters.isEmpty()) return finalFbo
        ensure(w, h)
        var read = inputTex
        var useB = false
        for (i in filters.indices) {
            val last = i == filters.lastIndex
            val dstFbo = if (last) finalFbo else if (useB) fboB else fboA
            val dstTex = if (last) 0 else if (useB) texB else texA
            filters[i].render(read, dstFbo, w, h)
            if (!last) {
                read = dstTex
                useB = !useB
            }
        }
        return finalFbo
    }

    private fun createRgba8(w: Int, h: Int): Int {
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
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return fbo[0]
    }
}
