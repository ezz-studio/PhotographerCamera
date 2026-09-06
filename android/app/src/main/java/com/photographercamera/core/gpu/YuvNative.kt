/**
 * YuvNative — YUV plane compaction backed by libyuv.
 *
 * Per the architecture refactor mandate, the hand-written plane reader in
 * [com.photographercamera.core.gpu.ProfileRenderer.compactPlane] is replaced
 * by code ported from Google's **libyuv** (https://github.com/lemenkov/libyuv,
 * cloned into vendor/libyuv). The two primitives we need are:
 *
 *   - [copyPlane]      — ported from libyuv `source/planar_functions.cc :: CopyPlane`
 *                        (rows copied honoring a per-row source stride; the C
 *                        version calls CopyRow_C = per-row memcpy). Used for the
 *                        tight/padded I420 case (pixelStride == 1).
 *   - [extractUPlane]  — ported from libyuv `source/row_common.cc :: SplitUVRow_C`
 *                        (de-interleave the U component out of an interleaved
 *                        UV buffer). Used for the NV12 case (pixelStride > 1).
 *
 * DEGRADATION PATH (NDK unavailable): this build environment has NO Android NDK
 * and NO CMake, so the C++ libyuv cannot be compiled into a shared library and
 * wrapped via JNI. We therefore ship the **pure-Kotlin port** of the same
 * algorithm (behavior-identical to libyuv's C) and keep
 * [com.photographercamera.core.gpu.ProfileRenderer.compactPlane] as the Java
 * fallback. The JNI hook ([nativeYuvPlaneCopy]) is reserved for the future NDK
 * build (externalNativeBuild + CMake wrapping vendor/libyuv) and is not wired
 * while [nativeAvailable] is false.
 *
 * Source attribution (every ported primitive is annotated at its definition):
 *   vendor/libyuv/source/planar_functions.cc  :: CopyPlane        (line 29)
 *   vendor/libyuv/source/planar_functions.cc  :: SplitUVPlane     (line 615)
 *   vendor/libyuv/source/row_common.cc        :: SplitUVRow_C     (line 2852)
 *   vendor/libyuv/source/row_common.cc        :: CopyRow_C        (line 3273)
 */
package com.photographercamera.core.gpu

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

object YuvNative {

