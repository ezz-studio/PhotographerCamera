/**
 * GLSL helpers: #include resolution (GLSL has no native preprocessor) and program
 * compilation. Include paths are resolved relative to the `assets/shaders` folder.
 *
 * Convention: every fragment shader starts with `#version 300 es` and `#include`s
 * only from `*.glsl` library files (which intentionally omit the version directive).
 */
package com.photographercamera.core.gpu

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLUtils

object GLSL {
    /** Resolve `#include "file.glsl"` against the shaders asset directory (depth-first). */
    fun resolveIncludes(src: String, assets: AssetManager, seen: MutableSet<String> = mutableSetOf()): String {
        val out = StringBuilder()
        for (line in src.lines()) {
            val m = Regex("""^\s*#include\s+"([^"]+)"""").find(line)
            if (m != null) {
                val path = "shaders/${m.groupValues[1]}"
                if (seen.add(path)) {
                    val inc = assets.open(path).bufferedReader().use { it.readText() }
                    out.append(resolveIncludes(inc, assets, seen)).append('\n')
                }
            } else {
                out.append(line).append('\n')
            }
        }
        return hoistExtensions(out.toString())
    }

    /**
     * GLSL spec + ARM Mali strictness: `#extension` must occur before any
     * non-preprocessor tokens (only `#version` may precede it) - ANGLE on the
     * emulator silently accepts it anywhere, Mali fails with "P0001" (the
     * real-device black-preview bug). Hoist every #extension to directly after
     * #version so comments or included files can never reorder them again.
     */
    private fun hoistExtensions(src: String): String {
        val lines = src.lines()
        if (lines.none { it.trimStart().startsWith("#extension") }) return src
        val exts = lines.filter { it.trimStart().startsWith("#extension") }
        val rest = lines.filterNot { it.trimStart().startsWith("#extension") }
        val verIdx = rest.indexOfFirst { it.trimStart().startsWith("#version") }
        val out = StringBuilder()
        rest.forEachIndexed { i, line ->
            out.append(line).append('\n')
            if (i == verIdx) exts.forEach { out.append(it).append('\n') }
        }
        return out.toString()
    }

    fun compile(type: Int, source: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, source)
        GLES30.glCompileShader(sh)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            val kind = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            // real-device GPU drivers (Adreno/Mali) reject different constructs than
            // the emulator's ANGLE - the info log is the ONLY way to know what to fix
            com.photographercamera.core.debug.DebugLog.log(
                "GL",
                "SHADER COMPILE FAILED ($kind): $log | source head: ${source.take(200).replace('\n', ' ')}",
            )
            GLES30.glDeleteShader(sh)
            throw RuntimeException("Shader compile failed:\n$log\n--- source head ---\n${source.take(400)}")
        }
        // non-fatal driver warnings are still valuable diagnostics
        val warn = GLES30.glGetShaderInfoLog(sh)
        if (warn.isNotBlank()) {
            com.photographercamera.core.debug.DebugLog.log("GL", "shader warning: ${warn.trim()}")
        }
        return sh
    }

    fun link(vs: Int, fs: Int): Int {
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            com.photographercamera.core.debug.DebugLog.log("GL", "PROGRAM LINK FAILED: $log")
            GLES30.glDeleteProgram(prog)
            throw RuntimeException("Program link failed:\n$log")
        }
        return prog
    }

    /** Build a program from an assets/ vertex + fragment file (fragment includes resolved). */
    fun program(assets: AssetManager, vertAsset: String, fragAsset: String): Int {
        val vsSrc = assets.open(vertAsset).bufferedReader().use { it.readText() }
        val fsRaw = assets.open(fragAsset).bufferedReader().use { it.readText() }
        val fsSrc = resolveIncludes(fsRaw, assets)
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        return link(vs, fs)
    }
}
