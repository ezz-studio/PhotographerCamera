/*
 * PhotonStackPipeline — 0.5.0 新增（自研胶水）。
 *
 * 把 CameraEngine 的多帧 YUV 连拍（List<ImageProxy>）接入移植的
 * PhotonCamera 堆栈器（帧间对齐 + 时域合并 + 空间降噪）与调色引擎
 * （ColorRecipe），产出干净且带风格的成片位图。
 *
 * 注意：GlesYuvStacker 与 LutImageProcessor 各自持有独立 EGL context，
 * 因此本管线全程运行在普通后台线程即可（不占预览 GL 线程）。
 */
package com.photographercamera.core.photon

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.camera.core.ImageProxy
import com.photographercamera.core.debug.DebugLog
import com.photographercamera.core.gpu.GpuParams
import com.photographercamera.core.photon.color.LutImageProcessor
import com.photographercamera.core.photon.color.ProfileToRecipeMapper
import com.photographercamera.core.photon.stack.PhotonMultiFrameStacker
import com.photographercamera.core.photon.stack.SafeImage

object PhotonStackPipeline {

    private val recipeProcessor by lazy { LutImageProcessor() }

    /**
     * 多帧 YUV → 堆栈降噪 → recipe 调色 → 镜像 → 成片 Bitmap（upright，
     * 未过 FilmCurve —— 终层由 ProfileRenderer.renderPhotonChain 在 GL 线程
     * 完成，与其它成片路径共享输出一致性签名）。
     *
     * @param proxies 引擎连拍得到的 YUV_420_888 帧（未关闭，本方法读取后由
     *                调用方统一 close）
     * @param rotDeg  首帧 ImageInfo.rotationDegrees
     * @param mirror  前置摄像头水平翻转（与 YUV 单帧路径语义一致）
     * @param params  当前生效的 GpuParams（内含源 Profile，用于 recipe 映射）
     * @return 成片；null = 管线不可用（调用方回退单帧链）
     */
    fun process(proxies: List<ImageProxy>, rotDeg: Int, mirror: Boolean, params: GpuParams?): Bitmap? {
        if (proxies.isEmpty()) return null
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)

        // ---- 1) 多帧堆栈（PhotonCamera GlesYuvStacker）-----------------------
        val images = proxies.map { SafeImage(it.image ?: return null) }
        val stacked = PhotonMultiFrameStacker.processBurst(
            images = images,
            rotation = rotDeg,
            aspectRatio = null,
            enableSuperResolution = false,
            colorSpace = srgb,
        ) ?: run {
            DebugLog.log("PHOTON", "stack failed (${proxies.size} frames)")
            return null
        }

        // ---- 2) recipe 调色（PhotonCamera ColorRecipe + 我方 Profile 映射）----
        val profile = params?.sourceProfile
        var styled: Bitmap = stacked
        if (profile == null) {
            DebugLog.log("PHOTON", "no source profile - returning stacked bitmap unstyled")
        } else {
            val mapping = ProfileToRecipeMapper.map(profile)
            styled = try {
                kotlinx.coroutines.runBlocking {
                    recipeProcessor.applyLut(
                        bitmap = stacked,
                        lutConfig = null,
                        colorRecipeParams = mapping.recipe,
                    )
                }
            } catch (t: Throwable) {
                DebugLog.logError("PHOTON", "recipe render failed - returning stacked bitmap", t)
                stacked
            }
        }

        // ---- 3) 前置镜像（CPU 一次矩阵翻转，与单帧 YUV 路径的 u_mirror 对应）----
        if (mirror) {
            val m = android.graphics.Matrix().apply { postScale(-1f, 1f) }
            val flipped = Bitmap.createBitmap(styled, 0, 0, styled.width, styled.height, m, false)
            if (flipped !== styled) styled.recycle()
            styled = flipped
        }
        return styled
    }
}