    /**
     * True once `libYuvNative.so` is loaded. The NDK is absent in this build env,
     * so this stays false and [compactYuvPlane] uses the Kotlin libyuv port.
     * When a future NDK build adds externalNativeBuild, set this true once the
     * shared library loads and route [compactYuvPlane] through [nativeYuvPlaneCopy].
     */
    @Volatile var nativeAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("YuvNative")
            nativeAvailable = true
        } catch (_: Throwable) {
            nativeAvailable = false
        }
    }

    /**
     * JNI hook reserved for the NDK build. NOT wired while [nativeAvailable] is
     * false (declared external but never called, so class load is safe).
     * Signature (planned): extract a single Y/U/V plane from a direct
     * ByteBuffer into a compact outW*outH buffer, honoring rowStride /
     * pixelStride, exactly like the Kotlin port below.
     */
    // external fun nativeYuvPlaneCopy(
    //     src: ByteBuffer, srcRowStride: Int, srcPixelStride: Int,
    //     dst: ByteBuffer, outW: Int, outH: Int,
    // ): Boolean

    /**
     * Compact one Y/U/V plane (from an [Image.Plane]) into a tightly-packed
     * outW*outH direct ByteBuffer, honoring rowStride / pixelStride.
     *
     * Behavior mirrors [ProfileRenderer.compactPlane] (which is kept as the Java
     * fallback) but the actual copy is performed by libyuv-ported primitives
     * ([copyPlane] / [extractUPlane]) instead of ad-hoc loops.
     *
     * @return compact buffer, or null when the backing buffer is too small.
     */
    fun compactYuvPlane(plane: Image.Plane, outW: Int, outH: Int): ByteBuffer? {
        if (outW <= 0 || outH <= 0) return null
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // Pick the layout that FITS the actual buffer (mirrors compactPlane):
        // HALs occasionally report a padded rowStride for tightly-packed data
        // (and vice versa).
        val paddedFits = (outH - 1) * rowStride + (outW - 1) * pixelStride + 1 <= src.capacity()
        val effStride = if (paddedFits) rowStride else outW * pixelStride
        if ((outH - 1) * effStride + (outW - 1) * pixelStride + 1 > src.capacity()) return null

        val out = ByteBuffer.allocateDirect(outW * outH).order(ByteOrder.nativeOrder())
        // Duplicate so we never mutate the caller's plane buffer (compactPlane
        // resets it via clear(); the duplicate leaves the original pristine).
        val s = src.duplicate().apply { position(0) }
        if (pixelStride == 1) {
            // I420 (tight or padded): libyuv CopyPlane — per-row copy with src stride.
            copyPlane(s, effStride, out, outW, outW, outH)
        } else {
            // NV12 interleaved UV: libyuv SplitUVRow_C — de-interleave the U
            // component (plane[2] supplies V; see ProfileRenderer.renderYuvChain).
            extractUPlane(s, effStride, out, outW, outW, outH)
        }
        out.position(0)
        return out
    }

    /**
     * ported from libyuv `source/planar_functions.cc :: CopyPlane` (line 29)
     * + `source/row_common.cc :: CopyRow_C` (line 3273).
     *
     * CopyPlane runs, for every row y in [0, height):
     *     CopyRow(src + y*srcStride, dst + y*dstStride, width)
     * where CopyRow_C is a per-row memcpy. We elide the C version's row-coalesce
     * and negative-height branches (Android YUV planes are always positive-height
     * with distinct src/dst strides). The result is a per-row tight copy that
     * honors a padded source row stride — exactly what I420 plane compaction
     * needs.
     */
    /**
     * ported from libyuv `source/planar_functions.cc :: CopyPlane` (line 29)
     * + `source/row_common.cc :: CopyRow_C` (line 3273).
     *
     * CopyPlane runs, for every row y in [0, height):
     *     CopyRow(src + y*srcStride, dst + y*dstStride, width)
     * where CopyRow_C is a per-row memcpy of `width` bytes. We elide the C
     * version's negative-height (image inversion) branch — Android YUV planes
     * are always positive-height — but we DO mirror its row-coalesce
     * optimization: when both strides equal `width` the whole plane is one
     * contiguous `width*height` block, so a single bulk copy replaces the
     * per-row loop. This matches the original [ProfileRenderer.compactPlane]
     * tight-I420 fast path and avoids a per-byte perf regression.
     */
    private fun copyPlane(
        src: ByteBuffer,
        srcStride: Int,
        dst: ByteBuffer,
        dstStride: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        // libyuv CopyPlane coalesce: src/dst contiguous, one memcpy.
        if (srcStride == width && dstStride == width) {
            val n = width * height
            src.limit(n); src.position(0)
            dst.limit(n); dst.position(0)
            dst.put(src)
            dst.position(0); src.position(0)
            return
        }
        for (y in 0 until height) {
            val s = y * srcStride
            val d = y * dstStride
            for (x in 0 until width) {
                dst.put(d + x, src.get(s + x))
            }
        }
    }

    /**
     * ported from libyuv `source/row_common.cc :: SplitUVRow_C` (line 2852)
     * + `source/planar_functions.cc :: SplitUVPlane` (line 615).
     *
     * SplitUVRow_C expands an interleaved UV buffer (U0 V0 U1 V1 ...) into
     * separate U and V planes; for the U component it is:
     *     dst_u[x]   = src[0]; dst_u[x+1] = src[2];  (advance src by 4)
     * i.e. the U samples are the EVEN-indexed bytes. Our YUV_420_888 U plane
     * needs only those even bytes, so this port writes one U sample per output
     * pixel from src index `x*2` — behavior-identical to the original
     * compactPlane pixelStride>1 branch (out.put(src.get(rowStart + col*2))).
     */
    private fun extractUPlane(
        src: ByteBuffer,
        srcStride: Int,
        dst: ByteBuffer,
        dstStride: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        for (y in 0 until height) {
            val s = y * srcStride
            val d = y * dstStride
            for (x in 0 until width) {
                dst.put(d + x, src.get(s + x * 2))
            }
        }
    }
}
