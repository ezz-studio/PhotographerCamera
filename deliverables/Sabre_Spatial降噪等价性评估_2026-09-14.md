# Sabre / Spatial 降噪：本仓 vs 上游 V25（a377781e9d）等价性评估报告

> 评估对象：`bjzhou/PhotonCamera` 上游 commit `a377781e9d`「feat: Sabre merged noise model and parameter validation」
> 评估基线：本仓 `PhotographerCamera`（1.7.0 / `26c35ae`）
> 目标：判断上游 Sabre 降噪优化在本仓是「已被自有管线覆盖」还是「确缺失/需定点移植」，并给出移植清单。同时核对 Spatial 路径算法是否一致。
> 约束：不得破坏现有 profile JSON 参数（噪声模型仍来自 DCP/RAW profile，本次只动合并数学与控制流）。

---

## 1. 结论速览

| 子系统 | 上游 V25（a377） | 本仓现状 | 等价性 |
|---|---|---|---|
| **Sabre 合并噪声模型** | `MgcSabreMergedNoiseModel.merge()` 真合并（逐帧相机-RGB read/shot 合成 + `snrCorrection`） | 旧标量启发式：`sabreAverageMergeFactor` + `referenceSnr/sqrt(scale)` | ❌ **缺失核心优化** |
| **Sabre QMC 角度位** | `qmcAngleBits()`（V25 真实算法，XOR 渐进高位掩码） | `Integer.reverse()` | ❌ **确切断缺失 bug** |
| **Sabre SNR 选择** | `outputNoiseModelSnr(green of merged model)` | `referenceSnr/sqrt(sabreNoiseModelScale)` | ❌ |
| **Sabre 下游噪声模型** | `mgcDenoiseReadNoise/ShotNoise` 直用合并模型 | `参考 Bayer 噪声 × 标量 scale` | ❌ |
| **Sabre schema 字段** | 已移除 `mgcSabreNoiseModelScale` | 仍存在并被多处消费 | ❌ 旧 schema |
| **Spatial 合并数学** | 未改动（GL 着色器 + native 不变） | 同 | ✅ **数学等价** |
| **Spatial 控制流/容错** | 单帧→identity；多帧→`check` 强校验；`finishRawDenoiseSnr` 强制 propagatedOutputSnr | 旧静默降级（fallback→identity / mergedSnr） | ⚠️ 仅容错差异 |
| **Spatial 参数校验** | `MgcSpatialStrengthMapGenerator` 增加输入 `require` | 仅基础 `valid` | ⚠️ 缺加固 |
| **Demosaic 噪声传播** | 上游无对应子系统 | 本仓自有 `DemosaicNoisePropagationCalibrator` | ➖ 独立，无冲突 |
| cbabd7fe77 设备配置子系统 | 新增 | 本仓走 `UserPreferencesRepository` | ➖ 不适用（vendor） |
| ee0c051ca3 Pixel5 噪声 profile | AGC Pixel5 设备 | PLG110 无关 | ➖ 不适用 |

**一句话结论**：
- **Sabre 降噪优化本仓并未覆盖**，且 a377 修了一个**确切断数值 bug**（QMC 角度位）。旧 `sabreAverageMergeFactor` 标量法被上游明确标注为 *diagnostic only*、不是 `GetMergedNoiseModel` 的输入——本仓当前正把它当噪声模型缩放器用，属于被上游删除的近似 hack。
- **Spatial 降噪算法（合成数学）本仓与上游完全等价**；a377 对 Spatial 只改了噪声模型传播的**控制流与容错**（静默降级→硬校验），不改任何去噪数学。
- 本仓的 `DemosaicNoisePropagationCalibrator` 是**独立子系统**（测 demosaic→YUV 噪声传播标定），**不等价于、也不覆盖**上游的 Sabre 合并模型；此前会议纪要中的「本仓已部分覆盖」判断需修正为「仅 Spatial 与 Demosaic 标定对齐，Sabre 合并模型仍落后」。

---

## 2. 上游 a377 到底改了什么

### 2.1 Sabre（核心优化）
1. **新增 `MgcSabreMergedNoiseModel.kt`（纯 Kotlin，+57 行，无 native）**
   - `merge(frames: List<Frame>, referenceSnr: Float): Output(read[3], shot[3], correlation[128])`
   - 逐帧取 `cameraRgbReadNoise/cameraRgbShotNoise`（已是曝光归一化），按 reference-residual 权重合成：
     - 每帧权重 `w = 1/n`（首帧 `1 - (1/n)(n-1) = 1/n`，等价统一权重）
     - `readSum[ch] += read_i[ch] * w²`；`shotSum[ch] += shot_i[ch] * w²`；`weightSum = 1`
     - `samples = 1/snrCorrection(refSnr)`；`correction = snrCorrection(refSnr)`
     - `read[ch] = readSum[ch] * correction`；`shot[ch] = (shotSum[ch]/weightSum) * correction`
     - `correlation = FloatArray(128){1f}`
   - `snrCorrection(referenceSnr)` 分段：`≤0.5→0.8`；`<4→线性(0.5,4,0.8,1)`；`<10→线性(4,10,1,0.7)`；`≥10→0.7`
