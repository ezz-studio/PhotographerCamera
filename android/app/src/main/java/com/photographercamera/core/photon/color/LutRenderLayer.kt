/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.lut/BaselineColorCorrection.kt 中的 LutRenderLayer
 * Licensed under the Apache License, Version 2.0.
 */
package com.photographercamera.core.photon.color

data class LutRenderLayer(
    val lutConfig: LutConfig?,
    val colorRecipeParams: ColorRecipeParams?,
)
