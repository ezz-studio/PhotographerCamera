# 交接卡：Android 端 lens 光学阶段 + 参数链补齐

> 日期：2026-09-06　来源：桌面端 Studio「lens/halation/bloom/sharpen 参数调节无效」修复轮
> 桌面端已完成（tools/profile_renderer.py），Android 端需要照此对齐。

## 1. 背景

- Schema（profiles/schema/photographer_profile.schema.json）定义了 `lens` section：
  `vignette / chromatic_aberration / sharpness_falloff / distortion / bloom / flare`（均 0~1，distortion -1~1）
- 生成器产出的 profile 已携带该 section，桌面 UI 有 6 个滑块
- 但 effect.frag（GPU 链）和 CPU 参考渲染器**都没有读 lens** → 滑块是死参数
- 本轮桌面端已实现 CPU 版 `apply_lens()`；App 端渲染需补齐同一阶段，否则「同一配置文件风格一致」承诺在 App 端不成立

## 2. 桌面端已完成（参考实现）

`tools/profile_renderer.py`：

- **`apply_lens(rgb, lens)`** —— Stage 0，位于一切风格化之前（光学阶段先于曝光/WB/曲线）
  - `distortion`：径向畸变，barrel(+) / pincushion(-)，`f = 1 + 0.35k·r²`，cv2.remap + BORDER_REFLECT
  - `chromatic_aberration`：R 径向内缩 `1 - 0.015ca`、B 外扩 `1 + 0.015ca`、G 不动（径向 remap）
  - `sharpness_falloff`：`r > 0.25` 后向角落渐进模糊，权重 `min(1.2f·(r-0.25), 0.85)`
  - `vignette`：自然光学暗角（乘法），`1 - v·0.75·clip(r-0.3)¹·⁵`
  - `bloom`：宽口径光学溢光，门限 0.82、核 `14·rel_scale` px、增益 `0.8·lb`
  - `flare`：水平变形条纹（核 `36×4·rel_scale`）+ 暖核心辉光，冷条纹 `[0.85,0.92,1.0]`、暖辉光 `[1.0,0.92,0.8]`
- **分辨率相对缩放**：`_rel_scale(rgb) = clamp(maxside/400, 1, 6)`，
  `apply_sharpen / apply_bloom / apply_halation` 的核大小已按此缩放（GPU 本来就是 texel 相对，语义对齐；App 端无需改）
- lens 全零时比特级恒等（`np.array_equal` 验证过），不影响未调参的既有 profile

## 3. Android 端待办

1. **effect.frag 增加 lens 阶段**（Stage 0，在 pc_exposure 之前）：
   - distortion / CA 需要 uv 重映射采样 `u_input`（单 pass 内可做：按 `f = 1+0.35k·r²` 重算 uv 后 texture() 采样；CA 按通道分别偏移采样三次）
   - flare 的水平条纹核较大，建议沿用现有 bloom 的降采样提取纹理（extract_blur 路径）或单独 pass
2. **GpuParams / ProfileRenderer 接线**：新增 uniforms
   `u_lensDistortion / u_lensCA / u_lensFalloff / u_lensVignette / u_lensBloom / u_lensFlare`
   从 profile.lens.* 读取（缺省全 0 = 阶段直通）
3. **链序**：lens 必须在 exposure 之前（光学 → 风格化），与 CPU 参考一致
4. **验收标准**：同一 profile + 同一照片，App 渲染 ≈ 桌面预览（允许 GPU/CPU 采样差异，结构一致即可）；
   lens 全零时与旧版本渲染逐像素一致（早退分支）

## 4. 注意

- `lens.bloom` 与 `bloom.amount`、`lens.vignette` 与 `vignette.amount` 是**独立参数**（光学模拟 vs 风格化），
  App 端两个阶段都要接，不要合并
- LUT 导出（/api/export_lut）不包含 lens/bloom/halation 等空间域效果——这是既有设计，不变
