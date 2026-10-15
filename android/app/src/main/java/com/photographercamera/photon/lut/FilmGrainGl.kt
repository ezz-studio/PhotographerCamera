package com.photographercamera.photon.lut

import android.opengl.GLES30
import com.photographercamera.core.photon.color.FilmParamsStore
import com.photographercamera.photon.utils.PLog
import kotlin.math.abs

internal data class FilmGrainOutput(
    val textureId: Int,
    val framebufferId: Int,
)

/** Owns one context-local output target for the optional final film-grain pass. */
internal class FilmGrainGl(
    private val logTag: String,
) {
    private var programId = 0
    private var inputLocation = -1
    private var amountLocation = -1
    private var frameSeedLocation = -1
    private var pixelScaleLocation = -1
    private var lumaContrastLocation = -1
    private var sizeLocation = -1
    private var densityLocation = -1
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var outputWidth = 0
    private var outputHeight = 0
    // Phase E-1：输出目标格式跟随调用方链（成片 16F 链 → RGBA16F；其余 8-bit）。
    // 尺寸缓存命中判断必须包含本标志，否则 16F/8-bit 交替调用会复用错格式目标。
    private var outputIs16F = false

    fun prepare(): Boolean = ensureProgram()

    fun renderToTexture(
        sourceTextureId: Int,
        width: Int,
        height: Int,
        amount: Float,
        frameSeed: Float,
        drawQuad: (Int) -> Unit,
        use16F: Boolean = false,
        grainSize: Float? = null,
        grainDensity: Float? = null,
        grainLumaContrast: Float? = null,
    ): FilmGrainOutput? {
        if (abs(amount) <= ENABLE_EPSILON) return null
        if (!ensureProgram() || !ensureOutput(width, height, use16F)) return null
        if (!drawToFramebuffer(
                targetFramebufferId = outputFramebufferId,
                sourceTextureId = sourceTextureId,
                width = width,
                height = height,
                amount = amount,
                frameSeed = frameSeed,
                drawQuad = drawQuad,
                grainSize = grainSize,
                grainDensity = grainDensity,
                grainLumaContrast = grainLumaContrast,
            )
        ) {
            return null
        }
        return FilmGrainOutput(outputTextureId, outputFramebufferId)
    }

    fun drawToFramebuffer(
        targetFramebufferId: Int,
        sourceTextureId: Int,
        width: Int,
        height: Int,
        amount: Float,
        frameSeed: Float,
        drawQuad: (Int) -> Unit,
        grainSize: Float? = null,
        grainDensity: Float? = null,
        grainLumaContrast: Float? = null,
    ): Boolean {
        if (abs(amount) <= ENABLE_EPSILON) return false
        if (!ensureProgram()) return false
        val upstreamError = GLES30.glGetError()
        if (upstreamError != GLES30.GL_NO_ERROR) {
            PLog.e(logTag, "Film grain skipped after upstream glError $upstreamError")
            return false
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(inputLocation, 0)
        GLES30.glUniform1f(amountLocation, amount.coerceIn(0f, 1f))
        GLES30.glUniform1f(frameSeedLocation, frameSeed)
        GLES30.glUniform1f(pixelScaleLocation, FilmGrainShaders.pixelScale(width, height))
        // grain 参数优先取调用方入参（成片端传快门快照值，Phase B 闭环）；
        // 未传时回退 FilmParamsStore 单例（预览/录像路径的既有行为）。
        GLES30.glUniform1f(lumaContrastLocation, grainLumaContrast ?: FilmParamsStore.current.grainLumaContrast)
        GLES30.glUniform1f(sizeLocation, grainSize ?: FilmParamsStore.current.grainSize)
        GLES30.glUniform1f(densityLocation, grainDensity ?: FilmParamsStore.current.grainDensity)
        drawQuad(programId)
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        if (error != GLES30.GL_NO_ERROR) {
            PLog.e(logTag, "Film grain pass failed: glError=$error")
            return false
        }
        return true
    }

    fun resetAfterContextLoss() {
        programId = 0
        inputLocation = -1
        amountLocation = -1
        frameSeedLocation = -1
        pixelScaleLocation = -1
        lumaContrastLocation = -1
        sizeLocation = -1
        densityLocation = -1
        outputTextureId = 0
        outputFramebufferId = 0
        outputWidth = 0
        outputHeight = 0
        outputIs16F = false
    }

    fun release() {
        GlUtils.deleteProgram(programId)
        programId = 0
        releaseOutput()
    }

    private fun ensureProgram(): Boolean {
        if (programId != 0) return true
        val vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, Shaders.SIMPLE_VERTEX_SHADER)
        val fragmentShader = GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, FilmGrainShaders.FRAGMENT)
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GLES30.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES30.glDeleteShader(fragmentShader)
            PLog.e(logTag, "Failed to compile film grain shaders")
            return false
        }
        programId = GlUtils.linkProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (programId == 0) {
            PLog.e(logTag, "Failed to link film grain program")
            return false
        }
        inputLocation = GLES30.glGetUniformLocation(programId, "uInputTexture")
        amountLocation = GLES30.glGetUniformLocation(programId, "uAmount")
        frameSeedLocation = GLES30.glGetUniformLocation(programId, "uFrameSeed")
        pixelScaleLocation = GLES30.glGetUniformLocation(programId, "uPixelScale")
        lumaContrastLocation = GLES30.glGetUniformLocation(programId, "uGrainLumaContrast")
        sizeLocation = GLES30.glGetUniformLocation(programId, "uGrainSize")
        densityLocation = GLES30.glGetUniformLocation(programId, "uGrainDensity")
        return true
    }

    private fun ensureOutput(width: Int, height: Int, use16F: Boolean): Boolean {
        if (
            outputTextureId != 0 &&
            outputFramebufferId != 0 &&
            outputWidth == width &&
            outputHeight == height &&
            outputIs16F == use16F
        ) {
            return true
        }
        val maxTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        if (width <= 0 || height <= 0 || width > maxTextureSize[0] || height > maxTextureSize[0]) {
            PLog.e(logTag, "Film grain target ${width}x$height exceeds GL_MAX_TEXTURE_SIZE=${maxTextureSize[0]}")
            return false
        }

        releaseOutput()
        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        outputTextureId = texture[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTextureId)
        // Phase E-1：成片 16F 链（use16F）下颗粒输出 RGBA16F——避免链尾第二次
        // 8-bit 量化把前序 16F 精度抹掉；8-bit 链保持 RGBA8 不变。
        GLES30.glTexStorage2D(
            GLES30.GL_TEXTURE_2D,
            1,
            if (use16F) GLES30.GL_RGBA16F else GLES30.GL_RGBA8,
            width,
            height
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        outputFramebufferId = framebuffer[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            outputTextureId,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE || error != GLES30.GL_NO_ERROR) {
            PLog.e(logTag, "Film grain framebuffer incomplete: status=$status glError=$error")
            releaseOutput()
            return false
        }
        outputWidth = width
        outputHeight = height
        outputIs16F = use16F
        return true
    }

    private fun releaseOutput() {
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
            outputFramebufferId = 0
        }
        if (outputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
            outputTextureId = 0
        }
        outputWidth = 0
        outputHeight = 0
        outputIs16F = false
    }

    private companion object {
        const val ENABLE_EPSILON = 0.001f
    }
}
