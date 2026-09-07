package com.photographercamera.photon.raw

const val RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV = 0.7f

enum class RawExposureCompensationDomain {
    Curve,
    Linear
}

enum class RawRenderingEngine(
    val shaderId: Int,
    val workingColorSpace: ColorSpace,
    val defaultExposureCompensationEv: Float,
    val exposureCompensationDomain: RawExposureCompensationDomain
) {
    AdobeCurve(
        shaderId = 0,
        workingColorSpace = ColorSpace.ProPhoto,
        defaultExposureCompensationEv = 0f,
        exposureCompensationDomain = RawExposureCompensationDomain.Curve
    ),
    HncsCcm(
        shaderId = 5,
        workingColorSpace = ColorSpace.HNCS,
        defaultExposureCompensationEv = 0f,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    HncsLut(
        shaderId = 6,
        workingColorSpace = ColorSpace.HNCS,
        defaultExposureCompensationEv = 0f,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    AgX(
        shaderId = 1,
        workingColorSpace = ColorSpace.BT2020,
        defaultExposureCompensationEv = RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    Spektrafilm(
        shaderId = 2,
        workingColorSpace = ColorSpace.ProPhoto,
        defaultExposureCompensationEv = RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    DarktableSigmoid(
        shaderId = 3,
        workingColorSpace = ColorSpace.BT2020,
        defaultExposureCompensationEv = RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    DarktableFilmic(
        shaderId = 4,
        workingColorSpace = ColorSpace.BT2020,
        defaultExposureCompensationEv = RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV,
        exposureCompensationDomain = RawExposureCompensationDomain.Linear
    ),
    ;

    val isHncs: Boolean
        get() = this == HncsCcm || this == HncsLut

    val usesHncsColorMap: Boolean
        get() = this == HncsLut

    companion object {
        fun fromPersistedName(
            value: String?,
            // 0.9.9：渲染引擎默认 AgX（用户指令）；AgX 曲线参数沿用上游默认
            // （black -10 / white 6.5 / toe 1.5 / shoulder 3.3）。
            fallback: RawRenderingEngine = AgX
        ): RawRenderingEngine {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback
        }
    }
}
