/*
 * FilmParamsStore — 0.9.1 新增（自研胶水，与 LensParamsStore 同模式）。
 *
 * ColorRecipeParams 的 JSON 桥（CameraScreen: toJson→fromJson）会把 halation
 * 强制抹零（上游 ColorRecipeParams.toJson 的兼容设计），且 film_curve 端点、
 * grain 颗粒度、相机原色矩阵、shadow.saturation 在 recipe/JSON 通道里没有
 * 对应字段——这些 profile 参数经本单例下发，预览（LutRenderer）与成片
 * （LutImageProcessor）读取。
 *
 * 单例桥接避免改动 photon 移植树的调用面——与 LensParamsStore 一致，
 * 实机验证后可升级为按 lutId 持久化。
 */
package com.photographercamera.core.photon.color

object FilmParamsStore {

    /** 全默认值（未注入 profile / profile 卸载后的回退），halation 链保持关闭。 */
    data class FilmParams(
        val halationStrength: Float = 0f,
        val halationRadius: Float = 1f,
        val halationThreshold: Float = 0.9f,
        val halationWarmth: Float = 1f,
        val grainSize: Float = 1f,
        val grainDensity: Float = 1f,
        val shadowSaturation: Float = 1f,
        val filmCurveShadowFloor: Float = 8f,
        val filmCurveHighlightCeiling: Float = 248f,
        val colorMatrix3x3: List<List<Float>> = listOf(
            listOf(1f, 0f, 0f),
            listOf(0f, 1f, 0f),
            listOf(0f, 0f, 1f),
        ),
        /**
         * 0.9.2：profile 注入标记。film_curve 默认端点（8/248）本身不是恒等曲线，
         * 无法用值区分"未注入 profile"与"注入了默认端点的 profile"——用显式标记
         * 门控 film 曲线消费端，保证非 profile 状态下引擎行为与上游一致。
         */
        val profileActive: Boolean = false,
        // ===== 0.9.17：补通桌面端 profile_renderer.py 已实现而 Android 端未接线的参数 =====
        /** sharpen.radius（0.5..3），SrgbSharpnessShader uRadius。 */
        val sharpenRadius: Float = 1f,
        /** vignette.radius（0..1，桌面 dist 归一半径），成片/预览 vignette shader。 */
        val vignetteRadius: Float = 1f,
        /** vignette.feather（0..1，边缘过渡带宽度）。 */
        val vignetteFeather: Float = 0.5f,
        /** vignette.center（0..1×2，uv 空间，y 自顶行起——与桌面 UV 约定一致）。 */
        val vignetteCenterX: Float = 0.5f,
        val vignetteCenterY: Float = 0.5f,
        /** bloom.threshold（0..1，桌面高光提取阈值）；null=未注入，走上游默认 0.9。 */
        val bloomThreshold: Float? = null,
        /** bloom.radius（0.5..4，桌面高斯核半径）；null=未注入，radius 由 bloom 强度推导。 */
        val bloomRadius: Float? = null,
    ) {
        val isIdentity: Boolean
            get() = halationStrength <= 0f && grainSize == 1f && grainDensity == 1f &&
                shadowSaturation == 1f && filmCurveShadowFloor == 8f &&
                filmCurveHighlightCeiling == 248f && colorMatrix3x3 == IDENTITY_MATRIX
    }

    @Volatile
    var current: FilmParams = FilmParams()

    /** profile 卸载 / 切到非 profile lut 时回默认，防残留毒化。 */
    fun reset() {
        current = FilmParams()
    }

    /**
     * 0.9.2：把 profile.color_matrix 复合进传感器校准矩阵（列主序 FloatArray）。
     *
     * shader 端 applyPrimaryCalibration 以 `uPrimaryCalibrationMatrix * v`（列向量）
     * 应用该矩阵，等价桌面端 profile_renderer.apply_color_matrix 的 `rgb @ M.T`
     * （= 列向量左乘 M）。复合顺序：校准先行、profile 矩阵后乘——out = P @ (C @ v)。
     * profile 未注入（单位阵）时原样返回校准矩阵，与上游行为逐字节一致。
     * 注：shader 侧在线性光空间应用（上游设计），与桌面端显示域应用存在二阶差异，
     * 实机 A/B 如见色相偏差再评估独立 gamma 域挂载点。
     */
    fun composeWithProfileColorMatrix(calibrationColumnMajor: FloatArray): FloatArray {
        val m = current.colorMatrix3x3
        if (m == IDENTITY_MATRIX) return calibrationColumnMajor
        if (m.size != 3 || m.any { it.size != 3 }) return calibrationColumnMajor
        // profile 矩阵（行主序）取元素
        val p00 = m[0][0]; val p01 = m[0][1]; val p02 = m[0][2]
        val p10 = m[1][0]; val p11 = m[1][1]; val p12 = m[1][2]
        val p20 = m[2][0]; val p21 = m[2][1]; val p22 = m[2][2]
        // 校准矩阵（列主序）还原行主序元素：C[row][col] = a[col * 3 + row]
        val c00 = calibrationColumnMajor[0]; val c01 = calibrationColumnMajor[3]; val c02 = calibrationColumnMajor[6]
        val c10 = calibrationColumnMajor[1]; val c11 = calibrationColumnMajor[4]; val c12 = calibrationColumnMajor[7]
        val c20 = calibrationColumnMajor[2]; val c21 = calibrationColumnMajor[5]; val c22 = calibrationColumnMajor[8]
        // (P @ C)[r][c] = Σk P[r][k] * C[k][c]
        val r00 = p00 * c00 + p01 * c10 + p02 * c20
        val r01 = p00 * c01 + p01 * c11 + p02 * c21
        val r02 = p00 * c02 + p01 * c12 + p02 * c22
        val r10 = p10 * c00 + p11 * c10 + p12 * c20
        val r11 = p10 * c01 + p11 * c11 + p12 * c21
        val r12 = p10 * c02 + p11 * c12 + p12 * c22
        val r20 = p20 * c00 + p21 * c10 + p22 * c20
        val r21 = p20 * c01 + p21 * c11 + p22 * c21
        val r22 = p20 * c02 + p21 * c12 + p22 * c22
        // 列主序输出：out[col * 3 + row] = R[row][col]
        return floatArrayOf(
            r00, r10, r20,
            r01, r11, r21,
            r02, r12, r22,
        )
    }

    private val IDENTITY_MATRIX = listOf(
        listOf(1f, 0f, 0f),
        listOf(0f, 1f, 0f),
        listOf(0f, 0f, 1f),
    )
}
