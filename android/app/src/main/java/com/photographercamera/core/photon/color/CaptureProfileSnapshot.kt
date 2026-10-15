/*
 * CaptureProfileSnapshot — Phase B（成像管线治理需求 2.7）不可变拍摄快照。
 *
 * 背景：Profile 参数此前经三个进程级可变单例下发（FilmParamsStore.current /
 * LensParamsStore.current / VM 的 currentRecipeParams）。快门触发后的异步多帧
 * 处理、RAW 显影与最终保存期间，用户可能切换 profile 或拖动参数面板，导致
 * 同一张照片的前半段与后半段使用不同 Profile 参数（参数竞争 / 状态错配）。
 *
 * 本快照在快门触发时由 CameraViewModel.createCaptureProfileSnapshot() 一次性
 * 冻结全部生效参数（Profile ID + Recipe + Film + Lens + 版本标识），并经
 * CaptureProfileStore.current 下达成片渲染链（LutImageProcessor 优先读快照，
 * 快照为空时回退单例——预览与相册路径不受影响）。禁止处理到一半时读取另一份
 * Profile 参数。
 */
package com.photographercamera.core.photon.color

import com.photographercamera.core.photon.lens.LensParams
import com.photographercamera.photon.model.ColorRecipeParams

/**
 * 一次拍摄的不可变 Profile 参数集合。
 *
 * @param profileId 当前 LUT / profile 标识（如 "profile:Nickshih"；原生档为 "native"）
 * @param recipe 色彩配方（photon.model.ColorRecipeParams，含快捷面板 EV/Grain 叠加后的生效值）
 * @param film FilmParams（halation/grain/film_curve/color_matrix/vignette/bloom 残余参数）
 * @param lens LensParams（光学阶段：畸变/暗角/色差等）
 * @param version 单调递增版本号（AtomicLong），用于日志与元数据追溯
 */
data class CaptureProfileSnapshot(
    val profileId: String,
    val recipe: ColorRecipeParams,
    val film: FilmParamsStore.FilmParams,
    val lens: LensParams,
    val version: Long,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 与 FilmParams.profileActive 同义：本快照是否携带活跃 profile 风格。 */
    val isProfileActive: Boolean
        get() = film.profileActive
}

/**
 * 拍摄快照单例桥（与 FilmParamsStore / LensParamsStore 同模式）：
 * VM 在快门触发时写入，LutImageProcessor 成片渲染时优先读取；
 * 预览（LutRenderer）与相册重渲染不读本桥，继续跟随活参数单例。
 */
object CaptureProfileStore {

    @Volatile
    var current: CaptureProfileSnapshot? = null

    /** 导出烘焙等非拍摄路径调用，回退到活参数单例（保持既有行为）。 */
    fun clear() {
        current = null
    }
}