2. **`GlesMgcRawSpatialStacker` Sabre 合并段**
   - 构造 `noiseFrames`（reference + 每帧 calibration 的 cameraRgb read/shot）
   - `mergedNoiseModel = MgcSabreMergedNoiseModel.merge(noiseFrames, kernelTuning.referenceSnr)`
   - `finishRawDenoiseSnr = checkNotNull(MgcSpatialMergeTuning.outputNoiseModelSnr(referenceSignal, merged.read[1], merged.shot[1]))`（从合并模型绿通道推导）
   - `RawStackResult(mgcDenoiseReadNoise=merged.read, mgcDenoiseShotNoise=merged.shot, mgcDenoiseCorrelation=merged.correlation, mgcDenoiseTuningSnr=finishRawDenoiseSnr, …)`，**删除** `mgcSabreNoiseModelScale`
3. **`MgcFullResolutionDenoise`**：`useSpatialModel || useSabreModel` 统一直接用 `metadata.mgcDenoiseReadNoise/ShotNoise`（**不再**用 `sabreNoiseModelScale` 缩放参考 Bayer 噪声）
4. **`RawDemosaicProcessor.sabreOutputNoiseMetadata`**：消费 `mgcDenoiseReadNoise/ShotNoise/Correlation`（合并模型），**不再** `null` 三字段、不再从 reference Bayer × scale 重算
5. **`RawMetadata` / `MultiFrameStacker.RawStackResult`**：**删除** `mgcSabreNoiseModelScale` 字段及 equals/hashCode
6. **`MgcSabreNoiseEstimatesLut`**：QMC 角度位 `Integer.reverse` → 新增 `qmcAngleBits()`（V25 `TransformNoiseModel`：XOR 渐进高位掩码）

### 2.2 Spatial（仅控制流/容错）
- `GlesMgcRawSpatialStacker` Spatial 段：
  - 旧：`resolvedSpatialNoiseModel ?: createIdentitySpatialNoiseModel(referenceCalibration, reason=…)`（**静默降级**到 identity）
  - 新：`spatialNoiseFrameCount==1 → identity`；否则 `checkNotNull(strengthCapture) / checkNotNull(queuedStrengthReadback) / checkNotNull(resolveSpatialNoiseModel(...))`（**强校验**）
  - `finishRawDenoiseSnr`：旧 `propagatedOutputSnr ?: MgcSpatialMergeTuning.mergedSnr(...)`（fallback）；新 `if (spatialDenoiseEnabled) checkNotNull(propagatedOutputSnr){…} else mergedSnr(...)`
  - `writeSpatialStrengthCapture`：删除 `usedIdentity` 静默回退，改为 `check(globalFrameWeight/kernelSigma 有限且 >0)`
  - `spatialNoiseParameters`：删除 `fallbackChannel/fallbackRead/fallbackShot`，改为 `check(cameraRgbReadNoise.size==3 && shot.size==3 && (0..2).all(validPair))`
  - `createIdentitySpatialNoiseModel` 签名去掉 `reason` 参数
  - 删除常量 `SPATIAL_IDENTITY_READ_NOISE=0f` / `SPATIAL_IDENTITY_SHOT_NOISE=1f`
- `MgcSpatialStrengthMapGenerator`：增加输入校验 `require`（read/shot 有限非负且有信号；frameWeights>0；kernelSigmas>0）

---

## 3. 本仓现状逐项比对（含代码定位）

### 3.1 Sabre —— 确缺失
- `GlesMgcRawSpatialStacker.kt:2357` `val sabreNoiseModelScale = sabreAverageMergeFactor`
- `GlesMgcRawSpatialStacker.kt:2580-2581` `val finishRawDenoiseSnr = kernelTuning.referenceSnr / sqrt(sabreNoiseModelScale)`
- `GlesMgcRawSpatialStacker.kt:2615` `mgcSabreNoiseModelScale = sabreNoiseModelScale`
- `MgcFullResolutionDenoise.kt:257-275` `rgbShot = resolvedNoise.shot * sabreNoiseModelScale`（resolvedNoise = 参考 Bayer 噪声）
- `RawDemosaicProcessor.kt:228,2479,5845` `mgcSabreNoiseModelScale` 传递/消费
- `RawMetadata.kt:184,1187,1225` `mgcSabreNoiseModelScale` 字段
- `MultiFrameStacker.kt:146` `val mgcSabreNoiseModelScale: Float? = null`
- `GalleryManager.kt:3193,3218,3292` 诊断引用
- **QMC bug**：`MgcSabreNoiseEstimatesLut.kt:103` `val reversed = Integer.reverse(index).toUInt()…`（上游改为 `qmcAngleBits(index)`）

### 3.2 Spatial —— 数学等价、控制流落后
- `GlesMgcRawSpatialStacker.kt:1845-1863` 旧 `resolvedSpatialNoiseModel ?: createIdentitySpatialNoiseModel(…, reason=…)`（静默降级）
- `GlesMgcRawSpatialStacker.kt:1941-1945` 旧 `finishRawDenoiseSnr = propagatedOutputSnr ?: mergedSnr(...)`（fallback）
- `GlesMgcRawSpatialStacker.kt:5784-5809` `usedIdentity` 静默回退到 `SPATIAL_IDENTITY_MULTIPLIER`
- `GlesMgcRawSpatialStacker.kt:5823-5834` `fallbackChannel/fallbackRead/fallbackShot` + `SPATIAL_IDENTITY_READ_NOISE/SHOT_NOISE`
- `GlesMgcRawSpatialStacker.kt:5838-5841` `createIdentitySpatialNoiseModel(referenceCalibration, reason)`
- `MgcSpatialStrengthMapGenerator.kt:81-84` 仅基础 `valid`，**无**上游新增的 `require` 校验
- **核心合成数学**：a377 未改动任何 Spatial GL 着色器或 native `mgc_denoise_static` 计算逻辑 → 本仓 Spatial 去噪输出（合法输入下）与上游一致。

### 3.3 独立子系统澄清
- 本仓 `DemosaicNoisePropagationCalibrator`（GL 标定测 demosaic→YUV 噪声传播）产出 `mgcDenoiseReadNoise/ShotNoise/Correlation`，用于 **demosaic 噪声金字塔传播**，与 Sabre 合并模型是**两条不同链路**。
- 该标定子系统的 `FloatArray(128){1f}` fallback 与上游 `MgcSabreMergedNoiseModel.correlation=FloatArray(128){1f}` 只是**巧合同值**，语义不同（前者是「无传播谱时用单位谱」，后者是「物理 RAW 输入无二次谱，加权合成保持单位谱」）。**不能据此认为 Sabre 合并已被覆盖。**

---

## 4. 数学辨析：旧标量法 vs V25 合并模型

**旧法（本仓当前）**：
- `merged ≈ 参考帧 Bayer 噪声 × sabreAverageMergeFactor`
- `sabreAverageMergeFactor` = 平均 `256 / accumulated_green_weight_q8` —— 上游 `GlesMgcRawSabreShaders` 注释明确：**"This is diagnostic only; classic Sabre builds its merged NoiseModel from the exposure-normalized frame models."**
- 单标量 uniform 缩放，**无法表达逐通道合并差异**；且把诊断量当模型输入是近似。

**V25 法（上游 a377）**：
- 逐帧相机-RGB read/shot（已曝光归一化）按 `1/n²` 权重合成 + `snrCorrection(refSnr)` 分段修正 → 逐通道合并 read/shot。
- 正确处理**多帧/不同曝光**的噪声合成；合并模型直接喂 `MgcFullResolutionDenoise` 与 FinishRaw SNR 选择。

**结论**：多帧或曝光差异明显时，旧法噪声模型偏差显著；单帧且各帧噪声相近时二者接近（scale≈1）。V25 更精确，是 a377「优化 Sabre 降噪效果」的真正落点。

---

## 5. 定点移植建议（按价值/风险排序）

### P0 — 必做，低风险高价值
- **修 QMC 角度位 bug**：`MgcSabreNoiseEstimatesLut` 新增 `qmcAngleBits(index)`（见 2.1/6 参考实现），替换 `Integer.reverse(index)`。纯函数 11 行，零风险，直接提升 Sabre Bayer 引导生成质量。

### P1 — 核心，中风险，纯 Kotlin + schema
1. 新增 `MgcSabreMergedNoiseModel.kt`（纯 Kotlin，无 native 改动）。
2. 改 `GlesMgcRawSpatialStacker` Sabre 合并段：构造 `noiseFrames`、调用 `merge()`、`finishRawDenoiseSnr = outputNoiseModelSnr(referenceSignal, merged.read[1], merged.shot[1])`、`RawStackResult` 改填 `mgcDenoiseReadNoise/ShotNoise/Correlation`，删除 `mgcSabreNoiseModelScale`。
3. 改 `MgcFullResolutionDenoise`：`useSpatialModel || useSabreModel` 分支直接用 `metadata.mgcDenoiseReadNoise/ShotNoise`，删除 `sabreNoiseModelScale` 缩放分支。
4. 改 `RawDemosaicProcessor.sabreOutputNoiseMetadata`：消费合并模型，不再 `null` 三字段 / 不再从 reference Bayer × scale 重算。
5. 改 `RawMetadata`：删除 `mgcSabreNoiseModelScale` 字段及 equals/hashCode；`MultiFrameStacker.RawStackResult` 同步；`GalleryManager` 去掉 3 处引用。
6. 验证：`compileDebugKotlin` + 真机 Sabre 单帧/多帧出片对比（重点：暗部噪点、亮部过降）。

### P2 — 加固，低风险（可选）
- `MgcSpatialStrengthMapGenerator` 补参数校验 `require`（与上游一致）。
- `GlesMgcRawSpatialStacker` `writeSpatialStrengthCapture`/`spatialNoiseParameters` 由静默降级改 `check`；`createIdentitySpatialNoiseModel` 去 `reason`；删除 `SPATIAL_IDENTITY_READ_NOISE/SHOT_NOISE`。
- ⚠️ 注意：P2 会把「静默降级」变「硬失败」，可能暴露本仓当前隐藏的边界问题，需真机回归。

### 不适用（跳过）
- cbabd7fe77 设备配置子系统（vendor 专属；本仓用 `UserPreferencesRepository` 的 `MgcMergeMethod.SABRE/SPATIAL`）。
- ee0c051ca3 Pixel5 噪声 profile（`AGC Pixel5` 设备，PLG110 无关）。

---

## 6. 风险与验证

- P1 涉及 schema 字段删除 + 6+ 调用点，必须全量 `compileDebugKotlin` + 真机 Sabre 单/多帧出片，与 1.6.x 噪声观感对比（暗部噪点、亮部过降、色彩噪声）。
- 本次改动只改**合并数学与控制流**，不改噪声模型来源（仍来自 DCP/RAW profile JSON）→ **不破坏现有 JSON 参数行为**。
- P0 单独可发（低风险），建议随 P1 一起进 1.7.1。

---

## 7. 下一步

可直接执行 **P0（QMC bug 修复）+ P1（Sabre 合并模型移植）**，编译验证 + 真机回归后单独发版（建议 1.7.1）。P2 视回归稳定性再定。是否现在执行？

---

## 附录 A：QMC `qmcAngleBits` 参考实现（上游 V25）

```kotlin
/** V25 TransformNoiseModel (0x5ee7524): XOR progressively filled high-bit masks. */
internal fun qmcAngleBits(index: Int): UInt {
    require(index >= 0)
    var remaining = index
    var mask = 0x80000000u
    var bits = 0u
    while (remaining != 0) {
        if ((remaining and 1) != 0) bits = bits xor mask
        mask = mask or (mask shr 1)
        remaining = remaining ushr 1
    }
    return bits
}
// 调用处：val phase = (qmcAngleBits(index).toLong().toDouble() / UINT32_RANGE).toFloat()
```

## 附录 B：上游 a377 受影响文件清单（18）

```
modified  .gitignore                                    +0/-7
modified  cpp/mgc_denoise_static/mgc_denoise_static.cpp  +11/-0
modified  cpp/mgc_denoise_static/mgc_denoise_static.h    +3/-1
modified  cpp/mgc_denoise_static/mgc_spatial_strength_jni.cpp  +37/-64
modified  gallery/GalleryManager.kt                      +3/-6
modified  processor/GlesMgcRawSabreShaders.kt            +2/-3
modified  processor/GlesMgcRawSpatialStacker.kt          +69/-67
added     processor/MgcSabreMergedNoiseModel.kt         +57/-0
modified  processor/MgcSabreNoiseEstimatesLut.kt        +16/-3
modified  processor/MgcSpatialMergeTuning.kt             +3/-3
modified  processor/MgcSpatialStrengthMapGenerator.kt    +9/-1
modified  processor/MultiFrameStacker.kt                 +4/-11
modified  processor/PhotonCoreImagingTuning.kt           +5/-1
modified  raw/MgcFullResolutionDenoise.kt                +11/-32
modified  raw/RawDemosaicProcessor.kt                    +11/-25
modified  raw/RawMetadata.kt                             +3/-7
added     test/.../MgcSabreMergedNoiseModelTest.kt      +47/-0
modified  test/.../MgcSabreNoiseEstimatesLutTest.kt     +14/-5
```
